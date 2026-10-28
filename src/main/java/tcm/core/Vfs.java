package tcm.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
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
 * Virtual FS: uniform access to local directories and archive contents (ZIP/JAR, TAR, RAR).
 */
public final class Vfs {

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "wbmp");

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Map<Path, ArchiveIndex> ARCHIVES = new ConcurrentHashMap<>();
    /** Archives unpacked from other archives: temporary copy → the entry it came from. */
    private static final Map<Path, Nested> NESTED = new ConcurrentHashMap<>();

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
                if (nested != null) {
                    // from the root of a nested archive — into the directory of the outer archive where it lies
                    return archiveDir(nested.origin.getHostPath(), ArchiveIndex.parentOf(nested.origin.getEntry()));
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

    /** Only ZIP/JAR can be modified; for other formats a write operation is rejected before it starts. */
    public static void checkWritable(final @NotNull Path archive) throws IOException {
        if (NESTED.containsKey(archive.toAbsolutePath().normalize())) {
            throw new IOException("Archive inside an archive is read-only: " + archive.getFileName());
        }
        final ArchiveFormat format = Files.exists(archive) && Files.size(archive) > 0
            ? index(archive).getFormat()
            : ArchiveFormat.of(String.valueOf(archive.getFileName()));
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
        final ArchiveIndex index = ArchiveIndex.load(key);
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
        final Path dir = Files.createTempDirectory("tcm-nested-");
        final Path tmp = dir.resolve(entry.getName());
        // deleteOnExit deletes in reverse order: the file first, then its directory
        dir.toFile().deleteOnExit();
        tmp.toFile().deleteOnExit();
        try {
            try (InputStream in = openInput(entry)) {
                Files.copy(in, tmp);
            }
            if (ArchiveFormat.forFile(tmp) == null) {
                deleteNested(tmp);

                return null;
            }
        } catch (final IOException | RuntimeException e) {
            deleteNested(tmp);
            throw e;
        }
        NESTED.put(tmp, new Nested(entry));

        return tmp;
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
