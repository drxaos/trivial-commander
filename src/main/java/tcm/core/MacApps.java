package tcm.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applications for a file on macOS. Launch Services is asked through osascript (JavaScript for Automation and its ObjC bridge to
 * NSWorkspace): Java has no API for it, and the JDK's own AppKit bridge has no such call. All applications are the bundles in the
 * usual folders; the chosen one is started with "open -a".
 */
final class MacApps implements OpenWith {

    private static final long TIMEOUT_SECONDS = 5;
    private static final String SCRIPT = String.join("\n",
        "ObjC.import('AppKit');",
        "function run(argv) {",
        "    var ws = $.NSWorkspace.sharedWorkspace;",
        "    var url = $.NSURL.fileURLWithPath(argv[0]);",
        "    var out = [];",
        "    try { var def = ws.URLForApplicationToOpenURL(url); if (def && !def.isNil()) out.push(def.path.js); } catch (e) {}",
        "    try {",
        "        var apps = ws.URLsForApplicationsToOpenURL(url);",
        "        for (var i = 0; i < apps.count; i++) out.push(apps.objectAtIndex(i).path.js);",
        "    } catch (e) {}",
        "    return out.join('\\n');",
        "}");

    @Override
    public @NotNull List<App> recommended(final @NotNull Path file) {
        final List<App> result = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        // the first line is the default application, the rest repeat it among the others
        for (final String line : askLaunchServices(file)) {
            final String bundle = line.trim();
            if (!bundle.isEmpty() && seen.add(bundle)) {
                result.add(app(Path.of(bundle), result.isEmpty()));
            }
        }

        return result;
    }

    @Override
    public @NotNull List<App> all() {
        final Set<Path> bundles = new LinkedHashSet<>();
        final List<Path> roots = List.of(Path.of("/Applications"), Path.of("/System/Applications"),
            Path.of(System.getProperty("user.home"), "Applications"));
        for (final Path root : roots) {
            for (final Path p : list(root)) {
                if (isBundle(p)) {
                    bundles.add(p);
                } else if (Files.isDirectory(p)) {
                    // Utilities and vendor folders hold apps one level down
                    list(p).stream()
                        .filter(MacApps::isBundle)
                        .forEach(bundles::add);
                }
            }
        }

        return bundles.stream()
            .map(p -> app(p, false))
            .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()))
            .toList();
    }

    @Override
    public void launch(final @NotNull App app, final @NotNull Path file) throws IOException {
        new ProcessBuilder("open", "-a", app.getId(), file.toAbsolutePath().toString())
            .redirectInput(new File("/dev/null"))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    }

    /** Bundle paths, the default first; empty on any failure — the dialog still offers all applications. */
    private static @NotNull List<String> askLaunchServices(final @NotNull Path file) {
        Path out = null;
        try {
            out = Files.createTempFile("tcm-openwith", ".txt");
            final Process p = new ProcessBuilder("osascript", "-l", "JavaScript", "-e", SCRIPT, file.toAbsolutePath().toString())
                .redirectInput(new File("/dev/null"))
                .redirectOutput(out.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();

                return List.of();
            }

            return p.exitValue() == 0 ? Files.readString(out, StandardCharsets.UTF_8).lines().toList() : List.of();
        } catch (final IOException | RuntimeException e) {
            return List.of();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();

            return List.of();
        } finally {
            deleteQuietly(out);
        }
    }

    private static void deleteQuietly(final @Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (final IOException e) {
            // a leftover temp file is harmless
        }
    }

    private static @NotNull App app(final @NotNull Path bundle, final boolean defaultApp) {
        final String fileName = String.valueOf(bundle.getFileName());
        final String name = fileName.endsWith(".app") ? fileName.substring(0, fileName.length() - 4) : fileName;

        return new App(bundle.toString(), name, bundle.toString(), defaultApp);
    }

    private static boolean isBundle(final @NotNull Path p) {
        return p.getFileName().toString().endsWith(".app") && Files.isDirectory(p);
    }

    private static @NotNull List<Path> list(final @NotNull Path dir) {
        final List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(result::add);
        } catch (final IOException | RuntimeException e) {
            // an unreadable folder adds no applications
        }

        return result;
    }
}
