package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;

/**
 * FAT12, FAT16 and FAT32 — read only. Long (VFAT) names; short names in code page 437 with the NT lower-case flags.
 * DOS times are local, as Windows writes them.
 */
final class FatFs {

    private static final int ATTR_VOLUME = 0x08;
    private static final int ATTR_DIRECTORY = 0x10;
    private static final int ATTR_LFN = 0x0F;
    private static final int DELETED = 0xE5;
    private static final int ENTRY = 32;
    private static final int FAT12_MAX = 4085;
    private static final int FAT16_MAX = 65525;
    private static final int FAT_BLOCK = 64 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_DIR = 256 * 1024 * 1024;
    private static final int NT_LOWER_BASE = 0x08;
    private static final int NT_LOWER_EXT = 0x10;
    private static final Charset OEM = Charset.forName("IBM437");

    private final @NotNull BlockSource dev;
    private final int bits;
    private final long fatOffset;
    private final long fatBytes;
    private final long dataOffset;
    private final int clusterSize;
    private final long clusters;
    private final Map<Long, byte[]> fatCache = new ConcurrentHashMap<>();

    private FatFs(final @NotNull BlockSource dev, final @NotNull ByteBuffer bpb) {
        this.dev = dev;
        final int sector = bpb.getShort(11) & 0xFFFF;
        final int perCluster = bpb.get(13) & 0xFF;
        final long reserved = bpb.getShort(14) & 0xFFFF;
        final int fats = bpb.get(16) & 0xFF;
        final int rootEntries = bpb.getShort(17) & 0xFFFF;
        final long total = total(bpb);
        final long fatSize = fatSize(bpb);
        final long rootSectors = (rootEntries * (long) ENTRY + sector - 1) / sector;
        this.clusterSize = sector * perCluster;
        this.fatOffset = reserved * sector;
        this.fatBytes = fatSize * sector;
        final long firstData = reserved + fats * fatSize + rootSectors;
        this.dataOffset = firstData * sector;
        this.clusters = (total - firstData) / perCluster;
        this.bits = clusters < FAT12_MAX ? 12 : clusters < FAT16_MAX ? 16 : 32;
    }

    private static long total(final @NotNull ByteBuffer bpb) {
        final int small = bpb.getShort(19) & 0xFFFF;

        return small != 0 ? small : Integer.toUnsignedLong(bpb.getInt(32));
    }

    private static long fatSize(final @NotNull ByteBuffer bpb) {
        final int small = bpb.getShort(22) & 0xFFFF;

        return small != 0 ? small : Integer.toUnsignedLong(bpb.getInt(36));
    }

    /** A plausible BIOS parameter block: FAT has no magic number of its own. */
    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        final byte[] b = DiskImages.peek(dev, 0, 512);
        if (b == null) {
            return false;
        }
        final ByteBuffer bpb = DiskImages.le(b);
        final int jump = b[0] & 0xFF;
        final int sector = bpb.getShort(11) & 0xFFFF;
        final int perCluster = b[13] & 0xFF;
        final int fats = b[16] & 0xFF;
        final int media = b[21] & 0xFF;

