package tcm.util;

import java.io.IOException;
import java.util.Arrays;

import org.jetbrains.annotations.NotNull;

/** LZO1X decoder (as lzo1x_decompress_safe): squashfs and btrfs blocks. */
public final class Lzo {

    private static final int M2_MAX_OFFSET = 0x0800;
    private static final int M4_OFFSET = 0x4000;
    private static final int LITERAL_STATE = 4;

    private Lzo() {
    }

    public static byte @NotNull [] decompress(final byte @NotNull [] src, final int max) throws IOException {
        return decompress(src, 0, src.length, max);
    }

    /** Decodes src[off, off + len) into at most max bytes; stops at the end marker. */
    public static byte @NotNull [] decompress(final byte @NotNull [] src, final int off, final int len, final int max) throws IOException {
        final Decoder d = new Decoder(src, off, off + len, max);
        try {
            d.run();
        } catch (final ArrayIndexOutOfBoundsException e) {
            throw new IOException("damaged LZO block", e);
        }

        return d.op == max ? d.out : Arrays.copyOf(d.out, d.op);
    }

    private static final class Decoder {

        private final byte @NotNull [] in;
        private final int end;
        private final byte @NotNull [] out;
        private int ip;
        private int op;

        private Decoder(final byte @NotNull [] in, final int ip, final int end, final int max) {
            this.in = in;
            this.ip = ip;
            this.end = end;
            this.out = new byte[max];
        }

        private int next() {
            if (ip >= end) {
                throw new ArrayIndexOutOfBoundsException("LZO input overrun");
            }

            return in[ip++] & 0xFF;
        }

        /** A length continued by zero bytes (255 each) and a final non-zero byte. */
        private int extend(final int base) {
            int t = base;
            while (in[ip] == 0) {
                t += 255;
                ip++;
                if (ip >= end) {
                    throw new ArrayIndexOutOfBoundsException("LZO input overrun");
                }
            }

            return t + next();
        }

        private void literals(final int n) throws IOException {
            if (n > end - ip || n > out.length - op) {
                throw new IOException("damaged LZO block");
            }
            System.arraycopy(in, ip, out, op, n);
            ip += n;
            op += n;
        }

        private void match(final int from, final int length) throws IOException {
            if (from < 0 || length > out.length - op) {
                throw new IOException("damaged LZO block");
            }
            Lz4.copyMatch(out, op, op - from, length);
            op += length;
        }

        private void run() throws IOException {
            int state = 0;
            int t;
            if ((in[ip] & 0xFF) > 17) {
                t = next() - 17;
                literals(t);
                state = t < LITERAL_STATE ? t : LITERAL_STATE;
            }
            while (true) {
                t = next();
                final int from;
                final int length;
                final int nextState;
                if (t < 16) {
                    if (state == 0) {
                        literals((t == 0 ? extend(15) : t) + 3);
                        state = LITERAL_STATE;
                        continue;
                    }
                    nextState = t & 3;
                    if (state != LITERAL_STATE) {
                        from = op - 1 - (t >> 2) - (next() << 2);
                        length = 2;
                    } else {
                        from = op - (1 + M2_MAX_OFFSET) - (t >> 2) - (next() << 2);
                        length = 3;
                    }
                } else if (t >= 64) {
                    nextState = t & 3;
                    from = op - 1 - (t >> 2 & 7) - (next() << 3);
                    length = (t >> 5) + 1;
                } else if (t >= 32) {
                    final int l = t & 31;
                    length = (l == 0 ? extend(31) : l) + 2;
                    final int word = next() | next() << 8;
                    from = op - 1 - (word >> 2);
                    nextState = word & 3;
                } else {
                    final int l = t & 7;
                    length = (l == 0 ? extend(7) : l) + 2;
                    final int word = next() | next() << 8;
                    final int pos = op - ((t & 8) << 11) - (word >> 2);
                    if (pos == op) {
                        return;
                    }
                    from = pos - M4_OFFSET;
                    nextState = word & 3;
                }
                match(from, length);
                literals(nextState);
                state = nextState;
            }
        }
    }
}
