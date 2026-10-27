package tcm.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import tcm.core.VFile;
import tcm.core.Vfs;

/**
 * One of the two panels: drive selector, free space, directory tabs; can temporarily show quick view (Ctrl+Q).
 */
public final class FilePanel extends JPanel {

    private static final String CARD_TABS = "tabs";
    private static final String CARD_QUICK = "quick";
    /** Long paths do not bloat a tab: the title is truncated, the full path is in the tooltip. */
    private static final int MAX_TAB_WIDTH = 200;

    @Getter
    private final @NotNull PanelHost host;
    private final JComboBox<String> drives = new JComboBox<>();
    private final JLabel freeSpace = new JLabel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private @Nullable Component quickView;
    private final MouseAdapter tabAreaMouse = new MouseAdapter() {
        @Override
        public void mouseClicked(final MouseEvent e) {
            final Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), tabs);
            if (SwingUtilities.isMiddleMouseButton(e)) {
                // middle button (wheel) on a tab closes it, as in browsers
                final int index = tabs.indexAtLocation(p.x, p.y);
                if (index >= 0) {
                    closeTab(index);
                }

                return;
            }
            if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && isFreeTabArea(p.x, p.y)) {
                duplicateCurrentTab();
            }
        }
    };
    private boolean updatingDrives;

    public FilePanel(final @NotNull PanelHost host, final @NotNull List<String> locations, final int selected) {
        super(new BorderLayout());
        this.host = host;
        add(createDriveBar(), BorderLayout.NORTH);
        tabs.putClientProperty("JTabbedPane.tabClosable", Boolean.TRUE);
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", (IntConsumer) this::closeTab);
        tabs.putClientProperty("JTabbedPane.hideTabAreaWithOneTab", Boolean.TRUE);
        tabs.setFocusable(false);
        // in WRAP mode long titles lay tabs out in several rows; in SCROLL there is one strip scrolled by the wheel
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty("JTabbedPane.maximumTabWidth", MAX_TAB_WIDTH);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() instanceof PanelTab) {
                final PanelTab tab = getCurrentTab();
                tab.setThumbs(host.isThumbs());
                tab.setBrief(host.isBrief());
                tabUpdated(tab);
                host.panelChanged(this);
            }
        });
        installTabAreaMouse();
        // theme change recreates the UI together with the tab scroll pane
        tabs.addPropertyChangeListener("UI", e -> installTabAreaMouse());
        center.add(tabs, CARD_TABS);
        add(center, BorderLayout.CENTER);
        for (final String loc : locations) {
            addTab(loc, false, false);
        }
        if (tabs.getTabCount() == 0) {
            addTab(System.getProperty("user.home"), false, false);
        }
        tabs.setSelectedIndex(Math.max(0, Math.min(selected, tabs.getTabCount() - 1)));
    }

    public @NotNull PanelTab getCurrentTab() {
        return (PanelTab) tabs.getSelectedComponent();
    }

    public @NotNull List<PanelTab> getTabs() {
        final List<PanelTab> result = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            result.add((PanelTab) tabs.getComponentAt(i));
        }

        return result;
    }

    public int getSelectedTabIndex() {
        return tabs.getSelectedIndex();
    }

    public void selectTab(final @NotNull PanelTab tab) {
        tabs.setSelectedComponent(tab);
        tab.focusTable();
    }

    /** New tab; background — do not switch to it (Ctrl+Shift+T). */
    public @Nullable PanelTab addTab(final @NotNull String location, final boolean select, final boolean background) {
        VFile dir;
        try {
            dir = Vfs.resolve(location);
        } catch (final IOException e) {
            try {
                dir = Vfs.resolve(System.getProperty("user.home"));
            } catch (final IOException e2) {
                return null;
            }
        }
        final PanelTab tab = new PanelTab(this, dir);
        tab.setThumbs(host.isThumbs());
        final int at = tabs.getTabCount() == 0 ? 0 : tabs.getSelectedIndex() + 1;
        tabs.insertTab(tab.getTitle(), null, tab, dir.getLocation(), at);
        if (select && !background) {
            selectTab(tab);
        }

        return tab;
    }

    /** Double click on empty space of the tab strip: duplicate of the current tab next to it. */
    public void duplicateCurrentTab() {
        addTab(getCurrentTab().getDir().getLocation(), true, false);
    }

    /**
     * A click is received by the deepest component with a mouse listener, and without listeners the event goes up to JTabbedPane.
     * So a listener is attached to the tab strip's service components only if they already have their own:
     * otherwise the tab scroll pane would grab clicks for itself, and selecting and closing tabs would stop working.
     */
    private void installTabAreaMouse() {
        tabs.removeMouseListener(tabAreaMouse);
        tabs.addMouseListener(tabAreaMouse);
        for (final Component c : tabs.getComponents()) {
            if (!(c instanceof PanelTab)) {
                installMouseDeep(c);
            }
        }
    }

    private void installMouseDeep(final @NotNull Component c) {
        // scroll arrows and tab list: double click on them is not "empty space"
        if (c instanceof AbstractButton) {
            return;
        }
        c.removeMouseListener(tabAreaMouse);
        if (c.getMouseListeners().length > 0) {
            c.addMouseListener(tabAreaMouse);
        }
        if (c instanceof Container) {
            for (final Component child : ((Container) c).getComponents()) {
                installMouseDeep(child);
            }
        }
    }

    /** A point on the tab strip, but not on a tab and not in the content. */
    private boolean isFreeTabArea(final int x, final int y) {
        if (tabs.indexAtLocation(x, y) >= 0) {
            return false;
        }
        final Component content = tabs.getSelectedComponent();

        return content == null || !content.getBounds().contains(x, y);
    }

    public void closeTab(final int index) {
        if (tabs.getTabCount() <= 1 || index < 0) {
            return;
        }
        tabs.removeTabAt(index);
        getCurrentTab().focusTable();
    }

    public void closeCurrentTab() {
        closeTab(tabs.getSelectedIndex());
    }

    /** Ctrl+Shift+W: close all tabs except the active one. */
    public void closeOtherTabs() {
        final PanelTab keep = getCurrentTab();
        for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
            if (tabs.getComponentAt(i) != keep) {
                tabs.removeTabAt(i);
            }
        }
        keep.focusTable();
    }

    public void switchTab(final int delta) {
        final int n = tabs.getTabCount();
        if (n > 1) {
            tabs.setSelectedIndex((tabs.getSelectedIndex() + delta + n) % n);
            getCurrentTab().focusTable();
        }
    }

    /** Ctrl+U: swap active tabs between panels (with history and selection). */
    public static void swapCurrentTabs(final @NotNull FilePanel a, final @NotNull FilePanel b) {
        final int ia = a.tabs.getSelectedIndex();
        final int ib = b.tabs.getSelectedIndex();
        final PanelTab ta = a.getCurrentTab();
        final PanelTab tb = b.getCurrentTab();
        a.tabs.removeTabAt(ia);
        b.tabs.removeTabAt(ib);
        a.insertMoved(tb, ia);
        b.insertMoved(ta, ib);
        a.tabs.setSelectedComponent(tb);
        b.tabs.setSelectedComponent(ta);
    }

    /** Ctrl+Shift+U: swap all tabs. */
    public static void swapAllTabs(final @NotNull FilePanel a, final @NotNull FilePanel b) {
        final List<PanelTab> ta = a.getTabs();
        final List<PanelTab> tb = b.getTabs();
        final int sa = a.tabs.getSelectedIndex();
        final int sb = b.tabs.getSelectedIndex();
        a.tabs.removeAll();
        b.tabs.removeAll();
        for (final PanelTab t : tb) {
            a.insertMoved(t, a.tabs.getTabCount());
        }
        for (final PanelTab t : ta) {
            b.insertMoved(t, b.tabs.getTabCount());
        }
        a.tabs.setSelectedIndex(sb);
        b.tabs.setSelectedIndex(sa);
    }

    private void insertMoved(final @NotNull PanelTab tab, final int index) {
        tab.setOwner(this);
        tabs.insertTab(tab.getTitle(), null, tab, tab.getDir().getLocation(), Math.min(index, tabs.getTabCount()));
    }

    public void setActive(final boolean active) {
        for (final PanelTab tab : getTabs()) {
            tab.setActive(active && tab == getCurrentTab());
        }
    }

    public void setThumbs(final boolean value) {
        for (final PanelTab tab : getTabs()) {
            tab.setThumbs(value);
        }
    }

    public void setBrief(final boolean value) {
        for (final PanelTab tab : getTabs()) {
            tab.setBrief(value);
        }
    }

    public void refresh() {
        getCurrentTab().refresh();
    }

    /** Alt+F1/Alt+F2: drop down the drive list. */
    public void showDrives() {
        drives.requestFocusInWindow();
        drives.showPopup();
    }

    /** Quick view in place of the tabs (null — restore the tabs). */
    public void setQuickView(final @Nullable Component view) {
        if (quickView != null) {
            center.remove(quickView);
        }
        quickView = view;
        if (view != null) {
            center.add(view, CARD_QUICK);
            cards.show(center, CARD_QUICK);
        } else {
            cards.show(center, CARD_TABS);
        }
        center.revalidate();
        center.repaint();
    }

    public boolean isQuickView() {
        return quickView != null;
    }

    /** Called by a tab after a directory change: tab title, drive, free space. */
    void tabUpdated(final @NotNull PanelTab tab) {
        final int idx = tabs.indexOfComponent(tab);
        if (idx >= 0) {
            tabs.setTitleAt(idx, tab.getTitle());
            tabs.setToolTipTextAt(idx, tab.getDir().getLocation());
        }
        if (idx < 0 || tab != tabs.getSelectedComponent()) {
            return;
        }
        freeSpace.setText(Vfs.freeSpaceText(tab.getDir()));
        updateDriveSelection(tab.getDir());
        host.panelChanged(this);
    }

    private @NotNull JPanel createDriveBar() {
        final JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        for (final File root : File.listRoots()) {
            drives.addItem(root.getPath());
        }
        drives.addItem("~");
        drives.setToolTipText("Drive / root (Alt+F1 / Alt+F2)");
        drives.addActionListener(e -> {
            if (updatingDrives || drives.getSelectedItem() == null) {
                return;
            }
            final String item = (String) drives.getSelectedItem();
            getCurrentTab().navigate("~".equals(item) ? System.getProperty("user.home") : item);
            getCurrentTab().focusTable();
        });
        bar.add(drives, BorderLayout.WEST);
        bar.add(freeSpace, BorderLayout.CENTER);
        final JPanel nav = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        final JButton root = navButton("\\", "Go to root (Ctrl+\\)");
        root.addActionListener(e -> getCurrentTab().goRoot());
        final JButton up = navButton("..", "Go to parent directory (Backspace)");
        up.addActionListener(e -> getCurrentTab().goUp());
        final JButton home = navButton("~", "Go to home directory");
        home.addActionListener(e -> getCurrentTab().navigate(System.getProperty("user.home")));
        nav.add(root);
        nav.add(up);
        nav.add(home);
        bar.add(nav, BorderLayout.EAST);

        return bar;
    }

    private static @NotNull JButton navButton(final @NotNull String text, final @NotNull String tooltip) {
        final JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.setMargin(new Insets(0, 6, 0, 6));
        b.putClientProperty("JButton.buttonType", "toolBarButton");

        return b;
    }

    private void updateDriveSelection(final @NotNull VFile dir) {
        final Path rootPath = dir.getHostPath().getRoot();
        final String root = rootPath == null ? "/" : rootPath.toString();
        updatingDrives = true;
        try {
            final String home = System.getProperty("user.home");
            final String loc = dir.getLocation();
            drives.setSelectedItem(loc.equals(home) || loc.startsWith(home + File.separator) ? "~" : root);
        } finally {
            updatingDrives = false;
        }
    }
}
