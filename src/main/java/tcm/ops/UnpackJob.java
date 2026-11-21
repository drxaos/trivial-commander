package tcm.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;

import tcm.core.ArchiveIndex;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.core.ZipOps;

/**
 * Unpacking whole archives into a directory (including into another archive); ownDirs — each archive into a subdirectory
 * named after it. A file that does not open as an archive is searched for one inside it (an installer, a self-extracting program).
 */
public final class UnpackJob extends Job {

    /** Compressed tar: the directory is named without both extensions (a.tar.gz → a). */
    private static final List<String> TAR_SUFFIXES = List.of(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.lzma",
        ".tar.z");

    private final @NotNull List<VFile> archives;
    private final @NotNull VFile targetDir;
    private final @NotNull String mask;
    private final boolean ownDirs;

    public UnpackJob(final @NotNull List<VFile> archives, final @NotNull VFile targetDir, final @NotNull String mask, final boolean ownDirs) {
        super("Unpack " + archives.size() + " archive(s) to " + targetDir.getLocation());
        this.archives = List.copyOf(archives);
        this.targetDir = targetDir;
        this.mask = mask;
        this.ownDirs = ownDirs;
    }

    @Override
    public void run(final @NotNull JobContext ctx) throws Exception {
        for (final VFile archive : archives) {
            final Path host = archive.getHostPath();
            final Path path = open(archive, ctx);
            try {
                final VFile root = Vfs.archiveDir(path, "");
                final VFile dir = ownDirs ? subdir(targetDir, dirName(archive.getName())) : targetDir;
                new CopyJob(Vfs.list(root), dir, null, mask, false).run(ctx);
            } finally {
                if (!path.equals(host)) {
                    Vfs.releaseNested(path);
                }
            }
        }
    }

    /** The path to read the archive from: the file itself or a temporary copy of the archive found inside it. */
    private static @NotNull Path open(final @NotNull VFile archive, final @NotNull JobContext ctx) throws IOException {
        final Path host = archive.getHostPath();
        try {
            Vfs.archiveDir(host, "");

            return host;
        } catch (final IOException notArchive) {
            final Path embedded = Vfs.openEmbedded(archive, ctx);
            if (embedded == null) {
                throw notArchive;
            }

            return embedded;
        }
    }

    /** Name of the directory for the archive: without the extension; a name without one gets ".d" (the archive itself is there). */
    public static @NotNull String dirName(final @NotNull String archiveName) {
        final String lower = archiveName.toLowerCase(Locale.ROOT);
        for (final String suffix : TAR_SUFFIXES) {
            if (lower.endsWith(suffix) && lower.length() > suffix.length()) {
                return archiveName.substring(0, archiveName.length() - suffix.length());
            }
        }
        final int dot = archiveName.lastIndexOf('.');

        return dot > 0 ? archiveName.substring(0, dot) : archiveName + ".d";
    }

    private static @NotNull VFile subdir(final @NotNull VFile dir, final @NotNull String name) throws IOException {
        if (dir.isInArchive()) {
            final String entry = ArchiveIndex.join(dir.getEntry(), name);
            if (Vfs.index(dir.getHostPath()).get(entry) == null) {
                ZipOps.mkdir(dir.getHostPath(), entry);
            }

            return Vfs.archiveDir(dir.getHostPath(), entry);
        }
        final Path p = dir.getHostPath().resolve(name);
        Files.createDirectories(p);

        return Vfs.stat(p);
    }
}
