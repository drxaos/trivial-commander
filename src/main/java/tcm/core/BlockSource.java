package tcm.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.jetbrains.annotations.NotNull;

/** A random-access byte range: a disk image partition read by file system parsers. Implementations are thread-safe. */
interface BlockSource {

    long size();

    /** Reads exactly len bytes at pos; EOFException if the range goes past the end. */
    void read(long pos, byte @NotNull [] b, int off, int len) throws IOException;

    default byte @NotNull [] read(final long pos, final int len) throws IOException {
        final byte[] b = new byte[len];
        read(pos, b, 0, len);

        return b;
    }

    /** The whole file behind the channel; positional reads, so concurrent readers do not disturb each other. */
    static @NotNull BlockSource of(final @NotNull FileChannel ch) throws IOException {
        final long size = ch.size();

        return new BlockSource() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                if (pos < 0 || pos + len > size) {
                    throw new EOFException("read past the end of the file");
                }
                final ByteBuffer buf = ByteBuffer.wrap(b, off, len);
                while (buf.hasRemaining()) {
                    if (ch.read(buf, pos + buf.position() - off) < 0) {
                        throw new EOFException("read past the end of the file");
                    }
                }
            }
        };
    }

    /** The range [offset, offset + length) of the source (cut to its end) as a source of its own: a partition of a disk. */
    static @NotNull BlockSource slice(final @NotNull BlockSource source, final long offset, final long length) {
        final long size = Math.max(0, Math.min(length, source.size() - offset));

        return new BlockSource() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                if (pos < 0 || pos + len > size) {
                    throw new EOFException("read past the end of the partition");
                }
                source.read(offset + pos, b, off, len);
            }
        };
    }

    /** A sequential stream over the range [offset, offset + length) of the source. */
    static @NotNull InputStream stream(final @NotNull BlockSource source, final long offset, final long length) {
        return new InputStream() {
            private long pos;

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
                if (pos >= length) {
                    return -1;
                }
                final int n = (int) Math.min(len, length - pos);
                source.read(offset + pos, b, off, n);
                pos += n;

                return n;
            }

            @Override
            public long skip(final long n) {
                final long step = Math.max(0, Math.min(n, length - pos));
                pos += step;

                return step;
            }
        };
    }
}
