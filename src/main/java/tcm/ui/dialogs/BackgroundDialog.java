package tcm.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.WindowConstants;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.ops.JobContext;
import tcm.ops.JobRunner;
import tcm.util.Placement;
import tcm.util.TextUtil;

/**
 * Background operations: the F2 queue and jobs sent to the background from their progress dialog. Pause / resume the whole
 * queue, remove waiting jobs, cancel a running one. Closes by itself when everything has finished.
 */
public final class BackgroundDialog extends JDialog {

    private static final int REFRESH_MS = 250;
    private static final int MAX_PATH = 70;

    private static @Nullable BackgroundDialog instance;

    private final DefaultListModel<JobRunner.Entry> model = new DefaultListModel<>();
    private final JList<JobRunner.Entry> list = new JList<>(model);
    private final JLabel current = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JButton pause = new JButton("Pause");
    private final JButton remove = new JButton("Remove from queue");
    private final JButton cancel = new JButton("Cancel");
    private final Timer timer = new Timer(REFRESH_MS, e -> update());

    private BackgroundDialog(final @NotNull Window owner) {
        super(owner, "Background operations", ModalityType.MODELESS);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int index, final boolean selected,
                final boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                final JobRunner.Entry e = (JobRunner.Entry) value;
                final String state = state(e) + "   ";
                final FontMetrics fm = getFontMetrics(getFont());
                final Insets in = getInsets();
                final int width = l.getVisibleRect().width - in.left - in.right - fm.stringWidth(state);
                // the titles differ at the end (the target path): the middle is cut
                setText(state + TextUtil.fitMiddle(e.getJob().getTitle(), fm, width));
                setToolTipText(e.getJob().getTitle());

                return this;
            }
        });
        list.addListSelectionListener(e -> updateButtons());
        final JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        ToolTipManager.sharedInstance().registerComponent(list);
        final JScrollPane scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(600, 180));
        center.add(scroll, BorderLayout.CENTER);
        final JPanel progress = new JPanel(new GridLayout(0, 1, 0, 4));
        bar.setStringPainted(true);
        progress.add(current);
        progress.add(bar);
        center.add(progress, BorderLayout.SOUTH);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        pause.addActionListener(e -> JobRunner.setQueuePaused(!JobRunner.isQueuePaused()));
        remove.addActionListener(e -> removeSelected());
        cancel.addActionListener(e -> cancelRunning());
        buttons.add(pause);
        buttons.add(remove);
        buttons.add(cancel);
        buttons.add(Dialogs.button("Hide", this::dispose));
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        Dialogs.bindEscape(this);
        Dialogs.bindKey(this, "DELETE", this::removeSelected);
        // the dialog appears while the user works in the panels: it does not take the keyboard away from them
        setAutoRequestFocus(false);
        pack();
        Placement.center(this, owner);
    }

    /** Shows the list (brings it forward if it is open); nothing happens when there are no background operations. */
    public static void open(final @NotNull Window owner) {
        if (JobRunner.getBackgroundCount() == 0) {
            return;
        }
        BackgroundDialog d = instance;
        if (d == null) {
            d = new BackgroundDialog(owner);
            instance = d;
            d.update();
            d.timer.start();
            d.setVisible(true);

            return;
        }
        d.update();
        d.setVisible(true);
        d.toFront();
    }

    @Override
    public void dispose() {
        timer.stop();
        if (instance == this) {
            instance = null;
        }
        super.dispose();
    }

    private void update() {
        final List<JobRunner.Entry> entries = JobRunner.getBackground();
        if (entries.isEmpty()) {
            dispose();

            return;
        }
        if (!sameEntries(entries)) {
            final Set<JobRunner.Entry> selected = new HashSet<>(list.getSelectedValuesList());
            model.clear();
            final List<Integer> keep = new ArrayList<>();
            for (final JobRunner.Entry e : entries) {
                if (selected.contains(e)) {
                    keep.add(model.size());
                }
                model.addElement(e);
            }
            list.setSelectedIndices(keep.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        }
        list.repaint();
        pause.setText(JobRunner.isQueuePaused() ? "Resume" : "Pause");
        final JobRunner.Entry shown = shownEntry();
        if (shown == null) {
            current.setText(JobRunner.isQueuePaused() ? "Paused" : " ");
            bar.setValue(0);
            bar.setString("");
        } else {
            final JobContext ctx = shown.getCtx();
            current.setText(shorten(ctx.getCurrent()));
            bar.setValue(permille(ctx.getDone(), ctx.getTotal()));
            bar.setString(TextUtil.shortSize(ctx.getDone()) + " of " + TextUtil.shortSize(ctx.getTotal()));
        }
        updateButtons();
    }

    private boolean sameEntries(final @NotNull List<JobRunner.Entry> entries) {
        if (entries.size() != model.size()) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) != model.get(i)) {
                return false;
            }
        }

        return true;
    }

    /** Progress below the list: of the selected running job, otherwise of the first running one. */
    private @Nullable JobRunner.Entry shownEntry() {
        final JobRunner.Entry selected = list.getSelectedValue();
        if (selected != null && selected.isRunning()) {
            return selected;
        }
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).isRunning()) {
                return model.get(i);
            }
        }

        return null;
    }

    private void updateButtons() {
        boolean waiting = false;
        for (final JobRunner.Entry e : list.getSelectedValuesList()) {
            waiting |= !e.isRunning();
        }
        remove.setEnabled(waiting);
        cancel.setEnabled(!runningTargets().isEmpty());
    }

    private void removeSelected() {
        for (final JobRunner.Entry e : list.getSelectedValuesList()) {
            if (!e.isRunning()) {
                JobRunner.remove(e);
            }
        }
        update();
    }

    /** Cancel: the selected running jobs, or the running one when nothing running is selected. */
    private void cancelRunning() {
        for (final JobRunner.Entry e : runningTargets()) {
            JobRunner.remove(e);
        }
        update();
    }

    private @NotNull List<JobRunner.Entry> runningTargets() {
        final List<JobRunner.Entry> targets = new ArrayList<>();
        for (final JobRunner.Entry e : list.getSelectedValuesList()) {
            if (e.isRunning() && !e.getCtx().isCancelled()) {
                targets.add(e);
            }
        }
        if (targets.isEmpty() && list.isSelectionEmpty()) {
            final JobRunner.Entry first = shownEntry();
            if (first != null && !first.getCtx().isCancelled()) {
                targets.add(first);
            }
        }

        return targets;
    }

    private static @NotNull String state(final @NotNull JobRunner.Entry e) {
        final JobContext ctx = e.getCtx();
        if (!e.isRunning()) {
            return "[Queued]";
        }
        if (ctx.isCancelled()) {
            return "[Cancelling]";
        }

        return "[" + (ctx.isPaused() ? "Paused " : "") + permille(ctx.getDone(), ctx.getTotal()) / 10 + "%]";
    }

    private static int permille(final long done, final long total) {
        if (total <= 0) {
            return 0;
        }

        return (int) Math.min(1000, done * 1000 / total);
    }

    private static @NotNull String shorten(final @NotNull String s) {
        return s.isEmpty() ? " " : s.length() <= MAX_PATH ? s : "..." + s.substring(s.length() - MAX_PATH);
    }
}
