package tcm.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import tcm.core.ArchiveFormat;
import tcm.core.ArchiveIndex;
import tcm.core.TreeStats;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.core.ZipOps;
import tcm.util.Masks;

/**
 * Panel tab: current directory, history, filters, file table, quick search and status line.
 */
public final class PanelTab extends JPanel implements QuickBar.Listener {

    private static final int HISTORY_LIMIT = 100;
    private static final int FLAT_LIMIT = 200_000;
    private static final Set<String> PROGRAM_EXTENSIONS = Set.of("exe", "com", "bat", "cmd", "sh", "ps1", "msi", "appimage", "run", "bin");

    @Getter
    private @NotNull FilePanel owner;
    @Getter
    private final @NotNull FileTableModel model = new FileTableModel();
    @Getter
    private final @NotNull FileTable table = new FileTable(model);
    private final JLabel header = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final QuickBar quickBar = new QuickBar(this);
    @Getter
    private final List<String> history = new ArrayList<>();
    private int historyPos = -1;
    private final AtomicInteger generation = new AtomicInteger();
    @Getter
    private @NotNull VFile dir;
    /** Not null — the tab shows search results or a flat listing (Ctrl+B). */
    private @Nullable List<VFile> feed;
    private @NotNull String feedTitle = "";
    @Getter
    private @NotNull String filter = "*";
    private boolean programsOnly;
    private @NotNull String quickFilter = "";
    private @NotNull String lastQuickFilter = "";
    private boolean active;

