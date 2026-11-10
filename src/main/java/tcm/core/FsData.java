package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The content of a file in a disk image, put together from pieces at logical offsets: byte ranges of the device and blocks
 * decoded on demand (compressed extents). Gaps between the pieces and the tail past the last one read as zeros (sparse files).
 */
final class FsData {

    private static final Comparator<Piece> BY_OFFSET = Comparator.comparingLong(p -> p.logical);

    private final @NotNull BlockSource dev;
    private final long size;
    private final @NotNull List<Piece> pieces = new ArrayList<>();
    private boolean sorted = true;

    FsData(final @NotNull BlockSource dev, final long size) {
        this.dev = dev;
        this.size = Math.max(size, 0);
    }

    long size() {
        return size;
    }

    /** Bytes [physical, physical + length) of the device at the logical offset; physical &lt; 0 — a hole. */
    @NotNull FsData raw(final long logical, final long physical, final long length) {
        if (length > 0 && physical >= 0) {
            add(new Piece(logical, length, physical, null, 0));
        }

        return this;
    }

    /** Bytes [skip, skip + length) of a block decoded when first read, at the logical offset. */
    @NotNull FsData decoded(final long logical, final long length, final long skip, final @NotNull IoSupplier<byte[]> block) {
        if (length > 0) {
            add(new Piece(logical, length, -1, block, skip));
        }

        return this;
    }

    private void add(final @NotNull Piece piece) {
        if (!pieces.isEmpty() && pieces.get(pieces.size() - 1).logical > piece.logical) {
            sorted = false;
        }
        pieces.add(piece);
    }

    /** Reads up to the whole content (at most max bytes) into an array — for symbolic link targets and small metadata files. */
    byte @NotNull [] readAll(final int max) throws IOException {
        try (InputStream in = stream()) {
            return in.readNBytes((int) Math.min(size, max));
        }
    }

    @NotNull InputStream stream() {
        if (!sorted) {
            pieces.sort(BY_OFFSET);
            sorted = true;
        }

        return new Stream(pieces.toArray(new Piece[0]));
    }

    private final class Stream extends InputStream {

        private final Piece @NotNull [] list;
        private long pos;
        private int index;
        private @Nullable Piece cachedPiece;
        private byte @Nullable [] cached;

        private Stream(final Piece @NotNull [] list) {
            this.list = list;
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
            if (pos >= size) {
                return -1;
            }
            while (index < list.length && list[index].logical + list[index].length <= pos) {
                index++;
            }
            final int n;
            if (index >= list.length || list[index].logical > pos) {
                final long end = index >= list.length ? size : Math.min(size, list[index].logical);
                n = (int) Math.min(len, end - pos);
                Arrays.fill(b, off, off + n, (byte) 0);
            } else {
                final Piece p = list[index];
                n = (int) Math.min(len, Math.min(p.logical + p.length, size) - pos);
                final long within = pos - p.logical;
                if (p.block == null) {
                    dev.read(p.physical + within, b, off, n);
                } else {
                    copyDecoded(p, within, b, off, n);
                }
            }
            pos += n;

            return n;
        }

        private void copyDecoded(final @NotNull Piece p, final long within, final byte @NotNull [] b, final int off, final int n)
            throws IOException {
            if (cachedPiece != p) {
                cached = p.block.get();
                cachedPiece = p;
            }
            final byte[] data = cached;
            final long from = p.skip + within;
            final int avail = (int) Math.max(0, Math.min(n, data.length - from));
            if (avail > 0) {
                System.arraycopy(data, (int) from, b, off, avail);
            }
            if (avail < n) {
                // a block decoded shorter than its extent: the rest of the extent reads as zeros
                Arrays.fill(b, off + avail, off + n, (byte) 0);
            }
        }

        @Override
        public long skip(final long n) {
            final long step = Math.max(0, Math.min(n, size - pos));
            pos += step;

            return step;
        }
    }

    private static final class Piece {

        private final long logical;
        private final long length;
        private final long physical;
        private final @Nullable IoSupplier<byte[]> block;
        private final long skip;

        private Piece(final long logical, final long length, final long physical, final @Nullable IoSupplier<byte[]> block, final long skip) {
            this.logical = logical;
            this.length = length;
            this.physical = physical;
            this.block = block;
            this.skip = skip;
        }
    }
}
