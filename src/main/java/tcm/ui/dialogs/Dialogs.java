package tcm.ui.dialogs;

import java.awt.Component;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Common dialog bits: Esc, button hotkeys, error messages.
 */
public final class Dialogs {

    private Dialogs() {
    }

    public static void bindKey(final @NotNull JDialog dialog, final @NotNull String key, final @NotNull Runnable action) {
        final JRootPane root = dialog.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        root.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                action.run();
            }
        });
    }

    public static void bindEscape(final @NotNull JDialog dialog) {
        bindKey(dialog, "ESCAPE", dialog::dispose);
    }

    /** F5/F6 in dialogs: select the name without extension → the name with extension → the whole line. */
    public static void cycleSelection(final @NotNull JTextComponent field) {
        final String text = field.getText();
        final int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        final int nameStart = slash + 1;
        final int dot = text.lastIndexOf('.');
        final int baseEnd = dot > nameStart ? dot : text.length();
        final int selStart = field.getSelectionStart();
        final int selEnd = field.getSelectionEnd();
        if (selStart == nameStart && selEnd == baseEnd && baseEnd != text.length()) {
            field.select(nameStart, text.length());
        } else if (selStart == nameStart && selEnd == text.length() && nameStart > 0) {
            field.select(0, text.length());
        } else {
            field.select(nameStart, baseEnd);
        }
    }

    /** Ctrl+Home: cursor before the file name in the path. */
    public static void caretBeforeName(final @NotNull JTextComponent field) {
        final String text = field.getText();
        field.setCaretPosition(Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\')) + 1);
    }

    public static @NotNull JButton button(final @NotNull String text, final @NotNull Runnable action) {
        final JButton b = new JButton(text);
        b.addActionListener(e -> action.run());

        return b;
    }

    public static void error(final @Nullable Component parent, final @NotNull String message, final @Nullable Throwable e) {
        final String details = e == null ? "" : "\n" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        JOptionPane.showMessageDialog(parent, message + details, "Error", JOptionPane.ERROR_MESSAGE);
    }

    /** Delete confirmation; [0] — "permanently" chosen. null — cancelled. */
    public static boolean @Nullable [] confirmDelete(final @Nullable Component parent, final @NotNull String what, final boolean permanent,
        final boolean archive) {
        final JCheckBox box = new JCheckBox("Delete permanently (do not use trash)", permanent || archive);
        box.setEnabled(!archive);
        final Object[] message = archive
            ? new Object[] {"Delete " + what + " from archive?", "Entries are removed from the archive permanently."}
            : new Object[] {"Delete " + what + "?", box};
        final int answer = JOptionPane.showConfirmDialog(parent, message, "Delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }

        return new boolean[] {box.isSelected()};
    }
}
