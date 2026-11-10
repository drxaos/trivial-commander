package tcm.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.util.TextUtil;

/**
 * Virtual FS: uniform access to local directories and archive contents (ZIP/JAR, TAR, RAR, 7z, ISO, squashfs/AppImage).
 */
public final class Vfs {

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "wbmp", "ico", "icns", "svg", "svgz");

    private static final int COPY_BUFFER = 1 << 20;
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Map<Path, ArchiveIndex> ARCHIVES = new ConcurrentHashMap<>();
    /** Archives unpacked from other archives: temporary copy → the entry it came from. */
    private static final Map<Path, Nested> NESTED = new ConcurrentHashMap<>();
    /**
     * Archives found inside other files (see EmbeddedArchives) → their format, which neither the name nor the signature at the start
     * gives: a file with a ZIP appended (read in place) or the temporary copy of the archive part.
     */
    private static final Map<Path, ArchiveFormat> EMBEDDED = new ConcurrentHashMap<>();

    private Vfs() {
    }

    /** Parses a path string ("/a/b", "/a/b.zip", "/a/b.zip!/dir") into a virtual FS directory. */
    public static @NotNull VFile resolve(final @NotNull String location) throws IOException {
        final int idx = location.indexOf(VFile.ARCHIVE_SEPARATOR);
        if (idx >= 0) {
            final Path archive = Path.of(location.substring(0, idx)).toAbsolutePath().normalize();

            return archiveDir(archive, ArchiveIndex.normalize(location.substring(idx + VFile.ARCHIVE_SEPARATOR.length())));
        }
        final Path path = Path.of(location.endsWith("!") ? location.substring(0, location.length() - 1) : location)
            .toAbsolutePath()
            .normalize();
        final VFile file = stat(path);
        if (file.isArchiveFile()) {
            return archiveDir(path, "");
        }
        if (!file.isDirectory()) {
            throw new NotDirectoryException(location);
        }

        return file;
    }

    /** File or directory by full path (including "archive.zip!/dir/file"); an archive is not opened as a directory. */
    public static @NotNull VFile file(final @NotNull String location) throws IOException {
        final int idx = location.indexOf(VFile.ARCHIVE_SEPARATOR);
        if (idx < 0) {
            return stat(Path.of(location).toAbsolutePath().normalize());
        }
        final Path archive = Path.of(location.substring(0, idx)).toAbsolutePath().normalize();
        final String entry = ArchiveIndex.normalize(location.substring(idx + VFile.ARCHIVE_SEPARATOR.length()));
        final VFile f = index(archive).get(entry);
        if (f == null) {
            throw new NoSuchFileException(location);
        }

        return f;
    }

    public static @NotNull VFile archiveDir(final @NotNull Path archive, final @NotNull String entry) throws IOException {
        final VFile dir = index(archive).get(entry);
        if (dir == null) {
            throw new NoSuchFileException(archive + VFile.ARCHIVE_SEPARATOR + entry);
        }
        if (!dir.isDirectory()) {
            throw new NotDirectoryException(dir.getLocation());
        }

        return dir;
    }

    public static @NotNull VFile stat(final @NotNull Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(path.toString());
        }
        final String name = String.valueOf(path.getFileName());
        if (POSIX) {
            final PosixFileAttributes attrs = readAttributes(path, PosixFileAttributes.class);
            final String perms = PosixFilePermissions.toString(attrs.permissions());

            return VFile.local(path, attrs.isDirectory(), attrs.size(), attrs.lastModifiedTime().toMillis(), perms, name.startsWith("."));
        }
        final DosFileAttributes attrs = readAttributes(path, DosFileAttributes.class);
        final String flags = FileAttrs.dos(attrs.isReadOnly(), attrs.isArchive(), attrs.isHidden(), attrs.isSystem());

        return VFile.local(path, attrs.isDirectory(), attrs.size(), attrs.lastModifiedTime().toMillis(), flags, attrs.isHidden());
    }

    public static @NotNull List<VFile> list(final @NotNull VFile dir) throws IOException {
        if (dir.isInArchive()) {
            return new ArrayList<>(index(dir.getHostPath()).list(dir.getEntry()));
        }
        final List<VFile> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.getHostPath())) {
            for (final Path p : stream) {
                try {
                    result.add(stat(p));
                } catch (final IOException e) {
                    // broken link or no permission to read attributes — just don't show the file
                }
            }
        }

        return result;
    }

    /** Parent directory; for an archive root it is the directory containing the archive. */
    public static @Nullable VFile parent(final @NotNull VFile file) throws IOException {
        if (file.isInArchive()) {
            if (file.getEntry().isEmpty()) {
                final Nested nested = NESTED.get(file.getHostPath());
                if (nested != null && nested.origin.isInArchive()) {
                    // from the root of a nested archive — into the directory of the outer archive where it lies
                    return archiveDir(nested.origin.getHostPath(), ArchiveIndex.parentOf(nested.origin.getEntry()));
                }
                if (nested != null) {
                    // an archive cut out of a local file — into the directory of that file
                    final Path parent = nested.origin.getHostPath().getParent();

                    return parent == null ? null : stat(parent);
                }
                final Path parent = file.getHostPath().getParent();

                return parent == null ? null : stat(parent);
            }

            return archiveDir(file.getHostPath(), ArchiveIndex.parentOf(file.getEntry()));
        }
        final Path parent = file.getHostPath().getParent();

        return parent == null ? null : stat(parent);
    }

    public static @Nullable VFile findChild(final @NotNull VFile dir, final @NotNull String name) throws IOException {
        if (dir.isInArchive()) {
            return index(dir.getHostPath()).get(ArchiveIndex.join(dir.getEntry(), name));
        }
        final Path p = dir.getHostPath().resolve(name);

        return Files.exists(p, LinkOption.NOFOLLOW_LINKS) ? stat(p) : null;
    }

    public static @NotNull InputStream openInput(final @NotNull VFile file) throws IOException {
        if (file.isLocal()) {
            return Files.newInputStream(file.getHostPath());
        }
        final ArchiveReader reader = openReader(file.getHostPath());
        final InputStream in;
        try {
            in = reader.open(file.getEntry());
        } catch (final IOException e) {
            reader.close();
            throw e;
        }

        return new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    reader.close();
                }
            }
        };
    }

    public static byte[] readAll(final @NotNull VFile file) throws IOException {
        try (InputStream in = openInput(file)) {
            return in.readAllBytes();
        }
    }

    public static @NotNull ZipFile openZip(final @NotNull Path archive) throws IOException {
        return new ZipFile(archive.toFile(), index(archive).getCharset());
    }

    public static @NotNull ArchiveReader openReader(final @NotNull Path archive) throws IOException {
        return index(archive).openReader();
    }

    /**
     * Temporary file next to the target of an atomic rewrite (archives). It gets the target's permissions, a new file — the usual ones
     * (umask), not the 0600 of Files.createTempFile, which the archive would keep after the swap.
     */
    public static @NotNull Path siblingTemp(final @NotNull Path target) throws IOException {
        final Path dir = target.toAbsolutePath().getParent();
        for (int i = 0; ; i++) {
            final Path tmp = dir.resolve(".tcm-" + Long.toHexString(System.nanoTime()) + "-" + i + ".tmp");
            try {
                Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW).close();
            } catch (final FileAlreadyExistsException e) {
                continue;
            }
            if (POSIX && Files.exists(target)) {
                try {
                    Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target));
                } catch (final IOException e) {
                    // the archive is still written, only with the default permissions
                }
            }

            return tmp;
        }
    }

    /** Only ZIP/JAR can be modified; for other formats a write operation is rejected before it starts. */
    public static void checkWritable(final @NotNull Path archive) throws IOException {
        if (EMBEDDED.containsKey(archive.toAbsolutePath().normalize())) {
            throw new IOException("Archive inside a file is read-only: " + archive.getFileName());
        }
        if (NESTED.containsKey(archive.toAbsolutePath().normalize())) {
            throw new IOException("Archive inside an archive is read-only: " + archive.getFileName());
        }
        if (Files.exists(archive) && Files.size(archive) > 0) {
            final ArchiveIndex index = index(archive);
            if (index.isEncrypted()) {
                throw new IOException("Encrypted archive is read-only: " + archive.getFileName());
            }
            if (!index.isWritable()) {
                throw new IOException("Archive is read-only (only ZIP/JAR can be modified): " + archive.getFileName());
            }

            return;
        }
        final ArchiveFormat format = ArchiveFormat.of(String.valueOf(archive.getFileName()));
        if (format != null && !format.isWritable()) {
            throw new IOException("Archive is read-only (only ZIP/JAR can be modified): " + archive.getFileName());
        }
    }

    public static @NotNull ArchiveIndex index(final @NotNull Path archive) throws IOException {
        final Path key = archive.toAbsolutePath().normalize();
        final ArchiveIndex cached = ARCHIVES.get(key);
        if (cached != null && Files.exists(key) && cached.getArchiveModified() == Files.getLastModifiedTime(key).toMillis()
            && cached.getArchiveSize() == Files.size(key)) {
            return cached;
        }
        final ArchiveIndex index = ArchiveIndex.load(key, EMBEDDED.get(key));
        final ArchiveIndex old = ARCHIVES.put(key, index);
        if (old != null) {
            old.dispose();
        }

        return index;
    }

    public static void invalidate(final @NotNull Path archive) {
        final ArchiveIndex old = ARCHIVES.remove(archive.toAbsolutePath().normalize());
        if (old != null) {
            old.dispose();
        }
    }

    /**
     * Unpacks an archive lying inside another archive into a temporary file with the same name, to be opened as a directory.
     * null — the entry is not an archive (the temporary copy is already deleted). The copy is deleted by the last
     * {@link #releaseNested} after {@link #retainNested}.
     */
    public static @Nullable Path openNested(final @NotNull VFile entry) throws IOException {
        return openNested(entry, Progress.NONE);
    }

    /** The same with progress and cancellation (a partition of a disk image may take gigabytes). */
    public static @Nullable Path openNested(final @NotNull VFile entry, final @NotNull Progress progress) throws IOException {
        return openNested(entry, progress, false);
    }

    /**
     * The same; searchEmbedded — an entry that does not open as an archive is searched for an archive inside it
     * (Ctrl+PgDn, see {@link #openEmbedded}).
     */
    public static @Nullable Path openNested(
        final @NotNull VFile entry,
        final @NotNull Progress progress,
        final boolean searchEmbedded
    ) throws IOException {
        if (!mayBeNestedArchive(entry)) {
            return null;
        }
        final Path tmp = nestedTemp(entry.getName());
        try {
            progress.file(entry.getName(), entry.getSize());
            unpackNested(entry, tmp, progress);
            if (ArchiveFormat.forFile(tmp) == null || searchEmbedded && !opens(tmp)) {
                final Path found = searchEmbedded ? openEmbedded(entry, tmp, Progress.ticksOnly(progress)) : null;
                if (!tmp.equals(found)) {
                    deleteNested(tmp);
                }

                return found;
            }
        } catch (final IOException | RuntimeException e) {
            deleteNested(tmp);
            throw e;
        }
        NESTED.put(tmp, new Nested(entry));

        return tmp;
    }

    /**
     * A local file that does not open as an archive is searched for one starting in its first 10 MB (a self-extracting program,
     * a makeself installer): the path to open as an archive — the file itself for a ZIP (read in place), otherwise a temporary copy
     * of the archive part, deleted like a nested archive ({@link #releaseNested}); null — none found. Such an archive is read-only.
     */
    public static @Nullable Path openEmbedded(final @NotNull VFile file, final @NotNull Progress progress) throws IOException {
        return openEmbedded(file, file.getHostPath(), progress);
    }

    /** The archives found in host (the file itself or its unpacked copy) are tried by offset; the first that opens wins. */
    private static @Nullable Path openEmbedded(
        final @NotNull VFile origin,
        final @NotNull Path host,
        final @NotNull Progress progress
    ) throws IOException {
        for (final EmbeddedArchives.Found found : EmbeddedArchives.find(host)) {
            final Path path = found.getFormat() == ArchiveFormat.ZIP ? openEmbeddedZip(host) : cutEmbedded(origin, host, found, progress);
            if (path != null) {
                if (origin.isInArchive() || !path.equals(host)) {
                    NESTED.put(path, new Nested(origin));
                }

                return path;
            }
        }

        return null;
    }

    private static @Nullable Path openEmbeddedZip(final @NotNull Path host) {
        final Path key = host.toAbsolutePath().normalize();
        EMBEDDED.put(key, ArchiveFormat.ZIP);
        invalidate(key);
        if (opens(key)) {
            return key;
        }
        EMBEDDED.remove(key);

        return null;
    }

    /** Copies the archive part of host into a temporary file named as the origin; null — it does not open as an archive. */
    private static @Nullable Path cutEmbedded(
        final @NotNull VFile origin,
        final @NotNull Path host,
        final @NotNull EmbeddedArchives.Found found,
        final @NotNull Progress progress
    ) throws IOException {
        final Path tmp = nestedTemp(origin.getName());
        try {
            try (FileChannel in = FileChannel.open(host, StandardOpenOption.READ)) {
                final long length = found.getLength() < 0 ? in.size() - found.getOffset() : found.getLength();
                progress.file(origin.getName(), length);
                copyRange(in, found.getOffset(), length, tmp, progress);
            }
            // the signature at the start of the copy tells a tar.gz from a single .gz, an ISO from its system area
            final ArchiveFormat format = ArchiveFormat.detect(tmp);
            if (format != null) {
                EMBEDDED.put(tmp, format);
                if (opens(tmp)) {
                    return tmp;
                }
                EMBEDDED.remove(tmp);
            }
        } catch (final IOException | RuntimeException e) {
            EMBEDDED.remove(tmp);
            deleteNested(tmp);
            throw e;
        }
        deleteNested(tmp);

        return null;
    }

    private static void copyRange(
        final @NotNull FileChannel in,
        final long offset,
        final long length,
        final @NotNull Path target,
        final @NotNull Progress progress
    ) throws IOException {
        final ByteBuffer buf = ByteBuffer.allocate(COPY_BUFFER);
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long done = 0;
            while (done < length) {
                buf.clear().limit((int) Math.min(COPY_BUFFER, length - done));
                final int n = in.read(buf, offset + done);
                if (n < 0) {
                    break;
                }
                buf.flip();
                while (buf.hasRemaining()) {
                    out.write(buf);
                }
                done += n;
                progress.bytes(n);
            }
        }
    }

    /** The archive is read (its index is built and cached); an archive that fails to open is only no archive. */
    private static boolean opens(final @NotNull Path archive) {
        try {
            index(archive);

            return true;
        } catch (final IOException | RuntimeException e) {
            return false;
        }
    }

    /** A new temporary file path with the given name, in a directory of its own (several archives may have one name). */
    private static @NotNull Path nestedTemp(final @NotNull String name) throws IOException {
        final Path dir = Files.createTempDirectory("tcm-nested-");
        final Path tmp = dir.resolve(name);
        // deleteOnExit deletes in reverse order: the file first, then its directory
        dir.toFile().deleteOnExit();
        tmp.toFile().deleteOnExit();

        return tmp;
    }

    /**
     * False if the entry is known not to be an archive without unpacking it: a partition of a disk image with no supported file
     * system or partition table (a swap or UFS partition would otherwise be copied for nothing).
     */
    public static boolean mayBeNestedArchive(final @NotNull VFile entry) throws IOException {
        if (!entry.isInArchive()) {
            return true;
        }
        try (ArchiveReader reader = openReader(entry.getHostPath())) {
            final BlockSource source = reader.source(entry.getEntry());

            return source == null || DiskImages.recognizes(source);
        }
    }

    private static void unpackNested(final @NotNull VFile entry, final @NotNull Path tmp, final @NotNull Progress progress) throws IOException {
        try (ArchiveReader reader = openReader(entry.getHostPath())) {
            final BlockSource source = reader.source(entry.getEntry());
            if (source != null) {
                copySparse(source, tmp, progress);

                return;
            }
            try (InputStream in = reader.open(entry.getEntry()); OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW)) {
                final byte[] buf = new byte[COPY_BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    progress.bytes(n);
                }
            }
        }
    }

    /** Writes the bytes into a sparse file: unallocated and all-zero blocks are not written and take no disk space. */
    public static void copySparse(final @NotNull BlockSource source, final @NotNull Path target, final @NotNull Progress progress) throws IOException {
        final long size = source.size();
        final byte[] buf = new byte[COPY_BUFFER];
        final byte[] zeros = new byte[COPY_BUFFER];
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            long pos = 0;
            long written = 0;
            while (pos < size) {
                final long data = Math.max(pos, source.dataFrom(pos));
                if (data >= size) {
                    progress.bytes(size - pos);
                    break;
                }
                // whole buffers aligned to the buffer size: the holes of a virtual disk start at block boundaries
                final long from = data - data % COPY_BUFFER;
                if (from > pos) {
                    progress.bytes(from - pos);
                    pos = from;
                }
                final int n = (int) Math.min(COPY_BUFFER, size - pos);
                source.read(pos, buf, 0, n);
                if (Arrays.mismatch(buf, 0, n, zeros, 0, n) >= 0) {
                    final ByteBuffer b = ByteBuffer.wrap(buf, 0, n);
                    while (b.hasRemaining()) {
                        out.write(b, pos + b.position());
                    }
                    written = pos + n;
                }
                pos += n;
                progress.bytes(n);
            }
            if (written < size && size > 0) {
                // the file gets its full length even when it ends with a hole
                out.write(ByteBuffer.wrap(new byte[1]), size - 1);
            }
        }
    }

    /** Usable bytes in the directory of temporary files, where nested archives are unpacked; -1 — unknown. */
    public static long tempFreeSpace() {
        try {
            return Files.getFileStore(tempDir()).getUsableSpace();
        } catch (final IOException | RuntimeException e) {
            return -1;
        }
    }

    public static @NotNull Path tempDir() {
        return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
    }

    /** One more panel shows the nested archive. */
    public static void retainNested(final @NotNull Path archive) {
        final Nested nested = NESTED.get(archive);
        if (nested != null) {
            nested.refs.incrementAndGet();
        }
    }

    /** The panel left the nested archive; when nobody else shows it, the temporary copy is deleted. */
    public static void releaseNested(final @NotNull Path archive) {
        final Nested nested = NESTED.get(archive);
        if (nested != null && nested.refs.decrementAndGet() <= 0) {
            NESTED.remove(archive);
            invalidate(archive);
            deleteNested(archive);
        }
    }

    /** Temporary copies of the nested archives the location is inside of, from the innermost outwards. */
    public static @NotNull List<Path> nestedChain(final @NotNull VFile file) {
        final List<Path> chain = new ArrayList<>();
        Path host = file.isInArchive() ? file.getHostPath() : null;
        while (host != null) {
            final Nested nested = NESTED.get(host);
            if (nested == null) {
                break;
            }
            chain.add(host);
            host = nested.origin.getHostPath();
        }

        return chain;
    }

    /** Path for display: a nested archive is shown inside the outer one ("a.zip!/b.tar!/dir") instead of its temporary copy. */
    public static @NotNull String displayLocation(final @NotNull VFile file) {
        final Nested nested = file.isInArchive() ? NESTED.get(file.getHostPath()) : null;
        if (nested == null) {
            return file.getLocation();
        }

        return displayLocation(nested.origin) + VFile.ARCHIVE_SEPARATOR + file.getEntry();
    }

    /** A real file on disk: for a nested archive — the outermost archive it was unpacked from. */
    public static @NotNull Path diskPath(final @NotNull VFile file) {
        final List<Path> chain = nestedChain(file);

        return chain.isEmpty() ? file.getHostPath() : NESTED.get(chain.get(chain.size() - 1)).origin.getHostPath();
    }

    private static void deleteNested(final @NotNull Path tmp) {
        EMBEDDED.remove(tmp);
        try {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(tmp.getParent());
        } catch (final IOException e) {
            // left to deleteOnExit
        }
    }

    /**
     * Two paths lead to the same file. Besides equal paths, these are "a.txt" and "A.txt" on a case-insensitive FS (macOS, Windows),
     * hard and symbolic links: writing to such a "copy" would truncate the source.
     */
    public static boolean isSameFile(final @NotNull Path a, final @NotNull Path b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (final IOException e) {
            // file inaccessible — treat as different, the operation itself will report the error later
            return false;
        }
    }

    /**
     * Rename on disk. If only the case of the name changes on a case-insensitive FS, Files.move treats source
     * and target as one file and silently does nothing — then rename via a temporary name.
     */
    public static void rename(final @NotNull Path src, final @NotNull Path dst) throws IOException {
        if (!isCaseOnlyRename(src, dst)) {
            Files.move(src, dst);

            return;
        }
        final Path tmp = src.resolveSibling(".tcm-rename-" + System.nanoTime());
        Files.move(src, tmp);
        try {
            Files.move(tmp, dst);
        } catch (final IOException e) {
            Files.move(tmp, src);
            throw e;
        }
    }

    /**
     * Same directory, the name differs only in case, and the FS treats both paths as one file. Hard links "a" and "A"
     * on a case-sensitive FS are one file too, but they are two directory entries: they differ by an entry with the exact name existing.
     */
    public static boolean isCaseOnlyRename(final @NotNull Path src, final @NotNull Path dst) {
        final Path dir = src.getParent();
        final Path srcName = src.getFileName();
        final Path dstName = dst.getFileName();
        if (src.equals(dst) || dir == null || srcName == null || dstName == null || !dir.equals(dst.getParent())
            || !srcName.toString().equalsIgnoreCase(dstName.toString()) || !isSameFile(src, dst)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (final Path p : stream) {
                if (dstName.toString().equals(String.valueOf(p.getFileName()))) {
                    return false;
                }
            }
        } catch (final IOException e) {
            // directory not readable — a regular rename will report the error
            return false;
        }

        return true;
    }

    /** String "[label] X k of Y k free" for the panel above the table. */
    public static @NotNull String freeSpaceText(final @NotNull VFile dir) {
        try {
            final FileStore store = Files.getFileStore(dir.getHostPath());
            final String label = store.name().isEmpty() ? "_none_" : store.name();

            return "[" + label + "]  " + TextUtil.groupDigits(store.getUsableSpace() / 1024) + " k of "
                + TextUtil.groupDigits(store.getTotalSpace() / 1024) + " k free";
        } catch (final IOException e) {
            return "";
        }
    }

    private static final class Nested {

        private final @NotNull VFile origin;
        private final AtomicInteger refs = new AtomicInteger();

        private Nested(final @NotNull VFile origin) {
            this.origin = origin;
        }
    }

    private static <A extends BasicFileAttributes> @NotNull A readAttributes(final @NotNull Path path, final @NotNull Class<A> type)
        throws IOException {
        try {
            return Files.readAttributes(path, type);
        } catch (final IOException e) {
            // broken symbolic link: show the link itself
            return Files.readAttributes(path, type, LinkOption.NOFOLLOW_LINKS);
        }
    }
}
