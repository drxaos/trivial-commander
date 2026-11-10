package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jetbrains.annotations.NotNull;

import tcm.util.Lzo;
import tcm.util.Zstd;

/**
 * Btrfs on a single device — read only: the default file system tree with its subvolumes and snapshots (as directories),
 * inline, regular and preallocated extents, zlib, LZO and zstd compression. Checksums are not verified.
 */
final class Btrfs {

    private static final long SUPERBLOCK = 0x10000;
    private static final String MAGIC = "_BHRfS_M";
    private static final int HEADER = 101;
    private static final int ITEM = 25;
    private static final int KEY_PTR = 33;
    private static final int SYS_CHUNK_ARRAY = 0x32B;
    private static final int TYPE_INODE_ITEM = 1;
    private static final int TYPE_DIR_INDEX = 96;
    private static final int TYPE_EXTENT_DATA = 108;
    private static final int TYPE_ROOT_ITEM = 132;
    private static final int TYPE_CHUNK_ITEM = 228;
    private static final long FS_TREE = 5;
    private static final long ROOT_DIR = 256;
    private static final int S_IFMT = 0170000;
    private static final int S_IFDIR = 0040000;
    private static final int S_IFREG = 0100000;
    private static final int S_IFLNK = 0120000;
    private static final int EXTENT_INLINE = 0;
    private static final int EXTENT_REGULAR = 1;
    private static final int COMPRESS_ZLIB = 1;
    private static final int COMPRESS_LZO = 2;
    private static final int COMPRESS_ZSTD = 3;
    private static final int LZO_SECTOR = 4096;
    private static final long STRIPED = 0x8 | 0x40 | 0x80 | 0x100;
    private static final int MAX_DEPTH = 256;
    private static final int MAX_LEVEL = 8;
    private static final int MAX_EXTENT = 128 * 1024 * 1024;
    private static final int MAX_LINK = 4096;
    private static final int CACHE_NODES = 256;

