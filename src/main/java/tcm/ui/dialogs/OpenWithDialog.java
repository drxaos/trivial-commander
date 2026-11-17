package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.OpenWith;
import tcm.ui.SystemIcons;
import tcm.util.Keys;
import tcm.util.Placement;

/**
 * "Open with": the applications registered for the file's type, the default one first; "All applications" switches to every
 * installed one. Keyboard first: the list has the focus, arrows move, typing jumps to a name, Enter opens, Esc cancels.
 * The lists and the icons are read in the background: on macOS asking Launch Services takes a noticeable moment.
 */
public final class OpenWithDialog extends JDialog {

    private static final int MAX_NAME = 60;
    /** Keeps the names lined up in rows whose application has no icon. */
    private static final Icon BLANK = new Icon() {
        @Override
        public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
            // nothing: a gap of the icon's size
        }

        @Override
        public int getIconWidth() {
            return SystemIcons.SIZE;
        }

        @Override
        public int getIconHeight() {
            return SystemIcons.SIZE;
        }
    };

    private final @NotNull OpenWith openWith;
    private final @NotNull Path file;
    private final DefaultListModel<OpenWith.App> model = new DefaultListModel<>();
    private final JList<OpenWith.App> list = new JList<>(model);
    private final JCheckBox allApps = new JCheckBox("All applications");
    private final JLabel hint = new JLabel(" ");
    /** Icon value → icon, null — it has none; filled on the EDT only. */
    private final Map<String, Icon> icons = new HashMap<>();
    private @Nullable List<OpenWith.App> recommended;
    private @Nullable List<OpenWith.App> all;
    private @Nullable SwingWorker<?, ?> listLoader;
    private @Nullable SwingWorker<?, ?> iconLoader;
    private @Nullable OpenWith.App result;
    /** The app selected when the lists were switched: the list being loaded clears the selection, the new one restores it. */
    private @Nullable String keptId;

    private OpenWithDialog(final @NotNull Window owner, final @NotNull OpenWith openWith, final @NotNull Path file) {
        super(owner, "Open with", ModalityType.APPLICATION_MODAL);
        this.openWith = openWith;
        this.file = file;
        final JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        content.add(new JLabel("Open \"" + shorten(String.valueOf(file.getFileName())) + "\" with:"), BorderLayout.NORTH);
        content.add(createList(), BorderLayout.CENTER);
        final JPanel options = new JPanel(new BorderLayout(12, 0));
        allApps.setMnemonic('A');
        allApps.addActionListener(e -> showList());
        options.add(allApps, BorderLayout.WEST);
        options.add(hint, BorderLayout.CENTER);
        content.add(options, BorderLayout.SOUTH);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton ok = Dialogs.button("OK", this::accept);
        buttons.add(ok);
        buttons.add(Dialogs.button("Cancel", this::dispose));
        getContentPane().add(content, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        Dialogs.bindEscape(this);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(final WindowEvent e) {
                list.requestFocusInWindow();
            }
        });
        pack();
        Placement.center(this, owner);
        showList();
    }

    /** The application chosen for the file, or null when cancelled. */
    public static @Nullable OpenWith.App choose(final @NotNull Window owner, final @NotNull OpenWith openWith, final @NotNull Path file) {
        final OpenWithDialog dialog = new OpenWithDialog(owner, openWith, file);
        dialog.setVisible(true);

        return dialog.result;
    }

    @Override
    public void dispose() {
        cancel(listLoader);
        cancel(iconLoader);
        super.dispose();
    }

    private @NotNull JScrollPane createList() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(14);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int index, final boolean selected,
                final boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                final OpenWith.App app = (OpenWith.App) value;
                setText(app.isDefaultApp() ? app.getName() + "  (default)" : app.getName());
                final Icon icon = app.getIcon() == null ? null : icons.get(app.getIcon());
                setIcon(icon == null ? BLANK : icon);

                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() == 2 && list.locationToIndex(e.getPoint()) >= 0) {
                    accept();
                }
            }
        });
        Keys.bind(list, JComponent.WHEN_FOCUSED, "ENTER", this::accept);
        final JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(440, 320));

        return scroll;
    }

    /** Shows the list the check box asks for, loading it on first use. */
    private void showList() {
        final OpenWith.App selected = list.getSelectedValue();
        if (selected != null) {
            keptId = selected.getId();
        }
        final boolean wantAll = allApps.isSelected();
        final List<OpenWith.App> apps = wantAll ? all : recommended;
        if (apps != null) {
            fill(apps, wantAll);

            return;
        }
        model.clear();
        hint.setText("Loading...");
        cancel(listLoader);
        listLoader = load(wantAll ? openWith::all : () -> openWith.recommended(file), wantAll);
        list.requestFocusInWindow();
    }

    private @NotNull SwingWorker<?, ?> load(final @NotNull Supplier<List<OpenWith.App>> source, final boolean isAll) {
        final SwingWorker<List<OpenWith.App>, Void> w = new SwingWorker<>() {
            @Override
            protected List<OpenWith.App> doInBackground() {
                return source.get();
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                List<OpenWith.App> apps;
                try {
                    apps = get();
                } catch (final InterruptedException | ExecutionException e) {
                    // the lookup failed: an empty list, "All applications" still works
                    apps = List.of();
                }
                if (isAll) {
                    all = apps;
                } else {
                    recommended = apps;
                }
                if (allApps.isSelected() == isAll) {
                    fill(apps, isAll);
                }
            }
        };
        w.execute();

        return w;
    }

    /** Puts the apps into the list, keeping the selected app selected if it is there too, and starts loading their icons. */
    private void fill(final @NotNull List<OpenWith.App> apps, final boolean isAll) {
        model.clear();
        int index = 0;
        for (int i = 0; i < apps.size(); i++) {
            model.addElement(apps.get(i));
            if (apps.get(i).getId().equals(keptId)) {
                index = i;
            }
        }
        if (!apps.isEmpty()) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
        hint.setText(apps.isEmpty() ? (isAll ? "No applications found" : "No associated applications") : " ");
        list.requestFocusInWindow();
        loadIcons(apps);
    }

    /** Icons of the apps not seen yet, in list order; each row is repainted as its icon arrives. */
    private void loadIcons(final @NotNull List<OpenWith.App> apps) {
        final Set<String> wanted = new LinkedHashSet<>();
        for (final OpenWith.App app : apps) {
            if (app.getIcon() != null && !icons.containsKey(app.getIcon())) {
                wanted.add(app.getIcon());
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        cancel(iconLoader);
        final SwingWorker<Void, Object[]> w = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (final String name : wanted) {
                    if (isCancelled()) {
                        break;
                    }
                    publish(new Object[] {name, SystemIcons.appIcon(name)});
                }

                return null;
            }

            @Override
            protected void process(final List<Object[]> chunks) {
                for (final Object[] pair : chunks) {
                    icons.put((String) pair[0], (Icon) pair[1]);
                }
                list.repaint();
            }
        };
        iconLoader = w;
        w.execute();
    }

    private void accept() {
        final OpenWith.App app = list.getSelectedValue();
        if (app != null) {
            result = app;
            dispose();
        }
    }

    private static void cancel(final @Nullable SwingWorker<?, ?> w) {
        if (w != null) {
            w.cancel(false);
        }
    }

    private static @NotNull String shorten(final @NotNull String name) {
        if (name.length() <= MAX_NAME) {
            return name;
        }

        return name.substring(0, MAX_NAME / 2 - 1) + "…" + name.substring(name.length() - MAX_NAME / 2);
    }
}
