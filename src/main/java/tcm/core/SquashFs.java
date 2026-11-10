package tcm.core;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.XZInputStream;

import tcm.util.Lz4;
import tcm.util.Lzo;
import tcm.util.Zstd;

/**
 * A read-only squashfs 4.0 image (the file system of an AppImage) at an offset in a file: directory listings, the whole tree,
 * whole files and file streams by path, symbolic links followed. Compression: gzip, xz, lzma, lzo, lz4, zstd. Sizes are bounded, so
 * a damaged image cannot make it allocate much.
 */
public final class SquashFs {

    private static final int MAGIC = 0x73717368;
    private static final int METADATA_SIZE = 8192;
    private static final int MAX_BLOCK = 1 << 20;
    private static final int MAX_LINKS = 16;
    private static final int NO_FRAGMENT = 0xFFFFFFFF;
    private static final int UNCOMPRESSED_BLOCK = 1 << 24;

    private static final int DIR = 1;
    private static final int FILE = 2;
    private static final int SYMLINK = 3;
    private static final int EXT_DIR = 8;
    private static final int EXT_FILE = 9;
    private static final int EXT_SYMLINK = 10;
    private static final long MAX_FILE = 1L << 40;
    private static final int MAX_ENTRIES = 2_000_000;
    private static final int MAX_DEPTH = 256;

    /** One entry of {@link #walk}: a symbolic link to a file is reported as that file; to anything else — with size 0. */
    public interface Visitor {

        void entry(@NotNull String path, boolean directory, long size, long modified, int mode) throws IOException;
    }

    private final BlockSource ch;
    private final long base;
    private final int blockSize;
    private final int compression;
    private final long rootInode;
    private final long inodeTable;
    private final long directoryTable;
    private final long fragmentTable;
    private final Map<Long, byte[]> metadataCache = new HashMap<>();
    private int lastFragmentIndex = -1;
    private byte @Nullable [] lastFragment;

    private SquashFs(final @NotNull BlockSource ch, final long base, final @NotNull ByteBuffer sb) {
        this.ch = ch;
        this.base = base;
        this.blockSize = sb.getInt(12);
        this.compression = sb.getShort(20) & 0xFFFF;
        this.rootInode = sb.getLong(32);
        this.inodeTable = sb.getLong(64);
        this.directoryTable = sb.getLong(72);
        this.fragmentTable = sb.getLong(80);
    }

    /** The image at the offset, or null if there is no squashfs 4.0 superblock there. The channel stays owned by the caller. */
    public static @Nullable SquashFs open(final @NotNull FileChannel ch, final long offset) throws IOException {
        return open(BlockSource.of(ch), offset);
    }

    /** The same over a partition of a disk image. */
    static @Nullable SquashFs open(final @NotNull BlockSource ch, final long offset) throws IOException {
        if (offset < 0 || offset + 96 > ch.size()) {
            return null;
        }
        final ByteBuffer sb = read(ch, offset, 96);
        final int block = sb.getInt(12);
        if (sb.getInt(0) != MAGIC || (sb.getShort(28) & 0xFFFF) != 4 || block <= 0 || block > MAX_BLOCK || Integer.bitCount(block) != 1) {
            return null;
        }

        return new SquashFs(ch, offset, sb);
    }

    /**
     * Where the image starts in a file: 0 for a bare squashfs image, after the ELF section header table for an AppImage (type 2);
     * -1 — neither.
     */
    public static long imageOffset(final @NotNull FileChannel ch) throws IOException {
        if (ch.size() < 64) {
            return -1;
        }
        final ByteBuffer h = read(BlockSource.of(ch), 0, 64);
        if (h.getInt(0) == MAGIC) {
            return 0;
        }
        if (h.getInt(0) != 0x464C457F || h.get(5) != 1) {
            return -1;
        }
        if (h.get(4) == 2) {
            return h.getLong(0x28) + (long) (h.getShort(0x3A) & 0xFFFF) * (h.getShort(0x3C) & 0xFFFF);
        }

        return (h.getInt(0x20) & 0xFFFFFFFFL) + (long) (h.getShort(0x2E) & 0xFFFF) * (h.getShort(0x30) & 0xFFFF);
    }

    /** All entries of the image, depth-first; paths without a leading "/". */
    public void walk(final @NotNull Visitor visitor) throws IOException {
        walk(inode(rootInode), "", visitor, new int[1], 0);
    }

