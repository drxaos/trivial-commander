package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.Passwords;

/**
 * Password of an encrypted archive: asked when the archive is opened, a file is viewed or unpacked from it. Works from any thread:
 * a background operation waits for the answer on the event thread.
 */
public final class PasswordDialog {

    private PasswordDialog() {
    }

    /** Installs the dialog as the password prompt of the archive layer; owner — the window used when no window is active. */
    public static void install(final @NotNull Window fallbackOwner) {
        Passwords.setPrompt((name, retry) -> {
            if (SwingUtilities.isEventDispatchThread()) {
                return ask(owner(fallbackOwner), name, retry);
            }
            final AtomicReference<String> result = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> result.set(ask(owner(fallbackOwner), name, retry)));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();

                return null;
            } catch (final InvocationTargetException e) {
                return null;
            }

            return result.get();
        });
    }

    private static @NotNull Window owner(final @NotNull Window fallback) {
        final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();

        return active != null ? active : fallback;
    }

    /** null — cancelled. */
    public static @Nullable String ask(final @NotNull Window owner, final @NotNull String archiveName, final boolean retry) {
        final JPasswordField field = new JPasswordField(28);
        final JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        final String text = retry ? "Wrong password for " + archiveName + ". Try again:" : archiveName + " is encrypted. Password:";
        panel.add(new JLabel(text), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        // the option pane puts the focus on its initial value (the OK button) whenever the dialog gets focus: the field takes it instead
        final JOptionPane pane = new JOptionPane(panel, retry ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE,
            JOptionPane.OK_CANCEL_OPTION) {
            @Override
            public void selectInitialValue() {
                // keeps OK the default button (Enter in the field)
                super.selectInitialValue();
                field.requestFocusInWindow();
            }
        };
        final JDialog dialog = pane.createDialog(owner, "Password");
        dialog.setVisible(true);
        dialog.dispose();
        final char[] password = field.getPassword();
        try {
            return Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue()) ? new String(password) : null;
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
