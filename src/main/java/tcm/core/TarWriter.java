package tcm.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/**
 * Creating TAR, TAR.GZ, TAR.BZ2 and TAR.XZ archives. A compressed TAR cannot be appended to, so the archive is always written anew:
 * into a temporary file next to it, which replaces the target only when everything is packed.
 */
public final class TarWriter {

    private final @NotNull Path archive;
    private final @NotNull ArchiveFormat format;
    private final boolean recursive;
    private final @NotNull Progress progress;
    private final Map<Path, ArchiveReader> readers = new HashMap<>();
    /** What got into the archive, files before their directories: for "move to archive". */
    @Getter
    private final List<VFile> packed = new ArrayList<>();

    private TarWriter(final @NotNull Path archive, final @NotNull ArchiveFormat format, final boolean recursive, final @NotNull Progress progress) {
        this.archive = archive;
        this.format = format;
        this.recursive = recursive;
        this.progress = progress;
    }

    /** Packs the sources into the archive root; returns the packed files and directories (directories after their contents). */
    public static @NotNull List<VFile> pack(
        final @NotNull Path archive,
        final @NotNull ArchiveFormat format,
        final @NotNull List<VFile> sources,
        final boolean recursive,
        final @NotNull Progress progress
    ) throws IOException {
        final Path target = archive.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        final TarWriter writer = new TarWriter(target, format, recursive, progress);
        final Path tmp = Vfs.siblingTemp(target);
        try {
            try (TarArchiveOutputStream out = writer.open(tmp)) {
                for (final VFile src : sources) {
                    writer.add(out, src, src.getName());
                }
                out.finish();
            } finally {
                writer.closeReaders();
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
            Vfs.invalidate(target);
        }

        return writer.packed;
    }

    private @NotNull TarArchiveOutputStream open(final @NotNull Path tmp) throws IOException {
        final OutputStream file = new BufferedOutputStream(Files.newOutputStream(tmp));
        final OutputStream compressed;
        try {
            compressed = switch (format) {
                case TAR_GZ -> new GzipCompressorOutputStream(file);
                case TAR_BZ2 -> new BZip2CompressorOutputStream(file);
                case TAR_XZ -> new XZCompressorOutputStream(file);
                case TAR -> file;
                default -> throw new IOException("Not a TAR format: " + format);
            };
        } catch (final IOException e) {
            file.close();
            throw e;
        }
        final TarArchiveOutputStream out = new TarArchiveOutputStream(compressed, "UTF-8");
        out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        out.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        out.setAddPaxHeadersForNonAsciiNames(true);

        return out;
    }

    private void add(final @NotNull TarArchiveOutputStream out, final @NotNull VFile src, final @NotNull String entry) throws IOException {
        if (src.isLocal() && Vfs.isSameFile(src.getHostPath(), archive)) {
            throw new IOException("Cannot pack archive into itself: " + archive);
        }
        progress.bytes(0);
        final TarArchiveEntry e = new TarArchiveEntry(src.isDirectory() ? entry + "/" : entry);
        e.setModTime(src.getModified());
        e.setMode((src.isDirectory() ? TarArchiveEntry.DEFAULT_DIR_MODE & ~0777 : TarArchiveEntry.DEFAULT_FILE_MODE & ~0777)
            | FileAttrs.mode(src.getAttrs(), src.isDirectory()));
        if (src.isDirectory()) {
            out.putArchiveEntry(e);
            out.closeArchiveEntry();
            // a link to a directory is packed as an empty directory: following it could loop forever
            if (recursive && !(src.isLocal() && Files.isSymbolicLink(src.getHostPath()))) {
                for (final VFile child : Vfs.list(src)) {
                    add(out, child, entry + "/" + child.getName());
                }
            }
            packed.add(src);

            return;
        }
        e.setSize(src.getSize());
        out.putArchiveEntry(e);
        progress.file(src.getLocation(), src.getSize());
        try (InputStream in = open(src)) {
            final long copied = Streams.copy(in, out, progress);
            if (copied != src.getSize()) {
                throw new IOException("File changed while packing: " + src.getLocation());
            }
        }
        out.closeArchiveEntry();
        packed.add(src);
    }

    private @NotNull InputStream open(final @NotNull VFile src) throws IOException {
        if (src.isLocal()) {
            return Files.newInputStream(src.getHostPath());
        }
        ArchiveReader reader = readers.get(src.getHostPath());
        if (reader == null) {
            reader = Vfs.openReader(src.getHostPath());
            readers.put(src.getHostPath(), reader);
        }

        return reader.open(src.getEntry());
    }

    private void closeReaders() {
        for (final ArchiveReader r : readers.values()) {
            try {
                r.close();
            } catch (final IOException e) {
                // only reading: nothing is lost
            }
        }
        readers.clear();
    }
}
