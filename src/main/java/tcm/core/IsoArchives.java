package tcm.core;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ISO 9660 images — read only. Names come from Rock Ridge (long names, Unix permissions) if the image has it, otherwise from Joliet
 * (Unicode names), otherwise the plain 8.3-style names without the ";1" version. Files larger than 4 GB (several extents) are joined.
 * UDF-only images (no ISO 9660 directory tree) are not supported.
 */
final class IsoArchives {

    private static final int SECTOR = 2048;
    private static final int FIRST_DESCRIPTOR = 16;
    private static final int MAX_DESCRIPTORS = 64;
    private static final int TYPE_PRIMARY = 1;
    private static final int TYPE_SUPPLEMENTARY = 2;
    private static final int TYPE_TERMINATOR = 255;
    private static final int ROOT_RECORD = 156;
    private static final int FLAG_DIRECTORY = 2;
    private static final int FLAG_MULTI_EXTENT = 0x80;
    private static final int MAX_ENTRIES = 2_000_000;
    private static final int MAX_DIRECTORY = 64 * 1024 * 1024;
    private static final int NM_CONTINUE = 1;
    private static final int NM_CURRENT = 2;
    private static final int NM_PARENT = 4;

    private final @NotNull FileChannel ch;
    private final long imageSize;
    /** The root directory record of the chosen tree. */
    private final byte @NotNull [] root;
    private final boolean joliet;
    private final boolean rockRidge;
    /** File entry → its extents: {byte offset, length}. */
    private final Map<String, List<long[]>> extents = new HashMap<>();
    private int count;

    private IsoArchives(final @NotNull FileChannel ch, final byte @NotNull [] root, final boolean joliet, final boolean rockRidge)
        throws IOException {
        this.ch = ch;
        this.imageSize = ch.size();
        this.root = root;
        this.joliet = joliet;
        this.rockRidge = rockRidge;
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final FileChannel ch = FileChannel.open(index.getArchive(), StandardOpenOption.READ);
        try {
            final IsoArchives iso = open(ch);
            iso.walk(index);
            index.setTar(iso.reader(index.getArchive()));
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException io ? io : new IOException("Not a valid ISO image: " + index.getArchive().getFileName(), e);
        }
    }

    /** Chooses the directory tree: the primary one with Rock Ridge, else the Joliet one, else the primary one. */
    private static @NotNull IsoArchives open(final @NotNull FileChannel ch) throws IOException {
        byte[] primary = null;
        byte[] jolietRoot = null;
        for (int i = 0; i < MAX_DESCRIPTORS; i++) {
            final long pos = (long) (FIRST_DESCRIPTOR + i) * SECTOR;
            if (pos + SECTOR > ch.size()) {
                break;
            }
            final ByteBuffer d = read(ch, pos, SECTOR);
            if (d.get(1) != 'C' || d.get(2) != 'D' || d.get(3) != '0' || d.get(4) != '0' || d.get(5) != '1') {
                break;
            }
            final int type = d.get(0) & 0xFF;
            if (type == TYPE_TERMINATOR) {
                break;
            }
            if (type == TYPE_PRIMARY && primary == null) {
                primary = record(d, ROOT_RECORD);
            } else if (type == TYPE_SUPPLEMENTARY && d.get(88) == '%' && d.get(89) == '/'
                && (d.get(90) == '@' || d.get(90) == 'C' || d.get(90) == 'E')) {
                jolietRoot = record(d, ROOT_RECORD);
            }
        }
        if (primary == null) {
            throw new IOException("No ISO 9660 primary volume descriptor (UDF-only images are not supported)");
        }
        final boolean rr = hasRockRidge(ch, primary);
        if (rr || jolietRoot == null) {
            return new IsoArchives(ch, primary, false, rr);
        }

        return new IsoArchives(ch, jolietRoot, true, false);
    }

    private static byte @NotNull [] record(final @NotNull ByteBuffer d, final int offset) {
        final byte[] r = new byte[34];
        d.get(offset, r);

        return r;
    }

    /** Rock Ridge: the "." record of the root directory starts its system use area with "SP" (0xBE 0xEF). */
    private static boolean hasRockRidge(final @NotNull FileChannel ch, final byte @NotNull [] rootRecord) throws IOException {
        final long extent = u32(rootRecord, 2) * SECTOR;
        if (extent + 34 > ch.size()) {
            return false;
        }
        final ByteBuffer first = read(ch, extent, (int) Math.min(ch.size() - extent, SECTOR));
        final int len = first.get(0) & 0xFF;
        final int nameLen = first.get(32) & 0xFF;
        final int sua = 33 + nameLen + (nameLen % 2 == 0 ? 1 : 0);

        return len >= sua + 7 && first.get(sua) == 'S' && first.get(sua + 1) == 'P' && (first.get(sua + 4) & 0xFF) == 0xBE
            && (first.get(sua + 5) & 0xFF) == 0xEF;
    }

