package tcm.core;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;

/** A random-access byte range: a disk image partition read by file system parsers. Implementations are thread-safe. */
interface BlockSource {

    long size();

    /** Reads exactly len bytes at pos; EOFException if the range goes past the end. */
    void read(long pos, byte @NotNull [] b, int off, int len) throws IOException;

    default byte @NotNull [] read(final long pos, final int len) throws IOException {
        final byte[] b = new byte[len];
        read(pos, b, 0, len);

        return b;
    }
}
