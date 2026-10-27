package tcm.ui;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.dnd.DragSource;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;

/**
 * Mouse gestures in the file table:
 * <ul>
 *   <li>pressing the right button marks a file, dragging with the right button held marks rows (as in TC with right-button
 *       selection), holding the right button — context menu, on empty space — menu of the current directory;</li>
 *   <li>double click opens a file already on the second button press. The AWT click counter is no good for this: when quickly
 *       walking through directories it keeps counting (3, 4, …), and only the first navigation would fire;</li>
 *   <li>dragging with the left button onto the other panel (or onto a directory) — copy dialog; while the right button is
 *       held during the drag — move. The directory where the files will land is outlined with a frame;
 *       outside the window the drag becomes a system one — files can be dropped into another program.</li>
 * </ul>
 * Events are intercepted before BasicTableUI: otherwise the table cursor would follow the mouse while dragging.
 */
final class PanelMouse implements FileTable.MouseFilter {

    private static final int DRAG_THRESHOLD = 6;
    private static final int LONG_PRESS_MS = 1000;
    private static final int DEFAULT_DOUBLE_CLICK_MS = 500;

    private final @NotNull PanelTab tab;
    private final @NotNull FileTable table;

    private int rightRow = -1;
    private int rightLastRow = -1;
    private boolean rightDragged;
    private boolean markState;
    private @Nullable Point rightPoint;
    private final @NotNull Timer longPress;
    private final @NotNull PanelExport export;

    private int leftRow = -1;
    private @Nullable Point leftPoint;
    private boolean dragging;
    private boolean move;
    private @Nullable FileTable dropTable;

    private long firstPressTime;
    private int firstPressRow = -1;
    private @Nullable Point firstPressPoint;
    /** The press opened a file: its drag and release must not reach the table of the already new directory. */
    private boolean swallowLeft;

    PanelMouse(final @NotNull PanelTab tab) {
        this.tab = tab;
        this.table = tab.getTable();
        this.longPress = new Timer(LONG_PRESS_MS, e -> showMenu());
        longPress.setRepeats(false);
        this.export = new PanelExport(table);
    }