    /** Directories breadth-first; an extent seen twice (a loop in a broken image) is not read again. */
    private void walk(final @NotNull ArchiveIndex index) throws IOException {
        final Deque<Dir> queue = new ArrayDeque<>();
        final Set<Long> visited = new HashSet<>();
        queue.add(new Dir("", u32(root, 2), u32(root, 10)));
        while (!queue.isEmpty()) {
            final Dir dir = queue.removeFirst();
            if (visited.add(dir.extent)) {
                readDirectory(index, dir.path, dir.extent, dir.size, queue);
            }
        }
    }

    private void readDirectory(
        final @NotNull ArchiveIndex index,
        final @NotNull String path,
        final long extent,
        final long size,
        final @NotNull Deque<Dir> queue
    ) throws IOException {
        if (size > MAX_DIRECTORY || extent * SECTOR + size > imageSize) {
            throw new IOException("Corrupted ISO directory " + path);
        }
        final ByteBuffer data = read(ch, extent * SECTOR, (int) size);
        final List<long[]> pending = new ArrayList<>();
        int p = 0;
        while (p < size) {
            final int len = data.get(p) & 0xFF;
            if (len == 0) {
                // records do not cross sector boundaries: the rest of the sector is padding
                p = (p / SECTOR + 1) * SECTOR;
                continue;
            }
            if (p + len > size || len < 34) {
                break;
            }
            final byte[] rec = new byte[len];
            data.get(p, rec);
            p += len;
            final Entry e = parse(rec);
            if (e == null) {
                continue;
            }
            if (++count > MAX_ENTRIES) {
                throw new IOException("Too many entries in the ISO image");
            }
            if (rockRidge && path.isEmpty() && e.directory && e.name.equals("rr_moved")) {
                // holds the relocated deep directories, which are listed at their real place (CL)
                continue;
            }
            final String entry = path.isEmpty() ? e.name : path + "/" + e.name;
            pending.add(new long[] {e.extent * SECTOR, e.size});
            if ((rec[25] & FLAG_MULTI_EXTENT) != 0 && !e.directory) {
                continue;
            }
            if (e.directory) {
                pending.clear();
                index.add(entry, true, 0, e.time, e.attrs);
                queue.add(new Dir(entry, e.extent, e.size));
                continue;
            }
            long total = 0;
            for (final long[] part : pending) {
                total += part[1];
            }
            extents.put(ArchiveIndex.entryName(entry), new ArrayList<>(pending));
            pending.clear();
            index.add(entry, false, e.symlink ? 0 : total, e.time, e.attrs);
        }
    }

    private @Nullable Entry parse(final byte @NotNull [] rec) throws IOException {
        final int nameLen = rec[32] & 0xFF;
        if (33 + nameLen > rec.length) {
            return null;
        }
        if (nameLen == 1 && (rec[33] == 0 || rec[33] == 1)) {
            // "." and ".."
            return null;
        }
        final Entry e = new Entry();
        e.extent = u32(rec, 2);
        e.size = u32(rec, 10);
        e.directory = (rec[25] & FLAG_DIRECTORY) != 0;
        e.time = time(rec);
        e.name = joliet ? jolietName(rec, nameLen) : plainName(rec, nameLen);
        if (rockRidge && !rockRidge(rec, 33 + nameLen + (nameLen % 2 == 0 ? 1 : 0), e)) {
            return null;
        }
        if (e.name.isEmpty() || e.name.equals(".") || e.name.equals("..") || e.name.contains("/")) {
            return null;
        }

        return e;
    }

    /** Rock Ridge fields of a record: NM (name), PX (mode), SL (symbolic link), CL/RE (relocated deep directories). false — hide. */
    private boolean rockRidge(final byte @NotNull [] rec, final int start, final @NotNull Entry e) throws IOException {
        byte[] area = Arrays.copyOfRange(rec, Math.min(start, rec.length), rec.length);
        final StringBuilder name = new StringBuilder();
        boolean hasName = false;
        int p = 0;
        int continuations = 0;
        while (true) {
            if (p + 4 > area.length) {
                break;
            }
            final char a = (char) (area[p] & 0xFF);
            final char b = (char) (area[p + 1] & 0xFF);
            final int len = area[p + 2] & 0xFF;
            if (len < 4 || p + len > area.length) {
                break;
            }
            if (a == 'N' && b == 'M' && len >= 5) {
                final int flags = area[p + 4] & 0xFF;
                if ((flags & (NM_CURRENT | NM_PARENT)) == 0) {
                    name.append(new String(area, p + 5, len - 5, StandardCharsets.UTF_8));
                    hasName = true;
                }
            } else if (a == 'P' && b == 'X' && len >= 12) {
                e.attrs = FileAttrs.posix((int) u32(area, p + 4));
                final int type = (int) (u32(area, p + 4) & 0170000);
                e.symlink = type == 0120000;
            } else if (a == 'S' && b == 'L') {
                e.symlink = true;
            } else if (a == 'R' && b == 'E') {
                // a relocated directory: it is listed where its CL entry points from
                return false;
            } else if (a == 'C' && b == 'L' && len >= 12) {
                e.directory = true;
                e.extent = u32(area, p + 4);
                e.size = relocatedSize(e.extent);
            } else if (a == 'C' && b == 'E' && len >= 28 && continuations++ < 16) {
                final long block = u32(area, p + 4);
                final long offset = u32(area, p + 12);
                final long length = u32(area, p + 20);
                final long pos = block * SECTOR + offset;
                if (length > 0 && length <= SECTOR && pos + length <= imageSize) {
                    final byte[] more = new byte[(int) length];
                    read(ch, pos, (int) length).get(more);
                    final byte[] rest = Arrays.copyOfRange(area, p + len, area.length);
                    area = concat(rest, more);
                    p = 0;
                    continue;
                }
            } else if (a == 'S' && b == 'T') {
                break;
            }
            p += len;
        }
        if (hasName) {
            e.name = name.toString();
        }

        return true;
    }

