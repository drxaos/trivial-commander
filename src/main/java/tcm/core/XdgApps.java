package tcm.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Applications for a file on Linux and other freedesktop systems, read straight from the files the desktops use: the MIME type
 * from shared-mime-info ({@link XdgMime}), associations by the "MIME Applications" spec (mimeapps.list, mimeinfo.cache, the legacy
 * defaults.list), the programs from desktop entries ("Desktop Entry" spec), started with their Exec line expanded as the spec says.
 * The directories, desktop names, locale and PATH lookup are given to the constructor, so the logic can run against a test tree.
 */
public final class XdgApps implements OpenWith {

    private static final String DESKTOP_ENTRY = "Desktop Entry";
    private static final String ADDED = "Added Associations";
    private static final String REMOVED = "Removed Associations";
    private static final String DEFAULTS = "Default Applications";
    private static final String CACHE = "MIME Cache";
    private static final String SUFFIX = ".desktop";
    /** Characters a backslash escapes inside a quoted Exec argument. */
    private static final String QUOTED_ESCAPES = "\"`$\\";

    private final List<Path> configDirs;
    private final List<Path> dataDirs;
    private final List<String> desktops;
    private final Function<String, Path> pathLookup;
    private final List<String> localeSuffixes;
    private @Nullable XdgMime mime;

    /** The real environment: XDG_* variables, XDG_CURRENT_DESKTOP, the messages locale and PATH. */
    public XdgApps() {
        this(defaultConfigDirs(), defaultDataDirs(), currentDesktops(), XdgApps::findOnPath, messagesLocale());
    }

    /**
     * @param configDirs $XDG_CONFIG_HOME, then $XDG_CONFIG_DIRS
     * @param dataDirs $XDG_DATA_HOME, then $XDG_DATA_DIRS (each holds mime/ and applications/)
     * @param desktops the names of $XDG_CURRENT_DESKTOP, e.g. "XFCE"
     * @param pathLookup a program name → its executable found on PATH, or null
     * @param locale the messages locale as in $LANG, e.g. "ru_RU.UTF-8"
     */
    public XdgApps(
        final @NotNull List<Path> configDirs,
        final @NotNull List<Path> dataDirs,
        final @NotNull List<String> desktops,
        final @NotNull Function<String, Path> pathLookup,
        final @NotNull String locale
    ) {
        this.configDirs = List.copyOf(configDirs);
        this.dataDirs = List.copyOf(dataDirs);
        this.desktops = List.copyOf(desktops);
        this.pathLookup = pathLookup;
        this.localeSuffixes = localeSuffixes(locale);
    }

    @Override
    public @NotNull List<App> recommended(final @NotNull Path file) {
        final XdgMime db = mime();

        return toApps(entriesFor(db.withParents(db.typeOf(file))));
    }

