package tcm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;

import org.jetbrains.annotations.NotNull;

/**
 * Virtual machine disk images for the smoke test, written the way their tools lay them out: only the blocks with non-zero data
 * are stored. Minimal headers — just the fields the readers use.
 */
final class DiskFixtures {

    private static final int K64 = 64 * 1024;
    private static final int MB = 1024 * 1024;

    private DiskFixtures() {
    }

    /** VirtualBox dynamic image, 64 KiB blocks. */
    static byte @NotNull [] vdi(final byte @NotNull [] disk) {
        final int blocks = units(disk, K64);
        final int offData = align(0x200 + blocks * 4, 512);
        final ByteBuffer b = le(offData + stored(disk, K64) * K64);
        b.put("<<< Oracle VM VirtualBox Disk Image >>>\n".getBytes(StandardCharsets.ISO_8859_1));
        b.putInt(0x40, 0xBEDA107F).putInt(0x44, 0x00010001).putInt(0x48, 0x190).putInt(0x4C, 1);
        b.putInt(0x154, 0x200).putInt(0x158, offData).putLong(0x170, disk.length).putInt(0x178, K64).putInt(0x180, blocks);
        int next = 0;
        for (int i = 0; i < blocks; i++) {
            if (isZero(disk, i, K64)) {
                b.putInt(0x200 + i * 4, -1);
            } else {
                b.putInt(0x200 + i * 4, next);
                b.put(offData + next++ * K64, disk, i * K64, Math.min(K64, disk.length - i * K64));
            }
        }

        return b.array();
    }

    /** Virtual PC dynamic disk, 64 KiB blocks: a footer copy, the dynamic header, the table, blocks (bitmap + data), the footer. */
    static byte @NotNull [] vhd(final byte @NotNull [] disk) {
        final int blocks = units(disk, K64);
        final int table = 512 + 1024;
        final int data = table + align(blocks * 4, 512);
        final int end = data + stored(disk, K64) * (512 + K64);
        final ByteBuffer b = ByteBuffer.allocate(end + 512);
        final byte[] footer = new byte[512];
        final ByteBuffer f = ByteBuffer.wrap(footer);
        f.put("conectix".getBytes(StandardCharsets.ISO_8859_1)).putInt(8, 2).putInt(12, 0x00010000).putLong(16, 512);
        f.putLong(40, disk.length).putLong(48, disk.length).putInt(60, 3);
        b.put(0, footer).put(end, footer);
        b.put(512, "cxsparse".getBytes(StandardCharsets.ISO_8859_1)).putLong(512 + 8, -1).putLong(512 + 16, table);
        b.putInt(512 + 24, 0x00010000).putInt(512 + 28, blocks).putInt(512 + 32, K64);
        int at = data;
        for (int i = 0; i < blocks; i++) {
            if (isZero(disk, i, K64)) {
                b.putInt(table + i * 4, -1);
            } else {
                b.putInt(table + i * 4, at / 512);
                Arrays.fill(b.array(), at, at + 512, (byte) 0xFF);
                b.put(at + 512, disk, i * K64, Math.min(K64, disk.length - i * K64));
                at += 512 + K64;
            }
        }

        return b.array();
    }

    /** qcow2 version 3, 64 KiB clusters, every data cluster deflated, zero clusters flagged. */
    static byte @NotNull [] qcow2(final byte @NotNull [] disk) {
        final int clusters = units(disk, K64);
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final long[] l2 = new long[clusters];
        final long base = 3L * K64;
        for (int i = 0; i < clusters; i++) {
            if (isZero(disk, i, K64)) {
                l2[i] = 1;
                continue;
            }
            final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
            deflater.setInput(Arrays.copyOfRange(disk, i * K64, Math.min(disk.length, (i + 1) * K64)));
            deflater.finish();
            final byte[] out = new byte[K64 * 2];
            final int n = deflater.deflate(out);
            deflater.end();
            final long offset = base + data.size();
            final long sectors = (n + 511) / 512;
            l2[i] = 1L << 62 | (sectors - 1) << 54 | offset;
            data.write(out, 0, (int) sectors * 512);
        }
        final ByteBuffer b = ByteBuffer.allocate((int) base + data.size());
        b.putInt(0, 0x514649FB).putInt(4, 3).putInt(20, 16).putLong(24, disk.length).putInt(36, 1).putLong(40, K64);
        b.putInt(100, 104);
        b.putLong(K64, 1L << 63 | 2L * K64);
        for (int i = 0; i < clusters; i++) {
            b.putLong(2 * K64 + i * 8, l2[i]);
        }
        b.put((int) base, data.toByteArray());

        return b.array();
    }

    /** VMware monolithic sparse extent, 64 KiB grains: header, grain directory, grain table, grains. */
    static byte @NotNull [] vmdk(final byte @NotNull [] disk) {
        final int grains = units(disk, K64);
        final int gt = 1024;
        final int data = align(gt + grains * 4, 512);
        final ByteBuffer b = le(data + stored(disk, K64) * K64);
        b.putInt(0, 0x564D444B).putInt(4, 1).putInt(8, 1).putLong(12, disk.length / 512).putLong(20, 128);
        b.putInt(44, 512).putLong(56, 1).putLong(64, data / 512);
        b.putInt(512, gt / 512);
        int at = data;
        for (int i = 0; i < grains; i++) {
            if (!isZero(disk, i, K64)) {
                b.putInt(gt + i * 4, at / 512);
                b.put(at, disk, i * K64, Math.min(K64, disk.length - i * K64));
                at += K64;
            }
        }

        return b.array();
    }

