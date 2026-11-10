package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.util.Lz4;
import tcm.util.Zstd;

/**
 * ZFS pool on a single disk (or one side of a mirror) — read only. The newest uberblock, the root dataset and its child
 * datasets (as directories, where they are mounted by default); micro and fat ZAP directories, system attributes and old znodes,
 * gang and embedded block pointers, lz4, lzjb, gzip, zle and zstd compression. RAID-Z, encrypted datasets and checksums are not
 * supported (not verified).
 */
final class Zfs {

    private static final long LABEL_SIZE = 256 * 1024;
    private static final long DATA_START = 4L * 1024 * 1024;
    private static final int UBERBLOCKS = 128 * 1024;
    private static final long UB_MAGIC = 0x00BAB10CL;
    private static final int NVLIST = 16 * 1024;
    private static final int NVLIST_SIZE = 112 * 1024;
    private static final int BP = 128;
    private static final int DNODE = 512;
    private static final int COMP_INHERIT = 0;
    private static final int COMP_ON = 1;
    private static final int COMP_OFF = 2;
    private static final int COMP_LZJB = 3;
    private static final int COMP_EMPTY = 4;
    private static final int COMP_GZIP_1 = 5;
    private static final int COMP_GZIP_9 = 13;
    private static final int COMP_ZLE = 14;
    private static final int COMP_LZ4 = 15;
    private static final int COMP_ZSTD = 16;
    private static final long ZBT_MICRO = 0x8000000000000003L;
    private static final long ZBT_HEADER = 0x8000000000000001L;
    private static final long ZBT_LEAF = 0x8000000000000000L;
    private static final int ZAP_LEAF_MAGIC = 0x2AB1EAF;
    private static final int ZAP_CHUNK = 24;
    private static final int ZAP_CHUNK_ENTRY = 252;
    private static final int ZAP_CHUNK_ARRAY = 251;
    private static final int MZAP_ENTRY = 64;
    private static final int MZAP_NAME = 50;
    private static final int BONUS_ZNODE = 17;
    private static final int BONUS_SA = 44;
    private static final int SA_MAGIC = 0x2F505A;
    private static final int DNODE_FLAG_SPILL = 4;
    private static final int S_IFMT = 0170000;
    private static final int S_IFDIR = 0040000;
    private static final int S_IFREG = 0100000;
    private static final int S_IFLNK = 0120000;
    private static final long OBJECT_MASK = 0xFFFFFFFFFFFFL;
    private static final int MAX_DEPTH = 256;
    private static final int MAX_BLOCK = 16 * 1024 * 1024;
    private static final int MAX_ZAP_BLOCKS = 1 << 20;
    private static final int MAX_LINK = 4096;
    private static final int CACHE_BLOCKS = 128;

