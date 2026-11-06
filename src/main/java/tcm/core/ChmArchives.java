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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Compiled HTML Help (.chm) — read only. The directory (PMGL chunks) lists the files; section 0 stores them as is, section 1
 * ("MSCompressed") is one LZX stream reset every few frames, so a file is decoded from the reset point before it, by the
 * reset table. Internal "::" entries are hidden. Decoded frames are cached a little: pages are usually read one after another.
 */
final class ChmArchives {

    private static final int ITSF = 0x46535449;
    private static final int ITSP = 0x50535449;
    private static final int PMGL = 0x4C474D50;
    private static final int LZXC = 0x43585A4C;
    private static final String STORAGE = "::DataSpace/Storage/MSCompressed/";
    private static final String CONTENT = STORAGE + "Content";
    private static final String CONTROL = STORAGE + "ControlData";
    private static final String RESET_TABLE = STORAGE + "Transform/{7FC28940-9D31-11D0-9B27-00A0C91E9C7C}/InstanceData/ResetTable";
    private static final int CACHED_FRAMES = 64;
    private static final int READ_PIECE = 65536;

    private ChmArchives() {
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 4 && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == ITSF;
    }

    /** A directory entry: section 0 (stored) or 1 (compressed), offset and length within the section. */
    private static final class Item {

        private final int section;
        private final long offset;
        private final long length;