    /** Size of a relocated directory: from its own "." record. */
    private long relocatedSize(final long extent) throws IOException {
        if (extent * SECTOR + 34 > imageSize) {
            return 0;
        }

        return u32(bytes(read(ch, extent * SECTOR, 34)), 10);
    }

    private static @NotNull String jolietName(final byte @NotNull [] rec, final int nameLen) {
        return stripVersion(new String(rec, 33, nameLen - nameLen % 2, StandardCharsets.UTF_16BE));
    }

    private static @NotNull String plainName(final byte @NotNull [] rec, final int nameLen) {
        String name = stripVersion(new String(rec, 33, nameLen, StandardCharsets.ISO_8859_1));
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }

        return name;
    }

    private static @NotNull String stripVersion(final @NotNull String name) {
        final int semi = name.lastIndexOf(';');

        return semi > 0 ? name.substring(0, semi) : name;
    }

    /** Recording date: years since 1900, month, day, hour, minute, second, offset from GMT in 15-minute steps. */
    private static long time(final byte @NotNull [] rec) {
        try {
            final LocalDateTime t = LocalDateTime.of(1900 + (rec[18] & 0xFF), rec[19], rec[20], rec[21], rec[22], rec[23]);

            return t.toInstant(ZoneOffset.ofTotalSeconds(rec[24] * 15 * 60)).toEpochMilli();
        } catch (final DateTimeException e) {
            // no date (zeros) or a broken one
            return 0;
        }
    }

    private @NotNull ArchiveReader reader(final @NotNull Path archive) {
        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final List<long[]> parts = extents.get(entry);
                if (parts == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }

                return new ExtentStream(parts);
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }

    /** File data: extents read with positional reads, so several streams can read the shared channel at once. */
    private final class ExtentStream extends InputStream {

        private final List<long[]> parts;
        private int part;
        private long offset;

        private ExtentStream(final @NotNull List<long[]> parts) {
            this.parts = parts;
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
            while (part < parts.size() && offset >= parts.get(part)[1]) {
                part++;
                offset = 0;
            }
            if (part >= parts.size()) {
                return -1;
            }
            final long[] p = parts.get(part);
            final int n = (int) Math.min(len, p[1] - offset);
            final int got = ch.read(ByteBuffer.wrap(b, off, n), p[0] + offset);
            if (got < 0) {
                throw new EOFException("Truncated ISO image");
            }
            offset += got;

            return got;
        }

        @Override
        public long skip(final long n) {
            long left = Math.max(n, 0);
            long skipped = 0;
            while (left > 0 && part < parts.size()) {
                final long step = Math.min(left, parts.get(part)[1] - offset);
                offset += step;
                skipped += step;
                left -= step;
                if (offset >= parts.get(part)[1]) {
                    part++;
                    offset = 0;
                }
            }

            return skipped;
        }
    }

    private static final class Dir {

        private final @NotNull String path;
        private final long extent;
        private final long size;

        private Dir(final @NotNull String path, final long extent, final long size) {
            this.path = path;
            this.extent = extent;
            this.size = size;
        }
    }

    private static final class Entry {

        private @NotNull String name = "";
        private long extent;
        private long size;
        private long time;
        private boolean directory;
        private boolean symlink;
        private @NotNull String attrs = "";
    }

    private static long u32(final byte @NotNull [] b, final int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }

    private static byte @NotNull [] concat(final byte @NotNull [] a, final byte @NotNull [] b) {
        final byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);

        return r;
    }

    private static byte @NotNull [] bytes(final @NotNull ByteBuffer buf) {
        final byte[] r = new byte[buf.remaining()];
        buf.get(r);

        return r;
    }

    private static @NotNull ByteBuffer read(final @NotNull FileChannel ch, final long pos, final int length) throws IOException {
        if (pos < 0 || length < 0 || pos + length > ch.size()) {
            throw new EOFException("Outside the ISO image");
        }
        final ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.hasRemaining()) {
            if (ch.read(buf, pos + buf.position()) < 0) {
                throw new EOFException("Truncated ISO image");
            }
        }

        return buf.flip();
    }
}
