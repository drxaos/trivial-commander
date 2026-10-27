package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.PatternSyntaxException;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.Searcher;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.ui.Icons;
import tcm.util.Keys;

/**
 * File search (Alt+F7): name mask/RegEx, text, depth, archives, size and date; results — view, go to,
 * feed to panel, search in results (F2), history of result lists (Alt+←/→).
 */
public final class SearchDialog extends JDialog {

    /** Main window actions on the found files. */
    public interface Host {

        void goToFile(@NotNull VFile file);

        void feedToPanel(@NotNull List<VFile> files);

        void view(@NotNull List<VFile> files, int index);

        void edit(@NotNull VFile file);
    }

    private static final String[] DEPTHS = {"all (unlimited depth)", "current dir only", "1 level", "2 levels", "3 levels", "5 levels",
        "10 levels"};
    private static final int[] DEPTH_VALUES = {-1, 0, 1, 2, 3, 5, 10};
    private static final String[] SIZE_UNITS = {"bytes", "KB", "MB", "GB"};
    private static final int FLUSH_MS = 200;

    private final @NotNull Host host;
    private final JTabbedPane pages = new JTabbedPane();
    private final JComboBox<String> searchFor = new JComboBox<>(new String[] {"*", "*.txt", "*.java", "*.jpg;*.png"});
    private final JTextField searchIn = new JTextField(40);
    private final JCheckBox nameRegex = new JCheckBox("RegEx (name)");
    private final JCheckBox archives = new JCheckBox("Search archives (ZIP/JAR)");
    private final JComboBox<String> depth = new JComboBox<>(DEPTHS);
    private final JCheckBox findText = new JCheckBox("Find text:");
    private final JTextField text = new JTextField(30);
    private final JCheckBox caseSensitive = new JCheckBox("Case sensitive");
    private final JCheckBox textRegex = new JCheckBox("RegEx (text)");
    private final JCheckBox notContaining = new JCheckBox("Find files NOT containing the text");
    private final JCheckBox inResults = new JCheckBox("Search in found files (F2)");
    private final JCheckBox minSizeOn = new JCheckBox("Size at least");
    private final JSpinner minSize = new JSpinner(new SpinnerNumberModel(0L, 0L, Long.MAX_VALUE, 1L));
    private final JComboBox<String> minUnit = new JComboBox<>(SIZE_UNITS);
    private final JCheckBox maxSizeOn = new JCheckBox("Size at most");
    private final JSpinner maxSize = new JSpinner(new SpinnerNumberModel(0L, 0L, Long.MAX_VALUE, 1L));
    private final JComboBox<String> maxUnit = new JComboBox<>(SIZE_UNITS);
    private final JCheckBox newerOn = new JCheckBox("Not older than (days)");
    private final JSpinner newerDays = new JSpinner(new SpinnerNumberModel(7, 0, 100000, 1));
    private final JCheckBox olderOn = new JCheckBox("Older than (days)");
    private final JSpinner olderDays = new JSpinner(new SpinnerNumberModel(30, 0, 100000, 1));
    private final JComboBox<String> kind = new JComboBox<>(new String[] {"Files and directories", "Files only", "Directories only"});
    private final DefaultListModel<VFile> results = new DefaultListModel<>();
    private final JList<VFile> resultList = new JList<>(results);
    private final JLabel status = new JLabel(" ");
    private final JButton start = new JButton("Start search");
    private final ConcurrentLinkedQueue<VFile> pending = new ConcurrentLinkedQueue<>();
    private final Timer flush = new Timer(FLUSH_MS, e -> flushResults());
    private final List<List<VFile>> resultHistory = new ArrayList<>();
    private int historyPos = -1;
    private volatile boolean cancelled;
    private volatile @NotNull String currentDir = "";
    private @Nullable Thread worker;

