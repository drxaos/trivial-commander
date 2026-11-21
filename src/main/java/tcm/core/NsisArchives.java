package tcm.core;

import java.io.BufferedInputStream;
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
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.X86Options;

/**
 * Nullsoft (NSIS 2 and 3) installers — read only: the files the script extracts (File commands), named as 7-Zip names them:
 * paths under $INSTDIR from the root, others with the variable ($PLUGINSDIR/..., $TEMP/...). The installer data is cut out of
 * the .exe by {@link EmbeddedArchives}: offsets here count from the "first header". Compression: LZMA (with or without the x86
 * filter), deflate or the bzip2 of NSIS; solid (one stream for the script and all files, kept decoded in a temporary file) or a
 * stream per file.
 */
final class NsisArchives {

    static final int FIRST_HEADER = 28;
    private static final int SIGNATURE = 0xDEADBEEF;
    private static final byte[] MAGIC = "NullsoftInst".getBytes(StandardCharsets.US_ASCII);
    private static final int COMPRESSED = 0x80000000;
    private static final int BLOCK_ENTRIES = 2;
    private static final int BLOCK_STRINGS = 3;
    private static final int ENTRY_SIZE = 28;
    private static final int EW_CREATEDIR = 11;
    private static final int EW_EXTRACTFILE = 20;
    private static final int EW_ASSIGNVAR = 25;
    private static final int PIECE = 1 << 16;
    /** 100-ns intervals between 1601-01-01 and 1970-01-01. */
    private static final long FILETIME_EPOCH = 116444736000000000L;

    private enum Method { LZMA, DEFLATE, BZIP2 }

    private NsisArchives() {
    }

