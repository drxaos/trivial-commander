package tcm.apps;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import javax.swing.WindowConstants;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;

/**
 * Tool windows (viewer, editor, compare, console). From a modal dialog (e.g. an overwrite question)
 * a modeless child JDialog is opened — otherwise modality would block the window.
 */
public final class AppWindows {

    /** Help (F1, Help menu) lives in ui, which apps does not depend on — the main window supplies it. */
    private static @Nullable Consumer<Window> helpHandler;

    private AppWindows() {
    }

    public static void setHelpHandler(final @NotNull Consumer<Window> handler) {
        helpHandler = handler;
    }

    static void showHelp(final @NotNull Window w) {
        final Consumer<Window> handler = helpHandler;
        if (handler != null) {
            handler.accept(w);
        }
    }

    /** Menu item; the window handles the key itself, here it is only labeled. */
    static @NotNull JMenuItem item(final @NotNull String text, final @Nullable String accel, final @NotNull Runnable action) {
        return setup(new JMenuItem(text), accel, action);
    }

    /** Checkbox item; the menu owner sets the check mark each time the menu opens. */
    static @NotNull JCheckBoxMenuItem check(final @NotNull String text, final @Nullable String accel, final @NotNull Runnable action) {
        return setup(new JCheckBoxMenuItem(text), accel, action);
    }

    private static <T extends JMenuItem> @NotNull T setup(final @NotNull T item, final @Nullable String accel, final @NotNull Runnable action) {
        if (accel != null) {
            item.setAccelerator(KeyStroke.getKeyStroke(accel));
        }
        item.addActionListener(e -> action.run());

        return item;
    }

    /** "Save as": initial name — the file name, directory — its directory; null — cancelled. */
    static @Nullable Path askSaveAs(final @NotNull Component parent, final @NotNull VFile file) {
        final Path dir = file.isLocal() ? file.getHostPath().getParent() : null;
        final JFileChooser chooser = new JFileChooser(dir != null ? dir.toFile() : null);
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), file.getName()));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        final Path target = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (Files.exists(target) && JOptionPane.showConfirmDialog(parent, "Overwrite " + target + "?", "Save as",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return null;
        }

        return target;
    }

    public static @NotNull Window create(final @Nullable Window owner, final @NotNull String title) {
        final Window w;
        if (owner instanceof Dialog && ((Dialog) owner).isModal()) {
            final JDialog d = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
            d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            w = d;
        } else {
            final JFrame f = new JFrame(title);
            f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            w = f;
        }
        if (owner != null) {
            w.setIconImages(owner.getIconImages());
        }
        w.setSize(900, 650);
        w.setLocationRelativeTo(owner);

        return w;
    }

    /** The window was closed in any way (Esc, title bar close button, dispose): e.g. to cancel background loading. */
    public static void onClosed(final @NotNull Window w, final @NotNull Runnable action) {
        w.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(final WindowEvent e) {
                action.run();
            }
        });
    }

    public static @NotNull RootPaneContainer container(final @NotNull Window w) {
        return (RootPaneContainer) w;
    }

    public static void setTitle(final @NotNull Window w, final @NotNull String title) {
        if (w instanceof Frame) {
            ((Frame) w).setTitle(title);
        } else if (w instanceof Dialog) {
            ((Dialog) w).setTitle(title);
        }
    }

    /** F11: maximize/restore. */
    public static void toggleMaximized(final @NotNull Window w) {
        if (w instanceof Frame) {
            final Frame f = (Frame) w;
            f.setExtendedState(f.getExtendedState() == Frame.MAXIMIZED_BOTH ? Frame.NORMAL : Frame.MAXIMIZED_BOTH);
        }
    }

    /** Alt+Enter: full screen mode. */
    public static void toggleFullScreen(final @NotNull Window w) {
        final GraphicsDevice device = w.getGraphicsConfiguration().getDevice();
        if (!device.isFullScreenSupported()) {
            toggleMaximized(w);

            return;
        }
        device.setFullScreenWindow(device.getFullScreenWindow() == w ? null : w);
    }
}
