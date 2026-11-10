package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.NotNull;

/**
 * XFS (v4 and v5) — read only: short-form, block, leaf and node directories, extent and B-tree data forks, inline and remote
 * symbolic links, big timestamps and 64-bit extent counters. The log is not replayed.
 */
final class Xfs {

    private static final int MAGIC = 0x58465342;
    private static final int INODE_MAGIC = 0x494E;
    private static final int FORMAT_LOCAL = 1;
    private static final int FORMAT_EXTENTS = 2;
    private static final int FORMAT_BTREE = 3;
    private static final int S_IFMT = 0170000;
    private static final int S_IFDIR = 0040000;
    private static final int S_IFREG = 0100000;
    private static final int S_IFLNK = 0120000;
    private static final long LEAF_OFFSET = 32L << 30;
    private static final int INCOMPAT_FTYPE = 0x1;
    private static final int INCOMPAT_NREXT64 = 0x20;
    private static final int FEATURES2_FTYPE = 0x200;
    private static final long FLAG2_BIGTIME = 0x8;
    private static final long FLAG2_NREXT64 = 0x10;
    private static final long BIGTIME_EPOCH_OFFSET = 1L << 31;
    private static final int DATA_HEADER_V4 = 16;
    private static final int DATA_HEADER_V5 = 64;
    private static final int BTREE_HEADER_V4 = 24;
    private static final int BTREE_HEADER_V5 = 72;
    private static final int SYMLINK_HEADER_V5 = 56;
    private static final int FREE_TAG = 0xFFFF;
    private static final int MAX_DEPTH = 256;
    private static final int MAX_TREE_DEPTH = 10;
    private static final long MAX_DIR = 256L * 1024 * 1024;
    private static final int MAX_LINK = 4096;

    private final @NotNull BlockSource dev;
    private final int blockSize;
    private final long agBlocks;
    private final int agBlockLog;
    private final int inodeSize;
    private final int inodesPerBlockLog;
    private final int dirBlockSize;
    private final boolean v5;
    private final boolean ftype;
    private final boolean nrext64;

    private Xfs(final @NotNull BlockSource dev, final @NotNull ByteBuffer sb) throws IOException {
        this.dev = dev;
        this.blockSize = sb.getInt(4);
        this.agBlocks = Integer.toUnsignedLong(sb.getInt(84));
        this.inodeSize = sb.getShort(104) & 0xFFFF;
        this.agBlockLog = sb.get(124) & 0xFF;
        this.inodesPerBlockLog = sb.get(123) & 0xFF;
        this.dirBlockSize = blockSize << (sb.get(192) & 0xFF);
        this.v5 = (sb.getShort(100) & 0xF) == 5;
        final int incompat = v5 ? sb.getInt(216) : 0;
        this.ftype = v5 ? (incompat & INCOMPAT_FTYPE) != 0 : (sb.getInt(200) & FEATURES2_FTYPE) != 0;
        this.nrext64 = (incompat & INCOMPAT_NREXT64) != 0;
        if (blockSize < 512 || blockSize > 65536 || Integer.bitCount(blockSize) != 1 || inodeSize < 256 || inodeSize > blockSize
            || agBlockLog > 32 || dirBlockSize > 1 << 20) {
            throw new IOException("XFS: damaged superblock");
        }
    }

    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        final byte[] b = DiskImages.peek(dev, 0, 512);

