package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The shared-mime-info database (freedesktop): MIME type of a file by its name from globs2, aliases and the subclass tree.
 * Names are matched by literal names and extensions only, without glob patterns: that covers practically every type and keeps
 * the lookup a few hash probes, cheap enough for every row the file panel paints.
 */
public final class XdgMime {

    public static final String DIRECTORY = "inode/directory";
    public static final String TEXT = "text/plain";
    public static final String BINARY = "application/octet-stream";

    private static final String GLOB_CHARS = "*?[";
    /** As much of a file as the content check reads: enough to tell text from binary, little enough to stay instant. */
    private static final int SNIFF_BYTES = 8192;

    /** Literal names ("Makefile") and extensions ("tar.gz") → MIME type; case-sensitive ones are kept as is, others in lower case. */
    private final Map<String, String> namesCs = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> extsCs = new HashMap<>();
    private final Map<String, String> exts = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, List<String>> parents = new HashMap<>();

    /** Reads mime/globs2, mime/aliases and mime/subclasses of each data directory, the most important directory first. */
    public XdgMime(final @NotNull List<Path> dataDirs) {
        for (final Path dir : dataDirs) {
            final Path mime = dir.resolve("mime");
            readGlobs(mime.resolve("globs2"));
            readAliases(mime.resolve("aliases"));
            readSubclasses(mime.resolve("subclasses"));
        }
    }

    /** False when no data directory has a MIME database: then nothing can be matched by name. */
    public boolean hasGlobs() {
        return !exts.isEmpty();
    }

    /** The type by the file name alone, as stored in globs2 (not resolved through aliases); null — no literal name or extension matches. */
    public @Nullable String byName(final @NotNull String name) {
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

    /**
     * The canonical type of a local file: a directory, a match by name, otherwise a look at the content — without a NUL byte in its
     * beginning a file is taken for text, so a README or a log without extension still offers the text editors.
     */
    public @NotNull String typeOf(final @NotNull Path file) {
        if (Files.isDirectory(file)) {
            return DIRECTORY;
        }
        final Path name = file.getFileName();
        final String byName = name == null ? null : byName(name.toString());
        if (byName != null) {
            return canonical(byName);
        }

        return isBinary(file) ? BINARY : TEXT;
    }

    public @NotNull String canonical(final @NotNull String type) {
        final String target = aliases.get(type);

        return target == null ? type : target;
    }

    /**
     * The type, then the types it is a subclass of, nearest first (breadth first, each once). Every text/* type is a text/plain
     * as well, even when the database does not say so. application/octet-stream, the parent of all binary types, is not added:
     * every program that opens "any file" would crowd the list for a type that has real handlers.
     */
    public @NotNull List<String> withParents(final @NotNull String type) {
        final Set<String> result = new LinkedHashSet<>();
        final Deque<String> queue = new ArrayDeque<>();
        queue.add(canonical(type));
        while (!queue.isEmpty()) {
            final String t = queue.poll();
            if (!result.add(t)) {
                continue;
            }
            for (final String parent : parents.getOrDefault(t, List.of())) {
                queue.add(canonical(parent));
            }
            if (t.startsWith("text/") && !t.equals(TEXT)) {
                queue.add(TEXT);
            }
        }

        return new ArrayList<>(result);
    }

    /** $XDG_DATA_HOME, then $XDG_DATA_DIRS, in priority order: where shared-mime-info, icon themes and desktop entries live. */
    public static @NotNull List<Path> dataDirs() {
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

    private static boolean isBinary(final @NotNull Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] head = in.readNBytes(SNIFF_BYTES);
            for (final byte b : head) {
                if (b == 0) {
                    return true;
                }
            }

            return false;
        } catch (final IOException e) {
            // unreadable (no permission, a device): nothing says it is text
            return true;
        }
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

    /** Lines "alias canonical". */
    private void readAliases(final @NotNull Path file) {
        for (final String line : lines(file)) {
            final String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && !line.startsWith("#")) {
                aliases.putIfAbsent(parts[0], parts[1]);
            }
        }
    }

    /** Lines "type parent"; a type may have several parents, in file order. */
    private void readSubclasses(final @NotNull Path file) {
        for (final String line : lines(file)) {
            final String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && !line.startsWith("#")) {
                final List<String> list = parents.computeIfAbsent(parts[0], k -> new ArrayList<>());
                if (!list.contains(parts[1])) {
                    list.add(parts[1]);
                }
            }
        }
    }

    private static boolean containsGlob(final @NotNull String s) {
        return s.chars().anyMatch(c -> GLOB_CHARS.indexOf(c) >= 0);
    }

    static @NotNull List<String> lines(final @NotNull Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (final IOException e) {
            return List.of();
        }
    }
}
