package tcm.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.ArchiveIndex;
import tcm.core.ArchiveReader;
import tcm.core.FileAttrs;
import tcm.core.Progress;
import tcm.core.Streams;
import tcm.core.Trash;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.core.ZipAddition;
import tcm.core.ZipEdit;
import tcm.core.ZipOps;
import tcm.util.Masks;

/**
 * Copy/move between any combination: directory ↔ directory, archive → directory, directory/archive → ZIP/JAR archive.
 */
public final class CopyJob extends Job {

    private final @NotNull List<VFile> sources;
    private final @NotNull VFile targetDir;
    /** New name — only when copying a single object ("copy as"). */
    private final @Nullable String newName;
    private final @NotNull Masks mask;
    private final boolean move;
    private final boolean recursive;
    private final Map<Path, ArchiveReader> openArchives = new HashMap<>();
    private final List<VFile> movedFiles = new ArrayList<>();
    private final List<VFile> movedDirs = new ArrayList<>();

    public CopyJob(
        final @NotNull List<VFile> sources,
        final @NotNull VFile targetDir,
        final @Nullable String newName,
        final @NotNull String mask,
        final boolean move
    ) {
        this(sources, targetDir, newName, mask, move, true);
    }

    public CopyJob(
        final @NotNull List<VFile> sources,
        final @NotNull VFile targetDir,
        final @Nullable String newName,
        final @NotNull String mask,
        final boolean move,
        final boolean recursive
    ) {
        super((move ? "Move " : "Copy ") + sources.size() + " item(s) to " + targetDir.getLocation());
        this.sources = List.copyOf(sources);
        this.targetDir = targetDir;
        this.newName = newName;
        this.mask = Masks.of(mask);
        this.move = move;
        this.recursive = recursive;
    }

    @Override
    public void run(final @NotNull JobContext ctx) throws Exception {
        if (targetDir.isInArchive()) {
            Vfs.checkWritable(targetDir.getHostPath());
        }
        ctx.setCurrent("Counting...");
        ctx.setTotal(countTotal());
        try {
            if (targetDir.isLocal()) {
                for (final VFile src : sources) {
                    copyToLocal(src, targetDir.getHostPath(), targetName(src), ctx);
                }
            } else {
                copyToArchive(ctx);
            }
            if (move) {
                finishMove(ctx);
            }
        } finally {
            closeArchives();
        }
    }

    private @NotNull String targetName(final @NotNull VFile src) {
        return newName != null && sources.size() == 1 ? newName : src.getName();
    }

    private long countTotal() throws IOException {
        long total = 0;
        for (final VFile src : sources) {
            total += countTree(src);
        }

        return total;
    }

    private long countTree(final @NotNull VFile file) throws IOException {
        if (!file.isDirectory()) {
            return mask.matches(file.getName()) ? file.getSize() : 0;
        }
        if (!recursive) {
            return 0;
        }
        long total = 0;
        for (final VFile child : Vfs.list(file)) {
            total += countTree(child);
        }

        return total;
    }

    // ---- to a local directory ----

