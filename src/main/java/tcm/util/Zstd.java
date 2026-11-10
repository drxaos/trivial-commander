package tcm.util;

import java.io.IOException;
import java.util.Arrays;

import org.jetbrains.annotations.NotNull;

/**
 * Zstandard decompressor (RFC 8878) in plain Java: whole frames in memory, no dictionaries. Enough for squashfs blocks
 * of AppImages; libraries with a pure Java decoder either need sun.misc.Unsafe (a warning on JDK 24+) or Java 25.
 */
public final class Zstd {

    private static final int MAGIC = 0xFD2FB528;
    private static final int SKIPPABLE_MASK = 0xFFFFFFF0;
    private static final int SKIPPABLE_MAGIC = 0x184D2A50;
    private static final int MAX_BLOCK = 128 * 1024;

    private static final int[] LL_BASE = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
        8192, 16384, 32768, 65536
    };
    private static final int[] LL_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
    private static final int[] ML_BASE = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
        35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539
    };
    private static final int[] ML_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
    private static final short[] LL_DEFAULT = {
        4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1
    };
    private static final short[] ML_DEFAULT = {
        1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1
    };
    private static final short[] OF_DEFAULT = {
        1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1
    };
    private static final Fse LL_PREDEFINED = Fse.build(LL_DEFAULT, LL_DEFAULT.length, 6);
    private static final Fse ML_PREDEFINED = Fse.build(ML_DEFAULT, ML_DEFAULT.length, 6);
    private static final Fse OF_PREDEFINED = Fse.build(OF_DEFAULT, OF_DEFAULT.length, 5);

    private final byte[] src;
    private final int maxSize;
    private byte[] out = new byte[1024];
    private int outPos;
    private final int[] reps = new int[3];
    private Huffman huffman;
    private Fse llTable;
    private Fse ofTable;
    private Fse mlTable;
    private byte[] literals = new byte[MAX_BLOCK];
    private int literalCount;

    private Zstd(final byte @NotNull [] src, final int maxSize) {
        this.src = src;
        this.maxSize = maxSize;
    }

    /** Decompresses all frames of the input; the result must not exceed maxSize bytes. */
    public static byte @NotNull [] decompress(final byte @NotNull [] src, final int maxSize) throws IOException {
        try {
            return new Zstd(src, maxSize).run();
        } catch (final ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("corrupted zstd data", e);
        }
    }

    /** Decompresses the first frame only: the bytes after it (padding to a sector of a qcow2 cluster) are ignored. */
    public static byte @NotNull [] decompressFrame(final byte @NotNull [] src, final int maxSize) throws IOException {
        try {
            return new Zstd(src, maxSize).run(true);
        } catch (final ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("corrupted zstd data", e);
        }
    }

    private byte @NotNull [] run() throws IOException {
        return run(false);
    }

    private byte @NotNull [] run(final boolean single) throws IOException {
        int pos = 0;
        while (pos + 4 <= src.length) {
            final int magic = le32(pos);
            if ((magic & SKIPPABLE_MASK) == SKIPPABLE_MAGIC) {
                pos += 8 + le32(pos + 4);
            } else if (magic == MAGIC) {
                pos = frame(pos + 4);
                if (single) {
                    break;
                }
            } else {
                throw new IOException("not a zstd frame");
            }
        }

        return Arrays.copyOf(out, outPos);
    }

    private int frame(final int start) throws IOException {
        int pos = start;
        final int fhd = src[pos++] & 0xFF;
        final int fcsFlag = fhd >> 6;
        final boolean singleSegment = (fhd & 0x20) != 0;
        if ((fhd & 0x08) != 0) {
            throw new IOException("reserved zstd frame bit");
        }
        if (!singleSegment) {
            pos++;
        }
        final int dictBytes = new int[] {0, 1, 2, 4}[fhd & 3];
        for (int i = 0; i < dictBytes; i++) {
            if (src[pos + i] != 0) {
                throw new IOException("zstd dictionaries are not supported");
            }
        }
        pos += dictBytes + (fcsFlag == 0 ? (singleSegment ? 1 : 0) : 1 << fcsFlag);
        reps[0] = 1;
        reps[1] = 4;
        reps[2] = 8;
        huffman = null;
        llTable = null;
        ofTable = null;
        mlTable = null;
        final int frameStart = outPos;
        boolean last = false;
        while (!last) {
            final int header = (src[pos] & 0xFF) | (src[pos + 1] & 0xFF) << 8 | (src[pos + 2] & 0xFF) << 16;
            pos += 3;
            last = (header & 1) != 0;
            final int size = header >>> 3;
            switch (header >> 1 & 3) {
                case 0:
                    ensure(size);
                    System.arraycopy(src, pos, out, outPos, size);
                    outPos += size;
                    pos += size;
                    break;
                case 1:
                    ensure(size);
                    Arrays.fill(out, outPos, outPos + size, src[pos]);
                    outPos += size;
                    pos++;
                    break;
                case 2:
                    compressedBlock(pos, pos + size, frameStart);
                    pos += size;
                    break;
                default:
                    throw new IOException("reserved zstd block type");
            }
        }

        return (fhd & 0x04) != 0 ? pos + 4 : pos;
    }

    private void compressedBlock(final int start, final int end, final int frameStart) throws IOException {
        int pos = readLiterals(start, end);
        final int b0 = src[pos++] & 0xFF;
        int count;
        if (b0 < 128) {
            count = b0;
        } else if (b0 < 255) {
            count = (b0 - 128 << 8) + (src[pos++] & 0xFF);
        } else {
            count = (src[pos] & 0xFF) + ((src[pos + 1] & 0xFF) << 8) + 0x7F00;
            pos += 2;
        }
        int literalPos = 0;
        if (count > 0) {
            final int modes = src[pos++] & 0xFF;
            pos = readTables(pos, end, modes);
            literalPos = sequences(new BackwardBits(src, pos, end), count, frameStart);
        }
        final int rest = literalCount - literalPos;
        ensure(rest);
        System.arraycopy(literals, literalPos, out, outPos, rest);
        outPos += rest;
    }

    /** Reads the literals section into the literal buffer; returns the position after it. */
    private int readLiterals(final int start, final int end) throws IOException {
        final int b0 = src[start] & 0xFF;
        final int type = b0 & 3;
        final int format = b0 >> 2 & 3;
        if (type < 2) {
            final int headerSize = (format & 1) == 0 ? 1 : format == 1 ? 2 : 3;
            final int size = switch (headerSize) {
                case 1 -> b0 >> 3;
                case 2 -> (b0 >> 4) + ((src[start + 1] & 0xFF) << 4);
                default -> (b0 >> 4) + ((src[start + 1] & 0xFF) << 4) + ((src[start + 2] & 0xFF) << 12);
            };
            setLiteralCount(size);
            final int pos = start + headerSize;
            if (type == 0) {
                System.arraycopy(src, pos, literals, 0, size);

                return pos + size;
            }
            Arrays.fill(literals, 0, size, src[pos]);

            return pos + 1;
        }
        final int headerSize = format < 2 ? 3 : format + 2;
        long bits = 0;
        for (int i = 0; i < headerSize; i++) {
            bits |= (src[start + i] & 0xFFL) << (8 * i);
        }
        final int sizeBits = format < 2 ? 10 : format == 2 ? 14 : 18;
        final int regenerated = (int) (bits >>> 4 & (1 << sizeBits) - 1);
        final int compressed = (int) (bits >>> 4 + sizeBits & (1 << sizeBits) - 1);
        int pos = start + headerSize;
        final int streamsEnd = pos + compressed;
        if (streamsEnd > end) {
            throw new IOException("zstd literals outside the block");
        }
        if (type == 2) {
            huffman = new Huffman();
            pos = huffman.read(src, pos);
        } else if (huffman == null) {
            throw new IOException("zstd treeless literals without a table");
        }
        setLiteralCount(regenerated);
        if (format == 0) {
            huffman.decode(new BackwardBits(src, pos, streamsEnd), literals, 0, regenerated);
        } else {
            decodeFourStreams(pos, streamsEnd, regenerated);
        }

        return streamsEnd;
    }

    private void decodeFourStreams(final int start, final int end, final int regenerated) throws IOException {
        final int s1 = le16(start);
        final int s2 = le16(start + 2);
        final int s3 = le16(start + 4);
        final int p1 = start + 6;
        final int p2 = p1 + s1;
        final int p3 = p2 + s2;
        final int p4 = p3 + s3;
        if (p4 > end) {
            throw new IOException("zstd literal streams outside the block");
        }
        final int quarter = (regenerated + 3) / 4;
        huffman.decode(new BackwardBits(src, p1, p2), literals, 0, quarter);
        huffman.decode(new BackwardBits(src, p2, p3), literals, quarter, quarter);
        huffman.decode(new BackwardBits(src, p3, p4), literals, 2 * quarter, quarter);
        huffman.decode(new BackwardBits(src, p4, end), literals, 3 * quarter, regenerated - 3 * quarter);
    }

    private void setLiteralCount(final int count) throws IOException {
        if (count > MAX_BLOCK) {
            throw new IOException("zstd literals exceed the block size");
        }
        literalCount = count;
    }

    private int readTables(final int start, final int end, final int modes) throws IOException {
        int pos = start;
        final Fse[] result = new Fse[3];
        final Fse[] predefined = {LL_PREDEFINED, OF_PREDEFINED, ML_PREDEFINED};
        final Fse[] previous = {llTable, ofTable, mlTable};
        final int[] maxSymbol = {35, 31, 52};
        final int[] maxLog = {9, 8, 9};
        for (int i = 0; i < 3; i++) {
            final int mode = modes >> 6 - 2 * i & 3;
            switch (mode) {
                case 0 -> result[i] = predefined[i];
                case 1 -> {
                    final int symbol = src[pos++] & 0xFF;
                    if (symbol > maxSymbol[i]) {
                        throw new IOException("zstd RLE symbol out of range");
                    }
                    result[i] = Fse.rle(symbol);
                }
                case 2 -> {
                    final short[] norm = new short[maxSymbol[i] + 1];
                    final int[] header = Fse.readCounts(src, pos, end, norm, maxSymbol[i]);
                    if (header[0] > maxLog[i]) {
                        throw new IOException("zstd FSE accuracy too high");
                    }
                    result[i] = Fse.build(norm, header[1], header[0]);
                    pos = header[2];
                }
                default -> {
                    if (previous[i] == null) {
                        throw new IOException("zstd repeat mode without a table");
                    }
                    result[i] = previous[i];
                }
            }
        }
        llTable = result[0];
        ofTable = result[1];
        mlTable = result[2];

        return pos;
    }

    /** Executes the sequences; returns how many literals they consumed. */
    private int sequences(final @NotNull BackwardBits bits, final int count, final int frameStart) throws IOException {
        int llState = bits.read(llTable.log);
        int ofState = bits.read(ofTable.log);
        int mlState = bits.read(mlTable.log);
        int literalPos = 0;
        for (int n = 0; n < count; n++) {
            final int ofCode = ofTable.symbol[ofState];
            final int llCode = llTable.symbol[llState];
            final int mlCode = mlTable.symbol[mlState];
            if (ofCode > 31) {
                throw new IOException("zstd offset code out of range");
            }
            final int offsetValue = (1 << ofCode) + bits.read(ofCode);
            final int matchLength = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode]);
            final int literalLength = LL_BASE[llCode] + bits.read(LL_BITS[llCode]);
            final int offset = offset(offsetValue, literalLength);
            if (n + 1 < count) {
                llState = llTable.next(llState, bits);
                mlState = mlTable.next(mlState, bits);
                ofState = ofTable.next(ofState, bits);
            }
            if (literalPos + literalLength > literalCount) {
                throw new IOException("zstd sequence exceeds the literals");
            }
            ensure(literalLength + matchLength);
            System.arraycopy(literals, literalPos, out, outPos, literalLength);
            outPos += literalLength;
            literalPos += literalLength;
            if (offset <= 0 || offset > outPos - frameStart) {
                throw new IOException("zstd offset outside the frame");
            }
            for (int i = 0; i < matchLength; i++) {
                out[outPos] = out[outPos - offset];
                outPos++;
            }
        }

        return literalPos;
    }

    /** The match offset from the offset value, updating the repeat offsets (RFC 8878, 3.1.1.5). */
    private int offset(final int offsetValue, final int literalLength) {
        if (offsetValue > 3) {
            reps[2] = reps[1];
            reps[1] = reps[0];
            reps[0] = offsetValue - 3;

            return reps[0];
        }
        final int index = literalLength == 0 ? offsetValue + 1 : offsetValue;
        if (index == 1) {
            return reps[0];
        }
        final int offset = index == 4 ? reps[0] - 1 : reps[index - 1];
        if (index != 2) {
            reps[2] = reps[1];
        }
        reps[1] = reps[0];
        reps[0] = offset;

        return offset;
    }

    private void ensure(final int extra) throws IOException {
        final long need = (long) outPos + extra;
        if (need > maxSize) {
            throw new IOException("zstd output exceeds " + maxSize + " bytes");
        }
        if (need > out.length) {
            out = Arrays.copyOf(out, (int) Math.min(maxSize, Math.max(need, 2L * out.length)));
        }
    }

    private int le16(final int pos) {
        return (src[pos] & 0xFF) | (src[pos + 1] & 0xFF) << 8;
    }

    private int le32(final int pos) {
        return le16(pos) | le16(pos + 2) << 16;
    }

    /** A bitstream read from its end towards its start; the last byte's highest set bit marks where the data begins. */
    private static final class BackwardBits {

        private final byte[] data;
        private final int start;
        private final int end;
        /** Bits left before the stream start; negative after an overread (reads past the start give zeros). */
        private int position;

        private BackwardBits(final byte @NotNull [] data, final int start, final int end) throws IOException {
            if (end <= start || data[end - 1] == 0) {
                throw new IOException("bad zstd bitstream end");
            }
            this.data = data;
            this.start = start;
            this.end = end;
            this.position = (end - start) * 8 - Integer.numberOfLeadingZeros(data[end - 1] & 0xFF) + 24 - 1;
        }

        private int read(final int n) {
            if (n > 24) {
                // offset codes take up to 31 bits; the earlier bits are the high ones
                final int high = read(n - 24);

                return high << 24 | read(24);
            }
            final int value = peek(n);
            position -= n;

            return value;
        }

        /** The next n (at most 24) bits without consuming them, the earliest ones in the low positions. */
        private int peek(final int n) {
            if (n == 0) {
                return 0;
            }
            final int from = position - n;
            if (from < 0) {
                return from <= -n ? 0 : bitsAt(0, n + from) << -from;
            }

            return bitsAt(from, n);
        }

        private int bitsAt(final int from, final int n) {
            final int first = start + (from >> 3);
            long acc = 0;
            for (int i = 0; i < 4 && first + i < end; i++) {
                acc |= (data[first + i] & 0xFFL) << (8 * i);
            }

            return (int) (acc >>> (from & 7) & (1L << n) - 1);
        }

        private boolean overflow() {
            return position < 0;
        }
    }

    /** An FSE decoding table. */
    private static final class Fse {

        private final int log;
        private final int[] symbol;
        private final int[] bits;
        private final int[] base;

        private Fse(final int log) {
            this.log = log;
            this.symbol = new int[1 << log];
            this.bits = new int[1 << log];
            this.base = new int[1 << log];
        }

        private int next(final int state, final @NotNull BackwardBits in) {
            return base[state] + in.read(bits[state]);
        }

        private static @NotNull Fse rle(final int symbol) {
            final Fse t = new Fse(0);
            t.symbol[0] = symbol;

            return t;
        }

        /** Builds the table from normalized counts (-1 = "less than one"), RFC 8878, 4.1.1. */
        private static @NotNull Fse build(final short @NotNull [] norm, final int symbols, final int log) {
            final Fse t = new Fse(log);
            final int size = 1 << log;
            int high = size - 1;
            final int[] next = new int[symbols];
            for (int s = 0; s < symbols; s++) {
                if (norm[s] == -1) {
                    t.symbol[high--] = s;
                    next[s] = 1;
                } else {
                    next[s] = norm[s];
                }
            }
            final int step = (size >> 1) + (size >> 3) + 3;
            int pos = 0;
            for (int s = 0; s < symbols; s++) {
                for (int i = 0; i < norm[s]; i++) {
                    t.symbol[pos] = s;
                    do {
                        pos = pos + step & size - 1;
                    } while (pos > high);
                }
            }
            for (int u = 0; u < size; u++) {
                final int state = next[t.symbol[u]]++;
                final int nb = log - (31 - Integer.numberOfLeadingZeros(state));
                t.bits[u] = nb;
                t.base[u] = (state << nb) - size;
            }

            return t;
        }

        /** Reads a table description: returns {accuracy log, symbol count, position after it}. */
        private static int @NotNull [] readCounts(
            final byte @NotNull [] src,
            final int start,
            final int end,
            final short @NotNull [] norm,
            final int maxSymbol
        ) throws IOException {
            final ForwardBits in = new ForwardBits(src, start, end);
            final int log = in.read(4) + 5;
            int remaining = (1 << log) + 1;
            int threshold = 1 << log;
            int nbBits = log + 1;
            int symbol = 0;
            while (remaining > 1 && symbol <= maxSymbol) {
                final int max = 2 * threshold - 1 - remaining;
                int count = in.peek(nbBits);
                if ((count & threshold - 1) < max) {
                    count &= threshold - 1;
                    in.skip(nbBits - 1);
                } else {
                    count &= 2 * threshold - 1;
                    if (count >= threshold) {
                        count -= max;
                    }
                    in.skip(nbBits);
                }
                count--;
                remaining -= Math.abs(count);
                norm[symbol++] = (short) count;
                if (count == 0) {
                    symbol = readZeroRun(in, norm, symbol, maxSymbol);
                }
                while (remaining < threshold) {
                    nbBits--;
                    threshold >>= 1;
                }
            }
            if (remaining != 1 || symbol > maxSymbol + 1) {
                throw new IOException("corrupted zstd FSE table");
            }

            return new int[] {log, symbol, in.bytePosition()};
        }

        private static int readZeroRun(final @NotNull ForwardBits in, final short @NotNull [] norm, final int from, final int maxSymbol)
            throws IOException {
            int symbol = from;
            int repeat;
            do {
                repeat = in.read(2);
                for (int i = 0; i < repeat; i++) {
                    if (symbol > maxSymbol) {
                        throw new IOException("corrupted zstd FSE zero run");
                    }
                    norm[symbol++] = 0;
                }
            } while (repeat == 3);

            return symbol;
        }
    }

    /** A little-endian bitstream read from its start (FSE table descriptions). */
    private static final class ForwardBits {

        private final byte[] data;
        private final int start;
        private final int end;
        private int position;

        private ForwardBits(final byte @NotNull [] data, final int start, final int end) {
            this.data = data;
            this.start = start;
            this.end = end;
        }

        private int peek(final int n) {
            final int first = start + (position >> 3);
            long acc = 0;
            for (int i = 0; i < 4 && first + i < end; i++) {
                acc |= (data[first + i] & 0xFFL) << (8 * i);
            }

            return (int) (acc >>> (position & 7) & (1L << n) - 1);
        }

        private int read(final int n) {
            final int value = peek(n);
            position += n;

            return value;
        }

        private void skip(final int n) throws IOException {
            position += n;
            if (start + (position + 7 >> 3) > end) {
                throw new IOException("zstd FSE table outside the block");
            }
        }

        private int bytePosition() {
            return start + (position + 7 >> 3);
        }
    }

    /** A Huffman literal table: weights (direct or FSE-compressed) → a lookup by the next maxBits bits. */
    private static final class Huffman {

        private int maxBits;
        private byte[] symbols;
        private byte[] lengths;

        /** Reads the tree description; returns the position after it. */
        private int read(final byte @NotNull [] src, final int start) throws IOException {
            final int header = src[start] & 0xFF;
            final int[] weights = new int[256];
            int count;
            int pos;
            if (header < 128) {
                pos = start + 1 + header;
                count = fseWeights(src, start + 1, pos, weights);
            } else {
                count = header - 127;
                for (int i = 0; i < count; i++) {
                    final int b = src[start + 1 + i / 2] & 0xFF;
                    weights[i] = i % 2 == 0 ? b >> 4 : b & 15;
                }
                pos = start + 1 + (count + 1) / 2;
            }
            build(weights, count);

            return pos;
        }

        private static int fseWeights(final byte @NotNull [] src, final int start, final int end, final int @NotNull [] weights)
            throws IOException {
            final short[] norm = new short[256];
            final int[] header = Fse.readCounts(src, start, end, norm, 255);
            if (header[0] > 6) {
                throw new IOException("zstd Huffman weight table too precise");
            }
            final Fse table = Fse.build(norm, header[1], header[0]);
            final BackwardBits in = new BackwardBits(src, header[2], end);
            int state1 = in.read(table.log);
            int state2 = in.read(table.log);
            int n = 0;
            while (n < 254) {
                weights[n++] = table.symbol[state1];
                state1 = table.next(state1, in);
                if (in.overflow()) {
                    weights[n++] = table.symbol[state2];
                    break;
                }
                weights[n++] = table.symbol[state2];
                state2 = table.next(state2, in);
                if (in.overflow()) {
                    weights[n++] = table.symbol[state1];
                    break;
                }
            }

            return n;
        }

        /** The last weight is implied: it completes the sum of 2^(w-1) to a power of two. */
        private void build(final int @NotNull [] weights, final int count) throws IOException {
            long sum = 0;
            for (int i = 0; i < count; i++) {
                if (weights[i] > 11) {
                    throw new IOException("zstd Huffman weight too large");
                }
                sum += weights[i] == 0 ? 0 : 1L << weights[i] - 1;
            }
            if (sum == 0) {
                throw new IOException("empty zstd Huffman table");
            }
            maxBits = 64 - Long.numberOfLeadingZeros(sum);
            final long leftover = (1L << maxBits) - sum;
            if (Long.bitCount(leftover) != 1 || maxBits > 11) {
                throw new IOException("corrupted zstd Huffman weights");
            }
            weights[count] = Long.numberOfTrailingZeros(leftover) + 1;
            final int symbolCount = count + 1;
            final int[] rankStart = new int[maxBits + 2];
            for (int i = 0; i < symbolCount; i++) {
                if (weights[i] > 0) {
                    rankStart[weights[i]] += 1 << weights[i] - 1;
                }
            }
            int next = 0;
            for (int w = 1; w <= maxBits; w++) {
                final int size = rankStart[w];
                rankStart[w] = next;
                next += size;
            }
            symbols = new byte[1 << maxBits];
            lengths = new byte[1 << maxBits];
            for (int s = 0; s < symbolCount; s++) {
                final int w = weights[s];
                if (w > 0) {
                    final int from = rankStart[w];
                    final int to = from + (1 << w - 1);
                    Arrays.fill(symbols, from, to, (byte) s);
                    Arrays.fill(lengths, from, to, (byte) (maxBits + 1 - w));
                    rankStart[w] = to;
                }
            }
        }

        private void decode(final @NotNull BackwardBits in, final byte @NotNull [] dst, final int from, final int count) throws IOException {
            for (int i = 0; i < count; i++) {
                final int index = in.peek(maxBits);
                dst[from + i] = symbols[index];
                in.position -= lengths[index];
            }
            if (in.position != 0) {
                throw new IOException("corrupted zstd Huffman stream");
            }
        }
    }
}
