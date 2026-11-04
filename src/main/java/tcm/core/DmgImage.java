package tcm.core;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.XZInputStream;

import lombok.Getter;

/**
 * Apple disk image (UDIF .dmg): the "koly" trailer at the end of the file points to an XML property list whose "blkx" array
 * describes each partition as a table of chunks — raw, zero-filled, or compressed with zlib, bzip2, ADC, LZMA. A partition is
 * read as a {@link BlockSource}: chunks are decompressed on demand, the last few are cached. LZFSE chunks are not supported.
 */
final class DmgImage {

    static final int SECTOR = 512;
    private static final int KOLY_SIZE = 512;
    private static final int KOLY_DATA_FORK = 24;
    private static final int KOLY_XML_OFFSET = 216;
    private static final int KOLY_XML_LENGTH = 224;
    private static final int MAX_XML = 64 * 1024 * 1024;
    private static final int MISH_FIRST_SECTOR = 8;
    private static final int MISH_SECTOR_COUNT = 16;
    private static final int MISH_CHUNK_COUNT = 200;
    private static final int MISH_CHUNKS = 204;
    private static final int CHUNK_SIZE = 40;
    private static final int MAX_CHUNK = 64 * 1024 * 1024;
    private static final int CACHED_CHUNKS = 8;

    private static final int ZERO = 0;
    private static final int RAW = 1;
    private static final int IGNORE = 2;
    private static final int COMMENT = 0x7FFFFFFE;
    private static final int ADC = 0x80000004;
    private static final int ZLIB = 0x80000005;
    private static final int BZIP2 = 0x80000006;
    private static final int LZFSE = 0x80000007;
    private static final int LZMA = 0x80000008;
    private static final int END = 0xFFFFFFFF;

    private static final Pattern BLKX = Pattern.compile("<key>blkx</key>\\s*<array>(.*?)</array>", Pattern.DOTALL);
    private static final Pattern DICT = Pattern.compile("<dict>(.*?)</dict>", Pattern.DOTALL);
    private static final Pattern DATA = Pattern.compile("<key>Data</key>\\s*<data>([^<]*)</data>");
    private static final Pattern NAME = Pattern.compile("<key>(?:Name|CFName)</key>\\s*<string>([^<]*)</string>");

    private final @NotNull FileChannel ch;
    @Getter
    private final @NotNull List<Partition> partitions;

    private DmgImage(final @NotNull FileChannel ch, final @NotNull List<Partition> partitions) {
        this.ch = ch;
        this.partitions = partitions;
    }

    /** True if the file ends with the UDIF trailer. */
    static boolean matches(final @NotNull FileChannel ch) throws IOException {
        if (ch.size() < KOLY_SIZE) {
            return false;
        }
        final ByteBuffer magic = ByteBuffer.allocate(4);
        ch.read(magic, ch.size() - KOLY_SIZE);

        return new String(magic.array(), StandardCharsets.ISO_8859_1).equals("koly");
    }

    static @NotNull DmgImage open(final @NotNull FileChannel ch) throws IOException {
        if (!matches(ch)) {
            throw new IOException("Not an Apple disk image (no UDIF trailer)");
        }
        final ByteBuffer koly = read(ch, ch.size() - KOLY_SIZE, KOLY_SIZE);
        final long dataFork = koly.getLong(KOLY_DATA_FORK);
        final long xmlOffset = koly.getLong(KOLY_XML_OFFSET);
        final long xmlLength = koly.getLong(KOLY_XML_LENGTH);
        if (xmlLength <= 0 || xmlLength > MAX_XML || xmlOffset < 0 || xmlOffset + xmlLength > ch.size()) {
            throw new IOException("Disk image without a partition list (an old resource-fork image?)");
        }
        final String xml = new String(read(ch, xmlOffset, (int) xmlLength).array(), StandardCharsets.UTF_8);
        final Matcher blkx = BLKX.matcher(xml);
        if (!blkx.find()) {
            throw new IOException("Disk image without a partition list");
        }
        final List<Partition> result = new ArrayList<>();
        final DmgImage image = new DmgImage(ch, result);
        final Matcher dict = DICT.matcher(blkx.group(1));
        while (dict.find()) {
            final Matcher data = DATA.matcher(dict.group(1));
            if (!data.find()) {
                continue;
            }
            final Matcher name = NAME.matcher(dict.group(1));
            final byte[] mish = Base64.getMimeDecoder().decode(data.group(1).strip());
            final Partition p = image.partition(result.size(), name.find() ? unescape(name.group(1)) : "", mish, dataFork);
            if (p != null) {
                result.add(p);
            }
        }

        return image;
    }

