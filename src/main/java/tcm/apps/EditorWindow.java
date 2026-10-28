package tcm.apps;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import javax.swing.undo.UndoManager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.ArchiveIndex;
import tcm.core.Progress;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.core.ZipAddition;
import tcm.core.ZipEdit;
import tcm.core.ZipOps;
import tcm.util.Keys;
import tcm.util.TextUtil;

/**
 * Text editor (F4): saving with F2/Ctrl+S in the original encoding (with BOM if it was present), including back into an archive.
 */
public final class EditorWindow {

    private static final int TAB_SIZE = 4;

    private final @NotNull Window window;
    private final JTextArea area = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final UndoManager undo = new UndoManager();
    private final TextFinder finder = new TextFinder();
    /** Changes after "Save as": from then on the new file is edited. */
    private @NotNull VFile file;
    private final @Nullable Runnable onSaved;
    /** null — the file is still loading: nothing to save. */
    private TextUtil.@Nullable Decoded decoded;
    private boolean modified;
    private @Nullable SwingWorker<Object[], Void> loader;
    private final DocumentListener modifiedListener = new DocumentListener() {
        @Override
        public void insertUpdate(final DocumentEvent e) {
            setModified(true);
        }

        @Override
        public void removeUpdate(final DocumentEvent e) {
            setModified(true);
        }

        @Override
        public void changedUpdate(final DocumentEvent e) {
            // attributes do not change in plain text
        }
    };

