package tcm.core;

import java.io.IOException;
import java.util.Arrays;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * LZX decompressor (Microsoft cabinets, CHM help files). The stream is decoded in frames of up to 32 KiB of output;
 * the bit stream is realigned to 16 bits after each frame. Follows the format as decoded by libmspack: verbatim, aligned
 * and stored blocks, the Intel E8 call translation of the first 32768 frames. Not thread-safe.
 */
final class Lzx {

    static final int FRAME_SIZE = 32768;

    /** The compressed bytes come in pieces: one CAB data block, one read of a CHM section. */
    interface Input {

        /** The next piece of compressed data; null — the end (the decoder then reads zeros). */
        byte @Nullable [] next() throws IOException;
    }

    private static final int MIN_MATCH = 2;
    private static final int NUM_CHARS = 256;
    private static final int PRIMARY_LENGTHS = 7;
    private static final int SECONDARY_LENGTHS = 249;
    private static final int PRETREE_SYMBOLS = 20;
    private static final int ALIGNED_SYMBOLS = 8;
    private static final int MAX_MAIN_SYMBOLS = NUM_CHARS + 50 * 8;
    /** Runs of the pretree may write a little past the end of a length table. */
    private static final int LENGTH_SAFETY = 64;
    private static final int BLOCK_VERBATIM = 1;
    private static final int BLOCK_ALIGNED = 2;
    private static final int BLOCK_STORED = 3;
    private static final int E8_FRAMES = 32768;
    private static final int E8_TAIL = 10;
    private static final int[] EXTRA_BITS = new int[52];
    private static final int[] POSITION_BASE = new int[52];

    static {
        for (int i = 0, j = 0; i < EXTRA_BITS.length; i += 2) {
            EXTRA_BITS[i] = j;
            EXTRA_BITS[i + 1] = j;
            if (i != 0 && j < 17) {
                j++;
            }
        }
        for (int i = 0, j = 0; i < POSITION_BASE.length; i++) {
            POSITION_BASE[i] = j;
            j += 1 << EXTRA_BITS[i];
        }
    }

    private final byte[] window;
    private final int windowMask;
    private final int mainSymbols;
    private final byte[] mainLengths = new byte[MAX_MAIN_SYMBOLS + LENGTH_SAFETY];
    private final byte[] lengthLengths = new byte[SECONDARY_LENGTHS + LENGTH_SAFETY];
    private final byte[] alignedLengths = new byte[ALIGNED_SYMBOLS];
    private final byte[] pretreeLengths = new byte[PRETREE_SYMBOLS + LENGTH_SAFETY];
    private final Huffman mainTree = new Huffman(MAX_MAIN_SYMBOLS);
    private final Huffman lengthTree = new Huffman(SECONDARY_LENGTHS);
    private final Huffman alignedTree = new Huffman(ALIGNED_SYMBOLS);
    private final Huffman pretree = new Huffman(PRETREE_SYMBOLS);

    private int r0;
    private int r1;
    private int r2;
    private boolean headerRead;
    private int intelFileSize;
    private boolean intelStarted;
    private int intelPosition;
    private int frame;
    private int blockType;
    private int blockLength;
    private int blockRemaining;
    private int windowPos;

    private @Nullable Input input;
    private byte[] in = new byte[0];
    private int inPos;
    private long bitBuffer;
    private int bitsLeft;

    /** windowBits — 15..21 (32 KiB..2 MiB). */
    Lzx(final int windowBits) throws IOException {
        if (windowBits < 15 || windowBits > 21) {
            throw new IOException("Unsupported LZX window: 2^" + windowBits);
        }
        window = new byte[1 << windowBits];
        windowMask = window.length - 1;
        final int positionSlots = windowBits == 21 ? 50 : windowBits == 20 ? 42 : windowBits * 2;
        mainSymbols = NUM_CHARS + positionSlots * 8;
        reset();
    }

    /** Starts a new stream: the state, the length tables and the window position (a CHM reset point). */
    void reset() {
        r0 = 1;
        r1 = 1;
        r2 = 1;
        headerRead = false;
        intelFileSize = 0;
        intelStarted = false;
        intelPosition = 0;
        frame = 0;
        blockType = 0;
        blockLength = 0;
        blockRemaining = 0;
        windowPos = 0;
        Arrays.fill(mainLengths, (byte) 0);
        Arrays.fill(lengthLengths, (byte) 0);
    }