    /** The first header at the start of the data: flags, 0xDEADBEEF, "NullsoftInst", header and data lengths. */
    static boolean matches(final byte @NotNull [] head) {
        return matches(ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN), 0, head.length);
    }

    static boolean matches(final @NotNull ByteBuffer le, final int i, final int n) {
        if (i + FIRST_HEADER > n || le.getInt(i + 4) != SIGNATURE) {
            return false;
        }
        for (int k = 0; k < MAGIC.length; k++) {
            if (le.get(i + 8 + k) != MAGIC[k]) {
                return false;
            }
        }

        return le.getInt(i + 20) > 0 && le.getInt(i + 24) > FIRST_HEADER;
    }

    /** Length of the installer data from the first header at i (the .exe may have a signature or other data after it). */
    static long length(final @NotNull ByteBuffer le, final int i) {
        return le.getInt(i + 24) & 0xFFFFFFFFL;
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        try {
            final Installer nsis = new Installer(BlockSource.of(ch));
            final Map<String, Long> byName = nsis.list(index);
            index.setTar(new ArchiveReader() {
                @Override
                public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                    final Long offset = byName.get(entry);
                    if (offset == null) {
                        throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                    }

                    return nsis.open(offset);
                }

                @Override
                public void close() throws IOException {
                    try {
                        nsis.close();
                    } finally {
                        ch.close();
                    }
                }
            });
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException io ? io : new IOException("Not a valid NSIS installer: " + archive.getFileName(), e);
        }
    }

    /** The installer data: compression, the script header and the file blocks. */
    private static final class Installer {

        private final @NotNull BlockSource src;
        private final long dataLength;
        private final @NotNull Method method;
        private final boolean solid;
        /** Every LZMA stream starts with a flag byte: 1 — the x86 branch filter is applied to the decoded data. */
        private final boolean filterFlag;
        /** Solid: the whole decoded stream (size of the header, the header, the file blocks). */
        private final @Nullable SpillCache stream;
        private final byte @NotNull [] header;
        /** Where the offsets of the files count from: in the decoded stream (solid) or in the data (otherwise). */
        private final long filesStart;
        private final Map<Long, Long> sizes = new HashMap<>();

        Installer(final @NotNull BlockSource src) throws IOException {
            this.src = src;
            final ByteBuffer first = ByteBuffer.wrap(src.read(0, FIRST_HEADER)).order(ByteOrder.LITTLE_ENDIAN);
            if (!matches(first, 0, FIRST_HEADER)) {
                throw new IOException("No NSIS header");
            }
            final int headerLength = first.getInt(20);
            dataLength = Math.min(first.getInt(24) & 0xFFFFFFFFL, src.size());
            final byte[] p = src.read(FIRST_HEADER, (int) Math.min(16, dataLength - FIRST_HEADER));
            final int block = le32(p, 0);
            final int lzmaAt = lzmaAt(p);
            if (lzmaAt >= 0) {
                method = Method.LZMA;
                solid = lzmaAt < 4;
                filterFlag = lzmaAt == 1 || lzmaAt == 5;
            } else {
                // a header block of its own: its length with the "compressed" bit (and it fits the data), or stored as is
                final boolean blocks = (block & COMPRESSED) != 0 && (block & ~COMPRESSED) <= dataLength - FIRST_HEADER - 4
                    || block == headerLength;
                final boolean bzip2 = blocks ? isBzip2(p, 4) : isBzip2(p, 0);
                method = bzip2 ? Method.BZIP2 : Method.DEFLATE;
                solid = !blocks;
                filterFlag = false;
            }
            if (solid) {
                final SpillCache s = new SpillCache(decoder(FIRST_HEADER, dataLength - FIRST_HEADER), Long.MAX_VALUE, PIECE);
                stream = s;
                final int size = le32(s.read(0, 4), 0) & ~COMPRESSED;
                header = s.read(4, size);
                filesStart = 4L + size;
            } else {
                stream = null;
                header = readBlock(FIRST_HEADER).readAllBytes();
                filesStart = FIRST_HEADER + 4L + (block & ~COMPRESSED);
            }
        }

        /** Position of the LZMA properties in the first bytes: 0 / 4 (solid / per block), 1 / 5 behind a filter flag; -1 — not LZMA. */
        private static int lzmaAt(final byte @NotNull [] p) {
            for (final int at : new int[] {0, 1, 4, 5}) {
                final boolean flagged = at == 1 || at == 5;
                if (at + 5 <= p.length && isLzma(p, at) && (!flagged || p[at - 1] == 0 || p[at - 1] == 1)) {
                    return at;
                }
            }

            return -1;
        }

        /** NSIS bzip2 data starts with '1' and the block size level. */
        private static boolean isBzip2(final byte @NotNull [] p, final int at) {
            return at + 2 <= p.length && p[at] == 0x31 && p[at + 1] < 14;
        }

        /** NSIS writes lc=3 lp=0 pb=2 (0x5D) and a dictionary of 2^n bytes (or 3·2^n) up to 1.5 GB. */
        private static boolean isLzma(final byte @NotNull [] p, final int at) {
            if (p[at] != 0x5D) {
                return false;
            }
            final int dict = le32(p, at + 1);
            if (dict <= 0) {
                return false;
            }
            final int bits = Integer.numberOfTrailingZeros(dict);

            return dict == 1 << bits || dict == 3 << bits;
        }

        /** Files of the script (EW_EXTRACTFILE) into the index; the entry name → offset of its data block. */
        @NotNull Map<String, Long> list(final @NotNull ArchiveIndex index) throws IOException {
            final ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            final int entries = h.getInt(4 + 8 * BLOCK_ENTRIES);
            final int count = h.getInt(4 + 8 * BLOCK_ENTRIES + 4);
            final NsisStrings strings = new NsisStrings(header, h.getInt(4 + 8 * BLOCK_STRINGS));
            final Map<String, Long> byName = new HashMap<>();
            String outDir = "$INSTDIR";
            // File /r keeps the output directory in $_OUTDIR and sets the subdirectories as "$_OUTDIR\\sub"
            String savedOutDir = outDir;
            for (int i = 0; i < count && entries + (i + 1) * ENTRY_SIZE <= header.length; i++) {
                final int at = entries + i * ENTRY_SIZE;
                final int which = h.getInt(at);
                if (which == EW_CREATEDIR && h.getInt(at + 8) != 0) {
                    outDir = expand(strings.get(h.getInt(at + 4)), outDir, savedOutDir);
                } else if (which == EW_ASSIGNVAR && h.getInt(at + 12) == 0 && h.getInt(at + 16) == 0
                    && NsisStrings.isSavedOutDir(h.getInt(at + 4))) {
                    savedOutDir = expand(strings.get(h.getInt(at + 8)), outDir, savedOutDir);
                } else if (which == EW_EXTRACTFILE) {
                    final long offset = h.getInt(at + 12) & 0xFFFFFFFFL;
                    final String name = NsisStrings.path(outDir, expand(strings.get(h.getInt(at + 8)), outDir, savedOutDir));
                    final String unique = unique(byName, name, offset);
                    if (unique != null) {
                        byName.put(unique, offset);
                        index.add(unique, false, size(offset), time(h.getInt(at + 16), h.getInt(at + 20), index), "");
                    }
                }
            }

            return byName;
        }

        /** $OUTDIR and $_OUTDIR at the start of a path are replaced with what the script has set them to. */
        private static @NotNull String expand(final @NotNull String path, final @NotNull String outDir, final @NotNull String saved) {
            if (path.startsWith(NsisStrings.OUTDIR)) {
                return outDir + path.substring(NsisStrings.OUTDIR.length());
            }
            if (path.startsWith(NsisStrings.SAVED_OUTDIR)) {
                return saved + path.substring(NsisStrings.SAVED_OUTDIR.length());
            }

            return path;
        }

        /** The same file extracted again (plugins in every page callback) is listed once; another file of that name gets a suffix. */
        private static @Nullable String unique(final @NotNull Map<String, Long> byName, final @NotNull String name, final long offset) {
            String candidate = name;
            for (int n = 2; byName.containsKey(candidate); n++) {
                if (byName.get(candidate) == offset) {
                    return null;
                }
                candidate = name + " (" + n + ")";
            }

            return candidate;
        }

        private static long time(final int low, final int high, final @NotNull ArchiveIndex index) {
            final long ft = (high & 0xFFFFFFFFL) << 32 | low & 0xFFFFFFFFL;
            if (ft == 0 || low == -1 && high == -1) {
                return index.getArchiveModified();
            }

            return (ft - FILETIME_EPOCH) / 10_000;
        }

        /** Size of the file at offset: stored before its data (solid, uncompressed block) or found by decoding the block. */
        private long size(final long offset) throws IOException {
            final Long known = sizes.get(offset);
            if (known != null) {
                return known;
            }
            final long size;
            final int block = le32(read(filesStart + offset, 4), 0);
            if (solid || (block & COMPRESSED) == 0) {
                size = block & ~COMPRESSED & 0xFFFFFFFFL;
            } else {
                try (InputStream in = readBlock(filesStart + offset)) {
                    size = in.transferTo(OutputStreamSink.INSTANCE);
                }
            }
            sizes.put(offset, size);

            return size;
        }

        @NotNull InputStream open(final long offset) throws IOException {
            if (!solid) {
                return readBlock(filesStart + offset);
            }
            final SpillCache s = stream;
            final long size = size(offset);

            return BlockSource.stream(s == null ? src : s, filesStart + offset + 4, size);
        }

        private byte @NotNull [] read(final long pos, final int len) throws IOException {
            final SpillCache s = stream;

            return solid && s != null ? s.read(pos, len) : src.read(pos, len);
        }

        /** A block of the data (not solid): its length with the "compressed" bit, then the bytes. */
        private @NotNull InputStream readBlock(final long pos) throws IOException {
            final int block = le32(src.read(pos, 4), 0);
            final long length = block & ~COMPRESSED & 0xFFFFFFFFL;
            if (pos + 4 + length > dataLength) {
                throw new IOException("NSIS block out of the data");
            }
            final InputStream raw = BlockSource.stream(src, pos + 4, length);

            return (block & COMPRESSED) == 0 ? raw : decompress(new BufferedInputStream(raw, PIECE));
        }

        private @NotNull InputStream decompress(final @NotNull InputStream raw) throws IOException {
            return switch (method) {
                case LZMA -> {
                    final boolean filtered = filterFlag && raw.read() == 1;
                    final InputStream lzma = lzma(raw);
                    yield filtered ? new X86Options().getInputStream(lzma, ArrayCache.getDefaultCache()) : lzma;
                }
                case DEFLATE -> new InflaterInputStream(raw, new Inflater(true), PIECE);
                case BZIP2 -> new NsisBzip2InputStream(raw);
            };
        }

        private static @NotNull InputStream lzma(final @NotNull InputStream raw) throws IOException {
            final byte props = (byte) raw.read();
            final byte[] dict = raw.readNBytes(4);
            if (dict.length < 4) {
                throw new IOException("Truncated LZMA stream");
            }
            // NSIS streams end with the end marker: the size is not known in advance
            return new LZMAInputStream(raw, -1, props, le32(dict, 0));
        }

        /** The solid stream decoded piece by piece into the spill cache. */
        private @NotNull SpillCache.Decoder decoder(final long pos, final long length) throws IOException {
            // the LZMA range decoder reads byte by byte: unbuffered, each byte would be a read of the file
            final InputStream in = decompress(new BufferedInputStream(BlockSource.stream(src, pos, length), PIECE));

            return buf -> {
                final int n = in.readNBytes(buf, 0, buf.length);

                return n == 0 ? -1 : n;
            };
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    /** Discards what is written: the size of a block is found by decoding it. */
    private static final class OutputStreamSink extends java.io.OutputStream {

        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(final int b) {
            // discarded
        }

        @Override
        public void write(final byte @NotNull [] b, final int off, final int len) {
            // discarded
        }
    }

    static int le32(final byte @NotNull [] b, final int at) {
        return b[at] & 0xFF | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }
}