    @Override
    public @NotNull List<App> all() {
        final List<DesktopEntry> result = new ArrayList<>();
        for (final Map.Entry<String, Path> e : allDesktopFiles().entrySet()) {
            final DesktopEntry entry = parse(e.getKey(), e.getValue());
            if (entry != null && isLaunchable(entry) && isShown(entry)) {
                result.add(entry);
            }
        }
        result.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));

        return result.stream()
            .map(e -> new App(e.getId(), e.getName(), e.getIcon(), false))
            .toList();
    }

    @Override
    public void launch(final @NotNull App app, final @NotNull Path file) throws IOException {
        final DesktopEntry entry = entry(app.getId());
        if (entry == null || !isLaunchable(entry)) {
            throw new IOException("No application " + app.getId());
        }
        final List<String> command = command(entry, file);
        if (command.isEmpty()) {
            throw new IOException("Empty Exec line in " + entry.getFile());
        }
        final File dir = workingDirectory(entry, file);
        if (!entry.isTerminal()) {
            start(command, dir);

            return;
        }
        for (final List<String> terminal : terminalCommands()) {
            final List<String> full = new ArrayList<>(terminal);
            full.addAll(command);
            try {
                start(full, dir);

                return;
            } catch (final IOException e) {
                // no such terminal emulator — try the next one
            }
        }
        throw new IOException("No terminal emulator found for " + entry.getName());
    }

    /**
     * Desktop entries for the types (the file's type first, then its parents): the default application first, flagged, then the
     * associated ones in order, each once. Entries hidden from menus (NoDisplay, OnlyShowIn/NotShowIn) are left out unless one of
     * them is the default: such entries exist precisely to handle a MIME type.
     */
    public @NotNull List<Associated> entriesFor(final @NotNull List<String> types) {
        final List<Source> sources = sources();
        final List<Associated> result = new ArrayList<>();
        final DesktopEntry def = findDefault(types, sources);
        if (def != null) {
            result.add(new Associated(def, true));
        }
        for (final String id : associatedIds(types, sources)) {
            if (def != null && def.getId().equals(id)) {
                continue;
            }
            final DesktopEntry entry = entry(id);
            if (entry != null && isLaunchable(entry) && isShown(entry)) {
                result.add(new Associated(entry, result.isEmpty()));
            }
        }

        return result;
    }

    /** The desktop entry of an id, from the first applications directory that has the file, or null. */
    public @Nullable DesktopEntry entry(final @NotNull String id) {
        for (final Path dir : dataDirs) {
            final Path file = findDesktopFile(dir.resolve("applications"), id);
            if (file != null) {
                return parse(id, file);
            }
        }

        return null;
    }

    /**
     * The Exec line expanded for the file as the spec says: split into arguments honouring double quotes, then field codes replaced
     * in each argument. A standalone %F or %U gives one argument per file, %i gives "--icon" and the icon or nothing. An Exec line
     * without a file field code gets the file appended. The result goes to the program as is, never through a shell.
     */
    public @NotNull List<String> command(final @NotNull DesktopEntry entry, final @NotNull Path file) {
        final String path = file.toAbsolutePath().toString();
        final String uri = file.toAbsolutePath().toUri().toString();
        final List<String> result = new ArrayList<>();
        boolean hasFileCode = false;
        for (final String arg : splitExec(entry.getExec() == null ? "" : entry.getExec())) {
            if (arg.equals("%i")) {
                if (entry.getIcon() != null) {
                    result.add("--icon");
                    result.add(entry.getIcon());
                }
                continue;
            }
            hasFileCode |= hasFileCode(arg);
            final String expanded = expand(arg, entry, path, uri);
            // an argument that was only deprecated codes disappears; an empty quoted "" stays
            if (!expanded.isEmpty() || arg.indexOf('%') < 0) {
                result.add(expanded);
            }
        }
        if (!hasFileCode && !result.isEmpty()) {
            result.add(path);
        }

        return result;
    }

    /** Splits an Exec value into arguments: whitespace separates, double quotes group, inside them \ escapes " ` $ \. */
    static @NotNull List<String> splitExec(final @NotNull String exec) {
        final List<String> result = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inArg = false;
        boolean quoted = false;
        for (int i = 0; i < exec.length(); i++) {
            final char c = exec.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < exec.length() && QUOTED_ESCAPES.indexOf(exec.charAt(i + 1)) >= 0) {
                    current.append(exec.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                inArg = true;
            } else if (Character.isWhitespace(c)) {
                if (inArg) {
                    result.add(current.toString());
                    current.setLength(0);
                    inArg = false;
                }
            } else {
                current.append(c);
                inArg = true;
            }
        }
        if (inArg) {
            result.add(current.toString());
        }

        return result;
    }

    /** %f %F %u %U anywhere in the argument; "%%f" is a literal "%f", not a code. */
    private static boolean hasFileCode(final @NotNull String arg) {
        for (int i = 0; i + 1 < arg.length(); i++) {
            if (arg.charAt(i) == '%') {
                if ("fFuU".indexOf(arg.charAt(i + 1)) >= 0) {
                    return true;
                }
                i++;
            }
        }

        return false;
    }

    private static @NotNull String expand(
        final @NotNull String arg,
        final @NotNull DesktopEntry entry,
        final @NotNull String path,
        final @NotNull String uri
    ) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arg.length(); i++) {
            final char c = arg.charAt(i);
            if (c != '%' || i + 1 >= arg.length()) {
                sb.append(c);
                continue;
            }
            final char code = arg.charAt(++i);
            switch (code) {
                case 'f', 'F' -> sb.append(path);
                case 'u', 'U' -> sb.append(uri);
                case 'c' -> sb.append(entry.getName());
                case 'k' -> sb.append(entry.getFile());
                case 'i' -> sb.append(entry.getIcon() == null ? "" : entry.getIcon());
                case '%' -> sb.append('%');
                default -> {
                    // deprecated (%d %D %n %N %v %m) and unknown codes expand to nothing
                }
            }
        }

        return sb.toString();
    }

    private @NotNull List<App> toApps(final @NotNull List<Associated> entries) {
        return entries.stream()
            .map(a -> new App(a.getEntry().getId(), a.getEntry().getName(), a.getEntry().getIcon(), a.isDefaultApp()))
            .toList();
    }

    private synchronized @NotNull XdgMime mime() {
        if (mime == null) {
            mime = new XdgMime(dataDirs);
        }

        return mime;
    }

    /** Installed, an application, not deleted (Hidden), with a command whose TryExec program exists. */
    public boolean isLaunchable(final @NotNull DesktopEntry e) {
        if (!"Application".equals(e.getType()) || e.isHidden() || e.getExec() == null || e.getExec().isBlank()) {
            return false;
        }
        final String tryExec = e.getTryExec();
        if (tryExec == null || tryExec.isBlank()) {
            return true;
        }
        if (tryExec.startsWith("/")) {
            return Files.isExecutable(Path.of(tryExec));
        }

        return pathLookup.apply(tryExec) != null;
    }

    /** Meant to be shown in menus on the current desktop. */
    public boolean isShown(final @NotNull DesktopEntry e) {
        if (e.isNoDisplay()) {
            return false;
        }
        if (!e.getOnlyShowIn().isEmpty() && e.getOnlyShowIn().stream().noneMatch(desktops::contains)) {
            return false;
        }

        return e.getNotShowIn().stream().noneMatch(desktops::contains);
    }

    // ---- associations ----

    /**
     * The association files of one directory, in precedence order: $desktop-mimeapps.list and mimeapps.list, the cache of the
     * desktop files installed there (applications directories only), and the deprecated defaults.list.
     */
    private static final class Source {

        private final List<Map<String, Map<String, List<String>>>> lists = new ArrayList<>();
        private @Nullable Map<String, Map<String, List<String>>> cache;
        private @Nullable Map<String, Map<String, List<String>>> legacy;
    }

    private @NotNull List<Source> sources() {
        final XdgMime db = mime();
        final List<Source> result = new ArrayList<>();
        for (final Path dir : configDirs) {
            result.add(source(dir, false, db));
        }
        for (final Path dir : dataDirs) {
            result.add(source(dir.resolve("applications"), true, db));
        }

        return result;
    }

    private @NotNull Source source(final @NotNull Path dir, final boolean applications, final @NotNull XdgMime db) {
        final Source s = new Source();
        for (final String desktop : desktops) {
            s.lists.add(readIni(dir.resolve(desktop.toLowerCase(Locale.ROOT) + "-mimeapps.list"), db));
        }
        s.lists.add(readIni(dir.resolve("mimeapps.list"), db));
        if (applications) {
            s.cache = readIni(dir.resolve("mimeinfo.cache"), db);
            s.legacy = readIni(dir.resolve("defaults.list"), db);
        }

        return s;
    }

    /**
     * Associated ids, per type in order: walking the directories from the most important, Added Associations not blacklisted so far
     * are taken, then Removed Associations join the blacklist, then the directory's mimeinfo.cache entries not blacklisted are taken.
     * So a removal hides an app from less important files only, and the user's own additions always count.
     */
    private static @NotNull Set<String> associatedIds(final @NotNull List<String> types, final @NotNull List<Source> sources) {
        final Set<String> result = new LinkedHashSet<>();
        for (final String type : types) {
            final Set<String> blacklist = new HashSet<>();
            for (final Source s : sources) {
                for (final Map<String, Map<String, List<String>>> list : s.lists) {
                    addUnlisted(result, values(list, ADDED, type), blacklist);
                    blacklist.addAll(values(list, REMOVED, type));
                }
                if (s.cache != null) {
                    addUnlisted(result, values(s.cache, CACHE, type), blacklist);
                }
            }
        }

        return result;
    }

    private static void addUnlisted(final @NotNull Set<String> to, final @NotNull List<String> ids, final @NotNull Set<String> blacklist) {
        for (final String id : ids) {
            if (!blacklist.contains(id)) {
                to.add(id);
            }
        }
    }

    /**
     * The first Default Applications entry that is installed and launchable: per type (the file's own type first), the mimeapps.list
     * files in precedence order, then the legacy defaults.list files.
     */
    private @Nullable DesktopEntry findDefault(final @NotNull List<String> types, final @NotNull List<Source> sources) {
        for (final String type : types) {
            final List<String> candidates = new ArrayList<>();
            for (final Source s : sources) {
                for (final Map<String, Map<String, List<String>>> list : s.lists) {
                    candidates.addAll(values(list, DEFAULTS, type));
                }
            }
            for (final Source s : sources) {
                if (s.legacy != null) {
                    candidates.addAll(values(s.legacy, DEFAULTS, type));
                }
            }
            for (final String id : candidates) {
                final DesktopEntry e = entry(id);
                if (e != null && isLaunchable(e)) {
                    return e;
                }
            }
        }

        return null;
    }

    private static @NotNull List<String> values(
        final @NotNull Map<String, Map<String, List<String>>> ini,
        final @NotNull String group,
        final @NotNull String key
    ) {
        return ini.getOrDefault(group, Map.of()).getOrDefault(key, List.of());
    }

    /**
     * Groups → MIME type → ";"-separated ids; a type listed twice in a group adds to the list. Odd lines are skipped. Types are
     * resolved through the aliases: desktop files often name an alias (image/pdf), and mimeinfo.cache keeps it as written.
     */
    private static @NotNull Map<String, Map<String, List<String>>> readIni(final @NotNull Path file, final @NotNull XdgMime db) {
        final Map<String, Map<String, List<String>>> result = new HashMap<>();
        Map<String, List<String>> group = null;
        for (final String raw : readLines(file)) {
            final String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                group = result.computeIfAbsent(line.substring(1, line.length() - 1), k -> new LinkedHashMap<>());
                continue;
            }
            final int eq = line.indexOf('=');
            if (group == null || eq <= 0 || line.startsWith("#")) {
                continue;
            }
            final List<String> ids = group.computeIfAbsent(db.canonical(line.substring(0, eq).trim()), k -> new ArrayList<>());
            for (final String id : line.substring(eq + 1).split(";")) {
                if (!id.isBlank() && !ids.contains(id.trim())) {
                    ids.add(id.trim());
                }
            }
        }

        return result;
    }

    // ---- desktop entries ----

    /**
     * The file of a desktop file id in one applications directory: "a-b-c.desktop" may also be a/b-c.desktop or a/b/c.desktop.
     * Only directories that exist are descended into, so a long id with many dashes costs a few stats, not one per variant.
     */
    private static @Nullable Path findDesktopFile(final @NotNull Path dir, final @NotNull String id) {
        final Path direct = dir.resolve(id);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        for (int dash = id.indexOf('-'); dash > 0; dash = id.indexOf('-', dash + 1)) {
            final Path sub = dir.resolve(id.substring(0, dash));
            if (Files.isDirectory(sub)) {
                final Path found = findDesktopFile(sub, id.substring(dash + 1));
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /** Every desktop file id → file, the first applications directory having the id winning; ids from the path, "/" → "-". */
    private @NotNull Map<String, Path> allDesktopFiles() {
        final Map<String, Path> result = new LinkedHashMap<>();
        for (final Path dataDir : dataDirs) {
            final Path dir = dataDir.resolve("applications");
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(SUFFIX) && Files.isRegularFile(p))
                    .forEach(p -> result.putIfAbsent(dir.relativize(p).toString().replace(File.separatorChar, '-'), p));
            } catch (final IOException | RuntimeException e) {
                // an unreadable directory adds no applications
            }
        }

        return result;
    }

    /** The [Desktop Entry] group of the file, or null if it has none; the first occurrence of a key wins. */
    private @Nullable DesktopEntry parse(final @NotNull String id, final @NotNull Path file) {
        final Map<String, String> keys = new HashMap<>();
        boolean inMain = false;
        boolean found = false;
        for (final String raw : readLines(file)) {
            final String line = raw.trim();
            if (line.startsWith("[")) {
                inMain = line.equals("[" + DESKTOP_ENTRY + "]");
                found |= inMain;
                continue;
            }
            final int eq = line.indexOf('=');
            if (inMain && eq > 0 && !line.startsWith("#")) {
                keys.putIfAbsent(line.substring(0, eq).trim(), unescape(line.substring(eq + 1).trim()));
            }
        }

        return found ? new DesktopEntry(id, file, keys, localizedName(keys, id)) : null;
    }

    /** Name[ll_CC], then Name[ll], then Name; the id when there is no name at all. */
    private @NotNull String localizedName(final @NotNull Map<String, String> keys, final @NotNull String id) {
        for (final String suffix : localeSuffixes) {
            final String v = keys.get("Name[" + suffix + "]");
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        final String name = keys.get("Name");

        return name != null && !name.isBlank() ? name : id.substring(0, id.length() - (id.endsWith(SUFFIX) ? SUFFIX.length() : 0));
    }

    /** String value escapes of the spec: \s \n \t \r \\; any other backslash stays for the Exec quoting rules. */
    static @NotNull String unescape(final @NotNull String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        final StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            final char next = i + 1 < value.length() ? value.charAt(i + 1) : 0;
            final int idx = c == '\\' ? "sntr\\".indexOf(next) : -1;
            if (idx < 0) {
                sb.append(c);
                continue;
            }
            sb.append(" \n\t\r\\".charAt(idx));
            i++;
        }

        return sb.toString();
    }

    private static @NotNull List<String> readLines(final @NotNull Path file) {
        try {
            // decoded leniently: one bad byte in a translation must not lose the whole file
            return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList() : List.of();
        } catch (final IOException e) {
            return List.of();
        }
    }

    // ---- launching ----

    /** The entry's Path when it is a directory, otherwise the file's own directory: a program saving "next to" the file finds it. */
    private static @Nullable File workingDirectory(final @NotNull DesktopEntry e, final @NotNull Path file) {
        if (e.getWorkDir() != null && !e.getWorkDir().isBlank() && Files.isDirectory(Path.of(e.getWorkDir()))) {
            return new File(e.getWorkDir());
        }
        final Path parent = file.toAbsolutePath().getParent();

        return parent == null ? null : parent.toFile();
    }

    /**
     * Started detached from our streams: a GUI program that inherits a pipe nobody reads blocks once the pipe buffer fills,
     * and one that reads stdin would steal it. Not waited for.
     */
    private static void start(final @NotNull List<String> command, final @Nullable File dir) throws IOException {
        new ProcessBuilder(command)
            .directory(dir)
            .redirectInput(new File("/dev/null"))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    }

    /**
     * Terminal emulators for Terminal=true entries, each with the option after which the rest of the line is the command to run:
     * the user's choice ($TERMINAL), the system default (Debian), then common ones.
     */
    private static @NotNull List<List<String>> terminalCommands() {
        final List<List<String>> result = new ArrayList<>();
        final String preferred = System.getenv("TERMINAL");
        if (preferred != null && !preferred.isBlank()) {
            result.add(List.of(preferred, "-e"));
        }
        result.add(List.of("x-terminal-emulator", "-e"));
        result.add(List.of("xfce4-terminal", "-x"));
        result.add(List.of("gnome-terminal", "--"));
        result.add(List.of("konsole", "-e"));
        result.add(List.of("mate-terminal", "-x"));
        result.add(List.of("lxterminal", "-e"));
        result.add(List.of("xterm", "-e"));

        return result;
    }

    // ---- environment ----

    private static @NotNull List<Path> defaultConfigDirs() {
        final Set<Path> result = new LinkedHashSet<>();
        final String home = System.getenv("XDG_CONFIG_HOME");
        result.add(home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".config"));
        final String dirs = System.getenv("XDG_CONFIG_DIRS");
        for (final String d : (dirs != null && !dirs.isBlank() ? dirs : "/etc/xdg").split(":")) {
            if (!d.isBlank()) {
                result.add(Path.of(d));
            }
        }

        return new ArrayList<>(result);
    }

    /** The data directories plus the Flatpak and Snap export directories, which a session started without them may lack. */
    private static @NotNull List<Path> defaultDataDirs() {
        final Set<Path> result = new LinkedHashSet<>(XdgMime.dataDirs());
        result.add(Path.of(System.getProperty("user.home"), ".local", "share", "flatpak", "exports", "share"));
        result.add(Path.of("/var/lib/flatpak/exports/share"));
        result.add(Path.of("/var/lib/snapd/desktop"));

        return new ArrayList<>(result);
    }

    private static @NotNull List<String> currentDesktops() {
        final String value = System.getenv("XDG_CURRENT_DESKTOP");
        if (value == null) {
            return List.of();
        }

        return Stream.of(value.split(":"))
            .filter(s -> !s.isBlank())
            .toList();
    }

    /** POSIX order: LC_ALL, LC_MESSAGES, LANG. */
    private static @NotNull String messagesLocale() {
        for (final String name : new String[] {"LC_ALL", "LC_MESSAGES", "LANG"}) {
            final String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }

        return "C";
    }

    /** "ru_RU.UTF-8@mod" → ["ru_RU", "ru"]. */
    private static @NotNull List<String> localeSuffixes(final @NotNull String locale) {
        String base = locale;
        for (final char c : new char[] {'.', '@'}) {
            final int idx = base.indexOf(c);
            if (idx >= 0) {
                base = base.substring(0, idx);
            }
        }
        if (base.isEmpty() || base.equals("C") || base.equals("POSIX")) {
            return List.of();
        }
        final int underscore = base.indexOf('_');

        return underscore > 0 ? List.of(base, base.substring(0, underscore)) : List.of(base);
    }

    private static @Nullable Path findOnPath(final @NotNull String name) {
        final String path = System.getenv("PATH");
        if (path == null || name.contains("/")) {
            return null;
        }
        for (final String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            final Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }

        return null;
    }

    /** A desktop entry and whether it is the default application for the type. */
    @Getter
    public static final class Associated {

        private final @NotNull DesktopEntry entry;
        private final boolean defaultApp;

        Associated(final @NotNull DesktopEntry entry, final boolean defaultApp) {
            this.entry = entry;
            this.defaultApp = defaultApp;
        }
    }

    /** The keys of a desktop entry this class uses, string escapes already resolved. */
    @Getter
    public static final class DesktopEntry {

        private final @NotNull String id;
        private final @NotNull Path file;
        private final @NotNull String name;
        private final @Nullable String type;
        private final @Nullable String exec;
        private final @Nullable String tryExec;
        private final @Nullable String icon;
        private final @Nullable String workDir;
        private final boolean terminal;
        private final boolean hidden;
        private final boolean noDisplay;
        private final @NotNull List<String> onlyShowIn;
        private final @NotNull List<String> notShowIn;

        DesktopEntry(final @NotNull String id, final @NotNull Path file, final @NotNull Map<String, String> keys, final @NotNull String name) {
            this.id = id;
            this.file = file;
            this.name = name;
            this.type = keys.get("Type");
            this.exec = keys.get("Exec");
            this.tryExec = keys.get("TryExec");
            final String iconValue = keys.get("Icon");
            this.icon = iconValue == null || iconValue.isBlank() ? null : iconValue;
            this.workDir = keys.get("Path");
            this.terminal = isTrue(keys.get("Terminal"));
            this.hidden = isTrue(keys.get("Hidden"));
            this.noDisplay = isTrue(keys.get("NoDisplay"));
            this.onlyShowIn = list(keys.get("OnlyShowIn"));
            this.notShowIn = list(keys.get("NotShowIn"));
        }

        /** "true", and "1" which old entries write. */
        private static boolean isTrue(final @Nullable String v) {
            return "true".equals(v) || "1".equals(v);
        }

        private static @NotNull List<String> list(final @Nullable String v) {
            if (v == null) {
                return List.of();
            }

            return Stream.of(v.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }
    }
}
