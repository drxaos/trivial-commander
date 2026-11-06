package tcm.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * RPM packages — read only: the files of the cpio payload (newc format) after the lead and the signature and main headers.
 * The payload (gzip, bzip2, xz, lzma or zstd) is decompressed once into a temporary file, deleted when the index is disposed.
 * Symbolic links are shown as empty files, as in TAR; hard links read the data stored with the last link.
 */
final class RpmArchives {

    private static final int LEAD_SIZE = 96;
    private static final int HEADER_MAGIC = 0x8EADE801;
    private static final int CPIO_HEADER = 110;
    private static final String TRAILER = "TRAILER!!!";
    private static final int TYPE_MASK = 0170000;
    private static final int TYPE_DIR = 0040000;
    private static final int TYPE_FILE = 0100000;

    private RpmArchives() {
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 4 && (head[0] & 0xFF) == 0xED && (head[1] & 0xFF) == 0xAB && (head[2] & 0xFF) == 0xEE && (head[3] & 0xFF) == 0xDB;
    }

    /** A file's data in the decompressed payload. */
    private static final class Item {

        private final long inode;
        private final int links;
        private long offset;
        private long size;

        Item(final long inode, final int links, final long offset, final long size) {
            this.inode = inode;
            this.links = links;
            this.offset = offset;
            this.size = size;
        }
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final FileChannel payload;
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            final BlockSource src = BlockSource.of(ch);
            if (src.size() < LEAD_SIZE || !matches(src.read(0, 4))) {
                throw new IOException("Not an RPM package: " + archive.getFileName());
            }
            long pos = LEAD_SIZE;
            // the signature header is padded to 8 bytes, the main header is not
            pos += headerSize(src, pos);
            pos = (pos + 7) & ~7L;
            pos += headerSize(src, pos);
            payload = Compression.spill(Compression.decompress(BlockSource.stream(src, pos, src.size() - pos)));
        }
        try {
            final Map<String, Item> byName = readCpio(index, BlockSource.of(payload));
            index.setTar(reader(archive, payload, byName));
        } catch (final IOException | RuntimeException e) {
            payload.close();
            throw e instanceof IOException io ? io : new IOException("Not a valid RPM package: " + archive.getFileName(), e);
        }
    }

    private static long headerSize(final @NotNull BlockSource src, final long pos) throws IOException {
        final ByteBuffer h = ByteBuffer.wrap(src.read(pos, 16));
        if (h.getInt(0) != HEADER_MAGIC) {
            throw new IOException("Bad RPM header");
        }

        return 16 + 16L * (h.getInt(8) & 0xFFFFFFFFL) + (h.getInt(12) & 0xFFFFFFFFL);
    }

    private static @NotNull Map<String, Item> readCpio(final @NotNull ArchiveIndex index, final @NotNull BlockSource cpio) throws IOException {
        final Map<String, Item> byName = new HashMap<>();
        final List<Item> linked = new ArrayList<>();
        long pos = 0;
        while (pos + CPIO_HEADER <= cpio.size()) {
            final String h = new String(cpio.read(pos, CPIO_HEADER), StandardCharsets.ISO_8859_1);
            if (!h.startsWith("070701") && !h.startsWith("070702")) {
                throw new IOException("Unsupported RPM payload (not cpio newc)");
            }
            final long inode = hex(h, 0);
            final int mode = (int) hex(h, 1);
            final int links = (int) hex(h, 4);
            final long mtime = hex(h, 5);
            final long size = hex(h, 6);
            final int nameSize = (int) hex(h, 11);
            final String name = new String(cpio.read(pos + CPIO_HEADER, Math.max(0, nameSize - 1)), StandardCharsets.UTF_8);
            final long data = align4(pos + CPIO_HEADER + nameSize);
            if (TRAILER.equals(name)) {
                break;
            }
            final int type = mode & TYPE_MASK;
            final boolean dir = type == TYPE_DIR;
            index.add(name, dir, type == TYPE_FILE ? size : 0, mtime * 1000, FileAttrs.posix(mode));
            if (!dir) {
                final Item item = new Item(inode, links, data, type == TYPE_FILE ? size : 0);
                byName.put(ArchiveIndex.entryName(name), item);
                if (type == TYPE_FILE && links > 1) {
                    linked.add(item);
                }
            }
            pos = align4(data + size);
        }
        resolveHardLinks(index, byName, linked);

        return byName;
    }

    /** All links of a file but the last one come with no data: they share it. */
    private static void resolveHardLinks(
        final @NotNull ArchiveIndex index,
        final @NotNull Map<String, Item> byName,
        final @NotNull List<Item> linked
    ) {
        final Map<Long, Item> withData = new HashMap<>();
        for (final Item item : linked) {
            if (item.size > 0) {
                withData.put(item.inode, item);
            }
        }
        for (final Map.Entry<String, Item> e : byName.entrySet()) {
            final Item item = e.getValue();
            final Item source = item.links > 1 && item.size == 0 ? withData.get(item.inode) : null;
            if (source != null) {
                item.offset = source.offset;
                item.size = source.size;
                final VFile f = index.get(e.getKey());
                if (f != null) {
                    index.add(e.getKey(), false, source.size, f.getModified(), f.getAttrs());
                }
            }
        }
    }

    private static long hex(final @NotNull String header, final int field) throws IOException {
        try {
            return Long.parseLong(header, 6 + field * 8, 14 + field * 8, 16);
        } catch (final NumberFormatException e) {
            throw new IOException("Bad cpio header", e);
        }
    }

    private static long align4(final long pos) {
        return (pos + 3) & ~3L;
    }

    private static @NotNull ArchiveReader reader(
        final @NotNull Path archive,
        final @NotNull FileChannel payload,
        final @NotNull Map<String, Item> byName
    ) throws IOException {
        final BlockSource src = BlockSource.of(payload);

        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final Item item = byName.get(entry);
                if (item == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }

                return BlockSource.stream(src, item.offset, item.size);
            }

            @Override
            public void close() throws IOException {
                payload.close();
            }
        };
    }
}
