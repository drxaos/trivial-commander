package tcm;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.LayoutFocusTraversalPolicy;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.MenuKeyEvent;
import javax.swing.event.MenuKeyListener;
import javax.swing.plaf.basic.BasicSplitPaneUI;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import tcm.apps.AppWindows;
import tcm.apps.ViewerPanel;
import tcm.core.DirWatcher;
import tcm.core.VFile;
import tcm.ops.JobRunner;
import tcm.ui.CommandLine;
import tcm.ui.FilePanel;
import tcm.ui.FileTable;
import tcm.ui.FileTableModel;
import tcm.ui.Icons;
import tcm.ui.LayoutSwitchKeys;
import tcm.ui.PanelHost;
import tcm.ui.PanelTab;
import tcm.ui.dialogs.HelpDialog;
import tcm.ui.dialogs.TreeDialog;
import tcm.util.Keys;
import tcm.util.Settings;

/**
 * Main window: menu, toolbar, two file panels, command line, F-key buttons and TC keyboard layout.
 */
public final class MainFrame extends JFrame implements PanelHost, CommandLine.Host {

    private static final String APP_TITLE = "Trivial Commander";
    /** The window is not wider than this: the panel divider stays in the middle. */
    private static final int NARROW_WIDTH = 1200;
    /** How often panels pick up changes on disk. */
    private static final int WATCH_PERIOD_MS = 1000;
    /** How often window, tab and history state is saved without exiting the program. */
    private static final int SAVE_PERIOD_MS = 30_000;

    @Getter
    private final @NotNull FilePanel leftPanel;
    @Getter
    private final @NotNull FilePanel rightPanel;
    @Getter
    private @NotNull FilePanel activePanel;
    private final @NotNull CommandLine commandLine;
    private final @NotNull FileCommands commands = new FileCommands(this);
    private final Map<KeyStroke, Runnable> globalKeys = new HashMap<>();
    @Getter
    private final List<String> favorites = new ArrayList<>(Settings.getList("favorites"));
    private final JMenuBar menuBar = new JMenuBar();
    private final JToolBar toolBar = new JToolBar();
    private final JToggleButton thumbsButton = new JToggleButton(Icons.THUMBS);
    private final JToggleButton hiddenButton = new JToggleButton(Icons.HIDDEN);
    private boolean showHidden = Settings.getBoolean("showHidden", false);
    private boolean thumbs = Settings.getBoolean("thumbs", false);
    private boolean brief = Settings.getBoolean("brief", false);
    private @Nullable ViewerPanel quickView;
    private @Nullable ViewerPanel quickWindowViewer;
    private @Nullable Window quickWindow;
    private final DirWatcher dirWatcher = new DirWatcher();
    /** Panel refreshed by itself: the cursor stayed on the same file, quick view does not need re-reading. */
    private boolean quietRefresh;

