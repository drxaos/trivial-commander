package tcm.ui;

import java.util.List;

import javax.swing.JPopupMenu;

import org.jetbrains.annotations.NotNull;

import tcm.core.VFile;

/**
 * Callbacks from panels to the main window.
 */
public interface PanelHost {

    boolean isShowHidden();

    boolean isThumbs();

    boolean isBrief();

    void panelActivated(@NotNull FilePanel panel);

    void panelChanged(@NotNull FilePanel panel);

    /** A printable character in the table goes to the command line, as in TC. */
    void typedToCommandLine(char c);

    boolean isCommandLineEmpty();

    /** Enter/double click on a file (not a directory and not an archive); false — there is no program associated with it. */
    boolean openFile(@NotNull VFile file);

    /** Files dragged with the mouse into a directory (of the other panel or the directory under the mouse). */
    void dropFiles(@NotNull List<VFile> files, @NotNull VFile targetDir, boolean move);

    /** Binds to the table the keys that the main window executes (operations, tabs, command line). */
    void bindPanelKeys(@NotNull PanelTab tab);

    @NotNull
    JPopupMenu createContextMenu(@NotNull PanelTab tab);

    @NotNull
    JPopupMenu createHotlistMenu(@NotNull PanelTab tab);

    /** Panel empty-space menu: create, terminal, file manager, properties of the current directory. */
    @NotNull
    JPopupMenu createDirMenu(@NotNull PanelTab tab);
}
