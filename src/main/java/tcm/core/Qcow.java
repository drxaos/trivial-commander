package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.util.Zstd;

/**
 * QEMU copy-on-write disk (.qcow version 1, .qcow2 versions 2 and 3): a two-level cluster table, compressed clusters (deflate,
 * zstd), zero clusters and a backing file. Encrypted images, external data files and extended L2 entries are not supported.
 */
final class Qcow extends SparseDisk {

    static final int MAGIC = 0x514649FB;
    private static final long V2_OFFSET_MASK = 0x00FFFFFFFFFFFE00L;
    private static final long V2_COMPRESSED = 1L << 62;
    private static final long V2_ZERO = 1;
    private static final int TABLE_CACHE = 256;
    private static final int COMPRESSION_ZSTD = 1;
    /** Known incompatible features: dirty, corrupt, compression type. */
    private static final long SUPPORTED_FEATURES = 1 | 2 | 8;

    private final int version;
    private final int clusterBits;
    private final int l2Bits;
    private final long[] l1;
    private final boolean zstd;
    private final Map<Long, long[]> tables = lru(TABLE_CACHE);

    private Qcow(
        final @NotNull FileChannel ch,
        final @NotNull Header h,
        final long @NotNull [] l1,
        final @Nullable VirtualDisks.Disk parent
    ) throws IOException {
        super(ch, h.size, 1 << h.clusterBits, parent);
        this.version = h.version;
        this.clusterBits = h.clusterBits;
        this.l2Bits = h.l2Bits;
        this.l1 = l1;
        this.zstd = h.zstd;
    }

    /** Header fields of both versions. */
    private static final class Header {

        private int version;
        private long size;
        private int clusterBits;
        private int l2Bits;
        private long l1Offset;
        private long l1Size;
        private boolean zstd;
        private long backingOffset;
        private int backingSize;
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 8 && ByteBuffer.wrap(head).getInt(0) == MAGIC;
    }

    static @NotNull Qcow open(final @NotNull FileChannel ch, final @NotNull Path file) throws IOException {
        final byte[] raw = new byte[(int) Math.min(ch.size(), 512)];
        readFully(ch, 0, raw, 0, raw.length);
        final Header h = header(ByteBuffer.wrap(raw));
        final long entries = h.l1Size;
        if (entries > (1 << 26)) {
            throw new IOException("Damaged qcow header");
        }
        final byte[] table = new byte[(int) entries * 8];
        readFully(ch, h.l1Offset, table, 0, table.length);
        final long[] l1 = new long[(int) entries];
        ByteBuffer.wrap(table).asLongBuffer().get(l1);
        VirtualDisks.Disk parent = null;
        if (h.backingOffset != 0 && h.backingSize > 0) {
            final byte[] name = new byte[Math.min(h.backingSize, 1023)];
            readFully(ch, h.backingOffset, name, 0, name.length);
            parent = VirtualDisks.backing(file, new String(name, StandardCharsets.UTF_8));
        }
        try {
            return new Qcow(ch, h, l1, parent);
        } catch (final IOException | RuntimeException e) {
            closeQuietly(parent);
            throw e;
        }
    }

    private static @NotNull Header header(final @NotNull ByteBuffer b) throws IOException {
        if (b.capacity() < 72 || b.getInt(0) != MAGIC) {
            throw new IOException("Not a qcow image");
        }
        final Header h = new Header();
        h.version = b.getInt(4);
        h.backingOffset = b.getLong(8);
        h.backingSize = b.getInt(16);
        if (h.version == 1) {
            h.size = b.getLong(24);
            h.clusterBits = b.get(32) & 0xFF;
            h.l2Bits = b.get(33) & 0xFF;
            if (b.getInt(36) != 0) {
                throw new IOException("Encrypted qcow images are not supported");
            }
            h.l1Offset = b.getLong(40);
            final int shift = h.clusterBits + h.l2Bits;
            if (shift < 9 || shift > 62) {
                throw new IOException("Damaged qcow header");
            }
            h.l1Size = (h.size + (1L << shift) - 1) >>> shift;
        } else if (h.version == 2 || h.version == 3) {
            h.clusterBits = b.getInt(20);
            h.size = b.getLong(24);
            if (b.getInt(32) != 0) {
                throw new IOException("Encrypted qcow2 images are not supported");
            }
            h.l1Size = Integer.toUnsignedLong(b.getInt(36));
            h.l1Offset = b.getLong(40);
            h.l2Bits = h.clusterBits - 3;
            if (h.version == 3 && b.capacity() >= 104) {
                final long incompatible = b.getLong(72);
                if ((incompatible & ~SUPPORTED_FEATURES) != 0) {
                    throw new IOException("Unsupported qcow2 features: 0x" + Long.toHexString(incompatible & ~SUPPORTED_FEATURES));
                }
                final int length = b.getInt(100);
                h.zstd = (incompatible & 8) != 0 && length > 104 && b.get(104) == COMPRESSION_ZSTD;
            }
        } else {
            throw new IOException("Unsupported qcow version " + h.version);
        }
        if (h.clusterBits < 9 || h.clusterBits > 21) {
            throw new IOException("Damaged qcow header");
        }

        return h;
    }

    @Override
    protected long map(final long index) throws IOException {
        final long e = entry(index);
        if (version == 1) {
            if (e == 0) {
                return HOLE;
            }

            return e < 0 ? COMPRESSED : e;
        }
        if ((e & V2_COMPRESSED) != 0) {
            return COMPRESSED;
        }
        if (version >= 3 && (e & V2_ZERO) != 0) {
            return ZERO;
        }
        final long at = e & V2_OFFSET_MASK;

        return at == 0 ? HOLE : at;
    }

    @Override
    protected long holeEnd(final long index) {
        final long table = index >>> l2Bits;

        return table >= l1.length || l1Offset(table) == 0 ? (table + 1) << l2Bits : index + 1;
    }

    private long l1Offset(final long table) {
        final long e = l1[(int) table];

        return version == 1 ? e : e & V2_OFFSET_MASK;
    }

    private long entry(final long index) throws IOException {
        final long table = index >>> l2Bits;
        if (table >= l1.length) {
            return 0;
        }
        final long at = l1Offset(table);
        if (at == 0) {
            return 0;
        }
        long[] l2 = tables.get(at);
        if (l2 == null) {
            final byte[] raw = readFile(at, 8 << l2Bits);
            l2 = new long[1 << l2Bits];
            ByteBuffer.wrap(raw).asLongBuffer().get(l2);
            tables.put(at, l2);
        }

        return l2[(int) (index & ((1L << l2Bits) - 1))];
    }

    @Override
    protected byte @NotNull [] inflate(final long index) throws IOException {
        final long e = entry(index);
        final long offset;
        final long length;
        if (version == 1) {
            final int shift = 63 - clusterBits;
            offset = e & ((1L << shift) - 1);
            length = (e >>> shift) & (unit() - 1);
        } else {
            final int shift = 62 - (clusterBits - 8);
            offset = e & ((1L << shift) - 1);
            final long sectors = ((e >>> shift) & ((1L << (clusterBits - 8)) - 1)) + 1;
            length = sectors * 512 - (offset & 511);
        }
        // the last compressed cluster may be padded past the end of the file
        final int n = (int) Math.max(0, Math.min(length, fileSize - offset));
        final byte[] src = readFile(offset, n);

        return zstd ? Zstd.decompressFrame(src, unit()) : inflate(src, unit(), true);
    }
}
