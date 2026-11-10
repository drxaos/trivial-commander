package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * ext2, ext3 and ext4 — read only: block maps and extent trees, inline data, 64-bit and meta_bg group descriptors.
 * The journal is not replayed: an image of an unmounted (cleanly unmounted) file system is read as it is.
 */
final class ExtFs {

    private static final int SUPERBLOCK = 1024;
    private static final int MAGIC = 0xEF53;
    private static final long ROOT = 2;
    private static final int INCOMPAT_FILETYPE = 0x2;
    private static final int INCOMPAT_META_BG = 0x10;
    private static final int INCOMPAT_64BIT = 0x80;
    private static final int RO_COMPAT_SPARSE_SUPER = 0x1;
    private static final int FL_EXTENTS = 0x80000;
    private static final int FL_INLINE_DATA = 0x10000000;
    private static final int EXTENT_MAGIC = 0xF30A;
    private static final int UNINITIALIZED = 32768;
    private static final int I_BLOCK = 40;
    private static final int I_BLOCK_SIZE = 60;
    private static final int XATTR_MAGIC = 0xEA020000;
    private static final int XATTR_SYSTEM = 7;
    private static final int S_IFMT = 0170000;
    private static final int S_IFDIR = 0040000;
    private static final int S_IFREG = 0100000;
    private static final int S_IFLNK = 0120000;
    private static final int MAX_DEPTH = 256;
    private static final int MAX_TREE_DEPTH = 8;
    private static final int MAX_DIR = 256 * 1024 * 1024;
    private static final int MAX_LINK = 4096;
    private static final int CACHE_BLOCKS = 256;

