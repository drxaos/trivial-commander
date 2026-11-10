package tcm.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reading entries of an open archive. Keeps the archive open between entries so that when copying many files
 * the headers are not parsed again for each one.
 */
public interface ArchiveReader extends Closeable {

    /** Content stream of a file entry; closed by the caller. */
    @NotNull InputStream open(@NotNull String entry) throws IOException;

    /**
     * The entry as random-access bytes, for an entry that is a slice of a disk (a partition of a disk image): it is unpacked
     * sparse, skipping the unallocated blocks; null — read it with {@link #open}.
     */
    default @Nullable BlockSource source(final @NotNull String entry) throws IOException {
        return null;
    }

    @Override
    void close() throws IOException;
}