        return b != null && ByteBuffer.wrap(b).getInt(0) == MAGIC;
    }

    static @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
        final ByteBuffer sb = ByteBuffer.wrap(dev.read(0, 512));
        final Xfs fs = new Xfs(dev, sb);
        final FsTree tree = new FsTree("XFS");
        tree.setLabel(DiskImages.ascii(sb, 108, 12).replace("\0", "").strip());
        final long root = sb.getLong(56);
        tree.enter(root);
        fs.walk(fs.inode(root), "", tree, 0);

        return tree;
    }

    private void walk(final @NotNull ByteBuffer dir, final @NotNull String path, final @NotNull FsTree tree, final int depth)
        throws IOException {
        final List<long[]> children = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final int format = dir.get(5);
        if (format == FORMAT_LOCAL) {
            shortForm(fork(dir), names, children);
        } else if (format == FORMAT_EXTENTS || format == FORMAT_BTREE) {
            for (final long[] e : extents(dir)) {
                blockDir(e, names, children);
            }
        }
        for (int i = 0; i < names.size(); i++) {
            final String p = FsTree.child(path, names.get(i));
            if (p != null) {
                entry(children.get(i)[0], p, tree, depth);
            }
        }
    }

    private void entry(final long ino, final @NotNull String path, final @NotNull FsTree tree, final int depth) throws IOException {
        final ByteBuffer n = inode(ino);
        final int mode = n.getShort(2) & 0xFFFF;
        final long modified = mtime(n);
        final String attrs = FileAttrs.posix(mode);
        switch (mode & S_IFMT) {
            case S_IFDIR -> {
                tree.dir(path, modified, attrs);
                if (depth < MAX_DEPTH && tree.enter(ino)) {
                    walk(n, path, tree, depth + 1);
                }
            }
            case S_IFREG -> tree.file(path, n.getLong(56), modified, attrs, () -> data(inode(ino)).stream());
            case S_IFLNK -> tree.link(path, modified, () -> linkTarget(inode(ino)));
            default -> tree.file(path, 0, modified, attrs, InputStream::nullInputStream);
        }
    }

    private long mtime(final @NotNull ByteBuffer n) {
        if ((n.get(4) & 0xFF) >= 3 && (n.getLong(120) & FLAG2_BIGTIME) != 0) {
            final long nanos = n.getLong(40);

            return Long.divideUnsigned(nanos, 1_000_000) - BIGTIME_EPOCH_OFFSET * 1000;
        }

        return n.getInt(40) * 1000L + Integer.toUnsignedLong(n.getInt(44)) / 1_000_000;
    }

    private @NotNull String linkTarget(final @NotNull ByteBuffer n) throws IOException {
        final int size = (int) Math.min(n.getLong(56), MAX_LINK);
        if (n.get(5) == FORMAT_LOCAL) {
            final ByteBuffer f = fork(n);

            return new String(f.array(), f.arrayOffset(), Math.min(size, f.limit()), StandardCharsets.UTF_8);
        }
        final byte[] out = new byte[size];
        int got = 0;
        for (final long[] e : extents(n)) {
            for (long b = 0; b < e[2] && got < size; b++) {
                final byte[] block = dev.read(physical(e[1] + b), blockSize);
                final int skip = v5 ? SYMLINK_HEADER_V5 : 0;
                final int len = Math.min(size - got, blockSize - skip);
                System.arraycopy(block, skip, out, got, len);
                got += len;
            }
        }

        return new String(out, 0, got, StandardCharsets.UTF_8);
    }

    private @NotNull FsData data(final @NotNull ByteBuffer n) throws IOException {
        final FsData data = new FsData(dev, n.getLong(56));
        final int format = n.get(5);
        if (format == FORMAT_LOCAL) {
            final ByteBuffer f = fork(n);
            final byte[] inline = Arrays.copyOfRange(f.array(), f.arrayOffset(), f.arrayOffset() + f.limit());

            return data.decoded(0, inline.length, 0, () -> inline);
        }
        for (final long[] e : extents(n)) {
            if (e[3] == 0) {
                data.raw(e[0] * blockSize, physical(e[1]), e[2] * blockSize);
            }
        }

        return data;
    }

    /** Byte offset of a file system block number (AG number in the high bits). */
    private long physical(final long fsBlock) {
        final long ag = fsBlock >>> agBlockLog;
        final long block = fsBlock & (1L << agBlockLog) - 1;

        return (ag * agBlocks + block) * blockSize;
    }

    /** The data fork of the inode. */
    private @NotNull ByteBuffer fork(final @NotNull ByteBuffer n) {
        final int core = (n.get(4) & 0xFF) >= 3 ? 176 : 100;
        final int forkOff = n.get(82) & 0xFF;
        final int size = forkOff != 0 ? forkOff * 8 : inodeSize - core;

        return ByteBuffer.wrap(n.array(), core, Math.min(size, inodeSize - core)).slice();
    }

    /** Data fork extents {logical block, fs block, count, unwritten}. */
    private @NotNull List<long[]> extents(final @NotNull ByteBuffer n) throws IOException {
        final List<long[]> result = new ArrayList<>();
        final ByteBuffer f = fork(n);
        if (n.get(5) == FORMAT_EXTENTS) {
            final long count = nrext64 && (n.get(4) & 0xFF) >= 3 && (n.getLong(120) & FLAG2_NREXT64) != 0
                ? n.getLong(24) : Integer.toUnsignedLong(n.getInt(76));
            for (int i = 0; i < count && (i + 1) * 16 <= f.limit(); i++) {
                result.add(record(f, i * 16));
            }
        } else if (n.get(5) == FORMAT_BTREE) {
            final int level = f.getShort(0) & 0xFFFF;
            final int records = f.getShort(2) & 0xFFFF;
            final int max = (f.limit() - 4) / 16;
            for (int i = 0; i < records && i < max; i++) {
                btree(f.getLong(4 + max * 8 + i * 8), level - 1, result);
            }
        }

        return result;
    }

    private void btree(final long block, final int level, final @NotNull List<long[]> out) throws IOException {
        if (level < 0 || level > MAX_TREE_DEPTH) {
            throw new IOException("XFS: damaged block map");
        }
        final ByteBuffer b = ByteBuffer.wrap(dev.read(physical(block), blockSize));
        final int header = v5 ? BTREE_HEADER_V5 : BTREE_HEADER_V4;
        final int records = b.getShort(6) & 0xFFFF;
        if (level == 0) {
            for (int i = 0; i < records && header + (i + 1) * 16 <= blockSize; i++) {
                out.add(record(b, header + i * 16));
            }
        } else {
            final int max = (blockSize - header) / 16;
            for (int i = 0; i < records && i < max; i++) {
                btree(b.getLong(header + max * 8 + i * 8), level - 1, out);
            }
        }
    }

    /** A packed 128-bit extent record: unwritten flag, 54-bit offset, 52-bit block, 21-bit count. */
    private static long @NotNull [] record(final @NotNull ByteBuffer b, final int off) {
        final long hi = b.getLong(off);
        final long lo = b.getLong(off + 8);
        final long unwritten = hi >>> 63;
        final long logical = (hi & 0x7FFFFFFFFFFFFFFFL) >>> 9;
        final long block = (hi & 0x1FF) << 43 | lo >>> 21;
        final long count = lo & 0x1FFFFF;

        return new long[] {logical, block, count, unwritten};
    }

    private void shortForm(final @NotNull ByteBuffer f, final @NotNull List<String> names, final @NotNull List<long[]> inodes) {
        final int count = f.get(0) & 0xFF;
        final boolean wide = (f.get(1) & 0xFF) != 0;
        final int inoSize = wide ? 8 : 4;
        int p = 2 + inoSize;
        for (int i = 0; i < count && p < f.limit(); i++) {
            final int nameLen = f.get(p) & 0xFF;
            final int nameAt = p + 3;
            final int inoAt = nameAt + nameLen + (ftype ? 1 : 0);
            if (inoAt + inoSize > f.limit()) {
                break;
            }
            names.add(new String(f.array(), f.arrayOffset() + nameAt, nameLen, StandardCharsets.UTF_8));
            inodes.add(new long[] {wide ? f.getLong(inoAt) : Integer.toUnsignedLong(f.getInt(inoAt))});
            p = inoAt + inoSize;
        }
    }

    /** The data blocks of a directory extent (the leaf and free index parts past 32 GiB are skipped). */
    private void blockDir(final long @NotNull [] e, final @NotNull List<String> names, final @NotNull List<long[]> inodes)
        throws IOException {
        final int perDirBlock = dirBlockSize / blockSize;
        final long maxBlocks = MAX_DIR / blockSize;
        for (long b = 0; b < e[2] && b < maxBlocks; b += perDirBlock) {
            if ((e[0] + b) * blockSize >= LEAF_OFFSET) {
                return;
            }
            dataBlock(ByteBuffer.wrap(dev.read(physical(e[1] + b), dirBlockSize)), names, inodes);
        }
    }

    private void dataBlock(final @NotNull ByteBuffer b, final @NotNull List<String> names, final @NotNull List<long[]> inodes) {
        final int magic = b.getInt(0);
        final boolean single = magic == 0x58443242 || magic == 0x58444233;
        final boolean data = magic == 0x58443244 || magic == 0x58444433;
        if (!single && !data) {
            return;
        }
        int end = dirBlockSize;
        if (single) {
            final int leafCount = b.getInt(dirBlockSize - 8);
            end = dirBlockSize - 8 - leafCount * 8;
        }
        int p = v5 ? DATA_HEADER_V5 : DATA_HEADER_V4;
        while (p + 8 <= end) {
            if ((b.getShort(p) & 0xFFFF) == FREE_TAG) {
                final int length = b.getShort(p + 2) & 0xFFFF;
                if (length < 8) {
                    return;
                }
                p += length;
                continue;
            }
            final long ino = b.getLong(p);
            final int nameLen = b.get(p + 8) & 0xFF;
            if (nameLen == 0 || p + 9 + nameLen > end) {
                return;
            }
            final String name = new String(b.array(), p + 9, nameLen, StandardCharsets.UTF_8);
            if (!".".equals(name) && !"..".equals(name)) {
                names.add(name);
                inodes.add(new long[] {ino});
            }
            p += 9 + nameLen + (ftype ? 1 : 0) + 2 + 7 & ~7;
        }
    }

    private @NotNull ByteBuffer inode(final long ino) throws IOException {
        final int perBlockLog = inodesPerBlockLog;
        final long ag = ino >>> agBlockLog + perBlockLog;
        final long block = ino >>> perBlockLog & (1L << agBlockLog) - 1;
        final long index = ino & (1L << perBlockLog) - 1;
        final long pos = (ag * agBlocks + block) * blockSize + index * inodeSize;
        final ByteBuffer n = ByteBuffer.wrap(dev.read(pos, inodeSize));
        if ((n.getShort(0) & 0xFFFF) != INODE_MAGIC) {
            throw new IOException("XFS: damaged inode " + ino);
        }

        return n;
    }
}
