package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** QEMU enhanced disk (.qed): a two-level cluster table, zero clusters and a backing file. */
final class Qed extends SparseDisk {

    static final int MAGIC = 0x00444551;
    private static final long ZERO_CLUSTER = 1;
    private static final long HAS_BACKING = 1;
    private static final int TABLE_CACHE = 64;

    private final long[] l1;
    private final int tableBytes;
    private final int entries;
    private final Map<Long, long[]> tables = lru(TABLE_CACHE);

    private Qed(
        final @NotNull FileChannel ch,
        final long size,
        final int cluster,
        final int tableBytes,
        final long @NotNull [] l1,
        final @Nullable VirtualDisks.Disk parent
    ) throws IOException {
        super(ch, size, cluster, parent);
        this.l1 = l1;
        this.tableBytes = tableBytes;
        this.entries = tableBytes / 8;
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 4 && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == MAGIC;
    }

    static @NotNull Qed open(final @NotNull FileChannel ch, final @NotNull Path file) throws IOException {
        final byte[] raw = new byte[64];
        readFully(ch, 0, raw, 0, raw.length);
        final ByteBuffer h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getInt(0) != MAGIC) {
            throw new IOException("Not a QED image");
        }
        final int cluster = h.getInt(4);
        final int tableClusters = h.getInt(8);
        final long features = h.getLong(16);
        final long l1Offset = h.getLong(40);
        final long size = h.getLong(48);
        if (cluster < 4096 || Integer.bitCount(cluster) != 1 || cluster > (64 << 20) || tableClusters < 1 || tableClusters > 16
            || (long) cluster * tableClusters > (1 << 26)) {
            throw new IOException("Damaged QED header");
        }
        final int tableBytes = cluster * tableClusters;
        final long[] l1 = new long[tableBytes / 8];
        final byte[] table = new byte[tableBytes];
        readFully(ch, l1Offset, table, 0, tableBytes);
        ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(l1);
        if (size > (long) l1.length * l1.length * cluster || size < 0) {
            throw new IOException("Damaged QED header");
        }
        VirtualDisks.Disk parent = null;
        final int nameSize = h.getInt(60);
        if ((features & HAS_BACKING) != 0 && nameSize > 0) {
            final byte[] name = new byte[Math.min(nameSize, 1023)];
            readFully(ch, Integer.toUnsignedLong(h.getInt(56)), name, 0, name.length);
            parent = VirtualDisks.backing(file, new String(name, StandardCharsets.UTF_8));
        }
        try {
            return new Qed(ch, size, cluster, tableBytes, l1, parent);
        } catch (final IOException | RuntimeException e) {
            closeQuietly(parent);
            throw e;
        }
    }

    @Override
    protected long map(final long index) throws IOException {
        final long table = index / entries;
        if (table >= l1.length || l1[(int) table] == 0) {
            return HOLE;
        }
        final long at = l1[(int) table];
        long[] l2 = tables.get(at);
        if (l2 == null) {
            final byte[] raw = readFile(at, tableBytes);
            l2 = new long[entries];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(l2);
            tables.put(at, l2);
        }
        final long e = l2[(int) (index % entries)];
        if (e == 0) {
            return HOLE;
        }

        return e == ZERO_CLUSTER ? ZERO : e;
    }

    @Override
    protected long holeEnd(final long index) {
        final long table = index / entries;

        return table >= l1.length || l1[(int) table] == 0 ? (table + 1) * entries : index + 1;
    }
}
