package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Window;

import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * F1 help: implemented keyboard assignments (Total Commander layout).
 */
public final class HelpDialog {

    /** Viewer section: help from the viewer window opens directly at it. */
    public static final String VIEWER = "Viewer";

    private static final String[][] KEYS = {
        {"File panels", ""},
        {"F1", "This help"},
        {"F2 / Ctrl+R", "Refresh panel; in search results — return to the directory"},
        {"F3 / Shift+F3", "View selected files / only the file under the cursor (on a directory — enter it)"},
        {"Alt+F3", "Open with the associated program"},
        {"F4 / Shift+F4", "Edit / create a text file and edit it"},
        {"F5 / Shift+F5", "Copy / copy into the same directory under another name"},
        {"Ctrl+Shift+F5", "Create symbolic links"},
        {"F6 / Shift+F6", "Move / rename in the panel (several selected — multi-rename; on [..] — path)"},
        {"F7 / Shift+F7", "Create directory / in the other panel"},
        {"F8, Del / Shift+F8, Shift+Del", "Delete to trash / permanently"},
        {"F9 / F10", "Menu above the panel / main menu"},
        {"Shift+F1", "View mode menu"},
        {"Shift+F2", "Compare panel file lists (mark differences)"},
        {"Ctrl+Shift+F3", "Compare files by content"},
        {"Shift+F9, Shift+F10, Menu", "Context menu"},
        {"Alt+F1 / Alt+F2", "Drive list of the left / right panel"},
        {"Alt+F4, Alt+X", "Exit"},
        {"Alt+F5 / Alt+Shift+F5", "Pack / pack and delete"},
        {"Alt+F6, Alt+F9", "Unpack archives (or the selection in an open archive)"},
        {"Alt+Shift+F6, Alt+Shift+F9", "Test archives"},
        {"Alt+F7", "Find files"},
        {"Alt+F8", "Command line history"},
        {"Alt+F10", "Directory tree"},
        {"Alt+Shift+F11", "Focus the toolbar (Esc — back)"},
        {"Ctrl+F1 / Ctrl+F2 / Ctrl+Shift+F1", "Brief / full view / thumbnails"},
        {"Ctrl+F3..F6 / Ctrl+F7", "Sort by name, extension, date, size / unsorted"},
        {"Ctrl+F9", "Print"},
        {"Ctrl+F10 / Ctrl+F11 / Ctrl+F12", "All files / programs only / custom filter"},
        {"Ctrl+A, Ctrl+Num + / Ctrl+Num -", "Select all / deselect all"},
        {"Ctrl+Shift+Num + / -", "Select / deselect files only"},
        {"Num + / Num - / Num * / Num /", "Select group / deselect / invert / restore"},
        {"Shift+Num +, -, *", "Same for files only"},
        {"Alt+Num + / Alt+Num -", "Select / deselect by the extension of the file under the cursor"},
        {"Ins, Shift+↓ / Shift+↑ / Alt+Shift+↑", "Mark and move down / up / in place"},
        {"Space", "Mark (for a directory, count its size)"},
        {"Ctrl+B / Ctrl+Shift+B", "Flat list of subdirectories / for the selection (again — to the file)"},
        {"Ctrl+C, Ctrl+Ins / Ctrl+X / Ctrl+V, Shift+Ins", "Clipboard: copy / cut / paste files"},
        {"Ctrl+D", "Favorite directories (Num + add, Num - remove, Num * configure)"},
        {"Ctrl+E / Ctrl+↓", "Previous command / last command into the line"},
        {"Ctrl+I, Tab", "Other panel"},
        {"Ctrl+J, Ctrl+Enter / +Shift", "Name / full path to the command line"},
        {"Ctrl+L", "Disk usage"},
        {"Ctrl+M", "Multi-rename"},
        {"Ctrl+P", "Current path to the command line"},
        {"Ctrl+Q / Ctrl+Shift+Q", "Quick view in the other panel / in a window"},
        {"Ctrl+S / Ctrl+Shift+S", "Quick filter / repeat the last one (Ctrl+S in the box — search↔filter)"},
        {"Ctrl+Alt+letter", "Quick search"},
        {"Ctrl+T / Ctrl+Shift+T", "New tab / in background"},
        {"Ctrl+W / Ctrl+Shift+W", "Close tab / all others"},
        {"Ctrl+Tab / Ctrl+Shift+Tab, Ctrl+Shift+A", "Next / previous tab, tab list"},
        {"Ctrl+U / Ctrl+Shift+U", "Swap panels / with all tabs"},
        {"Ctrl+=", "Open the other panel's directory here (also a toolbar button)"},
        {"Ctrl+Y, Esc", "Clear the command line (Esc first resets the quick filter)"},
        {"Ctrl+\\", "Root directory"},
        {"Ctrl+H", "Show/hide hidden files"},
        {"Enter / Shift+Enter", "Open / alternatively (archive — with a program); a non-empty command line is executed"},
        {"Alt+Enter / Alt+Shift+Enter", "Properties / sizes of all subdirectories (works with Alt+Shift layout switching too)"},
        {"Shift+Esc", "Minimize the window"},
        {"Backspace, Ctrl+PgUp / Ctrl+PgDn", "Up / enter the directory; open a file as an archive (format by signature)"},
        {"←/→, Shift+←/→", "To the command line"},
        {"Alt+← / Alt+→ / Alt+↓", "Back / forward / directory history (Shift+Enter — new tab)"},
        {"Ctrl+← / Ctrl+→", "Open in the other panel (arrow towards the active one — the other panel's directory here)"},
        {"Ctrl+↑ / Ctrl+Shift+↑", "Directory under the cursor in a new tab / in the other panel"},
        {"Letter", "To the command line"},
        {"Command line", ""},
        {"Enter / Shift+Enter", "Execute (cd, md, rd are built in)"},
        {"Esc / ↑ ↓ Tab", "Clear and return to the panel / return to the panel"},
        {"Shift+↑/↓, Ctrl+E / Ctrl+↓", "History / history list"},
        {"Ctrl+K, Ctrl+Del / Ctrl+T / Ctrl+W, Ctrl+Backspace", "Delete to end / word to the right / word to the left"},
        {"Ctrl+Y", "Clear"},
        {VIEWER, ""},
        {"F2, Backspace / F4", "Reload / to the editor"},
        {"F7, Ctrl+F / Ctrl+F7", "Find / find backwards"},
        {"F3, F5 / Shift+F3 / Ctrl+F3 / Shift+F7", "Next / previous / reverse direction / next and forward"},
        {"N, P, Alt+←/→", "Next / previous file"},
        {"1 2 3 4 5 6 7 8 9", "Text / binary / hex / image / HTML / UTF-16 / UTF-8 / Markdown / JSON"},
        {"A / S / V", "Windows-1251 / DOS-866 / proportional font (other encodings — Options menu)"},
        {"W / F / L / C", "Wrap lines / fit all / fit large / center"},
        {"Num + / Num - / Ctrl+0, mouse wheel", "Zoom / 1:1"},
        {"Wheel with the wheel pressed", "Scroll text 5 times faster"},
        {"Image: ← / →", "Previous / next image in the directory"},
        {"H / Shift+H, R / Shift+R", "Flip horizontally / vertically, rotate clockwise / counterclockwise"},
        {"Left-button drag, right button", "Scroll the image, Image menu"},
        {"Space / Shift+Space, F6", "Page down / up, show cursor"},
        {"Click / drag on line numbers", "Select a line / several lines (also in the editor)"},
        {"Ctrl+A / Ctrl+C / Ctrl+Shift+C / Ctrl+P", "Select all / copy (text or image) / as hex / print"},
        {"Esc, F10, Q / F11 / Alt+Enter", "Close / maximize / full screen"},
        {"Mouse in the panel", ""},
        {"Right-button press / drag", "Mark a file / mark files"},
        {"Holding the right button for 1 s", "Context menu (on empty space — directory menu)"},
        {"Dragging out of the window", "Copy files to another program"},
        {"Shift+click on F8 Delete", "Delete bypassing the trash"},
        {"Left-button drag", "Copy to the other panel or to the framed directory (a marked file drags all marked ones)"},
        {"…while the right button is pressed", "Move (right button released — copy again)"},
        {"Double click right of the tabs", "Duplicate the current tab"},
        {"Middle button (wheel) on a tab", "Close the tab"},
        {"Wheel over the tabs", "Scroll the tab strip"},
        {"Compare by content", ""},
        {"Alt+↓ / Alt+↑", "Next / previous difference"},
        {"F7, Ctrl+F, F3, Shift+F3, Ctrl+F3", "Find"},
        {"Ctrl+U / F9 / Ctrl+Tab", "Swap files / two-line panel / other side"},
        {"←/→, Alt+←/→", "Scroll by 1 / 30 characters"},
        {"Dialogs", ""},
        {"F2 (copying)", "To the queue"},
        {"F5/F6, Shift+F5/F6, Alt+F5", "Toggle name selection"},
        {"F10 / Ctrl+D / F8 / Ctrl+Home", "Tree / favorites / mask templates / cursor before the name"},
        {"Overwrite: F3 / Shift+F3 / Ctrl+F3 / Ctrl+C", "View target / source / compare / copy details"},
        {"Search: F3 / F4 / F2 / Alt+←→ / Ctrl+C / Ctrl+Tab", "View / edit / in results / lists / copy / pages"},
        {"Multi-rename", "Shift+↑/↓ move, Del remove, Ctrl+F3..F6 sort, Enter execute"},
    };

    private HelpDialog() {
    }

    public static void show(final @NotNull Window owner) {
        show(owner, null);
    }

    /** section — title of the section to show at the top of the window. */
    public static void show(final @NotNull Window owner, final @Nullable String section) {
        final DefaultTableModel model = new DefaultTableModel(new Object[] {"Keys", "Action"}, 0) {
            @Override
            public boolean isCellEditable(final int row, final int column) {
                return false;
            }
        };
        for (final String[] row : KEYS) {
            model.addRow(row[1].isEmpty() ? new Object[] {"— " + row[0] + " —", ""} : row);
        }
        final JTable table = new JTable(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(560);
        final JDialog d = new JDialog(owner, "Keyboard reference", JDialog.ModalityType.MODELESS);
        final JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(900, 620));
        d.getContentPane().add(scroll, BorderLayout.CENTER);
        Dialogs.bindEscape(d);
        d.pack();
        for (int row = 0; row < KEYS.length; row++) {
            if (KEYS[row][1].isEmpty() && KEYS[row][0].equals(section)) {
                final Rectangle r = table.getCellRect(row, 0, true);
                r.height = scroll.getViewport().getHeight();
                table.scrollRectToVisible(r);
                table.setRowSelectionInterval(row, row);
            }
        }
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
