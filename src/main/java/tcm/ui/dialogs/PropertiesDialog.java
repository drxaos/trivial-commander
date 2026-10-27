package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;

import tcm.core.TreeStats;
import tcm.core.VFile;
import tcm.util.TextUtil;

/**
 * Properties (Alt+Enter) and disk usage calculation (Ctrl+L). Directory size is computed in the background.
 */
public final class PropertiesDialog extends JDialog {

    private final JLabel size = new JLabel("calculating...");
    private final JLabel counts = new JLabel(" ");
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private int row;

    private PropertiesDialog(final @NotNull Window owner, final @NotNull List<VFile> files, final @NotNull String title) {
        super(owner, title, ModalityType.MODELESS);
        final JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        if (files.size() == 1) {
            describeSingle(grid, files.get(0));
        } else {
            add(grid, "Objects:", files.size() + " selected");
        }
        add(grid, "Size:", size);
        add(grid, "Contains:", counts);
        getContentPane().add(grid, BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(Dialogs.button("Close", this::dispose));
        getContentPane().add(buttons, BorderLayout.SOUTH);
        Dialogs.bindEscape(this);
        Dialogs.bindKey(this, "ENTER", this::dispose);
        pack();
        setLocationRelativeTo(owner);
        calculate(files);
    }

    public static void show(final @NotNull Window owner, final @NotNull List<VFile> files, final @NotNull String title) {
        if (!files.isEmpty()) {
            new PropertiesDialog(owner, files, title).setVisible(true);
        }
    }

    private void describeSingle(final @NotNull JPanel grid, final @NotNull VFile f) {
        add(grid, "Name:", field(f.getName()));
        add(grid, "Location:", field(f.getLocation()));
        add(grid, "Type:", f.isDirectory() ? "Directory" : f.getExtension().isEmpty() ? "File" : f.getExtension().toUpperCase(Locale.ROOT) + " file");
        add(grid, "Modified:", f.getModified() > 0 ? TextUtil.formatDate(f.getModified()) : "");
        if (f.isInArchive()) {
            add(grid, "Archive:", field(f.getHostPath().toString()));

            return;
        }
        add(grid, "Attributes:", f.getAttrs());
        final Path p = f.getHostPath();
        try {
            add(grid, "Owner:", String.valueOf(Files.getOwner(p, LinkOption.NOFOLLOW_LINKS)));
            if (Files.isSymbolicLink(p)) {
                add(grid, "Link target:", field(String.valueOf(Files.readSymbolicLink(p))));
            }
            add(grid, "Created:", TextUtil.formatDate(((FileTime) Files.getAttribute(p, "creationTime")).toMillis()));
        } catch (final Exception e) {
            // some attributes are unavailable on this FS
        }
    }

    private static @NotNull JTextField field(final @NotNull String text) {
        final JTextField f = new JTextField(text, 40);
        f.setEditable(false);
        f.setBorder(BorderFactory.createEmptyBorder());

        return f;
    }

    private void add(final @NotNull JPanel grid, final @NotNull String label, final @NotNull Object value) {
        final GridBagConstraints c = new GridBagConstraints();
        c.gridy = row++;
        c.insets = new Insets(2, 0, 2, 8);
        c.anchor = GridBagConstraints.WEST;
        grid.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(value instanceof Component ? (Component) value : new JLabel(String.valueOf(value)), c);
    }

    private void calculate(final @NotNull List<VFile> files) {
        final Thread t = new Thread(() -> {
            final TreeStats st = TreeStats.of(files, cancelled::get);
            SwingUtilities.invokeLater(() -> {
                size.setText(TextUtil.groupDigits(st.getBytes()) + " bytes (" + TextUtil.shortSize(st.getBytes()) + ")");
                counts.setText(TextUtil.groupDigits(st.getFiles()) + " file(s), " + TextUtil.groupDigits(st.getDirs()) + " dir(s)");
                pack();
            });
        }, "properties-size");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void dispose() {
        cancelled.set(true);
        super.dispose();
    }
}
