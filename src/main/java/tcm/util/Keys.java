package tcm.util;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Binding keys to actions in a single line.
 */
public final class Keys {

    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    /** Cmd+key the system keeps for itself: quit, hide, minimize, switch application / window, Spotlight. */
    private static final Set<Integer> MAC_RESERVED = Set.of(KeyEvent.VK_Q, KeyEvent.VK_H, KeyEvent.VK_M, KeyEvent.VK_TAB,
        KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_SPACE);

    private Keys() {
    }

    /**
     * On macOS the Cmd twin of a Ctrl shortcut, null — there is none (not macOS, no Ctrl in it, or the system owns that Cmd+key).
     * Ctrl shortcuts are those of Total Commander and stay, but macOS takes Ctrl+arrows and Ctrl+F1..F8 for Mission Control
     * and keyboard navigation before a program sees them, and Cmd+C/V/X is what the hands are used to there.
     */
    public static @Nullable KeyStroke macTwin(final @Nullable KeyStroke ks) {
        if (!MAC || ks == null) {
            return null;
        }
        final int held = ks.getModifiers();
        if ((held & InputEvent.CTRL_DOWN_MASK) == 0 || (held & InputEvent.META_DOWN_MASK) != 0 || MAC_RESERVED.contains(ks.getKeyCode())) {
            return null;
        }
        final int kept = held & (InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK);

        return KeyStroke.getKeyStroke(ks.getKeyCode(), kept | InputEvent.META_DOWN_MASK, ks.isOnKeyRelease());
    }

    public static void bind(final @NotNull JComponent c, final int condition, final @NotNull String key, final @NotNull Runnable action) {
        final KeyStroke ks = KeyStroke.getKeyStroke(key);
        if (ks == null) {
            throw new IllegalArgumentException("Bad key: " + key);
        }
        final String name = "tcm:" + key;
        c.getInputMap(condition).put(ks, name);
        final KeyStroke twin = macTwin(ks);
        if (twin != null) {
            c.getInputMap(condition).put(twin, name);
        }
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
