package tcm;

import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.apps.CompareWindow;
import tcm.apps.ConsoleWindow;
import tcm.apps.EditorWindow;
import tcm.apps.ViewerWindow;
import tcm.core.ArchiveIndex;
import tcm.core.AttribChange;
import tcm.core.Progress;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.core.ZipAddition;
import tcm.core.ZipEdit;
import tcm.core.ZipOps;
import tcm.ops.AttribJob;
import tcm.ops.CopyJob;
import tcm.ops.DeleteJob;
import tcm.ops.Job;
import tcm.ops.JobRunner;
import tcm.ops.LinkJob;
import tcm.ops.PackJob;
import tcm.ops.TestJob;
import tcm.ops.UnpackJob;
import tcm.ui.FileClipboard;
import tcm.ui.PanelTab;
import tcm.ui.dialogs.AttribDialog;
import tcm.ui.dialogs.Dialogs;
import tcm.ui.dialogs.MultiRenameDialog;
import tcm.ui.dialogs.PackDialog;
import tcm.ui.dialogs.PropertiesDialog;
import tcm.ui.dialogs.SearchDialog;
import tcm.ui.dialogs.TargetDialog;

/**
 * File commands of the main window: F3–F8, packing, search, comparison, clipboard, launching files and the command line.
 */
public final class FileCommands {

    private final @NotNull MainFrame frame;
    private final FileClipboard clipboard = new FileClipboard();

    /** Target directory and (for a single object) a new name. */
    static final class Target {

        private final @NotNull VFile dir;
        private final @Nullable String name;

        Target(final @NotNull VFile dir, final @Nullable String name) {
            this.dir = dir;
            this.name = name;
        }
    }

    public FileCommands(final @NotNull MainFrame frame) {
        this.frame = frame;
    }

    private @NotNull PanelTab active() {
        return frame.getActivePanel().getCurrentTab();
    }

    private @NotNull PanelTab other() {
        return frame.getOtherPanel().getCurrentTab();
    }

    private void run(final @NotNull Job job, final boolean queued) {
        JobRunner.run(frame, job, queued, frame::refreshPanels);
    }

    // ---- viewing and editing ----

    /** F3: selected files (or the file under the cursor); on a directory — enter it, as Enter does. */
    public void view(final boolean cursorOnly) {
        final PanelTab tab = active();
        final VFile cursor = tab.getCursorFile();
        final List<VFile> marked = new ArrayList<>();
        for (final VFile f : tab.getModel().getMarked()) {
            if (!f.isDirectory()) {
                marked.add(f);
            }
        }
        if (!cursorOnly && !marked.isEmpty()) {
            final int idx = cursor == null ? 0 : Math.max(0, marked.indexOf(cursor));
            ViewerWindow.open(frame, marked, idx, this::edit);

            return;
        }
        if (cursor == null) {
            return;
        }
        if (cursor.isDirectory()) {
            tab.openCursor();

            return;
        }
        ViewerWindow.open(frame, List.of(cursor), 0, this::edit);
    }

    public void view(final @NotNull List<VFile> files, final int index) {
        ViewerWindow.open(frame, files, index, this::edit);
    }

    /** Alt+F3: external viewer — the program associated with the file type. */
    public void viewExternal() {
        final VFile f = active().getCursorFile();
        if (f != null && !f.isParentLink()) {
            openWithSystem(f);
        }
    }

    public void edit() {
        final VFile f = active().getCursorFile();
        if (f != null && !f.isDirectory()) {
            edit(f);
        }
    }

    public void edit(final @NotNull VFile f) {
        EditorWindow.open(frame, f, frame::refreshPanels);
    }

