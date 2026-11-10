package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * NTFS — read only. The whole MFT is scanned at open and the tree is built from the parent references of the file names
 * (hard links appear under each of their names). Unnamed data streams only: resident, non-resident, sparse and LZNT1-compressed;
 * EFS-encrypted files fail on read. The metadata files ($MFT, $LogFile...) are hidden. Symbolic links and junctions with relative
 * targets point to their files, the others are shown as empty files.
 */
final class Ntfs {

    private static final int ATTR_STANDARD_INFORMATION = 0x10;
    private static final int ATTR_ATTRIBUTE_LIST = 0x20;
    private static final int ATTR_FILE_NAME = 0x30;
    private static final int ATTR_VOLUME_NAME = 0x60;
    private static final int ATTR_DATA = 0x80;
    private static final int ATTR_REPARSE_POINT = 0xC0;
    private static final int ATTR_END = 0xFFFFFFFF;
    private static final int FLAG_IN_USE = 0x01;
    private static final int FLAG_DIRECTORY = 0x02;
    private static final int ATTR_FLAG_COMPRESSED = 0x0001;
    private static final int ATTR_FLAG_ENCRYPTED = 0x4000;
    private static final int NAMESPACE_DOS = 2;
    private static final long ROOT = 5;
    private static final long FIRST_USER_RECORD = 16;
    private static final long REF_MASK = 0xFFFFFFFFFFFFL;
    private static final int FIXUP_STRIDE = 512;
    private static final long EPOCH_DIFF_MILLIS = 11_644_473_600_000L;
    private static final int TAG_SYMLINK = 0xA000000C;
    private static final int TAG_MOUNT_POINT = 0xA0000003;
    private static final int TAG_LX_SYMLINK = 0xA000001D;
    private static final int DOS_ATTRS = 0x27;
    private static final int LZNT1_CHUNK = 4096;
    private static final int MAX_DEPTH = 256;
    private static final int MAX_LINK = 4096;
    private static final int SCAN_BATCH = 1024;

    private final @NotNull BlockSource dev;
    private final int clusterSize;
    private final int recordSize;
    private @NotNull FsData mft;

    private Ntfs(final @NotNull BlockSource dev, final int clusterSize, final int recordSize) {
        this.dev = dev;
        this.clusterSize = clusterSize;
        this.recordSize = recordSize;
        this.mft = new FsData(dev, 0);
    }

    static boolean matches(final @NotNull BlockSource dev) throws IOException {
        final byte[] b = DiskImages.peek(dev, 0, 512);

        return b != null && "NTFS    ".equals(DiskImages.ascii(DiskImages.le(b), 3, 8));
    }

    static @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
        final ByteBuffer boot = DiskImages.le(dev.read(0, 512));
        final int sector = boot.getShort(11) & 0xFFFF;
        final int spc = boot.get(13) & 0xFF;
        final int clusterSize = sector * (spc > 0x80 ? 1 << 256 - spc : spc);
        final int perRecord = boot.get(64);
        final int recordSize = perRecord < 0 ? 1 << -perRecord : perRecord * clusterSize;
        if (sector < 256 || clusterSize <= 0 || recordSize < 1024 || recordSize > 65536) {
            throw new IOException("NTFS: bad geometry");
        }
        final Ntfs fs = new Ntfs(dev, clusterSize, recordSize);
        fs.bootstrap(boot.getLong(48) * clusterSize);
        final FsTree tree = new FsTree("NTFS");
        fs.scan(tree);

