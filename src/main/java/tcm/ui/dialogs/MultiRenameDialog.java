package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

import org.jetbrains.annotations.NotNull;

import tcm.core.ArchiveIndex;
import tcm.core.Progress;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.core.ZipEdit;
import tcm.core.ZipOps;
import tcm.util.Keys;

/**
 * Multi-rename (Ctrl+M): name/extension mask with [N] [E] [C] [P] [YMD] [hms], search and replace, case.
 * Shift+↑/↓ — move a file in the list, Del — remove from the list, Ctrl+F3..F6 — sorting, Enter — execute.
 */
public final class MultiRenameDialog extends JDialog {

    private final List<VFile> files;
    private final JTextField nameMask = new JTextField("[N]", 16);
    private final JTextField extMask = new JTextField("[E]", 8);
    private final JTextField search = new JTextField(12);
    private final JTextField replace = new JTextField(12);
    private final JCheckBox regex = new JCheckBox("RegEx");
    private final JComboBox<String> caseMode = new JComboBox<>(new String[] {"Unchanged", "lowercase", "UPPERCASE", "First letter upper"});
    private final JSpinner counterStart = new JSpinner(new SpinnerNumberModel(1, 0, 1_000_000, 1));
    private final JSpinner counterStep = new JSpinner(new SpinnerNumberModel(1, 1, 1000, 1));
    private final JSpinner counterDigits = new JSpinner(new SpinnerNumberModel(1, 1, 10, 1));
    private final JLabel status = new JLabel(" ");
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final List<String> newNames = new ArrayList<>();
    private boolean done;
    private int sortColumn = -1;
    private boolean sortAsc = true;

    private MultiRenameDialog(final @NotNull Window owner, final @NotNull List<VFile> files) {
        super(owner, "Multi-Rename Tool", ModalityType.APPLICATION_MODAL);
        this.files = new ArrayList<>(files);
        final JPanel masks = new JPanel(new GridLayout(0, 1, 0, 4));
        masks.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        masks.add(row(new JLabel("Rename mask:"), nameMask, new JLabel("Extension:"), extMask,
            new JLabel("  [N] name  [E] ext  [C] counter  [P] parent  [YMD] [hms] date/time")));
        masks.add(row(new JLabel("Search for:"), search, new JLabel("Replace with:"), replace, regex, new JLabel("Case:"), caseMode));
        masks.add(row(new JLabel("Counter start:"), counterStart, new JLabel("step:"), counterStep, new JLabel("digits:"), counterDigits));
        getContentPane().add(masks, BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        final JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.WEST);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(Dialogs.button("Start!", this::execute));
        buttons.add(Dialogs.button("Close", this::dispose));
        bottom.add(buttons, BorderLayout.EAST);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        getContentPane().add(bottom, BorderLayout.SOUTH);
        installListeners();
        installKeys();
        recompute();
        setSize(900, 560);
        setLocationRelativeTo(owner);
    }

    /** true — something was renamed (the panel needs refreshing). */
    public static boolean show(final @NotNull Window owner, final @NotNull List<VFile> files) {
        final MultiRenameDialog d = new MultiRenameDialog(owner, files);
        d.setVisible(true);

        return d.done;
    }

    private static @NotNull JPanel row(final @NotNull JComponent... items) {
        final JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (final JComponent c : items) {
            r.add(c);
        }

        return r;
    }

