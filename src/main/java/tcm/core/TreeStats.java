package tcm.core;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/**
 * Disk usage calculation: size, number of files and directories (Ctrl+L, Space on a directory, Alt+Shift+Enter).
 */
@Getter
public final class TreeStats {

    private long bytes;
    private long files;
    private long dirs;

    public static @NotNull TreeStats of(final @NotNull List<VFile> roots, final @NotNull BooleanSupplier cancelled) {
        final TreeStats stats = new TreeStats();
        for (final VFile f : roots) {
            stats.add(f, cancelled);
        }

        return stats;
    }

    private void add(final @NotNull VFile f, final @NotNull BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || f.isParentLink()) {
            return;
        }
        if (!f.isDirectory()) {
            files++;
            bytes += f.getSize();

            return;
        }
        dirs++;
        if (f.isLocal() && Files.isSymbolicLink(f.getHostPath())) {
            return;
        }
        try {
            for (final VFile child : Vfs.list(f)) {
                add(child, cancelled);
            }
        } catch (final IOException e) {
            // no access — count what is accessible
        }
    }
}
