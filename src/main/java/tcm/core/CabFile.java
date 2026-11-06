package tcm.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * A Microsoft cabinet (.cab, also embedded in MSI packages). Files are stored in folders — solid streams compressed as a whole
 * (stored, MSZIP or LZX; Quantum is not supported); a folder is decoded on demand into a {@link SpillCache}. Files continued
 * from or into another cabinet of a set are skipped.
 */
final class CabFile implements Closeable {

    private static final int SIGNATURE = 0x4643534D;
    private static final int HEADER_SIZE = 36;
    private static final int FLAG_PREV = 0x0001;
    private static final int FLAG_NEXT = 0x0002;
    private static final int FLAG_RESERVE = 0x0004;
    private static final int ATTR_UTF8 = 0x80;
    private static final int FOLDER_CONTINUED = 0xFFFD;
    private static final int COMPRESS_MASK = 0x000F;
    private static final int COMPRESS_NONE = 0;
    private static final int COMPRESS_MSZIP = 1;
    private static final int COMPRESS_QUANTUM = 2;
    private static final int COMPRESS_LZX = 3;
    private static final int DATA_HEADER = 8;
    private static final int MSZIP_HISTORY = 32768;
    private static final Charset OEM = Charset.forName("IBM437");

    @Getter
    private final List<Entry> entries = new ArrayList<>();
    private final BlockSource source;
    private final List<Folder> folders = new ArrayList<>();
    private int dataReserve;

    private CabFile(final @NotNull BlockSource source) {
        this.source = source;
    }

    /** A file of the cabinet: a range of the uncompressed stream of its folder. */
    @Getter
    static final class Entry {

        private final String name;
        private final long size;
        private final long time;
        private final int attributes;
        private final int folder;
        private final long offset;

        private Entry(final @NotNull String name, final long size, final long time, final int attributes, final int folder, final long offset) {
            this.name = name;
            this.size = size;
            this.time = time;
            this.attributes = attributes;
            this.folder = folder;
            this.offset = offset;
        }
    }

    private static final class Folder {

        private final long start;
        private final int blocks;
        private final int compression;
        private @Nullable SpillCache cache;

        Folder(final long start, final int blocks, final int compression) {
            this.start = start;
            this.blocks = blocks;
            this.compression = compression;
        }
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 4 && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == SIGNATURE;
    }

    static @NotNull CabFile open(final @NotNull BlockSource source) throws IOException {
        final ByteBuffer h = ByteBuffer.wrap(source.read(0, (int) Math.min(source.size(), HEADER_SIZE + 4))).order(ByteOrder.LITTLE_ENDIAN);
        if (h.capacity() < HEADER_SIZE || h.getInt(0) != SIGNATURE) {
            throw new IOException("Not a cabinet file");
        }
        final CabFile cab = new CabFile(source);
        final long filesOffset = h.getInt(16) & 0xFFFFFFFFL;
        final int folderCount = h.getShort(26) & 0xFFFF;
        final int fileCount = h.getShort(28) & 0xFFFF;
        final int flags = h.getShort(30) & 0xFFFF;
        long pos = HEADER_SIZE;
        int folderReserve = 0;
        if ((flags & FLAG_RESERVE) != 0) {
            final int headerReserve = h.getShort(HEADER_SIZE) & 0xFFFF;
            folderReserve = h.get(HEADER_SIZE + 2) & 0xFF;
            cab.dataReserve = h.get(HEADER_SIZE + 3) & 0xFF;
            pos += 4 + headerReserve;
        }
        // names of the previous/next cabinet and disk
        for (int i = 0; i < ((flags & FLAG_PREV) != 0 ? 2 : 0) + ((flags & FLAG_NEXT) != 0 ? 2 : 0); i++) {
            pos += cab.stringLength(pos) + 1;
        }
        for (int i = 0; i < folderCount; i++) {
            final ByteBuffer f = ByteBuffer.wrap(source.read(pos, 8)).order(ByteOrder.LITTLE_ENDIAN);
            cab.folders.add(new Folder(f.getInt(0) & 0xFFFFFFFFL, f.getShort(4) & 0xFFFF, f.getShort(6) & 0xFFFF));
            pos += 8 + folderReserve;
        }
        pos = filesOffset;
        for (int i = 0; i < fileCount; i++) {
            final ByteBuffer f = ByteBuffer.wrap(source.read(pos, 16)).order(ByteOrder.LITTLE_ENDIAN);
            final int nameLength = cab.stringLength(pos + 16);
            final int attributes = f.getShort(14) & 0xFFFF;
            final String name = new String(source.read(pos + 16, nameLength), (attributes & ATTR_UTF8) != 0 ? StandardCharsets.UTF_8 : OEM);
            final int folder = f.getShort(8) & 0xFFFF;
            pos += 16 + nameLength + 1;
            if (folder < FOLDER_CONTINUED && folder < folderCount) {
                final long time = dosTime(f.getShort(10) & 0xFFFF, f.getShort(12) & 0xFFFF);
                cab.entries.add(new Entry(name, f.getInt(0) & 0xFFFFFFFFL, time, attributes, folder, f.getInt(4) & 0xFFFFFFFFL));
            }
        }

        return cab;
    }

    /** Length of the zero-terminated string at pos (at most 256 bytes long in a valid cabinet). */
    private int stringLength(final long pos) throws IOException {
        final byte[] b = source.read(pos, (int) Math.min(257, source.size() - pos));
        for (int i = 0; i < b.length; i++) {
            if (b[i] == 0) {
                return i;
            }
        }

        throw new IOException("Bad cabinet string");
    }

