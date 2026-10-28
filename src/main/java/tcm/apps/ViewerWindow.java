package tcm.apps;

import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JComponent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.util.Keys;

/**
 * Viewer window (F3): one or more files, N/P and Alt+←/→ — neighboring files, F4 — to the editor.
 * In an image ←/→ flip through images of the same directory.
 */
public final class ViewerWindow {

    private final @NotNull Window window;
    private final @NotNull ViewerPanel viewer = new ViewerPanel();
    private @NotNull List<VFile> files;
    private final @Nullable Consumer<VFile> onEdit;
    private int index;

    private ViewerWindow(final @Nullable Window owner, final @NotNull List<VFile> files, final int index,
        final @Nullable Consumer<VFile> onEdit) {
        this.files = List.copyOf(files);
        this.index = index;
        this.onEdit = onEdit;
        window = AppWindows.create(owner, "Viewer");
        AppWindows.container(window).getContentPane().add(viewer);
        AppWindows.container(window).getRootPane().setJMenuBar(viewer.createMenuBar(this::close));
        viewer.setImageNavigator(this::stepImage);
        // a closed window must not keep reading and converting the file
        AppWindows.onClosed(window, viewer::cancel);
        final JComponent root = AppWindows.container(window).getRootPane();
        Keys.bindWindow(root, "ESCAPE", this::close);
        Keys.bindWindow(root, "F10", this::close);
        Keys.bindWindow(root, "Q", this::close);
        Keys.bindWindow(root, "F1", () -> AppWindows.showHelp(window));
        Keys.bindWindow(root, "F11", () -> AppWindows.toggleMaximized(window));
        Keys.bindWindow(root, "alt ENTER", () -> AppWindows.toggleFullScreen(window));
        Keys.bindWindow(root, "N", () -> step(1));
        Keys.bindWindow(root, "P", () -> step(-1));
        Keys.bindWindow(root, "alt RIGHT", () -> step(1));
        Keys.bindWindow(root, "alt LEFT", () -> step(-1));
        Keys.bindWindow(root, "F4", this::edit);
        window.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                viewer.refit();
            }
        });
        show();
        window.setVisible(true);
        viewer.getFocusTarget().requestFocusInWindow();
    }

    public static void open(final @Nullable Window owner, final @NotNull List<VFile> files, final int index,
        final @Nullable Consumer<VFile> onEdit) {
        if (!files.isEmpty()) {
            new ViewerWindow(owner, files, Math.max(0, Math.min(index, files.size() - 1)), onEdit);
        }
    }

    private void show() {
        final VFile f = files.get(index);
        AppWindows.setFileIcon(window, f.isImage());
        AppWindows.setTitle(window, "Viewer - [" + f.getLocation() + "]" + (files.size() > 1 ? "  (" + (index + 1) + "/" + files.size() + ")" : ""));
        viewer.load(f);
    }

    private void step(final int delta) {
        if (files.size() == 1) {
            viewer.reload();

            return;
        }
        index = Math.floorMod(index + delta, files.size());
        show();
    }

    /** ←/→ in an image: the neighboring image of the directory, the list of browsed files is replaced with the directory's images. */
    private void stepImage(final int delta) {
        final VFile current = files.get(index);
        final List<VFile> images = imagesNear(current);
        final int next = images.indexOf(current) + delta;
        if (next < 0 || next >= images.size()) {
            Toolkit.getDefaultToolkit().beep();

            return;
        }
        files = images;
        index = next;
        show();
    }

    private static @NotNull List<VFile> imagesNear(final @NotNull VFile file) {
        try {
            final VFile dir = Vfs.parent(file);
            if (dir == null) {
                return List.of(file);
            }
            final Collator collator = Collator.getInstance();
            final List<VFile> images = new ArrayList<>();
            for (final VFile f : Vfs.list(dir)) {
                if (f.isImage()) {
                    images.add(f);
                }
            }
            images.sort((a, b) -> collator.compare(a.getName(), b.getName()));

            return images.contains(file) ? images : List.of(file);
        } catch (final IOException e) {
            return List.of(file);
        }
    }

    private void edit() {
        if (onEdit != null) {
            onEdit.accept(files.get(index));
        }
    }

    private void close() {
        if (window.getGraphicsConfiguration().getDevice().getFullScreenWindow() == window) {
            AppWindows.toggleFullScreen(window);

            return;
        }
        window.dispose();
    }
}