    @Override
    public boolean mouse(final @NotNull MouseEvent e) {
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED:
                return pressed(e);
            case MouseEvent.MOUSE_RELEASED:
                return released(e);
            case MouseEvent.MOUSE_CLICKED:
                return !SwingUtilities.isLeftMouseButton(e);
            default:
                return false;
        }
    }

    @Override
    public boolean motion(final @NotNull MouseEvent e) {
        if (e.getID() != MouseEvent.MOUSE_DRAGGED) {
            return false;
        }
        if (rightPoint != null && (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) {
            if (rightRow >= 0) {
                rightDrag(e);
            } else if (rightPoint.distance(e.getPoint()) >= DRAG_THRESHOLD) {
                // dragged over empty space — this is not a hold for the menu
                longPress.stop();
            }

            return true;
        }
        if (swallowLeft) {
            return true;
        }
        if (leftPoint == null) {
            return false;
        }
        if (!dragging && leftPoint.distance(e.getPoint()) >= DRAG_THRESHOLD && leftRow >= 0
            && !tab.getModel().getFile(leftRow).isParentLink()) {
            dragging = true;
        }
        if (dragging && outsideWindow(e)) {
            exportDrag(e);

            return true;
        }
        if (dragging) {
            updateCursor(e);
        }

        return dragging;
    }

    private boolean pressed(final @NotNull MouseEvent e) {
        if (SwingUtilities.isRightMouseButton(e)) {
            if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
                // right button on top of a left-button drag — move instead of copy
                move = true;
                if (dragging) {
                    updateCursor(e);
                }

                return true;
            }
            tab.focusTable();
            rightRow = table.rowAtPoint(e.getPoint());
            rightLastRow = rightRow;
            rightPoint = e.getPoint();
            rightDragged = false;
            if (rightRow >= 0) {
                final FileTableModel model = tab.getModel();
                markState = !model.isMarked(model.getFile(rightRow));
                model.setMarked(rightRow, markState);
                table.setCursorRow(rightRow);
                tab.updateLabels();
            }
            longPress.restart();

            return true;
        }
        if (SwingUtilities.isLeftMouseButton(e)) {
            final int row = table.rowAtPoint(e.getPoint());
            if (isSecondPress(e, row)) {
                openPressed(row);

                return true;
            }
            firstPressTime = e.getWhen();
            firstPressRow = row;
            firstPressPoint = e.getLocationOnScreen();
            swallowLeft = false;
            leftRow = row;
            leftPoint = e.getPoint();
            dragging = false;
            move = false;
        }

        return false;
    }

    // ---- double click ----

    private boolean isSecondPress(final @NotNull MouseEvent e, final int row) {
        return row >= 0 && row == firstPressRow && firstPressPoint != null && e.getWhen() - firstPressTime <= doubleClickInterval()
            && firstPressPoint.distance(e.getLocationOnScreen()) < DRAG_THRESHOLD;
    }

    /** The table is not called: after navigation BasicTableUI would put the cursor on the row under the mouse in the new directory. */
    private void openPressed(final int row) {
        // the next press is the first again: this way each pair of quick clicks opens the next directory
        firstPressTime = 0;
        firstPressRow = -1;
        firstPressPoint = null;
        swallowLeft = true;
        leftPoint = null;
        dragging = false;
        tab.focusTable();
        table.setCursorRow(row);
        tab.openCursor();
    }

    private static int doubleClickInterval() {
        final Object value = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");

        return value instanceof Integer ? (Integer) value : DEFAULT_DOUBLE_CLICK_MS;
    }

    private boolean released(final @NotNull MouseEvent e) {
        if (SwingUtilities.isRightMouseButton(e)) {
            if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
                // right button released, left is still dragging — copy again
                move = false;
                if (dragging) {
                    updateCursor(e);
                }

                return true;
            }
            longPress.stop();
            rightRow = -1;
            rightPoint = null;

            return true;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) {
            return false;
        }
        if (swallowLeft) {
            swallowLeft = false;

            return true;
        }
        final boolean wasDragging = dragging;
        leftPoint = null;
        dragging = false;
        if (!wasDragging) {
            return false;
        }
        // dropping files — not the first click of a double click
        firstPressTime = 0;
        setCursor(null);
        setDropTable(null, -1);
        // the copy dialog is modal: open it after BasicTableUI handles the button release
        final VFile target = findTarget(e);
        if (target != null && leftRow >= 0 && leftRow < tab.getModel().getRowCount()) {
            final List<VFile> files = draggedFiles();
            final boolean asMove = move;
            SwingUtilities.invokeLater(() -> tab.getOwner().getHost().dropFiles(files, target, asMove));
        }

        return false;
    }

    // ---- right-button selection ----

    private void rightDrag(final @NotNull MouseEvent e) {
        final int row = clampedRow(e.getY());
        if (row == rightLastRow || row < 0) {
            return;
        }
        final FileTableModel model = tab.getModel();
        if (!rightDragged) {
            // the first row was already marked on press
            rightDragged = true;
            longPress.stop();
        }
        final int step = row > rightLastRow ? 1 : -1;
        for (int r = rightLastRow + step; r != row + step; r += step) {
            model.setMarked(r, markState);
        }
        rightLastRow = row;
        table.setCursorRow(row);
        tab.updateLabels();
    }

    /** Right button held without dragging: menu for the file under the mouse, on empty space — for the current directory. */
    private void showMenu() {
        final Point at = rightPoint;
        if (rightDragged || at == null || rightRow >= tab.getModel().getRowCount()) {
            return;
        }
        if (rightRow < 0) {
            tab.showDirMenu(at);

            return;
        }
        table.setCursorRow(rightRow);
        tab.showContextMenu(at);
    }

    /** Row under the mouse; outside the table — the edge row (during autoscroll the mouse goes past the edge). */
    private int clampedRow(final int y) {
        final int count = table.getRowCount();
        if (count == 0) {
            return -1;
        }

        return Math.max(0, Math.min(count - 1, y / table.getRowHeight()));
    }

    // ---- left-button drag ----

    private void updateCursor(final @NotNull MouseEvent e) {
        final DropSpot target = findDropSpot(e);
        if (target == null) {
            setCursor(DragSource.DefaultCopyNoDrop);
            setDropTable(null, -1);
        } else {
            setCursor(move ? DragSource.DefaultMoveDrop : DragSource.DefaultCopyDrop);
            setDropTable(target.tab.getTable(), target.row);
        }
    }

    private boolean outsideWindow(final @NotNull MouseEvent e) {
        final Window w = SwingUtilities.getWindowAncestor(table);

        return w != null && !w.getBounds().contains(e.getLocationOnScreen());
    }

    /** The mouse left the window: from now on the drag is a system one, our own (frames, cursor) stops. */
    private void exportDrag(final @NotNull MouseEvent e) {
        final List<VFile> files = draggedFiles();
        if (!PanelExport.canExport(files)) {
            updateCursor(e);

            return;
        }
        setCursor(null);
        setDropTable(null, -1);
        if (export.start(e, files)) {
            // the button release goes to the system drag; if it still reaches the table — swallow it
            dragging = false;
            leftPoint = null;
            firstPressTime = 0;
            swallowLeft = true;
        }
    }

    private void setDropTable(final @Nullable FileTable target, final int row) {
        if (dropTable != null && dropTable != target) {
            dropTable.setDropRow(-1);
        }
        dropTable = target;
        if (target != null) {
            target.setDropRow(row);
        }
    }

    /** A marked file drags all marked ones along, an unmarked one — only itself (as in TC). */
    private @NotNull List<VFile> draggedFiles() {
        final FileTableModel model = tab.getModel();
        final VFile pressed = model.getFile(leftRow);

        return model.isMarked(pressed) ? model.getMarked() : List.of(pressed);
    }

    private @Nullable VFile findTarget(final @NotNull MouseEvent e) {
        final DropSpot target = findDropSpot(e);

        return target == null ? null : target.dir;
    }

    /** Directory where files will land: the directory under the mouse or the other panel's current directory; null — nowhere to drop. */
    private @Nullable DropSpot findDropSpot(final @NotNull MouseEvent e) {
        final JRootPane root = SwingUtilities.getRootPane(table);
        if (root == null) {
            return null;
        }
        final Point p = SwingUtilities.convertPoint(table, e.getPoint(), root.getContentPane());
        final Component deepest = SwingUtilities.getDeepestComponentAt(root.getContentPane(), p.x, p.y);
        final PanelTab targetTab = deepest == null ? null : (PanelTab) SwingUtilities.getAncestorOfClass(PanelTab.class, deepest);
        if (targetTab == null || targetTab.isFeed()) {
            return null;
        }
        final List<VFile> dragged = draggedFiles();
        if (deepest == targetTab.getTable()) {
            final Point tp = SwingUtilities.convertPoint(table, e.getPoint(), targetTab.getTable());
            final int row = targetTab.getTable().rowAtPoint(tp);
            if (row >= 0) {
                final VFile f = targetTab.getModel().getFile(row);
                if (f.isDirectory() && !f.isParentLink() && !dragged.contains(f)) {
                    return new DropSpot(targetTab, row, f);
                }
            }
        }
        if (targetTab == tab) {
            return null;
        }

        return new DropSpot(targetTab, -1, targetTab.getDir());
    }

    private void setCursor(final @Nullable Cursor cursor) {
        final Window w = SwingUtilities.getWindowAncestor(table);
        if (w != null) {
            w.setCursor(cursor);
        }
        table.setCursor(cursor);
    }

    /** Where files will land: tab, directory row (-1 — the tab's current directory) and the directory itself. */
    private static final class DropSpot {

        private final @NotNull PanelTab tab;
        private final int row;
        private final @NotNull VFile dir;

        private DropSpot(final @NotNull PanelTab tab, final int row, final @NotNull VFile dir) {
            this.tab = tab;
            this.row = row;
            this.dir = dir;
        }
    }
}
