package tcm.ops;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import tcm.core.VFile;
import tcm.core.Vfs;

/**
 * Unpacking whole archives into a directory (including into another archive).
 */
public final class UnpackJob extends Job {

    private final @NotNull List<VFile> archives;
    private final @NotNull VFile targetDir;
    private final @NotNull String mask;

    public UnpackJob(final @NotNull List<VFile> archives, final @NotNull VFile targetDir, final @NotNull String mask) {
        super("Unpack " + archives.size() + " archive(s) to " + targetDir.getLocation());
        this.archives = List.copyOf(archives);
        this.targetDir = targetDir;
        this.mask = mask;
    }

    @Override
    public void run(final @NotNull JobContext ctx) throws Exception {
        for (final VFile archive : archives) {
            final VFile root = Vfs.archiveDir(archive.getHostPath(), "");
            new CopyJob(Vfs.list(root), targetDir, null, mask, false).run(ctx);
        }
    }
}
