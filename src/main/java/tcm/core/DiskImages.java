package tcm.core;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Raw disk images (.img, .dd) — read only: a bare file system or a disk with an MBR or GPT partition table. Supported file systems:
 * FAT12/16/32, exFAT, NTFS, ext2/3/4, XFS, Btrfs, ZFS, squashfs, APFS and HFS+. A bare file system is shown at the root; the
 * partitions of a disk are listed as raw .img files named after their labels (file system label, GPT name) or numbers, which open
 * as nested archives. The image stays open for the index's lifetime.
 */
final class DiskImages {

    private static final int SECTOR = 512;
    private static final int MBR_SIGNATURE = 0xAA55;
    private static final int MBR_TABLE = 446;
    private static final int MBR_ENTRIES = 4;
    private static final int TYPE_GPT_PROTECTIVE = 0xEE;
    private static final int MAX_PARTITIONS = 256;
    private static final int GPT_ENTRY_MIN = 128;
    private static final int GPT_NAME = 56;
    private static final int GPT_NAME_LENGTH = 72;

    /** File system readers by signature; FAT, recognized only by the plausibility of its boot sector, goes last. */
    private static final List<Reader> READERS = List.of(
        new Reader(Apfs::matches, DiskImages::apfs),
        new Reader(HfsPlus::matches, HfsPlus::read),
        new Reader(Ntfs::matches, Ntfs::read),
        new Reader(ExFat::matches, ExFat::read),
        new Reader(ExtFs::matches, ExtFs::read),
        new Reader(Xfs::matches, Xfs::read),
        new Reader(Btrfs::matches, Btrfs::read),
        new Reader(DiskImages::isSquashFs, DiskImages::squashFs),
        new Reader(Zfs::matches, Zfs::read),
        new Reader(FatFs::matches, FatFs::read)
    );

    /** A partition or the whole disk. */
    static final class Volume {

        private final int number;
        private final @NotNull String name;
        private final @NotNull BlockSource source;
        /** The partition's own name (GPT); "" — none. */
        private final @NotNull String label;

        Volume(final int number, final @NotNull String name, final @NotNull BlockSource source) {
            this(number, name, source, "");
        }

        Volume(final int number, final @NotNull String name, final @NotNull BlockSource source, final @NotNull String label) {
            this.number = number;
            this.name = name;
            this.source = source;
            this.label = label;
        }
    }

