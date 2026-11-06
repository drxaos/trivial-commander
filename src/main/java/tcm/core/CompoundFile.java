package tcm.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/**
 * A Compound File Binary (OLE2 structured storage: MSI packages, old Office documents): a FAT file system inside a file.
 * Streams shorter than the cutoff live in the mini stream with 64-byte sectors. Read only.
 */
final class CompoundFile {

    private static final long SIGNATURE = 0xE11AB1A1E011CFD0L;
    private static final int END_OF_CHAIN = -2;
    private static final int FREE = -1;
    private static final int HEADER_DIFAT = 109;
    private static final int DIR_ENTRY = 128;
    private static final int TYPE_STORAGE = 1;
    private static final int TYPE_STREAM = 2;
    private static final int TYPE_ROOT = 5;
    private static final int NO_STREAM = -1;

    private final BlockSource file;
    private final int sectorShift;
    private final int miniShift;
    private final long miniCutoff;
    private final int[] fat;
    private int[] miniFat = new int[0];
    private BlockSource miniStream;
    @Getter
    private final List<Stream> streams = new ArrayList<>();

    /** A stream with its path inside the storage tree ("dir/name"). */
    @Getter
    static final class Stream {

        private final String path;
        private final long size;
        private final BlockSource data;

        Stream(final @NotNull String path, final long size, final @NotNull BlockSource data) {
            this.path = path;
            this.size = size;
            this.data = data;
        }
    }

    private CompoundFile(final @NotNull BlockSource file, final int sectorShift, final int miniShift, final long miniCutoff, final int[] fat) {
        this.file = file;
        this.sectorShift = sectorShift;
        this.miniShift = miniShift;
        this.miniCutoff = miniCutoff;
        this.fat = fat;
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 8 && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(0) == SIGNATURE;
    }

    /** Parses the header, the FAT and the directory; names of nested storages are joined with '/'. */
    static @NotNull CompoundFile open(final @NotNull BlockSource file) throws IOException {
        if (file.size() < 512) {
            throw new IOException("Not a compound file");
        }
        final ByteBuffer h = le(file.read(0, 512));
        if (h.getLong(0) != SIGNATURE) {
            throw new IOException("Not a compound file");
        }
        final int sectorShift = h.getShort(0x1E);
        final int miniShift = h.getShort(0x20);
        if (sectorShift < 7 || sectorShift > 16 || miniShift < 1 || miniShift >= sectorShift) {
            throw new IOException("Bad compound file sector size");
        }
        final int fatSectors = h.getInt(0x2C);
        final int perSector = (1 << sectorShift) / 4;
        final int[] fatList = new int[Math.max(0, fatSectors)];
        int n = 0;
        for (int i = 0; i < HEADER_DIFAT && n < fatList.length; i++) {
            fatList[n++] = h.getInt(0x4C + i * 4);
        }
        int difat = h.getInt(0x44);
        final Set<Integer> seen = new HashSet<>();
        while (n < fatList.length && difat >= 0 && seen.add(difat)) {
            final ByteBuffer d = le(file.read((difat + 1L) << sectorShift, 1 << sectorShift));
            for (int i = 0; i < perSector - 1 && n < fatList.length; i++) {
                fatList[n++] = d.getInt(i * 4);
            }
            difat = d.getInt((perSector - 1) * 4);
        }
        final int[] fat = new int[n * perSector];
        for (int i = 0; i < n; i++) {
            final ByteBuffer s = le(file.read((fatList[i] + 1L) << sectorShift, 1 << sectorShift));
            for (int j = 0; j < perSector; j++) {
                fat[i * perSector + j] = s.getInt(j * 4);
            }
        }
        final CompoundFile cf = new CompoundFile(file, sectorShift, miniShift, h.getInt(0x38) & 0xFFFFFFFFL, fat);
        cf.readDirectory(h.getInt(0x30), h.getInt(0x3C));

        return cf;
    }

