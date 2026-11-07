package tcm.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.jetbrains.annotations.NotNull;

/**
 * 7-Zip's BCJ2 filter (x86 executables), decoding side: the main stream with the E8/E9/Jcc opcodes, their CALL and JUMP targets
 * (big-endian absolute addresses) and a range-coded stream of flags telling which opcodes were converted.
 */
final class Bcj2InputStream extends InputStream {

    private static final int NUM_BIT_MODEL_TOTAL_BITS = 11;
    private static final int BIT_MODEL_TOTAL = 1 << NUM_BIT_MODEL_TOTAL_BITS;
    private static final int NUM_MOVE_BITS = 5;
    private static final int TOP_VALUE = 1 << 24;

    private final @NotNull InputStream main;
    private final @NotNull InputStream call;
    private final @NotNull InputStream jump;
    private final @NotNull InputStream rc;
    private final long size;
    /** 256 for E8 by the previous byte, one for E9, one for Jcc. */
    private final int[] probs = new int[256 + 2];
    private final byte[] pending = new byte[4];
    private int pendingPos;
    private int pendingLen;
    private long outPos;
    private int prevByte;
    private int range;
    private int code;
    private boolean initialized;

    Bcj2InputStream(final @NotNull InputStream main, final @NotNull InputStream call, final @NotNull InputStream jump,
        final @NotNull InputStream rc, final long size) {
        this.main = main;
        this.call = call;
        this.jump = jump;
        this.rc = rc;
        this.size = size;
        Arrays.fill(probs, BIT_MODEL_TOTAL >>> 1);
    }

    @Override
    public int read() throws IOException {
        final byte[] one = new byte[1];

        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(final byte @NotNull [] b, final int off, final int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!initialized) {
            range = 0xFFFFFFFF;
            for (int i = 0; i < 5; i++) {
                code = code << 8 | rcByte();
            }
            initialized = true;
        }
        int n = 0;
        while (n < len && outPos < size) {
            if (pendingPos < pendingLen) {
                b[off + n++] = pending[pendingPos++];
                outPos++;
                continue;
            }
            final int c = main.read();
            if (c < 0) {
                throw new EOFException("BCJ2: unexpected end of the main stream");
            }
            b[off + n++] = (byte) c;
            outPos++;
            if (!isJump(prevByte, c) || outPos == size) {
                prevByte = c;
                continue;
            }
            final int prob = c == 0xE8 ? prevByte : c == 0xE9 ? 256 : 257;
            if (bit(prob) == 0) {
                prevByte = c;
                continue;
            }
            final int dest = int32(c == 0xE8 ? call : jump) - (int) (outPos + 4);
            pending[0] = (byte) dest;
            pending[1] = (byte) (dest >>> 8);
            pending[2] = (byte) (dest >>> 16);
            pending[3] = (byte) (dest >>> 24);
            pendingPos = 0;
            pendingLen = 4;
            prevByte = dest >>> 24 & 0xFF;
        }

        return n == 0 ? -1 : n;
    }

    private static boolean isJump(final int b0, final int b1) {
        return (b1 & 0xFE) == 0xE8 || b0 == 0x0F && (b1 & 0xF0) == 0x80;
    }

    private int bit(final int index) throws IOException {
        final int p = probs[index];
        final int bound = (range >>> NUM_BIT_MODEL_TOTAL_BITS) * p;
        final int bit;
        if (Integer.compareUnsigned(code, bound) < 0) {
            range = bound;
            probs[index] = p + (BIT_MODEL_TOTAL - p >>> NUM_MOVE_BITS);
            bit = 0;
        } else {
            range -= bound;
            code -= bound;
            probs[index] = p - (p >>> NUM_MOVE_BITS);
            bit = 1;
        }
        if (Integer.compareUnsigned(range, TOP_VALUE) < 0) {
            range <<= 8;
            code = code << 8 | rcByte();
        }

        return bit;
    }

    /** The encoder flushes the range coder, so an early end means damaged data; the entry CRC reports it. */
    private int rcByte() throws IOException {
        final int c = rc.read();

        return Math.max(c, 0);
    }

    private static int int32(final @NotNull InputStream in) throws IOException {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            final int c = in.read();
            if (c < 0) {
                throw new EOFException("BCJ2: unexpected end of the call/jump stream");
            }
            v = v << 8 | c;
        }

        return v;
    }

    @Override
    public void close() throws IOException {
        try (main; call; jump; rc) {
            // all four inputs are closed
        }
    }
}
