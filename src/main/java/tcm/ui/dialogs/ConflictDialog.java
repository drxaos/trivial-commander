package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.apps.CompareWindow;
import tcm.apps.ViewerWindow;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.ops.Decision;
import tcm.util.TextUtil;

/**
 * Overwrite confirmation: Enter — overwrite, F3/Shift+F3 — view target/source, Ctrl+F3 — compare,
 * Ctrl+C — copy details, Esc — cancel the operation.
 */
public final class ConflictDialog extends JDialog {

    private Decision decision = Decision.CANCEL;

    private ConflictDialog(final @Nullable Window owner, final @NotNull String source, final @NotNull String target) {
        super(owner, "File exists", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        final VFile src = statOrNull(source);
        final VFile dst = statOrNull(target);
        final JPanel info = new JPanel(new GridLayout(0, 1, 0, 2));
        info.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        info.add(new JLabel("Overwrite target file?"));
        info.add(new JLabel(" "));
        info.add(new JLabel(target));
        info.add(new JLabel(describe(dst)));
        info.add(new JLabel(" "));
        info.add(new JLabel("with source file"));
        info.add(new JLabel(source));
        info.add(new JLabel(describe(src)));
        info.add(new JLabel(" "));
        info.add(new JLabel("F3 view target, Shift+F3 view source, Ctrl+F3 compare, Ctrl+C copy info"));
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        final JButton overwrite = choice("Overwrite", Decision.OVERWRITE);
        buttons.add(overwrite);
        buttons.add(choice("All", Decision.OVERWRITE_ALL));
        buttons.add(choice("Skip", Decision.SKIP));
        buttons.add(choice("Skip all", Decision.SKIP_ALL));
        buttons.add(choice("Cancel", Decision.CANCEL));
        getContentPane().add(info, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(overwrite);
        Dialogs.bindEscape(this);
        Dialogs.bindKey(this, "F3", () -> view(dst));
        Dialogs.bindKey(this, "shift F3", () -> view(src));
        Dialogs.bindKey(this, "alt F3", () -> view(dst));
        Dialogs.bindKey(this, "alt shift F3", () -> view(src));
        Dialogs.bindKey(this, "ctrl F3", () -> CompareWindow.open(this, src, dst));
        Dialogs.bindKey(this, "ctrl shift F3", () -> CompareWindow.open(this, src, dst));
        final String clip = "Target: " + target + "  " + describe(dst) + "\nSource: " + source + "  " + describe(src);
        Dialogs.bindKey(this, "ctrl C", () -> copy(clip));
        Dialogs.bindKey(this, "ctrl INSERT", () -> copy(clip));
        pack();
        setLocationRelativeTo(owner);
    }

    public static @NotNull Decision ask(final @Nullable Window owner, final @NotNull String source, final @NotNull String target) {
        final ConflictDialog d = new ConflictDialog(owner, source, target);
        d.setVisible(true);

        return d.decision;
    }

    private @NotNull JButton choice(final @NotNull String text, final @NotNull Decision value) {
        return Dialogs.button(text, () -> {
            decision = value;
            dispose();
        });
    }

    private void view(final @Nullable VFile f) {
        if (f != null) {
            ViewerWindow.open(this, List.of(f), 0, null);
        }
    }

    private static void copy(final @NotNull String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static @Nullable VFile statOrNull(final @NotNull String location) {
        try {
            return Vfs.file(location);
        } catch (final Exception e) {
            return null;
        }
    }

    private static @NotNull String describe(final @Nullable VFile f) {
        if (f == null) {
            return "";
        }

        return TextUtil.groupDigits(f.getSize()) + " bytes, " + TextUtil.formatDate(f.getModified());
    }
}
