package tcm.core;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * TAR, TAR.GZ, TAR.BZ2, TAR.XZ — read only. A compressed archive is unpacked once into a temporary .tar
 * (TC does the same): otherwise each entry would require decompressing the stream from the very beginning.
 */
public final class TarArchives {

    private TarArchives() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final Path unpacked = index.getFormat() == ArchiveFormat.TAR ? null : decompressToTemp(archive, index.getFormat());
        final TarFile tar;
        try {
            tar = new TarFile(unpacked != null ? unpacked : archive);
        } catch (final IOException | RuntimeException e) {
            deleteQuietly(unpacked);
            throw new IOException("Not a valid TAR archive: " + archive.getFileName() + " (" + e.getMessage() + ")", e);
        }
        final Map<String, TarArchiveEntry> byName = new HashMap<>();
        for (final TarArchiveEntry e : tar.getEntries()) {
            final boolean dir = e.isDirectory();
            index.add(e.getName(), dir, e.isFile() ? e.getSize() : 0, e.getModTime().getTime(), FileAttrs.posix(e.getMode()));
            if (!dir) {
                byName.put(ArchiveIndex.entryName(e.getName()), e);
            }
        }
        index.setTar(reader(tar, byName, archive, unpacked));
    }

    /** TAR stream on top of the decompressor — for archive testing (Alt+Shift+F9): compressed formats verify their checksum. */
    public static @NotNull TarArchiveInputStream openStream(final @NotNull Path archive, final @NotNull ArchiveFormat format) throws IOException {
        return new TarArchiveInputStream(decompressor(new BufferedInputStream(Files.newInputStream(archive)), format));
    }

    private static @NotNull ArchiveReader reader(
        final @NotNull TarFile tar,
        final @NotNull Map<String, TarArchiveEntry> byName,
        final @NotNull Path archive,
        final @Nullable Path unpacked
    ) {
        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final TarArchiveEntry e = byName.get(entry);
                if (e == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }
                // links and special files have no content
                return e.isFile() ? tar.getInputStream(e) : InputStream.nullInputStream();
            }

            @Override
            public void close() throws IOException {
                try {
                    tar.close();
                } finally {
                    deleteQuietly(unpacked);
                }
            }
        };
    }

    private static @NotNull Path decompressToTemp(final @NotNull Path archive, final @NotNull ArchiveFormat format) throws IOException {
        final Path tmp = Files.createTempFile("tcm-", ".tar");
        tmp.toFile().deleteOnExit();
        try (InputStream in = decompressor(new BufferedInputStream(Files.newInputStream(archive)), format);
             OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        } catch (final IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw new IOException("Cannot decompress " + archive.getFileName() + ": " + e.getMessage(), e);
        }

        return tmp;
    }

    private static @NotNull InputStream decompressor(final @NotNull InputStream raw, final @NotNull ArchiveFormat format) throws IOException {
        switch (format) {
            case TAR_GZ:
                return new GzipCompressorInputStream(raw, true);
            case TAR_BZ2:
                return new BZip2CompressorInputStream(raw, true);
            case TAR_XZ:
                return new XZCompressorInputStream(raw, true);
            default:
                return raw;
        }
    }

    private static void deleteQuietly(final @Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (final IOException e) {
            // left to deleteOnExit
        }
    }
}
