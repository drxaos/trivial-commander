package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;

/** exFAT — read only. Times with a UTC offset are exact, the others are taken as local. */
final class ExFat {

    private static final int ENTRY = 32;
    private static final int TYPE_LABEL = 0x83;
    private static final int TYPE_FILE = 0x85;
    private static final int TYPE_STREAM = 0xC0;
    private static final int TYPE_NAME = 0xC1;
    private static final int ATTR_DIRECTORY = 0x10;
    private static final int NO_FAT_CHAIN = 0x02;
    private static final int FAT_BLOCK = 64 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_DIR = 256 * 1024 * 1024;
    private static final int NAME_CHARS = 15;
    private static final int MINUTES_PER_STEP = 15;

    private final @NotNull BlockSource dev;
    private final long fatOffset;
    private final long fatBytes;
    private final long heapOffset;
    private final int clusterSize;
    private final long clusters;
    private final Map<Long, byte[]> fatCache = new ConcurrentHashMap<>();

    private ExFat(final @NotNull BlockSource dev, final @NotNull ByteBuffer boot) {
        this.dev = dev;
        final int sectorShift = boot.get(108) & 0xFF;
        final int clusterShift = boot.get(109) & 0xFF;
        this.fatOffset = Integer.toUnsignedLong(boot.getInt(80)) << sectorShift;
        this.fatBytes = Integer.toUnsignedLong(boot.getInt(84)) << sectorShift;
        this.heapOffset = Integer.toUnsignedLong(boot.getInt(88)) << sectorShift;
        this.clusters = Integer.toUnsignedLong(boot.getInt(92));
        this.clusterSize = 1 << sectorShift + clusterShift;
    }

    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        final byte[] b = DiskImages.peek(dev, 0, 512);