    public PanelTab(final @NotNull FilePanel owner, final @NotNull VFile dir) {
        super(new BorderLayout());
        this.owner = owner;
        this.dir = dir;
        add(createHeader(), BorderLayout.NORTH);
        final JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        final JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(quickBar, BorderLayout.NORTH);
        status.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bottom.add(status, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        installKeys();
        installMouse();
        table.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(final FocusEvent e) {
                PanelTab.this.owner.getHost().panelActivated(PanelTab.this.owner);
            }
        });
        table.setBrief(owner.getHost().isBrief());
        navigate(dir, null, true);
        owner.getHost().bindPanelKeys(this);
    }

    /** Ctrl+Shift+U moves tabs between panels. */
    void setOwner(final @NotNull FilePanel value) {
        owner = value;
    }

    // ---- navigation ----

    public boolean navigate(final @NotNull VFile target, final @Nullable String select, final boolean record) {
        final List<VFile> files;
        final VFile parent;
        try {
            files = Vfs.list(target);
            parent = Vfs.parent(target);
        } catch (final IOException e) {
            showError("Cannot open " + target.getLocation(), e);

            return false;
        }
        final boolean sameDir = feed == null && target.equals(dir);
        if (!sameDir) {
            quickFilter = "";
            quickBar.close();
        }
        final List<VFile> visible = new ArrayList<>();
        if (parent != null) {
            visible.add(VFile.parentLink(parent));
        }
        for (final VFile f : files) {
            if (isVisible(f)) {
                visible.add(f);
            }
        }
        final String keep = select != null ? select : sameDir ? cursorName() : null;
        final int keepRow = table.getCursorRow();
        // re-reading the same directory does not cancel size counting: directories are the same, results are valid
        if (!sameDir) {
            generation.incrementAndGet();
        }
        table.stopRename();
        dir = target;
        feed = null;
        model.setFiles(visible, false);
        if (record) {
            record(target.getLocation());
        }
        final int row = keep == null ? -1 : model.indexOf(keep);
        table.setCursorRow(row >= 0 ? row : sameDir ? keepRow : 0);
        updateLabels();

        return true;
    }

    public boolean navigate(final @NotNull String location) {
        try {
            return navigate(Vfs.resolve(location), null, true);
        } catch (final IOException e) {
            showError("Cannot open " + location, e);

            return false;
        }
    }

    /** Go to a file: open its directory and put the cursor on the file. */
    public void goToFile(final @NotNull VFile file) {
        try {
            final VFile parent = Vfs.parent(file);
            if (parent != null) {
                navigate(parent, file.getName(), true);
            }
        } catch (final IOException e) {
            showError("Cannot open " + file.getLocation(), e);
        }
    }

    public void refresh() {
        if (feed != null) {
            final List<VFile> alive = new ArrayList<>();
            for (final VFile f : feed) {
                if (f.isInArchive() || Files.exists(f.getHostPath())) {
                    alive.add(f);
                }
            }
            feed(alive, feedTitle);

            return;
        }
        VFile target = dir;
        // the directory may have been deleted/renamed externally — climb up to an existing one
        while (true) {
            try {
                Vfs.list(target);
                break;
            } catch (final IOException e) {
                final VFile up = parentOf(target);
                if (up == null) {
                    return;
                }
                target = up;
            }
        }
        navigate(target, null, !target.equals(dir));
    }

    /**
     * Refresh without user involvement (changes on disk, focus returning to the window): does not touch search results
     * or in-panel rename, keeps the scroll position.
     */
    public void refreshQuietly() {
        if (feed != null || table.isRenaming()) {
            return;
        }
        final VFile before = dir;
        final Rectangle view = table.getVisibleRect();
        refresh();
        // the directory may have been deleted — then the parent is open and the old scroll position does not apply to it
        if (dir.equals(before)) {
            table.scrollRectToVisible(view);
        }
    }

    /** F2/Ctrl+R: in search results — return to the base directory, otherwise re-read. */
    public void reread() {
        if (feed != null) {
            navigate(dir, null, false);
        } else {
            refresh();
        }
    }

    public void goUp() {
        if (feed != null) {
            navigate(dir, null, false);

            return;
        }
        final VFile parent = parentOf(dir);
        if (parent != null) {
            navigate(parent, dir.getName(), true);
        }
    }

    public void goRoot() {
        if (feed != null) {
            navigate(dir, null, false);

            return;
        }
        if (dir.isInArchive()) {
            navigate(dir.getHostPath() + VFile.ARCHIVE_SEPARATOR);

            return;
        }
        final Path root = dir.getHostPath().getRoot();
        navigate(root == null ? "/" : root.toString());
    }

    public void goBack() {
        goHistory(-1);
    }

    public void goForward() {
        goHistory(1);
    }

    public void goHistoryEntry(final @NotNull String location) {
        final int pos = history.lastIndexOf(location);
        if (pos >= 0) {
            goHistory(pos - historyPos);
        } else {
            navigate(location);
        }
    }

    public void feed(final @NotNull List<VFile> files, final @NotNull String title) {
        final List<VFile> rows = new ArrayList<>();
        rows.add(VFile.parentLink(dir));
        rows.addAll(files);
        generation.incrementAndGet();
        feed = List.copyOf(files);
        feedTitle = title;
        model.setFeedBase(dir.getLocation());
        model.setFiles(rows, true);
        table.setCursorRow(1);
        updateLabels();
    }

    public boolean isFeed() {
        return feed != null;
    }

    /** Ctrl+B / Ctrl+Shift+B: all files of the directory (or of the selected objects) and subdirectories as one list. */
    public void toggleFlatView(final boolean selectedOnly) {
        if (feed != null) {
            final VFile cursor = getCursorFile();
            if (cursor != null && !cursor.isParentLink()) {
                goToFile(cursor);
            } else {
                navigate(dir, null, false);
            }

            return;
        }
        final List<VFile> roots = selectedOnly ? getSelectedFiles() : List.of(dir);
        final List<VFile> result = new ArrayList<>();
        for (final VFile root : roots) {
            collectFlat(root, result);
        }
        feed(result, "Branch view");
    }

    private void collectFlat(final @NotNull VFile f, final @NotNull List<VFile> out) {
        if (out.size() >= FLAT_LIMIT) {
            return;
        }
        if (!f.isDirectory()) {
            if (isVisible(f)) {
                out.add(f);
            }

            return;
        }
        if (f.isLocal() && Files.isSymbolicLink(f.getHostPath())) {
            return;
        }
        try {
            for (final VFile child : Vfs.list(f)) {
                if (owner.getHost().isShowHidden() || !child.isHidden()) {
                    collectFlat(child, out);
                }
            }
        } catch (final IOException e) {
            // skip an inaccessible subdirectory
        }
    }

    // ---- filters ----

    public void setFilter(final @NotNull String mask) {
        filter = mask.isBlank() ? "*" : mask.trim();
        programsOnly = false;
        refresh();
    }

    /** Ctrl+F11: programs only. */
    public void showProgramsOnly() {
        filter = "*";
        programsOnly = true;
        refresh();
    }

    /** Ctrl+F10: all files, including resetting the quick filter. */
    public void resetFilters() {
        filter = "*";
        programsOnly = false;
        quickFilter = "";
        quickBar.close();
        refresh();
    }

    public void openQuickBar(final boolean filterMode, final @NotNull String text) {
        quickBar.open(filterMode, text);
    }

    /** Ctrl+Shift+S: re-apply the last quick filter. */
    public void reapplyQuickFilter() {
        quickBar.open(true, lastQuickFilter);
    }

    public boolean hasQuickFilter() {
        return !quickFilter.isEmpty();
    }

    private boolean isVisible(final @NotNull VFile f) {
        if (!owner.getHost().isShowHidden() && f.isHidden()) {
            return false;
        }
        if (f.isDirectory()) {
            return quickFilter.isEmpty() || Masks.of(quickFilter).matches(f.getName());
        }
        if (programsOnly && !isProgram(f)) {
            return false;
        }

        return Masks.of(filter).matches(f.getName()) && (quickFilter.isEmpty() || Masks.of(quickFilter).matches(f.getName()));
    }

    private static boolean isProgram(final @NotNull VFile f) {
        return f.getAttrs().length() == 9 && f.getAttrs().indexOf('x') >= 0
            || PROGRAM_EXTENSIONS.contains(f.getExtension().toLowerCase(Locale.ROOT));
    }

    @Override
    public void quickTextChanged(final @NotNull String text, final boolean filterMode) {
        if (filterMode) {
            quickFilter = text.trim();
            if (!quickFilter.isEmpty()) {
                lastQuickFilter = quickFilter;
            }
            if (feed == null) {
                navigate(dir, null, false);
            }

            return;
        }
        if (!quickFilter.isEmpty()) {
            quickFilter = "";
            navigate(dir, null, false);
        }
        final int row = findQuick(text, Math.max(0, table.getCursorRow()), 1);
        if (row >= 0) {
            table.setCursorRow(row);
        }
    }

    @Override
    public void quickMove(final int direction, final boolean mark) {
        final int cur = table.getCursorRow();
        if (mark && cur >= 0) {
            model.toggleMark(cur);
            updateLabels();
        }
        final int row = findQuick(quickBar.getField().getText(), cur + direction, direction);
        if (row >= 0) {
            table.setCursorRow(row);
        }
    }

    @Override
    public void quickEnter() {
        focusTable();
        openCursor();
    }

    @Override
    public void quickClosed() {
        focusTable();
    }

    /** String search: names starting with the text first, then names containing it (with '*' support). */
    private int findQuick(final @NotNull String text, final int from, final int step) {
        if (text.isEmpty() || model.getRowCount() == 0) {
            return -1;
        }
        final String lower = text.toLowerCase(Locale.ROOT);
        final Masks mask = Masks.of(text.contains("*") || text.contains("?") ? text : text + "*");
        final int n = model.getRowCount();
        for (final boolean prefix : new boolean[] {true, false}) {
            for (int i = 0; i < n; i++) {
                final int row = Math.floorMod(from + i * step, n);
                final String name = model.getFile(row).getName();
                if (model.getFile(row).isParentLink()) {
                    continue;
                }
                if (prefix ? mask.matches(name) : name.toLowerCase(Locale.ROOT).contains(lower)) {
                    return row;
                }
            }
        }

        return -1;
    }

    // ---- opening ----

    /** Enter: enter a directory/archive, otherwise open the file via the main window. */
    public void openCursor() {
        final VFile f = getCursorFile();
        if (f == null) {
            return;
        }
        if (f.isParentLink()) {
            goUp();

            return;
        }
        if (f.isDirectory()) {
            navigate(f, null, true);

            return;
        }
        if (f.isArchiveFile() && f.isLocal()) {
            openArchive(f);

            return;
        }
        if (!owner.getHost().openFile(f)) {
            openUnassociated(f);
        }
    }

    /** No associated program — like Ctrl+PgDn: a file with an archive signature is opened as an archive. */
    private void openUnassociated(final @NotNull VFile f) {
        try {
            if (f.isLocal() && ArchiveFormat.detect(f.getHostPath()) != null) {
                openArchive(f);

                return;
            }
        } catch (final IOException e) {
            showError("Cannot read " + f.getLocation(), e);

            return;
        }
        showError("No application is associated with " + f.getName(), null);
    }

    /** Ctrl+PgDn: the file is opened as an archive regardless of extension (format detected by signature). */
    public void openCursorAsArchive() {
        final VFile f = getCursorFile();
        if (f == null || f.isParentLink() || f.isDirectory()) {
            openCursor();

            return;
        }
        if (!f.isLocal()) {
            showError("Archives inside archives are not supported: " + f.getName(), null);

            return;
        }
        openArchive(f);
    }

    public void openArchive(final @NotNull VFile f) {
        try {
            navigate(Vfs.archiveDir(f.getHostPath(), ""), null, true);
        } catch (final IOException e) {
            showError("Cannot open archive " + f.getLocation(), e);
        }
    }

    public void selectName(final @NotNull String name) {
        final int row = model.indexOf(name);
        if (row >= 0) {
            table.setCursorRow(row);
        }
    }

    // ---- in-panel rename (Shift+F6) ----

    public void startRename() {
        final int row = table.getCursorRow();
        if (row < 0 || feed != null) {
            return;
        }
        final VFile f = model.getFile(row);
        if (f.isParentLink()) {
            return;
        }
        table.startRename(row, (name, move) -> {
            if (!name.isEmpty() && !name.equals(f.getName()) && !rename(f, name)) {
                return;
            }
            final int next = model.indexOf(name.isEmpty() ? f.getName() : name) + move;
            table.setCursorRow(next);
            if (move != 0 && next >= 0 && next < model.getRowCount() && !model.getFile(next).isParentLink()) {
                SwingUtilities.invokeLater(this::startRename);
            }
        });
    }

    private boolean rename(final @NotNull VFile f, final @NotNull String name) {
        if (name.contains("/") || name.contains("\\")) {
            showError("Invalid name: " + name, null);

            return false;
        }
        try {
            if (f.isInArchive()) {
                ZipOps.rename(f.getHostPath(), f.getEntry(), ArchiveIndex.join(ArchiveIndex.parentOf(f.getEntry()), name));
                navigate(Vfs.archiveDir(f.getHostPath(), dir.getEntry()), name, false);
            } else {
                Vfs.rename(f.getHostPath(), f.getHostPath().resolveSibling(name));
                navigate(dir, name, false);
            }

            return true;
        } catch (final IOException e) {
            showError("Cannot rename " + f.getName(), e);

            return false;
        }
    }

    // ---- selection ----

    public @Nullable VFile getCursorFile() {
        final int row = table.getCursorRow();

        return row >= 0 && row < model.getRowCount() ? model.getFile(row) : null;
    }

    /** Marked files, or if there are none — the file under the cursor (except ".."). */
    public @NotNull List<VFile> getSelectedFiles() {
        final List<VFile> marked = model.getMarked();
        if (!marked.isEmpty()) {
            return marked;
        }
        final VFile cursor = getCursorFile();

        return cursor == null || cursor.isParentLink() ? List.of() : List.of(cursor);
    }

    /** Space: mark without moving the cursor; a marked directory gets its size counted. */
    public void toggleMarkStay() {
        final int row = table.getCursorRow();
        if (row < 0) {
            return;
        }
        final VFile f = model.getFile(row);
        model.toggleMark(row);
        if (f.isDirectory() && !f.isParentLink() && model.isMarked(f) && model.getDirSize(f) == null) {
            calculateSizes(List.of(f));
        }
        updateLabels();
    }

    public void toggleMarkAndMove(final int delta) {
        final int row = table.getCursorRow();
        if (row < 0) {
            return;
        }
        model.toggleMark(row);
        table.setCursorRow(row + delta);
        updateLabels();
    }

    public void markAll(final boolean mark, final boolean filesOnly) {
        keepCursor(() -> model.markAll(mark, filesOnly));
    }

    public void invertMarks(final boolean filesOnly) {
        keepCursor(() -> model.invertMarks(filesOnly));
    }

    public void markByMask(final @NotNull String mask, final boolean mark, final boolean filesOnly) {
        keepCursor(() -> model.markByMask(Masks.of(mask), mark, filesOnly));
    }

    public void markByCursorExtension(final boolean mark) {
        final VFile f = getCursorFile();
        if (f != null && !f.isDirectory()) {
            keepCursor(() -> model.markByExtension(f.getExtension(), mark));
        }
    }

    public void restoreMarks() {
        keepCursor(model::restoreMarks);
    }

    private void keepCursor(final @NotNull Runnable change) {
        final int row = table.getCursorRow();
        change.run();
        table.setCursorRow(row);
        updateLabels();
    }

    /** Directory sizes are counted in the background; the result is shown in the Size column. */
    public void calculateSizes(final @NotNull List<VFile> dirs) {
        final int gen = generation.get();
        final Thread t = new Thread(() -> {
            for (final VFile d : dirs) {
                if (generation.get() != gen) {
                    return;
                }
                final TreeStats stats = TreeStats.of(List.of(d), () -> generation.get() != gen);
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() == gen) {
                        model.setDirSize(d, stats.getBytes());
                        updateLabels();
                    }
                });
            }
        }, "dir-size");
        t.setDaemon(true);
        t.start();
    }

    /** Alt+Shift+Enter: sizes of all subdirectories. */
    public void calculateAllSizes() {
        final List<VFile> dirs = new ArrayList<>();
        for (final VFile f : model.getFiles()) {
            if (f.isDirectory() && !f.isParentLink()) {
                dirs.add(f);
            }
        }
        calculateSizes(dirs);
    }

    // ---- state ----

    public void setActive(final boolean value) {
        active = value;
        updateHeaderColors();
    }

    public void setThumbs(final boolean value) {
        table.setThumbs(value);
    }

    public void setBrief(final boolean value) {
        table.setBrief(value);
    }

    public @NotNull String getTitle() {
        if (feed != null) {
            return feedTitle;
        }
        final String name = dir.getName();

        return name.isEmpty() ? dir.getLocation() : name;
    }

    /** Local directory for external programs: for an archive — the directory where it resides. */
    public @NotNull Path getWorkingDirectory() {
        final Path host = dir.getHostPath();
        if (dir.isInArchive()) {
            final Path parent = host.getParent();

            return parent == null ? host : parent;
        }

        return host;
    }

    public void focusTable() {
        table.requestFocusInWindow();
    }

    public void updateLabels() {
        final String mask = programsOnly ? "<programs>" : Masks.isAll(filter) ? "*" : filter;
        final String quick = quickFilter.isEmpty() ? "" : "  [filter: " + quickFilter + "]";
        final String sep = dir.isInArchive() ? (dir.getEntry().isEmpty() ? "" : "/") : (dir.getLocation().endsWith("/") ? "" : "/");
        header.setText(feed != null ? feedTitle + " (" + feed.size() + ")" : dir.getLocation() + sep + mask + quick);
        status.setText(model.getSummary());
        owner.tabUpdated(this);
    }

    public void updateHeaderColors() {
        final Color bg = active ? UIManager.getColor("Component.accentColor") : UIManager.getColor("Panel.background");
        header.setBackground(bg == null ? new Color(0x3C82D2) : bg);
        header.setForeground(active ? Color.WHITE : UIManager.getColor("Label.foreground"));
    }

    private @NotNull String cursorName() {
        final VFile f = getCursorFile();

        return f == null || f.isParentLink() ? "" : f.getName();
    }

    private void record(final @NotNull String location) {
        if (historyPos >= 0 && history.get(historyPos).equals(location)) {
            return;
        }
        while (history.size() > historyPos + 1) {
            history.remove(history.size() - 1);
        }
        history.add(location);
        if (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
        historyPos = history.size() - 1;
    }

    private void goHistory(final int delta) {
        final int pos = historyPos + delta;
        if (delta == 0 || pos < 0 || pos >= history.size()) {
            return;
        }
        try {
            if (navigate(Vfs.resolve(history.get(pos)), null, false)) {
                historyPos = pos;
            }
        } catch (final IOException e) {
            history.remove(pos);
            if (historyPos > pos) {
                historyPos--;
            }
        }
    }

    private static @Nullable VFile parentOf(final @NotNull VFile f) {
        try {
            return Vfs.parent(f);
        } catch (final IOException e) {
            return null;
        }
    }

    private void showError(final @NotNull String message, final @Nullable Exception e) {
        JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this), message + (e == null ? "" : "\n" + e.getMessage()), "Error",
            JOptionPane.ERROR_MESSAGE);
    }

    private @NotNull JComponent createHeader() {
        final JPanel panel = new JPanel(new BorderLayout());
        header.setOpaque(true);
        header.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        panel.add(header, BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new BorderLayout());
        final JButton filterButton = smallButton("*", "Filter (Ctrl+F12)");
        filterButton.addActionListener(e -> askFilter());
        final JButton hotlist = smallButton("▾", "Directory hotlist (Ctrl+D)");
        hotlist.addActionListener(e -> owner.getHost().createHotlistMenu(this).show(hotlist, 0, hotlist.getHeight()));
        buttons.add(filterButton, BorderLayout.WEST);
        buttons.add(hotlist, BorderLayout.EAST);
        panel.add(buttons, BorderLayout.EAST);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editPath();
                } else {
                    focusTable();
                }
            }
        });
        updateHeaderColors();

        return panel;
    }

    /** Ctrl+F12: user filter. */
    public void askFilter() {
        final String mask = (String) JOptionPane.showInputDialog(this, "Show only files matching mask (e.g. *.txt;*.md, exclude: *|*.bak):",
            "Filter", JOptionPane.PLAIN_MESSAGE, null, null, filter);
        if (mask != null) {
            setFilter(mask);
        }
        focusTable();
    }

    /** Shift+F6 on "..": edit the path in the header. */
    public void editPath() {
        final String path = (String) JOptionPane.showInputDialog(this, "Go to:", "Path", JOptionPane.PLAIN_MESSAGE, null, null,
            dir.getLocation());
        if (path != null && !path.isBlank()) {
            navigate(path.trim().replaceFirst("^~", System.getProperty("user.home")));
        }
        focusTable();
    }

    private static @NotNull JButton smallButton(final @NotNull String text, final @NotNull String tooltip) {
        final JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.setMargin(new Insets(0, 4, 0, 4));
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");

        return b;
    }

    private void installKeys() {
        bind("BACK_SPACE", this::goUp);
        bind("ctrl PAGE_UP", this::goUp);
        bind("ctrl PAGE_DOWN", this::openCursorAsArchive);
        bind("ctrl BACK_SLASH", this::goRoot);
        bind("ctrl LESS", this::goRoot);
        bind("INSERT", () -> toggleMarkAndMove(1));
        bind("shift DOWN", () -> toggleMarkAndMove(1));
        bind("shift UP", () -> toggleMarkAndMove(-1));
        bind("alt shift UP", this::toggleMarkStay);
        bind("ctrl A", () -> markAll(true, false));
        bind("ctrl ADD", () -> markAll(true, false));
        bind("ctrl SUBTRACT", () -> markAll(false, false));
        bind("ctrl shift ADD", () -> markAll(true, true));
        bind("ctrl shift SUBTRACT", () -> markAll(false, true));
        bind("ADD", () -> askMarkGroup(true, false));
        bind("SUBTRACT", () -> askMarkGroup(false, false));
        bind("shift ADD", () -> askMarkGroup(true, true));
        bind("shift SUBTRACT", () -> askMarkGroup(false, true));
        bind("MULTIPLY", () -> invertMarks(false));
        bind("shift MULTIPLY", () -> invertMarks(true));
        bind("DIVIDE", this::restoreMarks);
        bind("alt ADD", () -> markByCursorExtension(true));
        bind("alt SUBTRACT", () -> markByCursorExtension(false));
        bind("HOME", () -> table.setCursorRow(0));
        bind("END", () -> table.setCursorRow(model.getRowCount() - 1));
        bind("ctrl B", () -> toggleFlatView(false));
        bind("ctrl shift B", () -> toggleFlatView(true));
        bind("ctrl S", () -> openQuickBar(true, quickFilter));
        bind("ctrl shift S", this::reapplyQuickFilter);
        bind("alt shift ENTER", this::calculateAllSizes);
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(final KeyEvent e) {
                // Ctrl+Alt+letter — quick search (AltGr on some layouts produces the same combination)
                final int code = e.getKeyCode();
                final boolean letter = code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z || code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9;
                if (e.isControlDown() && e.isAltDown() && !e.isShiftDown() && letter) {
                    e.consume();
                    final char c = e.getKeyChar();
                    openQuickBar(false, String.valueOf(Character.isLetterOrDigit(c) ? c : (char) code).toLowerCase(Locale.ROOT));
                }
            }

            @Override
            public void keyTyped(final KeyEvent e) {
                final char c = e.getKeyChar();
                if (c > ' ' && c != KeyEvent.CHAR_UNDEFINED && c != 127 && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                    e.consume();
                    owner.getHost().typedToCommandLine(c);
                }
            }
        });
    }

    private void askMarkGroup(final boolean mark, final boolean filesOnly) {
        final String title = (mark ? "Select" : "Unselect") + (filesOnly ? " files" : " group");
        final String mask = (String) JOptionPane.showInputDialog(this, "Mask (e.g. *.txt;*.md):", title, JOptionPane.PLAIN_MESSAGE, null, null,
            "*.*");
        if (mask != null && !mask.isBlank()) {
            markByMask(mask, mark, filesOnly);
        }
        focusTable();
    }

    public void bind(final @NotNull String key, final @NotNull Runnable action) {
        final KeyStroke ks = KeyStroke.getKeyStroke(key);
        if (ks == null) {
            throw new IllegalArgumentException("Bad key: " + key);
        }
        table.getInputMap(JComponent.WHEN_FOCUSED).put(ks, key);
        table.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                action.run();
            }
        });
    }

    private void installMouse() {
        // PanelMouse detects double click itself, on the second button press
        table.setMouseFilter(new PanelMouse(this));
        // own DropTarget, not UIResource: BasicTableUI will not uninstall it on theme change
        table.setDropTarget(new DropTarget(table, DnDConstants.ACTION_COPY_OR_MOVE, new PanelDrop(this), true));
    }

    public void showContextMenu() {
        final int row = Math.max(0, table.getCursorRow());
        final Rectangle rect = table.getCellRect(row, 0, true);
        showContextMenu(new Point(rect.x + 20, rect.y + rect.height));
    }

    public void showContextMenu(final @NotNull Point at) {
        owner.getHost().createContextMenu(this).show(table, at.x, at.y);
    }

    /** Menu of the current directory (right button on empty space). */
    public void showDirMenu(final @NotNull Point at) {
        owner.getHost().createDirMenu(this).show(table, at.x, at.y);
    }
}
