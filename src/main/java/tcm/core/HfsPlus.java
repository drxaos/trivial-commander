package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * HFS+ and HFSX (also inside an HFS wrapper) — read only. The catalog is read in full at open; data forks with overflow extents,
 * file hard links, symbolic links and zlib-compressed files (decmpfs). Names are shown composed (NFC), "/" in a name as ":".
 * The private directories of hard links are hidden. The journal is not replayed.
 */
final class HfsPlus {

    private static final int HEADER = 1024;
    private static final int SIG_PLUS = 0x482B;
    private static final int SIG_X = 0x4858;
    private static final int SIG_HFS = 0x4244;
    private static final int NODE_LEAF = -1;
    private static final int FOLDER = 1;
    private static final int FILE = 2;
    private static final long ROOT = 2;
    private static final long EXTENTS_FILE = 3;
    private static final long CATALOG_FILE = 4;
    private static final long ATTRIBUTES_FILE = 8;
    private static final long MAC_EPOCH_OFFSET = 2_082_844_800L;
    private static final int S_IFMT = 0170000;
    private static final int S_IFDIR = 0040000;
    private static final int S_IFREG = 0100000;
    private static final int S_IFLNK = 0120000;
    private static final int UF_COMPRESSED = 0x20;
    private static final int HARD_LINK_TYPE = 0x686C6E6B;
    private static final int HARD_LINK_CREATOR = 0x6866732B;
    private static final int ATTR_INLINE = 0x10;
    private static final String FILE_LINKS = "\0\0\0\0HFS+ Private Data";
    private static final String DIR_LINKS = ".HFS+ Private Directory Data\r";
    private static final int MAX_DEPTH = 256;
    private static final int MAX_NODES = 1 << 24;
    private static final int MAX_LINK = 4096;
    private static final int MAX_ATTR = 64 * 1024 * 1024;

    private final @NotNull BlockSource dev;
    private final int blockSize;
    private final @NotNull ByteBuffer header;
    private final Map<Long, String> folders = new HashMap<>();
    private final Map<Long, List<Record>> children = new HashMap<>();
    private @Nullable Map<String, List<long[]>> overflow;
    /** The decmpfs attributes by file id. */
    private @Nullable Map<Long, byte[]> compression;

    private HfsPlus(final @NotNull BlockSource dev, final @NotNull ByteBuffer header) {
        this.dev = dev;
        this.header = header;
        this.blockSize = header.getInt(40);
    }

    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        final byte[] b = DiskImages.peek(dev, HEADER, 512);
        if (b == null) {
            return false;
        }
        final ByteBuffer h = ByteBuffer.wrap(b);
        final int sig = h.getShort(0) & 0xFFFF;

