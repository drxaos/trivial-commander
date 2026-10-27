package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.AttribChange;
import tcm.core.AttribChange.Bit;
import tcm.core.VFile;

/**
 * Change attributes: permissions (or DOS attributes), modification time, processing directory contents.
 * An untouched checkbox does not change the attribute; a gray one means the selected files have different values.
 */
public final class AttribDialog extends JDialog {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final String[] WHO = {"Owner", "Group", "Others"};
    private static final String[] WHAT = {"Read", "Write", "Execute"};

    private final @NotNull List<VFile> files;
    private final Map<Bit, TriStateBox> boxes = new EnumMap<>(Bit.class);
    private final Map<Bit, Boolean> common = new EnumMap<>(Bit.class);
    private final JCheckBox changeDate = new JCheckBox("Change date/time:");
    private final JTextField date = new JTextField(16);
    private final JCheckBox recursive = new JCheckBox("Process subdirectory contents");
    private @Nullable AttribChange result;

    private AttribDialog(final @NotNull Window owner, final @NotNull List<VFile> files) {
        super(owner, "Change attributes", ModalityType.APPLICATION_MODAL);
        this.files = files;
        final JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        body.add(left(new JLabel(files.size() == 1 ? files.get(0).getName() : files.size() + " objects")));
        body.add(Box.createVerticalStrut(8));
        body.add(left(AttribChange.isPosixSystem() ? posixGrid() : dosList()));
        body.add(Box.createVerticalStrut(8));
        body.add(left(dateRow()));
        recursive.setEnabled(files.stream().anyMatch(VFile::isDirectory));
        recursive.addActionListener(e -> resetBoxes());
        body.add(left(recursive));
        getContentPane().add(body, BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(Dialogs.button("OK", this::accept));
        buttons.add(Dialogs.button("Cancel", this::dispose));
        getContentPane().add(buttons, BorderLayout.SOUTH);
        Dialogs.bindEscape(this);
        Dialogs.bindKey(this, "ENTER", this::accept);
        resetBoxes();
        pack();
        setLocationRelativeTo(owner);
    }

    /** null — cancelled or nothing changed. */
    public static @Nullable AttribChange show(final @NotNull Window owner, final @NotNull List<VFile> files) {
        final AttribDialog d = new AttribDialog(owner, files);
        d.setVisible(true);

        return d.result;
    }

    private @NotNull JComponent posixGrid() {
        final JPanel grid = new JPanel(new GridLayout(4, 4, 8, 2));
        grid.setBorder(BorderFactory.createTitledBorder("Permissions"));
        grid.add(new JLabel());
        for (final String w : WHAT) {
            grid.add(new JLabel(w));
        }
        final Bit[] bits = Bit.values();
        for (int who = 0; who < WHO.length; who++) {
            grid.add(new JLabel(WHO[who]));
            for (int what = 0; what < WHAT.length; what++) {
                grid.add(box(bits[who * WHAT.length + what], ""));
            }
        }

        return grid;
    }

    private @NotNull JComponent dosList() {
        final JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
        list.setBorder(BorderFactory.createTitledBorder("Attributes"));
        list.add(box(Bit.READ_ONLY, "Read only"));
        list.add(box(Bit.HIDDEN, "Hidden"));
        list.add(box(Bit.ARCHIVE, "Archive"));
        list.add(box(Bit.SYSTEM, "System"));

        return list;
    }

    private @NotNull TriStateBox box(final @NotNull Bit bit, final @NotNull String text) {
        final TriStateBox b = new TriStateBox(text);
        boxes.put(bit, b);
        common.put(bit, commonValue(bit));

        return b;
    }

    /** Bit value common to all selected files; null — the values differ or cannot be read. */
    private @Nullable Boolean commonValue(final @NotNull Bit bit) {
        Boolean value = null;
        for (final VFile f : files) {
            try {
                final boolean v = AttribChange.read(f.getHostPath(), bit);
                if (value != null && value != v) {
                    return null;
                }
                value = v;
            } catch (final IOException | UnsupportedOperationException | IllegalArgumentException e) {
                return null;
            }
        }

        return value;
    }

    private @NotNull JComponent dateRow() {
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        date.setText(DATE.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(files.get(0).getModified()), ZoneId.systemDefault())));
        date.setEnabled(false);
        changeDate.addActionListener(e -> date.setEnabled(changeDate.isSelected()));
        row.add(changeDate);
        row.add(date);
        row.add(Dialogs.button("Now", () -> {
            changeDate.setSelected(true);
            date.setEnabled(true);
            date.setText(DATE.format(LocalDateTime.now()));
        }));

        return row;
    }

    /** With content processing all checkboxes are gray: the selected files' values have nothing to do with nested ones. */
    private void resetBoxes() {
        for (final Map.Entry<Bit, TriStateBox> e : boxes.entrySet()) {
            e.getValue().reset(recursive.isSelected() ? null : common.get(e.getKey()));
        }
    }

    private void accept() {
        FileTime modified = null;
        if (changeDate.isSelected()) {
            try {
                modified = FileTime.from(LocalDateTime.parse(date.getText().trim(), DATE).atZone(ZoneId.systemDefault()).toInstant());
            } catch (final DateTimeParseException e) {
                Dialogs.error(this, "Date must look like " + DATE.format(LocalDateTime.now()), null);

                return;
            }
        }
        final Map<Bit, Boolean> bits = new EnumMap<>(Bit.class);
        for (final Map.Entry<Bit, TriStateBox> e : boxes.entrySet()) {
            final Boolean v = e.getValue().getChange();
            if (v != null) {
                bits.put(e.getKey(), v);
            }
        }
        final AttribChange change = new AttribChange(bits, modified, recursive.isSelected());
        result = change.isEmpty() ? null : change;
        dispose();
    }

    private static @NotNull JComponent left(final @NotNull JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);

        return c;
    }

    /** Checkbox with a third "don't change" state (gray, like indeterminate in FlatLaf). */
    private static final class TriStateBox extends JCheckBox {

        private @Nullable Boolean initial;
        private @Nullable Boolean value;

        private TriStateBox(final @NotNull String text) {
            super(text);
            // the JCheckBox model has already toggled itself; we keep our own state
            addActionListener(e -> {
                if (initial == null) {
                    value = value == null ? Boolean.TRUE : value ? Boolean.FALSE : null;
                } else {
                    value = !Boolean.TRUE.equals(value);
                }
                render();
            });
        }

        private void reset(final @Nullable Boolean shown) {
            initial = shown;
            value = shown;
            render();
        }

        /** New value; null — don't change. */
        private @Nullable Boolean getChange() {
            return Objects.equals(value, initial) ? null : value;
        }

        private void render() {
            setSelected(Boolean.TRUE.equals(value));
            putClientProperty("JButton.selectedState", value == null ? "indeterminate" : null);
            repaint();
        }
    }
}
