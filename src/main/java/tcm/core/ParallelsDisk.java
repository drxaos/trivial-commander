package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import org.jetbrains.annotations.NotNull;

/** Parallels expanding disk image (.hdd, .hds): a header with a block allocation table, version 2. */
final class ParallelsDisk extends SparseDisk {

    private static final String MAGIC = "WithoutFreeSpace";
    /** The newer format: the table holds block numbers instead of sector numbers. */
    private static final String MAGIC_EXT = "WithouFreSpacExt";
    private static final int HEADER = 64;
    private static final int VERSION = 2;

    private final int[] bat;
    private final long multiplier;

    private ParallelsDisk(final @NotNull FileChannel ch, final long size, final int block, final int @NotNull [] bat, final long multiplier)
        throws IOException {
        super(ch, size, block, null);
        this.bat = bat;
        this.multiplier = multiplier;
    }

    static boolean matches(final byte @NotNull [] head) {
        if (head.length < HEADER) {
            return false;
        }
        final String magic = new String(head, 0, 16, StandardCharsets.ISO_8859_1);

        return (MAGIC.equals(magic) || MAGIC_EXT.equals(magic)) && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(16) == VERSION;
    }

    static @NotNull ParallelsDisk open(final @NotNull FileChannel ch) throws IOException {
        final byte[] raw = new byte[HEADER];
        readFully(ch, 0, raw, 0, HEADER);
        if (!matches(raw)) {
            throw new IOException("Not a Parallels disk image");
        }
        final ByteBuffer h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        final boolean ext = MAGIC_EXT.equals(new String(raw, 0, 16, StandardCharsets.ISO_8859_1));
        final int sectors = h.getInt(28);
        final int entries = h.getInt(32);
        final long size = (ext ? h.getLong(36) : Integer.toUnsignedLong(h.getInt(36))) * 512;
        if (sectors <= 0 || sectors > (1 << 22) || entries < 0 || (long) entries * sectors * 512 < size) {
            throw new IOException("Damaged Parallels disk header");
        }
        final byte[] map = new byte[entries * 4];
        readFully(ch, HEADER, map, 0, map.length);
        final int[] bat = new int[entries];
        ByteBuffer.wrap(map).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(bat);

        return new ParallelsDisk(ch, size, sectors * 512, bat, ext ? sectors : 1);
    }

    @Override
    protected long map(final long index) {
        final int e = bat[(int) index];

        return e == 0 ? HOLE : Integer.toUnsignedLong(e) * multiplier * 512;
    }
}
