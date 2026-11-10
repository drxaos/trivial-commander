package tcm.core;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Files compressed by macOS (decmpfs, on APFS and HFS+): the "com.apple.decmpfs" attribute holds a header and either the
 * whole compressed data or a pointer to the resource fork. zlib only (the most common method); LZVN/LZFSE fail on read.
 */
final class Decmpfs {

    static final String XATTR = "com.apple.decmpfs";
    private static final int MAGIC = 0x636D7066;
    private static final int HEADER = 16;
    private static final int UNCOMPRESSED_XATTR = 1;
    private static final int ZLIB_XATTR = 3;
    private static final int ZLIB_RSRC = 4;
    private static final int BLOCK = 64 * 1024;
    private static final int MAX_XATTR = 64 * 1024 * 1024;

    private Decmpfs() {
    }

    /** True if the attribute is a decmpfs header. */
    static boolean matches(final byte @Nullable [] header) {
        return header != null && header.length >= HEADER && le(header).getInt(0) == MAGIC;
    }

    /** The uncompressed size stored in the header. */
    static long size(final byte @NotNull [] header) {
        return le(header).getLong(8);
    }

    /** The uncompressed content; the resource fork is only opened for the methods that keep data there. */
    static @NotNull InputStream open(final byte @NotNull [] header, final @NotNull IoSupplier<BlockSource> resourceFork) throws IOException {
        final int method = le(header).getInt(4);
        final long size = size(header);
        switch (method) {
            case UNCOMPRESSED_XATTR -> {
                return new ByteArrayInputStream(header, HEADER, header.length - HEADER);
            }
            case ZLIB_XATTR -> {
                if (size > MAX_XATTR || size < 0) {
                    throw new IOException("damaged compressed file");
                }

                return new ByteArrayInputStream(block(Arrays.copyOfRange(header, HEADER, header.length), (int) size));
            }
            case ZLIB_RSRC -> {
                final BlockSource fork = resourceFork.get();
                if (fork == null) {
                    throw new IOException("compressed file without its resource fork");
                }

                return new ForkStream(fork, size);
            }
            default -> throw new IOException("file compression method " + method + " (LZVN/LZFSE) is not supported");
        }
    }

    /** A byte array as a source — an attribute stored inline. */
    static @NotNull BlockSource bytes(final byte @NotNull [] data) {
        return new BlockSource() {
            @Override
            public long size() {
                return data.length;
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                if (pos < 0 || pos + len > data.length) {
                    throw new EOFException("damaged resource fork");
                }
                System.arraycopy(data, (int) pos, b, off, len);
            }
        };
    }

    private static @NotNull ByteBuffer le(final byte @NotNull [] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** A zlib block of a compressed file; a first byte with the low nibble 0xF marks data stored as is. */
    private static byte @NotNull [] block(final byte @NotNull [] packed, final int size) throws IOException {
        if (packed.length > 0 && (packed[0] & 0x0F) == 0x0F) {
            return Arrays.copyOfRange(packed, 1, Math.min(packed.length, size + 1));
        }
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

            return n == size ? out : Arrays.copyOf(out, n);
        } catch (final DataFormatException e) {
            throw new IOException("damaged compressed file", e);
        } finally {
            inflater.end();
        }
    }

    /**
     * A file compressed into its resource fork: after the 256-byte fork header, at the data offset, a big-endian length,
     * then a little-endian block count and {offset, size} table (offsets from the table start), each block 64 KiB unpacked.
     */
    private static final class ForkStream extends InputStream {

        private final @NotNull BlockSource fork;
        private final long size;
        private long @Nullable [] table;
        private long tableBase;
        private byte @NotNull [] current = new byte[0];
        private int currentPos;
        private int nextBlock;
        private long produced;

        private ForkStream(final @NotNull BlockSource fork, final long size) {
            this.fork = fork;
            this.size = size;
        }

        private void readTable() throws IOException {
            final ByteBuffer header = ByteBuffer.wrap(fork.read(0, 16));
            final long dataOffset = Integer.toUnsignedLong(header.getInt(0));
            tableBase = dataOffset + 4;
            final int count = le(fork.read(tableBase, 4)).getInt(0);
            if (count < 0 || count > MAX_XATTR / 8) {
                throw new IOException("damaged resource fork");
            }
            final ByteBuffer t = le(fork.read(tableBase + 4, count * 8));
            table = new long[count * 2];
            for (int i = 0; i < count; i++) {
                table[i * 2] = Integer.toUnsignedLong(t.getInt(i * 8));
                table[i * 2 + 1] = Integer.toUnsignedLong(t.getInt(i * 8 + 4));
            }
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
            if (currentPos >= current.length) {
                if (produced >= size) {
                    return -1;
                }
                if (table == null) {
                    readTable();
                }
                if (nextBlock * 2 >= table.length) {
                    throw new EOFException("truncated compressed file");
                }
                final long packed = table[nextBlock * 2 + 1];
                if (packed > BLOCK * 2L) {
                    throw new IOException("damaged compressed file");
                }
                final int unpacked = (int) Math.min(BLOCK, size - produced);
                current = block(fork.read(tableBase + table[nextBlock * 2], (int) packed), unpacked);
                currentPos = 0;
                nextBlock++;
                produced += current.length;
                if (current.length == 0) {
                    throw new EOFException("truncated compressed file");
                }
            }
            final int n = Math.min(len, current.length - currentPos);
            System.arraycopy(current, currentPos, b, off, n);
            currentPos += n;

            return n;
        }
    }
}
