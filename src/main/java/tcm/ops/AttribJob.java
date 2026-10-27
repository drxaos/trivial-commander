package tcm.ops;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.AttribChange;
import tcm.core.VFile;

/**
 * Changing attributes of the selected files (and directory contents, if requested). Symbolic links are skipped:
 * link permissions do not change on Linux, and changing them on the target, which may be anywhere, is dangerous.
 */
public final class AttribJob extends Job {

    private final @NotNull List<VFile> sources;
    private final @NotNull AttribChange change;

    public AttribJob(final @NotNull List<VFile> sources, final @NotNull AttribChange change) {
        super("Change attributes of " + sources.size() + " item(s)");
        this.sources = List.copyOf(sources);
        this.change = change;
    }

    @Override
    public void run(final @NotNull JobContext ctx) throws Exception {
        for (final VFile f : sources) {
            if (!f.isLocal()) {
                throw new IOException("Cannot change attributes inside an archive: " + f.getLocation());
            }
            final Path p = f.getHostPath();
            if (change.isRecursive() && Files.isDirectory(p) && !Files.isSymbolicLink(p)) {
                walk(p, ctx);
            } else {
                applyTo(p, false, ctx);
            }
        }
    }

    private void walk(final @NotNull Path root, final @NotNull JobContext ctx) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                // permissions being granted are needed already for traversal: e.g. x on a directory
                applyTo(dir, true, ctx);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                applyTo(file, false, ctx);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final @Nullable IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                applyTo(dir, false, ctx);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void applyTo(final @NotNull Path p, final boolean onlyAdditions, final @NotNull JobContext ctx) throws IOException {
        if (Files.isSymbolicLink(p)) {
            return;
        }
        ctx.file(p.toString(), 0);
        change.apply(p, onlyAdditions);
    }
}
