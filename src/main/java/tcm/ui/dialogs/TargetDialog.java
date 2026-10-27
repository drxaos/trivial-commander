package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Copy/move/unpack dialog: target path, "only files of type" mask, OK / F2 Queue / F10 Tree / Cancel.
 * F5/F6 — toggle selection in the path, Ctrl+D — favorite directories, F8 — mask templates, Ctrl+Home — cursor before the name.
 */
public final class TargetDialog extends JDialog {

    private static final String[] MASKS = {"*", "*.txt", "*.java", "*.jpg;*.png;*.gif", "*.zip;*.jar", "*.html;*.htm;*.css;*.js"};

    private final JTextField target = new JTextField(52);
    private final JComboBox<String> mask = new JComboBox<>(MASKS);
    private final @NotNull List<String> hotlist;
    private @Nullable Result result;

    @Getter
    public static final class Result {

        private final @NotNull String target;
        private final @NotNull String mask;
        private final boolean queued;

        public Result(final @NotNull String target, final @NotNull String mask, final boolean queued) {
            this.target = target;
            this.mask = mask;
            this.queued = queued;
        }
    }

    private TargetDialog(
        final @NotNull Window owner,
        final @NotNull String title,
        final @NotNull String prompt,
        final @NotNull String path,
        final @NotNull List<String> hotlist
    ) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.hotlist = hotlist;
        final JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        final GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        content.add(new JLabel(prompt), c);
        target.setText(path);
        target.selectAll();
        content.add(target, c);
        c.insets = new Insets(8, 0, 2, 0);
        content.add(new JLabel("Only files of this type:"), c);
        c.insets = new Insets(2, 0, 2, 0);
        mask.setEditable(true);
        content.add(mask, c);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton ok = Dialogs.button("OK", () -> close(false));
        buttons.add(ok);
        buttons.add(Dialogs.button("F2 Queue", () -> close(true)));
        buttons.add(Dialogs.button("Tree F10", this::tree));
        buttons.add(Dialogs.button("Cancel", this::dispose));
        getContentPane().add(content, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        installKeys();
        pack();
        setLocationRelativeTo(owner);
    }

    public static @Nullable Result show(
        final @NotNull Window owner,
        final @NotNull String title,
        final @NotNull String prompt,
        final @NotNull String path,
        final @NotNull List<String> hotlist
    ) {
        final TargetDialog dialog = new TargetDialog(owner, title, prompt, path, hotlist);
        dialog.setVisible(true);

        return dialog.result;
    }

    private void installKeys() {
        Dialogs.bindEscape(this);
        Dialogs.bindKey(this, "F2", () -> close(true));
        for (final String k : new String[] {"F5", "F6", "shift F5", "shift F6"}) {
            Dialogs.bindKey(this, k, () -> Dialogs.cycleSelection(target));
        }
        Dialogs.bindKey(this, "F10", this::tree);
        Dialogs.bindKey(this, "ctrl D", this::showHotlist);
        Dialogs.bindKey(this, "ctrl HOME", () -> Dialogs.caretBeforeName(target));
        Dialogs.bindKey(this, "F8", () -> {
            mask.requestFocusInWindow();
            mask.showPopup();
        });
    }

    private void tree() {
        final String t = target.getText().trim();
        final String chosen = TreeDialog.choose(this, Path.of(t.isEmpty() ? System.getProperty("user.home") : t.split("!/")[0]));
        if (chosen != null) {
            target.setText(chosen.endsWith("/") ? chosen : chosen + "/");
        }
        target.requestFocusInWindow();
    }

    private void showHotlist() {
        final JPopupMenu menu = new JPopupMenu();
        for (final String dir : hotlist) {
            final JMenuItem item = new JMenuItem(dir);
            item.addActionListener(e -> target.setText(dir.endsWith("/") ? dir : dir + "/"));
            menu.add(item);
        }
        if (hotlist.isEmpty()) {
            menu.add(new JMenuItem("(hotlist is empty — add directories with Ctrl+D in a panel)")).setEnabled(false);
        }
        menu.show(target, 0, target.getHeight());
    }

    private void close(final boolean queued) {
        final String t = target.getText().trim();
        if (t.isEmpty()) {
            return;
        }
        final Object m = mask.getEditor().getItem();
        result = new Result(t, m == null || m.toString().isBlank() ? "*" : m.toString().trim(), queued);
        dispose();
    }
}
