package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.util.Keys;

/**
 * Directory tree (Alt+F10, F10 in dialogs): subdirectories are loaded on expansion; Enter — choose the directory.
 */
public final class TreeDialog extends JDialog {

    private static final String LOADING = "...";

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Computer");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private @Nullable String result;

    private TreeDialog(final @NotNull Window owner, final @NotNull Path start) {
        super(owner, "Directory tree", ModalityType.APPLICATION_MODAL);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(final TreeExpansionEvent e) {
                load((DefaultMutableTreeNode) e.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(final TreeExpansionEvent e) {
                // collapsed branches are left alone
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() == 2) {
                    choose();
                }
            }
        });
        Keys.bind(tree, JComponent.WHEN_FOCUSED, "ENTER", this::choose);
        Dialogs.bindEscape(this);
        Dialogs.bindKey(this, "F2", () -> rescan(start));
        Dialogs.bindKey(this, "ctrl R", () -> rescan(start));
        final JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(460, 520));
        getContentPane().add(scroll, BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton ok = Dialogs.button("OK", this::choose);
        buttons.add(ok);
        buttons.add(Dialogs.button("Cancel", this::dispose));
        getContentPane().add(buttons, BorderLayout.SOUTH);
        rescan(start);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Selected path or null. */
    public static @Nullable String choose(final @NotNull Window owner, final @NotNull Path start) {
        final TreeDialog d = new TreeDialog(owner, start);
        d.setVisible(true);

        return d.result;
    }

    private void rescan(final @NotNull Path start) {
        root.removeAllChildren();
        for (final File r : File.listRoots()) {
            root.add(placeholder(r.toPath()));
        }
        model.reload();
        select(start.toAbsolutePath().normalize());
        tree.requestFocusInWindow();
    }

    private static @NotNull DefaultMutableTreeNode placeholder(final @NotNull Path p) {
        final DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Entry(p));
        node.add(new DefaultMutableTreeNode(LOADING));

        return node;
    }

    private void load(final @NotNull DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof Entry) || node.getChildCount() != 1
            || !LOADING.equals(((DefaultMutableTreeNode) node.getFirstChild()).getUserObject())) {
            return;
        }
        node.removeAllChildren();
        final List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(((Entry) node.getUserObject()).path, Files::isDirectory)) {
            for (final Path p : ds) {
                dirs.add(p);
            }
        } catch (final IOException e) {
            // no access — the branch is empty
        }
        dirs.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.getFileName()), String.valueOf(b.getFileName())));
        for (final Path p : dirs) {
            node.add(placeholder(p));
        }
        model.nodeStructureChanged(node);
    }

    private void select(final @NotNull Path target) {
        DefaultMutableTreeNode node = root;
        for (int i = 0; i < node.getChildCount(); i++) {
            final DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (target.startsWith(((Entry) child.getUserObject()).path)) {
                node = child;
                break;
            }
        }
        boolean progressed = node != root;
        while (progressed) {
            load(node);
            progressed = false;
            for (int i = 0; i < node.getChildCount(); i++) {
                final DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                if (child.getUserObject() instanceof Entry && target.startsWith(((Entry) child.getUserObject()).path)) {
                    node = child;
                    progressed = true;
                    break;
                }
            }
        }
        final TreePath tp = new TreePath(node.getPath());
        tree.setSelectionPath(tp);
        tree.scrollPathToVisible(tp);
    }

    private void choose() {
        final TreePath tp = tree.getSelectionPath();
        if (tp != null && ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject() instanceof Entry) {
            result = ((Entry) ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject()).path.toString();
            dispose();
        }
    }

    private static final class Entry {

        private final @NotNull Path path;

        private Entry(final @NotNull Path path) {
            this.path = path;
        }

        @Override
        public String toString() {
            final Path name = path.getFileName();

            return name == null ? path.toString() : name.toString();
        }
    }
}
