package tcm.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A sequentially decoded stream (a solid CAB folder) kept in a temporary file as far as it has been decoded: a file in the
 * middle of the stream needs everything before it, and unpacking file after file must not decode the folder again each time.
 * Decoding goes only as far as the requested range; the temporary file is deleted on close.
 */
final class SpillCache implements BlockSource, Closeable {

    /** Produces the stream piece by piece. */
    interface Decoder {

        /** Writes the next piece into buf (sized to the largest piece) and returns its length; -1 — the end of the stream. */
        int next(byte @NotNull [] buf) throws IOException;
    }

    private final Decoder decoder;
    private final long size;
    private final byte[] piece;
    private @Nullable FileChannel temp;
    private long decoded;
    private boolean finished;
    private boolean closed;

    SpillCache(final @NotNull Decoder decoder, final long size, final int maxPiece) {
        this.decoder = decoder;
        this.size = size;
        this.piece = new byte[maxPiece];
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public synchronized void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
        if (pos < 0 || pos + len > size) {
            throw new EOFException("read past the end of the stream");
        }
        final FileChannel ch = channel();
        while (decoded < pos + len) {
            if (finished) {
                throw new EOFException("the stream is shorter than declared");
            }
            final int n = decoder.next(piece);
            if (n < 0) {
                finished = true;
                continue;
            }
            final ByteBuffer buf = ByteBuffer.wrap(piece, 0, n);
            while (buf.hasRemaining()) {
                ch.write(buf, decoded + buf.position());
            }
            decoded += n;
        }
        final ByteBuffer buf = ByteBuffer.wrap(b, off, len);
        while (buf.hasRemaining()) {
            if (ch.read(buf, pos + buf.position() - off) < 0) {
                throw new EOFException("temporary file truncated");
            }
        }
    }

    private @NotNull FileChannel channel() throws IOException {
        if (closed) {
            throw new IOException("Archive is closed");
        }
        if (temp == null) {
            final Path file = Files.createTempFile("tcm-", ".tmp");
            temp = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
        }

        return temp;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (temp != null) {
            temp.close();
            temp = null;
        }
    }
}
