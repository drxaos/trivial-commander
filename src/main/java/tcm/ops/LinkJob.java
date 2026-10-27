package tcm.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import tcm.core.VFile;

/**
 * Creating shortcuts (symbolic links) to the selected files in the target directory.
 */
public final class LinkJob extends Job {

    private final @NotNull List<VFile> sources;
    private final @NotNull Path targetDir;

    public LinkJob(final @NotNull List<VFile> sources, final @NotNull Path targetDir) {
        super("Create " + sources.size() + " link(s) in " + targetDir);
        this.sources = List.copyOf(sources);
        this.targetDir = targetDir;
    }

    @Override
    public void run(final @NotNull JobContext ctx) throws Exception {
        for (final VFile src : sources) {
            if (!src.isLocal()) {
                throw new IOException("Cannot link to a file inside an archive: " + src.getLocation());
            }
            final Path link = targetDir.resolve(src.getName());
            ctx.file(link.toString(), 0);
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                if (!ctx.shouldOverwrite(src.getLocation(), link.toString())) {
                    continue;
                }
                Files.delete(link);
            }
            Files.createSymbolicLink(link, src.getHostPath().toAbsolutePath());
        }
    }
}
