package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import lombok.Setter;

/**
 * The file tree of one file system in a disk image, filled by its reader. File data is opened lazily by suppliers.
 * A symbolic link is shown as the file it points to inside the file system (reading it reads that file), a link to a directory
 * or outside the file system — as an empty file, like in the other read-only formats.
 */
final class FsTree {

    private static final int MAX_LINKS = 40;
    private static final int MAX_ENTRIES = 5_000_000;
    private static final IoSupplier<InputStream> EMPTY = InputStream::nullInputStream;

    /** Short type name of the file system, shown in the names of partitions. */
    @Getter
    private final @NotNull String type;
    @Getter
    @Setter
    private @NotNull String label = "";
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Set<Long> visited = new HashSet<>();
    private final List<String> links = new ArrayList<>();

    FsTree(final @NotNull String type) {
        this.type = type;
    }

    /** The path of a child: names that cannot be in a path ("", ".", "..") give null, path separators inside a name become "_". */
    static @Nullable String child(final @NotNull String dir, final @NotNull String name) {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        final String clean = name.replace('/', '_').replace('\\', '_').replace('\0', '_');

        return dir.isEmpty() ? clean : dir + "/" + clean;
    }

    /** False if the directory with the id was already entered — a loop in a damaged file system (or a hard-linked directory). */
    boolean enter(final long id) {
        return visited.add(id);
    }

    void dir(final @NotNull String path, final long modified, final @NotNull String attrs) throws IOException {
        put(path, new Node(true, 0, modified, attrs, null, null));
    }

    void file(
        final @NotNull String path,
        final long size,
        final long modified,
        final @NotNull String attrs,
        final @NotNull IoSupplier<InputStream> data
    ) throws IOException {
        put(path, new Node(false, size, modified, attrs, data, null));
    }

    /** A symbolic link; the target is read when the tree is complete (see {@link #resolveLinks}). */
    void link(final @NotNull String path, final long modified, final @NotNull IoSupplier<String> target) throws IOException {
        put(path, new Node(false, 0, modified, "", EMPTY, target));
        links.add(path);
    }

    private void put(final @NotNull String path, final @NotNull Node node) throws IOException {
        if (nodes.size() >= MAX_ENTRIES) {
            throw new IOException("too many files in the file system");
        }
        nodes.put(path, node);
    }

    /** Replaces links to files by those files; unreadable or dangling links stay empty files. */
    void resolveLinks() {
        final Map<String, String> targets = new LinkedHashMap<>();
        for (final String path : links) {
            try {
                targets.put(path, nodes.get(path).target.get());
            } catch (final IOException | RuntimeException e) {
                // a damaged link is shown as an empty file
            }
        }
        for (final Map.Entry<String, String> e : targets.entrySet()) {
            final Node file = follow(e.getKey(), targets);
            if (file != null) {
                final Node link = nodes.get(e.getKey());
                nodes.put(e.getKey(), new Node(false, file.size, link.modified, file.attrs, file.data, null));
            }
        }
    }

    /** The file a link leads to, following links in the target and in the directories of its path; null — not a file. */
    private @Nullable Node follow(final @NotNull String start, final @NotNull Map<String, String> targets) {
        String path = start;
        int hops = 0;
        while (hops++ < MAX_LINKS) {
            final String target = targets.get(path);
            if (target != null) {
                path = resolve(ArchiveIndex.parentOf(path), target);
                if (path == null) {
                    return null;
                }
                continue;
            }
            final String expanded = expandParents(path, targets);
            if (expanded == null) {
                return null;
            }
            if (expanded.equals(path)) {
                final Node n = nodes.get(path);

                return n != null && !n.directory && n.target == null ? n : null;
            }
            path = expanded;
        }

        return null;
    }

    /** The path with its first directory that is a link replaced by the link's target; null — that target leaves the file system. */
    private static @Nullable String expandParents(final @NotNull String path, final @NotNull Map<String, String> targets) {
        int slash = path.indexOf('/');
        while (slash > 0) {
            final String dir = path.substring(0, slash);
            final String target = targets.get(dir);
            if (target != null) {
                final String resolved = resolve(ArchiveIndex.parentOf(dir), target);

                return resolved == null ? null : ArchiveIndex.join(resolved, path.substring(slash + 1));
            }
            slash = path.indexOf('/', slash + 1);
        }

        return path;
    }

    /** The target path relative to the directory (absolute — from the root); null — it leaves the file system. */
    private static @Nullable String resolve(final @NotNull String dir, final @NotNull String target) {
        final List<String> parts = new ArrayList<>();
        if (!target.startsWith("/") && !dir.isEmpty()) {
            parts.addAll(List.of(dir.split("/")));
        }
        for (final String p : target.split("/")) {
            if (p.isEmpty() || ".".equals(p)) {
                continue;
            }
            if ("..".equals(p)) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
            } else {
                parts.add(p);
            }
        }

        return String.join("/", parts);
    }

    /** Adds all entries to the index under the prefix ("" or "dir/"). */
    void addTo(final @NotNull ArchiveIndex index, final @NotNull String prefix) {
        for (final Map.Entry<String, Node> e : nodes.entrySet()) {
            final Node n = e.getValue();
            index.add(prefix + e.getKey(), n.directory, n.size, n.modified, n.attrs);
        }
    }

    /** The content of the file at the path; null — no such file. */
    @Nullable InputStream open(final @NotNull String path) throws IOException {
        final Node n = nodes.get(path);
        if (n == null || n.directory) {
            return null;
        }

        return n.data.get();
    }

    private static final class Node {

        private final boolean directory;
        private final long size;
        private final long modified;
        private final @NotNull String attrs;
        private final IoSupplier<InputStream> data;
        private final @Nullable IoSupplier<String> target;

        private Node(
            final boolean directory,
            final long size,
            final long modified,
            final @NotNull String attrs,
            final @Nullable IoSupplier<InputStream> data,
            final @Nullable IoSupplier<String> target
        ) {
            this.directory = directory;
            this.size = size;
            this.modified = modified;
            this.attrs = attrs;
            this.data = data;
            this.target = target;
        }
    }
}
