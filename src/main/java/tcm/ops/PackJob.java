package tcm.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.NotNull;

import tcm.core.VFile;
import tcm.core.Vfs;

/**
 * Packing into ZIP/JAR: creates the archive (if it does not exist) and copies into its root.
 */
public final class PackJob extends Job {

    private final @NotNull List<VFile> sources;
    private final @NotNull Path archive;
    private final boolean recursive;
    private final boolean move;

    public PackJob(final @NotNull List<VFile> sources, final @NotNull Path archive, final boolean recursive, final boolean move) {
        super("Pack " + sources.size() + " item(s) to " + archive);
        this.sources = List.copyOf(sources);
        this.archive = archive.toAbsolutePath().normalize();
        this.recursive = recursive;
        this.move = move;
    }

    @Override
    public void run(final @NotNull JobContext ctx) throws Exception {
        for (final VFile src : sources) {
            if (src.isLocal() && src.getHostPath().equals(archive)) {
                throw new IOException("Cannot pack archive into itself: " + archive);
            }
        }
        if (!Files.exists(archive)) {
            Vfs.checkWritable(archive);
            createEmpty(archive);
        }
        new CopyJob(sources, Vfs.archiveDir(archive, ""), null, "*", move, recursive).run(ctx);
    }

    public static void createEmpty(final @NotNull Path archive) throws IOException {
        Files.createDirectories(archive.toAbsolutePath().getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.finish();
        }
        Vfs.invalidate(archive);
    }
}
