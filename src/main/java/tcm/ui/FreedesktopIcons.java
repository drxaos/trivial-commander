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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.Icon;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;
import tcm.core.XdgMime;

/**
 * Freedesktop file icons: the MIME type by name from shared-mime-info (globs2, see {@link XdgMime}), the icon name from the MIME type,
 * the icon file from the current icon theme and the themes it inherits, hicolor last. PNG and SVG icons (rendered with jsvg).
 * Files are matched by name only, without reading their content, so archive entries get icons too.
 */
final class FreedesktopIcons implements SystemIcons.Provider {

    /** Panel icons come from these contexts only: fewer directories to list, and no application icon stands in for a file type. */
    private static final Set<String> MIME_CONTEXTS = Set.of("MimeTypes", "Legacy");
    private static final Path PIXMAPS = Path.of("/usr/share/pixmaps");
    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".svg", ".xpm");

    /** Themes indexed in every context, for the icons of desktop entries; built on first use. */
    private static @Nullable List<Theme> appThemes;

    private final int size;
    private final XdgMime mimeDb;
    private final Map<String, String> customIcons = new HashMap<>();
    private final Map<String, String> genericIcons = new HashMap<>();
    private final List<Theme> themes;

    FreedesktopIcons(final int size) {
        this(size, themeName());
    }

    FreedesktopIcons(final int size, final @NotNull String theme) {
        this.size = size;
        final List<Path> dataDirs = XdgMime.dataDirs();
        mimeDb = new XdgMime(dataDirs);
        for (final Path dir : dataDirs) {
            final Path mimeDir = dir.resolve("mime");
            readPairs(mimeDir.resolve("icons"), customIcons);
            readPairs(mimeDir.resolve("generic-icons"), genericIcons);
        }
        themes = loadThemes(theme, iconBaseDirs(dataDirs), MIME_CONTEXTS);
        if (!mimeDb.hasGlobs() || themes.isEmpty()) {
            throw new IllegalStateException("no MIME database or icon theme");
        }
    }

    @Override
    public @Nullable String key(final @NotNull VFile file) {
        return mimeDb.byName(file.getName());
    }

    @Override
    public @Nullable Icon load(final @NotNull String mime, final @NotNull VFile file) throws IOException {
        for (final String iconName : iconNames(mime)) {
            for (final Theme theme : themes) {
                final Path found = theme.find(iconName, size);
                final BufferedImage img = found == null ? null : read(found, size);
                if (img != null) {
                    return SystemIcons.toIcon(img);
                }
            }
        }

        return null;
    }

    /**
     * The icon of a desktop entry's Icon key: an absolute file, or a name looked up in the current theme, the themes it inherits and
     * hicolor in any context, then in /usr/share/pixmaps. Null when there is none or it cannot be read. Reads files: not on the EDT.
     */
    static @Nullable Icon appIcon(final @NotNull String icon, final int size) {
        try {
            final Path file = findAppIcon(icon, size);
            final BufferedImage img = file == null ? null : read(file, size);

            return img == null ? null : SystemIcons.toIcon(img);
        } catch (final IOException | RuntimeException e) {
            // a damaged icon file: the entry is listed without an icon
            return null;
        }
    }

    /** Synchronized: theme directories are listed lazily without locking, and icon loaders of successive dialogs may overlap. */
    private static synchronized @Nullable Path findAppIcon(final @NotNull String icon, final int size) {
        if (icon.startsWith("/")) {
            final Path p = Path.of(icon);

            return Files.isRegularFile(p) ? p : null;
        }
        // the spec wants a bare name, yet some entries give "name.png"
        String name = icon;
        for (final String ext : IMAGE_EXTENSIONS) {
            if (name.endsWith(ext)) {
                name = name.substring(0, name.length() - ext.length());
            }
        }
        for (final Theme theme : appThemes()) {
            final Path found = theme.find(name, size);
            if (found != null) {
                return found;
            }
        }
        for (final String candidate : List.of(icon, name + ".png", name + ".svg")) {
            final Path p = PIXMAPS.resolve(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }

        return null;
    }

    private static synchronized @NotNull List<Theme> appThemes() {
        if (appThemes == null) {
            appThemes = loadThemes(themeName(), iconBaseDirs(XdgMime.dataDirs()), null);
        }

        return appThemes;
    }

    private static @Nullable BufferedImage read(final @NotNull Path file, final int size) throws IOException {
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

    /**
     * The theme, the themes it inherits (depth first, each once), then hicolor — the spec's fallback for every theme.
     * Only icon directories of the given contexts are indexed; null — all of them.
     */
    private static @NotNull List<Theme> loadThemes(
        final @NotNull String name,
        final @NotNull List<Path> bases,
        final @Nullable Set<String> contexts
    ) {
        final List<Theme> result = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        addTheme(name, bases, contexts, seen, result);
        addTheme("hicolor", bases, contexts, seen, result);

        return result;
    }

    private static void addTheme(
        final @NotNull String name,
        final @NotNull List<Path> bases,
        final @Nullable Set<String> contexts,
        final @NotNull Set<String> seen,
        final @NotNull List<Theme> result
    ) {
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
        result.add(new Theme(roots, ini, head.getOrDefault("Directories", ""), contexts));
        for (final String parent : head.getOrDefault("Inherits", "").split(",")) {
            if (!parent.isBlank()) {
                addTheme(parent.trim(), bases, contexts, seen, result);
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

    private static @NotNull List<Path> iconBaseDirs(final @NotNull List<Path> dataDirs) {
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

    /** One theme: its icon directories of the wanted contexts; icon names in a directory are listed on first use. */
    private static final class Theme {

        private final List<Dir> dirs = new ArrayList<>();

        private Theme(
            final @NotNull List<Path> roots,
            final @NotNull Map<String, Map<String, String>> ini,
            final @NotNull String directories,
            final @Nullable Set<String> contexts
        ) {
            for (final String d : directories.split(",")) {
                final Map<String, String> props = ini.get(d.trim());
                if (d.isBlank() || props == null || contexts != null && !contexts.contains(props.get("Context"))
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
