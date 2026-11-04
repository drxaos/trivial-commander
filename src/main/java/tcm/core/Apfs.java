package tcm.core;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only APFS container (as found in a .dmg): the latest checkpoint superblock, the object maps, and each unencrypted
 * volume's file system tree, read in full at open. File data comes from extents; files compressed by the system (decmpfs)
 * are supported with zlib, the most common method; LZVN/LZFSE ones fail on read. Encrypted volumes are refused.
 * A symbolic link is shown as the file it points to inside the volume (reading it reads that file), any other link —
 * as an empty file. With several volumes each one becomes a top-level directory named after the volume.
 */
final class Apfs {

    private static final int MIN_BLOCK = 4096;
    private static final int MAX_BLOCK = 65536;
    private static final int OBJ_HEADER = 32;
    private static final int TYPE_NX_SUPERBLOCK = 1;
    private static final int TYPE_OMAP = 0x0B;
    private static final int MAX_CHECKPOINT_BLOCKS = 65536;
    private static final int MAX_VOLUMES = 100;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_LINKS = 16;
    private static final int BTREE_INFO = 40;
    private static final int BTNODE_ROOT = 1;
    private static final int BTNODE_FIXED = 4;
    private static final int BTREE_PHYSICAL = 0x10;
    private static final int OMAP_DELETED = 1;
    private static final long OID_MASK = 0x0FFFFFFFFFFFFFFFL;
    private static final int TYPE_SHIFT = 60;
    private static final int J_INODE = 3;
    private static final int J_XATTR = 4;
    private static final int J_EXTENT = 8;
    private static final int J_DREC = 9;
    private static final long ROOT_DIR = 2;
    private static final int S_IFMT = 0170000;
    private static final int S_IFDIR = 0040000;
    private static final int S_IFREG = 0100000;
    private static final int S_IFLNK = 0120000;
    private static final int UF_COMPRESSED = 0x20;
    private static final int INO_EXT_DSTREAM = 8;
    private static final int INODE_XFIELDS = 92;
    private static final long FS_UNENCRYPTED = 1;
    private static final int XATTR_STREAM = 1;
    private static final int XATTR_EMBEDDED = 2;
    private static final String DECMPFS = "com.apple.decmpfs";
    private static final String RESOURCE_FORK = "com.apple.ResourceFork";
    private static final String SYMLINK = "com.apple.fs.symlink";
    private static final int DECMPFS_MAGIC = 0x636D7066;
    private static final int DECMPFS_HEADER = 16;
    private static final int CMP_UNCOMPRESSED_XATTR = 1;
    private static final int CMP_ZLIB_XATTR = 3;
    private static final int CMP_ZLIB_RSRC = 4;
    private static final int CMP_BLOCK = 64 * 1024;
    private static final int MAX_XATTR = 64 * 1024 * 1024;
    private static final long NANOS_PER_MILLI = 1_000_000;

    /** One entry of {@link #walk}. */
    interface Visitor {

        void entry(@NotNull String path, boolean directory, long size, long modified, int mode) throws IOException;
    }

    private final @NotNull BlockSource dev;
    private final int blockSize;
    private final @NotNull List<Volume> volumes = new ArrayList<>();