        return tree;
    }

    /** Reads the runs of $MFT from its first record, then from extension records when it has an attribute list. */
    private void bootstrap(final long mftOffset) throws IOException {
        final ByteBuffer first = fixup(dev.read(mftOffset, recordSize));
        if (first == null) {
            throw new IOException("NTFS: damaged $MFT record");
        }
        final List<Attr> data = new ArrayList<>();
        for (final Attr a : attributes(first)) {
            if (a.type == ATTR_DATA && a.nameLength == 0) {
                data.add(a);
            }
        }
        if (data.isEmpty() || data.get(0).resident) {
            throw new IOException("NTFS: $MFT without data");
        }
        mft = data(data);
        final Attr list = find(attributes(first), ATTR_ATTRIBUTE_LIST);
        if (list != null) {
            data.clear();
            data.addAll(listedAttributes(first, list, ATTR_DATA));
            mft = data(data);
        }
    }

    /** All attributes of the type from the records named in the attribute list (unnamed ones only). */
    private @NotNull List<Attr> listedAttributes(final @NotNull ByteBuffer base, final @NotNull Attr list, final int type) throws IOException {
        final byte[] content = content(list).readAll(Integer.MAX_VALUE - 16);
        final ByteBuffer b = DiskImages.le(content);
        final List<Attr> result = new ArrayList<>();
        final Map<Long, ByteBuffer> records = new HashMap<>();
        for (int off = 0; off + 26 <= content.length; ) {
            final int t = b.getInt(off);
            final int length = b.getShort(off + 4) & 0xFFFF;
            final int nameLength = content[off + 6] & 0xFF;
            final long ref = b.getLong(off + 16) & REF_MASK;
            if (length < 26) {
                break;
            }
            if (t == type && nameLength == 0) {
                ByteBuffer rec = records.get(ref);
                if (rec == null) {
                    rec = record(ref);
                    records.put(ref, rec);
                }
                if (rec != null) {
                    for (final Attr a : attributes(rec)) {
                        if (a.type == type && a.nameLength == 0 && !result.contains(a)) {
                            result.add(a);
                        }
                    }
                }
            }
            off += length;
        }

        return result;
    }

    private void scan(final @NotNull FsTree tree) throws IOException {
        // a damaged $MFT size cannot make the scan longer than the volume
        final long count = Math.min(mft.size(), dev.size()) / recordSize;
        final Map<Long, Entry> entries = new HashMap<>();
        try (InputStream in = mft.stream()) {
            final byte[] batch = new byte[recordSize * SCAN_BATCH];
            for (long r = 0; r < count; r += SCAN_BATCH) {
                final int n = (int) Math.min(SCAN_BATCH, count - r);
                final int got = in.readNBytes(batch, 0, n * recordSize);
                for (int i = 0; i * recordSize + recordSize <= got; i++) {
                    final ByteBuffer rec = fixup(Arrays.copyOfRange(batch, i * recordSize, (i + 1) * recordSize));
                    if (rec != null) {
                        entry(r + i, rec, entries, tree);
                    }
                }
            }
        }
        final Map<Long, List<Long>> children = new HashMap<>();
        for (final Map.Entry<Long, Entry> e : entries.entrySet()) {
            for (final Name n : e.getValue().names) {
                children.computeIfAbsent(n.parent, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        tree.enter(ROOT);
        build(ROOT, "", entries, children, tree, 0);
    }

    private void entry(final long number, final @NotNull ByteBuffer rec, final @NotNull Map<Long, Entry> entries,
        final @NotNull FsTree tree) throws IOException {
        final int flags = rec.getShort(22) & 0xFFFF;
        if ((flags & FLAG_IN_USE) == 0 || (rec.getLong(32) & REF_MASK) != 0) {
            return;
        }
        final Entry e = new Entry((flags & FLAG_DIRECTORY) != 0);
        final List<Name> dos = new ArrayList<>();
        for (final Attr a : attributes(rec)) {
            if (a.type == ATTR_STANDARD_INFORMATION && a.resident && a.value.length >= 36) {
                final ByteBuffer v = DiskImages.le(a.value);
                e.modified = time(v.getLong(8));
                e.attrs = v.getInt(32);
            } else if (a.type == ATTR_FILE_NAME && a.resident && a.value.length >= 66) {
                final ByteBuffer v = DiskImages.le(a.value);
                final int length = a.value[64] & 0xFF;
                if (66 + length * 2 <= a.value.length) {
                    final Name name = new Name(v.getLong(0) & REF_MASK, DiskImages.utf16(v, 66, length));
                    (a.value[65] == NAMESPACE_DOS ? dos : e.names).add(name);
                    e.size = Math.max(e.size, v.getLong(48));
                }
            } else if (a.type == ATTR_DATA && a.nameLength == 0) {
                e.size = a.resident ? a.value.length : a.startVcn == 0 ? a.dataSize : e.size;
            } else if (a.type == ATTR_VOLUME_NAME && number == 3 && a.resident) {
                tree.setLabel(new String(a.value, StandardCharsets.UTF_16LE));
            } else if (a.type == ATTR_REPARSE_POINT) {
                e.reparse = true;
            }
        }
        if (e.names.isEmpty()) {
            e.names.addAll(dos);
        }
        if (!e.names.isEmpty() && (number >= FIRST_USER_RECORD || number == ROOT)) {
            entries.put(number, e);
        }
    }

    private void build(final long dir, final @NotNull String path, final @NotNull Map<Long, Entry> entries,
        final @NotNull Map<Long, List<Long>> children, final @NotNull FsTree tree, final int depth) throws IOException {
        for (final long number : children.getOrDefault(dir, List.of())) {
            final Entry e = entries.get(number);
            if (number == ROOT) {
                continue;
            }
            for (final Name n : e.names) {
                if (n.parent != dir) {
                    continue;
                }
                final String p = FsTree.child(path, n.name);
                if (p == null) {
                    continue;
                }
                final String attrs = FileAttrs.dos(e.attrs & DOS_ATTRS);
                if (e.reparse) {
                    tree.link(p, e.modified, () -> linkTarget(number));
                } else if (e.directory) {
                    tree.dir(p, e.modified, attrs);
                    if (depth < MAX_DEPTH && tree.enter(number)) {
                        build(number, p, entries, children, tree, depth + 1);
                    }
                } else {
                    tree.file(p, e.size, e.modified, attrs, () -> open(number));
                }
            }
        }
    }

    private @NotNull InputStream open(final long number) throws IOException {
        final ByteBuffer rec = record(number);
        if (rec == null) {
            throw new IOException("NTFS: damaged file record " + number);
        }
        final List<Attr> all = attributes(rec);
        final Attr list = find(all, ATTR_ATTRIBUTE_LIST);
        final List<Attr> data = new ArrayList<>();
        if (list != null) {
            data.addAll(listedAttributes(rec, list, ATTR_DATA));
        } else {
            for (final Attr a : all) {
                if (a.type == ATTR_DATA && a.nameLength == 0) {
                    data.add(a);
                }
            }
        }
        if (data.isEmpty()) {
            return InputStream.nullInputStream();
        }
        if ((data.get(0).flags & ATTR_FLAG_ENCRYPTED) != 0) {
            throw new IOException("NTFS: the file is encrypted (EFS)");
        }

        return data(data).stream();
    }

    private @NotNull String linkTarget(final long number) throws IOException {
        final ByteBuffer rec = record(number);
        final Attr a = rec == null ? null : find(attributes(rec), ATTR_REPARSE_POINT);
        if (a == null) {
            throw new IOException("NTFS: no reparse point");
        }
        final byte[] v = content(a).readAll(MAX_LINK);
        final ByteBuffer b = DiskImages.le(v);
        final int tag = b.getInt(0);
        if (tag == TAG_LX_SYMLINK) {
            return new String(v, 12, v.length - 12, StandardCharsets.UTF_8);
        }
        if (tag != TAG_SYMLINK && tag != TAG_MOUNT_POINT) {
            throw new IOException("NTFS: unsupported reparse point");
        }
        final int buffer = tag == TAG_SYMLINK ? 20 : 16;
        final boolean relative = tag == TAG_SYMLINK && (b.getInt(16) & 1) != 0;
        final int offset = b.getShort(8 + 4) & 0xFFFF;
        final int length = b.getShort(8 + 6) & 0xFFFF;
        final String print = new String(v, buffer + offset, length, StandardCharsets.UTF_16LE).replace('\\', '/');

        // an absolute Windows path (C:/...) cannot be resolved inside the image
        return relative ? print : "/../" + print;
    }

    /** The content of the attribute fragments (sorted by VCN): resident bytes or the runs, possibly compressed. */
    private @NotNull FsData data(final @NotNull List<Attr> fragments) throws IOException {
        fragments.sort((x, y) -> Long.compare(x.startVcn, y.startVcn));
        final Attr first = fragments.get(0);
        if (first.resident) {
            return new FsData(dev, first.value.length).decoded(0, first.value.length, 0, () -> first.value);
        }
        final List<long[]> runs = new ArrayList<>();
        for (final Attr a : fragments) {
            if (!a.resident) {
                runs(a, runs);
            }
        }
        final FsData data = new FsData(dev, first.dataSize);
        final long initialized = Math.min(first.initializedSize, first.dataSize);
        if ((first.flags & ATTR_FLAG_COMPRESSED) != 0 && first.compressionUnit > 0) {
            compressed(runs, 1 << first.compressionUnit, initialized, data);
        } else {
            for (final long[] r : runs) {
                final long logical = r[0] * clusterSize;
                final long length = Math.min((long) r[1] * clusterSize, initialized - logical);
                if (length > 0 && r[2] >= 0) {
                    data.raw(logical, r[2] * clusterSize, length);
                }
            }
        }

        return data;
    }

    private @NotNull FsData content(final @NotNull Attr a) throws IOException {
        return data(new ArrayList<>(List.of(a)));
    }

    /** Decodes a runlist into {vcn, clusters, lcn (-1 — sparse)}. */
    private static void runs(final @NotNull Attr a, final @NotNull List<long[]> out) throws IOException {
        final byte[] r = a.runlist;
        long vcn = a.startVcn;
        long lcn = 0;
        int p = 0;
        while (p < r.length && r[p] != 0) {
            final int header = r[p++] & 0xFF;
            final int lengthBytes = header & 0x0F;
            final int offsetBytes = header >> 4;
            if (lengthBytes == 0 || lengthBytes > 8 || offsetBytes > 8 || p + lengthBytes + offsetBytes > r.length) {
                throw new IOException("NTFS: damaged runlist");
            }
            final long length = signed(r, p, lengthBytes);
            p += lengthBytes;
            if (offsetBytes == 0) {
                out.add(new long[] {vcn, length, -1});
            } else {
                lcn += signed(r, p, offsetBytes);
                out.add(new long[] {vcn, length, lcn});
            }
            p += offsetBytes;
            vcn += length;
        }
    }

    private static long signed(final byte @NotNull [] b, final int off, final int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) {
            v = v << 8 | b[off + i] & 0xFF;
        }
        final int shift = 64 - n * 8;

        return v << shift >> shift;
    }

    /**
     * Compression units of 16 (2^unit) clusters: fully allocated — stored as is; allocated in part — LZNT1 in the allocated
     * clusters; not allocated — zeros.
     */
    private void compressed(final @NotNull List<long[]> runs, final int unitClusters, final long initialized, final @NotNull FsData data) {
        long total = 0;
        for (final long[] r : runs) {
            total = Math.max(total, r[0] + r[1]);
        }
        final long unitBytes = (long) unitClusters * clusterSize;
        for (long unit = 0; unit * unitClusters < total && unit * unitBytes < initialized; unit++) {
            final List<long[]> pieces = slice(runs, unit * unitClusters, unitClusters);
            long allocated = 0;
            for (final long[] p : pieces) {
                if (p[2] >= 0) {
                    allocated += p[1];
                }
            }
            final long logical = unit * unitBytes;
            final long length = Math.min(unitBytes, initialized - logical);
            if (allocated == unitClusters) {
                for (final long[] p : pieces) {
                    data.raw(p[0] * clusterSize, p[2] * clusterSize, Math.min(p[1] * clusterSize, initialized - p[0] * clusterSize));
                }
            } else if (allocated > 0) {
                final long stored = allocated;
                data.decoded(logical, length, 0, () -> lznt1(read(pieces, stored), (int) unitBytes));
            }
        }
    }

    /** The parts of the runs covering clusters [vcn, vcn + count). */
    private static @NotNull List<long[]> slice(final @NotNull List<long[]> runs, final long vcn, final long count) {
        final List<long[]> result = new ArrayList<>();
        for (final long[] r : runs) {
            final long from = Math.max(vcn, r[0]);
            final long to = Math.min(vcn + count, r[0] + r[1]);
            if (from < to) {
                result.add(new long[] {from, to - from, r[2] < 0 ? -1 : r[2] + from - r[0]});
            }
        }

        return result;
    }

    private byte @NotNull [] read(final @NotNull List<long[]> pieces, final long clusters) throws IOException {
        final byte[] out = new byte[(int) (clusters * clusterSize)];
        int off = 0;
        for (final long[] p : pieces) {
            if (p[2] >= 0) {
                dev.read(p[2] * clusterSize, out, off, (int) (p[1] * clusterSize));
                off += (int) (p[1] * clusterSize);
            }
        }

        return out;
    }

    /** LZNT1: 4 KiB chunks with a 2-byte header; in a compressed chunk 8 tokens follow each flag byte. */
    static byte @NotNull [] lznt1(final byte @NotNull [] in, final int max) throws IOException {
        final byte[] out = new byte[max];
        int ip = 0;
        int op = 0;
        while (ip + 2 <= in.length && op < max) {
            final int header = in[ip] & 0xFF | (in[ip + 1] & 0xFF) << 8;
            ip += 2;
            if (header == 0) {
                break;
            }
            final int size = (header & 0x0FFF) + 1;
            final int end = Math.min(in.length, ip + size);
            final int chunkStart = op;
            if ((header & 0x8000) == 0) {
                final int n = Math.min(end - ip, max - op);
                System.arraycopy(in, ip, out, op, n);
                op += n;
            } else {
                int p = ip;
                while (p < end && op < max) {
                    final int flags = in[p++] & 0xFF;
                    for (int bit = 0; bit < 8 && p < end && op < max; bit++) {
                        if ((flags & 1 << bit) == 0) {
                            out[op++] = in[p++];
                            continue;
                        }
                        if (p + 1 >= end) {
                            throw new IOException("NTFS: damaged compressed data");
                        }
                        final int token = in[p] & 0xFF | (in[p + 1] & 0xFF) << 8;
                        p += 2;
                        int shift = 12;
                        for (int i = op - chunkStart - 1; i >= 0x10; i >>= 1) {
                            shift--;
                        }
                        final int length = (token & (1 << shift) - 1) + 3;
                        final int distance = (token >>> shift) + 1;
                        if (distance > op - chunkStart) {
                            throw new IOException("NTFS: damaged compressed data");
                        }
                        final int n = Math.min(length, max - op);
                        for (int i = 0; i < n; i++) {
                            out[op + i] = out[op - distance + i];
                        }
                        op += n;
                    }
                }
                // a chunk that decompresses to less than 4 KiB ends with zeros
                op = Math.min(max, Math.max(op, chunkStart + LZNT1_CHUNK));
            }
            ip = end;
        }

        return op == max ? out : Arrays.copyOf(out, op);
    }

    private @Nullable ByteBuffer record(final long number) throws IOException {
        final long pos = number * recordSize;
        if (pos + recordSize > mft.size()) {
            return null;
        }
        try (InputStream in = mft.stream()) {
            in.skipNBytes(pos);

            return fixup(in.readNBytes(recordSize));
        }
    }

    /** Applies the update sequence array; null — not a valid FILE record. */
    private static @Nullable ByteBuffer fixup(final byte @NotNull [] rec) {
        final ByteBuffer b = DiskImages.le(rec);
        if (rec.length < 48 || b.getInt(0) != 0x454C4946) {
            return null;
        }
        final int usa = b.getShort(4) & 0xFFFF;
        final int count = b.getShort(6) & 0xFFFF;
        if (usa + count * 2 > rec.length || (count - 1) * FIXUP_STRIDE > rec.length) {
            return null;
        }
        final short check = b.getShort(usa);
        for (int i = 1; i < count; i++) {
            final int at = i * FIXUP_STRIDE - 2;
            if (b.getShort(at) != check) {
                return null;
            }
            b.putShort(at, b.getShort(usa + i * 2));
        }

        return b;
    }

    private static @NotNull List<Attr> attributes(final @NotNull ByteBuffer rec) {
        final List<Attr> result = new ArrayList<>();
        int off = rec.getShort(20) & 0xFFFF;
        final int used = Math.min(rec.getInt(24), rec.limit());
        while (off + 16 <= used) {
            final int type = rec.getInt(off);
            final int length = rec.getInt(off + 4);
            if (type == ATTR_END || length < 16 || off + length > used) {
                break;
            }
            final Attr a = attr(rec, off, length);
            if (a != null) {
                result.add(a);
            }
            off += length;
        }

        return result;
    }

    private static @Nullable Attr attr(final @NotNull ByteBuffer rec, final int off, final int length) {
        final byte[] all = rec.array();
        final Attr a = new Attr();
        a.type = rec.getInt(off);
        a.resident = all[off + 8] == 0;
        a.nameLength = all[off + 9] & 0xFF;
        a.flags = rec.getShort(off + 12) & 0xFFFF;
        if (a.resident) {
            final int valueLength = rec.getInt(off + 16);
            final int valueOffset = rec.getShort(off + 20) & 0xFFFF;
            if (valueLength < 0 || valueOffset + valueLength > length) {
                return null;
            }
            a.value = Arrays.copyOfRange(all, off + valueOffset, off + valueOffset + valueLength);
        } else {
            if (length < 64) {
                return null;
            }
            a.startVcn = rec.getLong(off + 16);
            final int runOffset = rec.getShort(off + 32) & 0xFFFF;
            a.compressionUnit = rec.getShort(off + 34) & 0xFFFF;
            a.dataSize = rec.getLong(off + 48);
            a.initializedSize = rec.getLong(off + 56);
            if (runOffset > length) {
                return null;
            }
            a.runlist = Arrays.copyOfRange(all, off + runOffset, off + length);
        }

        return a;
    }

    private static @Nullable Attr find(final @NotNull List<Attr> attrs, final int type) {
        for (final Attr a : attrs) {
            if (a.type == type) {
                return a;
            }
        }

        return null;
    }

    private static long time(final long filetime) {
        return filetime <= 0 ? 0 : filetime / 10_000 - EPOCH_DIFF_MILLIS;
    }

    private static final class Attr {

        private int type;
        private boolean resident;
        private int nameLength;
        private int flags;
        private byte @NotNull [] value = new byte[0];
        private byte @NotNull [] runlist = new byte[0];
        private long startVcn;
        private int compressionUnit;
        private long dataSize;
        private long initializedSize;
    }

    private static final class Name {

        private final long parent;
        private final @NotNull String name;

        private Name(final long parent, final @NotNull String name) {
            this.parent = parent;
            this.name = name;
        }
    }

    private static final class Entry {

        private final boolean directory;
        private final List<Name> names = new ArrayList<>();
        private long modified;
        private int attrs;
        private long size;
        private boolean reparse;

        private Entry(final boolean directory) {
            this.directory = directory;
        }
    }
}