    /** Local time from DOS date and time words; 0 — invalid. */
    private static long dosTime(final int date, final int time) {
        try {
            return LocalDateTime.of(1980 + (date >> 9), date >> 5 & 15, date & 31, time >> 11, time >> 5 & 63, (time & 31) * 2)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        } catch (final DateTimeException e) {
            return 0;
        }
    }

    @NotNull InputStream open(final @NotNull Entry entry) throws IOException {
        return BlockSource.stream(folder(entry.folder), entry.offset, entry.size);
    }

    private synchronized @NotNull SpillCache folder(final int index) throws IOException {
        final Folder folder = folders.get(index);
        if (folder.cache == null) {
            final long[] offsets = new long[folder.blocks];
            final int[] packed = new int[folder.blocks];
            final int[] unpacked = new int[folder.blocks];
            long pos = folder.start;
            long size = 0;
            for (int i = 0; i < folder.blocks; i++) {
                final ByteBuffer d = ByteBuffer.wrap(source.read(pos, DATA_HEADER)).order(ByteOrder.LITTLE_ENDIAN);
                packed[i] = d.getShort(4) & 0xFFFF;
                unpacked[i] = d.getShort(6) & 0xFFFF;
                offsets[i] = pos + DATA_HEADER + dataReserve;
                pos = offsets[i] + packed[i];
                size += unpacked[i];
            }
            folder.cache = new SpillCache(decoder(folder.compression, offsets, packed, unpacked), size, Lzx.FRAME_SIZE * 2);
        }

        return folder.cache;
    }

    private @NotNull SpillCache.Decoder decoder(
        final int compression,
        final long @NotNull [] offsets,
        final int @NotNull [] packed,
        final int @NotNull [] unpacked
    ) throws IOException {
        return switch (compression & COMPRESS_MASK) {
            case COMPRESS_NONE -> new SpillCache.Decoder() {
                private int block;

                @Override
                public int next(final byte @NotNull [] buf) throws IOException {
                    if (block >= offsets.length) {
                        return -1;
                    }
                    source.read(offsets[block], buf, 0, packed[block]);

                    return packed[block++];
                }
            };
            case COMPRESS_MSZIP -> mszip(offsets, packed, unpacked);
            case COMPRESS_LZX -> lzx(compression >> 8 & 0x1F, offsets, packed, unpacked);
            case COMPRESS_QUANTUM -> throw new IOException("Quantum compression is not supported");
            default -> throw new IOException("Unknown cabinet compression: " + compression);
        };
    }

    /** Each block is a deflate stream ("CK" first) whose dictionary is the last 32 KiB of the previous blocks. */
    private @NotNull SpillCache.Decoder mszip(final long @NotNull [] offsets, final int @NotNull [] packed, final int @NotNull [] unpacked) {
        return new SpillCache.Decoder() {
            private int block;
            private byte @Nullable [] history;

            @Override
            public int next(final byte @NotNull [] buf) throws IOException {
                if (block >= offsets.length) {
                    return -1;
                }
                final byte[] data = source.read(offsets[block], packed[block]);
                final int size = unpacked[block];
                block++;
                if (data.length < 2 || data[0] != 'C' || data[1] != 'K') {
                    throw new IOException("Bad MSZIP block");
                }
                final Inflater inflater = new Inflater(true);
                try {
                    if (history != null) {
                        inflater.setDictionary(history);
                    }
                    inflater.setInput(data, 2, data.length - 2);
                    int n = 0;
                    while (n < size && !inflater.finished()) {
                        final int k = inflater.inflate(buf, n, size - n);
                        if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                            break;
                        }
                        n += k;
                    }
                    if (n != size) {
                        throw new IOException("Bad MSZIP block size");
                    }
                } catch (final DataFormatException e) {
                    throw new IOException("Corrupted MSZIP data", e);
                } finally {
                    inflater.end();
                }
                history = history(history, buf, size);

                return size;
            }
        };
    }

    private static byte @NotNull [] history(final byte @Nullable [] old, final byte @NotNull [] buf, final int size) {
        if (size >= MSZIP_HISTORY || old == null) {
            return Arrays.copyOfRange(buf, Math.max(0, size - MSZIP_HISTORY), size);
        }
        final int keep = Math.min(old.length, MSZIP_HISTORY - size);
        final byte[] h = Arrays.copyOfRange(old, old.length - keep, old.length + size);
        System.arraycopy(buf, 0, h, keep, size);

        return h;
    }

    /** One continuous LZX stream over all data blocks; each block decodes into one frame. */
    private @NotNull SpillCache.Decoder lzx(
        final int windowBits,
        final long @NotNull [] offsets,
        final int @NotNull [] packed,
        final int @NotNull [] unpacked
    ) throws IOException {
        final Lzx lzx = new Lzx(windowBits);
        lzx.input(new Lzx.Input() {
            private int block;

            @Override
            public byte @Nullable [] next() throws IOException {
                return block < offsets.length ? source.read(offsets[block], packed[block++]) : null;
            }
        });

        return new SpillCache.Decoder() {
            private int frame;

            @Override
            public int next(final byte @NotNull [] buf) throws IOException {
                if (frame >= unpacked.length) {
                    return -1;
                }
                final int size = unpacked[frame++];
                lzx.decodeFrame(buf, 0, size, frame == unpacked.length);

                return size;
            }
        };
    }

    @Override
    public synchronized void close() throws IOException {
        for (final Folder f : folders) {
            if (f.cache != null) {
                f.cache.close();
            }
        }
    }
}
