package tcm.util;

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;

import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where dialogs appear: in the middle of the window they belong to.
 * <p>
 * {@link Window#setLocationRelativeTo} centers over the component it is given, and a component may be much bigger than what is seen
 * of it (the text of a long file in a scroll pane): the middle of it is far below the screen, and the dialog lands at the bottom
 * edge of the screen, beside the window. So dialogs are centered over the window, never over a component in it.
 */
public final class Placement {

    private Placement() {
    }

    /** The window a component is in (a window is its own); the parent to give a JOptionPane. */
    public static @Nullable Window windowOf(final @Nullable Component c) {
        return c == null || c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
    }

    /**
     * Puts the already sized window in the middle of the window of {@code in}, moved as little as needed to stay whole on the screen
     * that window is on; no window on screen — the middle of the default screen.
     */
    public static void center(final @NotNull Window window, final @Nullable Component in) {
        final Window owner = windowOf(in);
        if (owner == null || !owner.isShowing()) {
            window.setLocationRelativeTo(owner);

            return;
        }
        final Rectangle over = owner.getBounds();
        final Rectangle screen = screenOf(owner);
        final int x = over.x + (over.width - window.getWidth()) / 2;
        final int y = over.y + (over.height - window.getHeight()) / 2;
        // a window bigger than the screen keeps its title bar on it
        window.setLocation(
            Math.max(screen.x, Math.min(x, screen.x + screen.width - window.getWidth())),
            Math.max(screen.y, Math.min(y, screen.y + screen.height - window.getHeight())));
    }

    /**
     * The usable area (without panels, the Dock, the menu bar) of the screen the middle of the window is on. The screens are asked
     * directly: the configuration a window reports may be that of the screen it was created on, not of the one it was moved to.
     */
    private static @NotNull Rectangle screenOf(final @NotNull Window w) {
        final Rectangle b = w.getBounds();
        final Point middle = new Point(b.x + b.width / 2, b.y + b.height / 2);
        GraphicsConfiguration found = w.getGraphicsConfiguration();
        for (final GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            final GraphicsConfiguration gc = device.getDefaultConfiguration();
            if (gc.getBounds().contains(middle)) {
                found = gc;
                break;
            }
        }
        final Rectangle r = new Rectangle(found.getBounds());
        final Insets in = Toolkit.getDefaultToolkit().getScreenInsets(found);
        r.x += in.left;
        r.y += in.top;
        r.width -= in.left + in.right;
        r.height -= in.top + in.bottom;

        return r;
    }
}