    private DiskImages() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        try {
            index.setTar(listDisk(index, BlockSource.of(ch), ch));
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException io ? io : new IOException("Damaged disk image: " + archive.getFileName(), e);
        }
    }

    /**
     * A whole disk: its partitions as raw .img files, a bare file system at the root. A disk with neither is an error for a raw
     * image and a single raw "disk.img" for a virtual disk (which may hold a layout we do not know).
     */
    static @NotNull ArchiveReader listDisk(
        final @NotNull ArchiveIndex index,
        final @NotNull BlockSource disk,
        final @NotNull Closeable resource
    ) throws IOException {
        final List<Volume> partitions = partitions(disk);
        if (partitions.isEmpty() && (find(disk) != null || index.getFormat() != ArchiveFormat.VIRTUAL_DISK)) {
            return mount(index, List.of(new Volume(1, "disk", disk)), resource, false);
        }
        final Map<String, BlockSource> raw = new LinkedHashMap<>();
        if (partitions.isEmpty()) {
            raw.put("disk.img", disk);
        }
        final Set<String> used = new HashSet<>();
        for (final Volume v : partitions) {
            if (v.source.size() <= 0) {
                continue;
            }
            final String base = partitionName(v);
            String name = base + ".img";
            for (int i = 2; !used.add(name.toLowerCase(Locale.ROOT)); i++) {
                name = String.format(Locale.ROOT, "%s (%d).img", base, i);
            }
            raw.put(name, v.source);
        }
        for (final Map.Entry<String, BlockSource> e : raw.entrySet()) {
            index.add(e.getKey(), false, e.getValue().size(), 0, "");
        }

        return reader(index.getArchive(), Map.of(), raw, resource);
    }

    /** File system label, else the partition's GPT name, else "partition" and its number. */
    private static @NotNull String partitionName(final @NotNull Volume v) {
        String name;
        try {
            name = clean(label(v.source));
        } catch (final IOException | RuntimeException e) {
            // an unreadable boot sector: the partition is still listed
            name = "";
        }
        if (name.isEmpty()) {
            name = clean(v.label);
        }
        // Windows drops trailing dots and spaces from file names: the temporary copy would get another name
        name = name.replaceAll("[. ]+$", "");
        if (name.isEmpty()) {
            name = "partition" + v.number;
        }

        return name;
    }

    /** The volume label from the superblock, without reading the file system tree; "" — none or not known. */
    static @NotNull String label(final @NotNull BlockSource dev) throws IOException {
        if (ExtFs.matches(dev)) {
            return cString(dev.read(1024 + 120, 16), StandardCharsets.UTF_8);
        }
        if (Btrfs.matches(dev)) {
            return cString(dev.read(65536 + 299, 256), StandardCharsets.UTF_8);
        }
        if (Xfs.matches(dev)) {
            return cString(dev.read(108, 12), StandardCharsets.UTF_8);
        }
        if (Ntfs.matches(dev) || ExFat.matches(dev) || !FatFs.matches(dev)) {
            return "";
        }
        final ByteBuffer bpb = le(dev.read(0, 512));
        final boolean fat32 = (bpb.getShort(22) & 0xFFFF) == 0;
        if ((bpb.get(fat32 ? 66 : 38) & 0xFF) != 0x29) {
            return "";
        }
        final String label = ascii(bpb, fat32 ? 71 : 43, 11).strip();

        return "NO NAME".equals(label) ? "" : label;
    }

    private static @NotNull String cString(final byte @NotNull [] b, final @NotNull Charset charset) {
        int n = 0;
        while (n < b.length && b[n] != 0) {
            n++;
        }

        return new String(b, 0, n, charset).strip();
    }

    /**
     * The volume may be opened as an archive: it holds a supported file system, a partition table or an ISO 9660 image.
     * Checked before a partition is unpacked into a temporary file.
     */
    static boolean recognizes(final @NotNull BlockSource dev) {
        try {
            if (find(dev) != null || !partitions(dev).isEmpty()) {
                return true;
            }
            final byte[] iso = peek(dev, 0x8001, 5);

            return iso != null && "CD001".equals(new String(iso, StandardCharsets.ISO_8859_1));
        } catch (final IOException | RuntimeException e) {
            // unreadable: let the full open report it
            return true;
        }
    }

    /** True if the file holds a supported file system or a partition table — for Ctrl+PgDn on an image with another extension. */
    static boolean matches(final @NotNull Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final BlockSource disk = BlockSource.of(ch);

            return find(disk) != null || !partitions(disk).isEmpty();
        } catch (final IOException | RuntimeException e) {
            // a truncated or odd file is just not a disk image: the other signatures are still checked
            return false;
        }
    }

    /** The file system of the volume read in full; null — none of the supported ones. */
    static @Nullable FsTree read(final @NotNull BlockSource dev) throws IOException {
        final Reader reader = find(dev);
        if (reader == null) {
            return null;
        }
        final FsTree tree = reader.read(dev);
        tree.resolveLinks();

        return tree;
    }

    private static @Nullable Reader find(final @NotNull BlockSource dev) throws IOException {
        for (final Reader r : READERS) {
            if (r.matches(dev)) {
                return r;
            }
        }

        return null;
    }

    /**
     * Lists the file systems of the volumes in the index: one at the root, several in directories; with none — the volumes
     * as raw .img files (if listRaw; otherwise that is an error). The reader closes the resource (the image file).
     */
    static @NotNull ArchiveReader mount(
        final @NotNull ArchiveIndex index,
        final @NotNull List<Volume> volumes,
        final @NotNull Closeable resource,
        final boolean listRaw
    ) throws IOException {
        final Map<Volume, FsTree> trees = new LinkedHashMap<>();
        IOException failure = null;
        for (final Volume v : volumes) {
            try {
                final FsTree tree = read(v.source);
                if (tree != null) {
                    trees.put(v, tree);
                }
            } catch (final IOException | RuntimeException e) {
                failure = e instanceof IOException io ? io : new IOException("Damaged file system in " + v.name, e);
            }
        }
        final Map<String, FsTree> mounted = new LinkedHashMap<>();
        final Map<String, BlockSource> raw = new LinkedHashMap<>();
        if (trees.size() == 1) {
            final FsTree tree = trees.values().iterator().next();
            tree.addTo(index, "");
            mounted.put("", tree);
        } else if (!trees.isEmpty()) {
            final Set<String> used = new HashSet<>();
            for (final Map.Entry<Volume, FsTree> e : trees.entrySet()) {
                String name = directoryName(e.getKey(), e.getValue());
                while (!used.add(name)) {
                    name = name + "_";
                }
                index.add(name, true, 0, 0, "");
                e.getValue().addTo(index, name + "/");
                mounted.put(name + "/", e.getValue());
            }
        } else if (failure != null && (volumes.size() == 1 || !listRaw)) {
            throw failure;
        } else if (!listRaw) {
            throw new IOException("No supported file system or partition table in " + index.getArchive().getFileName());
        } else {
            for (final Volume v : volumes) {
                if (v.source.size() > 0) {
                    final String name = rawName(v);
                    raw.put(name, v.source);
                    index.add(name, false, v.source.size(), 0, "");
                }
            }
        }

        return reader(index.getArchive(), mounted, raw, resource);
    }

    private static @NotNull String directoryName(final @NotNull Volume v, final @NotNull FsTree tree) {
        final String label = clean(tree.getLabel());

        return String.format(Locale.ROOT, "%d %s%s", v.number, tree.getType(), label.isEmpty() ? "" : " " + label);
    }

    static @NotNull String rawName(final @NotNull Volume v) {
        final String name = clean(v.name);

        return String.format(Locale.ROOT, "%d %s.img", v.number, name.isEmpty() ? "partition" : name);
    }

    private static @NotNull String clean(final @NotNull String name) {
        return name.replaceAll("[/\\\\:*?\"<>|\\x00-\\x1F]", " ").replaceAll("\\s+", " ").strip();
    }

    private static @NotNull ArchiveReader reader(
        final @NotNull Path archive,
        final @NotNull Map<String, FsTree> mounted,
        final @NotNull Map<String, BlockSource> raw,
        final @NotNull Closeable resource
    ) {
        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final BlockSource partition = raw.get(entry);
                if (partition != null) {
                    return BlockSource.stream(partition, 0, partition.size());
                }
                for (final Map.Entry<String, FsTree> e : mounted.entrySet()) {
                    if (entry.startsWith(e.getKey())) {
                        final InputStream in = e.getValue().open(entry.substring(e.getKey().length()));
                        if (in != null) {
                            return in;
                        }
                    }
                }

                throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
            }

            @Override
            public @Nullable BlockSource source(final @NotNull String entry) {
                return raw.get(entry);
            }

            @Override
            public void close() throws IOException {
                resource.close();
            }
        };
    }

    /** Partitions of a GPT or MBR table; empty — no table (a bare file system or an unknown layout). */
    static @NotNull List<Volume> partitions(final @NotNull BlockSource disk) throws IOException {
        for (final int sector : new int[] {SECTOR, 4096}) {
            final List<Volume> gpt = gpt(disk, sector);
            if (gpt != null) {
                return gpt;
            }
        }
        if (find(disk) != null) {
            return List.of();
        }

        return mbr(disk);
    }

    private static @Nullable List<Volume> gpt(final @NotNull BlockSource disk, final int sector) throws IOException {
        if (disk.size() < sector * 3L) {
            return null;
        }
        final ByteBuffer h = le(disk.read(sector, 92));
        if (!"EFI PART".equals(ascii(h, 0, 8))) {
            return null;
        }
        final long entriesLba = h.getLong(72);
        final int count = Math.min(h.getInt(80), MAX_PARTITIONS);
        final int entrySize = h.getInt(84);
        if (entrySize < GPT_ENTRY_MIN || entrySize > 4096 || count <= 0 || entriesLba <= 0 || entriesLba * sector >= disk.size()) {
            return null;
        }
        final List<Volume> result = new ArrayList<>();
        final byte[] table = disk.read(entriesLba * sector, (int) Math.min((long) count * entrySize, disk.size() - entriesLba * sector));
        for (int i = 0; i < count && (i + 1) * entrySize <= table.length; i++) {
            final ByteBuffer e = le(table).position(i * entrySize).slice().order(ByteOrder.LITTLE_ENDIAN);
            if (e.getLong(0) == 0 && e.getLong(8) == 0) {
                continue;
            }
            final long first = e.getLong(32);
            final long last = e.getLong(40);
            if (first <= 0 || last < first) {
                continue;
            }
            final byte[] name = new byte[GPT_NAME_LENGTH];
            e.get(GPT_NAME, name);
            final String label = new String(name, StandardCharsets.UTF_16LE).replace("\0", "").strip();
            result.add(new Volume(i + 1, label, BlockSource.slice(disk, first * sector, (last - first + 1) * sector), label));
        }

        return result;
    }

    private static @NotNull List<Volume> mbr(final @NotNull BlockSource disk) throws IOException {
        if (disk.size() < SECTOR) {
            return List.of();
        }
        final ByteBuffer s = le(disk.read(0, SECTOR));
        if ((s.getShort(510) & 0xFFFF) != MBR_SIGNATURE) {
            return List.of();
        }
        final List<Volume> result = new ArrayList<>();
        for (int i = 0; i < MBR_ENTRIES; i++) {
            final int e = MBR_TABLE + i * 16;
            final int status = s.get(e) & 0xFF;
            final int type = s.get(e + 4) & 0xFF;
            final long start = Integer.toUnsignedLong(s.getInt(e + 8));
            final long count = Integer.toUnsignedLong(s.getInt(e + 12));
            if (status != 0 && status != 0x80) {
                // boot code or a boot sector of a file system, not a partition table
                return List.of();
            }
            if (type == 0 || count == 0) {
                continue;
            }
            if (type == TYPE_GPT_PROTECTIVE || start * SECTOR >= disk.size()) {
                return List.of();
            }
            if (isExtended(type)) {
                logical(disk, start, result);
            } else {
                result.add(new Volume(i + 1, mbrType(type), BlockSource.slice(disk, start * SECTOR, count * SECTOR)));
            }
        }

        return result;
    }

    private static boolean isExtended(final int type) {
        return type == 0x05 || type == 0x0F || type == 0x85;
    }

    /** Logical partitions: a chain of extended boot records, numbered from 5 as Linux does. */
    private static void logical(final @NotNull BlockSource disk, final long extended, final @NotNull List<Volume> result) throws IOException {
        long ebr = extended;
        int number = 5;
        final Set<Long> seen = new HashSet<>();
        while (ebr > 0 && ebr * SECTOR + SECTOR <= disk.size() && seen.add(ebr) && result.size() < MAX_PARTITIONS) {
            final ByteBuffer s = le(disk.read(ebr * SECTOR, SECTOR));
            if ((s.getShort(510) & 0xFFFF) != MBR_SIGNATURE) {
                return;
            }
            final int type = s.get(MBR_TABLE + 4) & 0xFF;
            final long start = Integer.toUnsignedLong(s.getInt(MBR_TABLE + 8));
            final long count = Integer.toUnsignedLong(s.getInt(MBR_TABLE + 12));
            if (type != 0 && count > 0) {
                result.add(new Volume(number++, mbrType(type), BlockSource.slice(disk, (ebr + start) * SECTOR, count * SECTOR)));
            }
            final long next = Integer.toUnsignedLong(s.getInt(MBR_TABLE + 16 + 8));
            ebr = next == 0 ? 0 : extended + next;
        }
    }

    private static @NotNull String mbrType(final int type) {
        return switch (type) {
            case 0x01, 0x04, 0x06, 0x0B, 0x0C, 0x0E -> "FAT";
            case 0x07 -> "NTFS";
            case 0x82 -> "Linux swap";
            case 0x83 -> "Linux";
            case 0x8E -> "Linux LVM";
            case 0xA5 -> "FreeBSD";
            case 0xAF -> "HFS";
            case 0xEF -> "EFI";
            case 0xFD -> "Linux RAID";
            default -> String.format(Locale.ROOT, "type %02X", type);
        };
    }

    private static boolean isSquashFs(final @NotNull BlockSource dev) throws IOException {
        return dev.size() >= 96 && "hsqs".equals(new String(dev.read(0, 4), StandardCharsets.ISO_8859_1));
    }

    private static @NotNull FsTree squashFs(final @NotNull BlockSource dev) throws IOException {
        final SquashFs fs = SquashFs.open(dev, 0);
        if (fs == null) {
            throw new IOException("unsupported squashfs version");
        }
        final FsTree tree = new FsTree("squashfs");
        fs.walk((path, directory, size, modified, mode) -> {
            if (directory) {
                tree.dir(path, modified, FileAttrs.posix(mode));
            } else {
                tree.file(path, size, modified, FileAttrs.posix(mode), () -> orEmpty(fs.open(path)));
            }
        });

        return tree;
    }

    private static @NotNull FsTree apfs(final @NotNull BlockSource dev) throws IOException {
        final Apfs fs = Apfs.open(dev);
        final FsTree tree = new FsTree("APFS");
        fs.walk((path, directory, size, modified, mode) -> {
            if (directory) {
                tree.dir(path, modified, FileAttrs.posix(mode));
            } else {
                tree.file(path, size, modified, FileAttrs.posix(mode), () -> orEmpty(fs.open(path)));
            }
        });

        return tree;
    }

    private static @NotNull InputStream orEmpty(final @Nullable InputStream in) {
        return in != null ? in : InputStream.nullInputStream();
    }

    /** A file system reader: its signature check and parser. */
    private static final class Reader {

        private final @NotNull Matcher matcher;
        private final @NotNull Parser parser;

        private Reader(final @NotNull Matcher matcher, final @NotNull Parser parser) {
            this.matcher = matcher;
            this.parser = parser;
        }

        private boolean matches(final @NotNull BlockSource dev) throws IOException {
            return matcher.matches(dev);
        }

        private @NotNull FsTree read(final @NotNull BlockSource dev) throws IOException {
            return parser.read(dev);
        }
    }

    @FunctionalInterface
    private interface Matcher {

        boolean matches(@NotNull BlockSource dev) throws IOException;
    }

    @FunctionalInterface
    private interface Parser {

        @NotNull FsTree read(@NotNull BlockSource dev) throws IOException;
    }

    static @NotNull ByteBuffer le(final byte @NotNull [] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    static @NotNull String ascii(final @NotNull ByteBuffer b, final int off, final int len) {
        final byte[] s = new byte[len];
        b.get(off, s);

        return new String(s, StandardCharsets.ISO_8859_1);
    }

    static @NotNull String utf16(final @NotNull ByteBuffer b, final int off, final int chars) {
        final byte[] s = new byte[chars * 2];
        b.get(off, s);

        return new String(s, StandardCharsets.UTF_16LE);
    }

    /** Bytes at pos or null if the device is shorter — for signature checks. */
    static byte @Nullable [] peek(final @NotNull BlockSource dev, final long pos, final int len) throws IOException {
        return pos >= 0 && pos + len <= dev.size() ? dev.read(pos, len) : null;
    }
}