    private final @NotNull BlockSource dev;
    private final Map<String, byte[]> cache = new LinkedHashMap<>(CACHE_BLOCKS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_BLOCKS;
        }
    };

    private long fileSystems;

    private Zfs(final @NotNull BlockSource dev) {
        this.dev = dev;
    }

    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        if (dev.size() < DATA_START + LABEL_SIZE) {
            return false;
        }
        for (final long label : new long[] {0, LABEL_SIZE}) {
            final ByteBuffer ring = DiskImages.le(dev.read(label + UBERBLOCKS, 8192));
            for (int off = 0; off < 8192; off += 1024) {
                if (ring.getLong(off) == UB_MAGIC || Long.reverseBytes(ring.getLong(off)) == UB_MAGIC) {
                    return true;
                }
            }
        }

        return false;
    }

    static @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
        final Zfs fs = new Zfs(dev);
        final FsTree tree = new FsTree("ZFS");
        tree.setLabel(poolName(dev.read(NVLIST, NVLIST_SIZE)));
        IOException failure = null;
        for (final byte[] ub : fs.uberblocks()) {
            try {
                fs.pool(ub, tree);

                return tree;
            } catch (final IOException e) {
                failure = e;
            }
        }

        throw failure != null ? failure : new IOException("ZFS: no uberblock");
    }

    /** Uberblocks of the first two labels, the newest transaction first. */
    private @NotNull List<byte[]> uberblocks() throws IOException {
        final List<byte[]> result = new ArrayList<>();
        for (final long label : new long[] {0, LABEL_SIZE}) {
            final byte[] ring = dev.read(label + UBERBLOCKS, UBERBLOCKS);
            for (int off = 0; off + 1024 <= ring.length; off += 1024) {
                final ByteBuffer b = DiskImages.le(ring).position(off).slice().order(ByteOrder.LITTLE_ENDIAN);
                if (b.getLong(0) == UB_MAGIC) {
                    result.add(Arrays.copyOfRange(ring, off, off + 1024));
                } else if (Long.reverseBytes(b.getLong(0)) == UB_MAGIC) {
                    result.add(Arrays.copyOfRange(ring, off, off + 1024));
                }
            }
        }
        result.sort((x, y) -> Long.compareUnsigned(txg(y), txg(x)));

        return result;
    }

    private static long txg(final byte @NotNull [] ub) {
        final ByteBuffer b = order(ub);

        return b.getLong(16);
    }

    /** A buffer in the byte order the magic number at its start says (uberblocks). */
    private static @NotNull ByteBuffer order(final byte @NotNull [] ub) {
        final ByteBuffer b = DiskImages.le(ub);

        return b.getLong(0) == UB_MAGIC ? b : b.order(ByteOrder.BIG_ENDIAN);
    }

    private void pool(final byte @NotNull [] ub, final @NotNull FsTree tree) throws IOException {
        final ByteBuffer u = order(ub);
        final ByteBuffer mos = objset(readBp(u, 40));
        final long objectDir = zapLookup(dnode(mos, 1), "root_dataset");
        walkDataset(mos, objectDir, "", tree, 0);
    }

    /** The file system of a DSL directory, then its children as subdirectories. */
    private void walkDataset(final @NotNull ByteBuffer mos, final long dslDir, final @NotNull String path, final @NotNull FsTree tree,
        final int depth) throws IOException {
        final Dnode dir = dnode(mos, dslDir);
        final ByteBuffer dd = dir.bonus();
        final long head = dd.getLong(8);
        final long children = dd.getLong(32);
        if (head != 0) {
            final ByteBuffer ds = dnode(mos, head).bonus();
            final byte[] objsetBlock = readBp(ds, 128);
            if (objsetBlock != null) {
                walkFileSystem(objset(objsetBlock), path, tree);
            }
        }
        if (children == 0 || depth >= MAX_DEPTH) {
            return;
        }
        for (final Map.Entry<String, Long> e : zap(dnode(mos, children)).entrySet()) {
            final String p = e.getKey().startsWith("$") ? null : FsTree.child(path, e.getKey());
            if (p != null && tree.enter(Long.MIN_VALUE + e.getValue())) {
                tree.dir(p, 0, "");
                try {
                    walkDataset(mos, e.getValue(), p, tree, depth + 1);
                } catch (final IOException ex) {
                    // an encrypted or damaged child dataset stays an empty directory; the rest of the pool is still shown
                }
            }
        }
    }

    private void walkFileSystem(final @NotNull ByteBuffer objset, final @NotNull String path, final @NotNull FsTree tree) throws IOException {
        final Map<String, Long> master = zap(dnode(objset, 1));
        final Long root = master.get("ROOT");
        if (root == null) {
            return;
        }
        final Sa sa = master.containsKey("SA_ATTRS") ? sa(objset, master.get("SA_ATTRS")) : null;
        final Zpl zpl = new Zpl(objset, sa);
        walkDir(zpl, root, path, tree, 0);
    }

    private void walkDir(final @NotNull Zpl zpl, final long dir, final @NotNull String path, final @NotNull FsTree tree, final int depth)
        throws IOException {
        for (final Map.Entry<String, Long> e : zap(dnode(zpl.objset, dir)).entrySet()) {
            final String p = FsTree.child(path, e.getKey());
            if (p == null) {
                continue;
            }
            final long object = e.getValue() & OBJECT_MASK;
            final Dnode d = dnode(zpl.objset, object);
            final Attrs a = zpl.attrs(d);
            final String attrs = FileAttrs.posix(a.mode);
            switch (a.mode & S_IFMT) {
                case S_IFDIR -> {
                    tree.dir(p, a.modified, attrs);
                    if (depth < MAX_DEPTH && tree.enter(zpl.id + object)) {
                        walkDir(zpl, object, p, tree, depth + 1);
                    }
                }
                case S_IFREG -> tree.file(p, a.size, a.modified, attrs, () -> data(dnode(zpl.objset, object), a.size).stream());
                case S_IFLNK -> tree.link(p, a.modified, () -> zpl.symlink(dnode(zpl.objset, object), a));
                default -> tree.file(p, 0, a.modified, attrs, InputStream::nullInputStream);
            }
        }
    }

    private @NotNull FsData data(final @NotNull Dnode d, final long size) {
        final FsData data = new FsData(dev, size);
        final long blockSize = d.blockSize();
        for (long blk = 0; blk <= d.maxBlock && blk * blockSize < size; blk++) {
            final long id = blk;
            data.decoded(blk * blockSize, Math.min(blockSize, size - blk * blockSize), 0, () -> {
                final byte[] b = d.block(id);

                return b == null ? new byte[0] : b;
            });
        }

        return data;
    }

    // ---- objects

    /** objset_phys_t: the meta dnode at its start. */
    private @NotNull ByteBuffer objset(final byte @Nullable [] block) throws IOException {
        if (block == null || block.length < DNODE) {
            throw new IOException("ZFS: damaged object set");
        }

        return DiskImages.le(block);
    }

    private @NotNull Dnode dnode(final @NotNull ByteBuffer objset, final long object) throws IOException {
        final Dnode meta = new Dnode(objset, 0);
        final long blockSize = meta.blockSize();
        final long pos = object * DNODE;
        final byte[] block = meta.block(pos / blockSize);
        if (block == null) {
            throw new IOException("ZFS: missing object " + object);
        }
        final int off = (int) (pos % blockSize);
        if (off + DNODE > block.length) {
            throw new IOException("ZFS: damaged object " + object);
        }
        final int slots = 1 + (block[off + 12] & 0xFF);
        final int length = Math.min(slots * DNODE, block.length - off);

        return new Dnode(DiskImages.le(Arrays.copyOfRange(block, off, off + length)), 0);
    }

    /** dnode_phys_t. */
    private final class Dnode {

        private final @NotNull ByteBuffer d;
        private final int base;
        private final int levels;
        private final int pointers;
        private final int indirectShift;
        private final long maxBlock;

        private Dnode(final @NotNull ByteBuffer d, final int base) {
            this.d = d;
            this.base = base;
            this.indirectShift = d.get(base + 1) & 0xFF;
            this.levels = d.get(base + 2) & 0xFF;
            this.pointers = d.get(base + 3) & 0xFF;
            this.maxBlock = d.getLong(base + 16);
        }

        private long blockSize() {
            return (d.getShort(base + 8) & 0xFFFFL) * 512;
        }

        private int bonusType() {
            return d.get(base + 4) & 0xFF;
        }

        private @NotNull ByteBuffer bonus() {
            final int off = base + 64 + pointers * BP;
            final int length = Math.min(d.getShort(base + 10) & 0xFFFF, d.limit() - off);

            return DiskImages.le(Arrays.copyOfRange(d.array(), off, off + Math.max(0, length)));
        }

        private byte @Nullable [] spill() throws IOException {
            if ((d.get(base + 7) & DNODE_FLAG_SPILL) == 0 || d.limit() < BP) {
                return null;
            }

            return readBp(d, d.limit() - BP);
        }

        /** The level-0 block; null — a hole. */
        private byte @Nullable [] block(final long id) throws IOException {
            if (id > maxBlock || pointers == 0 || levels == 0) {
                return null;
            }
            final int epbs = indirectShift - 7;
            if (epbs <= 0 || (long) epbs * (levels - 1) >= 63) {
                throw new IOException("ZFS: damaged dnode");
            }
            final long top = id >>> epbs * (levels - 1);
            if (top >= pointers) {
                return null;
            }
            ByteBuffer holder = d;
            int at = base + 64 + (int) top * BP;
            for (int level = levels - 1; level > 0; level--) {
                final byte[] indirect = readBp(holder, at);
                if (indirect == null) {
                    return null;
                }
                holder = order(indirect, holder, at);
                at = (int) (id >>> epbs * (level - 1) & (1L << epbs) - 1) * BP;
                if (at + BP > indirect.length) {
                    return null;
                }
            }

            return readBp(holder, at);
        }
    }

    // ---- block pointers

    /** A block in the byte order of the pointer that read it. */
    private static @NotNull ByteBuffer order(final byte @NotNull [] block, final @NotNull ByteBuffer holder, final int at) {
        final boolean little = holder.getLong(at + 48) < 0;

        return ByteBuffer.wrap(block).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    }

    /** The logical content of the block pointer at the offset; null — a hole. */
    private byte @Nullable [] readBp(final @NotNull ByteBuffer holder, final int at) throws IOException {
        final ByteBuffer bp = ByteBuffer.wrap(Arrays.copyOfRange(holder.array(), holder.arrayOffset() + at,
            holder.arrayOffset() + at + BP)).order(holder.order());
        final long prop = bp.getLong(48);
        final int comp = (int) (prop >>> 32 & 0x7F);
        if ((prop >>> 39 & 1) != 0) {
            final int lsize = (int) (prop & 0x1FFFFFF) + 1;
            final int psize = (int) (prop >>> 25 & 0x7F) + 1;

            return decompress(comp, embedded(bp, psize), lsize);
        }
        final int lsize = (int) ((prop & 0xFFFF) + 1) << 9;
        final int psize = (int) ((prop >>> 16 & 0xFFFF) + 1) << 9;
        final String key = Long.toHexString(bp.getLong(8)) + ":" + Long.toHexString(prop) + ":" + Long.toHexString(bp.getLong(80));
        synchronized (cache) {
            final byte[] cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        IOException failure = null;
        for (int dva = 0; dva < 3; dva++) {
            final long w0 = bp.getLong(dva * 16);
            final long w1 = bp.getLong(dva * 16 + 8);
            if ((w0 & 0xFFFFFF) == 0 && w1 == 0) {
                continue;
            }
            try {
                final byte[] raw = w1 < 0 ? gang(bp.order(), (w1 & Long.MAX_VALUE) << 9, psize) : read((w1 & Long.MAX_VALUE) << 9, psize);
                final byte[] block = decompress(comp, raw, lsize);
                synchronized (cache) {
                    cache.put(key, block);
                }

                return block;
            } catch (final IOException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }

        return null;
    }

    private byte @NotNull [] read(final long offset, final int size) throws IOException {
        if (size > MAX_BLOCK) {
            throw new IOException("ZFS: block too large");
        }

        return dev.read(DATA_START + offset, size);
    }

    /** A gang block: a header with three pointers whose contents make up the data. */
    private byte @NotNull [] gang(final @NotNull ByteOrder order, final long offset, final int psize) throws IOException {
        final ByteBuffer header = ByteBuffer.wrap(read(offset, 512)).order(order);
        final byte[] out = new byte[psize];
        int n = 0;
        for (int i = 0; i < 3 && n < psize; i++) {
            final byte[] part = readBp(header, i * BP);
            if (part != null) {
                final int len = Math.min(part.length, psize - n);
                System.arraycopy(part, 0, out, n, len);
                n += len;
            }
        }

        return out;
    }

    /** The payload of an embedded block pointer: all its words but the properties (6) and the birth (10). */
    private static byte @NotNull [] embedded(final @NotNull ByteBuffer bp, final int psize) {
        final byte[] out = new byte[psize];
        int word = 0;
        long w = 0;
        for (int i = 0; i < psize; i++) {
            if (i % 8 == 0) {
                if (word == 6 || word == 10) {
                    word++;
                }
                w = bp.getLong(word * 8);
                word++;
            }
            out[i] = (byte) (w >>> 8 * (i % 8));
        }

        return out;
    }

    private static byte @NotNull [] decompress(final int comp, final byte @NotNull [] in, final int lsize) throws IOException {
        if (comp == COMP_OFF || comp == COMP_INHERIT || comp == COMP_ON && in.length == lsize) {
            return in.length == lsize ? in : Arrays.copyOf(in, lsize);
        }
        if (comp == COMP_LZ4) {
            final int length = ByteBuffer.wrap(in).getInt(0);
            if (length < 0 || length + 4 > in.length) {
                throw new IOException("ZFS: damaged lz4 block");
            }

            return pad(Lz4.decompress(in, 4, length, lsize), lsize);
        }
        if (comp == COMP_LZJB || comp == COMP_ON) {
            return lzjb(in, lsize);
        }
        if (comp == COMP_EMPTY) {
            return new byte[lsize];
        }
        if (comp >= COMP_GZIP_1 && comp <= COMP_GZIP_9) {
            return inflate(in, lsize);
        }
        if (comp == COMP_ZLE) {
            return zle(in, lsize);
        }
        if (comp == COMP_ZSTD) {
            final int length = ByteBuffer.wrap(in).getInt(0);
            if (length < 0 || length + 8 > in.length) {
                throw new IOException("ZFS: damaged zstd block");
            }

            return pad(Zstd.decompress(Arrays.copyOfRange(in, 8, 8 + length), lsize), lsize);
        }

        throw new IOException("ZFS: unsupported compression " + comp);
    }

    private static byte @NotNull [] pad(final byte @NotNull [] b, final int size) {
        return b.length == size ? b : Arrays.copyOf(b, size);
    }

    private static byte @NotNull [] lzjb(final byte @NotNull [] src, final int size) throws IOException {
        final byte[] dst = new byte[size];
        int s = 0;
        int d = 0;
        int copymap = 0;
        int copymask = 1 << 7;
        while (d < size) {
            copymask <<= 1;
            if (copymask == 1 << 8) {
                copymask = 1;
                if (s >= src.length) {
                    break;
                }
                copymap = src[s++] & 0xFF;
            }
            if ((copymap & copymask) != 0) {
                if (s + 1 >= src.length) {
                    break;
                }
                int length = ((src[s] & 0xFF) >> 2) + 3;
                final int offset = ((src[s] & 0xFF) << 8 | src[s + 1] & 0xFF) & 0x3FF;
                s += 2;
                int from = d - offset;
                if (from < 0 || offset == 0) {
                    throw new IOException("ZFS: damaged lzjb block");
                }
                while (length-- > 0 && d < size) {
                    dst[d++] = dst[from++];
                }
            } else {
                if (s >= src.length) {
                    break;
                }
                dst[d++] = src[s++];
            }
        }

        return dst;
    }

    private static byte @NotNull [] zle(final byte @NotNull [] src, final int size) {
        final byte[] dst = new byte[size];
        int s = 0;
        int d = 0;
        while (s < src.length && d < size) {
            int length = 1 + (src[s++] & 0xFF);
            if (length <= 64) {
                while (length-- > 0 && s < src.length && d < size) {
                    dst[d++] = src[s++];
                }
            } else {
                d = Math.min(size, d + length - 64);
            }
        }

        return dst;
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

            return out;
        } catch (final DataFormatException e) {
            throw new IOException("ZFS: damaged gzip block", e);
        } finally {
            inflater.end();
        }
    }

    // ---- ZAP

    private long zapLookup(final @NotNull Dnode d, final @NotNull String name) throws IOException {
        final Long v = zap(d).get(name);
        if (v == null) {
            throw new IOException("ZFS: no " + name);
        }

        return v;
    }

    /** All name → first integer pairs of a ZAP object. */
    private @NotNull Map<String, Long> zap(final @NotNull Dnode d) throws IOException {
        final Map<String, Long> result = new LinkedHashMap<>();
        for (final Map.Entry<String, long[]> e : zapArrays(d).entrySet()) {
            if (e.getValue().length > 0) {
                result.put(e.getKey(), e.getValue()[0]);
            }
        }

        return result;
    }

    /** All entries of a ZAP object with their integer arrays (microzap values are single 8-byte integers). */
    private @NotNull Map<String, long[]> zapArrays(final @NotNull Dnode d) throws IOException {
        final Map<String, long[]> result = new LinkedHashMap<>();
        final byte[] first = d.block(0);
        if (first == null) {
            return result;
        }
        final ByteBuffer b0 = ByteBuffer.wrap(first).order(d.d.order());
        final long type = b0.getLong(0);
        if (type == ZBT_MICRO) {
            for (int off = MZAP_ENTRY; off + MZAP_ENTRY <= first.length; off += MZAP_ENTRY) {
                if (first[off + 14] == 0) {
                    continue;
                }
                int len = 0;
                while (len < MZAP_NAME - 1 && first[off + 14 + len] != 0) {
                    len++;
                }
                result.put(new String(first, off + 14, len, StandardCharsets.UTF_8), new long[] {b0.getLong(off)});
            }
        } else if (type == ZBT_HEADER) {
            for (long blk = 1; blk <= d.maxBlock && blk < MAX_ZAP_BLOCKS; blk++) {
                final byte[] leaf = d.block(blk);
                if (leaf != null) {
                    leaf(ByteBuffer.wrap(leaf).order(d.d.order()), result);
                }
            }
        }

        return result;
    }

    private static void leaf(final @NotNull ByteBuffer b, final @NotNull Map<String, long[]> out) {
        if (b.getLong(0) != ZBT_LEAF || b.getInt(24) != ZAP_LEAF_MAGIC) {
            return;
        }
        final int shift = Integer.numberOfTrailingZeros(b.limit());
        final int hashEntries = 1 << shift - 5;
        final int chunksStart = 48 + hashEntries * 2;
        final int chunks = (b.limit() - 2 * hashEntries) / ZAP_CHUNK - 2;
        for (int i = 0; i < chunks; i++) {
            final int c = chunksStart + i * ZAP_CHUNK;
            if ((b.get(c) & 0xFF) != ZAP_CHUNK_ENTRY) {
                continue;
            }
            final int intLength = b.get(c + 1) & 0xFF;
            final int nameChunk = b.getShort(c + 4) & 0xFFFF;
            final int nameLength = b.getShort(c + 6) & 0xFFFF;
            final int valueChunk = b.getShort(c + 8) & 0xFFFF;
            final int valueCount = b.getShort(c + 10) & 0xFFFF;
            final byte[] name = array(b, chunksStart, chunks, nameChunk, nameLength);
            final byte[] value = array(b, chunksStart, chunks, valueChunk, valueCount * intLength);
            if (name == null || value == null || intLength == 0 || intLength > 8) {
                continue;
            }
            final int n = name.length > 0 && name[name.length - 1] == 0 ? name.length - 1 : name.length;
            final long[] values = new long[valueCount];
            for (int k = 0; k < valueCount; k++) {
                long v = 0;
                for (int j = 0; j < intLength; j++) {
                    v = v << 8 | value[k * intLength + j] & 0xFF;
                }
                values[k] = v;
            }
            out.put(new String(name, 0, n, StandardCharsets.UTF_8), values);
        }
    }

    /** Bytes of a chain of array chunks (21 bytes each). */
    private static byte @Nullable [] array(final @NotNull ByteBuffer b, final int start, final int chunks, final int first, final int length) {
        final byte[] out = new byte[length];
        int chunk = first;
        int n = 0;
        int steps = 0;
        while (n < length) {
            if (chunk >= chunks || steps++ > chunks) {
                return null;
            }
            final int c = start + chunk * ZAP_CHUNK;
            if ((b.get(c) & 0xFF) != ZAP_CHUNK_ARRAY) {
                return null;
            }
            final int k = Math.min(21, length - n);
            b.get(c + 1, out, n, k);
            n += k;
            chunk = b.getShort(c + 22) & 0xFFFF;
        }

        return out;
    }

    // ---- ZPL attributes

    /** The system attribute registry (name → number and length) and layouts (number → attribute numbers). */
    private @NotNull Sa sa(final @NotNull ByteBuffer objset, final long master) throws IOException {
        final Map<String, Long> m = zap(dnode(objset, master));
        final Sa sa = new Sa();
        final Long registry = m.get("REGISTRY");
        final Long layouts = m.get("LAYOUTS");
        if (registry != null) {
            for (final Map.Entry<String, Long> e : zap(dnode(objset, registry)).entrySet()) {
                final long v = e.getValue();
                sa.numbers.put(e.getKey(), (int) (v & 0xFFFF));
                sa.lengths.put((int) (v & 0xFFFF), (int) (v >>> 24 & 0xFFFF));
            }
        }
        if (layouts != null) {
            for (final Map.Entry<String, long[]> e : zapArrays(dnode(objset, layouts)).entrySet()) {
                try {
                    sa.layouts.put(Integer.parseInt(e.getKey()), e.getValue());
                } catch (final NumberFormatException ex) {
                    // not a layout entry
                }
            }
        }

        return sa;
    }

    private static final class Sa {

        private final Map<String, Integer> numbers = new HashMap<>();
        private final Map<Integer, Integer> lengths = new HashMap<>();
        private final Map<Integer, long[]> layouts = new HashMap<>();
    }

    private static final class Attrs {

        private int mode;
        private long size;
        private long modified;
        private byte @Nullable [] symlink;
    }

    /** One file system: its object set and attribute layouts. */
    private final class Zpl {

        private final @NotNull ByteBuffer objset;
        private final @Nullable Sa sa;
        /** Distinguishes the objects of this file system from those of the other datasets (FsTree.enter). */
        private final long id;

        private Zpl(final @NotNull ByteBuffer objset, final @Nullable Sa sa) {
            this.objset = objset;
            this.sa = sa;
            this.id = ++fileSystems << 48;
        }

        private @NotNull Attrs attrs(final @NotNull Dnode d) throws IOException {
            final Attrs a = new Attrs();
            final ByteBuffer bonus = d.bonus();
            if (d.bonusType() == BONUS_ZNODE && bonus.limit() >= 152) {
                a.modified = bonus.getLong(16) * 1000 + bonus.getLong(24) / 1_000_000;
                a.mode = (int) bonus.getLong(72);
                a.size = bonus.getLong(80);
                if (bonus.limit() > 264) {
                    a.symlink = Arrays.copyOfRange(bonus.array(), 264, bonus.limit());
                }
            } else if (d.bonusType() == BONUS_SA && sa != null) {
                saAttrs(bonus, a);
                if (a.mode == 0 || (a.mode & S_IFMT) == S_IFLNK && a.symlink == null) {
                    final byte[] spill = d.spill();
                    if (spill != null) {
                        saAttrs(ByteBuffer.wrap(spill).order(bonus.order()), a);
                    }
                }
            }

            return a;
        }

        private void saAttrs(final @NotNull ByteBuffer raw, final @NotNull Attrs a) {
            if (raw.limit() < 8) {
                return;
            }
            final ByteBuffer b = raw.getInt(0) == SA_MAGIC ? raw : raw.duplicate().order(raw.order() == ByteOrder.LITTLE_ENDIAN
                ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != SA_MAGIC) {
                return;
            }
            final int info = b.getShort(4) & 0xFFFF;
            final int headerSize = (info >>> 10) * 8;
            final long[] layout = sa.layouts.get(info & 0x3FF);
            if (layout == null || headerSize < 8) {
                return;
            }
            int off = headerSize;
            int variable = 0;
            for (final long attr : layout) {
                int length = sa.lengths.getOrDefault((int) attr, 0);
                if (length == 0) {
                    length = b.getShort(6 + variable * 2) & 0xFFFF;
                    variable++;
                }
                if (off + length > b.limit()) {
                    return;
                }
                attribute((int) attr, b, off, length, a);
                off += length + 7 & ~7;
            }
        }

        private void attribute(final int attr, final @NotNull ByteBuffer b, final int off, final int length, final @NotNull Attrs a) {
            if (is(attr, "ZPL_MODE") && length >= 8) {
                a.mode = (int) b.getLong(off);
            } else if (is(attr, "ZPL_SIZE") && length >= 8) {
                a.size = b.getLong(off);
            } else if (is(attr, "ZPL_MTIME") && length >= 16) {
                a.modified = b.getLong(off) * 1000 + b.getLong(off + 8) / 1_000_000;
            } else if (is(attr, "ZPL_SYMLINK")) {
                a.symlink = Arrays.copyOfRange(b.array(), b.arrayOffset() + off, b.arrayOffset() + off + length);
            }
        }

        private boolean is(final int attr, final @NotNull String name) {
            final Integer n = sa.numbers.get(name);

            return n != null && n == attr;
        }

        private @NotNull String symlink(final @NotNull Dnode d, final @NotNull Attrs a) throws IOException {
            final int size = (int) Math.min(a.size, MAX_LINK);
            if (a.symlink != null) {
                return new String(a.symlink, 0, Math.min(size, a.symlink.length), StandardCharsets.UTF_8);
            }

            return new String(data(d, size).readAll(size), StandardCharsets.UTF_8);
        }
    }

    // ---- label

    /** The "name" string of the pool configuration (an XDR-encoded nvlist). */
    private static @NotNull String poolName(final byte @NotNull [] nv) {
        final ByteBuffer b = ByteBuffer.wrap(nv);
        if (nv.length < 12 || nv[0] != 1) {
            return "";
        }
        int p = 12;
        while (p + 8 <= nv.length) {
            final int encoded = b.getInt(p);
            if (encoded <= 0 || p + encoded > nv.length) {
                return "";
            }
            final int nameLength = b.getInt(p + 8);
            if (nameLength < 0 || p + 12 + nameLength > nv.length) {
                return "";
            }
            final String name = new String(nv, p + 12, nameLength, StandardCharsets.UTF_8);
            final int typeAt = p + 12 + (nameLength + 3 & ~3);
            if ("name".equals(name) && typeAt + 12 <= nv.length && b.getInt(typeAt) == 9) {
                final int length = b.getInt(typeAt + 8);
                if (length >= 0 && typeAt + 12 + length <= nv.length) {
                    return new String(nv, typeAt + 12, length, StandardCharsets.UTF_8);
                }
            }
            p += encoded;
        }

        return "";
    }
}