    /** Shift+F4: create a text file and open it in the editor. */
    public void newFile() {
        final PanelTab tab = active();
        // as in TC: the name under the cursor is prefilled — handy to adjust it (another extension, a copy alongside)
        final VFile cursor = tab.getCursorFile();
        final String name = ask("Create new text file:", "New file", cursor == null || cursor.isParentLink() ? "new.txt" : cursor.getName());
        if (name == null) {
            return;
        }
        try {
            final VFile dir = tab.getDir();
            final VFile file;
            if (dir.isLocal()) {
                final Path p = dir.getHostPath().resolve(name);
                if (Files.isDirectory(p)) {
                    throw new IOException(name + " is a directory");
                }
                if (!Files.exists(p)) {
                    Files.createDirectories(p.getParent());
                    Files.createFile(p);
                }
                file = Vfs.stat(p);
            } else {
                final String entry = ArchiveIndex.join(dir.getEntry(), name);
                if (Vfs.index(dir.getHostPath()).get(entry) == null) {
                    ZipOps.apply(dir.getHostPath(), new ZipEdit().add(ZipAddition.file(entry, 0, System.currentTimeMillis(),
                        () -> new ByteArrayInputStream(new byte[0]))), Progress.NONE);
                }
                file = Vfs.file(dir.getHostPath() + VFile.ARCHIVE_SEPARATOR + entry);
            }
            tab.refresh();
            tab.selectName(file.getName());
            edit(file);
        } catch (final IOException e) {
            Dialogs.error(frame, "Cannot create " + name, e);
        }
    }

    // ---- copying, moving ----

    /** F5/F6: to the other panel (or to an entered path). */
    public void copyOrMove(final boolean move) {
        final List<VFile> src = active().getSelectedFiles();
        if (!src.isEmpty()) {
            dropFiles(src, other().getDir(), move);
        }
    }

    /** Mouse drag: the same dialog as F5/F6, with the directory the files were dropped on. */
    public void dropFiles(final @NotNull List<VFile> src, final @NotNull VFile targetDir, final boolean move) {
        final String title = move ? "Rename/move" : "Copy";
        final TargetDialog.Result r = TargetDialog.show(frame, title, (move ? "Rename/move " : "Copy ") + describe(src) + " to:",
            dirLocation(targetDir), frame.getFavorites());
        if (r != null) {
            startCopy(src, r, move);
        }
    }

    /** Shift+F5/Shift+F6 with selection: copy/rename within the same directory. */
    public void copySameDir(final boolean move) {
        final List<VFile> src = active().getSelectedFiles();
        if (src.isEmpty()) {
            return;
        }
        final String def = src.size() == 1 ? src.get(0).getName() : dirLocation(active().getDir());
        final TargetDialog.Result r = TargetDialog.show(frame, move ? "Rename" : "Copy", (move ? "Rename " : "Copy ") + describe(src) + " to:",
            def, frame.getFavorites());
        if (r != null) {
            startCopy(src, r, move);
        }
    }

    private void startCopy(final @NotNull List<VFile> src, final @NotNull TargetDialog.Result r, final boolean move) {
        try {
            final Target t = resolveTarget(r.getTarget(), active().getDir(), src.size());
            run(new CopyJob(src, t.dir, t.name, r.getMask(), move), r.isQueued());
        } catch (final IOException e) {
            Dialogs.error(frame, "Invalid target: " + r.getTarget(), e);
        }
    }

    /** Ctrl+Shift+F5: shortcuts (symbolic links) to the selected files. */
    public void createLinks() {
        final List<VFile> src = active().getSelectedFiles();
        if (src.isEmpty()) {
            return;
        }
        final TargetDialog.Result r = TargetDialog.show(frame, "Create symbolic links", "Create links to " + describe(src) + " in:",
            dirLocation(other().getDir()), frame.getFavorites());
        if (r == null) {
            return;
        }
        try {
            final Target t = resolveTarget(r.getTarget(), active().getDir(), Integer.MAX_VALUE);
            if (!t.dir.isLocal()) {
                throw new IOException("Links cannot be created inside an archive");
            }
            run(new LinkJob(src, t.dir.getHostPath()), r.isQueued());
        } catch (final IOException e) {
            Dialogs.error(frame, "Cannot create links", e);
        }
    }