    private static @NotNull ByteBuffer le(final byte @NotNull [] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void readDirectory(final int firstDirSector, final int firstMiniFatSector) throws IOException {
        final int[] dirChain = chain(fat, firstDirSector);
        final BlockSource dir = new Chain(file, dirChain, sectorShift, true, (long) dirChain.length << sectorShift);
        final int count = (int) (dir.size() / DIR_ENTRY);
        final ByteBuffer d = le(dir.read(0, count * DIR_ENTRY));
        if (count == 0 || (d.get(0x42) & 0xFF) != TYPE_ROOT) {
            throw new IOException("Bad compound file directory");
        }
        final int[] miniChain = chain(fat, firstMiniFatSector);
        final BlockSource miniFatData = new Chain(file, miniChain, sectorShift, true, (long) miniChain.length << sectorShift);
        final ByteBuffer m = le(miniFatData.read(0, (int) miniFatData.size()));
        miniFat = new int[(int) miniFatData.size() / 4];
        for (int i = 0; i < miniFat.length; i++) {
            miniFat[i] = m.getInt(i * 4);
        }
        miniStream = new Chain(file, chain(fat, d.getInt(0x74)), sectorShift, true, size(d, 0));
        walk(d, count, d.getInt(0x4C), "", new HashSet<>());
    }

    /** In-order walk of the red-black tree of siblings, descending into storages. */
    private void walk(final @NotNull ByteBuffer d, final int count, final int id, final @NotNull String prefix, final @NotNull Set<Integer> seen)
        throws IOException {
        if (id < 0 || id >= count || !seen.add(id)) {
            return;
        }
        final int base = id * DIR_ENTRY;
        walk(d, count, d.getInt(base + 0x44), prefix, seen);
        final int nameBytes = Math.max(0, Math.min(64, d.getShort(base + 0x40)) - 2);
        final byte[] raw = new byte[nameBytes];
        d.get(base, raw);
        final String path = prefix + new String(raw, StandardCharsets.UTF_16LE);
        final int type = d.get(base + 0x42) & 0xFF;
        if (type == TYPE_STREAM) {
            final long size = size(d, base);
            final int start = d.getInt(base + 0x74);
            final BlockSource data = size < miniCutoff
                ? new Chain(miniStream, chain(miniFat, start), miniShift, false, size)
                : new Chain(file, chain(fat, start), sectorShift, true, size);
            streams.add(new Stream(path, size, data));
        } else if (type == TYPE_STORAGE) {
            walk(d, count, d.getInt(base + 0x4C), path + "/", seen);
        }
        walk(d, count, d.getInt(base + 0x48), prefix, seen);
    }

    /** Version 3 files (512-byte sectors) may leave garbage in the high half of the size. */
    private long size(final @NotNull ByteBuffer d, final int base) {
        return sectorShift == 9 ? d.getInt(base + 0x78) & 0xFFFFFFFFL : d.getLong(base + 0x78);
    }

    private static int @NotNull [] chain(final int @NotNull [] table, final int start) throws IOException {
        final List<Integer> list = new ArrayList<>();
        int s = start;
        while (s != END_OF_CHAIN && s != FREE && s != NO_STREAM) {
            if (s < 0 || s >= table.length || list.size() > table.length) {
                throw new IOException("Bad compound file sector chain");
            }
            list.add(s);
            s = table[s];
        }

        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A stream as a chain of sectors of the file (the header takes sector -1) or of the mini stream. */
    private static final class Chain implements BlockSource {

        private final BlockSource base;
        private final int[] sectors;
        private final int shift;
        private final boolean afterHeader;
        private final long size;

        Chain(final @NotNull BlockSource base, final int @NotNull [] sectors, final int shift, final boolean afterHeader, final long size)
            throws IOException {
            if (size > (long) sectors.length << shift || size < 0) {
                throw new IOException("Compound file stream is longer than its sector chain");
            }
            this.base = base;
            this.sectors = sectors;
            this.shift = shift;
            this.afterHeader = afterHeader;
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
            if (pos < 0 || pos + len > size) {
                throw new EOFException("read past the end of a compound file stream");
            }
            int done = 0;
            while (done < len) {
                final long p = pos + done;
                final int sector = sectors[(int) (p >>> shift)];
                final int at = (int) (p & ((1 << shift) - 1));
                final int n = Math.min(len - done, (1 << shift) - at);
                base.read(((long) sector + (afterHeader ? 1 : 0) << shift) + at, b, off + done, n);
                done += n;
            }
        }
    }
}
