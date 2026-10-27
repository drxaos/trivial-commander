package tcm.util;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

import org.jetbrains.annotations.NotNull;

/**
 * Binding keys to actions in a single line.
 */
public final class Keys {

    private Keys() {
    }

    public static void bind(final @NotNull JComponent c, final int condition, final @NotNull String key, final @NotNull Runnable action) {
        final KeyStroke ks = KeyStroke.getKeyStroke(key);
        if (ks == null) {
            throw new IllegalArgumentException("Bad key: " + key);
        }
        final String name = "tcm:" + key;
        c.getInputMap(condition).put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                action.run();
            }
        });
    }

    /** The key works while focus is anywhere inside the component. */
    public static void bindAncestor(final @NotNull JComponent c, final @NotNull String key, final @NotNull Runnable action) {
        bind(c, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, key, action);
    }

    public static void bindWindow(final @NotNull JComponent c, final @NotNull String key, final @NotNull Runnable action) {
        bind(c, JComponent.WHEN_IN_FOCUSED_WINDOW, key, action);
    }
}
