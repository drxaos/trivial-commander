package tcm.ops;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.NotNull;

import tcm.core.ArchiveFormat;
import tcm.core.TarWriter;
import tcm.core.Trash;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.core.ZipOps;

/**
 * Packing into ZIP/JAR: creates the archive (if it does not exist) and copies into its root.
 * TAR, TAR.GZ, TAR.BZ2, TAR.XZ cannot be appended to: such an archive is written anew (after confirmation if it exists).
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
        final ArchiveFormat format = ArchiveFormat.of(String.valueOf(archive.getFileName()));
        if (format != null && format.isTar()) {
            packTar(format, ctx);

            return;
        }
        if (!Files.exists(archive)) {
            Vfs.checkWritable(archive);
            createEmpty(archive);
        }
        new CopyJob(sources, Vfs.archiveDir(archive, ""), null, "*", move, recursive).run(ctx);
    }

    private void packTar(final @NotNull ArchiveFormat format, final @NotNull JobContext ctx) throws IOException {
        if (Files.exists(archive) && !ctx.confirm("Archive " + archive + " already exists.\nReplace it?")) {
            return;
        }
        ctx.setCurrent("Counting...");
        long total = 0;
        for (final VFile src : sources) {
            total += size(src);
        }
        ctx.setTotal(total);
        final List<VFile> packed = TarWriter.pack(archive, format, sources, recursive, ctx);
        if (move) {
            deleteSources(packed, ctx);
        }
    }

    private long size(final @NotNull VFile file) throws IOException {
        if (!file.isDirectory()) {
            return file.getSize();
        }
        long total = 0;
        if (recursive && !(file.isLocal() && Files.isSymbolicLink(file.getHostPath()))) {
            for (final VFile child : Vfs.list(file)) {
                total += size(child);
            }
        }

        return total;
    }

    /** Move to archive: files are deleted, then directories that became empty; entries of a ZIP — in one rewrite per archive. */
    private static void deleteSources(final @NotNull List<VFile> packed, final @NotNull JobContext ctx) throws IOException {
        final Map<Path, List<String>> entries = new LinkedHashMap<>();
        for (final VFile f : packed) {
            if (f.isInArchive()) {
                entries.computeIfAbsent(f.getHostPath(), k -> new ArrayList<>()).add(f.getEntry());
                continue;
            }
            ctx.setCurrent("Deleting " + f.getLocation());
            if (!f.isDirectory()) {
                Trash.deleteIfExists(f.getHostPath());
                continue;
            }
            try {
                Files.deleteIfExists(f.getHostPath());
            } catch (final DirectoryNotEmptyException e) {
                // something not packed is left inside (non-recursive packing) — the directory stays
            }
        }
        for (final Map.Entry<Path, List<String>> e : entries.entrySet()) {
            ZipOps.remove(e.getKey(), e.getValue());
        }
    }

    public static void createEmpty(final @NotNull Path archive) throws IOException {
        Files.createDirectories(archive.toAbsolutePath().getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.finish();
        }
        Vfs.invalidate(archive);
    }
}