    /** Where the compressed bytes come from from now on; the bit buffer is emptied. */
    void input(final @NotNull Input source) {
        input = source;
        in = new byte[0];
        inPos = 0;
        bitBuffer = 0;
        bitsLeft = 0;
    }

    /**
     * Decodes the next frame of frameSize (at most 32 KiB) bytes into out. The last frame of a stream may be encoded past
     * the real end of the data (CHM pads it to a whole frame): there the last match may overshoot.
     */
    void decodeFrame(final byte @NotNull [] out, final int off, final int frameSize, final boolean last) throws IOException {
        final int start;
        try {
            start = decode(frameSize, last);
        } catch (final ArrayIndexOutOfBoundsException e) {
            throw new IOException("Corrupted LZX data", e);
        }
        System.arraycopy(window, start, out, off, frameSize);
        if (windowPos == window.length) {
            windowPos = 0;
        }
        if (intelStarted && intelFileSize != 0 && frame < E8_FRAMES && frameSize > E8_TAIL) {
            translateCalls(out, off, frameSize);
        }
        intelPosition += frameSize;
        frame++;
    }

    /** Returns the window position where the frame starts. */
    private int decode(final int frameSize, final boolean last) throws IOException {
        if (frameSize <= 0 || frameSize > FRAME_SIZE) {
            throw new IOException("Bad LZX frame size: " + frameSize);
        }
        if (!headerRead) {
            if (bits(1) != 0) {
                final int high = bits(16);
                intelFileSize = high << 16 | bits(16);
            }
            headerRead = true;
        }
        final int frameStart = windowPos;
        int todo = frameSize;
        while (todo > 0) {
            if (blockRemaining == 0) {
                readBlockHeader();
            }
            final int run = Math.min(blockRemaining, todo);
            blockRemaining -= run;
            final int overrun = blockType == BLOCK_STORED ? copyStored(run) : decodeRun(run);
            // the last match may run into the next block
            if (overrun > blockRemaining) {
                throw new IOException("LZX match past the end of a block");
            }
            blockRemaining -= overrun;
            todo -= run + overrun;
        }
        if (windowPos - frameStart != frameSize && !last) {
            throw new IOException("LZX data decodes past the end of a frame");
        }
        // frames start on 16-bit boundaries of the input
        if (bitsLeft > 0) {
            ensure(16);
        }
        if ((bitsLeft & 15) != 0) {
            remove(bitsLeft & 15);
        }

        return frameStart;
    }

    private void readBlockHeader() throws IOException {
        if (blockType == BLOCK_STORED && (blockLength & 1) != 0) {
            // a stored block of odd length is padded to 16 bits
            nextByte();
        }
        blockType = bits(3);
        final int high = bits(16);
        blockLength = high << 8 | bits(8);
        blockRemaining = blockLength;
        switch (blockType) {
            case BLOCK_ALIGNED, BLOCK_VERBATIM -> {
                if (blockType == BLOCK_ALIGNED) {
                    for (int i = 0; i < ALIGNED_SYMBOLS; i++) {
                        alignedLengths[i] = (byte) bits(3);
                    }
                    alignedTree.build(alignedLengths, ALIGNED_SYMBOLS);
                }
                readLengths(mainLengths, 0, NUM_CHARS);
                readLengths(mainLengths, NUM_CHARS, mainSymbols);
                mainTree.build(mainLengths, mainSymbols);
                if (mainLengths[0xE8] != 0) {
                    intelStarted = true;
                }
                readLengths(lengthLengths, 0, SECONDARY_LENGTHS);
                lengthTree.build(lengthLengths, SECONDARY_LENGTHS);
            }
            case BLOCK_STORED -> {
                intelStarted = true;
                // 1..16 bits of padding up to the 16-bit boundary
                if (bitsLeft == 0) {
                    ensure(16);
                }
                bitsLeft = 0;
                bitBuffer = 0;
                r0 = le32();
                r1 = le32();
                r2 = le32();
            }
            default -> throw new IOException("Bad LZX block type: " + blockType);
        }
    }