    private final @NotNull BlockSource dev;
    private final int nodeSize;
    private final TreeMap<Long, long[]> chunks = new TreeMap<>();
    private final Map<Long, ByteBuffer> cache = new LinkedHashMap<>(CACHE_NODES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, ByteBuffer> eldest) {
            return size() > CACHE_NODES;
        }
    };
    private long rootTree;

    private Btrfs(final @NotNull BlockSource dev, final int nodeSize) {
        this.dev = dev;
        this.nodeSize = nodeSize;
    }

    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        final byte[] b = DiskImages.peek(dev, SUPERBLOCK, 4096);

        return b != null && MAGIC.equals(DiskImages.ascii(DiskImages.le(b), 64, 8));
    }

    static @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
        final ByteBuffer sb = DiskImages.le(dev.read(SUPERBLOCK, 4096));
        final int nodeSize = sb.getInt(148);
        if (nodeSize < 4096 || nodeSize > 65536 || Integer.bitCount(nodeSize) != 1 || sb.getLong(136) > 1) {
            throw new IOException(sb.getLong(136) > 1 ? "Btrfs: multi-device file systems are not supported" : "Btrfs: damaged superblock");
        }
        final Btrfs fs = new Btrfs(dev, nodeSize);
        fs.sysChunks(sb);
        fs.scan(sb.getLong(88), Long.MIN_VALUE, TYPE_CHUNK_ITEM, Long.MIN_VALUE, Long.MAX_VALUE, (key, item) -> fs.chunk(key[2], item));
        fs.rootTree = sb.getLong(80);
        final FsTree tree = new FsTree("Btrfs");
        final byte[] label = new byte[256];
        sb.get(299, label);
        tree.setLabel(new String(label, StandardCharsets.UTF_8).replace("\0", "").strip());
        final long fsRoot = fs.subvolume(FS_TREE);
        if (fsRoot == 0) {
            throw new IOException("Btrfs: no file system tree");
        }
        tree.enter(key(FS_TREE, ROOT_DIR));
        fs.walk(fsRoot, FS_TREE, ROOT_DIR, "", tree, 0);

        return tree;
    }

    private static long key(final long subvolume, final long ino) {
        return subvolume << 40 ^ ino;
    }

    /** The system chunks in the superblock: enough to read the chunk tree. */
    private void sysChunks(final @NotNull ByteBuffer sb) throws IOException {
        final int size = sb.getInt(160);
        int p = SYS_CHUNK_ARRAY;
        final int end = SYS_CHUNK_ARRAY + Math.min(size, 2048);
        while (p + 17 + 48 <= end) {
            final long logical = sb.getLong(p + 9);
            final int stripes = sb.getShort(p + 17 + 44) & 0xFFFF;
            final byte[] item = new byte[48 + stripes * 32];
            if (p + 17 + item.length > end) {
                break;
            }
            sb.get(p + 17, item);
            chunk(logical, DiskImages.le(item));
            p += 17 + item.length;
        }
    }

    private void chunk(final long logical, final @NotNull ByteBuffer item) throws IOException {
        final long length = item.getLong(0);
        final long type = item.getLong(24);
        final int stripes = item.getShort(44) & 0xFFFF;
        if (stripes == 0 || item.limit() < 48 + 32) {
            return;
        }
        if ((type & STRIPED) != 0 && stripes > 1) {
            throw new IOException("Btrfs: striped (RAID0/5/6/10) chunks are not supported");
        }
        chunks.put(logical, new long[] {length, item.getLong(48 + 8)});
    }

    private long physical(final long logical) throws IOException {
        final Map.Entry<Long, long[]> e = chunks.floorEntry(logical);
        if (e == null || logical >= e.getKey() + e.getValue()[0]) {
            throw new IOException("Btrfs: address outside the chunks: " + logical);
        }

        return e.getValue()[1] + logical - e.getKey();
    }

    private @NotNull ByteBuffer node(final long logical) throws IOException {
        synchronized (cache) {
            final ByteBuffer cached = cache.get(logical);
            if (cached != null) {
                return cached;
            }
        }
        final ByteBuffer b = DiskImages.le(dev.read(physical(logical), nodeSize));
        if (b.getLong(48) != logical) {
            throw new IOException("Btrfs: damaged tree node at " + logical);
        }
        synchronized (cache) {
            cache.put(logical, b);
        }

        return b;
    }

    /** One item of a scan: its key {objectid, type, offset} and data. */
    @FunctionalInterface
    private interface ItemVisitor {

        void item(long @NotNull [] key, @NotNull ByteBuffer data) throws IOException;
    }

    /** Visits the items of the tree with the objectid and type, offsets in [from, to]; objectid MIN_VALUE — all objects. */
    private void scan(final long root, final long objectid, final int type, final long from, final long to,
        final @NotNull ItemVisitor visitor) throws IOException {
        final long[] min = objectid == Long.MIN_VALUE ? new long[] {0, type, 0} : new long[] {objectid, type, from};
        final long[] max = objectid == Long.MIN_VALUE ? new long[] {-1, type, -1} : new long[] {objectid, type, to};
        scan(root, min, max, objectid == Long.MIN_VALUE ? type : -1, visitor, -1);
    }

    /** expectedLevel: the level a child must have (one less than its parent's), -1 for a root. */
    private void scan(final long logical, final long @NotNull [] min, final long @NotNull [] max, final int anyObjectType,
        final @NotNull ItemVisitor visitor, final int expectedLevel) throws IOException {
        final ByteBuffer n = node(logical);
        final int count = n.getInt(96);
        final int level = n.get(100);
        if (level < 0 || level > MAX_LEVEL || expectedLevel >= 0 && level != expectedLevel) {
            throw new IOException("Btrfs: damaged tree");
        }
        if (level == 0) {
            for (int i = 0; i < count && HEADER + (i + 1) * ITEM <= nodeSize; i++) {
                final int at = HEADER + i * ITEM;
                final long[] key = key(n, at);
                final boolean inRange = anyObjectType >= 0 ? key[1] == anyObjectType : compare(key, min) >= 0 && compare(key, max) <= 0;
                if (inRange) {
                    final int off = HEADER + n.getInt(at + 17);
                    final int size = n.getInt(at + 21);
                    if (off >= HEADER && size >= 0 && off + size <= nodeSize) {
                        visitor.item(key, DiskImages.le(Arrays.copyOfRange(n.array(), off, off + size)));
                    }
                }
            }

            return;
        }
        for (int i = 0; i < count && HEADER + (i + 1) * KEY_PTR <= nodeSize; i++) {
            final int at = HEADER + i * KEY_PTR;
            final boolean last = i + 1 >= count;
            if (anyObjectType >= 0 || compare(key(n, at), max) <= 0 && (last || compare(key(n, at + KEY_PTR), min) > 0)) {
                scan(n.getLong(at + 17), min, max, anyObjectType, visitor, level - 1);
            }
        }
    }

    private static long @NotNull [] key(final @NotNull ByteBuffer n, final int at) {
        return new long[] {n.getLong(at), n.get(at + 8) & 0xFF, n.getLong(at + 9)};
    }

    private static int compare(final long @NotNull [] a, final long @NotNull [] b) {
        for (int i = 0; i < 3; i++) {
            final int c = Long.compareUnsigned(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }

        return 0;
    }

    /** The root node of a subvolume tree (the latest root item); 0 — none. */
    private long subvolume(final long id) throws IOException {
        final long[] root = {0, 0};
        scan(rootTree, id, TYPE_ROOT_ITEM, 0, -1, (key, item) -> {
            if (item.limit() >= 239 && Long.compareUnsigned(key[2], root[1]) >= 0) {
                root[0] = item.getLong(176);
                root[1] = key[2];
            }
        });

        return root[0];
    }

    private void walk(final long root, final long subvolume, final long dir, final @NotNull String path, final @NotNull FsTree tree,
        final int depth) throws IOException {
        final List<long[]> locations = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        scan(root, dir, TYPE_DIR_INDEX, 0, -1, (key, item) -> {
            final int nameLen = item.getShort(27) & 0xFFFF;
            if (30 + nameLen <= item.limit()) {
                locations.add(key(item, 0));
                names.add(new String(item.array(), 30, nameLen, StandardCharsets.UTF_8));
            }
        });
        for (int i = 0; i < names.size(); i++) {
            final String p = FsTree.child(path, names.get(i));
            final long[] location = locations.get(i);
            if (p == null) {
                continue;
            }
            if (location[1] == TYPE_ROOT_ITEM) {
                final long subRoot = subvolume(location[0]);
                tree.dir(p, 0, "");
                if (subRoot != 0 && depth < MAX_DEPTH && tree.enter(key(location[0], ROOT_DIR))) {
                    walk(subRoot, location[0], ROOT_DIR, p, tree, depth + 1);
                }
            } else if (location[1] == TYPE_INODE_ITEM) {
                entry(root, subvolume, location[0], p, tree, depth);
            }
        }
    }

    private void entry(final long root, final long subvolume, final long ino, final @NotNull String path, final @NotNull FsTree tree,
        final int depth) throws IOException {
        final ByteBuffer[] inode = new ByteBuffer[1];
        scan(root, ino, TYPE_INODE_ITEM, 0, 0, (key, item) -> inode[0] = item);
        final ByteBuffer n = inode[0];
        if (n == null || n.limit() < 160) {
            return;
        }
        final int mode = n.getInt(52);
        final long modified = n.getLong(136) * 1000 + Integer.toUnsignedLong(n.getInt(144)) / 1_000_000;
        final String attrs = FileAttrs.posix(mode);
        final long size = n.getLong(16);
        switch (mode & S_IFMT) {
            case S_IFDIR -> {
                tree.dir(path, modified, attrs);
                if (depth < MAX_DEPTH && tree.enter(key(subvolume, ino))) {
                    walk(root, subvolume, ino, path, tree, depth + 1);
                }
            }
            case S_IFREG -> tree.file(path, size, modified, attrs, () -> data(root, ino, size).stream());
            case S_IFLNK -> tree.link(path, modified, () -> new String(data(root, ino, size).readAll(MAX_LINK), StandardCharsets.UTF_8));
            default -> tree.file(path, 0, modified, attrs, InputStream::nullInputStream);
        }
    }

    private @NotNull FsData data(final long root, final long ino, final long size) throws IOException {
        final FsData data = new FsData(dev, size);
        scan(root, ino, TYPE_EXTENT_DATA, 0, -1, (key, item) -> extent(key[2], item, data));

        return data;
    }

    private void extent(final long logical, final @NotNull ByteBuffer item, final @NotNull FsData data) throws IOException {
        if (item.limit() < 21) {
            return;
        }
        final long ramBytes = item.getLong(8);
        final int compression = item.get(16) & 0xFF;
        final int type = item.get(20) & 0xFF;
        if (item.get(17) != 0) {
            throw new IOException("Btrfs: encrypted extents are not supported");
        }
        if (type == EXTENT_INLINE) {
            final byte[] raw = Arrays.copyOfRange(item.array(), 21, item.limit());
            final long length = compression == 0 ? raw.length : ramBytes;
            data.decoded(logical, length, 0, () -> compression == 0 ? raw : decompress(compression, raw, (int) Math.min(ramBytes, MAX_EXTENT)));

            return;
        }
        if (type != EXTENT_REGULAR || item.limit() < 53) {
            // preallocated: reads as zeros
            return;
        }
        final long diskStart = item.getLong(21);
        final long diskBytes = item.getLong(29);
        final long offset = item.getLong(37);
        final long numBytes = item.getLong(45);
        if (diskStart == 0) {
            return;
        }
        if (compression == 0) {
            data.raw(logical, physical(diskStart + offset), numBytes);
        } else {
            if (diskBytes > MAX_EXTENT || ramBytes > MAX_EXTENT) {
                throw new IOException("Btrfs: compressed extent too large");
            }
            final long physical = physical(diskStart);
            data.decoded(logical, numBytes, offset, () -> decompress(compression, dev.read(physical, (int) diskBytes), (int) ramBytes));
        }
    }

    private static byte @NotNull [] decompress(final int method, final byte @NotNull [] in, final int size) throws IOException {
        return switch (method) {
            case COMPRESS_ZLIB -> inflate(in, size);
            case COMPRESS_LZO -> lzo(in, size);
            case COMPRESS_ZSTD -> Zstd.decompress(in, size);
            default -> throw new IOException("Btrfs: unknown compression " + method);
        };
    }

    private static byte @NotNull [] inflate(final byte @NotNull [] in, final int size) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(in);
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
            throw new IOException("Btrfs: damaged zlib extent", e);
        } finally {
            inflater.end();
        }
    }

    /** Btrfs LZO: total length, then segments (length + LZO1X data) that never cross a 4 KiB sector boundary in their headers. */
    private static byte @NotNull [] lzo(final byte @NotNull [] in, final int size) throws IOException {
        final ByteBuffer b = DiskImages.le(in);
        final int total = Math.min(b.getInt(0), in.length);
        final byte[] out = new byte[size];
        int ip = 4;
        int op = 0;
        while (ip + 4 <= total && op < size) {
            if (LZO_SECTOR - ip % LZO_SECTOR < 4) {
                ip = (ip / LZO_SECTOR + 1) * LZO_SECTOR;
                continue;
            }
            final int length = b.getInt(ip);
            ip += 4;
            if (length <= 0 || ip + length > in.length) {
                throw new IOException("Btrfs: damaged LZO extent");
            }
            final byte[] segment = Lzo.decompress(in, ip, length, Math.min(LZO_SECTOR, size - op));
            System.arraycopy(segment, 0, out, op, segment.length);
            op += segment.length;
            ip += length;
        }

        return op == size ? out : Arrays.copyOf(out, op);
    }
}
