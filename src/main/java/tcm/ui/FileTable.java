package tcm.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import tcm.core.VFile;

/**
 * File table: Name/Ext/Size/Date/Attr columns, sorting by clicking the header, brief mode, thumbnails,
 * in-panel renaming (Shift+F6).
 */
public final class FileTable extends JTable {

    /** Client property: the component handles F-keys itself, the window's global keymap does not intercept them. */
    public static final String NO_GLOBAL_KEYS = "tcm.noGlobalKeys";

    private static final Color MARKED = new Color(0xE03C3C);
    private static final int[] WIDTHS = {260, 50, 95, 125, 85};
    private static final int ROW_HEIGHT = 22;
    /** Empty strip below the last row: shows that the list has ended, and gives room to invoke the directory menu. */
    private static final int BOTTOM_GAP = 30;
    /** Inner horizontal padding of the rename field and its minimum gap from the panel edges. */
    private static final int RENAME_PAD = 2;
    /** The full name of a cut-off name appears after the mouse rests on it this long. */
    private static final int NAME_TIP_DELAY = 1000;
    /** Diameter of the ring that counts down to the context menu, and its distance from the mouse. */
    private static final int HOLD_RING_SIZE = 18;
    private static final int HOLD_RING_GAP = 8;

    @Getter
    private final @NotNull FileTableModel fileModel;
    private final List<TableColumn> columns = new ArrayList<>();
    private boolean thumbs;
    @Getter
    private boolean brief;
    @Getter
    private @Nullable JTextField renameField;
    @Getter
    private int renameRow = -1;
    private @Nullable MouseFilter mouseFilter;
    private int dropRow = -1;
    private @Nullable Point holdPoint;
    private double holdProgress;

    /** Intercepts mouse events before BasicTableUI; true — the event is handled and goes no further. */
    public interface MouseFilter {

        boolean mouse(@NotNull MouseEvent e);

        boolean motion(@NotNull MouseEvent e);
    }