        Item(final int section, final long offset, final long length) {
            this.section = section;
            this.offset = offset;
            this.length = length;
        }
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        try {
            final BlockSource src = BlockSource.of(ch);
            final ByteBuffer h = le(src.read(0, Math.min((int) src.size(), 0x60)));
            if (h.capacity() < 0x58 || h.getInt(0) != ITSF) {
                throw new IOException("Not a CHM file: " + archive.getFileName());
            }
            final long dirOffset = h.getLong(0x48);
            final long dirLength = h.getLong(0x50);
            final long contentOffset = h.getInt(4) >= 3 && h.capacity() >= 0x60 ? h.getLong(0x58) : dirOffset + dirLength;
            final Map<String, Item> items = directory(src, dirOffset);
            final Map<String, Item> byName = new HashMap<>();
            for (final Map.Entry<String, Item> e : items.entrySet()) {
                final String name = e.getKey();
                if (name.startsWith("/") && name.length() > 1) {
                    final boolean dir = name.endsWith("/");
                    index.add(name, dir, e.getValue().length, 0, "");
                    if (!dir) {
                        byName.put(ArchiveIndex.entryName(name), e.getValue());
                    }
                }
            }
            final BlockSource stored = range(src, contentOffset, src.size() - contentOffset);
            final Compressed compressed = compressed(items, stored);
            index.setTar(new ArchiveReader() {
                @Override
                public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                    final Item item = byName.get(entry);
                    if (item == null) {
                        throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                    }
                    if (item.section == 0) {
                        return BlockSource.stream(stored, item.offset, item.length);
                    }
                    if (compressed == null) {
                        throw new IOException("No compressed section in " + archive.getFileName());
                    }

                    return BlockSource.stream(compressed, item.offset, item.length);
                }

                @Override
                public void close() throws IOException {
                    ch.close();
                }
            });
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException io ? io : new IOException("Not a valid CHM file: " + archive.getFileName(), e);
        }
    }

    private static @NotNull ByteBuffer le(final byte @NotNull [] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** All entries of the PMGL (listing) chunks; the index chunks (PMGI) are only a search aid. */
    private static @NotNull Map<String, Item> directory(final @NotNull BlockSource src, final long offset) throws IOException {
        final ByteBuffer d = le(src.read(offset, 0x54));
        if (d.getInt(0) != ITSP) {
            throw new IOException("Bad CHM directory");
        }
        final int headerLength = d.getInt(8);
        final int chunkSize = d.getInt(0x10);
        final int chunks = d.getInt(0x2C);
        if (chunkSize < 0x20 || chunks < 0) {
            throw new IOException("Bad CHM directory");
        }
        final Map<String, Item> items = new LinkedHashMap<>();
        for (int c = 0; c < chunks; c++) {
            final byte[] chunk = src.read(offset + headerLength + (long) c * chunkSize, chunkSize);
            final ByteBuffer b = le(chunk);
            if (b.getInt(0) != PMGL) {
                continue;
            }
            final int end = chunkSize - b.getInt(4);
            final int[] pos = {0x14};
            while (pos[0] < end) {
                final int nameLength = (int) encInt(chunk, pos);
                final String name = new String(chunk, pos[0], nameLength, StandardCharsets.UTF_8);
                pos[0] += nameLength;
                final int section = (int) encInt(chunk, pos);
                final long itemOffset = encInt(chunk, pos);
                final long length = encInt(chunk, pos);
                items.put(name, new Item(section, itemOffset, length));
            }
        }

        return items;
    }

    /** Variable-length integer: 7 bits per byte, most significant first, the high bit set on all but the last byte. */
    private static long encInt(final byte @NotNull [] b, final int @NotNull [] pos) {
        long v = 0;
        int x;
        do {
            x = b[pos[0]++];
            v = v << 7 | (x & 0x7F);
        } while ((x & 0x80) != 0);

        return v;
    }

    private static @NotNull BlockSource range(final @NotNull BlockSource src, final long offset, final long length) {
        return new BlockSource() {
            @Override
            public long size() {
                return length;
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                if (pos < 0 || pos + len > length) {
                    throw new EOFException("read past the end of a CHM section");
                }
                src.read(offset + pos, b, off, len);
            }
        };
    }

    private static @Nullable Compressed compressed(final @NotNull Map<String, Item> items, final @NotNull BlockSource stored) throws IOException {
        final Item content = items.get(CONTENT);
        final Item control = items.get(CONTROL);
        final Item reset = items.get(RESET_TABLE);
        if (content == null || control == null || reset == null) {
            return null;
        }
        final ByteBuffer c = le(stored.read(control.offset, (int) Math.min(control.length, 0x1C)));
        if (c.capacity() < 0x18 || c.getInt(4) != LZXC) {
            throw new IOException("Bad CHM LZX control data");
        }
        long resetInterval = c.getInt(0x0C) & 0xFFFFFFFFL;
        long windowSize = c.getInt(0x10) & 0xFFFFFFFFL;
        if (c.getInt(8) == 2) {
            resetInterval *= Lzx.FRAME_SIZE;
            windowSize *= Lzx.FRAME_SIZE;
        }
        final int windowBits = Long.numberOfTrailingZeros(windowSize);
        if (Long.bitCount(windowSize) != 1 || resetInterval == 0 || resetInterval % Lzx.FRAME_SIZE != 0) {
            throw new IOException("Unsupported CHM LZX parameters");
        }
        final ByteBuffer t = le(stored.read(reset.offset, (int) reset.length));
        final int entries = t.getInt(4);
        final int entrySize = t.getInt(8);
        final int tableOffset = t.getInt(0x0C);
        final long[] frames = new long[entries];
        for (int i = 0; i < entries; i++) {
            frames[i] = entrySize == 4 ? t.getInt(tableOffset + i * 4) & 0xFFFFFFFFL : t.getLong(tableOffset + i * entrySize);
        }

        return new Compressed(range(stored, content.offset, content.length), t.getLong(0x10), frames,
            (int) (resetInterval / Lzx.FRAME_SIZE), new Lzx(windowBits));
    }

    /** The decompressed section 1 as a random-access source. */
    private static final class Compressed implements BlockSource {

        private final BlockSource packed;
        private final long size;
        private final long[] frameOffsets;
        private final int resetFrames;
        private final Lzx lzx;
        private final Map<Integer, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Integer, byte[]> eldest) {
                return size() > CACHED_FRAMES;
            }
        };
        /** The frame the decoder can continue with without a reset; -1 — none. */
        private int nextFrame = -1;

        Compressed(
            final @NotNull BlockSource packed,
            final long size,
            final long @NotNull [] frameOffsets,
            final int resetFrames,
            final @NotNull Lzx lzx
        ) {
            this.packed = packed;
            this.size = size;
            this.frameOffsets = frameOffsets;
            this.resetFrames = resetFrames;
            this.lzx = lzx;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public synchronized void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
            if (pos < 0 || pos + len > size) {
                throw new EOFException("read past the end of the CHM compressed section");
            }
            int done = 0;
            while (done < len) {
                final long p = pos + done;
                final byte[] frame = frame((int) (p / Lzx.FRAME_SIZE));
                final int at = (int) (p % Lzx.FRAME_SIZE);
                final int n = Math.min(len - done, frame.length - at);
                System.arraycopy(frame, at, b, off + done, n);
                done += n;
            }
        }

        private byte @NotNull [] frame(final int index) throws IOException {
            final byte[] cached = cache.get(index);
            if (cached != null) {
                return cached;
            }
            int k = index;
            if (index != nextFrame || index % resetFrames == 0) {
                k = index - index % resetFrames;
                seek(k);
            }
            byte[] out = null;
            for (; k <= index; k++) {
                if (k % resetFrames == 0 && k != index - index % resetFrames) {
                    seek(k);
                }
                out = new byte[(int) Math.min(Lzx.FRAME_SIZE, size - (long) k * Lzx.FRAME_SIZE)];
                lzx.decodeFrame(out, 0, out.length, (long) (k + 1) * Lzx.FRAME_SIZE >= size);
                cache.put(k, out);
            }
            nextFrame = index + 1;

            return out;
        }

        /** Resets the decoder and points it at the compressed data of a reset frame. */
        private void seek(final int frame) throws IOException {
            if (frame >= frameOffsets.length) {
                throw new IOException("CHM reset table is too short");
            }
            nextFrame = -1;
            lzx.reset();
            final long[] pos = {frameOffsets[frame]};
            lzx.input(() -> {
                final int n = (int) Math.min(READ_PIECE, packed.size() - pos[0]);
                if (n <= 0) {
                    return null;
                }
                final byte[] piece = packed.read(pos[0], n);
                pos[0] += n;

                return piece;
            });
        }
    }
}