    private void installListeners() {
        final DocumentListener dl = new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                recompute();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                recompute();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                recompute();
            }
        };
        for (final JTextField f : new JTextField[] {nameMask, extMask, search, replace}) {
            f.getDocument().addDocumentListener(dl);
        }
        regex.addActionListener(e -> recompute());
        caseMode.addActionListener(e -> recompute());
        for (final JSpinner s : new JSpinner[] {counterStart, counterStep, counterDigits}) {
            s.addChangeListener(e -> recompute());
        }
    }

    private void installKeys() {
        Dialogs.bindEscape(this);
        Dialogs.bindKey(this, "ENTER", this::execute);
        Dialogs.bindKey(this, "ctrl F3", () -> sortBy(0));
        Dialogs.bindKey(this, "ctrl F4", () -> sortBy(1));
        Dialogs.bindKey(this, "ctrl F5", () -> sortBy(2));
        Dialogs.bindKey(this, "ctrl F6", () -> sortBy(3));
        Keys.bind(table, JComponent.WHEN_FOCUSED, "shift UP", () -> moveSelected(-1));
        Keys.bind(table, JComponent.WHEN_FOCUSED, "shift DOWN", () -> moveSelected(1));
        Keys.bind(table, JComponent.WHEN_FOCUSED, "DELETE", this::removeSelected);
    }

    private void sortBy(final int column) {
        sortAsc = column != sortColumn || !sortAsc;
        sortColumn = column;
        Comparator<VFile> cmp;
        switch (column) {
            case 1:
                cmp = Comparator.comparing(VFile::getExtension, String.CASE_INSENSITIVE_ORDER);
                break;
            case 2:
                cmp = Comparator.comparingLong(VFile::getModified);
                break;
            case 3:
                cmp = Comparator.comparingLong(VFile::getSize);
                break;
            default:
                cmp = Comparator.comparing(VFile::getName, String.CASE_INSENSITIVE_ORDER);
                break;
        }
        files.sort(sortAsc ? cmp : cmp.reversed());
        recompute();
    }

    private void moveSelected(final int delta) {
        final int row = table.getSelectedRow();
        final int target = row + delta;
        if (row < 0 || target < 0 || target >= files.size()) {
            return;
        }
        files.add(target, files.remove(row));
        recompute();
        table.setRowSelectionInterval(target, target);
    }

    private void removeSelected() {
        final int row = table.getSelectedRow();
        if (row >= 0) {
            files.remove(row);
            recompute();
            if (!files.isEmpty()) {
                final int r = Math.min(row, files.size() - 1);
                table.setRowSelectionInterval(r, r);
            }
        }
    }

    private void recompute() {
        newNames.clear();
        String error = null;
        int counter = (Integer) counterStart.getValue();
        for (final VFile f : files) {
            try {
                newNames.add(newName(f, counter));
            } catch (final PatternSyntaxException e) {
                newNames.add(f.getName());
                error = e.getDescription();
            }
            counter += (Integer) counterStep.getValue();
        }
        model.fireTableDataChanged();
        status.setText(error != null ? "RegEx error: " + error : files.size() + " file(s)");
    }

    private @NotNull String newName(final @NotNull VFile f, final int counter) {
        final String base = f.isDirectory() ? f.getName() : f.getBaseName();
        final String ext = f.isDirectory() ? "" : f.getExtension();
        String name = expand(nameMask.getText(), f, base, ext, counter);
        final String newExt = expand(extMask.getText(), f, base, ext, counter);
        if (!newExt.isEmpty()) {
            name = name + "." + newExt;
        }
        if (!search.getText().isEmpty()) {
            name = regex.isSelected()
                ? Pattern.compile(search.getText()).matcher(name).replaceAll(replace.getText())
                : name.replace(search.getText(), replace.getText());
        }
        switch (caseMode.getSelectedIndex()) {
            case 1:
                return name.toLowerCase(Locale.ROOT);
            case 2:
                return name.toUpperCase(Locale.ROOT);
            case 3:
                return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1).toLowerCase(Locale.ROOT);
            default:
                return name;
        }
    }

    private @NotNull String expand(final @NotNull String mask, final @NotNull VFile f, final @NotNull String base, final @NotNull String ext,
        final int counter) {
        final String digits = "%0" + counterDigits.getValue() + "d";
        final Path parent = f.isLocal() ? f.getHostPath().getParent() : null;
        final String parentName = f.isLocal()
            ? (parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString())
            : ArchiveIndex.parentOf(f.getEntry()).replaceAll(".*/", "");
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("[N]", base);
        values.put("[E]", ext);
        values.put("[C]", String.format(digits, counter));
        values.put("[P]", parentName);
        values.put("[YMD]", new SimpleDateFormat("yyyyMMdd").format(new Date(f.getModified())));
        values.put("[hms]", new SimpleDateFormat("HHmmss").format(new Date(f.getModified())));
        final Matcher m = Pattern.compile("\\[(N|E|C|P|YMD|hms)]").matcher(mask);
        final StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(values.get(m.group())));
        }
        m.appendTail(sb);

        return sb.toString();
    }

    private void execute() {
        for (final String n : newNames) {
            if (n.isBlank() || n.contains("/") || n.contains("\\")) {
                Dialogs.error(this, "Invalid target name: \"" + n + "\"", null);

                return;
            }
        }
        try {
            renameAll();
        } catch (final IOException e) {
            Dialogs.error(this, "Rename failed:", e);
        }
        done = true;
        dispose();
    }

    private void renameAll() throws IOException {
        final Map<Path, ZipEdit> archiveEdits = new LinkedHashMap<>();
        final List<Path[]> second = new ArrayList<>();
        final Set<Path> sources = new HashSet<>();
        for (final VFile f : files) {
            if (f.isLocal()) {
                sources.add(f.getHostPath());
            }
        }
        final Set<Path> targets = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            final VFile f = files.get(i);
            final Path target = f.isLocal() ? f.getHostPath().resolveSibling(newNames.get(i)) : null;
            if (target == null) {
                continue;
            }
            if (!targets.add(target)) {
                throw new IOException("Several files would get the same name: " + target.getFileName());
            }
            // case-only change on a case-insensitive FS: the target "exists", but it is the file itself
            if (!target.equals(f.getHostPath()) && !Vfs.isCaseOnlyRename(f.getHostPath(), target) && Files.exists(target)
                && !sources.contains(target)) {
                throw new IOException("Target already exists: " + target);
            }
        }
        for (int i = 0; i < files.size(); i++) {
            final VFile f = files.get(i);
            final String target = newNames.get(i);
            if (target.equals(f.getName())) {
                continue;
            }
            if (f.isInArchive()) {
                archiveEdits.computeIfAbsent(f.getHostPath(), k -> new ZipEdit())
                    .rename(f.getEntry(), ArchiveIndex.join(ArchiveIndex.parentOf(f.getEntry()), target));
                continue;
            }
            // via a temporary name: chains like a→b, b→a must not overwrite files
            final Path tmp = f.getHostPath().resolveSibling(".tcm-rename-" + i + "-" + System.nanoTime());
            Files.move(f.getHostPath(), tmp);
            second.add(new Path[] {tmp, f.getHostPath().resolveSibling(target)});
        }
        for (final Path[] p : second) {
            Files.move(p[0], p[1]);
        }
        for (final Map.Entry<Path, ZipEdit> e : archiveEdits.entrySet()) {
            ZipOps.apply(e.getKey(), e.getValue(), Progress.NONE);
        }
    }

    private final class Model extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return files.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(final int column) {
            return column == 0 ? "Old name" : "New name";
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            return column == 0 ? files.get(row).getName() : row < newNames.size() ? newNames.get(row) : "";
        }
    }
}
