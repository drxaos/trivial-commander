package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Packing: archive name, ZIP/JAR format, recursion, moving into the archive.
 */
public final class PackDialog extends JDialog {

    private final JTextField archive = new JTextField(48);
    private final JRadioButton zip = new JRadioButton("ZIP", true);
    private final JRadioButton jar = new JRadioButton("JAR");
    private final JCheckBox recursive = new JCheckBox("Recursively pack subdirectories", true);
    private final JCheckBox move = new JCheckBox("Move to archive");
    private @Nullable Result result;

    @Getter
    public static final class Result {

        private final @NotNull String archive;
        private final boolean recursive;
        private final boolean move;
        private final boolean queued;

        public Result(final @NotNull String archive, final boolean recursive, final boolean move, final boolean queued) {
            this.archive = archive;
            this.recursive = recursive;
            this.move = move;
            this.queued = queued;
        }
    }

    private PackDialog(final @NotNull Window owner, final @NotNull String prompt, final @NotNull String path) {
        super(owner, "Pack files", ModalityType.APPLICATION_MODAL);
        final JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        final GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        content.add(new JLabel(prompt), c);
        archive.setText(path);
        content.add(archive, c);
        final JPanel packer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        packer.add(new JLabel("Packer:"));
        final ButtonGroup group = new ButtonGroup();
        group.add(zip);
        group.add(jar);
        packer.add(zip);
        packer.add(jar);
        zip.addActionListener(e -> switchExtension("zip"));
        jar.addActionListener(e -> switchExtension("jar"));
        content.add(packer, c);
        content.add(recursive, c);
        content.add(move, c);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton ok = Dialogs.button("OK", () -> close(false));
        buttons.add(ok);
        buttons.add(Dialogs.button("F2 Queue", () -> close(true)));
        buttons.add(Dialogs.button("Cancel", this::dispose));
        getContentPane().add(content, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        Dialogs.bindEscape(this);
        Dialogs.bindKey(this, "F2", () -> close(true));
        Dialogs.bindKey(this, "alt F5", () -> Dialogs.cycleSelection(archive));
        Dialogs.bindKey(this, "F5", () -> Dialogs.cycleSelection(archive));
        Dialogs.bindKey(this, "F10", this::tree);
        pack();
        setLocationRelativeTo(owner);
        archive.requestFocusInWindow();
        selectBaseName();
    }

    public static @Nullable Result show(final @NotNull Window owner, final @NotNull String prompt, final @NotNull String path) {
        final PackDialog dialog = new PackDialog(owner, prompt, path);
        dialog.setVisible(true);

        return dialog.result;
    }

    private void tree() {
        final String text = archive.getText().trim();
        final Path current = Path.of(text.isEmpty() ? System.getProperty("user.home") : text);
        final Path parent = current.getParent();
        final String chosen = TreeDialog.choose(this, parent == null ? current : parent);
        if (chosen != null) {
            archive.setText(Path.of(chosen).resolve(String.valueOf(current.getFileName())).toString());
            selectBaseName();
        }
    }

    private void switchExtension(final @NotNull String ext) {
        final String text = archive.getText();
        final int dot = text.lastIndexOf('.');
        final int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        archive.setText((dot > slash ? text.substring(0, dot) : text) + "." + ext);
        selectBaseName();
    }

    private void selectBaseName() {
        final String text = archive.getText();
        final int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        final int dot = text.lastIndexOf('.');
        archive.select(slash + 1, dot > slash ? dot : text.length());
    }

    private void close(final boolean queued) {
        final String path = archive.getText().trim();
        if (path.isEmpty()) {
            return;
        }
        result = new Result(path, recursive.isSelected(), move.isSelected(), queued);
        dispose();
    }
}