    public FileTable(final @NotNull FileTableModel model) {
        super(model);
        this.fileModel = model;
        setShowGrid(false);
        setIntercellSpacing(new Dimension(0, 0));
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setFillsViewportHeight(true);
        setRowHeight(ROW_HEIGHT);
        setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        // Tab switches panels, not cells
        setFocusTraversalKeysEnabled(false);
        for (int i = 0; i < WIDTHS.length; i++) {
            final TableColumn col = getColumnModel().getColumn(i);
            col.setPreferredWidth(WIDTHS[i]);
            columns.add(col);
        }
        getColumnModel().getColumn(FileTableModel.COL_NAME).setMinWidth(80);
        final Renderer renderer = new Renderer();
        setDefaultRenderer(Object.class, renderer);
        setDefaultRenderer(VFile.class, renderer);
        setDefaultRenderer(String.class, renderer);
        installHeader();
        // standard JTable actions on these keys interfere with file navigation
        final String[] blocked = {"ENTER", "shift ENTER", "ctrl A", "TAB", "shift TAB", "ctrl TAB", "ctrl shift TAB", "F2", "F8", "ctrl C",
            "ctrl V", "ctrl X", "ctrl INSERT", "shift INSERT", "shift DELETE", "ESCAPE", "LEFT", "RIGHT", "shift LEFT", "shift RIGHT",
            "shift UP", "shift DOWN", "ctrl UP", "ctrl DOWN", "ctrl LEFT", "ctrl RIGHT", "ctrl SPACE", "shift SPACE", "ctrl shift UP"};
        for (final String key : blocked) {
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), "none");
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "none");
        }
        installNameTips();
    }

    /** The delay is shared by all tooltips: it is set while the mouse is over the table and restored when it leaves. */
    private void installNameTips() {
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            private int saved = -1;

            @Override
            public void mouseEntered(final MouseEvent e) {
                final ToolTipManager tips = ToolTipManager.sharedInstance();
                if (saved < 0) {
                    saved = tips.getInitialDelay();
                }
                tips.setInitialDelay(NAME_TIP_DELAY);
            }

            @Override
            public void mouseExited(final MouseEvent e) {
                if (saved >= 0) {
                    ToolTipManager.sharedInstance().setInitialDelay(saved);
                    saved = -1;
                }
            }
        });
    }

    /** Tooltip only over a name that does not fit into its column: the full name. */
    @Override
    public String getToolTipText(final MouseEvent e) {
        final int row = rowAtPoint(e.getPoint());
        final int column = columnAtPoint(e.getPoint());
        if (row < 0 || column < 0 || isRenaming() || convertColumnIndexToModel(column) != FileTableModel.COL_NAME) {
            return null;
        }
        final Component c = prepareRenderer(getCellRenderer(row, column), row, column);
        if (!(c instanceof JLabel label) || label.getPreferredSize().width <= getCellRect(row, column, false).width) {
            return null;
        }
        final VFile f = fileModel.getFile(row);

        return f.isParentLink() ? null : fileModel.isFeedMode() ? fileModel.feedName(f) : f.getName();
    }

    public void setThumbs(final boolean value) {
        thumbs = value;
        setRowHeight(value ? ThumbCache.SIZE + 4 : ROW_HEIGHT);
        repaint();
    }

    public boolean isThumbs() {
        return thumbs;
    }

    /** Brief mode (Ctrl+F1): names only. */
    public void setBrief(final boolean value) {
        if (value == brief) {
            return;
        }
        brief = value;
        for (int i = 1; i < columns.size(); i++) {
            if (value) {
                getColumnModel().removeColumn(columns.get(i));
            } else {
                getColumnModel().addColumn(columns.get(i));
            }
        }
    }

    @Override
    public Dimension getPreferredSize() {
        final Dimension d = super.getPreferredSize();

        return new Dimension(d.width, d.height + BOTTOM_GAP);
    }

    public void setMouseFilter(final @Nullable MouseFilter filter) {
        mouseFilter = filter;
    }

    @Override
    protected void processMouseEvent(final MouseEvent e) {
        if (mouseFilter != null && mouseFilter.mouse(e)) {
            e.consume();

            return;
        }
        super.processMouseEvent(e);
    }

    @Override
    protected void processMouseMotionEvent(final MouseEvent e) {
        if (mouseFilter != null && mouseFilter.motion(e)) {
            e.consume();

            return;
        }
        super.processMouseMotionEvent(e);
    }

    /** Directory row where dragged files will land (frame); -1 — none. */
    public void setDropRow(final int row) {
        if (row == dropRow) {
            return;
        }
        if (dropRow >= 0 && dropRow < getRowCount()) {
            repaint(getCellRect(dropRow, 0, true).union(getCellRect(dropRow, getColumnCount() - 1, true)));
        }
        dropRow = row;
        if (row >= 0 && row < getRowCount()) {
            repaint(getCellRect(row, 0, true).union(getCellRect(row, getColumnCount() - 1, true)));
        }
    }

    /**
     * Ring next to the mouse while the right button is held: the part of the wait for the context menu that has passed (0..1);
     * null — no ring.
     */
    public void setHoldProgress(final @Nullable Point mouse, final double progress) {
        final Point old = holdPoint;
        holdPoint = mouse;
        holdProgress = progress;
        if (old != null) {
            repaint(holdRingBounds(old));
        }
        if (mouse != null) {
            repaint(holdRingBounds(mouse));
        }
    }

    /** Up and to the right of the mouse, where the pointer does not cover it; pulled back into the visible part of the table. */
    private @NotNull Rectangle holdRingBounds(final @NotNull Point mouse) {
        final Rectangle view = getVisibleRect();
        final int x = Math.max(view.x, Math.min(mouse.x + HOLD_RING_GAP, view.x + view.width - HOLD_RING_SIZE));
        final int y = Math.max(view.y, Math.min(mouse.y - HOLD_RING_GAP - HOLD_RING_SIZE, view.y + view.height - HOLD_RING_SIZE));

        return new Rectangle(x, y, HOLD_RING_SIZE, HOLD_RING_SIZE);
    }

    /** Over the rows and the rename field. Black on white whatever the theme: the ring is read on any row color. */
    @Override
    public void paint(final Graphics g) {
        super.paint(g);
        final Point mouse = holdPoint;
        if (mouse == null) {
            return;
        }
        final Rectangle r = holdRingBounds(mouse);
        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Double(r.x + 0.5, r.y + 0.5, r.width - 1, r.height - 1));
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new Ellipse2D.Double(r.x + 0.5, r.y + 0.5, r.width - 1, r.height - 1));
            // clockwise from 12 o'clock, as a clock hand goes
            final double inset = 3.5;
            g2.fill(new Arc2D.Double(r.x + inset, r.y + inset, r.width - 2 * inset, r.height - 2 * inset, 90,
                -360 * Math.max(0, Math.min(1, holdProgress)), Arc2D.PIE));
        } finally {
            g2.dispose();
        }
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);
        if (dropRow < 0 || dropRow >= getRowCount()) {
            return;
        }
        final Rectangle r = getCellRect(dropRow, 0, true).union(getCellRect(dropRow, getColumnCount() - 1, true));
        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            final Color c = UIManager.getColor("Component.focusColor");
            g2.setColor(c != null ? c : new Color(0x3C82D2));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);
        } finally {
            g2.dispose();
        }
    }

    public int getCursorRow() {
        return getSelectedRow();
    }

    public void setCursorRow(final int row) {
        if (getRowCount() == 0) {
            return;
        }
        final int r = Math.max(0, Math.min(row, getRowCount() - 1));
        getSelectionModel().setSelectionInterval(r, r);
        final Rectangle rect = getCellRect(r, 0, true);
        scrollRectToVisible(rect);
    }

    public int getVisibleRowCount() {
        return Math.max(1, getVisibleRect().height / getRowHeight());
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
        return false;
    }

    /**
     * Rename field over the name cell. commit receives the new name and the cursor offset after saving
     * (0 — Enter, -1/+1 — up/down arrows: save and move to the neighboring file).
     */
    public void startRename(final int row, final @NotNull BiConsumer<String, Integer> commit) {
        stopRename();
        final VFile f = fileModel.getFile(row);
        final JTextField field = new JTextField(f.getName());
        field.putClientProperty(NO_GLOBAL_KEYS, Boolean.TRUE);
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.accentColor")),
            BorderFactory.createEmptyBorder(0, RENAME_PAD, 0, RENAME_PAD)));
        // the field text starts where the name text is drawn (after the icon), not at the panel edge
        final Rectangle rect = getCellRect(row, 0, true);
        final int indent = Math.max(RENAME_PAD, nameTextIndent(row) - 1 - RENAME_PAD);
        rect.x += indent;
        rect.width = Math.max(rect.width - indent - RENAME_PAD, 200);
        field.setBounds(rect);
        selectBaseName(field, f);
        final Runnable cancel = () -> {
            stopRename();
            requestFocusInWindow();
        };
        bindRename(field, "ENTER", () -> finishRename(field, commit, 0));
        bindRename(field, "UP", () -> finishRename(field, commit, -1));
        bindRename(field, "DOWN", () -> finishRename(field, commit, 1));
        bindRename(field, "ESCAPE", cancel);
        bindRename(field, "shift F6", () -> toggleSelection(field, f));
        bindRename(field, "F2", () -> toggleSelection(field, f));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(final FocusEvent e) {
                if (renameField == field) {
                    stopRename();
                }
            }
        });
        renameRow = row;
        renameField = field;
        add(field);
        field.requestFocusInWindow();
        repaint();
    }

    public boolean isRenaming() {
        return renameField != null;
    }

    public void stopRename() {
        final JTextField field = renameField;
        renameField = null;
        renameRow = -1;
        if (field != null) {
            remove(field);
            repaint();
        }
    }

    @Override
    public Object getValueAt(final int row, final int column) {
        if (convertColumnIndexToModel(column) == FileTableModel.COL_EXT && isRenaming() && row == renameRow) {
            return "";
        }

        return super.getValueAt(row, column);
    }

    private void finishRename(final @NotNull JTextField field, final @NotNull BiConsumer<String, Integer> commit, final int move) {
        final String name = field.getText().trim();
        stopRename();
        requestFocusInWindow();
        commit.accept(name, move);
    }

    /** X offset of the name text inside the name cell: renderer inset + icon + gap. */
    private int nameTextIndent(final int row) {
        final Component c = prepareRenderer(getCellRenderer(row, 0), row, 0);
        if (!(c instanceof JLabel label)) {
            return 0;
        }
        final Icon icon = label.getIcon();

        return label.getInsets().left + (icon == null ? 0 : icon.getIconWidth() + label.getIconTextGap());
    }

    static void selectBaseName(final @NotNull JTextField field, final @NotNull VFile f) {
        final String text = field.getText();
        final int dot = f.isDirectory() ? -1 : text.lastIndexOf('.');
        field.select(0, dot > 0 ? dot : text.length());
    }

    /** F2/Shift+F6 in the field: name without extension ↔ whole name. */
    static void toggleSelection(final @NotNull JTextField field, final @NotNull VFile f) {
        final int len = field.getText().length();
        if (field.getSelectionStart() == 0 && field.getSelectionEnd() == len) {
            selectBaseName(field, f);
        } else {
            field.select(0, len);
        }
    }

    private static void bindRename(final @NotNull JTextField field, final @NotNull String key, final @NotNull Runnable action) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        field.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                action.run();
            }
        });
    }

    private void installHeader() {
        final JTableHeader header = getTableHeader();
        header.setReorderingAllowed(false);
        final TableCellRenderer base = header.getDefaultRenderer();
        header.setDefaultRenderer((table, value, isSelected, hasFocus, row, column) -> {
            final Component c = base.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (c instanceof JLabel) {
                final JLabel label = (JLabel) c;
                final int modelCol = convertColumnIndexToModel(column);
                final String arrow = modelCol == fileModel.getSortColumn() ? (fileModel.isAscending() ? "↑" : "↓") : "";
                label.setText(arrow + value);
                label.setHorizontalAlignment(modelCol == FileTableModel.COL_SIZE ? SwingConstants.RIGHT : SwingConstants.LEFT);
            }

            return c;
        });
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                final int col = convertColumnIndexToModel(header.columnAtPoint(e.getPoint()));
                if (col >= 0) {
                    sortKeepingCursor(col);
                }
            }
        });
    }

    public void sortKeepingCursor(final int column) {
        final VFile cursor = getCursorRow() >= 0 ? fileModel.getFile(getCursorRow()) : null;
        if (column == FileTableModel.UNSORTED) {
            fileModel.setSort(FileTableModel.UNSORTED, true);
        } else {
            fileModel.toggleSort(column);
        }
        getTableHeader().repaint();
        if (cursor != null) {
            setCursorRow(fileModel.indexOf(cursor.getLocation()));
        }
    }

    private final class Renderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(
            final JTable table,
            final Object value,
            final boolean isSelected,
            final boolean hasFocus,
            final int row,
            final int column
        ) {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            final VFile f = fileModel.getFile(row);
            final int modelCol = convertColumnIndexToModel(column);
            setIcon(null);
            setHorizontalAlignment(modelCol == FileTableModel.COL_SIZE ? SwingConstants.RIGHT : SwingConstants.LEFT);
            if (modelCol == FileTableModel.COL_NAME) {
                setText(nameText(f));
                setIcon(iconFor(f));
            } else if (modelCol == FileTableModel.COL_EXT && isRenaming() && row == renameRow) {
                setText("");
            }
            // the color is set explicitly every time: DefaultTableCellRenderer remembers setForeground for subsequent cells
            if (fileModel.isMarked(f)) {
                setForeground(MARKED);
            } else if (isSelected) {
                setForeground(table.getSelectionForeground());
            } else if (f.isHidden()) {
                setForeground(UIManager.getColor("Label.disabledForeground"));
            } else {
                setForeground(table.getForeground());
            }

            return this;
        }

        private @NotNull String nameText(final @NotNull VFile f) {
            if (f.isParentLink()) {
                return "[..]";
            }
            if (fileModel.isFeedMode()) {
                return fileModel.feedName(f);
            }
            if (f.isDirectory()) {
                return "[" + f.getName() + "]";
            }

            return brief ? f.getName() : f.getBaseName();
        }

        private @NotNull Icon iconFor(final @NotNull VFile f) {
            if (thumbs && f.isImage()) {
                final ImageIcon thumb = ThumbCache.get(f, FileTable.this);
                if (thumb != null) {
                    return thumb;
                }
            }

            return Icons.forFile(f, FileTable.this);
        }
    }
}