    private final @NotNull BlockSource dev;
    private final int blockSize;
    private final long blocksPerGroup;
    private final long inodesPerGroup;
    private final int inodeSize;
    private final int descSize;
    private final long firstDataBlock;
    private final long firstMetaBg;
    private final int incompat;
    private final int roCompat;
    private final long groups;
    private final Map<Long, byte[]> cache = new LinkedHashMap<>(CACHE_BLOCKS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, byte[]> eldest) {
            return size() > CACHE_BLOCKS;
        }
    };

    private ExtFs(final @NotNull BlockSource dev, final @NotNull ByteBuffer sb) throws IOException {
        this.dev = dev;
        final int log = sb.getInt(24);
        if (log < 0 || log > 6) {
            throw new IOException("ext: bad block size");
        }
        this.blockSize = 1024 << log;
        this.blocksPerGroup = Integer.toUnsignedLong(sb.getInt(32));
        this.inodesPerGroup = Integer.toUnsignedLong(sb.getInt(40));
        this.inodeSize = sb.getInt(76) == 0 ? 128 : sb.getShort(88) & 0xFFFF;
        this.incompat = sb.getInt(96);
        this.roCompat = sb.getInt(100);
        this.descSize = (incompat & INCOMPAT_64BIT) != 0 ? Math.max(32, sb.getShort(254) & 0xFFFF) : 32;
        this.firstDataBlock = Integer.toUnsignedLong(sb.getInt(20));
        this.firstMetaBg = Integer.toUnsignedLong(sb.getInt(0x104));
        final long blocks = Integer.toUnsignedLong(sb.getInt(4))
            | ((incompat & INCOMPAT_64BIT) != 0 ? Integer.toUnsignedLong(sb.getInt(0x150)) << 32 : 0);
        if (blocksPerGroup == 0 || inodesPerGroup == 0 || inodeSize < 128 || inodeSize > blockSize || descSize > 1024) {
            throw new IOException("ext: damaged superblock");
        }
        this.groups = (blocks - firstDataBlock + blocksPerGroup - 1) / blocksPerGroup;
    }

    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        final byte[] b = DiskImages.peek(dev, SUPERBLOCK, 1024);

        return b != null && (DiskImages.le(b).getShort(56) & 0xFFFF) == MAGIC;
    }

    static @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
        final ByteBuffer sb = DiskImages.le(dev.read(SUPERBLOCK, 1024));
        final ExtFs fs = new ExtFs(dev, sb);
        final int compat = sb.getInt(92);
        final String type = (fs.incompat & (0x40 | 0x80 | 0x200)) != 0 || (fs.roCompat & 0x8) != 0 ? "ext4" : (compat & 0x4) != 0 ? "ext3" : "ext2";
        final FsTree tree = new FsTree(type);
        tree.setLabel(cString(Arrays.copyOfRange(sb.array(), 120, 136)));
        tree.enter(ROOT);
        fs.walk(ROOT, fs.inode(ROOT), "", tree, 0);

        return tree;
    }

    private static @NotNull String cString(final byte @NotNull [] b) {
        int n = 0;
        while (n < b.length && b[n] != 0) {
            n++;
        }

        return new String(b, 0, n, StandardCharsets.UTF_8).strip();
    }

    private void walk(final long ino, final @NotNull ByteBuffer dirInode, final @NotNull String path, final @NotNull FsTree tree,
        final int depth) throws IOException {
        final byte[] dir = contents(dirInode, MAX_DIR);
        final boolean inline = (dirInode.getInt(32) & FL_INLINE_DATA) != 0;
        int off = inline ? 4 : 0;
        final boolean filetype = (incompat & INCOMPAT_FILETYPE) != 0;
        final ByteBuffer b = DiskImages.le(dir);
        while (off + 8 <= dir.length) {
            final long child = Integer.toUnsignedLong(b.getInt(off));
            final int recLen = b.getShort(off + 4) & 0xFFFF;
            final int nameLen = filetype ? dir[off + 6] & 0xFF : b.getShort(off + 6) & 0xFFFF;
            if (recLen < 8 || off + recLen > dir.length) {
                if (inline && recLen == 0) {
                    break;
                }
                // the next block: a damaged entry ends the scan of this block only
                off = (off / blockSize + 1) * blockSize;
                continue;
            }
            if (child != 0 && child != ino && nameLen > 0 && 8 + nameLen <= recLen) {
                final String name = new String(dir, off + 8, nameLen, StandardCharsets.UTF_8);
                final String p = FsTree.child(path, name);
                if (p != null) {
                    entry(child, p, tree, depth);
                }
            }
            off += recLen;
        }
    }

    private void entry(final long ino, final @NotNull String path, final @NotNull FsTree tree, final int depth) throws IOException {
        final ByteBuffer n = inode(ino);
        final int mode = n.getShort(0) & 0xFFFF;
        final long modified = mtime(n);
        final String attrs = FileAttrs.posix(mode);
        switch (mode & S_IFMT) {
            case S_IFDIR -> {
                tree.dir(path, modified, attrs);
                if (depth < MAX_DEPTH && tree.enter(ino)) {
                    walk(ino, n, path, tree, depth + 1);
                }
            }
            case S_IFREG -> tree.file(path, size(n), modified, attrs, () -> data(inode(ino)).stream());
            case S_IFLNK -> tree.link(path, modified, () -> linkTarget(inode(ino)));
            default -> tree.file(path, 0, modified, attrs, InputStream::nullInputStream);
        }
    }

    private static long size(final @NotNull ByteBuffer n) {
        return Integer.toUnsignedLong(n.getInt(4)) | Integer.toUnsignedLong(n.getInt(108)) << 32;
    }

    /** Modification time; the extra field adds two epoch bits (dates past 2038) and nanoseconds. */
    private long mtime(final @NotNull ByteBuffer n) {
        long seconds = n.getInt(16);
        long nanos = 0;
        if (inodeSize > 128 && (n.getShort(128) & 0xFFFF) >= 12) {
            final int extra = n.getInt(0x88);
            seconds += (long) (extra & 3) << 32;
            nanos = extra >>> 2;
        }

        return seconds * 1000 + nanos / 1_000_000;
    }

    private @NotNull String linkTarget(final @NotNull ByteBuffer n) throws IOException {
        final long size = size(n);
        final int flags = n.getInt(32);
        // a fast link keeps its target in i_block and has no data blocks (an attribute block may be counted in i_blocks)
        final long dataSectors = Integer.toUnsignedLong(n.getInt(28)) - (n.getInt(104) != 0 ? blockSize / 512 : 0);
        if (size < I_BLOCK_SIZE && (flags & (FL_EXTENTS | FL_INLINE_DATA)) == 0 && dataSectors <= 0) {
            return new String(n.array(), I_BLOCK, (int) size, StandardCharsets.UTF_8);
        }

        return new String(contents(n, MAX_LINK), StandardCharsets.UTF_8);
    }

    private byte @NotNull [] contents(final @NotNull ByteBuffer n, final int max) throws IOException {
        if (size(n) > max) {
            throw new IOException("ext: directory too large");
        }

        return data(n).readAll(max);
    }

    /** The file content: inline, by extents or by the block map. */
    private @NotNull FsData data(final @NotNull ByteBuffer n) throws IOException {
        final long size = size(n);
        final FsData data = new FsData(dev, size);
        final int flags = n.getInt(32);
        if ((flags & FL_INLINE_DATA) != 0) {
            final byte[] inline = inline(n, size);

            return data.decoded(0, inline.length, 0, () -> inline);
        }
        final byte[] iblock = Arrays.copyOfRange(n.array(), I_BLOCK, I_BLOCK + I_BLOCK_SIZE);
        if ((flags & FL_EXTENTS) != 0) {
            extents(DiskImages.le(iblock), data, 0);
        } else {
            blockMap(DiskImages.le(iblock), data, (size + blockSize - 1) / blockSize);
        }

        return data;
    }

    private void extents(final @NotNull ByteBuffer node, final @NotNull FsData data, final int level) throws IOException {
        if ((node.getShort(0) & 0xFFFF) != EXTENT_MAGIC || level > MAX_TREE_DEPTH) {
            throw new IOException("ext: damaged extent tree");
        }
        final int entries = node.getShort(2) & 0xFFFF;
        final int depth = node.getShort(6) & 0xFFFF;
        for (int i = 0; i < entries && 12 + (i + 1) * 12 <= node.limit(); i++) {
            final int e = 12 + i * 12;
            final long logical = Integer.toUnsignedLong(node.getInt(e));
            if (depth == 0) {
                int length = node.getShort(e + 4) & 0xFFFF;
                final boolean uninitialized = length > UNINITIALIZED;
                if (uninitialized) {
                    length -= UNINITIALIZED;
                }
                final long start = (node.getShort(e + 6) & 0xFFFFL) << 32 | Integer.toUnsignedLong(node.getInt(e + 8));
                if (!uninitialized) {
                    data.raw(logical * blockSize, start * blockSize, (long) length * blockSize);
                }
            } else {
                final long leaf = Integer.toUnsignedLong(node.getInt(e + 4)) | (node.getShort(e + 8) & 0xFFFFL) << 32;
                extents(DiskImages.le(block(leaf)), data, level + 1);
            }
        }
    }

    /** ext2/ext3 block map: 12 direct pointers, then single, double and triple indirect blocks. */
    private void blockMap(final @NotNull ByteBuffer iblock, final @NotNull FsData data, final long blocks) throws IOException {
        final long[] next = {0};
        final long[] run = {-1, 0, 0};
        for (int i = 0; i < 12 && next[0] < blocks; i++) {
            map(Integer.toUnsignedLong(iblock.getInt(i * 4)), data, next, run);
        }
        for (int level = 1; level <= 3 && next[0] < blocks; level++) {
            indirect(Integer.toUnsignedLong(iblock.getInt((11 + level) * 4)), level, data, next, run, blocks);
        }
        flush(data, run);
    }

    private void indirect(final long ptr, final int level, final @NotNull FsData data, final long @NotNull [] next,
        final long @NotNull [] run, final long blocks) throws IOException {
        final long span = (long) Math.pow((double) blockSize / 4, level);
        if (ptr == 0) {
            next[0] += span;

            return;
        }
        final ByteBuffer b = DiskImages.le(block(ptr));
        for (int i = 0; i < blockSize / 4 && next[0] < blocks; i++) {
            final long p = Integer.toUnsignedLong(b.getInt(i * 4));
            if (level == 1) {
                map(p, data, next, run);
            } else {
                indirect(p, level - 1, data, next, run, blocks);
            }
        }
    }

    /** One logical block: merged into the current run {logical start, physical start, count} when contiguous. */
    private void map(final long physical, final @NotNull FsData data, final long @NotNull [] next, final long @NotNull [] run) {
        final long logical = next[0]++;
        if (physical == 0) {
            return;
        }
        if (run[0] >= 0 && run[0] + run[2] == logical && run[1] + run[2] == physical) {
            run[2]++;

            return;
        }
        flush(data, run);
        run[0] = logical;
        run[1] = physical;
        run[2] = 1;
    }

    private void flush(final @NotNull FsData data, final long @NotNull [] run) {
        if (run[0] >= 0) {
            data.raw(run[0] * blockSize, run[1] * blockSize, run[2] * blockSize);
        }
        run[0] = -1;
    }

    /** Inline data: the 60 bytes of i_block, continued by the "system.data" attribute inside the inode. */
    private byte @NotNull [] inline(final @NotNull ByteBuffer n, final long size) {
        final int head = (int) Math.min(size, I_BLOCK_SIZE);
        final byte[] tail = inlineXattr(n);
        final byte[] result = new byte[(int) Math.min(size, head + (long) tail.length)];
        System.arraycopy(n.array(), I_BLOCK, result, 0, head);
        System.arraycopy(tail, 0, result, head, result.length - head);

        return result;
    }

    private byte @NotNull [] inlineXattr(final @NotNull ByteBuffer n) {
        if (inodeSize <= 128) {
            return new byte[0];
        }
        final int start = 128 + (n.getShort(128) & 0xFFFF);
        if (start + 4 > inodeSize || n.getInt(start) != XATTR_MAGIC) {
            return new byte[0];
        }
        final int first = start + 4;
        int e = first;
        while (e + 16 <= inodeSize && n.getInt(e) != 0) {
            final int nameLen = n.get(e) & 0xFF;
            final int index = n.get(e + 1) & 0xFF;
            final int valueOffset = n.getShort(e + 2) & 0xFFFF;
            final int valueSize = n.getInt(e + 8);
            if (e + 16 + nameLen > inodeSize) {
                break;
            }
            final String name = new String(n.array(), e + 16, nameLen, StandardCharsets.ISO_8859_1);
            if (index == XATTR_SYSTEM && "data".equals(name) && valueSize >= 0 && first + valueOffset + valueSize <= inodeSize) {
                return Arrays.copyOfRange(n.array(), first + valueOffset, first + valueOffset + valueSize);
            }
            e += 16 + (nameLen + 3 & ~3);
        }

        return new byte[0];
    }

    private @NotNull ByteBuffer inode(final long ino) throws IOException {
        if (ino < 1 || (ino - 1) / inodesPerGroup >= groups) {
            throw new IOException("ext: bad inode number " + ino);
        }
        final long group = (ino - 1) / inodesPerGroup;
        final long index = (ino - 1) % inodesPerGroup;
        final long pos = inodeTable(group) * blockSize + index * inodeSize;
        final byte[] b = block(pos / blockSize);
        final int off = (int) (pos % blockSize);

        return DiskImages.le(Arrays.copyOfRange(b, off, off + inodeSize));
    }

    private long inodeTable(final long group) throws IOException {
        final int perBlock = blockSize / descSize;
        final long descBlock;
        if ((incompat & INCOMPAT_META_BG) != 0 && group / perBlock >= firstMetaBg) {
            final long first = group / perBlock * perBlock;
            descBlock = firstDataBlock + first * blocksPerGroup + (hasSuper(first) ? 1 : 0);
        } else {
            descBlock = firstDataBlock + 1 + group / perBlock;
        }
        final ByteBuffer d = DiskImages.le(block(descBlock));
        final int off = (int) (group % perBlock) * descSize;
        final long lo = Integer.toUnsignedLong(d.getInt(off + 8));

        return descSize >= 64 ? lo | Integer.toUnsignedLong(d.getInt(off + 0x28)) << 32 : lo;
    }

    private boolean hasSuper(final long group) {
        if ((roCompat & RO_COMPAT_SPARSE_SUPER) == 0 || group <= 1) {
            return true;
        }

        return isPower(group, 3) || isPower(group, 5) || isPower(group, 7);
    }

    private static boolean isPower(final long n, final int base) {
        long p = base;
        while (p < n) {
            p *= base;
        }

        return p == n;
    }

    private byte @NotNull [] block(final long n) throws IOException {
        synchronized (cache) {
            final byte[] cached = cache.get(n);
            if (cached != null) {
                return cached;
            }
        }
        final byte[] b = dev.read(n * blockSize, blockSize);
        synchronized (cache) {
            cache.put(n, b);
        }

        return b;
    }
}