    private void walk(final @NotNull Inode dir, final @NotNull String path, final @NotNull Visitor visitor, final int @NotNull [] count,
        final int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("squashfs directories nested too deep");
        }
        for (final Map.Entry<String, Long> e : entries(dir).entrySet()) {
            if (++count[0] > MAX_ENTRIES) {
                throw new IOException("too many squashfs entries");
            }
            final String child = path.isEmpty() ? e.getKey() : path + "/" + e.getKey();
            final Inode inode = inode(e.getValue());
            if (inode.isDirectory()) {
                visitor.entry(child, true, 0, inode.modified, inode.mode);
                walk(inode, child, visitor, count, depth + 1);
            } else if (inode.target != null) {
                final Inode target = resolveQuietly(child);
                final long size = target != null && target.isFile() ? target.size : 0;
                visitor.entry(child, false, size, inode.modified, inode.mode);
            } else {
                visitor.entry(child, false, inode.isFile() ? inode.size : 0, inode.modified, inode.mode);
            }
        }
    }

    private @Nullable Inode resolveQuietly(final @NotNull String path) {
        try {
            return resolve(path);
        } catch (final IOException e) {
            // a broken link: shown with size 0
            return null;
        }
    }

    /** Stream of the regular file at the path (links followed); null — no such file. Blocks are decompressed as they are read. */
    public @Nullable InputStream open(final @NotNull String path) throws IOException {
        final Inode file = resolve(path);
        if (file == null) {
            return null;
        }

        return file.isFile() ? new FileStream(file) : InputStream.nullInputStream();
    }

    /** Names in the directory at the path (null if it is not a directory). */
    public @Nullable List<String> list(final @NotNull String path) throws IOException {
        final Inode dir = resolve(path);

        return dir == null || !dir.isDirectory() ? null : new ArrayList<>(entries(dir).keySet());
    }

    /** Contents of the regular file at the path, or null if there is none or it is larger than maxSize. */
    public byte @Nullable [] readFile(final @NotNull String path, final int maxSize) throws IOException {
        final Inode file = resolve(path);
        if (file == null || !file.isFile() || file.size > maxSize) {
            return null;
        }

        return contents(file);
    }

    /** Walks the path from the root; symbolic links on the way (and at the end) are followed. */
    private @Nullable Inode resolve(final @NotNull String path) throws IOException {
        final Deque<String> parts = new ArrayDeque<>(split(path));
        final Deque<Inode> stack = new ArrayDeque<>();
        Inode current = inode(rootInode);
        int links = 0;
        while (!parts.isEmpty()) {
            final String part = parts.removeFirst();
            if (part.equals("..")) {
                current = stack.isEmpty() ? current : stack.removeLast();
                continue;
            }
            if (!current.isDirectory()) {
                return null;
            }
            final Long ref = entries(current).get(part);
            if (ref == null) {
                return null;
            }
            final Inode next = inode(ref);
            if (next.target == null) {
                stack.addLast(current);
                current = next;
                continue;
            }
            if (++links > MAX_LINKS) {
                return null;
            }
            final List<String> target = split(next.target);
            for (int i = target.size() - 1; i >= 0; i--) {
                parts.addFirst(target.get(i));
            }
            if (next.target.startsWith("/")) {
                current = inode(rootInode);
                stack.clear();
            }
        }

        return current;
    }

    private static @NotNull List<String> split(final @NotNull String path) {
        final List<String> result = new ArrayList<>();
        for (final String p : path.split("/")) {
            if (!p.isEmpty() && !p.equals(".")) {
                result.add(p);
            }
        }

        return result;
    }

    /** Directory entries: name → inode reference. */
    private @NotNull Map<String, Long> entries(final @NotNull Inode dir) throws IOException {
        final Map<String, Long> result = new LinkedHashMap<>();
        final Metadata in = new Metadata(directoryTable, dir.dirBlock, dir.dirOffset);
        // the stored size counts "." and ".." (3 bytes) that are not in the listing
        final long end = dir.size - 3;
        while (in.consumed < end) {
            final int count = in.u32() + 1;
            final long start = in.u32() & 0xFFFFFFFFL;
            in.u32();
            if (count > 256) {
                throw new IOException("corrupted squashfs directory");
            }
            for (int i = 0; i < count; i++) {
                final int offset = in.u16();
                in.u16();
                in.u16();
                final int nameSize = in.u16() + 1;
                final String name = new String(in.bytes(nameSize), StandardCharsets.UTF_8);
                result.put(name, start << 16 | offset);
            }
        }

        return result;
    }

    private @NotNull Inode inode(final long ref) throws IOException {
        final Metadata in = new Metadata(inodeTable, ref >>> 16, (int) (ref & 0xFFFF));
        final Inode inode = new Inode(in.u16());
        inode.mode = in.u16();
        in.u32();
        inode.modified = (in.u32() & 0xFFFFFFFFL) * 1000;
        in.u32();
        switch (inode.type) {
            case DIR -> {
                inode.dirBlock = in.u32() & 0xFFFFFFFFL;
                in.u32();
                inode.size = in.u16();
                inode.dirOffset = in.u16();
            }
            case EXT_DIR -> {
                in.u32();
                inode.size = in.u32() & 0xFFFFFFFFL;
                inode.dirBlock = in.u32() & 0xFFFFFFFFL;
                in.u32();
                in.u16();
                inode.dirOffset = in.u16();
            }
            case FILE -> {
                inode.blocksStart = in.u32() & 0xFFFFFFFFL;
                inode.fragment = in.u32();
                inode.fragmentOffset = in.u32();
                inode.size = in.u32() & 0xFFFFFFFFL;
                inode.blockSizes = blockSizes(in, inode);
            }
            case EXT_FILE -> {
                inode.blocksStart = in.u64();
                inode.size = in.u64();
                in.u64();
                in.u32();
                inode.fragment = in.u32();
                inode.fragmentOffset = in.u32();
                in.u32();
                inode.blockSizes = blockSizes(in, inode);
            }
            case SYMLINK, EXT_SYMLINK -> {
                in.u32();
                final int length = in.u32();
                if (length < 0 || length > 4096) {
                    throw new IOException("corrupted squashfs symlink");
                }
                inode.target = new String(in.bytes(length), StandardCharsets.UTF_8);
            }
            default -> {
                // devices, pipes, sockets: neither directories nor files
            }
        }

        return inode;
    }

    private int @NotNull [] blockSizes(final @NotNull Metadata in, final @NotNull Inode inode) throws IOException {
        if (inode.size < 0 || inode.size > MAX_FILE) {
            throw new IOException("squashfs file too large");
        }
        final long full = inode.fragment == NO_FRAGMENT ? (inode.size + blockSize - 1) / blockSize : inode.size / blockSize;
        final int[] result = new int[(int) full];
        for (int i = 0; i < result.length; i++) {
            result[i] = in.u32();
        }

        return result;
    }

    /** A data block of a file: zero length is a sparse block of zeros. */
    private byte @NotNull [] dataBlock(final int word, final long disk, final int want) throws IOException {
        final int length = word & (UNCOMPRESSED_BLOCK - 1);
        if (length == 0) {
            return new byte[want];
        }
        final byte[] raw = bytes(read(ch, disk, length));
        final byte[] block = (word & UNCOMPRESSED_BLOCK) != 0 ? raw : decompress(raw, blockSize);

        return block.length == want ? block : Arrays.copyOf(block, want);
    }

    private byte @NotNull [] contents(final @NotNull Inode file) throws IOException {
        try (InputStream in = new FileStream(file)) {
            return in.readAllBytes();
        }
    }

    /** A fragment block: the fragment table is a list of pointers to metadata blocks of 16-byte entries, 512 per block. */
    private byte @NotNull [] fragment(final int index) throws IOException {
        if (index < 0) {
            throw new IOException("corrupted squashfs fragment index");
        }
        // small files of a directory usually share a fragment block: the last one is kept
        if (index == lastFragmentIndex && lastFragment != null) {
            return lastFragment;
        }
        final long pointer = read(ch, base + fragmentTable + 8L * (index / 512), 8).getLong(0);
        final Metadata in = new Metadata(0, pointer, 0);
        in.bytes(16 * (index % 512));
        final long start = in.u64();
        final int word = in.u32();
        final int length = word & (UNCOMPRESSED_BLOCK - 1);
        final byte[] raw = bytes(read(ch, base + start, length));
        final byte[] block = (word & UNCOMPRESSED_BLOCK) != 0 ? raw : decompress(raw, blockSize);
        lastFragmentIndex = index;
        lastFragment = block;

        return block;
    }

    private byte @NotNull [] metadataBlock(final long pos) throws IOException {
        final byte[] cached = metadataCache.get(pos);
        if (cached != null) {
            return cached;
        }
        final int header = read(ch, base + pos, 2).getShort(0) & 0xFFFF;
        final int length = header & 0x7FFF;
        final byte[] raw = bytes(read(ch, base + pos + 2, length));
        final byte[] block = (header & 0x8000) != 0 ? raw : decompress(raw, METADATA_SIZE);
        metadataCache.put(pos, block);

        return block;
    }

    private byte @NotNull [] decompress(final byte @NotNull [] data, final int max) throws IOException {
        return switch (compression) {
            case 1 -> inflate(data, max);
            case 2 -> readAll(new LZMAInputStream(new ByteArrayInputStream(data)), max);
            case 3 -> Lzo.decompress(data, max);
            case 4 -> readAll(new XZInputStream(new ByteArrayInputStream(data)), max);
            case 5 -> Lz4.decompress(data, max);
            case 6 -> Zstd.decompress(data, max);
            default -> throw new IOException("unsupported squashfs compression " + compression);
        };
    }

    private static byte @NotNull [] inflate(final byte @NotNull [] data, final int max) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            final byte[] out = new byte[max];
            int n = 0;
            while (!inflater.finished() && n < max) {
                final int got = inflater.inflate(out, n, max - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += got;
            }

            return Arrays.copyOf(out, n);
        } catch (final DataFormatException e) {
            throw new IOException("corrupted squashfs gzip block", e);
        } finally {
            inflater.end();
        }
    }

    private static byte @NotNull [] readAll(final @NotNull InputStream in, final int max) throws IOException {
        try (in) {
            final byte[] data = in.readNBytes(max);
            if (in.read() >= 0) {
                throw new IOException("squashfs block larger than " + max);
            }

            return data;
        }
    }

    private static @NotNull ByteBuffer read(final @NotNull BlockSource ch, final long pos, final int length) throws IOException {
        if (length < 0 || length > 2 * MAX_BLOCK || pos < 0 || pos + length > ch.size()) {
            throw new EOFException("outside the squashfs image");
        }

        return ByteBuffer.wrap(ch.read(pos, length)).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte @NotNull [] bytes(final @NotNull ByteBuffer buf) {
        final byte[] result = new byte[buf.remaining()];
        buf.get(result);

        return result;
    }

    /** File data decompressed block by block while it is read. */
    private final class FileStream extends InputStream {

        private final @NotNull Inode file;
        private int block;
        private long disk;
        private long remaining;
        private byte[] current = new byte[0];
        private int pos;

        private FileStream(final @NotNull Inode file) {
            this.file = file;
            this.disk = base + file.blocksStart;
            this.remaining = file.size;
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
            if (pos >= current.length && !next()) {
                return -1;
            }
            final int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;

            return n;
        }

        private boolean next() throws IOException {
            if (remaining <= 0) {
                return false;
            }
            final int want = (int) Math.min(blockSize, remaining);
            if (block < file.blockSizes.length) {
                final int word = file.blockSizes[block++];
                current = dataBlock(word, disk, want);
                disk += word & (UNCOMPRESSED_BLOCK - 1);
            } else {
                final byte[] fragment = fragment(file.fragment);
                if (file.fragmentOffset < 0 || file.fragmentOffset + want > fragment.length) {
                    throw new IOException("corrupted squashfs fragment");
                }
                current = Arrays.copyOfRange(fragment, file.fragmentOffset, file.fragmentOffset + want);
            }
            pos = 0;
            remaining -= want;

            return true;
        }
    }

    private static final class Inode {

        private final int type;
        private int mode;
        private long modified;
        private long size;
        private long dirBlock;
        private int dirOffset;
        private long blocksStart;
        private int fragment = NO_FRAGMENT;
        private int fragmentOffset;
        private int[] blockSizes = new int[0];
        private @Nullable String target;

        private Inode(final int type) {
            this.type = type;
        }

        private boolean isDirectory() {
            return type == DIR || type == EXT_DIR;
        }

        private boolean isFile() {
            return type == FILE || type == EXT_FILE;
        }
    }

    /** A stream over consecutive metadata blocks of a table, starting at a block and an offset inside it. */
    private final class Metadata {

        private final long table;
        private long block;
        private byte[] data;
        private int pos;
        private long consumed;

        private Metadata(final long table, final long block, final int offset) throws IOException {
            this.table = table;
            this.block = block;
            this.data = metadataBlock(table + block);
            this.pos = offset;
        }

        private byte @NotNull [] bytes(final int n) throws IOException {
            final byte[] result = new byte[n];
            int filled = 0;
            while (filled < n) {
                if (pos >= data.length) {
                    final int header = read(ch, base + table + block, 2).getShort(0) & 0xFFFF;
                    block += 2 + (header & 0x7FFF);
                    data = metadataBlock(table + block);
                    pos = 0;
                    if (data.length == 0) {
                        throw new EOFException("empty squashfs metadata block");
                    }
                }
                final int chunk = Math.min(n - filled, data.length - pos);
                System.arraycopy(data, pos, result, filled, chunk);
                filled += chunk;
                pos += chunk;
            }
            consumed += n;

            return result;
        }

        private int u16() throws IOException {
            final byte[] b = bytes(2);

            return (b[0] & 0xFF) | (b[1] & 0xFF) << 8;
        }

        private int u32() throws IOException {
            return u16() | u16() << 16;
        }

        private long u64() throws IOException {
            return u32() & 0xFFFFFFFFL | (long) u32() << 32;
        }
    }
}
