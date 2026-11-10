package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

import org.jetbrains.annotations.NotNull;

/** VirtualBox disk image (.vdi), version 1.1: normal (dynamic) and fixed; differencing images need a parent and are refused. */
final class Vdi extends SparseDisk {

    static final int SIGNATURE = 0xBEDA107F;
    private static final int SIGNATURE_AT = 0x40;
    private static final int HEADER = 0x190;
    private static final int TYPE_DIFF = 4;
    private static final int BLOCK_FREE = -1;
    private static final int BLOCK_ZERO = -2;

    private final int[] blocks;
    private final long data;
    private final long stride;
    private final int extra;

    private Vdi(
        final @NotNull FileChannel ch,
        final long size,
        final int block,
        final int extra,
        final int @NotNull [] blocks,
        final long data
    ) throws IOException {
        super(ch, size, block, null);
        this.blocks = blocks;
        this.data = data;
        this.extra = extra;
        this.stride = (long) block + extra;
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= SIGNATURE_AT + 4 && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(SIGNATURE_AT) == SIGNATURE;
    }

    static @NotNull Vdi open(final @NotNull FileChannel ch) throws IOException {
        final byte[] raw = new byte[HEADER];
        readFully(ch, 0, raw, 0, raw.length);
        final ByteBuffer h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getInt(SIGNATURE_AT) != SIGNATURE) {
            throw new IOException("Not a VDI image");
        }
        if (h.getInt(0x44) >>> 16 != 1) {
            throw new IOException("Unsupported VDI version " + Integer.toHexString(h.getInt(0x44)));
        }
        if (h.getInt(0x4C) == TYPE_DIFF) {
            throw new IOException("Differencing VDI image needs its parent image");
        }
        final long offBlocks = Integer.toUnsignedLong(h.getInt(0x154));
        final long offData = Integer.toUnsignedLong(h.getInt(0x158));
        final long size = h.getLong(0x170);
        final int block = h.getInt(0x178);
        final int extra = h.getInt(0x17C);
        final int count = h.getInt(0x180);
        if (block < 512 || Integer.bitCount(block) != 1 || extra < 0 || count < 0 || (long) count * block < size) {
            throw new IOException("Damaged VDI header");
        }
        final byte[] map = new byte[count * 4];
        readFully(ch, offBlocks, map, 0, map.length);
        final int[] blocks = new int[count];
        ByteBuffer.wrap(map).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(blocks);

        return new Vdi(ch, size, block, extra, blocks, offData);
    }

    @Override
    protected long map(final long index) {
        final int b = blocks[(int) index];
        if (b == BLOCK_FREE) {
            return HOLE;
        }
        if (b == BLOCK_ZERO) {
            return ZERO;
        }

        return data + Integer.toUnsignedLong(b) * stride + extra;
    }
}
