package tcm.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * External attributes of ZIP entries (Unix permissions or DOS flags) from the central directory: java.util.zip reads them
 * but does not expose them. Attributes are optional, so any read problem just means "no attributes".
 */
final class ZipAttributes {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int EOCD_SIZE = 22;
    private static final int MAX_COMMENT = 0xFFFF;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int ZIP64_EOCD_SIZE = 56;
    private static final int CEN_SIG = 0x02014b50;
    private static final int CEN_SIZE = 46;
    private static final int UTF8_FLAG = 0x800;
    private static final int HOST_DOS = 0;
    private static final int HOST_UNIX = 3;
    private static final int HOST_NTFS = 10;
    private static final int HOST_VFAT = 14;
    private static final int HOST_OSX = 19;

    private ZipAttributes() {
    }

    /** Entry name (as in the archive tree) → Attr column text; names without the UTF-8 flag are decoded with charset, as in ZipFile. */
    static @NotNull Map<String, String> read(final @NotNull Path archive, final @NotNull Charset charset) {
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            return read(ch, charset);
        } catch (final IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private static @NotNull Map<String, String> read(final @NotNull FileChannel ch, final @NotNull Charset charset) throws IOException {
        final long size = ch.size();
        final int tailLength = (int) Math.min(size, EOCD_SIZE + MAX_COMMENT);
        final long tailStart = size - tailLength;
        final ByteBuffer tail = readAt(ch, tailStart, tailLength);
        int eocd = tailLength - EOCD_SIZE;
        while (eocd >= 0 && tail.getInt(eocd) != EOCD_SIG) {
            eocd--;
        }
        if (eocd < 0) {
            return Map.of();
        }
        // the central directory ends right before EOCD (or before the ZIP64 record); counting from the end also works
        // for archives with data prepended (self-extracting), where the offsets in EOCD are shifted
        long cenSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
        long cenEnd = tailStart + eocd;
        if (eocd >= ZIP64_LOCATOR_SIZE && tail.getInt(eocd - ZIP64_LOCATOR_SIZE) == ZIP64_LOCATOR_SIG) {
            final long z64 = cenEnd - ZIP64_LOCATOR_SIZE - ZIP64_EOCD_SIZE;
            final ByteBuffer rec = readAt(ch, z64, ZIP64_EOCD_SIZE);
            if (rec.getInt(0) == ZIP64_EOCD_SIG) {
                cenSize = rec.getLong(40);
                cenEnd = z64;
            }
        }
        if (cenSize > Integer.MAX_VALUE || cenSize > cenEnd) {
            return Map.of();
        }
        final ByteBuffer cen = readAt(ch, cenEnd - cenSize, (int) cenSize);
        final Map<String, String> result = new HashMap<>();
        int p = 0;
        while (p + CEN_SIZE <= cen.limit() && cen.getInt(p) == CEN_SIG) {
            final int host = cen.get(p + 5) & 0xFF;
            final int flags = cen.getShort(p + 8) & 0xFFFF;
            final int nameLength = cen.getShort(p + 28) & 0xFFFF;
            final int extraLength = cen.getShort(p + 30) & 0xFFFF;
            final int commentLength = cen.getShort(p + 32) & 0xFFFF;
            final int external = cen.getInt(p + 38);
            if (p + CEN_SIZE + nameLength > cen.limit()) {
                break;
            }
            final byte[] name = new byte[nameLength];
            cen.get(p + CEN_SIZE, name);
            final String attrs = attrs(host, external);
            if (!attrs.isEmpty()) {
                result.put(ArchiveIndex.entryName(new String(name, (flags & UTF8_FLAG) != 0 ? StandardCharsets.UTF_8 : charset)), attrs);
            }
            p += CEN_SIZE + nameLength + extraLength + commentLength;
        }

        return result;
    }

    /** Unix archivers keep the mode in the high 16 bits, DOS/Windows ones — the attribute byte in the low bits. */
    private static @NotNull String attrs(final int host, final int external) {
        if (host == HOST_UNIX || host == HOST_OSX) {
            final String posix = FileAttrs.posix(external >>> 16);

            return posix.isEmpty() ? FileAttrs.dos(external) : posix;
        }
        if (host == HOST_DOS || host == HOST_NTFS || host == HOST_VFAT) {
            return FileAttrs.dos(external);
        }

        return "";
    }

    private static @NotNull ByteBuffer readAt(final @NotNull FileChannel ch, final long position, final int length) throws IOException {
        final ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.hasRemaining()) {
            if (ch.read(buf, position + buf.position()) < 0) {
                throw new EOFException();
            }
        }

        return buf.flip();
    }
}