    /** Code lengths as deltas to the previous ones, coded with a pretree. */
    private void readLengths(final byte @NotNull [] lengths, final int first, final int last) throws IOException {
        for (int i = 0; i < PRETREE_SYMBOLS; i++) {
            pretreeLengths[i] = (byte) bits(4);
        }
        pretree.build(pretreeLengths, PRETREE_SYMBOLS);
        int x = first;
        while (x < last) {
            final int z = pretree.decode(this);
            if (z == 17) {
                for (int run = bits(4) + 4; run > 0; run--) {
                    lengths[x++] = 0;
                }
            } else if (z == 18) {
                for (int run = bits(5) + 20; run > 0; run--) {
                    lengths[x++] = 0;
                }
            } else if (z == 19) {
                final int run = bits(1) + 4;
                final byte len = delta(lengths[x], pretree.decode(this));
                for (int i = 0; i < run; i++) {
                    lengths[x++] = len;
                }
            } else {
                lengths[x] = delta(lengths[x], z);
                x++;
            }
        }
    }

    private static byte delta(final byte previous, final int code) {
        final int len = previous - code;

        return (byte) (len < 0 ? len + 17 : len);
    }

    /** Decodes literals and matches for run bytes; returns how far the last match went past run. */
    private int decodeRun(final int length) throws IOException {
        int run = length;
        while (run > 0) {
            int symbol = mainTree.decode(this);
            if (symbol < NUM_CHARS) {
                window[windowPos++] = (byte) symbol;
                run--;
                continue;
            }
            symbol -= NUM_CHARS;
            int matchLength = symbol & PRIMARY_LENGTHS;
            if (matchLength == PRIMARY_LENGTHS) {
                matchLength += lengthTree.decode(this);
            }
            matchLength += MIN_MATCH;
            copyMatch(matchOffset(symbol >>> 3), matchLength);
            run -= matchLength;
        }

        return -run;
    }

    private int matchOffset(final int slot) throws IOException {
        final int offset;
        switch (slot) {
            case 0 -> offset = r0;
            case 1 -> {
                offset = r1;
                r1 = r0;
                r0 = offset;
            }
            case 2 -> {
                offset = r2;
                r2 = r0;
                r0 = offset;
            }
            default -> {
                offset = blockType == BLOCK_ALIGNED ? alignedOffset(slot) : verbatimOffset(slot);
                r2 = r1;
                r1 = r0;
                r0 = offset;
            }
        }

        return offset;
    }

    private int verbatimOffset(final int slot) throws IOException {
        return slot == 3 ? 1 : POSITION_BASE[slot] - 2 + bits(EXTRA_BITS[slot]);
    }

    private int alignedOffset(final int slot) throws IOException {
        final int extra = EXTRA_BITS[slot];
        int offset = POSITION_BASE[slot] - 2;
        if (extra > 3) {
            offset += bits(extra - 3) << 3;
            offset += alignedTree.decode(this);
        } else if (extra == 3) {
            offset += alignedTree.decode(this);
        } else if (extra > 0) {
            offset += bits(extra);
        } else {
            offset = 1;
        }

        return offset;
    }

    private void copyMatch(final int offset, final int length) throws IOException {
        if (windowPos + length > window.length || offset <= 0 || offset > window.length) {
            throw new IOException("Bad LZX match");
        }
        int src = (windowPos - offset) & windowMask;
        for (int i = 0; i < length; i++) {
            window[windowPos++] = window[src];
            src = (src + 1) & windowMask;
        }
    }

    private int copyStored(final int length) throws IOException {
        for (int i = 0; i < length; i++) {
            window[windowPos++] = (byte) nextByte();
        }

        return 0;
    }