    private Apfs(final @NotNull BlockSource dev, final int blockSize) {
        this.dev = dev;
        this.blockSize = blockSize;
    }

    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        return dev.size() >= MIN_BLOCK && new String(dev.read(OBJ_HEADER, 4), StandardCharsets.ISO_8859_1).equals("NXSB");
    }

    static @NotNull Apfs open(final @NotNull BlockSource dev) throws IOException {
        if (!matches(dev)) {
            throw new IOException("Not an APFS container");
        }
        final ByteBuffer first = le(dev.read(0, MIN_BLOCK));
        final int blockSize = first.getInt(36);
        if (blockSize < MIN_BLOCK || blockSize > MAX_BLOCK || Integer.bitCount(blockSize) != 1) {
            throw new IOException("APFS: bad block size " + blockSize);
        }
        final Apfs fs = new Apfs(dev, blockSize);
        final ByteBuffer sb = fs.latestSuperblock(le(dev.read(0, blockSize)));
        final long xid = sb.getLong(16);
        final Map<Long, Long> omap = fs.omap(sb.getLong(160), xid);
        final int maxVolumes = Math.min(sb.getInt(180), MAX_VOLUMES);
        IOException refused = null;
        for (int i = 0; i < maxVolumes; i++) {
            final long oid = sb.getLong(184 + 8 * i);
            final Long paddr = oid == 0 ? null : omap.get(oid);
            if (paddr == null) {
                continue;
            }
            try {
                fs.volumes.add(fs.volume(fs.block(paddr), xid));
            } catch (final IOException e) {
                refused = e;
            }
        }
        if (fs.volumes.isEmpty()) {
            throw refused != null ? refused : new IOException("APFS container without volumes");
        }

        return fs;
    }

    /** All entries: directories before their contents. */
    void walk(final @NotNull Visitor visitor) throws IOException {
        final Set<String> used = new HashSet<>();
        for (final Volume v : volumes) {
            if (volumes.size() > 1) {
                String name = v.name.isEmpty() || v.name.contains("/") ? "Volume " + (volumes.indexOf(v) + 1) : v.name;
                while (!used.add(name)) {
                    name = name + "_";
                }
                v.prefix = name + "/";
                final Inode root = v.inodes.get(ROOT_DIR);
                visitor.entry(name, true, 0, root == null ? 0 : root.modified, root == null ? 0 : root.mode);
            }
            v.walk(ROOT_DIR, v.prefix, visitor, 0);
        }
    }

    /** Stream of the file at the path (links followed); null — no such file. */
    @Nullable InputStream open(final @NotNull String path) throws IOException {
        for (final Volume v : volumes) {
            if (path.startsWith(v.prefix)) {
                final Inode n = v.resolve(path.substring(v.prefix.length()), 0);
                if (n == null) {
                    return v.isLink(path.substring(v.prefix.length())) ? InputStream.nullInputStream() : null;
                }

                return n.isFile() ? v.data(n) : InputStream.nullInputStream();
            }
        }

        return null;
    }

    private @NotNull ByteBuffer latestSuperblock(final @NotNull ByteBuffer block0) throws IOException {
        ByteBuffer best = block0;
        final int descBlocks = block0.getInt(104);
        final long descBase = block0.getLong(112);
        // the top bit marks a checkpoint area described by a B-tree — rare; block 0 is then used as is
        if (descBlocks > 0 && descBlocks <= MAX_CHECKPOINT_BLOCKS) {
            for (int i = 0; i < descBlocks; i++) {
                final ByteBuffer b;
                try {
                    b = block(descBase + i);
                } catch (final EOFException e) {
                    break;
                }
                if ((b.getInt(24) & 0xFFFF) == TYPE_NX_SUPERBLOCK && b.getInt(OBJ_HEADER) == block0.getInt(OBJ_HEADER) && checksumOk(b)
                    && Long.compareUnsigned(b.getLong(16), best.getLong(16)) > 0) {
                    best = b;
                }
            }
        }

        return best;
    }

    /** Fletcher-64 over the object after its checksum field. */
    private static boolean checksumOk(final @NotNull ByteBuffer b) {
        final long mod = 0xFFFFFFFFL;
        long sum1 = 0;
        long sum2 = 0;
        for (int i = 8; i + 4 <= b.limit(); i += 4) {
            sum1 = (sum1 + Integer.toUnsignedLong(b.getInt(i))) % mod;
            sum2 = (sum2 + sum1) % mod;
        }
        final long c1 = mod - (sum1 + sum2) % mod;
        final long c2 = mod - (sum1 + c1) % mod;

        return (c2 << 32 | c1) == b.getLong(0);
    }

    private @NotNull ByteBuffer block(final long paddr) throws IOException {
        if (paddr <= 0 || paddr > (dev.size() - blockSize) / blockSize) {
            throw new EOFException("APFS: block " + paddr + " outside the container");
        }

        return le(dev.read(paddr * blockSize, blockSize));
    }

    /** Object map: virtual oid → physical block, the newest version not later than xid. */
    private @NotNull Map<Long, Long> omap(final long omapOid, final long xid) throws IOException {
        final ByteBuffer om = block(omapOid);
        if ((om.getInt(24) & 0xFFFF) != TYPE_OMAP) {
            throw new IOException("APFS: damaged object map");
        }
        final Map<Long, long[]> best = new HashMap<>();
        scan(om.getLong(48), null, (node, k, kl, v, vl) -> {
            final long oid = node.getLong(k);
            final long ver = node.getLong(k + 8);
            if (Long.compareUnsigned(ver, xid) > 0) {
                return;
            }
            final long[] old = best.get(oid);
            if (old == null || Long.compareUnsigned(ver, old[0]) > 0) {
                best.put(oid, new long[] {ver, (node.getInt(v) & OMAP_DELETED) != 0 ? 0 : node.getLong(v + 8)});
            }
        });
        final Map<Long, Long> result = new HashMap<>();
        best.forEach((oid, e) -> {
            if (e[1] != 0) {
                result.put(oid, e[1]);
            }
        });

        return result;
    }

    @FunctionalInterface
    private interface Records {

        void record(@NotNull ByteBuffer node, int key, int keyLength, int value, int valueLength) throws IOException;
    }

    /** Visits the leaf records of a B-tree in key order; virtual child nodes are mapped through omap (null — a physical tree). */
    private void scan(final long root, final @Nullable Map<Long, Long> omap, final @NotNull Records records) throws IOException {
        scan(block(root), omap, records, new HashSet<>(), 0);
    }

    private void scan(
        final @NotNull ByteBuffer node,
        final @Nullable Map<Long, Long> omap,
        final @NotNull Records records,
        final @NotNull Set<Long> seen,
        final int depth
    ) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("APFS: B-tree too deep");
        }
        final int flags = node.getShort(32) & 0xFFFF;
        final int level = node.getShort(34) & 0xFFFF;
        final int count = node.getInt(36);
        final int tocOffset = node.getShort(40) & 0xFFFF;
        final int tocLength = node.getShort(42) & 0xFFFF;
        final int toc = 56 + tocOffset;
        final int keys = toc + tocLength;
        final int values = blockSize - ((flags & BTNODE_ROOT) != 0 ? BTREE_INFO : 0);
        final boolean fixed = (flags & BTNODE_FIXED) != 0;
        final int entrySize = fixed ? 4 : 8;
        if (count < 0 || (long) count * entrySize > tocLength || keys > blockSize) {
            throw new IOException("APFS: damaged B-tree node");
        }
        for (int i = 0; i < count; i++) {
            final int e = toc + i * entrySize;
            final int k = keys + (node.getShort(e) & 0xFFFF);
            final int kl = fixed ? 16 : node.getShort(e + 2) & 0xFFFF;
            final int vOff = node.getShort(e + (fixed ? 2 : 4)) & 0xFFFF;
            final int vl = fixed ? level > 0 ? 8 : 16 : node.getShort(e + 6) & 0xFFFF;
            if (vOff == 0xFFFF) {
                continue;
            }
            final int v = values - vOff;
            if (k + kl > blockSize || v < keys || v + vl > blockSize) {
                throw new IOException("APFS: damaged B-tree record");
            }
            if (level == 0) {
                records.record(node, k, kl, v, vl);
                continue;
            }
            final long child = node.getLong(v);
            final Long paddr = omap == null ? Long.valueOf(child) : omap.get(child);
            if (paddr == null || !seen.add(paddr)) {
                throw new IOException("APFS: damaged B-tree link");
            }
            scan(block(paddr), omap, records, seen, depth + 1);
        }
    }

    private @NotNull Volume volume(final @NotNull ByteBuffer sb, final long xid) throws IOException {
        if (!new String(new byte[] {sb.get(32), sb.get(33), sb.get(34), sb.get(35)}, StandardCharsets.ISO_8859_1).equals("APSB")) {
            throw new IOException("APFS: damaged volume superblock");
        }
        final String name = cString(sb, 704, 256);
        if ((sb.getLong(264) & FS_UNENCRYPTED) == 0) {
            throw new IOException("Encrypted APFS volume \"" + name + "\" is not supported");
        }
        final Volume v = new Volume(name);
        v.omap = omap(sb.getLong(128), xid);
        final Long rootPaddr = v.omap.get(sb.getLong(136));
        if (rootPaddr == null) {
            throw new IOException("APFS: volume \"" + name + "\" has no file system tree");
        }
        final ByteBuffer root = block(rootPaddr);
        final boolean physical = (root.getInt(blockSize - BTREE_INFO) & BTREE_PHYSICAL) != 0;
        scan(root, physical ? null : v.omap, v::record, new HashSet<>(), 0);

        return v;
    }

    private static @NotNull String cString(final @NotNull ByteBuffer b, final int pos, final int max) {
        int end = pos;
        while (end < pos + max && end < b.limit() && b.get(end) != 0) {
            end++;
        }
        final byte[] s = new byte[end - pos];
        b.get(pos, s);

        return new String(s, StandardCharsets.UTF_8);
    }

    private static @NotNull ByteBuffer le(final byte @NotNull [] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private final class Volume {

        private final @NotNull String name;
        private @NotNull Map<Long, Long> omap = Map.of();
        private final Map<Long, Inode> inodes = new HashMap<>();
        private final Map<Long, List<Drec>> children = new HashMap<>();
        private final Map<Long, List<long[]>> extents = new HashMap<>();
        private final Map<Long, Map<String, Xattr>> xattrs = new HashMap<>();
        private @NotNull String prefix = "";

        private Volume(final @NotNull String name) {
            this.name = name;
        }

        private void record(final @NotNull ByteBuffer node, final int k, final int kl, final int v, final int vl) {
            if (kl < 8) {
                return;
            }
            final long header = node.getLong(k);
            final long oid = header & OID_MASK;
            switch ((int) (header >>> TYPE_SHIFT)) {
                case J_INODE -> inode(node, oid, v, vl);
                case J_DREC -> drec(node, oid, k, kl, v, vl);
                case J_EXTENT -> {
                    if (kl >= 16 && vl >= 16) {
                        extents.computeIfAbsent(oid, x -> new ArrayList<>())
                            .add(new long[] {node.getLong(k + 8), node.getLong(v) & 0x00FFFFFFFFFFFFFFL, node.getLong(v + 8)});
                    }
                }
                case J_XATTR -> xattr(node, oid, k, kl, v, vl);
                default -> {
                    // snapshots, sibling links, data stream references, crypto state: not needed for reading files
                }
            }
        }

        private void inode(final @NotNull ByteBuffer node, final long oid, final int v, final int vl) {
            if (vl < INODE_XFIELDS) {
                return;
            }
            final Inode n = new Inode(oid, node.getLong(v + 8), node.getLong(v + 24) / NANOS_PER_MILLI, node.getInt(v + 68),
                node.getShort(v + 80) & 0xFFFF);
            // extended fields: count, used bytes, then {type, flags, size} headers, then the data, each padded to 8 bytes
            if (vl >= INODE_XFIELDS + 4) {
                final int x = v + INODE_XFIELDS;
                final int fields = node.getShort(x) & 0xFFFF;
                int data = x + 4 + fields * 4;
                for (int i = 0; i < fields && x + 8 + i * 4 <= v + vl; i++) {
                    final int type = node.get(x + 4 + i * 4) & 0xFF;
                    final int size = node.getShort(x + 6 + i * 4) & 0xFFFF;
                    if (type == INO_EXT_DSTREAM && data + 8 <= v + vl) {
                        n.size = node.getLong(data);
                    }
                    data += (size + 7) & ~7;
                }
            }
            inodes.put(oid, n);
        }

        private void drec(final @NotNull ByteBuffer node, final long parent, final int k, final int kl, final int v, final int vl) {
            if (vl < 18 || kl < 10) {
                return;
            }
            // hashed key (case/normalization-insensitive volumes): u32 with the length in the low 10 bits and the name hash;
            // plain key: u16 length; both count the terminating zero, so the key length tells them apart
            final boolean hashed = kl >= 12 && kl == 12 + (node.getInt(k + 8) & 0x3FF);
            final int nameStart = k + (hashed ? 12 : 10);
            final String name = cString(node, nameStart, k + kl - nameStart);
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")) {
                return;
            }
            children.computeIfAbsent(parent, x -> new ArrayList<>()).add(new Drec(name, node.getLong(v)));
        }

        private void xattr(final @NotNull ByteBuffer node, final long oid, final int k, final int kl, final int v, final int vl) {
            if (kl < 10 || vl < 4) {
                return;
            }
            final String name = cString(node, k + 10, kl - 10);
            if (!name.equals(DECMPFS) && !name.equals(RESOURCE_FORK) && !name.equals(SYMLINK)) {
                return;
            }
            final int flags = node.getShort(v) & 0xFFFF;
            final int length = Math.min(node.getShort(v + 2) & 0xFFFF, vl - 4);
            final Xattr x = new Xattr();
            if ((flags & XATTR_EMBEDDED) != 0) {
                x.data = new byte[length];
                node.get(v + 4, x.data);
            } else if ((flags & XATTR_STREAM) != 0 && length >= 16) {
                x.stream = node.getLong(v + 4);
                x.size = node.getLong(v + 12);
            } else {
                return;
            }
            xattrs.computeIfAbsent(oid, y -> new HashMap<>()).put(name, x);
        }

        private void walk(final long dir, final @NotNull String base, final @NotNull Visitor visitor, final int depth) throws IOException {
            if (depth > 256) {
                return;
            }
            for (final Drec d : children.getOrDefault(dir, List.of())) {
                final Inode n = inodes.get(d.id);
                if (n == null) {
                    continue;
                }
                final String path = base + d.name;
                if (n.isDir()) {
                    visitor.entry(path, true, 0, n.modified, n.mode);
                    walk(d.id, path + "/", visitor, depth + 1);
                } else if (n.isFile()) {
                    visitor.entry(path, false, size(n), n.modified, n.mode);
                } else if (n.isLink()) {
                    final Inode target = resolve(path.substring(prefix.length()), 0);
                    final boolean file = target != null && target.isFile();
                    visitor.entry(path, false, file ? size(target) : 0, n.modified, file ? target.mode : n.mode);
                }
            }
        }

        private boolean isLink(final @NotNull String path) {
            final Inode n = lookup(path);

            return n != null && n.isLink();
        }

        /** The inode of a path without following links. */
        private @Nullable Inode lookup(final @NotNull String path) {
            long id = ROOT_DIR;
            for (final String part : path.split("/")) {
                if (part.isEmpty()) {
                    continue;
                }
                final Drec d = child(id, part);
                if (d == null) {
                    return null;
                }
                id = d.id;
            }

            return inodes.get(id);
        }

        private @Nullable Drec child(final long dir, final @NotNull String name) {
            for (final Drec d : children.getOrDefault(dir, List.of())) {
                if (d.name.equals(name)) {
                    return d;
                }
            }

            return null;
        }

        /** Follows the path from the volume root, resolving symbolic links on the way and at the end; null — not found or outside. */
        private @Nullable Inode resolve(final @NotNull String path, final int links) throws IOException {
            final List<String> parts = new ArrayList<>(Arrays.asList(path.split("/")));
            final List<Long> stack = new ArrayList<>(List.of(ROOT_DIR));
            int followed = links;
            while (!parts.isEmpty()) {
                final String part = parts.remove(0);
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    if (stack.size() > 1) {
                        stack.remove(stack.size() - 1);
                    }
                    continue;
                }
                final Drec d = child(stack.get(stack.size() - 1), part);
                final Inode n = d == null ? null : inodes.get(d.id);
                if (n == null) {
                    return null;
                }
                if (n.isLink()) {
                    if (++followed > MAX_LINKS) {
                        return null;
                    }
                    final String target = linkTarget(d.id);
                    // an absolute link points outside the image (/Applications in installers)
                    if (target == null || target.startsWith("/")) {
                        return null;
                    }
                    parts.addAll(0, Arrays.asList(target.split("/")));
                    continue;
                }
                stack.add(d.id);
            }

            return inodes.get(stack.get(stack.size() - 1));
        }

        private @Nullable String linkTarget(final long id) throws IOException {
            final byte[] data = xattr(id, SYMLINK);
            if (data == null) {
                return null;
            }
            int end = 0;
            while (end < data.length && data[end] != 0) {
                end++;
            }

            return new String(data, 0, end, StandardCharsets.UTF_8);
        }

        private byte @Nullable [] xattr(final long id, final @NotNull String attr) throws IOException {
            final Xattr x = xattrs.getOrDefault(id, Map.of()).get(attr);
            if (x == null) {
                return null;
            }
            if (x.data != null) {
                return x.data;
            }
            if (x.size < 0 || x.size > MAX_XATTR) {
                throw new IOException("APFS: attribute " + attr + " too large");
            }

            return new Extents(extents.getOrDefault(x.stream, List.of()), x.size).readAll();
        }

        /** Logical size: for a compressed file — from the decmpfs header. */
        private long size(final @NotNull Inode n) throws IOException {
            if ((n.bsdFlags & UF_COMPRESSED) == 0) {
                return n.size;
            }
            final byte[] h = xattr(n.id, DECMPFS);
            if (h == null || h.length < DECMPFS_HEADER || le(h).getInt(0) != DECMPFS_MAGIC) {
                return n.size;
            }

            return le(h).getLong(8);
        }

        private @NotNull InputStream data(final @NotNull Inode n) throws IOException {
            if ((n.bsdFlags & UF_COMPRESSED) == 0) {
                return new Extents(extents.getOrDefault(n.privateId, List.of()), n.size).stream();
            }
            final long id = n.id;
            final byte[] h = xattr(id, DECMPFS);
            if (h == null || h.length < DECMPFS_HEADER || le(h).getInt(0) != DECMPFS_MAGIC) {
                return new Extents(extents.getOrDefault(n.privateId, List.of()), n.size).stream();
            }
            final int method = le(h).getInt(4);
            final long size = le(h).getLong(8);
            switch (method) {
                case CMP_UNCOMPRESSED_XATTR -> {
                    return new ByteArrayInputStream(h, DECMPFS_HEADER, h.length - DECMPFS_HEADER);
                }
                case CMP_ZLIB_XATTR -> {
                    if (size > MAX_XATTR) {
                        throw new IOException("APFS: damaged compressed file");
                    }

                    return new ByteArrayInputStream(block(Arrays.copyOfRange(h, DECMPFS_HEADER, h.length), (int) size));
                }
                case CMP_ZLIB_RSRC -> {
                    final Xattr fork = xattrs.getOrDefault(id, Map.of()).get(RESOURCE_FORK);
                    if (fork == null) {
                        throw new IOException("APFS: compressed file without its resource fork");
                    }
                    final Extents stored = fork.data != null ? null : new Extents(extents.getOrDefault(fork.stream, List.of()), fork.size);

                    return new ForkStream(fork.data, stored, size);
                }
                default -> throw new IOException("APFS: file compression method " + method + " (LZVN/LZFSE) is not supported");
            }
        }
    }

    /** A zlib block of a compressed file; a first byte with the low nibble 0xF marks data stored as is. */
    private static byte @NotNull [] block(final byte @NotNull [] packed, final int size) throws IOException {
        if (packed.length > 0 && (packed[0] & 0x0F) == 0x0F) {
            return Arrays.copyOfRange(packed, 1, Math.min(packed.length, size + 1));
        }
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
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
            throw new IOException("APFS: damaged compressed file", e);
        } finally {
            inflater.end();
        }
    }

    /** File data by extents {logical offset, length, physical block (0 — sparse)}; past the extents the file reads as zeros. */
    private final class Extents {

        private final long @NotNull [] @NotNull [] list;
        private final long size;

        private Extents(final @NotNull List<long[]> list, final long size) {
            this.list = list.toArray(new long[0][]);
            Arrays.sort(this.list, (a, b) -> Long.compare(a[0], b[0]));
            this.size = size;
        }

        void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
            if (pos < 0 || pos + len > size) {
                throw new EOFException("APFS: read past the end of a file");
            }
            long p = pos;
            int o = off;
            int left = len;
            while (left > 0) {
                final long[] e = find(p);
                final int n;
                if (e == null) {
                    final long next = next(p);
                    n = (int) Math.min(left, next - p);
                    Arrays.fill(b, o, o + n, (byte) 0);
                } else {
                    n = (int) Math.min(left, e[0] + e[1] - p);
                    if (e[2] == 0) {
                        Arrays.fill(b, o, o + n, (byte) 0);
                    } else {
                        dev.read(e[2] * blockSize + (p - e[0]), b, o, n);
                    }
                }
                p += n;
                o += n;
                left -= n;
            }
        }

        private long @Nullable [] find(final long pos) {
            int lo = 0;
            int hi = list.length - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (pos < list[mid][0]) {
                    hi = mid - 1;
                } else if (pos >= list[mid][0] + list[mid][1]) {
                    lo = mid + 1;
                } else {
                    return list[mid];
                }
            }

            return null;
        }

        private long next(final long pos) {
            for (final long[] e : list) {
                if (e[0] > pos) {
                    return Math.min(e[0], size);
                }
            }

            return size;
        }

        byte @NotNull [] readAll() throws IOException {
            final byte[] b = new byte[(int) size];
            read(0, b, 0, b.length);

            return b;
        }

        @NotNull InputStream stream() {
            return new InputStream() {
                private long pos;

                @Override
                public int read() throws IOException {
                    final byte[] one = new byte[1];

                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
                }

                @Override
                public int read(final byte @NotNull [] b, final int off, final int len) throws IOException {
                    if (len == 0) {
                        return 0;
                    }
                    if (pos >= size) {
                        return -1;
                    }
                    final int n = (int) Math.min(len, size - pos);
                    Extents.this.read(pos, b, off, n);
                    pos += n;

                    return n;
                }

                @Override
                public long skip(final long n) {
                    final long step = Math.max(0, Math.min(n, size - pos));
                    pos += step;

                    return step;
                }
            };
        }
    }

    /**
     * A file compressed into its resource fork: after the 256-byte fork header, at the data offset, a big-endian length,
     * then a little-endian block count and {offset, size} table (offsets from the table start), each block 64 KiB unpacked.
     */
    private static final class ForkStream extends InputStream {

        private final byte @Nullable [] fork;
        private final @Nullable Extents extents;
        private final long size;
        private long @Nullable [] table;
        private long tableBase;
        private byte @NotNull [] current = new byte[0];
        private int currentPos;
        private int nextBlock;
        private long produced;

        private ForkStream(final byte @Nullable [] fork, final @Nullable Extents extents, final long size) {
            this.fork = fork;
            this.extents = extents;
            this.size = size;
        }

        private byte @NotNull [] readFork(final long pos, final int len) throws IOException {
            final byte[] b = new byte[len];
            if (fork != null) {
                if (pos < 0 || pos + len > fork.length) {
                    throw new EOFException("APFS: damaged resource fork");
                }
                System.arraycopy(fork, (int) pos, b, 0, len);
            } else if (extents != null) {
                extents.read(pos, b, 0, len);
            }

            return b;
        }

        private void readTable() throws IOException {
            final ByteBuffer header = ByteBuffer.wrap(readFork(0, 16));
            final long dataOffset = Integer.toUnsignedLong(header.getInt(0));
            tableBase = dataOffset + 4;
            final int count = le(readFork(tableBase, 4)).getInt(0);
            if (count < 0 || count > MAX_XATTR / 8) {
                throw new IOException("APFS: damaged resource fork");
            }
            final ByteBuffer t = le(readFork(tableBase + 4, count * 8));
            table = new long[count * 2];
            for (int i = 0; i < count; i++) {
                table[i * 2] = Integer.toUnsignedLong(t.getInt(i * 8));
                table[i * 2 + 1] = Integer.toUnsignedLong(t.getInt(i * 8 + 4));
            }
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];

            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(final byte @NotNull [] b, final int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (currentPos >= current.length) {
                if (produced >= size) {
                    return -1;
                }
                if (table == null) {
                    readTable();
                }
                if (nextBlock * 2 >= table.length) {
                    throw new EOFException("APFS: truncated compressed file");
                }
                final long blockSize = table[nextBlock * 2 + 1];
                if (blockSize > CMP_BLOCK * 2L) {
                    throw new IOException("APFS: damaged compressed file");
                }
                final int unpacked = (int) Math.min(CMP_BLOCK, size - produced);
                current = block(readFork(tableBase + table[nextBlock * 2], (int) blockSize), unpacked);
                currentPos = 0;
                nextBlock++;
                produced += current.length;
                if (current.length == 0) {
                    throw new EOFException("APFS: truncated compressed file");
                }
            }
            final int n = Math.min(len, current.length - currentPos);
            System.arraycopy(current, currentPos, b, off, n);
            currentPos += n;

            return n;
        }
    }

    private static final class Inode {

        /** The inode number: attributes are keyed by it, extents — by the data stream id (privateId). */
        private final long id;
        private final long privateId;
        private final long modified;
        private final int bsdFlags;
        private final int mode;
        private long size;

        private Inode(final long id, final long privateId, final long modified, final int bsdFlags, final int mode) {
            this.id = id;
            this.privateId = privateId;
            this.modified = modified;
            this.bsdFlags = bsdFlags;
            this.mode = mode;
        }

        private boolean isDir() {
            return (mode & S_IFMT) == S_IFDIR;
        }

        private boolean isFile() {
            return (mode & S_IFMT) == S_IFREG;
        }

        private boolean isLink() {
            return (mode & S_IFMT) == S_IFLNK;
        }
    }

    private static final class Drec {

        private final @NotNull String name;
        private final long id;

        private Drec(final @NotNull String name, final long id) {
            this.name = name;
            this.id = id;
        }
    }

    private static final class Xattr {

        private byte @Nullable [] data;
        private long stream;
        private long size;
    }
}