    private void copyToLocal(final @NotNull VFile src, final @NotNull Path dstDir, final @NotNull String name, final @NotNull JobContext ctx)
        throws IOException {
        ctx.checkpoint();
        final Path dst = dstDir.resolve(name);
        // path comparison does not see "a.txt" and "A.txt" on macOS: overwriting such a "copy" would truncate the source
        if (src.isLocal() && Vfs.isSameFile(src.getHostPath(), dst)) {
            if (!move) {
                throw new IOException("Cannot copy file onto itself: " + dst);
            }
            if (Vfs.isCaseOnlyRename(src.getHostPath(), dst)) {
                final long size = countTree(src);
                ctx.file(src.getLocation(), size);
                Vfs.rename(src.getHostPath(), dst);
                ctx.bytes(size);
            }

            return;
        }
        if (src.isDirectory()) {
            copyDirToLocal(src, dst, ctx);

            return;
        }
        if (!mask.matches(src.getName())) {
            return;
        }
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS) && !ctx.shouldOverwrite(src.getLocation(), dst.toString())) {
            ctx.skip(src.getSize());

            return;
        }
        if (move && src.isLocal() && tryRename(src.getHostPath(), dst)) {
            ctx.file(src.getLocation(), src.getSize());
            ctx.bytes(src.getSize());

            return;
        }
        ctx.file(src.getLocation(), src.getSize());
        try (InputStream in = open(src);
             OutputStream out = Files.newOutputStream(dst)) {
            Streams.copy(in, out, ctx);
        }
        Files.setLastModifiedTime(dst, FileTime.fromMillis(src.getModified()));
        if (src.isInArchive()) {
            FileAttrs.apply(dst, src.getAttrs());
        }
        if (move) {
            movedFiles.add(src);
        }
    }

    private void copyDirToLocal(final @NotNull VFile src, final @NotNull Path dst, final @NotNull JobContext ctx) throws IOException {
        if (src.isLocal() && dst.toAbsolutePath().normalize().startsWith(src.getHostPath().toAbsolutePath().normalize())) {
            throw new IOException("Cannot copy a directory into itself: " + src.getLocation());
        }
        // fast path: rename the whole directory on the same partition
        if (move && src.isLocal() && mask.isMatchAll() && !Files.exists(dst, LinkOption.NOFOLLOW_LINKS)
            && tryRename(src.getHostPath(), dst)) {
            ctx.bytes(countTree(Vfs.stat(dst)));

            return;
        }
        if (!recursive) {
            return;
        }
        Files.createDirectories(dst);
        for (final VFile child : Vfs.list(src)) {
            copyToLocal(child, dst, child.getName(), ctx);
        }
        Files.setLastModifiedTime(dst, FileTime.fromMillis(src.getModified()));
        // after the contents: a directory without write permission would not accept them
        if (src.isInArchive()) {
            FileAttrs.apply(dst, src.getAttrs());
        }
        if (move) {
            movedDirs.add(src);
        }
    }

    private static boolean tryRename(final @NotNull Path src, final @NotNull Path dst) {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);

            return true;
        } catch (final IOException e) {
            // another partition or a non-empty target directory — copy and delete
            return false;
        }
    }

    // ---- to an archive ----

    private void copyToArchive(final @NotNull JobContext ctx) throws IOException {
        final Path archive = targetDir.getHostPath();
        final ArchiveIndex index = Vfs.index(archive);
        final ZipEdit edit = new ZipEdit();
        final List<Path> staged = new ArrayList<>();
        try {
            for (final VFile src : sources) {
                ctx.checkpoint();
                final String entry = ArchiveIndex.join(targetDir.getEntry(), targetName(src));
                if (src.isInArchive() && src.getHostPath().equals(archive)) {
                    addFromSameArchive(src, entry, index, edit, staged, ctx);
                } else {
                    collectAdditions(src, entry, index, edit, ctx);
                }
            }
            ZipOps.apply(archive, edit, ctx);
        } finally {
            for (final Path dir : staged) {
                Trash.deleteTree(dir, Progress.NONE);
            }
        }
    }

    private void addFromSameArchive(
        final @NotNull VFile src,
        final @NotNull String entry,
        final @NotNull ArchiveIndex index,
        final @NotNull ZipEdit edit,
        final @NotNull List<Path> staged,
        final @NotNull JobContext ctx
    ) throws IOException {
        if (entry.equals(src.getEntry())) {
            if (move) {
                return;
            }
            throw new IOException("Cannot copy file onto itself: " + src.getLocation());
        }
        if (entry.startsWith(src.getEntry() + "/")) {
            throw new IOException("Cannot copy a directory into itself: " + src.getLocation());
        }
        if (move && mask.isMatchAll()) {
            final VFile existing = index.get(entry);
            if (existing != null) {
                if (!ctx.shouldOverwrite(src.getLocation(), existing.getLocation())) {
                    ctx.skip(countTree(src));

                    return;
                }
                edit.remove(entry);
            }
            edit.rename(src.getEntry(), entry);
            ctx.bytes(countTree(src));

            return;
        }
        // copy within the same archive: unpack to a temporary directory first, since the archive will be rewritten
        final Path tmp = Files.createTempDirectory("tcm-stage");
        staged.add(tmp);
        ZipOps.extract(src.getHostPath(), src.getEntry(), tmp, Progress.ticksOnly(ctx));
        final VFile local = Vfs.stat(tmp.resolve(src.getName()));
        final int filesBefore = movedFiles.size();
        final int dirsBefore = movedDirs.size();
        collectAdditions(local, entry, index, edit, ctx);
        if (move) {
            // the original archive entries must be deleted, not the temporary unpacked copy
            movedFiles.subList(filesBefore, movedFiles.size()).clear();
            movedDirs.subList(dirsBefore, movedDirs.size()).clear();
            markMovedTree(src);
        }
    }

    private void collectAdditions(
        final @NotNull VFile src,
        final @NotNull String entry,
        final @NotNull ArchiveIndex index,
        final @NotNull ZipEdit edit,
        final @NotNull JobContext ctx
    ) throws IOException {
        ctx.checkpoint();
        if (src.isDirectory()) {
            if (!recursive) {
                return;
            }
            edit.add(ZipAddition.dir(entry, src.getModified()));
            for (final VFile child : Vfs.list(src)) {
                collectAdditions(child, entry + "/" + child.getName(), index, edit, ctx);
            }
            if (move) {
                movedDirs.add(src);
            }

            return;
        }
        if (!mask.matches(src.getName())) {
            return;
        }
        final VFile existing = index.get(entry);
        if (existing != null && !ctx.shouldOverwrite(src.getLocation(), existing.getLocation())) {
            ctx.skip(src.getSize());

            return;
        }
        edit.add(ZipAddition.file(entry, src.getSize(), src.getModified(), () -> open(src)));
        if (move) {
            movedFiles.add(src);
        }
    }

    private void markMovedTree(final @NotNull VFile src) throws IOException {
        if (!src.isDirectory()) {
            if (mask.matches(src.getName())) {
                movedFiles.add(src);
            }

            return;
        }
        for (final VFile child : Vfs.list(src)) {
            markMovedTree(child);
        }
        movedDirs.add(src);
    }

    // ---- finishing a move: deleting the sources ----

    private void finishMove(final @NotNull JobContext ctx) throws IOException {
        final Map<Path, ZipEdit> archiveEdits = new LinkedHashMap<>();
        final Map<Path, Set<String>> movedEntries = new HashMap<>();
        for (final VFile f : movedFiles) {
            if (f.isLocal()) {
                ctx.setCurrent("Deleting " + f.getLocation());
                Files.deleteIfExists(f.getHostPath());
            } else {
                archiveEdits.computeIfAbsent(f.getHostPath(), k -> new ZipEdit()).remove(f.getEntry());
                movedEntries.computeIfAbsent(f.getHostPath(), k -> new HashSet<>()).add(f.getEntry());
            }
        }
        for (int i = movedDirs.size() - 1; i >= 0; i--) {
            final VFile d = movedDirs.get(i);
            if (d.isLocal()) {
                try {
                    Files.deleteIfExists(d.getHostPath());
                } catch (final DirectoryNotEmptyException e) {
                    // skipped files remain in the directory — keep the directory
                }
            } else if (allFilesMoved(d, movedEntries.getOrDefault(d.getHostPath(), Set.of()))) {
                archiveEdits.computeIfAbsent(d.getHostPath(), k -> new ZipEdit()).remove(d.getEntry());
            }
        }
        closeArchives();
        for (final Map.Entry<Path, ZipEdit> e : archiveEdits.entrySet()) {
            ctx.setCurrent("Updating " + e.getKey());
            ZipOps.apply(e.getKey(), e.getValue(), Progress.ticksOnly(ctx));
        }
    }

    private static boolean allFilesMoved(final @NotNull VFile dir, final @NotNull Set<String> moved) throws IOException {
        for (final VFile f : Vfs.index(dir.getHostPath()).subtree(dir.getEntry()).values()) {
            if (!f.isDirectory() && !moved.contains(f.getEntry())) {
                return false;
            }
        }

        return true;
    }

    // ---- reading sources ----

    private @NotNull InputStream open(final @NotNull VFile src) throws IOException {
        if (src.isLocal()) {
            return Files.newInputStream(src.getHostPath());
        }
        ArchiveReader reader = openArchives.get(src.getHostPath());
        if (reader == null) {
            reader = Vfs.openReader(src.getHostPath());
            openArchives.put(src.getHostPath(), reader);
        }

        return reader.open(src.getEntry());
    }

    private void closeArchives() {
        for (final ArchiveReader reader : openArchives.values()) {
            try {
                reader.close();
            } catch (final IOException e) {
                // the archive is open read-only — a close error affects nothing
            }
        }
        openArchives.clear();
    }
}