    private @Nullable Partition partition(final int index, final @NotNull String name, final byte @NotNull [] mish, final long dataFork)
        throws IOException {
        if (mish.length < MISH_CHUNKS || mish[0] != 'm' || mish[1] != 'i' || mish[2] != 's' || mish[3] != 'h') {
            return null;
        }
        final ByteBuffer b = ByteBuffer.wrap(mish);
        final long first = b.getLong(MISH_FIRST_SECTOR);
        final long sectors = b.getLong(MISH_SECTOR_COUNT);
        final int count = b.getInt(MISH_CHUNK_COUNT);
        if (count < 0 || MISH_CHUNKS + (long) count * CHUNK_SIZE > mish.length || sectors < 0) {
            throw new IOException("Damaged partition table in the disk image");
        }
        final List<Chunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int o = MISH_CHUNKS + i * CHUNK_SIZE;
            final int type = b.getInt(o);
            if (type == END || type == COMMENT) {
                continue;
            }
            final long sector = b.getLong(o + 8);
            final long sectorCount = b.getLong(o + 16);
            final long offset = dataFork + b.getLong(o + 24);
            final long length = b.getLong(o + 32);
            if (sectorCount <= 0) {
                continue;
            }
            if (sectorCount * SECTOR > MAX_CHUNK || length < 0 || length > MAX_CHUNK || offset < 0 || offset + length > ch.size()) {
                throw new IOException("Damaged chunk table in the disk image");
            }
            chunks.add(new Chunk(type, sector * SECTOR, (int) (sectorCount * SECTOR), offset, (int) length));
        }
        chunks.sort((x, y) -> Long.compare(x.start, y.start));

