package tcm.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Archives that start inside a file which is not an archive itself: self-extracting programs (a ZIP, RAR, 7z or cabinet after
 * the extractor code), makeself installers (.run/.sh: a shell script followed by a tar.gz, tar.bz2 or tar.xz), an MSI or a squashfs
 * image appended to a program. Only formats whose offsets are counted from their own start are searched; disk images, DMG, CHM,
 * RPM and .pak are not. A signature alone matches too often in program code, so each candidate is checked by its header checksum
 * or structure.
 */
public final class EmbeddedArchives {

    /** The archive must start within this many bytes of the file. */
    public static final int SEARCH_LIMIT = 10 << 20;

    /** Bytes read past the limit: the headers checked at a candidate (ISO volume descriptors are 32 KB into the image). */
    private static final int LOOKAHEAD = 64 << 10;
    private static final int TAR_MAGIC_OFFSET = 257;
    private static final int TAR_CHECKSUM_OFFSET = 148;
    private static final int TAR_HEADER = 512;
    private static final int ISO_DESCRIPTOR = 0x8000;
    private static final int SEVEN_ZIP_HEADER = 32;
    private static final int CAB_HEADER = 36;
    private static final int GZIP_PROBE = 4096;
    private static final int SQUASHFS_MIN_BLOCK_LOG = 12;
    private static final int SQUASHFS_MAX_BLOCK_LOG = 20;
    private static final byte[] SEVEN_ZIP = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    private static final byte[] RAR = {'R', 'a', 'r', '!', 0x1A, 0x07};
    private static final byte[] XZ = {(byte) 0xFD, '7', 'z', 'X', 'Z', 0};
    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] BZIP2_BLOCK = {0x31, 0x41, 0x59, 0x26, 0x53, 0x59};
    private static final byte[] BZIP2_END = {0x17, 0x72, 0x45, 0x38, 0x50, (byte) 0x90};
    private static final byte[] DEB = "!<arch>\ndebian-binary".getBytes(StandardCharsets.ISO_8859_1);

    private EmbeddedArchives() {
    }

    /** An archive found in a file: its format family (refined by the signature once cut out) and the byte range it takes. */
    @Getter
    public static final class Found {

        private final @NotNull ArchiveFormat format;
        private final long offset;
        /** -1 — up to the end of the file. */
        private final long length;

        Found(final @NotNull ArchiveFormat format, final long offset, final long length) {
            this.format = format;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public String toString() {
            return format + "@" + offset;
        }
    }

    /**
     * Archives starting in the first {@link #SEARCH_LIMIT} bytes of the file, by offset. A ZIP is reported at most once and only when
     * the whole file opens as one: java.util.zip reads a ZIP with anything prepended in place, so it is not cut out.
     */
    public static @NotNull List<Found> find(final @NotNull Path file) throws IOException {
        final List<Found> result = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final long size = ch.size();
            final byte[] data = new byte[(int) Math.min(size, SEARCH_LIMIT + LOOKAHEAD)];
            final ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining() && ch.read(buf, buf.position()) > 0) {
                // reading the head of the file
            }
            final ByteBuffer le = ByteBuffer.wrap(data, 0, buf.position()).order(ByteOrder.LITTLE_ENDIAN);
            final int n = buf.position();
            final int end = Math.min(n, SEARCH_LIMIT);
            boolean zipTried = false;
            for (int i = 0; i < end; i++) {
                final Found found = switch (data[i]) {
                    case 'P' -> {
                        if (zipTried || !isZipLocalHeader(le, i, n)) {
                            yield null;
                        }
                        zipTried = true;

                        yield opensAsZip(file) ? new Found(ArchiveFormat.ZIP, i, -1) : null;
                    }
                    case 'R' -> rar(le, i, n) ? new Found(ArchiveFormat.RAR, i, -1) : null;
                    case '7' -> sevenZip(le, i, n, size);
                    case 0x1F -> gzip(ch, le, i, n) ? new Found(ArchiveFormat.GZ, i, -1) : null;
                    case 'B' -> bzip2(data, i, n) ? new Found(ArchiveFormat.BZ2, i, -1) : null;
                    case (byte) 0xFD -> xz(le, i, n) ? new Found(ArchiveFormat.XZ, i, -1) : null;
                    case 'M' -> cab(le, i, n, size);
                    case 'h' -> squashfs(le, i, n, size) ? new Found(ArchiveFormat.SQUASHFS, i, -1) : null;
                    case (byte) 0xD0 -> ole(le, i, n) ? new Found(ArchiveFormat.MSI, i, -1) : null;
                    case '!' -> matches(data, i, n, DEB) ? new Found(ArchiveFormat.DEB, i, -1) : null;
                    default -> null;
                };
                if (found != null) {
                    result.add(found);
                } else if (tar(data, i, n)) {
                    result.add(new Found(ArchiveFormat.TAR, i, -1));
                } else if (iso(data, i, n)) {
                    result.add(new Found(ArchiveFormat.ISO, i, -1));
                }
            }
        }

        return result;
    }

    /** "PK\3\4" with a sane "version needed to extract" (at most 6.3) and a known compression method. */
    private static boolean isZipLocalHeader(final @NotNull ByteBuffer le, final int i, final int n) {
        if (i + 30 > n || le.getInt(i) != 0x04034b50) {
            return false;
        }
        final int version = le.getShort(i + 4) & 0xFFFF;
        final int method = le.getShort(i + 8) & 0xFFFF;

        return version <= 63 && (method <= 20 || method == 93 || method == 95 || method == 98 || method == 99);
    }

    private static boolean opensAsZip(final @NotNull Path file) {
        // ISO-8859-1 decodes any name, so a DOS-encoded archive is not rejected here
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.ISO_8859_1)) {
            return zip.size() > 0;
        } catch (final IOException | RuntimeException e) {
            return false;
        }
    }

    /** RAR 4: the main archive header follows the marker, its CRC16 checked; RAR 5: the CRC32 of the main archive header. */
    private static boolean rar(final @NotNull ByteBuffer le, final int i, final int n) {
        if (!matches(le.array(), i, n, RAR) || i + 8 > n) {
            return false;
        }
        final byte version = le.get(i + 6);
        if (version == 0) {
            final int h = i + 7;
            if (h + 7 > n || le.get(h + 2) != 0x73) {
                return false;
            }
            final int headSize = le.getShort(h + 5) & 0xFFFF;
            if (headSize < 7 || h + headSize > n) {
                return false;
            }

            return (crc(le.array(), h + 2, headSize - 2) & 0xFFFF) == (le.getShort(h) & 0xFFFF);
        }
        if (version != 1 || i + 12 > n || le.get(i + 7) != 0) {
            return false;
        }
        final int h = i + 8;
        long headSize = 0;
        int p = h + 4;
        for (int shift = 0; p < n && shift < 28; shift += 7) {
            final int b = le.get(p++);
            headSize |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        final long headEnd = p + headSize;
        if (headSize == 0 || headEnd > n) {
            return false;
        }

        return crc(le.array(), h + 4, (int) (headEnd - h - 4)) == (le.getInt(h) & 0xFFFFFFFFL);
    }

    /** The start header: version 0.x, its CRC32, the end header within the file. */
    private static @Nullable Found sevenZip(final @NotNull ByteBuffer le, final int i, final int n, final long size) {
        if (!matches(le.array(), i, n, SEVEN_ZIP) || i + SEVEN_ZIP_HEADER > n || le.get(i + 6) != 0
            || crc(le.array(), i + 12, 20) != (le.getInt(i + 8) & 0xFFFFFFFFL)) {
            return null;
        }
        final long nextOffset = le.getLong(i + 12);
        final long nextSize = le.getLong(i + 20);
        final long length = SEVEN_ZIP_HEADER + nextOffset + nextSize;
        if (nextOffset < 0 || nextSize < 0 || length < 0 || i + length > size) {
            return null;
        }

        return new Found(ArchiveFormat.SEVEN_ZIP, i, length);
    }

    /** Deflate, no reserved flags, a known OS byte, and the start of the stream inflates. */
    private static boolean gzip(final @NotNull FileChannel ch, final @NotNull ByteBuffer le, final int i, final int n) {
        if (i + 10 > n || le.get(i + 1) != (byte) 0x8B || le.get(i + 2) != 8 || (le.get(i + 3) & 0xE0) != 0) {
            return false;
        }
        final int extraFlags = le.get(i + 8) & 0xFF;
        final int os = le.get(i + 9) & 0xFF;
        if (extraFlags != 0 && extraFlags != 2 && extraFlags != 4 || os > 13 && os != 255) {
            return false;
        }
        try {
            final InputStream raw = Channels.newInputStream(ch.position(i));
            final InputStream in = new GzipCompressorInputStream(new BufferedInputStream(raw));
            final byte[] probe = new byte[GZIP_PROBE];
            int total = 0;
            int got;
            while (total < probe.length && (got = in.read(probe, total, probe.length - total)) > 0) {
                total += got;
            }

            // the stream is not closed: that would close the channel the search reads from
            return total > 0;
        } catch (final IOException | RuntimeException e) {
            return false;
        }
    }

    /** "BZh1".."BZh9" followed by the magic of the first block (or of the end of an empty stream). */
    private static boolean bzip2(final byte @NotNull [] data, final int i, final int n) {
        if (i + 10 > n || data[i + 1] != 'Z' || data[i + 2] != 'h' || data[i + 3] < '1' || data[i + 3] > '9') {
            return false;
        }

        return matches(data, i + 4, n, BZIP2_BLOCK) || matches(data, i + 4, n, BZIP2_END);
    }

    /** The stream flags and their CRC32. */
    private static boolean xz(final @NotNull ByteBuffer le, final int i, final int n) {
        return matches(le.array(), i, n, XZ) && i + 12 <= n && le.get(i + 6) == 0 && (le.get(i + 7) & 0xF0) == 0
            && crc(le.array(), i + 6, 2) == (le.getInt(i + 8) & 0xFFFFFFFFL);
    }

    /** "MSCF", zero reserved fields, version 1.3, the cabinet within the file. */
    private static @Nullable Found cab(final @NotNull ByteBuffer le, final int i, final int n, final long size) {
        if (i + CAB_HEADER > n || le.getInt(i) != 0x4643534D || le.getInt(i + 4) != 0 || le.getInt(i + 12) != 0 || le.getInt(i + 20) != 0
            || le.get(i + 24) != 3 || le.get(i + 25) != 1) {
            return null;
        }
        final long length = le.getInt(i + 8) & 0xFFFFFFFFL;
        final long files = le.getInt(i + 16) & 0xFFFFFFFFL;
        if (length < CAB_HEADER || files < CAB_HEADER || files >= length || i + length > size) {
            return null;
        }

        return new Found(ArchiveFormat.CAB, i, length);
    }

    /** "hsqs" version 4 with a block size between 4 KB and 1 MB matching its logarithm, the image within the file. */
    private static boolean squashfs(final @NotNull ByteBuffer le, final int i, final int n, final long size) {
        if (i + 96 > n || le.getInt(i) != 0x73717368 || le.getShort(i + 28) != 4) {
            return false;
        }
        final int blockLog = le.getShort(i + 22);
        final long bytesUsed = le.getLong(i + 40);

        return blockLog >= SQUASHFS_MIN_BLOCK_LOG && blockLog <= SQUASHFS_MAX_BLOCK_LOG && le.getInt(i + 12) == 1 << blockLog
            && bytesUsed > 0 && i + bytesUsed <= size;
    }

    /** An OLE2 compound file (MSI): little-endian byte order mark, version 3 with 512-byte sectors or 4 with 4 KB ones. */
    private static boolean ole(final @NotNull ByteBuffer le, final int i, final int n) {
        if (!matches(le.array(), i, n, OLE) || i + 32 > n || (le.getShort(i + 28) & 0xFFFF) != 0xFFFE) {
            return false;
        }
        final int major = le.getShort(i + 26);
        final int shift = le.getShort(i + 30);

        return major == 3 && shift == 9 || major == 4 && shift == 12;
    }

    /** "ustar" in a header whose checksum (the sum of its bytes with the checksum field as spaces) is right. */
    private static boolean tar(final byte @NotNull [] data, final int i, final int n) {
        if (i + TAR_HEADER > n || data[i + TAR_MAGIC_OFFSET] != 'u' || data[i + TAR_MAGIC_OFFSET + 1] != 's'
            || data[i + TAR_MAGIC_OFFSET + 2] != 't' || data[i + TAR_MAGIC_OFFSET + 3] != 'a' || data[i + TAR_MAGIC_OFFSET + 4] != 'r') {
            return false;
        }
        final int field = i + TAR_CHECKSUM_OFFSET;
        int p = field;
        while (p < field + 8 && data[p] == ' ') {
            p++;
        }
        final int digitsFrom = p;
        long stored = 0;
        while (p < field + 8 && data[p] >= '0' && data[p] <= '7') {
            stored = stored * 8 + data[p] - '0';
            p++;
        }
        if (p == digitsFrom || p < field + 8 && data[p] != ' ' && data[p] != 0) {
            return false;
        }
        long sum = 0;
        for (int k = i; k < i + TAR_HEADER; k++) {
            sum += k >= field && k < field + 8 ? ' ' : data[k] & 0xFF;
        }

        return sum == stored;
    }

    /** A primary volume descriptor 32 KB in: type 1, "CD001", version 1. */
    private static boolean iso(final byte @NotNull [] data, final int i, final int n) {
        final int d = i + ISO_DESCRIPTOR;

        return d + 7 <= n && data[d] == 1 && data[d + 1] == 'C' && data[d + 2] == 'D' && data[d + 3] == '0' && data[d + 4] == '0'
            && data[d + 5] == '1' && data[d + 6] == 1;
    }

    private static boolean matches(final byte @NotNull [] data, final int i, final int n, final byte @NotNull [] signature) {
        if (i + signature.length > n) {
            return false;
        }
        for (int k = 0; k < signature.length; k++) {
            if (data[i + k] != signature[k]) {
                return false;
            }
        }

        return true;
    }

    private static long crc(final byte @NotNull [] data, final int offset, final int length) {
        final CRC32 crc = new CRC32();
        crc.update(data, offset, length);

        return crc.getValue();
    }
}