        return b != null && "EXFAT   ".equals(DiskImages.ascii(DiskImages.le(b), 3, 8));
    }

    static @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
        final ByteBuffer boot = DiskImages.le(dev.read(0, 512));
        final int sectorShift = boot.get(108) & 0xFF;
        final int clusterShift = boot.get(109) & 0xFF;
        if (sectorShift < 9 || sectorShift > 12 || sectorShift + clusterShift > 25) {
            throw new IOException("exFAT: bad geometry");
        }
        final ExFat fs = new ExFat(dev, boot);
        final FsTree tree = new FsTree("exFAT");
        final long root = Integer.toUnsignedLong(boot.getInt(96));
        final Runs rootRuns = fs.chain(root, 0, false);
        if (rootRuns.total > MAX_DIR) {
            throw new IOException("exFAT: root directory too large");
        }
        fs.walk(rootRuns.readAll(MAX_DIR), "", tree, 0, new HashSet<>());

        return tree;
    }

    private void walk(final byte @NotNull [] dir, final @NotNull String path, final @NotNull FsTree tree, final int depth,
        final @NotNull Set<Long> seen) throws IOException {
        final ByteBuffer b = DiskImages.le(dir);
        for (int off = 0; off + ENTRY <= dir.length; off += ENTRY) {
            final int type = dir[off] & 0xFF;
            if (type == 0) {
                break;
            }
            if (type == TYPE_LABEL && depth == 0) {
                tree.setLabel(DiskImages.utf16(b, off + 2, Math.min(dir[off + 1] & 0xFF, 11)));
                continue;
            }
            if (type != TYPE_FILE) {
                continue;
            }
            final int secondary = dir[off + 1] & 0xFF;
            if (secondary < 2 || off + (secondary + 1) * ENTRY > dir.length || (dir[off + ENTRY] & 0xFF) != TYPE_STREAM) {
                continue;
            }
            entry(b, off, secondary, path, tree, depth, seen);
            off += secondary * ENTRY;
        }
    }

    private void entry(final @NotNull ByteBuffer b, final int off, final int secondary, final @NotNull String path,
        final @NotNull FsTree tree, final int depth, final @NotNull Set<Long> seen) throws IOException {
        final int attr = b.getShort(off + 4) & 0xFFFF;
        final long modified = time(b.getInt(off + 12), b.get(off + 23) & 0xFF);
        final int s = off + ENTRY;
        final int flags = b.get(s + 1) & 0xFF;
        final int nameLength = b.get(s + 3) & 0xFF;
        final long valid = b.getLong(s + 8);
        final long first = Integer.toUnsignedLong(b.getInt(s + 20));
        final long length = b.getLong(s + 24);
        final StringBuilder name = new StringBuilder(nameLength);
        for (int i = 2; i <= secondary && name.length() < nameLength; i++) {
            final int n = off + i * ENTRY;
            if ((b.get(n) & 0xFF) == TYPE_NAME) {
                name.append(DiskImages.utf16(b, n + 2, Math.min(NAME_CHARS, nameLength - name.length())));
            }
        }
        final String child = FsTree.child(path, name.toString());
        if (child == null) {
            return;
        }
        final String attrs = FileAttrs.dos(attr);
        final boolean contiguous = (flags & NO_FAT_CHAIN) != 0;
        if ((attr & ATTR_DIRECTORY) != 0) {
            tree.dir(child, modified, attrs);
            if (first >= 2 && depth < MAX_DEPTH && seen.add(first) && length <= MAX_DIR) {
                walk(chain(first, length, contiguous).readAll((int) length), child, tree, depth + 1, seen);
            }
        } else {
            tree.file(child, length, modified, attrs, () -> chain(first, Math.min(valid, length), contiguous).withSize(length).stream());
        }
    }

    /** DOS date and time in one word; with the offset valid (bit 7) — UTC plus the offset in 15-minute steps. */
    private static long time(final int stamp, final int offset) {
        if (stamp == 0) {
            return 0;
        }
        try {
            final LocalDateTime t = LocalDateTime.of(1980 + (stamp >>> 25), Math.max(1, stamp >> 21 & 15), Math.max(1, stamp >> 16 & 31),
                stamp >> 11 & 31, stamp >> 5 & 63, Math.min(59, (stamp & 31) * 2));
            if ((offset & 0x80) != 0) {
                final int steps = (byte) (offset << 1) >> 1;

                return t.toInstant(ZoneOffset.ofTotalSeconds(steps * MINUTES_PER_STEP * 60)).toEpochMilli();
            }

            return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (final DateTimeException e) {
            // a damaged date
            return 0;
        }
    }

    /** The clusters of a stream up to the length (0 — the whole FAT chain), contiguous or by the FAT. */
    private @NotNull Runs chain(final long first, final long length, final boolean contiguous) throws IOException {
        final Runs runs = new Runs();
        if (first < 2) {
            return runs;
        }
        if (contiguous) {
            runs.add(heapOffset + (first - 2) * clusterSize, length);

            return runs;
        }
        long c = first;
        int steps = 0;
        // a chain never has more links than there are clusters: more means a loop in a damaged FAT
        while (c >= 2 && c < clusters + 2 && (length == 0 || runs.total < length) && steps++ <= clusters) {
            runs.add(heapOffset + (c - 2) * clusterSize, length == 0 ? clusterSize : Math.min(clusterSize, length - runs.total));
            c = next(c);
        }

        return runs;
    }

    private long next(final long cluster) throws IOException {
        final long pos = cluster * 4;
        if (pos + 4 > fatBytes) {
            return -1;
        }
        final long block = pos / FAT_BLOCK;
        byte[] data = fatCache.get(block);
        if (data == null) {
            data = dev.read(fatOffset + block * FAT_BLOCK, (int) Math.min(FAT_BLOCK, fatBytes - block * FAT_BLOCK));
            fatCache.put(block, data);
        }
        final long e = Integer.toUnsignedLong(DiskImages.le(data).getInt((int) (pos % FAT_BLOCK)));

        return e >= 0xFFFFFFF7L ? -1 : e;
    }

    /** Device ranges of a stream, merged when adjacent. */
    private final class Runs {

        private final List<long[]> list = new ArrayList<>();
        private long total;

        private void add(final long physical, final long length) {
            final long[] last = list.isEmpty() ? null : list.get(list.size() - 1);
            if (last != null && last[0] + last[1] == physical) {
                last[1] += length;
            } else {
                list.add(new long[] {physical, length});
            }
            total += length;
        }

        private @NotNull FsData withSize(final long size) {
            final FsData data = new FsData(dev, size);
            long logical = 0;
            for (final long[] r : list) {
                data.raw(logical, r[0], r[1]);
                logical += r[1];
            }

            return data;
        }

        private byte @NotNull [] readAll(final int max) throws IOException {
            return withSize(total).readAll(max);
        }
    }
}