        return new Partition(index, name, first, sectors * SECTOR, chunks.toArray(new Chunk[0]));
    }

    private static @NotNull String unescape(final @NotNull String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static @NotNull ByteBuffer read(final @NotNull FileChannel ch, final long pos, final int len) throws IOException {
        final ByteBuffer buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            if (ch.read(buf, pos + buf.position()) < 0) {
                throw new EOFException("Truncated disk image");
            }
        }

        return buf.flip();
    }

    /** Decompressed contents of a chunk, exactly chunk.size bytes. */
    private byte @NotNull [] decode(final @NotNull Chunk c) throws IOException {
        if (c.type == ZERO || c.type == IGNORE) {
            return new byte[c.size];
        }
        final byte[] packed = read(ch, c.offset, c.length).array();
        final byte[] out;
        switch (c.type) {
            case RAW -> out = packed;
            case ZLIB -> out = inflate(packed, c.size);
            case BZIP2 -> out = stream(new BZip2CompressorInputStream(new ByteArrayInputStream(packed)), c.size);
            case ADC -> out = Adc.decode(packed, c.size);
            case LZMA -> out = stream(lzma(packed), c.size);
            case LZFSE -> throw new IOException("LZFSE-compressed disk images are not supported");
            default -> throw new IOException("Unknown disk image chunk type 0x" + Integer.toHexString(c.type));
        }
        if (out.length < c.size) {
            final byte[] full = new byte[c.size];
            System.arraycopy(out, 0, full, 0, out.length);

            return full;
        }

        return out;
    }

    private static @NotNull InputStream lzma(final byte @NotNull [] packed) throws IOException {
        final InputStream in = new ByteArrayInputStream(packed);
        final boolean xz = packed.length > 6 && (packed[0] & 0xFF) == 0xFD && packed[1] == '7' && packed[2] == 'z';

        return xz ? new XZInputStream(in) : new LZMAInputStream(in);
    }

    private static byte @NotNull [] inflate(final byte @NotNull [] packed, final int size) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            final byte[] out = new byte[size];
            int n = 0;
            while (n < size && !inflater.finished()) {
                final int got = inflater.inflate(out, n, size - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += got;
            }

            return out;
        } catch (final DataFormatException e) {
            throw new IOException("Damaged zlib chunk in the disk image", e);
        } finally {
            inflater.end();
        }
    }

    private static byte @NotNull [] stream(final @NotNull InputStream in, final int size) throws IOException {
        try (in) {
            return in.readNBytes(size);
        }
    }

    /** A partition of the image (one "blkx" entry): its chunks cover offsets 0..size. */
    final class Partition implements BlockSource {

        @Getter
        private final int index;
        @Getter
        private final @NotNull String name;
        private final long firstSector;
        private final long size;
        private final Chunk @NotNull [] chunks;
        private final Map<Chunk, byte[]> cache = new LinkedHashMap<>(CACHED_CHUNKS, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Chunk, byte[]> eldest) {
                return size() > CACHED_CHUNKS;
            }
        };

        private Partition(final int index, final @NotNull String name, final long firstSector, final long size, final Chunk @NotNull [] chunks) {
            this.index = index;
            this.name = name;
            this.firstSector = firstSector;
            this.size = size;
            this.chunks = chunks;
        }

        @Override
        public long size() {
            return size;
        }

        long firstSector() {
            return firstSector;
        }

        @Override
        public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
            if (pos < 0 || len < 0 || pos + len > size) {
                throw new EOFException("Read past the end of the disk image partition");
            }
            long p = pos;
            int o = off;
            int left = len;
            while (left > 0) {
                final Chunk c = find(p);
                if (c == null) {
                    // a hole in the chunk table reads as zeros
                    final long next = nextStart(p);
                    final int n = (int) Math.min(left, next - p);
                    Arrays.fill(b, o, o + n, (byte) 0);
                    p += n;
                    o += n;
                    left -= n;
                    continue;
                }
                final int in = (int) (p - c.start);
                final int n = Math.min(left, c.size - in);
                if (c.type == ZERO || c.type == IGNORE) {
                    Arrays.fill(b, o, o + n, (byte) 0);
                } else {
                    System.arraycopy(chunk(c), in, b, o, n);
                }
                p += n;
                o += n;
                left -= n;
            }
        }

        private synchronized byte @NotNull [] chunk(final @NotNull Chunk c) throws IOException {
            byte[] data = cache.get(c);
            if (data == null) {
                data = decode(c);
                cache.put(c, data);
            }

            return data;
        }

        private @Nullable Chunk find(final long pos) {
            int lo = 0;
            int hi = chunks.length - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                final Chunk c = chunks[mid];
                if (pos < c.start) {
                    hi = mid - 1;
                } else if (pos >= c.start + c.size) {
                    lo = mid + 1;
                } else {
                    return c;
                }
            }

            return null;
        }

        private long nextStart(final long pos) {
            for (final Chunk c : chunks) {
                if (c.start > pos) {
                    return c.start;
                }
            }

            return size;
        }
    }

    private static final class Chunk {

        private final int type;
        /** Offset of the uncompressed data in the partition. */
        private final long start;
        private final int size;
        /** Offset of the stored data in the file. */
        private final long offset;
        private final int length;

        private Chunk(final int type, final long start, final int size, final long offset, final int length) {
            this.type = type;
            this.start = start;
            this.size = size;
            this.offset = offset;
            this.length = length;
        }
    }

    /** Apple Data Compression: literal runs and back references of 2 or 3 bytes. */
    private static final class Adc {

        private Adc() {
        }

        static byte @NotNull [] decode(final byte @NotNull [] in, final int size) throws IOException {
            final byte[] out = new byte[size];
            int i = 0;
            int o = 0;
            while (i < in.length && o < size) {
                final int b = in[i++] & 0xFF;
                if ((b & 0x80) != 0) {
                    final int n = Math.min((b & 0x7F) + 1, Math.min(size - o, in.length - i));
                    System.arraycopy(in, i, out, o, n);
                    i += n;
                    o += n;
                    continue;
                }
                final int n;
                final int distance;
                if ((b & 0x40) != 0) {
                    if (i + 2 > in.length) {
                        break;
                    }
                    n = (b & 0x3F) + 4;
                    distance = ((in[i] & 0xFF) << 8 | in[i + 1] & 0xFF) + 1;
                    i += 2;
                } else {
                    if (i + 1 > in.length) {
                        break;
                    }
                    n = (b >> 2 & 0x0F) + 3;
                    distance = ((b & 0x03) << 8 | in[i] & 0xFF) + 1;
                    i++;
                }
                if (distance > o) {
                    throw new IOException("Damaged ADC chunk in the disk image");
                }
                for (int k = 0; k < n && o < size; k++, o++) {
                    out[o] = out[o - distance];
                }
            }

            return out;
        }
    }
}