    /** Parse the target path: an existing directory, a new name for a single object, or a directory to create. */
    @NotNull
    Target resolveTarget(final @NotNull String text, final @NotNull VFile base, final int count) throws IOException {
        final String t = expandHome(text.trim());
        final String full = absolute(t, base);
        final boolean dirIntent = t.endsWith("/") || t.endsWith(File.separator);
        try {
            return new Target(Vfs.resolve(full), null);
        } catch (final IOException e) {
            // not a directory — a new name or a new directory
        }
        final String stripped = full.replaceAll("[/\\\\]+$", "");
        if (count == 1 && !dirIntent) {
            final int idx = stripped.indexOf(VFile.ARCHIVE_SEPARATOR);
            if (idx >= 0) {
                final String entry = ArchiveIndex.normalize(stripped.substring(idx + 2));
                final String parent = stripped.substring(0, idx) + VFile.ARCHIVE_SEPARATOR + ArchiveIndex.parentOf(entry);

                return new Target(Vfs.resolve(parent), entry.substring(entry.lastIndexOf('/') + 1));
            }
            final Path p = Path.of(stripped).normalize();

            return new Target(Vfs.resolve(String.valueOf(p.getParent())), String.valueOf(p.getFileName()));
        }
        makeDirs(stripped);

        return new Target(Vfs.resolve(stripped), null);
    }

    private static @NotNull String absolute(final @NotNull String t, final @NotNull VFile base) {
        if (t.contains(VFile.ARCHIVE_SEPARATOR) || Path.of(t.isEmpty() ? "." : t).isAbsolute()) {
            return t;
        }
        if (base.isInArchive()) {
            return base.getHostPath() + VFile.ARCHIVE_SEPARATOR + ArchiveIndex.join(base.getEntry(), t);
        }

        return base.getHostPath().resolve(t).normalize().toString();
    }

    private static void makeDirs(final @NotNull String location) throws IOException {
        final int idx = location.indexOf(VFile.ARCHIVE_SEPARATOR);
        if (idx >= 0) {
            ZipOps.mkdir(Path.of(location.substring(0, idx)), location.substring(idx + 2));
        } else {
            Files.createDirectories(Path.of(location));
        }
    }

    // ---- directories, deletion, renaming ----

    /** F7 / Shift+F7: new directory in the active (or other) panel; defaults to the name under the cursor. */
    public void mkdir(final boolean inOther) {
        final VFile cursor = active().getCursorFile();
        final String def = cursor == null || cursor.isParentLink() ? "" : cursor.getBaseName();
        final PanelTab tab = inOther ? other() : active();
        final String name = ask("New folder (directory) in " + tab.getDir().getLocation() + ":", "New folder", def);
        if (name == null) {
            return;
        }
        try {
            final VFile dir = tab.getDir();
            if (dir.isInArchive()) {
                ZipOps.mkdir(dir.getHostPath(), ArchiveIndex.join(dir.getEntry(), name));
            } else {
                Files.createDirectories(dir.getHostPath().resolve(name));
            }
            tab.refresh();
            tab.selectName(name.split("[/\\\\]")[0]);
        } catch (final IOException e) {
            Dialogs.error(frame, "Cannot create directory " + name, e);
        }
    }

    /** F8/Del — to the trash, Shift+F8/Shift+Del — permanently. */
    public void delete(final boolean permanent) {
        final List<VFile> src = active().getSelectedFiles();
        if (src.isEmpty()) {
            return;
        }
        final boolean archive = src.get(0).isInArchive();
        final boolean[] answer = Dialogs.confirmDelete(frame, describe(src), permanent, archive);
        if (answer != null) {
            run(new DeleteJob(src, answer[0]), false);
        }
    }

    /** Shift+F6: one file — edit the name in the panel, selection — multi-rename, on ".." — edit the path. */
    public void rename() {
        final PanelTab tab = active();
        final VFile cursor = tab.getCursorFile();
        if (cursor != null && cursor.isParentLink() && tab.getModel().getMarked().isEmpty()) {
            tab.editPath();

            return;
        }
        if (tab.getModel().getMarked().size() > 1) {
            multiRename();

            return;
        }
        tab.startRename();
    }

    public void multiRename() {
        final List<VFile> src = active().getSelectedFiles();
        if (!src.isEmpty() && MultiRenameDialog.show(frame, src)) {
            frame.refreshPanels();
        }
    }

