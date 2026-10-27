package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import org.jetbrains.annotations.NotNull;

import tcm.ops.JobContext;
import tcm.util.TextUtil;

/**
 * Modeless operation progress: current file, total amount, Pause / Background / Cancel.
 */
public final class ProgressDialog extends JDialog {

    private static final int REFRESH_MS = 150;
    private static final int SHOW_DELAY_MS = 300;

    private final @NotNull JobContext ctx;
    private final JLabel current = new JLabel(" ");
    private final JLabel totals = new JLabel(" ");
    private final JProgressBar fileBar = new JProgressBar(0, 1000);
    private final JProgressBar totalBar = new JProgressBar(0, 1000);
    private final JButton pause = new JButton("Pause");
    private final Timer timer;
    private boolean background;
    private boolean finished;

    public ProgressDialog(final @NotNull Window owner, final @NotNull String title, final @NotNull JobContext ctx) {
        super(owner, title, ModalityType.MODELESS);
        this.ctx = ctx;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        final JPanel info = new JPanel(new GridLayout(0, 1, 0, 4));
        info.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        info.add(new JLabel(title));
        info.add(current);
        fileBar.setStringPainted(true);
        totalBar.setStringPainted(true);
        info.add(fileBar);
        info.add(totals);
        info.add(totalBar);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        pause.addActionListener(e -> {
            ctx.setPaused(!ctx.isPaused());
            pause.setText(ctx.isPaused() ? "Resume" : "Pause");
        });
        buttons.add(pause);
        buttons.add(Dialogs.button("Background", () -> {
            background = true;
            setVisible(false);
        }));
        buttons.add(Dialogs.button("Cancel", ctx::cancel));
        getContentPane().add(info, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        Dialogs.bindKey(this, "ESCAPE", ctx::cancel);
        setMinimumSize(new Dimension(520, 0));
        pack();
        setLocationRelativeTo(owner);
        timer = new Timer(REFRESH_MS, e -> update());
    }

    /** The window appears with a delay so that fast operations do not flash a dialog. */
    public void start() {
        timer.start();
        final Timer show = new Timer(SHOW_DELAY_MS, e -> {
            if (!finished && !background) {
                setVisible(true);
            }
        });
        show.setRepeats(false);
        show.start();
    }

    public void finish() {
        finished = true;
        timer.stop();
        dispose();
    }

    private void update() {
        current.setText(shorten(ctx.getCurrent()));
        fileBar.setValue(permille(ctx.getFileDone(), ctx.getFileSize()));
        totalBar.setValue(permille(ctx.getDone(), ctx.getTotal()));
        totals.setText(TextUtil.shortSize(ctx.getDone()) + " of " + TextUtil.shortSize(ctx.getTotal()) + (ctx.isPaused() ? "  (paused)" : ""));
    }

    private static int permille(final long done, final long total) {
        if (total <= 0) {
            return 0;
        }

        return (int) Math.min(1000, done * 1000 / total);
    }

    private static @NotNull String shorten(final @NotNull String s) {
        final int max = 80;

        return s.length() <= max ? s : "..." + s.substring(s.length() - max);
    }
}