        return (jump == 0xEB || jump == 0xE9 || jump == 0xE8)
            && (sector == 512 || sector == 1024 || sector == 2048 || sector == 4096)
            && perCluster > 0 && Integer.bitCount(perCluster) == 1
            && (bpb.getShort(14) & 0xFFFF) > 0 && fats >= 1 && fats <= 4
            && (media == 0xF0 || media >= 0xF8)
            && fatSize(bpb) > 0 && total(bpb) > 0;
    }

    static @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
        final ByteBuffer bpb = DiskImages.le(dev.read(0, 512));
        final FatFs fs = new FatFs(dev, bpb);
        final FsTree tree = new FsTree("FAT" + fs.bits);
        final int labelAt = fs.bits == 32 ? 71 : 43;
        final int signatureAt = fs.bits == 32 ? 66 : 38;
        if ((bpb.get(signatureAt) & 0xFF) == 0x29) {
            final String label = DiskImages.ascii(bpb, labelAt, 11).strip();
            tree.setLabel("NO NAME".equals(label) ? "" : label);
        }
        final byte[] root;
        if (fs.bits == 32) {
            root = fs.chain(Integer.toUnsignedLong(bpb.getInt(44)));
        } else {
            final int entries = bpb.getShort(17) & 0xFFFF;
            root = dev.read(fs.dataOffset - (long) entries * ENTRY, entries * ENTRY);
        }
        fs.walk(root, "", tree, 0, new HashSet<>());

        return tree;
    }

    private void walk(final byte @NotNull [] dir, final @NotNull String path, final @NotNull FsTree tree, final int depth,
        final @NotNull Set<Long> seen) throws IOException {
        final ByteBuffer b = DiskImages.le(dir);
        final StringBuilder lfn = new StringBuilder();
        int lfnChecksum = -1;
        for (int off = 0; off + ENTRY <= dir.length; off += ENTRY) {
            final int first = dir[off] & 0xFF;
            if (first == 0) {
                break;
            }
            final int attr = dir[off + 11] & 0xFF;
            if (first == DELETED) {
                lfn.setLength(0);
                continue;
            }
            if (attr == ATTR_LFN) {
                if ((first & 0x40) != 0) {
                    lfn.setLength(0);
                }
                lfnChecksum = dir[off + 13] & 0xFF;
                lfn.insert(0, lfnPart(b, off));
                continue;
            }
            final String shortName = shortName(dir, off);
            final String name = lfn.length() > 0 && lfnChecksum == checksum(dir, off) ? lfnName(lfn) : shortName;
            lfn.setLength(0);
            if ((attr & ATTR_VOLUME) != 0) {
                continue;
            }
            final String child = FsTree.child(path, name);
            if (child == null) {
                continue;
            }
            final long cluster = (b.getShort(off + 26) & 0xFFFFL) | (bits == 32 ? (b.getShort(off + 20) & 0xFFFFL) << 16 : 0);
            final long modified = dosTime(b.getShort(off + 24) & 0xFFFF, b.getShort(off + 22) & 0xFFFF);
            final String attrs = FileAttrs.dos(attr);
            if ((attr & ATTR_DIRECTORY) != 0) {
                tree.dir(child, modified, attrs);
                if (cluster >= 2 && depth < MAX_DEPTH && seen.add(cluster)) {
                    walk(chain(cluster), child, tree, depth + 1, seen);
                }
            } else {
                final long size = Integer.toUnsignedLong(b.getInt(off + 28));
                tree.file(child, size, modified, attrs, () -> data(cluster, size).stream());
            }
        }
    }

    private static @NotNull String lfnPart(final @NotNull ByteBuffer b, final int off) {
        final StringBuilder sb = new StringBuilder(13);
        for (final int[] range : new int[][] {{1, 5}, {14, 6}, {28, 2}}) {
            for (int i = 0; i < range[1]; i++) {
                sb.append(b.getChar(off + range[0] + i * 2));
            }
        }

        return sb.toString();
    }

    private static @NotNull String lfnName(final @NotNull StringBuilder lfn) {
        final int end = lfn.indexOf("\0");

        return end < 0 ? lfn.toString() : lfn.substring(0, end);
    }

    private static int checksum(final byte @NotNull [] dir, final int off) {
        int sum = 0;
        for (int i = 0; i < 11; i++) {
            sum = ((sum & 1) << 7) + (sum >> 1) + (dir[off + i] & 0xFF) & 0xFF;
        }

        return sum;
    }

    private static @NotNull String shortName(final byte @NotNull [] dir, final int off) {
        final byte[] raw = new byte[11];
        System.arraycopy(dir, off, raw, 0, 11);
        if ((raw[0] & 0xFF) == 0x05) {
            raw[0] = (byte) DELETED;
        }
        final int nt = dir[off + 12] & 0xFF;
        String base = new String(raw, 0, 8, OEM).stripTrailing();
        String ext = new String(raw, 8, 3, OEM).stripTrailing();
        if ((nt & NT_LOWER_BASE) != 0) {
            base = base.toLowerCase(Locale.ROOT);
        }
        if ((nt & NT_LOWER_EXT) != 0) {
            ext = ext.toLowerCase(Locale.ROOT);
        }

        return ext.isEmpty() ? base : base + "." + ext;
    }

    static long dosTime(final int date, final int time) {
        if (date == 0) {
            return 0;
        }
        try {
            final LocalDateTime t = LocalDateTime.of(1980 + (date >> 9), Math.max(1, date >> 5 & 15), Math.max(1, date & 31),
                time >> 11, time >> 5 & 63, Math.min(59, (time & 31) * 2));

            return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (final DateTimeException e) {
            // a damaged date
            return 0;
        }
    }

    private long next(final long cluster) throws IOException {
        final long pos = bits == 12 ? cluster + cluster / 2 : cluster * (bits / 8);
        if (pos + 4 > fatBytes + 4 || cluster >= clusters + 2) {
            return -1;
        }
        final int v = fatInt(pos);

        return switch (bits) {
            case 12 -> {
                final int e = (cluster & 1) == 0 ? v & 0xFFF : v >> 4 & 0xFFF;
                yield e >= 0xFF7 ? -1 : e;
            }
            case 16 -> {
                final int e = v & 0xFFFF;
                yield e >= 0xFFF7 ? -1 : e;
            }
            default -> {
                final int e = v & 0x0FFFFFFF;
                yield e >= 0x0FFFFFF7 ? -1 : e;
            }
        };
    }

    /** Four bytes of the FAT at pos (little-endian; past its end — zeros). */
    private int fatInt(final long pos) throws IOException {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            final long p = pos + i;
            if (p < fatBytes) {
                final byte[] block = fatBlock(p / FAT_BLOCK);
                v |= (block[(int) (p % FAT_BLOCK)] & 0xFF) << 8 * i;
            }
        }

        return v;
    }

    private byte @NotNull [] fatBlock(final long n) throws IOException {
        byte[] block = fatCache.get(n);
        if (block == null) {
            final long start = n * FAT_BLOCK;
            block = dev.read(fatOffset + start, (int) Math.min(FAT_BLOCK, fatBytes - start));
            fatCache.put(n, block);
        }

        return block;
    }

    /** The chain of clusters from the first one as data runs, up to the size. */
    private @NotNull FsData data(final long first, final long size) throws IOException {
        final FsData data = new FsData(dev, size);
        long logical = 0;
        long cluster = first;
        long runStart = -1;
        long runLength = 0;
        int steps = 0;
        // a chain never has more links than there are clusters: more means a loop in a damaged FAT
        while (cluster >= 2 && logical + runLength < size && steps++ <= clusters) {
            final long physical = dataOffset + (cluster - 2) * clusterSize;
            if (runStart >= 0 && runStart + runLength == physical) {
                runLength += clusterSize;
            } else {
                data.raw(logical, runStart, runLength);
                logical += runLength;
                runStart = physical;
                runLength = clusterSize;
            }
            cluster = next(cluster);
        }
        data.raw(logical, runStart, runLength);

        return data;
    }

    /** The whole chain read into memory: a directory. */
    private byte @NotNull [] chain(final long first) throws IOException {
        long length = 0;
        long c = first;
        int steps = 0;
        while (c >= 2 && steps++ <= clusters) {
            length += clusterSize;
            if (length > MAX_DIR) {
                throw new IOException("FAT: directory too large");
            }
            c = next(c);
        }

        return data(first, length).readAll((int) length);
    }
}
