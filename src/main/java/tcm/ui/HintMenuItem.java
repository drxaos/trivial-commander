package tcm.ui;

import java.awt.event.KeyEvent;
import java.util.Set;

import javax.swing.JMenuItem;
import javax.swing.KeyStroke;

import org.jetbrains.annotations.NotNull;

/**
 * A menu item whose accelerator is only shown, never fired. Used for keys that an open menu needs itself (Enter activates the
 * highlighted item, Escape closes the menu, arrows and Space move and choose): a live accelerator on such a key takes it from
 * every menu that contains the item, so Enter in the file context menu always ran "Open" and Escape in the viewer's File menu
 * closed the viewer. The key's real handler is the panel or window binding, which works as before while no menu is open.
 */
public final class HintMenuItem extends JMenuItem {

    private static final Set<KeyStroke> MENU_KEYS = Set.of(
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
        KeyStroke.getKeyStroke("ctrl ENTER"),
        KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_END, 0));

    public HintMenuItem(final @NotNull String text, final @NotNull KeyStroke accelerator) {
        super(text);
        setAccelerator(accelerator);
    }

    /** True for keys an open menu uses for navigation: such an accelerator must be a hint only. */
    public static boolean isMenuKey(final @NotNull KeyStroke ks) {
        return MENU_KEYS.contains(pressed(ks));
    }

    /** The accelerator's own binding is skipped, so the key goes on to the open menu (or to whoever binds it in the window). */
    @Override
    protected boolean processKeyBinding(final KeyStroke ks, final KeyEvent e, final int condition, final boolean pressed) {
        final KeyStroke accelerator = getAccelerator();
        if (accelerator != null && ks != null && pressed(ks).equals(pressed(accelerator))) {
            return false;
        }

        return super.processKeyBinding(ks, e, condition, pressed);
    }

    private static @NotNull KeyStroke pressed(final @NotNull KeyStroke ks) {
        return KeyStroke.getKeyStroke(ks.getKeyCode(), ks.getModifiers());
    }
}
