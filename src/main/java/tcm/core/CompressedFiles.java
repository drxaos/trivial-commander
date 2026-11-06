package tcm.core;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jetbrains.annotations.NotNull;
import org.tukaani.xz.SeekableFileInputStream;
import org.tukaani.xz.SeekableXZInputStream;

/**
 * A single compressed file (not a TAR): .gz, .bz2, .xz — read only. Shown as an archive with one entry named as the archive
 * without its extension. The content is decompressed on each read; the checksums are verified at the end of the stream.
 * The size: gzip keeps it in the trailer (modulo 4 GiB, of the last member), xz in the index of each stream; bzip2 does not
 * keep it at all, so a .bz2 is decompressed once when opened just to count it.
 */
final class CompressedFiles {

    private static final int GZIP_HEADER = 10;
    private static final int GZIP_FLAG_EXTRA = 0x04;
    private static final int GZIP_FLAG_NAME = 0x08;
    private static final int TAR_MAGIC_OFFSET = 257;
    private static final int NAME_LIMIT = 1024;

    private CompressedFiles() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final ArchiveFormat format = index.getFormat();
        final String name;
        final long size;
        long mtime = 0;
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            final BlockSource src = BlockSource.of(ch);
            if (format == ArchiveFormat.GZ) {
                if (src.size() < GZIP_HEADER + 8) {
                    throw new IOException("Not a gzip file: " + archive.getFileName());
                }
                final ByteBuffer h = ByteBuffer.wrap(src.read(0, GZIP_HEADER)).order(ByteOrder.LITTLE_ENDIAN);
                if ((h.get(0) & 0xFF) != 0x1F || (h.get(1) & 0xFF) != 0x8B) {
                    throw new IOException("Not a gzip file: " + archive.getFileName());
                }
                size = ByteBuffer.wrap(src.read(src.size() - 4, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt(0) & 0xFFFFFFFFL;
                mtime = (h.getInt(4) & 0xFFFFFFFFL) * 1000;
                name = entryName(archive, format, (h.get(3) & GZIP_FLAG_NAME) != 0 ? gzipName(src, h.get(3) & 0xFF) : "");
            } else {
                size = format == ArchiveFormat.XZ ? xzSize(archive) : decompressedSize(archive, format);
                name = entryName(archive, format, "");
            }
        }
        index.add(name, false, size, mtime, "");
        index.setTar(reader(archive, format, ArchiveIndex.entryName(name)));
    }

    /** The decompressed stream of the whole file (all gzip members, bzip2 and xz streams). */
    static @NotNull InputStream open(final @NotNull Path file, final @NotNull ArchiveFormat format) throws IOException {
        final InputStream raw = new BufferedInputStream(Files.newInputStream(file));
        try {
            return switch (format) {
                case GZ, TAR_GZ -> new GzipCompressorInputStream(raw, true);
                case BZ2, TAR_BZ2 -> new BZip2CompressorInputStream(raw, true);
                case XZ, TAR_XZ -> new XZCompressorInputStream(raw, true);
                default -> throw new IOException("Not a compressed file: " + file.getFileName());
            };
        } catch (final IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    /** True if the decompressed data starts like a TAR — then it is a .tar.gz/.tar.bz2/.tar.xz under another name. */
    static boolean containsTar(final @NotNull Path file, final @NotNull ArchiveFormat format) {
        try (InputStream in = open(file, format)) {
            final byte[] head = in.readNBytes(TAR_MAGIC_OFFSET + 5);

            return head.length == TAR_MAGIC_OFFSET + 5
                && Arrays.equals(head, TAR_MAGIC_OFFSET, TAR_MAGIC_OFFSET + 5, new byte[] {'u', 's', 't', 'a', 'r'}, 0, 5);
        } catch (final IOException e) {
            return false;
        }
    }

    private static @NotNull ArchiveReader reader(final @NotNull Path archive, final @NotNull ArchiveFormat format, final @NotNull String entry) {
        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String name) throws IOException {
                if (!entry.equals(name)) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + name);
                }

                return CompressedFiles.open(archive, format);
            }

            @Override
            public void close() {
                // every open() has its own stream
            }
        };
    }

    /** The uncompressed size from the xz indexes; a damaged index — counted by decompressing. */
    private static long xzSize(final @NotNull Path archive) throws IOException {
        try (SeekableXZInputStream in = new SeekableXZInputStream(new SeekableFileInputStream(archive.toFile()))) {
            return in.length();
        } catch (final IOException e) {
            return decompressedSize(archive, ArchiveFormat.XZ);
        }
    }

    private static long decompressedSize(final @NotNull Path archive, final @NotNull ArchiveFormat format) throws IOException {
        try (InputStream in = open(archive, format)) {
            return in.transferTo(OutputStream.nullOutputStream());
        } catch (final IOException e) {
            throw new IOException("Cannot decompress " + archive.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The archive name without its extension (".gz", ".bz2", ".xz"); found by signature under another extension
     * (Ctrl+PgDn on "x.bin") — the name stored in the gzip header, if any, otherwise the archive name without its extension.
     */
    private static @NotNull String entryName(final @NotNull Path archive, final @NotNull ArchiveFormat format, final @NotNull String stored) {
        final String fileName = String.valueOf(archive.getFileName());
        final String lower = fileName.toLowerCase(Locale.ROOT);
        for (final String suffix : format.getSuffixes()) {
            if (lower.endsWith(suffix) && fileName.length() > suffix.length()) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        if (!stored.isEmpty()) {
            return stored;
        }
        final int dot = fileName.lastIndexOf('.');

        return dot > 0 ? fileName.substring(0, dot) : fileName + ".out";
    }

    /** FNAME: a zero-terminated ISO-8859-1 name after the optional extra field; only its last path component. */
    private static @NotNull String gzipName(final @NotNull BlockSource src, final int flags) throws IOException {
        long pos = GZIP_HEADER;
        if ((flags & GZIP_FLAG_EXTRA) != 0) {
            final byte[] xlen = src.read(pos, 2);
            pos += 2 + ((xlen[0] & 0xFF) | (xlen[1] & 0xFF) << 8);
        }
        final byte[] b = src.read(pos, (int) Math.min(NAME_LIMIT, src.size() - pos));
        int end = 0;
        while (end < b.length && b[end] != 0) {
            end++;
        }
        final String name = ArchiveIndex.entryName(new String(b, 0, end, StandardCharsets.ISO_8859_1));

        return name.substring(name.lastIndexOf('/') + 1);
    }
}
