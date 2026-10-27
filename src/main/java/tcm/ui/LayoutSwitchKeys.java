package tcm.ui;

import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import org.jetbrains.annotations.NotNull;

/**
 * Restores modifiers eaten by keyboard layout switching (XKB grp:alt_shift_toggle, grp:ctrl_shift_toggle).
 * <p>
 * With Alt held, the Shift key produces ISO_Next_Group instead of Shift_L (and vice versa), X does not set its modifier in the state,
 * and Alt+Shift+Enter arrives as Alt+Enter or Shift+Enter. The Java key code is taken from the base level, so the press
 * of Shift/Alt itself is still visible: the missing modifiers are restored from those.
 */
public final class LayoutSwitchKeys implements KeyEventDispatcher {

    private boolean shift;
    private boolean ctrl;
    private boolean alt;

    public static void install() {
        final KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        final LayoutSwitchKeys keys = new LayoutSwitchKeys();
        kfm.addKeyEventDispatcher(keys);
        // a key release in another window will not be seen — without a reset the modifier would get "stuck"
        kfm.addPropertyChangeListener("activeWindow", e -> keys.reset());
    }

    @Override
    public boolean dispatchKeyEvent(final @NotNull KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_TYPED) {
            return false;
        }
        final boolean down = e.getID() == KeyEvent.KEY_PRESSED;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_SHIFT:
                shift = down;

                return false;
            case KeyEvent.VK_CONTROL:
                ctrl = down;

                return false;
            case KeyEvent.VK_ALT:
                alt = down;

                return false;
            default:
                break;
        }
        final int held = (shift ? InputEvent.SHIFT_DOWN_MASK : 0) | (ctrl ? InputEvent.CTRL_DOWN_MASK : 0) | (alt ? InputEvent.ALT_DOWN_MASK : 0);
        final int missing = held & ~e.getModifiersEx();
        // a single modifier does not switch the layout: so this is not XKB but a missed release
        if (missing == 0 || Integer.bitCount(held) < 2) {
            return false;
        }
        final Component target = e.getComponent();
        if (target == null) {
            return false;
        }
        final KeyEvent fixed = new KeyEvent(target, e.getID(), e.getWhen(), e.getModifiersEx() | missing, e.getKeyCode(), e.getKeyChar(),
            e.getKeyLocation());
        e.consume();
        target.dispatchEvent(fixed);

        return true;
    }

    private void reset() {
        shift = false;
        ctrl = false;
        alt = false;
    }
}
