package tcm.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import org.jetbrains.annotations.NotNull;

/**
 * The bzip2 of NSIS: the standard blocks (Huffman, MTF, BWT, run lengths) without the stream header ("BZh9") and checksums;
 * a block starts with the byte 0x31, the stream ends with 0x17. Blocks are up to 900 000 bytes, never randomised.
 */
final class NsisBzip2InputStream extends InputStream {

    private static final int BLOCK_START = 0x31;
    private static final int STREAM_END = 0x17;
    private static final int MAX_BLOCK = 900_000;
    private static final int MAX_GROUPS = 6;
    private static final int MIN_GROUPS = 2;
    private static final int GROUP_SIZE = 50;
    private static final int MAX_ALPHA = 258;
    private static final int MAX_CODE_LEN = 20;
    private static final int RUN_A = 0;
    private static final int RUN_B = 1;
    /** After 4 equal bytes the next one is how many more of them follow. */
    private static final int RUN_LENGTH = 4;

    private final @NotNull InputStream in;
    private int bitBuf;
    private int bitCount;
    private final int[] tt = new int[MAX_BLOCK];
    /** Bytes of the current block still to come out of the BWT. */
    private int left;
    private int tPos;
    private int last = -1;
    private int run;
    private int repeat;
    private boolean end;

    NsisBzip2InputStream(final @NotNull InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        while (true) {
            if (repeat > 0) {
                repeat--;

                return last;
            }
            if (left == 0) {
                if (end || !readBlock()) {
                    return -1;
                }
                continue;
            }
            tPos = tt[tPos];
            final int b = tPos & 0xFF;
            tPos >>>= 8;
            left--;
            if (run == RUN_LENGTH) {
                repeat = b;
                run = 0;
                continue;
            }
            if (b == last) {
                run++;
            } else {
                last = b;
                run = 1;
            }

            return b;
        }
    }