    /** QEMU enhanced disk, 64 KiB clusters, one-cluster tables. */
    static byte @NotNull [] qed(final byte @NotNull [] disk) {
        final int clusters = units(disk, K64);
        final ByteBuffer b = le((3 + stored(disk, K64)) * K64);
        b.putInt(0, 0x00444551).putInt(4, K64).putInt(8, 1).putInt(12, 1).putLong(40, K64).putLong(48, disk.length);
        b.putLong(K64, 2L * K64);
        int at = 3 * K64;
        for (int i = 0; i < clusters; i++) {
            if (!isZero(disk, i, K64)) {
                b.putLong(2 * K64 + i * 8, at);
                b.put(at, disk, i * K64, Math.min(K64, disk.length - i * K64));
                at += K64;
            }
        }

        return b.array();
    }

    /** Parallels expanding disk (the newer format: the table holds cluster numbers), 64 KiB clusters. */
    static byte @NotNull [] parallels(final byte @NotNull [] disk) {
        final int clusters = units(disk, K64);
        final ByteBuffer b = le((1 + stored(disk, K64)) * K64);
        b.put("WithouFreSpacExt".getBytes(StandardCharsets.ISO_8859_1)).putInt(16, 2).putInt(20, 16).putInt(28, 128);
        b.putInt(32, clusters).putLong(36, disk.length / 512).putInt(48, 128);
        int next = 1;
        for (int i = 0; i < clusters; i++) {
            if (!isZero(disk, i, K64)) {
                b.putInt(64 + i * 4, next);
                b.put(next++ * K64, disk, i * K64, Math.min(K64, disk.length - i * K64));
            }
        }

        return b.array();
    }

    /** Hyper-V disk, 1 MiB blocks: region table at 192 KiB, metadata at 1 MiB, the table at 2 MiB, blocks from 3 MiB. */
    static byte @NotNull [] vhdx(final byte @NotNull [] disk) {
        final int blocks = units(disk, MB);
        final ByteBuffer b = le((3 + stored(disk, MB)) * MB);
        b.put("vhdxfile".getBytes(StandardCharsets.ISO_8859_1));
        final int regions = 192 * 1024;
        b.put(regions, "regi".getBytes(StandardCharsets.ISO_8859_1)).putInt(regions + 8, 2);
        region(b, regions + 16, "2DC27766-F623-4200-9D64-115E9BFD4A08", 2L * MB, MB);
        region(b, regions + 48, "8B7CA206-4790-4B9A-B8FE-575F050F886E", MB, MB);
        b.put(MB, "metadata".getBytes(StandardCharsets.ISO_8859_1)).putShort(MB + 10, (short) 3);
        item(b, 0, "CAA16737-FA36-4D43-B3B6-33F0AA44E76B", 64 * 1024, 8);
        item(b, 1, "2FA54224-CD1B-4876-B211-5DBED83BF4B8", 64 * 1024 + 8, 8);
        item(b, 2, "8141BF1D-A96F-4709-BA47-F233A8FAAB5F", 64 * 1024 + 16, 4);
        b.putInt(MB + 64 * 1024, MB).putLong(MB + 64 * 1024 + 8, disk.length).putInt(MB + 64 * 1024 + 16, 512);
        int next = 3;
        for (int i = 0; i < blocks; i++) {
            if (!isZero(disk, i, MB)) {
                b.putLong(2 * MB + i * 8, (long) next << 20 | 6);
                b.put(next++ * MB, disk, i * MB, Math.min(MB, disk.length - i * MB));
            }
        }

        return b.array();
    }

    private static void region(final @NotNull ByteBuffer b, final int at, final @NotNull String guid, final long offset, final int length) {
        b.put(at, guid(guid)).putLong(at + 16, offset).putInt(at + 24, length).putInt(at + 28, 1);
    }

    private static void item(final @NotNull ByteBuffer b, final int i, final @NotNull String guid, final int offset, final int length) {
        final int at = MB + 32 + i * 32;
        b.put(at, guid(guid)).putInt(at + 16, offset).putInt(at + 20, length);
    }

    private static byte @NotNull [] guid(final @NotNull String text) {
        final String hex = text.replace("-", "");
        final byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }

        return new byte[] {b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]};
    }

    private static @NotNull ByteBuffer le(final int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int units(final byte @NotNull [] disk, final int unit) {
        return (disk.length + unit - 1) / unit;
    }

    private static int stored(final byte @NotNull [] disk, final int unit) {
        int n = 0;
        for (int i = 0; i < units(disk, unit); i++) {
            n += isZero(disk, i, unit) ? 0 : 1;
        }

        return n;
    }

    private static boolean isZero(final byte @NotNull [] disk, final int index, final int unit) {
        final int from = index * unit;
        final int to = Math.min(disk.length, from + unit);
        for (int i = from; i < to; i++) {
            if (disk[i] != 0) {
                return false;
            }
        }

        return true;
    }

    private static int align(final int value, final int to) {
        return (value + to - 1) / to * to;
    }
}
