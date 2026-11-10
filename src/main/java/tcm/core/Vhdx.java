package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hyper-V disk (.vhdx): the region table locates the block allocation table and the metadata (block size, disk size,
 * logical sector size). Differencing disks need a parent and are refused; a log left by an unclean shutdown is not replayed.
 */
final class Vhdx extends SparseDisk {

    static final String SIGNATURE = "vhdxfile";
    private static final long MB = 1 << 20;
    private static final int REGION_TABLE = 192 * 1024;
    private static final int REGION_TABLE_COPY = 256 * 1024;
    private static final int REGION_TABLE_SIZE = 64 * 1024;
    private static final int METADATA_TABLE = 64 * 1024;
    private static final byte[] BAT = guid("2DC27766-F623-4200-9D64-115E9BFD4A08");
    private static final byte[] METADATA = guid("8B7CA206-4790-4B9A-B8FE-575F050F886E");
    private static final byte[] FILE_PARAMETERS = guid("CAA16737-FA36-4D43-B3B6-33F0AA44E76B");
    private static final byte[] DISK_SIZE = guid("2FA54224-CD1B-4876-B211-5DBED83BF4B8");
    private static final byte[] LOGICAL_SECTOR = guid("8141BF1D-A96F-4709-BA47-F233A8FAAB5F");
    private static final int HAS_PARENT = 2;
    private static final int STATE_MASK = 7;
    private static final int ZERO_STATE = 2;
    private static final int FULLY_PRESENT = 6;
    private static final int PARTIALLY_PRESENT = 7;

    private final long[] bat;
    private final long chunkRatio;

    private Vhdx(final @NotNull FileChannel ch, final long size, final int block, final long @NotNull [] bat, final long chunkRatio)
        throws IOException {
        super(ch, size, block, null);
        this.bat = bat;
        this.chunkRatio = chunkRatio;
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 8 && SIGNATURE.equals(new String(head, 0, 8, StandardCharsets.ISO_8859_1));
    }

    static @NotNull Vhdx open(final @NotNull FileChannel ch) throws IOException {
        ByteBuffer regions = regionTable(ch, REGION_TABLE);
        if (regions == null) {
            regions = regionTable(ch, REGION_TABLE_COPY);
        }
        if (regions == null) {
            throw new IOException("Damaged VHDX region table");
        }
        final long[] bat = region(regions, BAT);
        final long[] meta = region(regions, METADATA);
        if (bat == null || meta == null) {
            throw new IOException("Damaged VHDX region table");
        }
        final byte[] metadata = new byte[(int) Math.min(meta[1], METADATA_TABLE)];
        readFully(ch, meta[0], metadata, 0, metadata.length);
        final ByteBuffer m = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN);
        if (!"metadata".equals(new String(metadata, 0, 8, StandardCharsets.ISO_8859_1))) {
            throw new IOException("Damaged VHDX metadata");
        }
        final ByteBuffer params = item(ch, meta[0], m, FILE_PARAMETERS, 8);
        final ByteBuffer diskSize = item(ch, meta[0], m, DISK_SIZE, 8);
        final ByteBuffer sector = item(ch, meta[0], m, LOGICAL_SECTOR, 4);
        if ((params.getInt(4) & HAS_PARENT) != 0) {
            throw new IOException("Differencing VHDX image needs its parent image");
        }
        final int block = params.getInt(0);
        final long size = diskSize.getLong(0);
        final int logical = sector.getInt(0);
        if (block < MB || Integer.bitCount(block) != 1 || (logical != 512 && logical != 4096) || size < 0) {
            throw new IOException("Damaged VHDX metadata");
        }
        final long chunkRatio = (1L << 23) * logical / block;
        final long blocks = (size + block - 1) / block;
        final long entries = blocks + (blocks - 1) / chunkRatio;
        if (entries * 8 > bat[1] || entries > Integer.MAX_VALUE / 8) {
            throw new IOException("Damaged VHDX block allocation table");
        }
        final byte[] table = new byte[(int) entries * 8];
        readFully(ch, bat[0], table, 0, table.length);
        final long[] map = new long[(int) entries];
        ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(map);

        return new Vhdx(ch, size, block, map, chunkRatio);
    }

    private static @Nullable ByteBuffer regionTable(final @NotNull FileChannel ch, final long at) throws IOException {
        if (ch.size() < at + REGION_TABLE_SIZE) {
            return null;
        }
        final byte[] b = new byte[REGION_TABLE_SIZE];
        readFully(ch, at, b, 0, b.length);

        return "regi".equals(new String(b, 0, 4, StandardCharsets.ISO_8859_1)) ? ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN) : null;
    }

    /** {offset, length} of the region with the id; null — none. */
    private static long @Nullable [] region(final @NotNull ByteBuffer table, final byte @NotNull [] id) {
        final int count = Math.min(table.getInt(8), (REGION_TABLE_SIZE - 16) / 32);
        for (int i = 0; i < count; i++) {
            final int e = 16 + i * 32;
            if (Arrays.equals(table.array(), e, e + 16, id, 0, 16)) {
                return new long[] {table.getLong(e + 16), Integer.toUnsignedLong(table.getInt(e + 24))};
            }
        }

        return null;
    }

    private static @NotNull ByteBuffer item(
        final @NotNull FileChannel ch,
        final long base,
        final @NotNull ByteBuffer table,
        final byte @NotNull [] id,
        final int length
    ) throws IOException {
        final int count = Math.min(table.getShort(10) & 0xFFFF, (table.capacity() - 32) / 32);
        for (int i = 0; i < count; i++) {
            final int e = 32 + i * 32;
            if (Arrays.equals(table.array(), e, e + 16, id, 0, 16)) {
                final byte[] b = new byte[length];
                readFully(ch, base + Integer.toUnsignedLong(table.getInt(e + 16)), b, 0, length);

                return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            }
        }

        throw new IOException("Damaged VHDX metadata: a required item is missing");
    }

    /** A GUID in its on-disk (mixed-endian) byte order. */
    static byte @NotNull [] guid(final @NotNull String text) {
        final String hex = text.replace("-", "");
        final byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        final byte[] out = b.clone();
        for (int i = 0; i < 4; i++) {
            out[i] = b[3 - i];
        }
        out[4] = b[5];
        out[5] = b[4];
        out[6] = b[7];
        out[7] = b[6];

        return out;
    }

    @Override
    protected long map(final long index) {
        // a sector bitmap entry follows every chunkRatio payload entries
        final long i = index + index / chunkRatio;
        if (i >= bat.length) {
            return HOLE;
        }
        final long e = bat[(int) i];
        final int state = (int) (e & STATE_MASK);
        if (state == FULLY_PRESENT || state == PARTIALLY_PRESENT) {
            return (e >>> 20) * MB;
        }

        return state == ZERO_STATE ? ZERO : HOLE;
    }
}