    @Override
    public int read(final byte @NotNull [] b, final int off, final int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        int n = 0;
        while (n < len) {
            final int c = read();
            if (c < 0) {
                break;
            }
            b[off + n++] = (byte) c;
        }

        return n == 0 ? -1 : n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Decodes the next block into tt (the BWT vector); false — the end of the stream. */
    private boolean readBlock() throws IOException {
        final int signature = bits(8);
        if (signature == STREAM_END) {
            end = true;

            return false;
        }
        if (signature != BLOCK_START) {
            throw new IOException("Bad NSIS bzip2 block");
        }
        final int origPtr = bits(24);
        final byte[] symbols = usedSymbols();
        final int alpha = symbols.length + 2;
        final int groups = bits(3);
        final int selectorCount = bits(15);
        if (groups < MIN_GROUPS || groups > MAX_GROUPS || selectorCount == 0) {
            throw new IOException("Bad NSIS bzip2 tables");
        }
        final byte[] selectors = selectors(groups, selectorCount);
        final Huffman[] tables = new Huffman[groups];
        for (int t = 0; t < groups; t++) {
            tables[t] = new Huffman(codeLengths(alpha));
        }
        final int count = decodeSymbols(symbols, selectors, tables);
        if (origPtr >= count) {
            throw new IOException("Bad NSIS bzip2 origin");
        }
        inverseBwt(count);
        tPos = tt[origPtr] >>> 8;
        left = count;
        last = -1;
        run = 0;

        return true;
    }

    /** The bytes the block uses: 16 bits for groups of 16, then 16 bits for each group present. */
    private byte @NotNull [] usedSymbols() throws IOException {
        final int groupsUsed = bits(16);
        final byte[] all = new byte[256];
        int n = 0;
        for (int i = 0; i < 16; i++) {
            if ((groupsUsed & 0x8000 >>> i) != 0) {
                final int used = bits(16);
                for (int j = 0; j < 16; j++) {
                    if ((used & 0x8000 >>> j) != 0) {
                        all[n++] = (byte) (i * 16 + j);
                    }
                }
            }
        }
        if (n == 0) {
            throw new IOException("Bad NSIS bzip2 symbol map");
        }
        final byte[] used = new byte[n];
        System.arraycopy(all, 0, used, 0, n);

        return used;
    }

    /** Which table codes each 50 symbols: MTF indices written in unary. */
    private byte @NotNull [] selectors(final int groups, final int count) throws IOException {
        final byte[] mtf = new byte[groups];
        for (int i = 0; i < groups; i++) {
            mtf[i] = (byte) i;
        }
        final byte[] selectors = new byte[count];
        for (int i = 0; i < count; i++) {
            int j = 0;
            while (bits(1) == 1) {
                if (++j >= groups) {
                    throw new IOException("Bad NSIS bzip2 selector");
                }
            }
            final byte v = mtf[j];
            System.arraycopy(mtf, 0, mtf, 1, j);
            mtf[0] = v;
            selectors[i] = v;
        }

        return selectors;
    }

    /** Code lengths as deltas: start with 5 bits, then per symbol "1x" steps (x: 0 — longer, 1 — shorter) ended by 0. */
    private byte @NotNull [] codeLengths(final int alpha) throws IOException {
        final byte[] lengths = new byte[alpha];
        int len = bits(5);
        for (int i = 0; i < alpha; i++) {
            while (bits(1) == 1) {
                len += bits(1) == 0 ? 1 : -1;
            }
            if (len < 1 || len > MAX_CODE_LEN) {
                throw new IOException("Bad NSIS bzip2 code length");
            }
            lengths[i] = (byte) len;
        }

        return lengths;
    }

    /** Huffman → MTF → zero runs (RUNA/RUNB): the BWT bytes into tt (low byte); the count of them. */
    private int decodeSymbols(final byte @NotNull [] symbols, final byte @NotNull [] selectors, final Huffman @NotNull [] tables)
        throws IOException {
        final int eob = symbols.length + 1;
        final byte[] mtf = symbols.clone();
        int count = 0;
        int group = -1;
        int groupLeft = 0;
        int runLength = 0;
        int runWeight = 1;
        while (true) {
            if (groupLeft == 0) {
                if (++group >= selectors.length) {
                    throw new IOException("Bad NSIS bzip2 data: selectors run out");
                }
                groupLeft = GROUP_SIZE;
            }
            groupLeft--;
            final int sym = tables[selectors[group]].decode();
            if (sym == RUN_A || sym == RUN_B) {
                runLength += runWeight << sym;
                runWeight <<= 1;
                if (runLength > MAX_BLOCK) {
                    throw new IOException("Bad NSIS bzip2 run");
                }
                continue;
            }
            if (runLength > 0) {
                count = fill(count, mtf[0] & 0xFF, runLength);
                runLength = 0;
                runWeight = 1;
            }
            if (sym == eob) {
                return count;
            }
            final int index = sym - 1;
            final byte v = mtf[index];
            System.arraycopy(mtf, 0, mtf, 1, index);
            mtf[0] = v;
            count = fill(count, v & 0xFF, 1);
        }
    }

    private int fill(final int count, final int value, final int times) throws IOException {
        if (count + times > MAX_BLOCK) {
            throw new IOException("NSIS bzip2 block too long");
        }
        for (int i = 0; i < times; i++) {
            tt[count + i] = value;
        }

        return count + times;
    }

    /** tt[i] = byte | index of the next byte << 8: following the links from origPtr gives the block. */
    private void inverseBwt(final int count) {
        final int[] start = new int[257];
        for (int i = 0; i < count; i++) {
            start[(tt[i] & 0xFF) + 1]++;
        }
        for (int i = 1; i < start.length; i++) {
            start[i] += start[i - 1];
        }
        for (int i = 0; i < count; i++) {
            final int b = tt[i] & 0xFF;
            tt[start[b]++] |= i << 8;
        }
    }

    private int bits(final int n) throws IOException {
        while (bitCount < n) {
            final int b = in.read();
            if (b < 0) {
                throw new EOFException("Truncated NSIS bzip2 data");
            }
            bitBuf = bitBuf << 8 | b;
            bitCount += 8;
        }
        bitCount -= n;

        return bitBuf >>> bitCount & (1 << n) - 1;
    }

    /** Canonical Huffman decoding: codes of each length are consecutive, assigned in symbol order. */
    private final class Huffman {

        private final int minLen;
        private final int[] limit = new int[MAX_CODE_LEN + 2];
        private final int[] base = new int[MAX_CODE_LEN + 2];
        private final int[] perm = new int[MAX_ALPHA];

        Huffman(final byte @NotNull [] lengths) {
            int min = MAX_CODE_LEN;
            int max = 0;
            for (final byte len : lengths) {
                min = Math.min(min, len);
                max = Math.max(max, len);
            }
            minLen = min;
            int p = 0;
            int code = 0;
            for (int len = min; len <= max; len++) {
                final int first = p;
                for (int s = 0; s < lengths.length; s++) {
                    if (lengths[s] == len) {
                        perm[p++] = s;
                    }
                }
                // codes of this length: code .. code + (p - first) - 1, the symbols perm[first..p)
                base[len] = first - code;
                code += p - first;
                limit[len] = code - 1;
                code <<= 1;
            }
            for (int len = max + 1; len < limit.length; len++) {
                limit[len] = Integer.MAX_VALUE;
            }
            for (int len = 0; len < min; len++) {
                limit[len] = -1;
            }
        }

        int decode() throws IOException {
            int len = minLen;
            int code = bits(len);
            while (code > limit[len]) {
                if (++len > MAX_CODE_LEN) {
                    throw new IOException("Bad NSIS bzip2 code");
                }
                code = code << 1 | bits(1);
            }
            final int index = code + base[len];
            if (index < 0 || index >= perm.length) {
                throw new IOException("Bad NSIS bzip2 code");
            }

            return perm[index];
        }
    }
}
