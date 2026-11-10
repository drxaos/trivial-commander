package tcm.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A virtual disk stored as a map of fixed-size units (blocks, clusters, grains) to offsets in the image file. Unmapped units
 * read from the parent image (a backing file) or as zeros. Thread-safe: positional reads, tables immutable or cached under a lock.
 */
abstract class SparseDisk implements VirtualDisks.Disk {

    /** The unit is not allocated: the parent's data or zeros. */
    static final long HOLE = -1;
    /** The unit is explicitly zero. */
    static final long ZERO = -2;
    /** The unit is stored compressed: see {@link #inflate}. */
    static final long COMPRESSED = -3;

    private static final int INFLATED_CACHE = 16;

    protected final @NotNull FileChannel ch;
    protected final long fileSize;
    private final long size;
    private final int unit;
    private final @Nullable VirtualDisks.Disk parent;
    private final Map<Long, byte[]> inflated = lru(INFLATED_CACHE);

    protected SparseDisk(
        final @NotNull FileChannel ch,
        final long size,
        final int unit,
        final @Nullable VirtualDisks.Disk parent
    ) throws IOException {
        if (unit <= 0 || size < 0) {
            throw new IOException("Damaged virtual disk header");
        }
        this.ch = ch;
        this.fileSize = ch.size();
        this.size = size;
        this.unit = unit;
        this.parent = parent;
    }

    /** File offset of the unit or HOLE, ZERO, COMPRESSED. */
    protected abstract long map(long index) throws IOException;

    /** The first unit after the hole the given unit starts: whole unallocated tables are skipped at once. */
    protected long holeEnd(final long index) throws IOException {
        return index + 1;
    }

    /** The decompressed contents of a COMPRESSED unit, exactly unit bytes. */
    protected byte @NotNull [] inflate(final long index) throws IOException {
        throw new IOException("Compressed blocks are not supported in this format");
    }

    @Override
    public long size() {
        return size;
    }

    protected int unit() {
        return unit;
    }

    @Override
    public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
        if (pos < 0 || len < 0 || pos + len > size) {
            throw new EOFException("read past the end of the disk");
        }
        long p = pos;
        int o = off;
        int left = len;
        while (left > 0) {
            final long index = p / unit;
            final int in = (int) (p % unit);
            final int n = Math.min(left, unit - in);
            final long at = map(index);
            if (at >= 0) {
                readFile(at + in, b, o, n);
            } else if (at == COMPRESSED) {
                System.arraycopy(inflated(index), in, b, o, n);
            } else if (at == HOLE && parent != null && p < parent.size()) {
                final int fromParent = (int) Math.min(n, parent.size() - p);
                parent.read(p, b, o, fromParent);
                Arrays.fill(b, o + fromParent, o + n, (byte) 0);
            } else {
                Arrays.fill(b, o, o + n, (byte) 0);
            }
            p += n;
            o += n;
            left -= n;
        }
    }

    @Override
    public long dataFrom(final long pos) throws IOException {
        long index = Math.max(0, pos) / unit;
        final long units = (size + unit - 1) / unit;
        while (index < units) {
            final long at = map(index);
            if (at >= 0 || at == COMPRESSED) {
                return Math.max(pos, index * unit);
            }
            if (at == HOLE && parent != null) {
                final long data = parent.dataFrom(Math.max(pos, index * unit));
                final long end = holeEnd(index) * unit;
                if (data < end) {
                    return Math.min(size, data);
                }
            }
            index = at == HOLE ? holeEnd(index) : index + 1;
        }

        return size;
    }

    @Override
    public void close() throws IOException {
        try {
            ch.close();
        } finally {
            if (parent != null) {
                parent.close();
            }
        }
    }

    private byte @NotNull [] inflated(final long index) throws IOException {
        final byte[] cached = inflated.get(index);
        if (cached != null) {
            return cached;
        }
        final byte[] data = inflate(index);
        if (data.length != unit) {
            throw new IOException("Damaged compressed block " + index);
        }
        inflated.put(index, data);

        return data;
    }

    /** Exactly len bytes of the image file at pos. */
    protected void readFile(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
        readFully(ch, pos, b, off, len);
    }

    protected byte @NotNull [] readFile(final long pos, final int len) throws IOException {
        final byte[] b = new byte[len];
        readFile(pos, b, 0, len);

        return b;
    }

    static void readFully(final @NotNull FileChannel ch, final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(b, off, len);
        while (buf.hasRemaining()) {
            if (ch.read(buf, pos + buf.position() - off) < 0) {
                throw new EOFException("Virtual disk image is truncated");
            }
        }
    }

    /** A zlib stream (nowrap — raw deflate, as qcow writes it) decompressed into size bytes; a shorter stream is padded with zeros. */
    static byte @NotNull [] inflate(final byte @NotNull [] src, final int size, final boolean nowrap) throws IOException {
        final Inflater inflater = new Inflater(nowrap);
        try {
            inflater.setInput(src);
            final byte[] out = new byte[size];
            int n = 0;
            while (n < size && !inflater.finished()) {
                final int got = inflater.inflate(out, n, size - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += got;
            }
            if (n != size && !inflater.finished()) {
                throw new IOException("Damaged compressed block");
            }

            return out;
        } catch (final DataFormatException e) {
            throw new IOException("Damaged compressed block", e);
        } finally {
            inflater.end();
        }
    }

    /** A synchronized map that keeps the max most recently used entries. */
    static <K, V> @NotNull Map<K, V> lru(final int max) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
                return size() > max;
            }
        });
    }

    /** Closes the resource after a failed open. */
    static void closeQuietly(final @Nullable Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (final IOException e) {
                // the open already failed: that error is reported
            }
        }
    }
}