    /** The window opens immediately, the file is read in the background: a large file or a file from an archive does not hold up the main window. */
    private EditorWindow(final @Nullable Window owner, final @NotNull VFile file, final @Nullable Runnable onSaved) {
        this.file = file;
        this.onSaved = onSaved;
        window = AppWindows.create(owner, file.getName());
        AppWindows.setFileIcon(window, false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setEditable(false);
        area.addCaretListener(e -> updateStatus());
        final JScrollPane scroll = new JScrollPane(area);
        scroll.setRowHeaderView(new LineNumbers(area));
        AppWindows.container(window).getContentPane().add(scroll, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        AppWindows.container(window).getContentPane().add(status, BorderLayout.SOUTH);
        installKeys();
        AppWindows.container(window).getRootPane().setJMenuBar(createMenuBar());
        updateTitle();
        setCloseHandler();
        // a window closed during reading cancels it
        AppWindows.onClosed(window, this::cancelLoading);
        window.setVisible(true);
        load();
    }

    public static void open(final @Nullable Window owner, final @NotNull VFile file, final @Nullable Runnable onSaved) {
        new EditorWindow(owner, file, onSaved);
    }

    /** Reading and decoding in the background; the document is also built off the EDT and replaces the current one entirely. */
    private void load() {
        cancelLoading();
        final VFile source = file;
        area.setEditable(false);
        status.setText("Loading " + source.getName() + "...");
        final SwingWorker<Object[], Void> w = new SwingWorker<>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                final TextUtil.Decoded d = TextUtil.decode(Vfs.readAll(source));
                final PlainDocument doc = new PlainDocument();
                doc.putProperty(PlainDocument.tabSizeAttribute, TAB_SIZE);
                doc.insertString(0, d.getText(), null);

                return new Object[] {d, doc, LongLines.longest(d.getText())};
            }

            @Override
            protected void done() {
                if (isCancelled() || loader != this) {
                    return;
                }
                loader = null;
                try {
                    final Object[] r = get();
                    showLoaded((TextUtil.Decoded) r[0], (PlainDocument) r[1], (Integer) r[2]);
                } catch (final InterruptedException | CancellationException e) {
                    // reading was cancelled
                } catch (final ExecutionException e) {
                    JOptionPane.showMessageDialog(window, "Cannot open " + source.getLocation() + "\n" + e.getCause().getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
                    if (decoded == null) {
                        window.dispose();
                    } else {
                        area.setEditable(true);
                        updateStatus();
                    }
                }
            }
        };
        loader = w;
        w.execute();
    }

    private void showLoaded(final TextUtil.@NotNull Decoded d, final @NotNull PlainDocument doc, final int longest) {
        decoded = d;
        // the editor cannot cut lines as the viewer does: too wide lines are wrapped (see LongLines)
        area.setLineWrap(longest > LongLines.maxChars(area, area.getFont()));
        area.setDocument(doc);
        doc.addUndoableEditListener(undo);
        doc.addDocumentListener(modifiedListener);
        undo.discardAllEdits();
        area.setCaretPosition(0);
        area.setEditable(true);
        setModified(false);
        updateStatus();
        area.requestFocusInWindow();
    }

    private void cancelLoading() {
        final SwingWorker<Object[], Void> w = loader;
        loader = null;
        if (w != null) {
            w.cancel(true);
        }
    }

    private void installKeys() {
        final JComponent root = AppWindows.container(window).getRootPane();
        Keys.bindWindow(root, "F2", this::save);
        Keys.bindWindow(root, "ctrl S", this::save);
        Keys.bindWindow(root, "ESCAPE", this::close);
        Keys.bindWindow(root, "F10", this::close);
        Keys.bindWindow(root, "F11", () -> AppWindows.toggleMaximized(window));
        Keys.bindWindow(root, "F7", this::find);
        Keys.bindWindow(root, "ctrl F", this::find);
        Keys.bindWindow(root, "F3", () -> finder.findNext(area, false));
        Keys.bindWindow(root, "shift F3", () -> finder.findNext(area, true));
        Keys.bindWindow(root, "ctrl G", this::goToLine);
        Keys.bind(area, JComponent.WHEN_FOCUSED, "ctrl Z", () -> {
            if (undo.canUndo()) {
                undo.undo();
            }
        });
        Keys.bind(area, JComponent.WHEN_FOCUSED, "ctrl Y", () -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        });
        Keys.bind(area, JComponent.WHEN_FOCUSED, "ctrl shift Z", () -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        });
    }

    /** The editor has only File and Edit; item keys work without the menu too. */
    private @NotNull JMenuBar createMenuBar() {
        final JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic('F');
        fileMenu.add(AppWindows.item("Save", "F2", this::save));
        fileMenu.add(AppWindows.item("Save as...", null, this::saveAs));
        fileMenu.add(AppWindows.item("Reload file", null, this::reload));
        fileMenu.addSeparator();
        fileMenu.add(AppWindows.item("Close", "ESCAPE", this::close));
        final JMenu editMenu = new JMenu("Edit");
        editMenu.setMnemonic('E');
        editMenu.add(AppWindows.item("Copy as text", "ctrl C", area::copy));
        editMenu.add(AppWindows.item("Select all", "ctrl A", area::selectAll));
        editMenu.addSeparator();
        editMenu.add(AppWindows.item("Find", "F7", this::find));
        editMenu.add(AppWindows.item("Find next", "F3", () -> finder.findNext(area, false)));
        final JMenuBar bar = new JMenuBar();
        bar.add(fileMenu);
        bar.add(editMenu);

        return bar;
    }

    private void setCloseHandler() {
        if (window instanceof JFrame) {
            ((JFrame) window).setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        } else if (window instanceof JDialog) {
            ((JDialog) window).setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        }
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                close();
            }
        });
    }

    private void find() {
        if (finder.ask(window, false)) {
            finder.findNext(area, false);
        }
    }

    private void goToLine() {
        final String s = JOptionPane.showInputDialog(window, "Line number:", "Go to line", JOptionPane.PLAIN_MESSAGE);
        if (s == null) {
            return;
        }
        try {
            final int line = Math.max(1, Math.min(Integer.parseInt(s.trim()), area.getLineCount()));
            area.setCaretPosition(area.getLineStartOffset(line - 1));
        } catch (final NumberFormatException | BadLocationException e) {
            // invalid line number — just ignore
        }
    }

    private boolean save() {
        final TextUtil.Decoded d = decoded;
        if (d == null) {
            return false;
        }
        final byte[] bytes = TextUtil.encode(area.getText(), d.getCharset(), d.getBom());
        try {
            if (file.isLocal()) {
                Files.write(file.getHostPath(), bytes);
            } else {
                final ZipEdit edit = new ZipEdit().add(ZipAddition.file(file.getEntry(), bytes.length, System.currentTimeMillis(),
                    () -> new ByteArrayInputStream(bytes)));
                ZipOps.apply(file.getHostPath(), edit, Progress.NONE);
            }
        } catch (final IOException e) {
            JOptionPane.showMessageDialog(window, "Cannot save " + file.getLocation() + "\n" + e.getMessage(), "Error",
                JOptionPane.ERROR_MESSAGE);

            return false;
        }
        setModified(false);
        if (onSaved != null) {
            onSaved.run();
        }

        return true;
    }

    /** Saves the text to a new file (in the same encoding) and continues editing it. */
    private void saveAs() {
        final TextUtil.Decoded d = decoded;
        if (d == null) {
            return;
        }
        final Path target = AppWindows.askSaveAs(window, file);
        if (target == null) {
            return;
        }
        try {
            Files.write(target, TextUtil.encode(area.getText(), d.getCharset(), d.getBom()));
            file = Vfs.stat(target);
        } catch (final IOException e) {
            JOptionPane.showMessageDialog(window, "Cannot save " + target + "\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);

            return;
        }
        setModified(false);
        updateTitle();
        if (onSaved != null) {
            onSaved.run();
        }
    }

    /** Re-reads the file from disk; unsaved changes are lost — only after confirmation. */
    private void reload() {
        if (modified && JOptionPane.showConfirmDialog(window, "Discard changes to " + file.getName() + "?", "Editor",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        load();
    }

    private void close() {
        if (modified) {
            final int answer = JOptionPane.showConfirmDialog(window, "Save changes to " + file.getName() + "?", "Editor",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
                return;
            }
            if (answer == JOptionPane.YES_OPTION && !save()) {
                return;
            }
        }
        window.dispose();
    }

    private void setModified(final boolean value) {
        if (modified != value) {
            modified = value;
            updateTitle();
        }
    }

    private void updateTitle() {
        final String where = file.isInArchive()
            ? "  [" + ArchiveIndex.parentOf(file.getEntry()) + " in " + file.getHostPath().getFileName() + "]"
            : "";
        AppWindows.setTitle(window, (modified ? "*" : "") + file.getName() + where + " - Editor");
    }

    private void updateStatus() {
        final TextUtil.Decoded d = decoded;
        if (d == null || loader != null) {
            return;
        }
        try {
            final int pos = area.getCaretPosition();
            final int line = area.getLineOfOffset(pos);
            final int col = pos - area.getLineStartOffset(line);
            status.setText("Ln " + (line + 1) + ", Col " + (col + 1) + "    " + d.getCharset().name() + (d.getBom() != null ? " BOM" : "")
                + (area.getLineWrap() ? ", wrap" : "")
                + "    F2/Ctrl+S save, F7 find, F3 next, Ctrl+G line, Esc close");
        } catch (final BadLocationException e) {
            status.setText(" ");
        }
    }
}