    // ---- archives ----

    /** Alt+F5 / Alt+Shift+F5: pack (deleting the sources). */
    public void pack(final boolean move) {
        final List<VFile> src = active().getSelectedFiles();
        if (src.isEmpty()) {
            return;
        }
        final String baseName = src.size() == 1 ? src.get(0).getBaseName() : active().getDir().getName();
        final Path dir = other().getWorkingDirectory();
        final PackDialog.Result r = PackDialog.show(frame, "Pack " + describe(src) + " to the archive:",
            dir.resolve((baseName.isEmpty() ? "archive" : baseName) + ".zip").toString());
        if (r == null) {
            return;
        }
        final Path archive = active().getWorkingDirectory().resolve(expandHome(r.getArchive()));
        run(new PackJob(src, archive, r.isRecursive(), r.isMove() || move), r.isQueued());
    }

    /** Alt+F9 / Alt+F6: unpack the selected archives or the selected files of an open archive. */
    public void unpack() {
        final PanelTab tab = active();
        final List<VFile> sel = tab.getSelectedFiles();
        final boolean inArchive = tab.getDir().isInArchive();
        final List<VFile> archives = new ArrayList<>();
        for (final VFile f : sel) {
            if (f.isArchiveFile() && f.isLocal()) {
                archives.add(f);
            }
        }
        if (!inArchive && archives.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No ZIP/JAR archives selected.", "Unpack", JOptionPane.INFORMATION_MESSAGE);

            return;
        }
        final String what = inArchive ? describe(sel) : archives.size() + " archive(s)";
        final TargetDialog.Result r = TargetDialog.show(frame, "Unpack files", "Unpack " + what + " to:", dirLocation(other().getDir()),
            frame.getFavorites());
        if (r == null) {
            return;
        }
        try {
            final Target t = resolveTarget(r.getTarget(), tab.getDir(), Integer.MAX_VALUE);
            if (inArchive) {
                final List<VFile> src = sel.isEmpty() ? Vfs.list(tab.getDir()) : sel;
                run(new CopyJob(src, t.dir, null, r.getMask(), false), r.isQueued());
            } else {
                run(new UnpackJob(archives, t.dir, r.getMask()), r.isQueued());
            }
        } catch (final IOException e) {
            Dialogs.error(frame, "Invalid target: " + r.getTarget(), e);
        }
    }

    /** Alt+Shift+F9 / Alt+Shift+F6: test archives. */
    public void testArchives() {
        final PanelTab tab = active();
        final List<VFile> archives = new ArrayList<>();
        for (final VFile f : tab.getSelectedFiles()) {
            if (f.isArchiveFile() && f.isLocal()) {
                archives.add(f);
            }
        }
        if (archives.isEmpty() && tab.getDir().isInArchive()) {
            try {
                archives.add(Vfs.stat(tab.getDir().getHostPath()));
            } catch (final IOException e) {
                Dialogs.error(frame, "Cannot read archive", e);

                return;
            }
        }
        if (archives.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No ZIP/JAR archives selected.", "Test archives", JOptionPane.INFORMATION_MESSAGE);

            return;
        }
        final TestJob job = new TestJob(archives);
        JobRunner.run(frame, job, false, () -> showReport("Test archives", job.getReport()));
    }

    private void showReport(final @NotNull String title, final @NotNull List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        final JTextArea area = new JTextArea(String.join("\n", lines), Math.min(20, lines.size() + 1), 70);
        area.setEditable(false);
        JOptionPane.showMessageDialog(frame, new JScrollPane(area), title, JOptionPane.INFORMATION_MESSAGE);
    }

    // ---- search and comparison ----

    public void search() {
        final SearchDialog dialog = new SearchDialog(frame, new SearchDialog.Host() {
            @Override
            public void goToFile(final @NotNull VFile file) {
                active().goToFile(file);
                active().focusTable();
            }

            @Override
            public void feedToPanel(final @NotNull List<VFile> files) {
                active().feed(files, "Search results");
                active().focusTable();
            }

            @Override
            public void view(final @NotNull List<VFile> files, final int index) {
                FileCommands.this.view(files, index);
            }

            @Override
            public void edit(final @NotNull VFile file) {
                FileCommands.this.edit(file);
            }
        }, active().getDir().getLocation());
        dialog.setVisible(true);
    }