        return sig == SIG_PLUS || sig == SIG_X || sig == SIG_HFS && (h.getShort(0x7C) & 0xFFFF) == SIG_PLUS;
    }

    static @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
        final ByteBuffer h = ByteBuffer.wrap(dev.read(HEADER, 512));
        if ((h.getShort(0) & 0xFFFF) == SIG_HFS) {
            // an HFS wrapper: the HFS+ volume is embedded in its allocation blocks
            final long offset = (h.getShort(0x1C) & 0xFFFFL) * 512 + (h.getShort(0x7E) & 0xFFFFL) * h.getInt(0x14);
            final long length = (h.getShort(0x80) & 0xFFFFL) * h.getInt(0x14);

            return read(BlockSource.slice(dev, offset, length));
        }
        final HfsPlus fs = new HfsPlus(dev, h);
        if (fs.blockSize < 512 || Integer.bitCount(fs.blockSize) != 1) {
            throw new IOException("HFS+: bad block size");
        }
        final FsTree tree = new FsTree((h.getShort(0) & 0xFFFF) == SIG_X ? "HFSX" : "HFS+");
        fs.catalog();
        final String volume = fs.folders.get(ROOT);
        if (volume == null) {
            throw new IOException("HFS+: no root folder");
        }
        tree.setLabel(volume);
        tree.enter(ROOT);
        fs.walk(ROOT, "", tree, 0);

        return tree;
    }

    /** Fork data (80 bytes) at the offset of a buffer as {logical size, extents...}. */
    private @NotNull Fork fork(final @NotNull ByteBuffer b, final int off, final long fileId, final boolean resource) {
        final Fork f = new Fork(fileId, resource, b.getLong(off), Integer.toUnsignedLong(b.getInt(off + 12)));
        for (int i = 0; i < 8; i++) {
            final long start = Integer.toUnsignedLong(b.getInt(off + 16 + i * 8));
            final long count = Integer.toUnsignedLong(b.getInt(off + 20 + i * 8));
            if (count > 0) {
                f.extents.add(new long[] {start, count});
            }
        }

        return f;
    }

    private final class Fork {

        private final long fileId;
        private final boolean resource;
        private final long size;
        private final long totalBlocks;
        private final List<long[]> extents = new ArrayList<>();

        private Fork(final long fileId, final boolean resource, final long size, final long totalBlocks) {
            this.fileId = fileId;
            this.resource = resource;
            this.size = size;
            this.totalBlocks = totalBlocks;
        }

        /** The content, with the extents overflow file consulted when the 8 extents do not cover all blocks. */
        private @NotNull FsData data() throws IOException {
            final List<long[]> all = new ArrayList<>(extents);
            long covered = 0;
            for (final long[] e : all) {
                covered += e[1];
            }
            if (covered < totalBlocks && fileId != EXTENTS_FILE) {
                all.addAll(overflow(fileId, resource, covered));
            }
            final FsData data = new FsData(dev, size);
            long logical = 0;
            for (final long[] e : all) {
                data.raw(logical, e[0] * blockSize, e[1] * blockSize);
                logical += e[1] * blockSize;
            }

            return data;
        }

        private @NotNull BlockSource source() throws IOException {
            final FsData data = data();

            return new BlockSource() {
                @Override
                public long size() {
                    return size;
                }

                @Override
                public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                    try (InputStream in = data.stream()) {
                        in.skipNBytes(pos);
                        if (in.readNBytes(b, off, len) != len) {
                            throw new IOException("HFS+: read past the end of a fork");
                        }
                    }
                }
            };
        }
    }

    /** Extents from the overflow file, in order of their start blocks (from the given one). */
    private synchronized @NotNull List<long[]> overflow(final long fileId, final boolean resource, final long from) throws IOException {
        if (overflow == null) {
            final Map<String, List<long[]>> map = new HashMap<>();
            leaves(fork(header, 192, EXTENTS_FILE, false), (node, off, keyLength) -> {
                final ByteBuffer k = node.duplicate();
                final int forkType = k.get(off + 2) & 0xFF;
                final long id = Integer.toUnsignedLong(k.getInt(off + 4));
                final long start = Integer.toUnsignedLong(k.getInt(off + 8));
                final int data = off + 2 + keyLength;
                final List<long[]> list = map.computeIfAbsent(id + ":" + forkType, x -> new ArrayList<>());
                for (int i = 0; i < 8 && data + i * 8 + 8 <= node.limit(); i++) {
                    final long count = Integer.toUnsignedLong(node.getInt(data + i * 8 + 4));
                    if (count > 0) {
                        list.add(new long[] {Integer.toUnsignedLong(node.getInt(data + i * 8)), count, start + sum(list)});
                    }
                }
            });
            for (final List<long[]> l : map.values()) {
                l.sort((x, y) -> Long.compare(x[2], y[2]));
            }
            overflow = map;
        }
        final List<long[]> result = new ArrayList<>();
        for (final long[] e : overflow.getOrDefault(fileId + ":" + (resource ? 0xFF : 0), List.of())) {
            if (e[2] >= from) {
                result.add(new long[] {e[0], e[1]});
            }
        }

        return result;
    }

    private static long sum(final @NotNull List<long[]> extents) {
        long total = 0;
        for (final long[] e : extents) {
            total += e[1];
        }

        return total;
    }

    @FunctionalInterface
    private interface LeafVisitor {

        void record(@NotNull ByteBuffer node, int off, int keyLength) throws IOException;
    }

    /** All records of the leaf nodes of a B-tree file, following the leaf chain. */
    private void leaves(final @NotNull Fork file, final @NotNull LeafVisitor visitor) throws IOException {
        final BlockSource tree = file.source();
        if (tree.size() < 512) {
            return;
        }
        final ByteBuffer head = ByteBuffer.wrap(tree.read(0, 512));
        final int nodeSize = head.getShort(14 + 18) & 0xFFFF;
        long node = Integer.toUnsignedLong(head.getInt(14 + 10));
        final long totalNodes = Math.min(Integer.toUnsignedLong(head.getInt(14 + 22)), MAX_NODES);
        if (nodeSize < 512) {
            throw new IOException("HFS+: damaged B-tree");
        }
        // the leaf chain visits each node at most once: more steps mean a loop in a damaged tree
        int steps = 0;
        while (node != 0 && node < totalNodes && steps++ < totalNodes) {
            final ByteBuffer n = ByteBuffer.wrap(tree.read(node * nodeSize, nodeSize));
            if (n.get(8) != NODE_LEAF) {
                break;
            }
            final int records = n.getShort(10) & 0xFFFF;
            for (int i = 0; i < records; i++) {
                final int off = n.getShort(nodeSize - 2 * (i + 1)) & 0xFFFF;
                if (off < 14 || off + 2 > nodeSize) {
                    continue;
                }
                final int keyLength = n.getShort(off) & 0xFFFF;
                if (off + 2 + keyLength > nodeSize) {
                    continue;
                }
                visitor.record(n, off, keyLength);
            }
            node = Integer.toUnsignedLong(n.getInt(0));
        }
    }

    private void catalog() throws IOException {
        leaves(fork(header, 272, CATALOG_FILE, false), (n, off, keyLength) -> {
            final long parent = Integer.toUnsignedLong(n.getInt(off + 2));
            final int nameLength = n.getShort(off + 6) & 0xFFFF;
            if (8 + nameLength * 2 > keyLength + 2) {
                return;
            }
            final byte[] raw = new byte[nameLength * 2];
            n.get(off + 8, raw);
            final String name = Normalizer.normalize(new String(raw, StandardCharsets.UTF_16BE), Normalizer.Form.NFC).replace('/', ':');
            int data = off + 2 + keyLength;
            data += data & 1;
            if (data + 2 > n.limit()) {
                return;
            }
            final int type = n.getShort(data);
            if (type == FOLDER && data + 88 <= n.limit()) {
                final Record r = new Record(name, ByteBuffer.wrap(n.array(), data, 88).slice(), true);
                final long id = Integer.toUnsignedLong(r.data.getInt(8));
                folders.put(id, name);
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(r);
            } else if (type == FILE && data + 248 <= n.limit()) {
                final Record r = new Record(name, ByteBuffer.wrap(n.array(), data, 248).slice(), false);
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(r);
            }
        });
    }

    private void walk(final long folder, final @NotNull String path, final @NotNull FsTree tree, final int depth) throws IOException {
        for (final Record r : children.getOrDefault(folder, List.of())) {
            if (folder == ROOT && (FILE_LINKS.equals(r.name) || DIR_LINKS.equals(r.name))) {
                continue;
            }
            final String p = FsTree.child(path, r.name);
            if (p == null) {
                continue;
            }
            final ByteBuffer d = r.data;
            final long modified = (Integer.toUnsignedLong(d.getInt(16)) - MAC_EPOCH_OFFSET) * 1000;
            final int mode = d.getShort(42) & 0xFFFF;
            final String attrs = FileAttrs.posix(mode);
            final long id = Integer.toUnsignedLong(d.getInt(8));
            if (r.folder) {
                tree.dir(p, modified, attrs);
                if (depth < MAX_DEPTH && tree.enter(id)) {
                    walk(id, p, tree, depth + 1);
                }
                continue;
            }
            final Record file = target(r);
            final ByteBuffer f = file.data;
            final int fileMode = f.getShort(42) & 0xFFFF;
            final long fileId = Integer.toUnsignedLong(f.getInt(8));
            switch (fileMode & S_IFMT) {
                case S_IFLNK -> tree.link(p, modified, () -> new String(fork(f, 88, fileId, false).data().readAll(MAX_LINK),
                    StandardCharsets.UTF_8));
                case S_IFDIR -> tree.dir(p, modified, attrs);
                default -> tree.file(p, size(f, fileId), modified, FileAttrs.posix((fileMode & S_IFMT) == 0 ? mode | S_IFREG : fileMode),
                    () -> data(f, fileId));
            }
        }
    }

    /** A hard link: the "iNode<n>" file in the private directory; any other file — itself. */
    private @NotNull Record target(final @NotNull Record r) {
        final ByteBuffer d = r.data;
        if (d.getInt(48) != HARD_LINK_TYPE || d.getInt(52) != HARD_LINK_CREATOR) {
            return r;
        }
        final String name = "iNode" + Integer.toUnsignedLong(d.getInt(44));
        for (final Record root : children.getOrDefault(ROOT, List.of())) {
            if (root.folder && FILE_LINKS.equals(root.name)) {
                for (final Record c : children.getOrDefault(Integer.toUnsignedLong(root.data.getInt(8)), List.of())) {
                    if (!c.folder && name.equals(c.name)) {
                        return c;
                    }
                }
            }
        }

        return r;
    }

    private long size(final @NotNull ByteBuffer f, final long fileId) throws IOException {
        if ((f.get(41) & UF_COMPRESSED) != 0) {
            final byte[] h = attribute(fileId, Decmpfs.XATTR);
            if (Decmpfs.matches(h)) {
                return Decmpfs.size(h);
            }
        }

        return f.getLong(88);
    }

    private @NotNull InputStream data(final @NotNull ByteBuffer f, final long fileId) throws IOException {
        if ((f.get(41) & UF_COMPRESSED) != 0) {
            final byte[] h = attribute(fileId, Decmpfs.XATTR);
            if (Decmpfs.matches(h)) {
                return Decmpfs.open(h, () -> fork(f, 168, fileId, true).source());
            }
        }

        return fork(f, 88, fileId, false).data().stream();
    }

    /** The inline attribute of the file from the attributes B-tree (all of its kind read at the first call); null — none. */
    private synchronized byte @Nullable [] attribute(final long fileId, final @NotNull String name) throws IOException {
        if (compression == null) {
            final Map<Long, byte[]> map = new HashMap<>();
            final Fork attributes = fork(header, 352, ATTRIBUTES_FILE, false);
            if (attributes.size > 0) {
                leaves(attributes, (n, off, keyLength) -> {
                    final int nameLength = n.getShort(off + 12) & 0xFFFF;
                    if (14 + nameLength * 2 > keyLength + 2) {
                        return;
                    }
                    final byte[] raw = new byte[nameLength * 2];
                    n.get(off + 14, raw);
                    final int data = off + 2 + keyLength;
                    if (name.equals(new String(raw, StandardCharsets.UTF_16BE)) && data + 16 <= n.limit() && n.getInt(data) == ATTR_INLINE) {
                        final int size = n.getInt(data + 12);
                        if (size >= 0 && size <= MAX_ATTR && data + 16 + size <= n.limit()) {
                            final byte[] value = new byte[size];
                            n.get(data + 16, value);
                            map.put(Integer.toUnsignedLong(n.getInt(off + 4)), value);
                        }
                    }
                });
            }
            compression = map;
        }

        return compression.get(fileId);
    }

    private static final class Record {

        private final @NotNull String name;
        private final @NotNull ByteBuffer data;
        private final boolean folder;

        private Record(final @NotNull String name, final @NotNull ByteBuffer data, final boolean folder) {
            this.name = name;
            this.data = data;
            this.folder = folder;
        }
    }
}
