package tcm.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

/**
 * Menu entry for a running AppImage. An AppImage started from a file manager has no .desktop file, so it is missing
 * from the application menu and docks cannot match its window. On every launch the AppImage writes its own entry into
 * $XDG_DATA_HOME/applications, rewrites it when the AppImage has moved, and withdraws it once another integration
 * (Gear Lever, AppImageLauncher, appimaged, a system package) owns the app. Files without our marker are never touched.
 */
public final class AppImageDesktopEntry {

    /** What {@link #reconcile} found and did. */
    public enum Status {
        NOT_APPIMAGE,
        EXTERNAL,
        HIDDEN_BY_USER,
        NO_ICON,
        INTEGRATED
    }

    static final String DESKTOP_FILE_NAME = "tcm.desktop";
    static final String MANAGED_KEY = "X-Tcm-Managed";
    /** The X11 WM_CLASS AWT derives from the main class tcm.Main. */
    static final String WM_CLASS = "tcm-Main";
    private static final String ICON_FILE_NAME = "tcm.svg";
    private static final String ICON_RELATIVE_PATH = "icons/hicolor/scalable/apps/" + ICON_FILE_NAME;

    private AppImageDesktopEntry() {
    }

    /**
     * Brings the user's menu entry in step with the running AppImage. Runs on a daemon thread, so a slow home
     * directory does not hold up the window; a failure only means the menu has no entry.
     */
    public static void reconcileInBackground() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return;
        }
        final Thread t = new Thread(() -> {
            try {
                reconcile(System.getenv(), Path.of(System.getProperty("user.home")));
            } catch (final IOException | RuntimeException e) {
                // the menu entry is a convenience: the program works the same without it
            }
        }, "desktop-entry");
        t.setDaemon(true);
        t.start();
    }

    @NotNull
    public static Status reconcile(final @NotNull Map<String, String> env, final @NotNull Path homeDir) throws IOException {
        final Path dataHome = dataHome(env, homeDir);
        final Path desktopPath = dataHome.resolve("applications").resolve(DESKTOP_FILE_NAME);
        final Path iconPath = dataHome.resolve(ICON_RELATIVE_PATH);

        final String appImage = env.getOrDefault("APPIMAGE", "").trim();
        final String appDir = env.getOrDefault("APPDIR", "").trim();
        if (!isAbsolute(appImage) || !isAbsolute(appDir) || appImage.contains("\n") || appImage.contains("\r")) {
            // A system package ships its own tcm.desktop, which an entry left by an earlier AppImage run would hide.
            // An extracted AppImage also runs without APPIMAGE: it keeps the entry.
            if (findInstalledPackageEntry(env, desktopPath).isPresent()) {
                removeManagedEntry(desktopPath, iconPath);
            }

            return Status.NOT_APPIMAGE;
        }
        final Path appImagePath = Path.of(appImage);

        // Gear Lever and the old AppImageKit integration launch with this set
        if (!env.getOrDefault("DESKTOPINTEGRATION", "").isBlank()) {
            removeManagedEntry(desktopPath, iconPath);

            return Status.EXTERNAL;
        }

        final Optional<Map<String, String>> ownKeys = readFile(desktopPath).map(AppImageDesktopEntry::readDesktopKeys);
        if (ownKeys.isPresent() && !isManaged(ownKeys.get())) {
            return Status.EXTERNAL;
        }
        // menu editors hide an entry by writing one of these keys into it
        if (ownKeys.isPresent() && ("true".equals(ownKeys.get().get("NoDisplay")) || "true".equals(ownKeys.get().get("Hidden")))) {
            return Status.HIDDEN_BY_USER;
        }

        if (findInstalledPackageEntry(env, desktopPath).isPresent()
            || findForeignEntry(desktopPath.getParent(), desktopPath, appImage).isPresent()) {
            removeManagedEntry(desktopPath, iconPath);

            return Status.EXTERNAL;
        }

        final Optional<byte[]> icon = readFile(Path.of(appDir, ICON_FILE_NAME));
        if (icon.isEmpty()) {
            return Status.NO_ICON;
        }
        // icon first: the menu reads the entry as soon as it appears
        writeIfChanged(iconPath, icon.get());
        writeIfChanged(desktopPath, buildEntry(appImagePath, iconPath).getBytes(StandardCharsets.UTF_8));

        return Status.INTEGRATED;
    }

    @NotNull
    static String buildEntry(final @NotNull Path appImagePath, final @NotNull Path iconPath) {
        return String.join("\n",
            "[Desktop Entry]",
            "Type=Application",
            "Name=Trivial Commander",
            "Comment=Two-panel file manager",
            "Exec=" + escapeString(quoteExecArgument(appImagePath.toString())) + " %F",
            "TryExec=" + escapeString(appImagePath.toString()),
            "Icon=" + escapeString(iconPath.toString()),
            "Terminal=false",
            "StartupWMClass=" + WM_CLASS,
            "Categories=System;FileTools;FileManager;",
            MANAGED_KEY + "=true",
            "");
    }

    private static Path dataHome(final @NotNull Map<String, String> env, final @NotNull Path homeDir) {
        final String value = env.getOrDefault("XDG_DATA_HOME", "").trim();

        return isAbsolute(value) ? Path.of(value) : homeDir.resolve(".local").resolve("share");
    }

    private static boolean isAbsolute(final @NotNull String path) {
        return !path.isEmpty() && Path.of(path).isAbsolute();
    }

    /** Desktop Entry spec: a string value escapes backslashes. */
    private static String escapeString(final @NotNull String value) {
        return value.replace("\\", "\\\\");
    }

    /** Desktop Entry spec: an Exec argument is double-quoted with ", `, $ and \ escaped, and a literal % is written %%. */
    private static String quoteExecArgument(final @NotNull String value) {
        return ("\"" + value.replaceAll("([\"`$\\\\])", "\\\\$1") + "\"").replace("%", "%%");
    }

    /** Keys of the [Desktop Entry] group; the first occurrence of a key wins. */
    static Map<String, String> readDesktopKeys(final byte @NotNull [] contents) {
        final Map<String, String> keys = new HashMap<>();
        boolean inMainGroup = false;
        for (final String rawLine : new String(contents, StandardCharsets.UTF_8).split("\n")) {
            final String line = rawLine.trim();
            if (line.startsWith("[")) {
                inMainGroup = line.equals("[Desktop Entry]");
                continue;
            }
            final int separator = line.indexOf('=');
            if (inMainGroup && separator > 0) {
                keys.putIfAbsent(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }

        return keys;
    }

    private static boolean isManaged(final @NotNull Map<String, String> keys) {
        return "true".equals(keys.get(MANAGED_KEY));
    }

    private static Optional<byte[]> readFile(final @NotNull Path path) throws IOException {
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (final NoSuchFileException | NotDirectoryException e) {
            return Optional.empty();
        }
    }

    /**
     * Another menu entry for this app in the user's applications folder, where Gear Lever, AppImageLauncher and
     * appimaged write theirs: one that claims our window class, or one that starts this very AppImage.
     */
    private static Optional<Path> findForeignEntry(
        final @NotNull Path directory,
        final @NotNull Path ownPath,
        final @NotNull String appImage
    ) {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.desktop")) {
            for (final Path entry : entries) {
                if (!entry.equals(ownPath) && isForeignEntry(entry, appImage)) {
                    return Optional.of(entry);
                }
            }
        } catch (final IOException e) {
            // an unreadable folder hides nothing we could detect
        }

        return Optional.empty();
    }

    private static boolean isForeignEntry(final @NotNull Path entry, final @NotNull String appImage) {
        final Map<String, String> keys;
        try {
            keys = readDesktopKeys(Files.readAllBytes(entry));
        } catch (final IOException e) {
            return false;
        }
        if (isManaged(keys)) {
            return false;
        }
        final boolean launchesAppImage = Stream.of(keys.get("Exec"), keys.get("TryExec"))
            .anyMatch(value -> value != null && value.contains(appImage));

        return WM_CLASS.equals(keys.get("StartupWMClass")) || launchesAppImage;
    }

    /** A system package installs its entry under the same name in a system data directory; a user entry would hide it. */
    private static Optional<Path> findInstalledPackageEntry(final @NotNull Map<String, String> env, final @NotNull Path ownPath) {
        final String dirs = env.getOrDefault("XDG_DATA_DIRS", "").isBlank()
            ? "/usr/local/share:/usr/share"
            : env.get("XDG_DATA_DIRS");

        final String sep = java.io.File.pathSeparatorChar == ';' ? ";" : ":";
        return Arrays.stream(dirs.split(sep))
            .filter(AppImageDesktopEntry::isAbsolute)
            .map(dir -> Path.of(dir, "applications", DESKTOP_FILE_NAME))
            .filter(path -> !path.toAbsolutePath().normalize().equals(ownPath.toAbsolutePath().normalize()))
            .filter(Files::isRegularFile)
            .findFirst();
    }

    private static void writeIfChanged(final @NotNull Path path, final byte @NotNull [] contents) throws IOException {
        final Optional<byte[]> current = readFile(path);
        if (current.isPresent() && Arrays.equals(current.get(), contents)) {
            return;
        }
        Files.createDirectories(path.getParent());
        final Path temp = path.resolveSibling(path.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.write(temp, contents);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static void removeManagedEntry(final @NotNull Path desktopPath, final @NotNull Path iconPath) throws IOException {
        final Optional<byte[]> contents = readFile(desktopPath);
        if (contents.isEmpty() || !isManaged(readDesktopKeys(contents.get()))) {
            return;
        }
        Files.deleteIfExists(desktopPath);
        Files.deleteIfExists(iconPath);
    }
}
