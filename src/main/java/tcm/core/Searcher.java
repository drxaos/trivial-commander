package tcm.core;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import lombok.Setter;
import tcm.util.Masks;
import tcm.util.TextUtil;

/**
 * File search: by name mask or RegEx, by content, with depth limit and descending into archives.
 */
public final class Searcher {

    /** Files larger than this are not checked by content, so as not to read gigabytes into memory. */
    private static final long MAX_CONTENT_SIZE = 64L * 1024 * 1024;

    @Getter
    @Setter
    public static final class Criteria {

        private @NotNull String name = "*";
        private boolean nameRegex;
        /** -1 — unlimited, 0 — the directory itself only. */
        private int depth = -1;
        private boolean searchArchives;
        private @NotNull String text = "";
        private boolean textRegex;
        private boolean caseSensitive;
        private boolean notContaining;
        /** "Advanced" limits: -1 — not set. */
        private long minSize = -1;
        private long maxSize = -1;
        private long newerThan = -1;
        private long olderThan = -1;
        private boolean filesOnly;
        private boolean dirsOnly;
    }

    private final @NotNull Criteria criteria;
    private final @NotNull Consumer<VFile> onFound;
    private final @NotNull Consumer<String> onDirectory;
    private final @NotNull BooleanSupplier cancelled;
    private final @Nullable Pattern namePattern;
    private final @NotNull Masks nameMask;
    private final @Nullable Pattern textPattern;

    public Searcher(
        final @NotNull Criteria criteria,
        final @NotNull Consumer<VFile> onFound,
        final @NotNull Consumer<String> onDirectory,
        final @NotNull BooleanSupplier cancelled
    ) {
        this.criteria = criteria;
        this.onFound = onFound;
        this.onDirectory = onDirectory;
        this.cancelled = cancelled;
        this.namePattern = criteria.isNameRegex() ? Pattern.compile(criteria.getName(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null;
        this.nameMask = Masks.of(criteria.getName());
        if (criteria.getText().isEmpty()) {
            this.textPattern = null;
        } else {
            final int flags = criteria.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            final String regex = criteria.isTextRegex() ? criteria.getText() : Pattern.quote(criteria.getText());
            this.textPattern = Pattern.compile(regex, flags | Pattern.MULTILINE);
        }
    }

    public void search(final @NotNull List<VFile> roots) {
        for (final VFile root : roots) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            if (root.isDirectory()) {
                walk(root, 0);
            } else {
                check(root);
            }
        }
    }

    private void walk(final @NotNull VFile dir, final int level) {
        if (cancelled.getAsBoolean()) {
            return;
        }
        onDirectory.accept(dir.getLocation());
        final List<VFile> children;
        try {
            children = Vfs.list(dir);
        } catch (final IOException e) {
            // no access to the directory — skip it, like TC
            return;
        }
        for (final VFile child : children) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            check(child);
            final boolean deeper = criteria.getDepth() < 0 || level < criteria.getDepth();
            if (child.isDirectory() && deeper && !isSymlink(child)) {
                walk(child, level + 1);
            } else if (deeper && criteria.isSearchArchives() && child.isLocal() && child.isArchiveFile()) {
                walkArchive(child);
            }
        }
    }

    private void walkArchive(final @NotNull VFile archive) {
        onDirectory.accept(archive.getLocation());
        try {
            for (final VFile entry : Vfs.index(archive.getHostPath()).subtree("").values()) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                if (!entry.getEntry().isEmpty()) {
                    check(entry);
                }
            }
        } catch (final IOException e) {
            // corrupted archive — skip it
        }
    }

    private void check(final @NotNull VFile file) {
        if (!nameMatches(file.getName()) || !attributesMatch(file)) {
            return;
        }
        if (textPattern != null) {
            if (file.isDirectory() || !contentMatches(file)) {
                return;
            }
        }
        onFound.accept(file);
    }

    private boolean attributesMatch(final @NotNull VFile f) {
        if (criteria.isFilesOnly() && f.isDirectory() || criteria.isDirsOnly() && !f.isDirectory()) {
            return false;
        }
        if (!f.isDirectory() && (criteria.getMinSize() >= 0 && f.getSize() < criteria.getMinSize()
            || criteria.getMaxSize() >= 0 && f.getSize() > criteria.getMaxSize())) {
            return false;
        }

        return (criteria.getNewerThan() < 0 || f.getModified() >= criteria.getNewerThan())
            && (criteria.getOlderThan() < 0 || f.getModified() <= criteria.getOlderThan());
    }

    private boolean nameMatches(final @NotNull String name) {
        if (namePattern != null) {
            return namePattern.matcher(name).find();
        }

        return nameMask.matches(name);
    }

    private boolean contentMatches(final @NotNull VFile file) {
        if (file.getSize() > MAX_CONTENT_SIZE) {
            return false;
        }
        try {
            final String text = TextUtil.decode(Vfs.readAll(file)).getText();
            final boolean found = textPattern != null && textPattern.matcher(text).find();

            return found != criteria.isNotContaining();
        } catch (final IOException e) {
            return false;
        }
    }

    private static boolean isSymlink(final @NotNull VFile f) {
        return f.isLocal() && Files.isSymbolicLink(f.getHostPath());
    }
}
