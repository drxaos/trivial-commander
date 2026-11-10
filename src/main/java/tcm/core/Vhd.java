package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import org.jetbrains.annotations.NotNull;

/**
 * Virtual PC / Hyper-V disk (.vhd): fixed — the raw disk followed by a 512-byte footer, dynamic — a block allocation table;
 * differencing disks need a parent and are refused.
 */
final class Vhd extends SparseDisk {

    static final String COOKIE = "conectix";
    private static final int FOOTER = 512;
    private static final int TYPE_FIXED = 2;
    private static final int TYPE_DYNAMIC = 3;
    private static final int TYPE_DIFFERENCING = 4;
    private static final int UNUSED = -1;

    private final int[] bat;
    private final int bitmap;

    private Vhd(final @NotNull FileChannel ch, final long size, final int block, final int @NotNull [] bat) throws IOException {
        super(ch, size, block, null);
        this.bat = bat;
        // a sector bitmap precedes the data of every block, padded to whole sectors
        this.bitmap = (block / 512 / 8 + 511) / 512 * 512;
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 8 && COOKIE.equals(new String(head, 0, 8, StandardCharsets.ISO_8859_1));
    }

    /** A fixed VHD has no header: only the footer at the end tells it from a raw disk. */
    static boolean hasFooter(final @NotNull FileChannel ch) throws IOException {
        final long size = ch.size();
        if (size < FOOTER * 2) {
            return false;
        }
        final byte[] b = new byte[8];
        readFully(ch, size - FOOTER, b, 0, 8);

        return matches(b);
    }

    static @NotNull VirtualDisks.Disk open(final @NotNull FileChannel ch) throws IOException {
        final long fileSize = ch.size();
        final byte[] raw = new byte[FOOTER];
        readFully(ch, fileSize - FOOTER, raw, 0, FOOTER);
        if (!matches(raw)) {
            // a dynamic disk keeps a copy of the footer at the start
            readFully(ch, 0, raw, 0, FOOTER);
            if (!matches(raw)) {
                throw new IOException("Not a VHD image");
            }
        }
        final ByteBuffer footer = ByteBuffer.wrap(raw);
        final long size = footer.getLong(48);
        final int type = footer.getInt(60);
        if (type == TYPE_FIXED) {
            return VirtualDisks.raw(ch, Math.min(size, fileSize - FOOTER));
        }
        if (type == TYPE_DIFFERENCING) {
            throw new IOException("Differencing VHD image needs its parent image");
        }
        if (type != TYPE_DYNAMIC) {
            throw new IOException("Unsupported VHD disk type " + type);
        }
        final byte[] dyn = new byte[1024];
        readFully(ch, footer.getLong(16), dyn, 0, dyn.length);
        final ByteBuffer h = ByteBuffer.wrap(dyn);
        if (!"cxsparse".equals(new String(dyn, 0, 8, StandardCharsets.ISO_8859_1))) {
            throw new IOException("Damaged VHD dynamic disk header");
        }
        final long table = h.getLong(16);
        final int entries = h.getInt(28);
        final int block = h.getInt(32);
        if (block < 512 || Integer.bitCount(block) != 1 || entries < 0 || (long) entries * block < size) {
            throw new IOException("Damaged VHD dynamic disk header");
        }
        final byte[] map = new byte[entries * 4];
        readFully(ch, table, map, 0, map.length);
        final int[] bat = new int[entries];
        ByteBuffer.wrap(map).asIntBuffer().get(bat);

        return new Vhd(ch, size, block, bat);
    }

    @Override
    protected long map(final long index) {
        final int sector = bat[(int) index];

        return sector == UNUSED ? HOLE : Integer.toUnsignedLong(sector) * 512 + bitmap;
    }
}
