package tcm.ui;

import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;

import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.ui.dialogs.Dialogs;

/**
 * Files dropped into the panel from another program (file manager, desktop): copy dialog into the directory
 * under the mouse or into the tab's current directory. Copy only: if the source is told about a move,
 * it deletes its files itself, while a copy into an archive or onto an occupied name may still not happen.
 */
final class PanelDrop implements DropTargetListener {

    /** URI list (XDnD on Linux): not all sources provide it as javaFileListFlavor. */
    private static final DataFlavor URI_LIST = uriListFlavor();

    private final @NotNull PanelTab tab;

    PanelDrop(final @NotNull PanelTab tab) {
        this.tab = tab;
    }

    @Override
    public void dragEnter(final @NotNull DropTargetDragEvent e) {
        dragOver(e);
    }

    @Override
    public void dragOver(final @NotNull DropTargetDragEvent e) {
        if (!accepts(e.getCurrentDataFlavors(), e.getSourceActions())) {
            tab.getTable().setDropRow(-1);
            e.rejectDrag();

            return;
        }
        tab.getTable().setDropRow(dirRow(e.getLocation().y));
        e.acceptDrag(DnDConstants.ACTION_COPY);
    }

    @Override
    public void dropActionChanged(final @NotNull DropTargetDragEvent e) {
        dragOver(e);
    }

    @Override
    public void dragExit(final @NotNull DropTargetEvent e) {
        tab.getTable().setDropRow(-1);
    }

    @Override
    public void drop(final @NotNull DropTargetDropEvent e) {
        tab.getTable().setDropRow(-1);
        if (!accepts(e.getCurrentDataFlavors(), e.getSourceActions())) {
            e.rejectDrop();

            return;
        }
        final int row = dirRow(e.getLocation().y);
        final VFile target = row >= 0 ? tab.getModel().getFile(row) : tab.getDir();
        e.acceptDrop(DnDConstants.ACTION_COPY);
        final List<Path> paths;
        try {
            paths = droppedPaths(e.getTransferable());
        } catch (final UnsupportedFlavorException | IOException ex) {
            e.dropComplete(false);
            Dialogs.error(tab, "Cannot accept dropped files", ex);

            return;
        }
        e.dropComplete(true);
        if (paths.isEmpty()) {
            return;
        }
        // the source waits for drop() to finish: the modal copy dialog is opened after it
        SwingUtilities.invokeLater(() -> copy(paths, target));
    }

    private void copy(final @NotNull List<Path> paths, final @NotNull VFile target) {
        if (target.isLocal() && paths.stream().allMatch(p -> target.getHostPath().equals(p.getParent()))) {
            // our own files dragged out of the window and dropped back into their directory — nowhere to copy
            return;
        }
        final List<VFile> files = new ArrayList<>();
        for (final Path p : paths) {
            try {
                files.add(Vfs.stat(p));
            } catch (final IOException ex) {
                Dialogs.error(tab, "Cannot read " + p, ex);

                return;
            }
        }
        tab.getOwner().getHost().dropFiles(files, target, false);
    }

    private boolean accepts(final @NotNull DataFlavor[] flavors, final int sourceActions) {
        if (tab.isFeed() || (sourceActions & DnDConstants.ACTION_COPY) == 0) {
            return false;
        }
        for (final DataFlavor f : flavors) {
            if (f.isFlavorJavaFileListType() || f.match(URI_LIST)) {
                return true;
            }
        }

        return false;
    }

    /** Directory row under the mouse; -1 — drop into the tab's current directory. */
    private int dirRow(final int y) {
        final int row = tab.getTable().rowAtPoint(new Point(0, y));
        if (row < 0) {
            return -1;
        }
        final VFile f = tab.getModel().getFile(row);

        return f.isDirectory() && !f.isParentLink() ? row : -1;
    }

    private static @NotNull List<Path> droppedPaths(final @NotNull Transferable t) throws UnsupportedFlavorException, IOException {
        final List<Path> result = new ArrayList<>();
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            for (final Object o : (List<?>) t.getTransferData(DataFlavor.javaFileListFlavor)) {
                result.add(((File) o).toPath());
            }

            return result;
        }
        for (final String line : ((String) t.getTransferData(URI_LIST)).split("\r?\n")) {
            // per RFC 2483 lines starting with # are comments
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            try {
                result.add(Path.of(URI.create(line.trim())));
            } catch (final IllegalArgumentException | FileSystemNotFoundException ex) {
                // not file:// (e.g. a link from a browser) — not copied
            }
        }

        return result;
    }

    private static @NotNull DataFlavor uriListFlavor() {
        try {
            return new DataFlavor("text/uri-list;class=java.lang.String");
        } catch (final ClassNotFoundException e) {
            // String is always available
            throw new IllegalStateException(e);
        }
    }
}
