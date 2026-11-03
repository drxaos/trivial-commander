package tcm.ui;

import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.Icon;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;

/**
 * Freedesktop file icons: the MIME type by name from shared-mime-info (globs2), the icon name from the MIME type, the icon file
 * from the current icon theme and the themes it inherits, hicolor last. PNG and SVG icons (rendered with jsvg).
 * Files are matched by name only, without reading their content, so archive entries get icons too.
 */
final class FreedesktopIcons implements SystemIcons.Provider {

    private static final String GLOB_CHARS = "*?[";
    private static final Set<String> ICON_CONTEXTS = Set.of("MimeTypes", "Legacy");

    private final int size;
    private final List<Path> dataDirs = dataDirs();
    /** Literal names ("Makefile") and extensions ("tar.gz") → MIME type; case-sensitive ones are kept as is, others in lower case. */
    private final Map<String, String> namesCs = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> extsCs = new HashMap<>();
    private final Map<String, String> exts = new HashMap<>();
    private final Map<String, String> customIcons = new HashMap<>();
    private final Map<String, String> genericIcons = new HashMap<>();
    private final List<Theme> themes = new ArrayList<>();

    FreedesktopIcons(final int size) {
        this(size, themeName());
    }

    FreedesktopIcons(final int size, final @NotNull String theme) {
        this.size = size;
        for (final Path dir : dataDirs) {
            final Path mime = dir.resolve("mime");
            readGlobs(mime.resolve("globs2"));
            readPairs(mime.resolve("icons"), customIcons);
            readPairs(mime.resolve("generic-icons"), genericIcons);
        }
        loadThemes(theme);
        if (exts.isEmpty() || themes.isEmpty()) {
            throw new IllegalStateException("no MIME database or icon theme");
        }
    }

    @Override
    public @Nullable String key(final @NotNull VFile file) {
        final String name = file.getName();
        final String lower = name.toLowerCase(Locale.ROOT);
        String mime = namesCs.get(name);
        if (mime == null) {
            mime = names.get(lower);
        }
        // the longest matching extension wins: "a.tar.gz" is a compressed tar, not just gzip
        for (int dot = name.indexOf('.'); mime == null && dot >= 0; dot = name.indexOf('.', dot + 1)) {
            mime = extsCs.get(name.substring(dot + 1));
            if (mime == null) {
                mime = exts.get(lower.substring(dot + 1));
            }
        }

        return mime;
    }

    @Override
    public @Nullable Icon load(final @NotNull String mime, final @NotNull VFile file) throws IOException {
        for (final String iconName : iconNames(mime)) {
            for (final Theme theme : themes) {
                final Path found = theme.find(iconName, size);
                final BufferedImage img = found == null ? null : read(found);
                if (img != null) {
                    return SystemIcons.toIcon(img);
                }
            }
        }

        return null;
    }

    private @Nullable BufferedImage read(final @NotNull Path file) throws IOException {
        if (!file.getFileName().toString().endsWith(".svg")) {
            return ImageIO.read(file.toFile());
        }
        try (InputStream in = Files.newInputStream(file)) {
            return SvgImages.render(in, file.toUri(), size);
        }
    }

    private @NotNull List<String> iconNames(final @NotNull String mime) {
        final Set<String> result = new LinkedHashSet<>();
        final String custom = customIcons.get(mime);
        if (custom != null) {
            result.add(custom);
        }
        result.add(mime.replace('/', '-'));
        final String generic = genericIcons.get(mime);
        if (generic != null) {
            result.add(generic);
        }
        final int slash = mime.indexOf('/');
        if (slash > 0) {
            result.add(mime.substring(0, slash) + "-x-generic");
        }

        return new ArrayList<>(result);
    }

    /** Lines "weight:mime:glob[:flags]", sorted by weight: the first pattern wins, as do earlier (user) directories. */
    private void readGlobs(final @NotNull Path file) {
        for (final String line : lines(file)) {
            final String[] parts = line.split(":");
            if (parts.length < 3 || line.startsWith("#")) {
                continue;
            }
            final String mime = parts[1];
            final String glob = parts[2];
            final boolean cs = parts.length > 3 && parts[3].contains("cs");
            if (glob.startsWith("*.") && !containsGlob(glob.substring(2))) {
                final String ext = glob.substring(2);
                (cs ? extsCs : exts).putIfAbsent(cs ? ext : ext.toLowerCase(Locale.ROOT), mime);
            } else if (!containsGlob(glob)) {
                (cs ? namesCs : names).putIfAbsent(cs ? glob : glob.toLowerCase(Locale.ROOT), mime);
            }
        }
    }