    public MainFrame() {
        super(APP_TITLE);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImage(appIcon());
        // tabs need the command line already at creation (key bindings)
        commandLine = new CommandLine(this, Settings.getList("cmd.history"));
        leftPanel = new FilePanel(this, Settings.getList("left.tabs"), Settings.getInt("left.tab", 0));
        rightPanel = new FilePanel(this, Settings.getList("right.tabs"), Settings.getInt("right.tab", 0));
        activePanel = "right".equals(Settings.get("active", "left")) ? rightPanel : leftPanel;
        buildMenu();
        buildToolBar();
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        installDividerMouse(split);
        // theme change recreates the UI together with the divider
        split.addPropertyChangeListener("UI", e -> installDividerMouse(split));
        keepDividerCentered(split);
        final JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(commandLine, BorderLayout.NORTH);
        bottom.add(buildFunctionKeys(), BorderLayout.SOUTH);
        getContentPane().add(toolBar, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        registerGlobalKeys();
        AppWindows.setHelpHandler(w -> HelpDialog.show(w, HelpDialog.VIEWER));
        // before GlobalKeys: global shortcuts must already see the corrected modifiers
        LayoutSwitchKeys.install();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(new GlobalKeys());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                exit();
            }

            @Override
            public void windowOpened(final WindowEvent e) {
                setActivePanel(activePanel);
                focusActive();
            }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(final WindowEvent e) {
                // while the window was inactive, files may have been changed by other programs
                refreshPanelsQuietly(null);
                final Component owner = getFocusOwner();
                if (owner == null || owner == MainFrame.this) {
                    SwingUtilities.invokeLater(MainFrame.this::focusActive);
                }
            }
        });
        restoreBounds();
        setActivePanel(activePanel);
        new Timer(WATCH_PERIOD_MS, e -> refreshPanelsQuietly(dirWatcher.watch(watchedDirs()))).start();
        // on crash or kill without closing the window, no more than one period is lost
        new Timer(SAVE_PERIOD_MS, e -> saveSettings()).start();
        installQuitHandler();
        // when the window opens, focus must go to the active panel's table, not to the first component at hand
        setFocusTraversalPolicy(new LayoutFocusTraversalPolicy() {
            @Override
            public Component getInitialComponent(final Window window) {
                return MainFrame.this.activePanel.getCurrentTab().getTable();
            }
        });
    }

    // ---- PanelHost ----

    @Override
    public boolean isShowHidden() {
        return showHidden;
    }

    @Override
    public boolean isThumbs() {
        return thumbs;
    }

    @Override
    public boolean isBrief() {
        return brief;
    }

    @Override
    public void panelActivated(final @NotNull FilePanel panel) {
        if (panel != activePanel) {
            setActivePanel(panel);
        }
    }

    @Override
    public void panelChanged(final @NotNull FilePanel panel) {
        if (panel == activePanel) {
            updateTitleAndPrompt();
        }
    }

    @Override
    public void typedToCommandLine(final char c) {
        commandLine.type(c);
    }

    @Override
    public boolean isCommandLineEmpty() {
        return commandLine.isEmpty();
    }

    @Override
    public boolean openFile(final @NotNull VFile file) {
        return commands.openFile(file);
    }

    @Override
    public void dropFiles(final @NotNull List<VFile> files, final @NotNull VFile targetDir, final boolean move) {
        commands.dropFiles(files, targetDir, move);
    }

    @Override
    public @NotNull JPopupMenu createContextMenu(final @NotNull PanelTab tab) {
        final JPopupMenu menu = new JPopupMenu();
        menu.add(item("Open", "ENTER", tab::openCursor));
        menu.add(item("View", "F3", () -> commands.view(true)));
        menu.add(item("Edit", "F4", commands::edit));
        menu.add(item("Open with system", "alt F3", commands::viewExternal));
        menu.addSeparator();
        menu.add(item("Copy to clipboard", "ctrl C", () -> commands.copyToClipboard(false)));
        menu.add(item("Cut to clipboard", "ctrl X", () -> commands.copyToClipboard(true)));
        menu.add(item("Paste", "ctrl V", commands::paste));
        menu.addSeparator();
        menu.add(item("Rename", "shift F6", commands::rename));
        menu.add(item("Delete", "F8", () -> commands.delete(false)));
        menu.add(item("Pack...", "alt F5", () -> commands.pack(false)));
        menu.addSeparator();
        menu.add(item("Copy path to clipboard", null, () -> copyText(cursorPath())));
        menu.add(item("Change attributes...", null, commands::changeAttributes));
        menu.add(item("Properties", "alt ENTER", commands::properties));

        return menu;
    }

    @Override
    public @NotNull JPopupMenu createDirMenu(final @NotNull PanelTab tab) {
        final JPopupMenu menu = new JPopupMenu();
        final JMenu newMenu = new JMenu("New");
        newMenu.add(item("Folder", "F7", () -> commands.mkdir(false)));
        newMenu.add(item("File", "shift F4", commands::newFile));
        menu.add(newMenu);
        menu.addSeparator();
        menu.add(item("Terminal", null, () -> commands.openTerminal(tab.getDir())));
        menu.add(item("Explore", null, () -> commands.explore(tab.getDir())));
        menu.addSeparator();
        menu.add(item("Properties", null, () -> commands.properties(tab.getDir())));

        return menu;
    }

    @Override
    public @NotNull JPopupMenu createHotlistMenu(final @NotNull PanelTab tab) {
        final JPopupMenu menu = new JPopupMenu();
        final String current = tab.getDir().getLocation();
        for (final String dir : favorites) {
            final JMenuItem it = new JMenuItem(dir);
            it.addActionListener(e -> {
                if ((e.getModifiers() & ActionEvent.SHIFT_MASK) != 0) {
                    activePanel.addTab(dir, true, false);
                } else {
                    tab.navigate(dir);
                }
                tab.focusTable();
            });
            menu.add(it);
        }
        if (!favorites.isEmpty()) {
            menu.addSeparator();
        }
        menu.add(item("Add current directory  (Num +)", null, () -> addFavorite(current)));
        menu.add(item("Remove current directory  (Num -)", null, () -> removeFavorite(current)));
        menu.add(item("Configure...  (Num *)", null, this::configureFavorites));
        final MenuKeyListener numKeys = new MenuKeyListener() {
            @Override
            public void menuKeyPressed(final MenuKeyEvent e) {
                final Runnable action = e.getKeyCode() == KeyEvent.VK_ADD ? () -> addFavorite(current)
                    : e.getKeyCode() == KeyEvent.VK_SUBTRACT ? () -> removeFavorite(current)
                    : e.getKeyCode() == KeyEvent.VK_MULTIPLY ? MainFrame.this::configureFavorites : null;
                if (action != null) {
                    e.consume();
                    menu.setVisible(false);
                    action.run();
                }
            }

            @Override
            public void menuKeyReleased(final MenuKeyEvent e) {
                // only pressed is needed
            }

            @Override
            public void menuKeyTyped(final MenuKeyEvent e) {
                // only pressed is needed
            }
        };
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem) {
                ((JMenuItem) c).addMenuKeyListener(numKeys);
            }
        }

        return menu;
    }

    /** Keys active only in the file table (in the command line they mean something else). */
    @Override
    public void bindPanelKeys(final @NotNull PanelTab tab) {
        tab.bind("ENTER", () -> enter(tab, false));
        tab.bind("shift ENTER", () -> enter(tab, true));
        tab.bind("SPACE", () -> {
            if (commandLine.isEmpty()) {
                tab.toggleMarkStay();
            } else {
                commandLine.type(' ');
            }
        });
        tab.bind("DELETE", () -> commands.delete(false));
        tab.bind("shift DELETE", () -> commands.delete(true));
        tab.bind("TAB", this::switchPanel);
        tab.bind("ctrl I", this::switchPanel);
        tab.bind("ESCAPE", () -> {
            if (tab.hasQuickFilter()) {
                tab.resetFilters();
            } else {
                commandLine.clear();
            }
        });
        for (final String k : new String[] {"LEFT", "RIGHT", "shift LEFT", "shift RIGHT"}) {
            tab.bind(k, () -> commandLine.getField().requestFocusInWindow());
        }
        tab.bind("ctrl LEFT", () -> openInOtherPanel(leftPanel));
        tab.bind("ctrl RIGHT", () -> openInOtherPanel(rightPanel));
        tab.bind("ctrl UP", () -> openInNewTab(activePanel));
        tab.bind("ctrl shift UP", () -> openInNewTab(getOtherPanel()));
        tab.bind("ctrl DOWN", commandLine::editLast);
        tab.bind("ctrl C", () -> commands.copyToClipboard(false));
        tab.bind("ctrl INSERT", () -> commands.copyToClipboard(false));
        tab.bind("ctrl X", () -> commands.copyToClipboard(true));
        tab.bind("ctrl V", commands::paste);
        tab.bind("shift INSERT", commands::paste);
        tab.bind("ctrl T", () -> newTab(false));
        tab.bind("ctrl W", () -> activePanel.closeCurrentTab());
        tab.bind("ctrl shift W", () -> activePanel.closeOtherTabs());
        tab.bind("ctrl Y", commandLine::clear);
        tab.bind("ctrl E", () -> {
            commandLine.getField().requestFocusInWindow();
            commandLine.previousHistory();
        });
        tab.bind("ctrl ENTER", () -> commandLine.appendCursor(false));
        tab.bind("ctrl shift ENTER", () -> commandLine.appendCursor(true));
        tab.bind("ctrl J", () -> commandLine.appendCursor(false));
        tab.bind("ctrl shift J", () -> commandLine.appendCursor(true));
        tab.getTable().getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !quietRefresh && activePanel != null && tab == activePanel.getCurrentTab()) {
                updateQuickViews();
            }
        });
    }

    // ---- CommandLine.Host ----

    @Override
    public void execute(final @NotNull String command, final boolean keepOpen) {
        commands.execute(command, keepOpen);
    }

    @Override
    public void returnToPanel() {
        focusActive();
    }

    @Override
    public @NotNull String cursorName() {
        final VFile f = activePanel.getCurrentTab().getCursorFile();

        return f == null || f.isParentLink() ? "" : f.getName();
    }

    @Override
    public @NotNull String cursorPath() {
        final VFile f = activePanel.getCurrentTab().getCursorFile();

        return f == null || f.isParentLink() ? activePanel.getCurrentTab().getDir().getLocation() : f.getLocation();
    }

    // ---- panels ----

    public @NotNull FilePanel getOtherPanel() {
        return activePanel == leftPanel ? rightPanel : leftPanel;
    }

    public void setActivePanel(final @NotNull FilePanel panel) {
        activePanel = panel;
        leftPanel.setActive(panel == leftPanel);
        rightPanel.setActive(panel == rightPanel);
        updateTitleAndPrompt();
        updateQuickViews();
    }

    public void focusActive() {
        activePanel.getCurrentTab().focusTable();
    }

    public void refreshPanels() {
        leftPanel.refresh();
        rightPanel.refresh();
    }

    /** Directories open in the panels: {@link DirWatcher} watches them for changes. */
    private @NotNull Set<Path> watchedDirs() {
        final Set<Path> dirs = new HashSet<>();
        for (final FilePanel panel : List.of(leftPanel, rightPanel)) {
            final PanelTab tab = panel.getCurrentTab();
            if (!tab.isFeed() && tab.getDir().isLocal()) {
                dirs.add(tab.getDir().getHostPath());
            }
        }

        return dirs;
    }

    /** Re-read panels without losing cursor, scroll and rename; changed — only these directories, null — all. */
    private void refreshPanelsQuietly(final @Nullable Set<Path> changed) {
        if (changed != null && changed.isEmpty()) {
            return;
        }
        for (final FilePanel panel : List.of(leftPanel, rightPanel)) {
            final PanelTab tab = panel.getCurrentTab();
            if (changed != null && !(tab.getDir().isLocal() && changed.contains(tab.getDir().getHostPath()))) {
                continue;
            }
            final VFile before = tab.getCursorFile();
            quietRefresh = true;
            try {
                tab.refreshQuietly();
            } finally {
                quietRefresh = false;
            }
            if (panel == activePanel && !Objects.equals(before, tab.getCursorFile())) {
                updateQuickViews();
            }
        }
    }

    private void switchPanel() {
        final FilePanel other = getOtherPanel();
        if (other.isQuickView()) {
            return;
        }
        setActivePanel(other);
        focusActive();
    }

    private void updateTitleAndPrompt() {
        final PanelTab tab = activePanel.getCurrentTab();
        commandLine.setPrompt(tab.getDir().getLocation());
        setTitle(APP_TITLE + " - " + tab.getDir().getLocation());
    }

    /** Enter in a panel: a non-empty command line is executed, otherwise the object under the cursor is opened. */
    private void enter(final @NotNull PanelTab tab, final boolean shift) {
        if (!commandLine.isEmpty()) {
            final String cmd = commandLine.getField().getText().trim();
            commandLine.addToHistory(cmd);
            commandLine.clear();
            commands.execute(cmd, shift);

            return;
        }
        final VFile f = tab.getCursorFile();
        if (shift && f != null && f.isArchiveFile()) {
            commands.openWithSystem(f);

            return;
        }
        tab.openCursor();
    }

    /** Ctrl+←/→: arrow towards the active panel — open the other panel's directory in it, otherwise — the object under the cursor in the other panel. */
    private void openInOtherPanel(final @NotNull FilePanel arrowTarget) {
        final PanelTab src = activePanel.getCurrentTab();
        if (arrowTarget == activePanel) {
            openOtherPanelDir();

            return;
        }
        final PanelTab dst = arrowTarget.getCurrentTab();
        final VFile f = src.getCursorFile();
        if (src.isFeed() && f != null && !f.isParentLink()) {
            dst.goToFile(f);
        } else if (f != null && !f.isParentLink() && f.isDirectory()) {
            dst.navigate(f, null, true);
        } else if (f != null && f.isArchiveFile() && f.isLocal()) {
            dst.openArchive(f);
        } else {
            dst.navigate(src.getDir(), null, true);
        }
    }

    /** The active panel switches to the inactive panel's directory (same as Ctrl+arrow towards the active panel). */
    private void openOtherPanelDir() {
        activePanel.getCurrentTab().navigate(getOtherPanel().getCurrentTab().getDir(), null, true);
        focusActive();
    }

    /** Ctrl+↑ / Ctrl+Shift+↑: directory under the cursor — in a new tab (of this or the other panel). */
    private void openInNewTab(final @NotNull FilePanel panel) {
        final PanelTab src = activePanel.getCurrentTab();
        final VFile f = src.getCursorFile();
        String loc = src.getDir().getLocation();
        if (f != null && !f.isParentLink() && (f.isDirectory() || f.isArchiveFile() && f.isLocal())) {
            loc = f.isArchiveFile() ? f.getLocation() + VFile.ARCHIVE_SEPARATOR : f.getLocation();
        }
        panel.addTab(loc, true, false);
        if (panel != activePanel) {
            setActivePanel(panel);
        }
        focusActive();
    }

    private void newTab(final boolean background) {
        activePanel.addTab(activePanel.getCurrentTab().getDir().getLocation(), true, background);
    }

    private void swapPanels(final boolean all) {
        if (all) {
            FilePanel.swapAllTabs(leftPanel, rightPanel);
        } else {
            FilePanel.swapCurrentTabs(leftPanel, rightPanel);
        }
        setActivePanel(activePanel);
        focusActive();
    }

    // ---- views ----

    private void setThumbs(final boolean value) {
        thumbs = value;
        thumbsButton.setSelected(value);
        leftPanel.setThumbs(value);
        rightPanel.setThumbs(value);
    }

    private void setBrief(final boolean value) {
        brief = value;
        leftPanel.setBrief(value);
        rightPanel.setBrief(value);
    }

    private void toggleHidden() {
        showHidden = !showHidden;
        hiddenButton.setSelected(showHidden);
        refreshPanels();
    }

    private void sort(final int column) {
        activePanel.getCurrentTab().getTable().sortKeepingCursor(column);
    }

    /** Ctrl+Q: quick view in place of the other panel. */
    private void toggleQuickView() {
        final FilePanel other = getOtherPanel();
        if (quickView != null) {
            quickView.cancel();
            leftPanel.setQuickView(null);
            rightPanel.setQuickView(null);
            quickView = null;
            focusActive();

            return;
        }
        final ViewerPanel v = new ViewerPanel();
        quickView = v;
        other.setQuickView(v);
        updateQuickViews();
        focusActive();
    }

    /** Ctrl+Shift+Q: quick view in a separate window that follows the cursor. */
    private void toggleQuickWindow() {
        if (quickWindow != null && quickWindow.isDisplayable()) {
            quickWindow.dispose();
            quickWindow = null;
            quickWindowViewer = null;

            return;
        }
        final Window w = AppWindows.create(this, "Quick view");
        final ViewerPanel v = new ViewerPanel();
        AppWindows.container(w).getContentPane().add(v);
        Keys.bindWindow(AppWindows.container(w).getRootPane(), "ESCAPE", w::dispose);
        AppWindows.onClosed(w, v::cancel);
        quickWindow = w;
        quickWindowViewer = v;
        w.setVisible(true);
        updateQuickViews();
        toFront();
        focusActive();
    }

    private void updateQuickViews() {
        final VFile f = activePanel.getCurrentTab().getCursorFile();
        if (quickView != null) {
            quickView.load(f);
        }
        if (quickWindowViewer != null && quickWindow != null && quickWindow.isDisplayable()) {
            quickWindowViewer.load(f);
        }
    }

    // ---- favorites, history, tabs ----

    private void addFavorite(final @NotNull String dir) {
        if (!favorites.contains(dir)) {
            favorites.add(dir);
        }
    }

    private void removeFavorite(final @NotNull String dir) {
        favorites.remove(dir);
    }

    private void configureFavorites() {
        final JTextArea area = new JTextArea(String.join("\n", favorites), 15, 60);
        final int answer = JOptionPane.showConfirmDialog(this, new JScrollPane(area),
            "Directory hotlist (one directory per line)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            favorites.clear();
            for (final String line : area.getText().split("\n")) {
                if (!line.isBlank()) {
                    favorites.add(line.trim());
                }
            }
        }
        focusActive();
    }

    private void showHotlist() {
        final PanelTab tab = activePanel.getCurrentTab();
        showPopupAtTable(createHotlistMenu(tab), tab);
    }

    /** Alt+↓: directory history of the active tab (Shift+Enter — in a new tab). */
    private void showHistory() {
        final PanelTab tab = activePanel.getCurrentTab();
        final JPopupMenu menu = new JPopupMenu();
        final List<String> history = new ArrayList<>(tab.getHistory());
        for (int i = history.size() - 1; i >= 0; i--) {
            final String loc = history.get(i);
            final JMenuItem it = new JMenuItem(loc);
            it.addActionListener(e -> {
                if ((e.getModifiers() & ActionEvent.SHIFT_MASK) != 0) {
                    activePanel.addTab(loc, true, false);
                } else {
                    tab.goHistoryEntry(loc);
                }
                focusActive();
            });
            menu.add(it);
        }
        showPopupAtTable(menu, tab);
    }

    /** Ctrl+Shift+A: tab list of the active panel. */
    private void showTabList() {
        final JPopupMenu menu = new JPopupMenu();
        for (final PanelTab t : activePanel.getTabs()) {
            final JMenuItem it = new JMenuItem(t.getTitle() + "   " + t.getDir().getLocation());
            it.addActionListener(e -> activePanel.selectTab(t));
            menu.add(it);
        }
        showPopupAtTable(menu, activePanel.getCurrentTab());
    }

    private static void showPopupAtTable(final @NotNull JPopupMenu menu, final @NotNull PanelTab tab) {
        if (menu.getComponentCount() == 0) {
            menu.add(new JMenuItem("(empty)")).setEnabled(false);
        }
        final Rectangle r = tab.getTable().getVisibleRect();
        menu.show(tab.getTable(), r.x + 20, r.y + 10);
        SwingUtilities.invokeLater(() -> MenuSelectionManager.defaultManager().setSelectedPath(
            new MenuElement[] {menu, (MenuElement) menu.getComponent(0)}));
    }

    private void chooseFromTree() {
        final String dir = TreeDialog.choose(this, activePanel.getCurrentTab().getWorkingDirectory());
        if (dir != null) {
            activePanel.getCurrentTab().navigate(dir);
        }
        focusActive();
    }

    private void addPathToCommandLine() {
        commandLine.appendQuoted(activePanel.getCurrentTab().getDir().getLocation());
        commandLine.getField().requestFocusInWindow();
    }

    private static void copyText(final @NotNull String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    // ---- keyboard ----

    private void key(final @NotNull String stroke, final @NotNull Runnable action) {
        final KeyStroke ks = KeyStroke.getKeyStroke(stroke);
        if (ks == null) {
            throw new IllegalArgumentException("Bad key: " + stroke);
        }
        globalKeys.put(ks, action);
    }

    private void registerGlobalKeys() {
        registerFileKeys();
        registerViewKeys();
        registerNavigationKeys();
    }

    private void registerFileKeys() {
        key("F1", () -> HelpDialog.show(this));
        key("F2", () -> activePanel.getCurrentTab().reread());
        key("ctrl R", () -> activePanel.getCurrentTab().reread());
        key("F3", () -> commands.view(false));
        key("shift F3", () -> commands.view(true));
        key("alt F3", commands::viewExternal);
        key("alt shift F3", () -> commands.view(true));
        key("F4", commands::edit);
        key("shift F4", commands::newFile);
        key("F5", () -> commands.copyOrMove(false));
        key("shift F5", () -> commands.copySameDir(false));
        key("ctrl shift F5", commands::createLinks);
        key("F6", () -> commands.copyOrMove(true));
        key("shift F6", commands::rename);
        key("F7", () -> commands.mkdir(false));
        key("shift F7", () -> commands.mkdir(true));
        key("F8", () -> commands.delete(false));
        key("shift F8", () -> commands.delete(true));
        key("alt F5", () -> commands.pack(false));
        key("alt shift F5", () -> commands.pack(true));
        key("alt F6", commands::unpack);
        key("alt F9", commands::unpack);
        key("alt shift F6", commands::testArchives);
        key("alt shift F9", commands::testArchives);
        key("alt F7", commands::search);
        key("alt shift F7", commands::search);
        key("ctrl shift F3", commands::compareContent);
        key("shift F2", commands::compareLists);
        key("ctrl M", commands::multiRename);
        key("ctrl L", commands::occupiedSpace);
        key("alt ENTER", commands::properties);
        key("ctrl F9", commands::print);
        key("alt F4", this::exit);
        key("alt X", this::exit);
        key("shift ESCAPE", () -> setExtendedState(getExtendedState() | Frame.ICONIFIED));
    }

    private void registerViewKeys() {
        key("ctrl F1", () -> setBrief(true));
        key("ctrl F2", () -> {
            setBrief(false);
            setThumbs(false);
        });
        key("ctrl shift F1", () -> setThumbs(!thumbs));
        key("shift F1", this::showViewMenu);
        key("ctrl F3", () -> sort(FileTableModel.COL_NAME));
        key("ctrl F4", () -> sort(FileTableModel.COL_EXT));
        key("ctrl F5", () -> sort(FileTableModel.COL_DATE));
        key("ctrl F6", () -> sort(FileTableModel.COL_SIZE));
        key("ctrl F7", () -> sort(FileTableModel.UNSORTED));
        key("ctrl F10", () -> activePanel.getCurrentTab().resetFilters());
        key("ctrl F11", () -> activePanel.getCurrentTab().showProgramsOnly());
        key("ctrl F12", () -> activePanel.getCurrentTab().askFilter());
        key("ctrl H", this::toggleHidden);
        key("ctrl Q", this::toggleQuickView);
        key("ctrl shift Q", this::toggleQuickWindow);
        key("F9", () -> openMenu(activePanel == leftPanel ? 0 : menuBar.getMenuCount() - 1));
        key("F10", () -> openMenu(0));
        key("alt shift F11", this::focusToolBar);
        key("shift F9", () -> activePanel.getCurrentTab().showContextMenu());
        key("shift F10", () -> activePanel.getCurrentTab().showContextMenu());
        key("CONTEXT_MENU", () -> activePanel.getCurrentTab().showContextMenu());
    }

    private void registerNavigationKeys() {
        key("alt F1", leftPanel::showDrives);
        key("alt F2", rightPanel::showDrives);
        key("alt F8", commandLine::showHistoryPopup);
        key("alt F10", this::chooseFromTree);
        key("alt LEFT", () -> activePanel.getCurrentTab().goBack());
        key("alt RIGHT", () -> activePanel.getCurrentTab().goForward());
        key("alt DOWN", this::showHistory);
        key("alt shift DOWN", this::showHistory);
        key("ctrl D", this::showHotlist);
        key("ctrl P", this::addPathToCommandLine);
        key("ctrl U", () -> swapPanels(false));
        key("ctrl EQUALS", this::openOtherPanelDir);
        key("ctrl shift U", () -> swapPanels(true));
        key("ctrl TAB", () -> activePanel.switchTab(1));
        key("ctrl shift TAB", () -> activePanel.switchTab(-1));
        key("ctrl shift T", () -> newTab(true));
        key("ctrl shift A", this::showTabList);
    }

    /** Global shortcuts are intercepted before components while the main window is active and no menu is open. */
    private final class GlobalKeys implements KeyEventDispatcher {

        @Override
        public boolean dispatchKeyEvent(final KeyEvent e) {
            if (e.getID() != KeyEvent.KEY_PRESSED || !isActive() || MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
                return false;
            }
            final Component c = e.getComponent();
            if (c instanceof JComponent && Boolean.TRUE.equals(((JComponent) c).getClientProperty(FileTable.NO_GLOBAL_KEYS))) {
                return false;
            }
            if (c != null && SwingUtilities.getWindowAncestor(c) != MainFrame.this && c != MainFrame.this) {
                return false;
            }
            final Runnable action = globalKeys.get(KeyStroke.getKeyStrokeForEvent(e));
            if (action == null) {
                return false;
            }
            e.consume();
            action.run();

            return true;
        }
    }

    private void openMenu(final int index) {
        if (index >= 0 && index < menuBar.getMenuCount()) {
            menuBar.getMenu(index).doClick();
        }
    }

    private void focusToolBar() {
        for (final Component c : toolBar.getComponents()) {
            if (c instanceof AbstractButton && c.isEnabled()) {
                c.setFocusable(true);
                c.requestFocusInWindow();

                return;
            }
        }
    }

    private void showViewMenu() {
        final JPopupMenu menu = new JPopupMenu();
        menu.add(item("Brief (names only)", "ctrl F1", () -> setBrief(true)));
        menu.add(item("Full (details)", "ctrl F2", () -> {
            setBrief(false);
            setThumbs(false);
        }));
        menu.add(item("Thumbnails", "ctrl shift F1", () -> setThumbs(!thumbs)));
        menu.add(item("Show hidden files", "ctrl H", this::toggleHidden));
        showPopupAtTable(menu, activePanel.getCurrentTab());
    }

    // ---- menu, toolbar, F-keys ----

    private static @NotNull JMenuItem item(final @NotNull String text, final @Nullable String accel, final @NotNull Runnable action) {
        final JMenuItem it = new JMenuItem(text);
        if (accel != null) {
            it.setAccelerator(KeyStroke.getKeyStroke(accel));
        }
        it.addActionListener(e -> action.run());

        return it;
    }

    private void buildMenu() {
        final JMenu files = new JMenu("Files");
        files.setMnemonic('F');
        files.add(item("View", "F3", () -> commands.view(false)));
        files.add(item("Edit", "F4", commands::edit));
        files.add(item("Edit new file", "shift F4", commands::newFile));
        files.add(item("Copy", "F5", () -> commands.copyOrMove(false)));
        files.add(item("Rename/move", "F6", () -> commands.copyOrMove(true)));
        files.add(item("Rename in place", "shift F6", commands::rename));
        files.add(item("Multi-rename tool", "ctrl M", commands::multiRename));
        files.add(item("Delete", "F8", () -> commands.delete(false)));
        files.add(item("Delete permanently", "shift F8", () -> commands.delete(true)));
        files.add(item("Create symbolic links", "ctrl shift F5", commands::createLinks));
        files.addSeparator();
        files.add(item("Pack...", "alt F5", () -> commands.pack(false)));
        files.add(item("Unpack...", "alt F9", commands::unpack));
        files.add(item("Test archives", "alt shift F9", commands::testArchives));
        files.addSeparator();
        files.add(item("Compare by content...", "ctrl shift F3", commands::compareContent));
        files.add(item("Change attributes...", null, commands::changeAttributes));
        files.add(item("Properties", "alt ENTER", commands::properties));
        files.add(item("Calculate occupied space", "ctrl L", commands::occupiedSpace));
        files.add(item("Calculate all directory sizes", "alt shift ENTER", () -> activePanel.getCurrentTab().calculateAllSizes()));
        files.add(item("Print", "ctrl F9", commands::print));
        files.addSeparator();
        files.add(item("Exit", "alt F4", this::exit));
        menuBar.add(files);
        menuBar.add(buildMarkMenu());
        menuBar.add(buildCommandsMenu());
        menuBar.add(buildShowMenu());
        menuBar.add(buildConfigMenu());
        final JMenu help = new JMenu("Help");
        help.setMnemonic('H');
        help.add(item("Keyboard reference", "F1", () -> HelpDialog.show(this)));
        menuBar.add(help);
        setJMenuBar(menuBar);
    }

    private @NotNull JMenu buildMarkMenu() {
        final JMenu mark = new JMenu("Mark");
        mark.setMnemonic('M');
        mark.add(item("Select group...    Num +", null, () -> activePanel.getCurrentTab().markByMask(askMask("Select group"), true, false)));
        mark.add(item("Unselect group...    Num -", null, () -> activePanel.getCurrentTab().markByMask(askMask("Unselect group"), false, false)));
        mark.add(item("Select all", "ctrl A", () -> activePanel.getCurrentTab().markAll(true, false)));
        mark.add(item("Unselect all    Ctrl+Num -", null, () -> activePanel.getCurrentTab().markAll(false, false)));
        mark.add(item("Invert selection    Num *", null, () -> activePanel.getCurrentTab().invertMarks(false)));
        mark.add(item("Restore selection    Num /", null, () -> activePanel.getCurrentTab().restoreMarks()));
        mark.add(item("Select same extension    Alt+Num +", null, () -> activePanel.getCurrentTab().markByCursorExtension(true)));
        mark.addSeparator();
        mark.add(item("Compare directories", "shift F2", commands::compareLists));

        return mark;
    }

    private @NotNull String askMask(final @NotNull String title) {
        final Object v = JOptionPane.showInputDialog(this, "Mask:", title, JOptionPane.PLAIN_MESSAGE, null, null, "*.*");

        return v == null ? "" : v.toString();
    }

    private @NotNull JMenu buildCommandsMenu() {
        final JMenu cmd = new JMenu("Commands");
        cmd.setMnemonic('C');
        cmd.add(item("Search...", "alt F7", commands::search));
        cmd.add(item("Directory hotlist", "ctrl D", this::showHotlist));
        cmd.add(item("Directory history", "alt DOWN", this::showHistory));
        cmd.add(item("Directory tree", "alt F10", this::chooseFromTree));
        cmd.add(item("Branch view (flat list)", "ctrl B", () -> activePanel.getCurrentTab().toggleFlatView(false)));
        cmd.add(item("Quick filter", "ctrl S", () -> activePanel.getCurrentTab().openQuickBar(true, "")));
        cmd.addSeparator();
        cmd.add(item("Same directory as other panel", "ctrl EQUALS", this::openOtherPanelDir));
        cmd.add(item("Swap panels", "ctrl U", () -> swapPanels(false)));
        cmd.add(item("Swap panels with all tabs", "ctrl shift U", () -> swapPanels(true)));
        cmd.add(item("New tab", "ctrl T", () -> newTab(false)));
        cmd.add(item("Close tab", "ctrl W", () -> activePanel.closeCurrentTab()));
        cmd.add(item("Close other tabs", "ctrl shift W", () -> activePanel.closeOtherTabs()));
        cmd.add(item("Tab list", "ctrl shift A", this::showTabList));
        cmd.addSeparator();
        cmd.add(item("Command history", "alt F8", commandLine::showHistoryPopup));
        cmd.add(item("Refresh", "ctrl R", this::refreshPanels));

        return cmd;
    }

    private @NotNull JMenu buildShowMenu() {
        final JMenu show = new JMenu("Show");
        show.setMnemonic('S');
        show.add(item("Brief", "ctrl F1", () -> setBrief(true)));
        show.add(item("Full", "ctrl F2", () -> setBrief(false)));
        show.add(item("Thumbnails", "ctrl shift F1", () -> setThumbs(!thumbs)));
        show.add(item("Quick view", "ctrl Q", this::toggleQuickView));
        show.add(item("Quick view window", "ctrl shift Q", this::toggleQuickWindow));
        show.addSeparator();
        show.add(item("Sort by name", "ctrl F3", () -> sort(FileTableModel.COL_NAME)));
        show.add(item("Sort by extension", "ctrl F4", () -> sort(FileTableModel.COL_EXT)));
        show.add(item("Sort by date", "ctrl F5", () -> sort(FileTableModel.COL_DATE)));
        show.add(item("Sort by size", "ctrl F6", () -> sort(FileTableModel.COL_SIZE)));
        show.add(item("Unsorted", "ctrl F7", () -> sort(FileTableModel.UNSORTED)));
        show.addSeparator();
        show.add(item("All files", "ctrl F10", () -> activePanel.getCurrentTab().resetFilters()));
        show.add(item("Programs only", "ctrl F11", () -> activePanel.getCurrentTab().showProgramsOnly()));
        show.add(item("Custom filter...", "ctrl F12", () -> activePanel.getCurrentTab().askFilter()));
        show.add(item("Hidden files", "ctrl H", this::toggleHidden));

        return show;
    }

    private @NotNull JMenu buildConfigMenu() {
        final JMenu config = new JMenu("Configuration");
        config.setMnemonic('o');
        for (final String[] theme : Main.THEMES) {
            config.add(item("Theme: " + theme[0], null, () -> {
                Main.applyTheme(theme[0]);
                Settings.put("theme", theme[0]);
                leftPanel.setActive(activePanel == leftPanel);
                rightPanel.setActive(activePanel == rightPanel);
            }));
        }

        return config;
    }

    /** Double click on the panel divider puts it exactly in the middle. */
    private static void installDividerMouse(final @NotNull JSplitPane split) {
        if (!(split.getUI() instanceof BasicSplitPaneUI)) {
            return;
        }
        ((BasicSplitPaneUI) split.getUI()).getDivider().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    split.setDividerLocation(0.5);
                }
            }
        });
    }

    /**
     * In a narrow window the panels' minimum widths prevent moving the divider, and it got stuck wherever it happened to be:
     * there it stays in the middle both after resizing and after an attempt to move it.
     */
    private void keepDividerCentered(final @NotNull JSplitPane split) {
        // deferred: layout itself reports the divider position, and JSplitPane silently ignores a nested call from inside it
        final Runnable center = () -> SwingUtilities.invokeLater(() -> {
            final int middle = (split.getWidth() - split.getDividerSize()) / 2;
            if (getWidth() <= NARROW_WIDTH && middle > 0 && split.getDividerLocation() != middle) {
                split.setDividerLocation(middle);
            }
        });
        split.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                center.run();
            }
        });
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> center.run());
    }

    private void buildToolBar() {
        toolBar.setFloatable(false);
        tool(Icons.REFRESH, "Refresh (Ctrl+R)", this::refreshPanels);
        toolBar.addSeparator();
        thumbsButton.setSelected(thumbs);
        thumbsButton.setToolTipText("Thumbnails (Ctrl+Shift+F1)");
        thumbsButton.addActionListener(e -> setThumbs(thumbsButton.isSelected()));
        thumbsButton.setFocusable(false);
        toolBar.add(thumbsButton);
        hiddenButton.setSelected(showHidden);
        hiddenButton.setToolTipText("Show hidden files (Ctrl+H)");
        hiddenButton.addActionListener(e -> toggleHidden());
        hiddenButton.setFocusable(false);
        toolBar.add(hiddenButton);
        toolBar.addSeparator();
        tool(Icons.BACK, "Back (Alt+Left)", () -> activePanel.getCurrentTab().goBack());
        tool(Icons.FORWARD, "Forward (Alt+Right)", () -> activePanel.getCurrentTab().goForward());
        toolBar.addSeparator();
        tool(Icons.PACK, "Pack (Alt+F5)", () -> commands.pack(false));
        tool(Icons.UNPACK, "Unpack (Alt+F9)", commands::unpack);
        toolBar.addSeparator();
        tool(Icons.SEARCH, "Find files (Alt+F7)", commands::search);
        tool(Icons.COMPARE, "Compare by content (Ctrl+Shift+F3)", commands::compareContent);
        tool(Icons.SAME_DIR, "Same directory as other panel (Ctrl+=)", this::openOtherPanelDir);
        tool(Icons.SWAP, "Swap panels (Ctrl+U)", () -> swapPanels(false));
        tool(Icons.NEW_TAB, "New tab (Ctrl+T)", () -> newTab(false));
        tool(Icons.TERMINAL, "Terminal", () -> commands.openTerminal(activePanel.getCurrentTab().getDir()));
        Keys.bindAncestor(toolBar, "ESCAPE", this::focusActive);
    }

    private void tool(final @NotNull Icon icon, final @NotNull String tooltip, final @NotNull Runnable action) {
        final JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.addActionListener(e -> {
            action.run();
            if (!commandLine.getField().isFocusOwner()) {
                focusActive();
            }
        });
        toolBar.add(b);
    }

    private @NotNull JPanel buildFunctionKeys() {
        final JPanel bar = new JPanel(new GridLayout(1, 0, 2, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));
        fkey(bar, "F3 View", () -> commands.view(false));
        fkey(bar, "F4 Edit", commands::edit);
        fkey(bar, "F5 Copy", () -> commands.copyOrMove(false));
        fkey(bar, "F6 Move", () -> commands.copyOrMove(true));
        fkey(bar, "F7 NewFolder", () -> commands.mkdir(false));
        // Shift-click — like Shift+F8: delete bypassing the trash
        fkeyEvent(bar, "F8 Delete", e -> commands.delete((e.getModifiers() & ActionEvent.SHIFT_MASK) != 0));
        fkey(bar, "Alt+F4 Exit", this::exit);

        return bar;
    }

    private void fkey(final @NotNull JPanel bar, final @NotNull String text, final @NotNull Runnable action) {
        fkeyEvent(bar, text, e -> action.run());
    }

    /** A button that cares about click modifiers. */
    private void fkeyEvent(final @NotNull JPanel bar, final @NotNull String text, final @NotNull Consumer<ActionEvent> action) {
        final JButton b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 4, 2, 4));
        b.addActionListener(e -> {
            action.accept(e);
            focusActive();
        });
        bar.add(b);
    }

    // ---- settings ----

    private void restoreBounds() {
        final int w = Settings.getInt("window.w", 1200);
        final int h = Settings.getInt("window.h", 780);
        setSize(w, h);
        final int x = Settings.getInt("window.x", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE) {
            setLocationRelativeTo(null);
        } else {
            setLocation(x, Settings.getInt("window.y", 0));
        }
        if (Settings.getBoolean("window.max", false)) {
            setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }

    /**
     * Cmd+Q and Quit in the macOS application menu: without a handler the JVM calls System.exit right away, bypassing windowClosing,
     * so settings are not saved and the user is not asked about the operation queue.
     */
    private void installQuitHandler() {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            return;
        }
        Desktop.getDesktop().setQuitHandler((e, response) -> SwingUtilities.invokeLater(() -> {
            exit();
            // we get here only if the user declined to exit
            response.cancelQuit();
        }));
    }

    private void saveSettings() {
        final int state = getExtendedState();
        final boolean max = (state & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        Settings.putBoolean("window.max", max);
        // a minimized window has service coordinates (-32000 on Windows)
        if (!max && (state & Frame.ICONIFIED) == 0) {
            Settings.putInt("window.x", getX());
            Settings.putInt("window.y", getY());
            Settings.putInt("window.w", getWidth());
            Settings.putInt("window.h", getHeight());
        }
        Settings.putList("left.tabs", tabLocations(leftPanel));
        Settings.putInt("left.tab", leftPanel.getSelectedTabIndex());
        Settings.putList("right.tabs", tabLocations(rightPanel));
        Settings.putInt("right.tab", rightPanel.getSelectedTabIndex());
        Settings.put("active", activePanel == rightPanel ? "right" : "left");
        Settings.putBoolean("showHidden", showHidden);
        Settings.putBoolean("thumbs", thumbs);
        Settings.putBoolean("brief", brief);
        Settings.putList("favorites", favorites);
        Settings.putList("cmd.history", commandLine.getHistory());
        Settings.flush();
    }

    private static @NotNull List<String> tabLocations(final @NotNull FilePanel panel) {
        final List<String> result = new ArrayList<>();
        for (final PanelTab t : panel.getTabs()) {
            result.add(t.getDir().getLocation());
        }

        return result;
    }

    private void exit() {
        if (JobRunner.getQueuedCount() > 0) {
            final int a = JOptionPane.showConfirmDialog(this, "There are queued operations. Exit anyway?", "Exit",
                JOptionPane.YES_NO_OPTION);
            if (a != JOptionPane.YES_OPTION) {
                return;
            }
        }
        saveSettings();
        dispose();
        System.exit(0);
    }

    private static @NotNull BufferedImage appIcon() {
        final BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        Icons.FOLDER.paintIcon(null, g, 0, 0);
        g.dispose();

        return img;
    }

}