    /** E8 (CALL) operands were turned from relative into absolute addresses by the compressor; restores them. */
    private void translateCalls(final byte @NotNull [] out, final int off, final int frameSize) {
        int position = intelPosition;
        int i = off;
        final int end = off + frameSize - E8_TAIL;
        while (i < end) {
            if ((out[i++] & 0xFF) != 0xE8) {
                position++;
                continue;
            }
            final int abs = out[i] & 0xFF | (out[i + 1] & 0xFF) << 8 | (out[i + 2] & 0xFF) << 16 | out[i + 3] << 24;
            if (abs >= -position && abs < intelFileSize) {
                final int rel = abs >= 0 ? abs - position : abs + intelFileSize;
                out[i] = (byte) rel;
                out[i + 1] = (byte) (rel >> 8);
                out[i + 2] = (byte) (rel >> 16);
                out[i + 3] = (byte) (rel >> 24);
            }
            i += 4;
            position += 5;
        }
    }

    // ---- input ----

    private int nextByte() throws IOException {
        while (inPos >= in.length) {
            final byte[] piece = input == null ? null : input.next();
            if (piece == null) {
                input = null;

                return 0;
            }
            in = piece;
            inPos = 0;
        }

        return in[inPos++] & 0xFF;
    }

    private int le32() throws IOException {
        return nextByte() | nextByte() << 8 | nextByte() << 16 | nextByte() << 24;
    }

    /** Bits come in little-endian 16-bit words, most significant bit first. */
    private void ensure(final int n) throws IOException {
        while (bitsLeft < n) {
            final int lo = nextByte();
            final int hi = nextByte();
            bitBuffer = bitBuffer << 16 | hi << 8 | lo;
            bitsLeft += 16;
        }
    }

    private int peek(final int n) {
        return (int) (bitBuffer >>> (bitsLeft - n)) & ((1 << n) - 1);
    }

    private void remove(final int n) {
        bitsLeft -= n;
        bitBuffer &= (1L << bitsLeft) - 1;
    }

    private int bits(final int n) throws IOException {
        if (n == 0) {
            return 0;
        }
        ensure(n);
        final int v = peek(n);
        remove(n);

        return v;
    }

    /** Canonical Huffman code, at most 16 bits; short codes through a table, long ones counted out. */
    private static final class Huffman {

        private static final int MAX_BITS = 16;
        private static final int TABLE_BITS = 10;

        private final int[] table = new int[1 << TABLE_BITS];
        private final int[] count = new int[MAX_BITS + 1];
        private final int[] symbols;
        private boolean empty;

        Huffman(final int maxSymbols) {
            symbols = new int[maxSymbols];
        }

        void build(final byte @NotNull [] lengths, final int n) throws IOException {
            Arrays.fill(count, 0);
            for (int s = 0; s < n; s++) {
                count[lengths[s]]++;
            }
            int left = 1;
            for (int len = 1; len <= MAX_BITS; len++) {
                left = (left << 1) - count[len];
                if (left < 0) {
                    throw new IOException("Bad LZX Huffman table");
                }
            }
            empty = count[0] == n;
            final int[] offsets = new int[MAX_BITS + 2];
            for (int len = 1; len <= MAX_BITS; len++) {
                offsets[len + 1] = offsets[len] + count[len];
            }
            for (int s = 0; s < n; s++) {
                if (lengths[s] != 0) {
                    symbols[offsets[lengths[s]]++] = s;
                }
            }
            Arrays.fill(table, -1);
            int code = 0;
            int k = 0;
            for (int len = 1; len <= TABLE_BITS; len++) {
                for (int i = 0; i < count[len]; i++) {
                    final int first = code << (TABLE_BITS - len);
                    Arrays.fill(table, first, first + (1 << (TABLE_BITS - len)), symbols[k++] << 5 | len);
                    code++;
                }
                code <<= 1;
            }
        }

        int decode(final @NotNull Lzx lzx) throws IOException {
            if (empty) {
                throw new IOException("LZX symbol from an empty Huffman table");
            }
            lzx.ensure(MAX_BITS);
            final int e = table[lzx.peek(TABLE_BITS)];
            if (e >= 0) {
                lzx.remove(e & 31);

                return e >>> 5;
            }
            int first = 0;
            int index = 0;
            for (int len = 1; len <= MAX_BITS; len++) {
                final int code = lzx.peek(len);
                if (code - first < count[len]) {
                    lzx.remove(len);

                    return symbols[index + code - first];
                }
                index += count[len];
                first = (first + count[len]) << 1;
            }

            throw new IOException("Bad LZX Huffman code");
        }
    }
}