    private static boolean containsGlob(final @NotNull String s) {
        return s.chars().anyMatch(c -> GLOB_CHARS.indexOf(c) >= 0);
    }

    /** Lines "mime:icon-name". */
    private static void readPairs(final @NotNull Path file, final @NotNull Map<String, String> target) {
        for (final String line : lines(file)) {
            final int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith("#")) {
                target.putIfAbsent(line.substring(0, colon), line.substring(colon + 1).trim());
            }
        }
    }

    private static @NotNull List<String> lines(final @NotNull Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (final IOException e) {
            return List.of();
        }
    }

    /** The theme, the themes it inherits (depth first, each once), then hicolor — the spec's fallback for every theme. */
    private void loadThemes(final @NotNull String name) {
        final List<Path> bases = iconBaseDirs();
        final Set<String> seen = new LinkedHashSet<>();
        addTheme(name, bases, seen);
        addTheme("hicolor", bases, seen);
    }

    private void addTheme(final @NotNull String name, final @NotNull List<Path> bases, final @NotNull Set<String> seen) {
        if (!seen.add(name)) {
            return;
        }
        final List<Path> roots = new ArrayList<>();
        Path index = null;
        for (final Path base : bases) {
            final Path root = base.resolve(name);
            if (Files.isDirectory(root)) {
                roots.add(root);
                if (index == null && Files.isRegularFile(root.resolve("index.theme"))) {
                    index = root.resolve("index.theme");
                }
            }
        }
        if (index == null) {
            return;
        }
        final Map<String, Map<String, String>> ini = readIni(index);
        final Map<String, String> head = ini.getOrDefault("Icon Theme", Map.of());
        themes.add(new Theme(roots, ini, head.getOrDefault("Directories", "")));
        for (final String parent : head.getOrDefault("Inherits", "").split(",")) {
            if (!parent.isBlank()) {
                addTheme(parent.trim(), bases, seen);
            }
        }
    }

    private static @NotNull Map<String, Map<String, String>> readIni(final @NotNull Path file) {
        final Map<String, Map<String, String>> result = new HashMap<>();
        Map<String, String> section = new HashMap<>();
        for (final String raw : lines(file)) {
            final String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = result.computeIfAbsent(line.substring(1, line.length() - 1), k -> new HashMap<>());
            } else {
                final int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) {
                    section.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }

        return result;
    }

    /** $XDG_DATA_HOME, then $XDG_DATA_DIRS — in priority order. */
    private static @NotNull List<Path> dataDirs() {
        final Set<Path> result = new LinkedHashSet<>();
        final String home = System.getProperty("user.home");
        final String dataHome = System.getenv("XDG_DATA_HOME");
        result.add(dataHome != null && !dataHome.isBlank() ? Path.of(dataHome) : Path.of(home, ".local", "share"));
        final String dirs = System.getenv("XDG_DATA_DIRS");
        for (final String d : (dirs != null && !dirs.isBlank() ? dirs : "/usr/local/share:/usr/share").split(":")) {
            if (!d.isBlank()) {
                result.add(Path.of(d));
            }
        }

        return new ArrayList<>(result);
    }

    private @NotNull List<Path> iconBaseDirs() {
        final List<Path> result = new ArrayList<>();
        result.add(Path.of(System.getProperty("user.home"), ".icons"));
        for (final Path dir : dataDirs) {
            result.add(dir.resolve("icons"));
        }

        return result;
    }

    /** -Dtcm.iconTheme, XSETTINGS (GNOME, Xfce, KDE's gtk bridge), then KDE's config, then GSettings. */
    private static @NotNull String themeName() {
        final String forced = System.getProperty("tcm.iconTheme");
        if (forced != null && !forced.isBlank()) {
            return forced;
        }
        final Object xsettings = Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Net/IconThemeName");
        if (xsettings instanceof String s && !s.isBlank()) {
            return s;
        }
        final String configHome = System.getenv("XDG_CONFIG_HOME");
        final Path config = configHome != null && !configHome.isBlank()
            ? Path.of(configHome)
            : Path.of(System.getProperty("user.home"), ".config");
        final String kde = readIni(config.resolve("kdeglobals")).getOrDefault("Icons", Map.of()).get("Theme");
        if (kde != null && !kde.isBlank()) {
            return kde;
        }
        final String gnome = gsettingsTheme();

        return gnome != null ? gnome : "hicolor";
    }

    private static @Nullable String gsettingsTheme() {
        try {
            final Process p = new ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "icon-theme")
                .redirectErrorStream(true)
                .start();
            final String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            if (!p.waitFor(2, TimeUnit.SECONDS) || p.exitValue() != 0 || out == null) {
                p.destroy();

                return null;
            }
            final String name = out.trim().replace("'", "");

            return name.isBlank() ? null : name;
        } catch (final IOException e) {
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();

            return null;
        }
    }

    /** One theme: its icon directories with the MimeTypes context; icon names in a directory are listed on first use. */
    private static final class Theme {

        private final List<Dir> dirs = new ArrayList<>();

        private Theme(
            final @NotNull List<Path> roots,
            final @NotNull Map<String, Map<String, String>> ini,
            final @NotNull String directories
        ) {
            for (final String d : directories.split(",")) {
                final Map<String, String> props = ini.get(d.trim());
                if (d.isBlank() || props == null || !ICON_CONTEXTS.contains(props.get("Context"))
                    || parseInt(props.get("Scale"), 1) != 1) {
                    continue;
                }
                final List<Path> paths = roots.stream()
                    .map(r -> r.resolve(d.trim()))
                    .filter(Files::isDirectory)
                    .toList();
                if (!paths.isEmpty()) {
                    dirs.add(new Dir(paths, props));
                }
            }
        }

        /** An exact size match, otherwise the closest size in this theme (as the icon theme spec does). */
        private @Nullable Path find(final @NotNull String name, final int size) {
            Path best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (final Dir dir : dirs) {
                final Path file = dir.files().get(name);
                if (file == null) {
                    continue;
                }
                final int distance = dir.distance(size);
                if (distance == 0) {
                    return file;
                }
                if (distance < bestDistance) {
                    best = file;
                    bestDistance = distance;
                }
            }

            return best;
        }
    }

    private static final class Dir {

        private final List<Path> paths;
        private final String type;
        private final int size;
        private final int min;
        private final int max;
        private final int threshold;
        private @Nullable Map<String, Path> files;

        private Dir(final @NotNull List<Path> paths, final @NotNull Map<String, String> props) {
            this.paths = paths;
            this.type = props.getOrDefault("Type", "Threshold");
            this.size = parseInt(props.get("Size"), 0);
            this.min = parseInt(props.get("MinSize"), size);
            this.max = parseInt(props.get("MaxSize"), size);
            this.threshold = parseInt(props.get("Threshold"), 2);
        }

        private int distance(final int s) {
            switch (type) {
                case "Fixed":
                    return Math.abs(size - s);
                case "Scalable":
                    return s < min ? min - s : Math.max(0, s - max);
                default:
                    if (s < size - threshold) {
                        return min - s;
                    }

                    return s > size + threshold ? s - max : 0;
            }
        }

        private @NotNull Map<String, Path> files() {
            if (files == null) {
                final Map<String, Path> result = new LinkedHashMap<>();
                for (final Path p : paths) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(p, "*.{png,svg}")) {
                        for (final Path f : stream) {
                            final String n = f.getFileName().toString();
                            // PNG is drawn for this exact size, SVG is the fallback
                            final Path old = result.putIfAbsent(n.substring(0, n.length() - 4), f);
                            if (old != null && old.toString().endsWith(".svg") && n.endsWith(".png")) {
                                result.put(n.substring(0, n.length() - 4), f);
                            }
                        }
                    } catch (final IOException e) {
                        // an unreadable icon directory is skipped
                    }
                }
                files = result;
            }

            return files;
        }
    }

    private static int parseInt(final @Nullable String s, final int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (final NumberFormatException e) {
            return def;
        }
    }
}
