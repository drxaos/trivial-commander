package tcm.ops;

import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

import tcm.ui.dialogs.BackgroundDialog;
import tcm.ui.dialogs.ConflictDialog;
import tcm.ui.dialogs.Dialogs;
import tcm.ui.dialogs.ProgressDialog;

/**
 * Running operations: immediately (a thread of its own, progress dialog) or queued (F2: one thread, jobs in order).
 * Queued jobs and the ones sent to the background from their progress dialog are listed in {@link BackgroundDialog}:
 * the queue can be paused as a whole, waiting jobs removed, a running one cancelled.
 */
public final class JobRunner {

    /** Background operation: a queued job (waiting or running) or an immediate one sent to the background. */
    @Getter
    public static final class Entry {

        private final @NotNull Job job;
        private final @NotNull JobContext ctx;
        /** From the F2 queue: runs on the queue thread in turn; otherwise it already runs on a thread of its own. */
        private final boolean queued;
        private final @NotNull Window owner;
        private final @NotNull Runnable onDone;
        private volatile boolean running;

        private Entry(
            final @NotNull Job job,
            final @NotNull JobContext ctx,
            final boolean queued,
            final @NotNull Window owner,
            final @NotNull Runnable onDone
        ) {
            this.job = job;
            this.ctx = ctx;
            this.queued = queued;
            this.owner = owner;
            this.onDone = onDone;
        }
    }

    private static final Object LOCK = new Object();
    /** Guarded by LOCK. In order of arrival: the queue thread takes the first waiting queued entry. */
    private static final List<Entry> BACKGROUND = new ArrayList<>();
    private static boolean queuePaused;
    private static @Nullable Thread queueThread;

    private JobRunner() {
    }

    /** Background operations still to finish: waiting, running in the queue or sent to the background. */
    public static int getBackgroundCount() {
        synchronized (LOCK) {
            return BACKGROUND.size();
        }
    }

    public static @NotNull List<Entry> getBackground() {
        synchronized (LOCK) {
            return List.copyOf(BACKGROUND);
        }
    }

    public static boolean isQueuePaused() {
        synchronized (LOCK) {
            return queuePaused;
        }
    }

    public static void run(final @NotNull Window owner, final @NotNull Job job, final boolean queued, final @NotNull Runnable onDone) {
        final JobContext ctx = new JobContext(new SwingResolver(owner));
        if (queued) {
            synchronized (LOCK) {
                BACKGROUND.add(new Entry(job, ctx, true, owner, onDone));
                startQueueThread();
                LOCK.notifyAll();
            }
            showBackground(owner);

            return;
        }
        final Thread t = new Thread(() -> execute(owner, job, ctx, onDone), "job");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Pause / resume all background operations: the running ones stop at the next file or block, the queue starts nothing new.
     * A job sent to the background later, or one running with its own progress dialog, is not affected.
     */
    public static void setQueuePaused(final boolean paused) {
        synchronized (LOCK) {
            queuePaused = paused;
            for (final Entry e : BACKGROUND) {
                if (e.running) {
                    e.ctx.setPaused(paused);
                }
            }
            LOCK.notifyAll();
        }
    }

    /** A waiting job leaves the queue without running; a running one is cancelled. */
    public static void remove(final @NotNull Entry entry) {
        synchronized (LOCK) {
            if (!entry.running) {
                BACKGROUND.remove(entry);

                return;
            }
        }
        entry.ctx.cancel();
    }

    private static void startQueueThread() {
        if (queueThread != null) {
            return;
        }
        final Thread t = new Thread(JobRunner::serveQueue, "job-queue");
        t.setDaemon(true);
        queueThread = t;
        t.start();
    }

    private static void serveQueue() {
        while (true) {
            final Entry next;
            try {
                next = takeNext();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();

                return;
            }
            final Throwable failure = runJob(next.job, next.ctx);
            synchronized (LOCK) {
                BACKGROUND.remove(next);
            }
            SwingUtilities.invokeLater(() -> {
                next.onDone.run();
                if (failure != null) {
                    Dialogs.error(next.owner, next.job.getTitle() + " failed:", failure);
                }
            });
        }
    }

    private static @NotNull Entry takeNext() throws InterruptedException {
        synchronized (LOCK) {
            while (true) {
                if (!queuePaused) {
                    for (final Entry e : BACKGROUND) {
                        if (e.queued && !e.running) {
                            e.running = true;

                            return e;
                        }
                    }
                }
                LOCK.wait();
            }
        }
    }

    private static void execute(final @NotNull Window owner, final @NotNull Job job, final @NotNull JobContext ctx, final @NotNull Runnable onDone) {
        final Entry entry = new Entry(job, ctx, false, owner, onDone);
        entry.running = true;
        final AtomicReference<ProgressDialog> dialog = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            final ProgressDialog d = new ProgressDialog(owner, job.getTitle(), ctx, () -> toBackground(entry));
            dialog.set(d);
            d.start();
        });
        final Throwable failure = runJob(job, ctx);
        synchronized (LOCK) {
            BACKGROUND.remove(entry);
        }
        SwingUtilities.invokeLater(() -> {
            final ProgressDialog d = dialog.get();
            if (d != null) {
                d.finish();
            }
            onDone.run();
            if (failure != null) {
                Dialogs.error(owner, job.getTitle() + " failed:", failure);
            }
        });
    }

    /** The progress dialog's "Background" button: the job goes on and shows up in the background list. */
    private static void toBackground(final @NotNull Entry entry) {
        synchronized (LOCK) {
            if (!entry.ctx.isFinished()) {
                BACKGROUND.add(entry);
            }
        }
        showBackground(entry.owner);
    }

    private static @Nullable Throwable runJob(final @NotNull Job job, final @NotNull JobContext ctx) {
        try {
            job.run(ctx);

            return null;
        } catch (final CancelledException e) {
            // cancellation by the user is not an error
            return null;
        } catch (final Throwable e) {
            return e;
        } finally {
            ctx.setFinished();
        }
    }

    private static void showBackground(final @NotNull Window owner) {
        SwingUtilities.invokeLater(() -> BackgroundDialog.open(owner));
    }

    /** Questions are asked from the worker thread, so dialogs are shown via invokeAndWait. */
    private static final class SwingResolver implements ConflictResolver {

        private final @NotNull Window owner;

        private SwingResolver(final @NotNull Window owner) {
            this.owner = owner;
        }

        @Override
        public @NotNull Decision ask(final @NotNull String source, final @NotNull String target) {
            final AtomicReference<Decision> answer = new AtomicReference<>(Decision.CANCEL);
            onEdt(() -> answer.set(ConflictDialog.ask(owner, source, target)));

            return answer.get();
        }

        @Override
        public boolean confirm(final @NotNull String message) {
            final AtomicBoolean answer = new AtomicBoolean();
            onEdt(() -> answer.set(JOptionPane.showConfirmDialog(owner, message, "Confirm", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION));

            return answer.get();
        }

        private static void onEdt(final @NotNull Runnable r) {
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();

                return;
            }
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final InvocationTargetException e) {
                // the dialog failed — the answer stays at the default value (cancel)
            }
        }
    }
}
