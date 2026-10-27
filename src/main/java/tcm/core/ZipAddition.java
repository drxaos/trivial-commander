package tcm.core;

import java.io.InputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * An entry added to an archive: a directory or a file with a data source.
 */
@Getter
public final class ZipAddition {

    private final @NotNull String entry;
    private final boolean directory;
    private final long size;
    private final long modified;
    private final @Nullable IoSupplier<InputStream> source;

    private ZipAddition(
        final @NotNull String entry,
        final boolean directory,
        final long size,
        final long modified,
        final @Nullable IoSupplier<InputStream> source
    ) {
        this.entry = ArchiveIndex.normalize(entry);
        this.directory = directory;
        this.size = size;
        this.modified = modified;
        this.source = source;
    }

    public static @NotNull ZipAddition dir(final @NotNull String entry, final long modified) {
        return new ZipAddition(entry, true, 0, modified, null);
    }

    public static @NotNull ZipAddition file(
        final @NotNull String entry,
        final long size,
        final long modified,
        final @NotNull IoSupplier<InputStream> source
    ) {
        return new ZipAddition(entry, false, size, modified, source);
    }
}
