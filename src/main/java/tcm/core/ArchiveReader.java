package tcm.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

import org.jetbrains.annotations.NotNull;

/**
 * Reading entries of an open archive. Keeps the archive open between entries so that when copying many files
 * the headers are not parsed again for each one.
 */
public interface ArchiveReader extends Closeable {

    /** Content stream of a file entry; closed by the caller. */
    @NotNull InputStream open(@NotNull String entry) throws IOException;

    @Override
    void close() throws IOException;
}