    public SearchDialog(final @NotNull Window owner, final @NotNull Host host, final @NotNull String startDir) {
        super(owner, "Find Files", ModalityType.MODELESS);
        this.host = host;
        searchIn.setText(startDir);
        searchFor.setEditable(true);
        pages.addTab("General", createGeneral());
        pages.addTab("Advanced", createAdvanced());
        final JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(pages, BorderLayout.CENTER);
        top.add(createSideButtons(), BorderLayout.EAST);
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(createResults(), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        getContentPane().add(status, BorderLayout.SOUTH);
        installKeys();
        getRootPane().setDefaultButton(start);
        setSize(860, 640);
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(final WindowEvent e) {
                final Component editor = searchFor.getEditor().getEditorComponent();
                editor.requestFocusInWindow();
                if (editor instanceof JTextField) {
                    ((JTextField) editor).selectAll();
                }
            }
        });
    }

    private @NotNull JPanel createGeneral() {
        final JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        addRow(p, c, 0, new JLabel("Search for:"), searchFor);
        final JPanel in = new JPanel(new BorderLayout(2, 0));
        in.add(searchIn, BorderLayout.CENTER);
        final JButton tree = new JButton(">>");
        tree.setToolTipText("Choose directory (tree)");
        tree.addActionListener(e -> chooseDir());
        in.add(tree, BorderLayout.EAST);
        addRow(p, c, 1, new JLabel("Search in:"), in);
        final JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        opts.add(nameRegex);
        opts.add(archives);
        opts.add(new JLabel("Search in subdirectories:"));
        opts.add(depth);
        addRow(p, c, 2, new JLabel(""), opts);
        addRow(p, c, 3, findText, text);
        final JPanel textOpts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        textOpts.add(caseSensitive);
        textOpts.add(textRegex);
        textOpts.add(notContaining);
        addRow(p, c, 4, new JLabel(""), textOpts);
        addRow(p, c, 5, new JLabel(""), inResults);
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                findText.setSelected(!text.getText().isEmpty());
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                findText.setSelected(!text.getText().isEmpty());
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                // attributes are not used
            }
        });

        return p;
    }

    private @NotNull JPanel createAdvanced() {
        final JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        p.add(row(minSizeOn, minSize, minUnit));
        p.add(row(maxSizeOn, maxSize, maxUnit));
        p.add(row(newerOn, newerDays));
        p.add(row(olderOn, olderDays));
        p.add(row(new JLabel("Find:"), kind));

        return p;
    }

    private static @NotNull JPanel row(final @NotNull Component... items) {
        final JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (final Component c : items) {
            if (c instanceof JSpinner) {
                c.setPreferredSize(new Dimension(110, c.getPreferredSize().height));
            }
            r.add(c);
        }

        return r;
    }

    private static void addRow(final @NotNull JPanel p, final @NotNull GridBagConstraints c, final int y, final @NotNull Component label,
        final @NotNull Component field) {
        c.gridy = y;
        c.gridx = 0;
        c.weightx = 0;
        p.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(field, c);
    }

    private @NotNull JPanel createSideButtons() {
        final JPanel side = new JPanel(new GridLayout(0, 1, 0, 6));
        start.addActionListener(e -> toggleSearch());
        side.add(start);
        side.add(Dialogs.button("Close", this::closeOrStop));
        final JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        wrap.add(side, BorderLayout.NORTH);

        return wrap;
    }

    private @NotNull JPanel createResults() {
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index, final boolean selected,
                final boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                final VFile f = (VFile) value;
                setText(f.isDirectory() ? "[" + f.getLocation() + "]" : f.getLocation());
                setIcon(Icons.forFile(f));

                return this;
            }
        });
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() == 2) {
                    goToSelected();
                }
            }
        });
        Keys.bind(resultList, JComponent.WHEN_FOCUSED, "ENTER", this::goToSelected);
        final JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
        p.add(new JLabel("Search results:"), BorderLayout.NORTH);
        p.add(new JScrollPane(resultList), BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new GridLayout(1, 0, 6, 0));
        buttons.add(Dialogs.button("View (F3)", this::viewSelected));
        buttons.add(Dialogs.button("New search", this::newSearch));
        buttons.add(Dialogs.button("Go to file", this::goToSelected));
        buttons.add(Dialogs.button("Feed to listbox", this::feed));
        buttons.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        p.add(buttons, BorderLayout.SOUTH);

        return p;
    }

    private void installKeys() {
        Dialogs.bindKey(this, "ESCAPE", this::closeOrStop);
        Dialogs.bindKey(this, "F3", this::viewSelected);
        Dialogs.bindKey(this, "alt shift F3", this::viewSelected);
        Dialogs.bindKey(this, "F4", () -> {
            final VFile f = resultList.getSelectedValue();
            if (f != null && !f.isDirectory()) {
                host.edit(f);
            }
        });
        Dialogs.bindKey(this, "F2", () -> inResults.setSelected(!inResults.isSelected()));
        Dialogs.bindKey(this, "ctrl C", this::copyResults);
        Dialogs.bindKey(this, "ctrl INSERT", this::copyResults);
        Dialogs.bindKey(this, "ctrl TAB", () -> pages.setSelectedIndex((pages.getSelectedIndex() + 1) % pages.getTabCount()));
        Dialogs.bindKey(this, "ctrl shift TAB", () -> pages.setSelectedIndex((pages.getSelectedIndex() + pages.getTabCount() - 1)
            % pages.getTabCount()));
        Dialogs.bindKey(this, "alt LEFT", () -> showHistory(historyPos - 1));
        Dialogs.bindKey(this, "alt RIGHT", () -> showHistory(historyPos + 1));
    }

    private void chooseDir() {
        final String chosen = TreeDialog.choose(this, Path.of(searchIn.getText().split(";")[0].trim()));
        if (chosen != null) {
            searchIn.setText(chosen);
        }
    }

    private void toggleSearch() {
        if (worker != null) {
            cancelled = true;

            return;
        }
        final Searcher.Criteria criteria;
        final List<VFile> roots;
        try {
            criteria = buildCriteria();
            roots = buildRoots();
        } catch (final IOException | IllegalArgumentException e) {
            Dialogs.error(this, "Cannot start search:", e);

            return;
        }
        pushHistory();
        results.clear();
        cancelled = false;
        start.setText("Stop");
        flush.start();
        final Thread t = new Thread(() -> runSearch(criteria, roots), "search");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private void runSearch(final @NotNull Searcher.Criteria criteria, final @NotNull List<VFile> roots) {
        String error = null;
        try {
            new Searcher(criteria, pending::add, d -> currentDir = d, () -> cancelled).search(roots);
        } catch (final PatternSyntaxException e) {
            error = e.getMessage();
        }
        final String err = error;
        SwingUtilities.invokeLater(() -> searchFinished(err));
    }

    private void searchFinished(final @Nullable String error) {
        worker = null;
        flush.stop();
        flushResults();
        start.setText("Start search");
        final String summary = "[" + results.size() + " item(s) found]" + (cancelled ? " - search aborted" : "");
        status.setText(error != null ? "Error: " + error : summary);
        if (!results.isEmpty()) {
            resultList.setSelectedIndex(0);
            resultList.requestFocusInWindow();
        }
    }

    private void flushResults() {
        VFile f;
        while ((f = pending.poll()) != null) {
            results.addElement(f);
        }
        if (worker != null) {
            status.setText("[" + results.size() + " found]  " + currentDir);
        }
    }

    private @NotNull Searcher.Criteria buildCriteria() {
        final Searcher.Criteria c = new Searcher.Criteria();
        final Object name = searchFor.getEditor().getItem();
        c.setName(name == null || name.toString().isBlank() ? "*" : name.toString().trim());
        c.setNameRegex(nameRegex.isSelected());
        c.setDepth(DEPTH_VALUES[depth.getSelectedIndex()]);
        c.setSearchArchives(archives.isSelected());
        if (findText.isSelected() && !text.getText().isEmpty()) {
            c.setText(text.getText());
            c.setTextRegex(textRegex.isSelected());
            c.setCaseSensitive(caseSensitive.isSelected());
            c.setNotContaining(notContaining.isSelected());
        }
        c.setMinSize(minSizeOn.isSelected() ? bytes(minSize, minUnit) : -1);
        c.setMaxSize(maxSizeOn.isSelected() ? bytes(maxSize, maxUnit) : -1);
        final long day = 24L * 3600 * 1000;
        c.setNewerThan(newerOn.isSelected() ? System.currentTimeMillis() - ((Integer) newerDays.getValue()) * day : -1);
        c.setOlderThan(olderOn.isSelected() ? System.currentTimeMillis() - ((Integer) olderDays.getValue()) * day : -1);
        c.setFilesOnly(kind.getSelectedIndex() == 1);
        c.setDirsOnly(kind.getSelectedIndex() == 2);

        return c;
    }

    private static long bytes(final @NotNull JSpinner value, final @NotNull JComboBox<String> unit) {
        return ((Number) value.getValue()).longValue() << (10 * unit.getSelectedIndex());
    }

    private @NotNull List<VFile> buildRoots() throws IOException {
        final List<VFile> roots = new ArrayList<>();
        if (inResults.isSelected() && !results.isEmpty()) {
            for (int i = 0; i < results.size(); i++) {
                roots.add(results.get(i));
            }

            return roots;
        }
        for (final String part : searchIn.getText().split(";")) {
            if (!part.isBlank()) {
                roots.add(Vfs.resolve(part.trim().replaceFirst("^~", System.getProperty("user.home"))));
            }
        }

        return roots;
    }

    private void pushHistory() {
        if (results.isEmpty()) {
            return;
        }
        final List<VFile> snapshot = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            snapshot.add(results.get(i));
        }
        resultHistory.add(snapshot);
        historyPos = resultHistory.size();
    }

    /** Alt+←/→: previous/next result list. */
    private void showHistory(final int pos) {
        if (worker != null || pos < 0 || pos >= resultHistory.size()) {
            return;
        }
        if (historyPos >= resultHistory.size()) {
            pushHistory();
        }
        historyPos = pos;
        results.clear();
        for (final VFile f : resultHistory.get(pos)) {
            results.addElement(f);
        }
        status.setText("Result list " + (pos + 1) + " of " + resultHistory.size() + ": " + results.size() + " item(s)");
    }

    private void closeOrStop() {
        if (worker != null) {
            cancelled = true;

            return;
        }
        dispose();
    }

    private void newSearch() {
        cancelled = true;
        results.clear();
        status.setText(" ");
        pages.setSelectedIndex(0);
        searchFor.requestFocusInWindow();
    }

    private void goToSelected() {
        final VFile f = resultList.getSelectedValue();
        if (f != null) {
            cancelled = true;
            host.goToFile(f);
            dispose();
        }
    }

    private void viewSelected() {
        final int idx = resultList.getSelectedIndex();
        if (idx < 0) {
            return;
        }
        final List<VFile> files = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < results.size(); i++) {
            if (!results.get(i).isDirectory()) {
                if (i == idx) {
                    start = files.size();
                }
                files.add(results.get(i));
            }
        }
        if (!files.isEmpty()) {
            host.view(files, start);
        }
    }

    private void feed() {
        final List<VFile> files = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            files.add(results.get(i));
        }
        cancelled = true;
        host.feedToPanel(files);
        dispose();
    }

    private void copyResults() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append(results.get(i).getLocation()).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    @Override
    public void dispose() {
        cancelled = true;
        flush.stop();
        super.dispose();
    }
}
