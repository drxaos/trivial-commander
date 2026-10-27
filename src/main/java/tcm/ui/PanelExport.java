package tcm.ui;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TooManyListenersException;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;

/**
 * Dragging files from the panel to other programs. Inside the window our own drag works (PanelMouse),
 * and when the mouse leaves the window, a system drag starts: the gesture recognizer is triggered manually by the current mouse event.
 * Copy only: on move the receiving program would delete the files, and the panel would not know about it.
 */
final class PanelExport extends DragGestureRecognizer {

    private @Nullable List<File> pending;

    PanelExport(final @NotNull FileTable table) {
        super(DragSource.getDefaultDragSource(), table, DnDConstants.ACTION_COPY);
        addDragGestureListenerSafely();
    }

    /** Only regular disk files are exported: other programs cannot open archive entries anyway. */
    static boolean canExport(final @NotNull List<VFile> files) {
        return !files.isEmpty() && files.stream().allMatch(f -> f.isLocal() && !f.isParentLink());
    }

    /** true — the system drag has started. */
    boolean start(final @NotNull MouseEvent trigger, final @NotNull List<VFile> files) {
        final List<File> list = new ArrayList<>();
        for (final VFile f : files) {
            list.add(f.getHostPath().toFile());
        }
        pending = list;
        try {
            appendEvent(trigger);
            fireDragGestureRecognized(DnDConstants.ACTION_COPY, trigger.getPoint());

            return pending == null;
        } finally {
            pending = null;
            resetRecognizer();
        }
    }

    private void addDragGestureListenerSafely() {
        try {
            addDragGestureListener(e -> {
                final List<File> list = pending;
                if (list == null) {
                    return;
                }
                try {
                    e.startDrag(DragSource.DefaultCopyDrop, new FileList(list));
                    pending = null;
                } catch (final InvalidDnDOperationException ex) {
                    // another drag has not finished yet — stay in the internal one
                }
            });
        } catch (final TooManyListenersException e) {
            // the listener is the only one and is added once
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void registerListeners() {
        // mouse events are parsed by PanelMouse, which decides itself when to start
    }

    @Override
    protected void unregisterListeners() {
        // no listeners
    }

    /** File list: a Java list (goes out as text/uri-list on X11) and paths as a string. */
    private static final class FileList implements Transferable {

        private final @NotNull List<File> files;

        private FileList(final @NotNull List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
        }

        @Override
        public @NotNull Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
            if (DataFlavor.javaFileListFlavor.equals(flavor)) {
                return files;
            }
            if (DataFlavor.stringFlavor.equals(flavor)) {
                return files.stream()
                    .map(File::getPath)
                    .collect(Collectors.joining("\n"));
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }
}
