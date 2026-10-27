package tcm.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import tcm.core.Trash;
import tcm.core.VFile;
import tcm.core.ZipEdit;
import tcm.core.ZipOps;

/**
 * Deletion: local files — to the trash (or permanently), archive entries — by rewriting the archive.
 */
public final class DeleteJob extends Job {

    private final @NotNull List<VFile> sources;
    private final boolean permanent;

    public DeleteJob(final @NotNull List<VFile> sources, final boolean permanent) {
        super("Delete " + sources.size() + " item(s)");
        this.sources = List.copyOf(sources);
        this.permanent = permanent;
    }

    @Override
    public void run(final @NotNull JobContext ctx) throws Exception {
        final Map<Path, ZipEdit> archiveEdits = new LinkedHashMap<>();
        boolean forcePermanent = permanent;
        for (final VFile f : sources) {
            ctx.checkpoint();
            if (f.isInArchive()) {
                archiveEdits.computeIfAbsent(f.getHostPath(), k -> new ZipEdit()).remove(f.getEntry());
                continue;
            }
            ctx.setCurrent(f.getLocation());
            if (!forcePermanent && Trash.moveToTrash(f.getHostPath())) {
                continue;
            }
            if (!forcePermanent) {
                if (!ctx.confirm("Cannot move to trash:\n" + f.getLocation() + "\n\nDelete permanently (this and following items)?")) {
                    throw new CancelledException();
                }
                forcePermanent = true;
            }
            deletePermanently(f, ctx);
        }
        for (final Map.Entry<Path, ZipEdit> e : archiveEdits.entrySet()) {
            ctx.setCurrent("Updating " + e.getKey());
            ZipOps.apply(e.getKey(), e.getValue(), ctx);
        }
    }

    private static void deletePermanently(final @NotNull VFile f, final @NotNull JobContext ctx) throws IOException {
        Trash.deleteTree(f.getHostPath(), ctx);
    }
}
