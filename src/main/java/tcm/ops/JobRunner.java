package tcm.ops;

import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;

import tcm.ui.dialogs.ConflictDialog;
import tcm.ui.dialogs.Dialogs;
import tcm.ui.dialogs.ProgressDialog;

/**
 * Running operations: immediately (separate thread) or queued (one thread, jobs in order).
 */
public final class JobRunner {

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "job-queue");
        t.setDaemon(true);

        return t;
    });
    private static final AtomicInteger QUEUED = new AtomicInteger();

    private JobRunner() {
    }

    public static int getQueuedCount() {
        return QUEUED.get();
    }

    public static void run(final @NotNull Window owner, final @NotNull Job job, final boolean queued, final @NotNull Runnable onDone) {
        final JobContext ctx = new JobContext(new SwingResolver(owner));
        final Runnable task = () -> execute(owner, job, ctx, onDone);
        if (queued) {
            QUEUED.incrementAndGet();
            QUEUE.submit(() -> {
                QUEUED.decrementAndGet();
                task.run();
            });

            return;
        }
        final Thread t = new Thread(task, "job");
        t.setDaemon(true);
        t.start();
    }

    private static void execute(final @NotNull Window owner, final @NotNull Job job, final @NotNull JobContext ctx, final @NotNull Runnable onDone) {
        final AtomicReference<ProgressDialog> dialog = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            final ProgressDialog d = new ProgressDialog(owner, job.getTitle(), ctx);
            dialog.set(d);
            d.start();
        });
        Throwable error = null;
        try {
            job.run(ctx);
        } catch (final CancelledException e) {
            // cancellation by the user is not an error
        } catch (final Throwable e) {
            error = e;
        }
        final Throwable failure = error;
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