    /** Compare by content: two marked files or the files under the cursor in both panels. */
    public void compareContent() {
        final List<VFile> marked = active().getModel().getMarked();
        VFile a = null;
        VFile b = null;
        if (marked.size() == 2) {
            a = marked.get(0);
            b = marked.get(1);
        } else {
            final VFile cursor = active().getCursorFile();
            if (cursor != null && !cursor.isDirectory()) {
                a = cursor;
                try {
                    b = Vfs.findChild(other().getDir(), cursor.getName());
                } catch (final IOException e) {
                    b = null;
                }
            }
            final VFile otherCursor = other().getCursorFile();
            if ((b == null || b.isDirectory()) && otherCursor != null && !otherCursor.isDirectory()) {
                b = otherCursor;
            }
        }
        if (frame.getActivePanel() == frame.getRightPanel() && marked.size() != 2) {
            final VFile t = a;
            a = b;
            b = t;
        }
        CompareWindow.open(frame, a, b);
    }

    /** Shift+F2: mark differing files in both panels (unique and newer ones). */
    public void compareLists() {
        final PanelTab l = frame.getLeftPanel().getCurrentTab();
        final PanelTab r = frame.getRightPanel().getCurrentTab();
        final Map<String, VFile> left = byName(l);
        final Map<String, VFile> right = byName(r);
        l.markAll(false, false);
        r.markAll(false, false);
        int differences = 0;
        differences += markDifferent(l, left, right);
        differences += markDifferent(r, right, left);
        l.updateLabels();
        r.updateLabels();
        if (differences == 0) {
            JOptionPane.showMessageDialog(frame, "The panels contain the same files.", "Compare directories", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static @NotNull Map<String, VFile> byName(final @NotNull PanelTab tab) {
        final Map<String, VFile> map = new HashMap<>();
        for (final VFile f : tab.getModel().getFiles()) {
            if (!f.isDirectory()) {
                map.put(f.getName(), f);
            }
        }

        return map;
    }

    private static int markDifferent(final @NotNull PanelTab tab, final @NotNull Map<String, VFile> mine,
        final @NotNull Map<String, VFile> theirs) {
        int count = 0;
        for (int row = 0; row < tab.getModel().getRowCount(); row++) {
            final VFile f = tab.getModel().getFile(row);
            if (f.isDirectory()) {
                continue;
            }
            final VFile o = theirs.get(f.getName());
            // one-second precision: file systems and archives store time differently
            final boolean newer = o != null && f.getModified() / 2000 > o.getModified() / 2000;
            final boolean sameTimeDiffSize = o != null && f.getModified() / 2000 == o.getModified() / 2000 && f.getSize() != o.getSize();
            if (o == null || newer || sameTimeDiffSize) {
                tab.getModel().setMarked(row, true);
                count++;
            }
        }

        return count;
    }

    // ---- properties, printing, clipboard ----

    public void properties() {
        final List<VFile> sel = active().getSelectedFiles();
        PropertiesDialog.show(frame, sel.isEmpty() ? List.of(active().getDir()) : sel, "Properties");
    }

    /** Change attributes: permissions and modification time of the selected files (on disk only, not in an archive). */
    public void changeAttributes() {
        final List<VFile> sel = active().getSelectedFiles();
        if (sel.isEmpty()) {
            return;
        }
        if (sel.stream().anyMatch(f -> !f.isLocal())) {
            JOptionPane.showMessageDialog(frame, "Attributes cannot be changed inside an archive.", "Change attributes",
                JOptionPane.INFORMATION_MESSAGE);

            return;
        }
        final AttribChange change = AttribDialog.show(frame, sel);
        if (change != null) {
            run(new AttribJob(sel, change), false);
        }
    }

    public void properties(final @NotNull VFile dir) {
        PropertiesDialog.show(frame, List.of(dir), "Properties");
    }

    /** Terminal in the directory; for a directory inside an archive — in the archive's own directory. */
    public void openTerminal(final @NotNull VFile dir) {
        final Path cwd = localDir(dir);
        for (final List<String> cmd : terminalCommands(cwd)) {
            try {
                new ProcessBuilder(cmd).directory(cwd.toFile()).start();

                return;
            } catch (final IOException e) {
                // no such program — try the next one
            }
        }
        JOptionPane.showMessageDialog(frame, "No terminal emulator found.", "Terminal", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Candidates in order: user's choice ($TERMINAL), system default (Debian, XFCE), common ones.
     * The directory is set as the process working directory: almost all terminals inherit it.
     */
    private static @NotNull List<List<String>> terminalCommands(final @NotNull Path cwd) {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of(List.of("cmd", "/c", "start", "cmd"));
        }
        if (os.contains("mac")) {
            return List.of(List.of("open", "-a", "Terminal", cwd.toString()));
        }
        final List<List<String>> result = new ArrayList<>();
        final String preferred = System.getenv("TERMINAL");
        if (preferred != null && !preferred.isBlank()) {
            result.add(List.of(preferred));
        }
        result.add(List.of("x-terminal-emulator"));
        result.add(List.of("exo-open", "--launch", "TerminalEmulator", "--working-directory", cwd.toString()));
        for (final String name : new String[] {"gnome-terminal", "konsole", "xfce4-terminal", "mate-terminal", "lxterminal", "xterm"}) {
            result.add(List.of(name));
        }

        return result;
    }

    /** Explore: the system file manager in the directory. */
    public void explore(final @NotNull VFile dir) {
        final Path p = localDir(dir);
        try {
            launch(p);
        } catch (final IOException | RuntimeException e) {
            Dialogs.error(frame, "Cannot open " + p, e);
        }
    }

    private static @NotNull Path localDir(final @NotNull VFile dir) {
        if (dir.isLocal()) {
            return dir.getHostPath();
        }
        final Path parent = dir.getHostPath().getParent();

        return parent != null ? parent : dir.getHostPath();
    }

    /** Ctrl+L: disk usage. */
    public void occupiedSpace() {
        final List<VFile> sel = active().getSelectedFiles();
        PropertiesDialog.show(frame, sel.isEmpty() ? List.of(active().getDir()) : sel, "Occupied space");
    }

    /** Ctrl+F9: print via associated programs. */
    public void print() {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.PRINT)) {
            JOptionPane.showMessageDialog(frame, "Printing is not supported on this system.", "Print", JOptionPane.INFORMATION_MESSAGE);

            return;
        }
        for (final VFile f : active().getSelectedFiles()) {
            try {
                if (!f.isDirectory()) {
                    Desktop.getDesktop().print(localCopy(f).toFile());
                }
            } catch (final IOException | RuntimeException e) {
                Dialogs.error(frame, "Cannot print " + f.getName(), e);
            }
        }
    }

    public void copyToClipboard(final boolean cut) {
        final List<VFile> sel = active().getSelectedFiles();
        if (!sel.isEmpty()) {
            clipboard.put(sel, cut);
        }
    }

    public void paste() {
        final FileClipboard.Content content = clipboard.take();
        if (content != null && !content.getFiles().isEmpty()) {
            run(new CopyJob(content.getFiles(), active().getDir(), null, "*", content.isMove()), false);
        }
    }

    // ---- launching ----

    /**
     * Enter on a file: a program — in the console, anything else — with the associated application.
     * false — there is no associated application (the caller decides: the panel tries to open the file as an archive).
     */
    public boolean openFile(final @NotNull VFile f) {
        if (f.isLocal() && isExecutable(f)) {
            final Path p = f.getHostPath();
            ConsoleWindow.run(frame, quote(p.toString()), p.getParent());

            return true;
        }
        final Path p;
        try {
            p = localCopy(f);
        } catch (final IOException e) {
            Dialogs.error(frame, "Cannot open " + f.getName(), e);

            return true;
        }
        try {
            launch(p);
        } catch (final IOException e) {
            // this is also how Desktop.open reports that there is no program for this file type
            return false;
        } catch (final RuntimeException e) {
            Dialogs.error(frame, "Cannot open " + f.getName(), e);
        }

        return true;
    }

    public void openWithSystem(final @NotNull VFile f) {
        try {
            launch(localCopy(f));
        } catch (final IOException | RuntimeException e) {
            Dialogs.error(frame, "Cannot open " + f.getName(), e);
        }
    }

    private static void launch(final @NotNull Path p) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(p.toFile());

            return;
        }
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        new ProcessBuilder(os.contains("mac") ? "open" : "xdg-open", p.toString()).start();
    }

