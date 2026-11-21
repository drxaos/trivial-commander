package tcm.apps;

import java.awt.Point;
import java.awt.event.MouseEvent;

import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.formdev.flatlaf.ui.FlatCaret;

/**
 * Caret that keeps the word (line) picked by a double (triple) click when the mouse shifts a little before the button is released.
 * FlatCaret extends the selection by words on any drag after a double click: the second press near the bottom of a line and a pixel
 * of movement down selected from the word to the next line. Here a drag within DEAD_ZONE pixels of the press is ignored.
 */
final class WordDragCaret extends FlatCaret {

    private static final int DEAD_ZONE = 15;

    /** Press of the double/triple click; null — single click, standard dragging. */
    private @Nullable Point press;

    private WordDragCaret() {
        super(null, false);
    }

    /**
     * Replaces the component's caret, keeping its blink rate and visibility. FlatCaret is a UIResource: a theme change installs
     * a plain one again, so it is replaced after every UI change.
     */
    static void installOn(final @NotNull JTextComponent c) {
        replaceCaret(c);
        c.addPropertyChangeListener("UI", e -> replaceCaret(c));
    }

    private static void replaceCaret(final @NotNull JTextComponent c) {
        if (c.getCaret() instanceof WordDragCaret) {
            return;
        }
        final WordDragCaret caret = new WordDragCaret();
        caret.setBlinkRate(c.getCaret().getBlinkRate());
        final boolean visible = c.getCaret().isVisible();
        c.setCaret(caret);
        caret.setVisible(visible);
    }

    @Override
    public void mousePressed(final MouseEvent e) {
        super.mousePressed(e);
        press = SwingUtilities.isLeftMouseButton(e) && e.getClickCount() >= 2 ? e.getPoint() : null;
    }

    @Override
    public void mouseReleased(final MouseEvent e) {
        press = null;
        super.mouseReleased(e);
    }

    @Override
    public void mouseDragged(final MouseEvent e) {
        final Point p = press;
        if (p != null && Math.abs(e.getX() - p.x) <= DEAD_ZONE && Math.abs(e.getY() - p.y) <= DEAD_ZONE) {
            return;
        }
        // once out of the dead zone the drag selects as usual (by words), even if the mouse comes back
        press = null;
        super.mouseDragged(e);
    }
}
