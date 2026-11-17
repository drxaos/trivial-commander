package tcm.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * "Open with...": the applications that can open a file, and starting one of them with it. Linux and other Unix desktops read the
 * freedesktop files ({@link XdgApps}), macOS asks Launch Services ({@link MacApps}); Windows has a dialog of its own for this, which
 * also knows the packaged (Store) apps that are invisible to the registry, so there is no list of ours there.
 */
public interface OpenWith {

    /** The apps registered for the file's type; the default app first, flagged. Empty when none or the lookup failed. */
    @NotNull List<App> recommended(@NotNull Path file);

    /** Every application the user could pick, sorted by name. */
    @NotNull List<App> all();

    /** Starts the app with the file and returns at once, without waiting for it. */
    void launch(@NotNull App app, @NotNull Path file) throws IOException;

    /** Null on Windows: {@link #showWindowsDialog} is used there instead. */
    static @Nullable OpenWith forPlatform() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return null;
        }

        return os.contains("mac") ? new MacApps() : new XdgApps();
    }

    /** The system "Open with" dialog of Windows for the file; it starts the chosen app itself. */
    static void showWindowsDialog(final @NotNull Path file) throws IOException {
        new ProcessBuilder(windowsDialogCommand(file.toAbsolutePath().toString())).start();
    }

    /**
     * rundll32 hands OpenAs_RunDLL the raw rest of the command line as the path and the quotes around it are not reliably stripped,
     * while ProcessBuilder quotes every argument that contains a space. So the path goes split at its spaces: arguments are joined
     * with one space and a space-free argument is not quoted, which rebuilds the path exactly. An empty piece (two spaces in a row,
     * a space at either end) would be quoted, though; such a rare path goes as one argument, quoted, and it is up to Windows.
     */
    static @NotNull List<String> windowsDialogCommand(final @NotNull String path) {
        final List<String> pieces = Arrays.asList(path.split(" ", -1));
        final List<String> result = new ArrayList<>(List.of("rundll32.exe", "shell32.dll,OpenAs_RunDLL"));
        if (pieces.contains("")) {
            result.add(path);
        } else {
            result.addAll(pieces);
        }

        return result;
    }

    /** An application in the list; {@link #toString} is the name, so a JList finds it by typing. */
    @Getter
    final class App {

        /** What the platform starts it by: the desktop file id on Linux, the bundle path on macOS. */
        private final @NotNull String id;
        private final @NotNull String name;
        /** The Icon value of a desktop entry or the bundle path; null — no icon. */
        private final @Nullable String icon;
        private final boolean defaultApp;

        public App(final @NotNull String id, final @NotNull String name, final @Nullable String icon, final boolean defaultApp) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.defaultApp = defaultApp;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