    private static boolean isExecutable(final @NotNull VFile f) {
        return !f.isDirectory() && f.getAttrs().length() == 9 && f.getAttrs().charAt(2) == 'x';
    }

    /** A file from an archive is unpacked into a temporary directory so an external program can open it. */
    private static @NotNull Path localCopy(final @NotNull VFile f) throws IOException {
        if (f.isLocal()) {
            return f.getHostPath();
        }
        final Path dir = Files.createTempDirectory("tcm-open");
        final Path out = dir.resolve(f.getName());
        Files.write(out, Vfs.readAll(f));
        out.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();

        return out;
    }

    /** Command line: cd/md/rd run internally, everything else — in a console window in the active panel's directory. */
    public void execute(final @NotNull String command, final boolean keepOpen) {
        final String[] parts = command.split("\\s+", 2);
        final String verb = parts[0].toLowerCase(Locale.ROOT);
        final String arg = parts.length > 1 ? unquote(parts[1].trim()) : "";
        try {
            switch (verb) {
                case "cd":
                    changeDir(arg);

                    return;
                case "md":
                case "mkdir":
                    makeDirs(absolute(expandHome(arg), active().getDir()));
                    active().refresh();
                    active().selectName(arg.split("[/\\\\]")[0]);

                    return;
                case "rd":
                case "rmdir":
                    Files.delete(Path.of(absolute(expandHome(arg), active().getDir())));
                    active().refresh();

                    return;
                default:
                    ConsoleWindow.run(frame, command, active().getWorkingDirectory());
            }
        } catch (final IOException | RuntimeException e) {
            Dialogs.error(frame, command, e);
        }
    }

