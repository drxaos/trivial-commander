package tcm.util;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.util.function.IntConsumer;

import javax.swing.JScrollPane;

import org.jetbrains.annotations.NotNull;

/**
 * Ctrl+wheel over a scroll pane changes the font size instead of scrolling (Cmd+wheel too on macOS).
 */
public final class WheelZoom {

    private WheelZoom() {
    }

    /**
     * Scroll pane that hands Ctrl+wheel to onZoom: +1 (wheel up — larger) or -1. The event is taken before the listeners, so
     * the look and feel's scrolling does not see it; a listener of its own would survive a theme change but could not stop scrolling.
     */
    public static @NotNull JScrollPane scrollPane(final @NotNull Component view, final @NotNull IntConsumer onZoom) {
        return new JScrollPane(view) {
            @Override
            protected void processMouseWheelEvent(final MouseWheelEvent e) {
                if ((e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) == 0) {
                    super.processMouseWheelEvent(e);

                    return;
                }
                // a touchpad sends many events with fractional rotation and wheelRotation 0: only whole steps count
                final int steps = e.getWheelRotation();
                if (steps != 0) {
                    onZoom.accept(steps < 0 ? 1 : -1);
                }
                e.consume();
            }
        };
    }
}
