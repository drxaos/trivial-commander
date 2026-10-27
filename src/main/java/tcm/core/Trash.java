package tcm.core;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;

/**
 * Deleting to the trash: via java.awt.Desktop, and where it cannot (Linux/GTK) — per the freedesktop.org Trash specification.
 */
public final class Trash {

    private Trash() {
    }

    /** false — the trash is unavailable for this file (another partition, wrong OS), nothing was deleted. */
    public static boolean moveToTrash(final @NotNull Path path) throws IOException {
        if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
            && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            return Desktop.getDesktop().moveToTrash(path.toFile());
        }
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") || os.contains("mac")) {
            return false;
        }

        return moveToXdgTrash(path.toAbsolutePath().normalize());
    }

    public static void deleteTree(final @NotNull Path path, final @NotNull Progress progress) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (final Path child : stream) {
                    deleteTree(child, progress);
                }
            }
        }
        progress.file(path.toString(), 0);
        Files.delete(path);
    }

    private static boolean moveToXdgTrash(final @NotNull Path path) throws IOException {
        final String dataHome = System.getenv("XDG_DATA_HOME");
        final Path base = dataHome == null || dataHome.isBlank()
            ? Path.of(System.getProperty("user.home"), ".local", "share")
            : Path.of(dataHome);
        final Path trash = base.resolve("Trash");
        final Path files = trash.resolve("files");
        final Path info = trash.resolve("info");
        Files.createDirectories(files);
        Files.createDirectories(info);
        // moving across partitions would turn into a long copy — such a file is not put into the trash
        if (!Files.getFileStore(path).equals(Files.getFileStore(files))) {
            return false;
        }
        final String name = String.valueOf(path.getFileName());
        String candidate = name;
        int n = 1;
        Path infoFile = info.resolve(candidate + ".trashinfo");
        while (Files.exists(files.resolve(candidate), LinkOption.NOFOLLOW_LINKS) || Files.exists(infoFile)) {
            candidate = name + "." + n++;
            infoFile = info.resolve(candidate + ".trashinfo");
        }
        final String date = LocalDateTime.now()
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        final String content = "[Trash Info]\nPath=" + path.toUri().getRawPath().replaceAll("/$", "") + "\nDeletionDate=" + date + "\n";
        Files.writeString(infoFile, content, StandardCharsets.UTF_8);
        try {
            Files.move(path, files.resolve(candidate));
        } catch (final IOException e) {
            Files.deleteIfExists(infoFile);
            throw e;
        }

        return true;
    }
}
