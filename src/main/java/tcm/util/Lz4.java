package tcm.util;

import java.io.IOException;
import java.util.Arrays;

import org.jetbrains.annotations.NotNull;

/** LZ4 block format decoder (no frame): squashfs and ZFS blocks. */
public final class Lz4 {

    private static final int MIN_MATCH = 4;
    private static final int RUN_MASK = 15;

    private Lz4() {
    }

    public static byte @NotNull [] decompress(final byte @NotNull [] src, final int max) throws IOException {
        return decompress(src, 0, src.length, max);
    }

    /** Decodes src[off, off + len) into at most max bytes. */
    public static byte @NotNull [] decompress(final byte @NotNull [] src, final int off, final int len, final int max) throws IOException {
        final byte[] out = new byte[max];
        final int end = off + len;
        int ip = off;
        int op = 0;
        try {
            while (ip < end) {
                final int token = src[ip++] & 0xFF;
                int literals = token >>> 4;
                if (literals == RUN_MASK) {
                    int b;
                    do {
                        b = src[ip++] & 0xFF;
                        literals += b;
                    } while (b == 255);
                }
                if (literals > end - ip || literals > max - op) {
                    throw new IOException("damaged LZ4 block");
                }
                System.arraycopy(src, ip, out, op, literals);
                ip += literals;
                op += literals;
                if (ip >= end) {
                    break;
                }
                final int offset = src[ip] & 0xFF | (src[ip + 1] & 0xFF) << 8;
                ip += 2;
                int length = token & RUN_MASK;
                if (length == RUN_MASK) {
                    int b;
                    do {
                        b = src[ip++] & 0xFF;
                        length += b;
                    } while (b == 255);
                }
                length += MIN_MATCH;
                if (offset == 0 || offset > op || length > max - op) {
                    throw new IOException("damaged LZ4 block");
                }
                copyMatch(out, op, offset, length);
                op += length;
            }
        } catch (final ArrayIndexOutOfBoundsException e) {
            throw new IOException("damaged LZ4 block", e);
        }

        return op == max ? out : Arrays.copyOf(out, op);
    }

    /** Copies an LZ77 match that may overlap its own output. */
    static void copyMatch(final byte @NotNull [] out, final int op, final int distance, final int length) {
        if (distance >= length) {
            System.arraycopy(out, op - distance, out, op, length);
        } else {
            for (int i = 0; i < length; i++) {
                out[op + i] = out[op - distance + i];
            }
        }
    }
}