    private void changeDir(final @NotNull String arg) {
        final PanelTab tab = active();
        if (arg.isEmpty() || "~".equals(arg)) {
            tab.navigate(System.getProperty("user.home"));
        } else if ("..".equals(arg)) {
            tab.goUp();
        } else if ("\\".equals(arg) || "/".equals(arg) && tab.getDir().isInArchive()) {
            tab.goRoot();
        } else {
            tab.navigate(absolute(expandHome(arg), tab.getDir()));
        }
        tab.focusTable();
    }

    // ---- utilities ----

    private @Nullable String ask(final @NotNull String prompt, final @NotNull String title, final @NotNull String def) {
        final Object v = JOptionPane.showInputDialog(frame, prompt, title, JOptionPane.PLAIN_MESSAGE, null, null, def);
        frame.focusActive();

        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    static @NotNull String describe(final @NotNull List<VFile> files) {
        if (files.size() == 1) {
            return "\"" + files.get(0).getName() + "\"";
        }
        int dirs = 0;
        for (final VFile f : files) {
            dirs += f.isDirectory() ? 1 : 0;
        }

        return (files.size() - dirs) + " file(s), " + dirs + " dir(s)";
    }

    /** Directory path with a trailing separator — this is how TC fills the target into the copy dialog. */
    static @NotNull String dirLocation(final @NotNull VFile dir) {
        if (dir.isInArchive()) {
            return dir.getHostPath() + VFile.ARCHIVE_SEPARATOR + (dir.getEntry().isEmpty() ? "" : dir.getEntry() + "/");
        }
        final String loc = dir.getLocation();

        return loc.endsWith(File.separator) ? loc : loc + File.separator;
    }

    static @NotNull String expandHome(final @NotNull String path) {
        return path.equals("~") || path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path;
    }

    private static @NotNull String quote(final @NotNull String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }

    private static @NotNull String unquote(final @NotNull String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
