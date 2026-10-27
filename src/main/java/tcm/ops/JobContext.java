package tcm.ops;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.Progress;

/**
 * State of a running operation: progress, pause, cancel and the "remembered" conflict answer.
 */
public final class JobContext implements Progress {

    private static final long PAUSE_POLL_MS = 200;

    private final @NotNull ConflictResolver resolver;
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong fileSize = new AtomicLong();
    private final AtomicLong fileDone = new AtomicLong();
    private final Object lock = new Object();
    private volatile @NotNull String current = "";
    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile @Nullable Decision sticky;

    public JobContext(final @NotNull ConflictResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void file(final @NotNull String name, final long size) throws IOException {
        checkpoint();
        current = name;
        fileSize.set(size);
        fileDone.set(0);
    }

    @Override
    public void bytes(final long count) throws IOException {
        done.addAndGet(count);
        fileDone.addAndGet(count);
        checkpoint();
    }

    /** A skipped file still counts toward the overall progress. */
    public void skip(final long size) {
        done.addAndGet(size);
    }

    public void checkpoint() throws CancelledException {
        if (paused) {
            synchronized (lock) {
                while (paused && !cancelled) {
                    try {
                        lock.wait(PAUSE_POLL_MS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        cancelled = true;
                    }
                }
            }
        }
        if (cancelled) {
            throw new CancelledException();
        }
    }

    /** true — overwrite the target, false — skip. Cancel throws CancelledException. */
    public boolean shouldOverwrite(final @NotNull String source, final @NotNull String target) throws CancelledException {
        final Decision remembered = sticky;
        if (remembered != null) {
            return remembered == Decision.OVERWRITE_ALL;
        }
        final Decision d = resolver.ask(source, target);
        if (d == Decision.CANCEL) {
            cancel();
            throw new CancelledException();
        }
        if (d == Decision.OVERWRITE_ALL || d == Decision.SKIP_ALL) {
            sticky = d;
        }

        return d == Decision.OVERWRITE || d == Decision.OVERWRITE_ALL;
    }

    public boolean confirm(final @NotNull String message) {
        return resolver.confirm(message);
    }

    public void setTotal(final long value) {
        total.set(value);
    }

    public void setCurrent(final @NotNull String value) {
        current = value;
    }

    public void setPaused(final boolean value) {
        synchronized (lock) {
            paused = value;
            lock.notifyAll();
        }
    }

    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            lock.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public @NotNull String getCurrent() {
        return current;
    }

    public long getTotal() {
        return total.get();
    }

    public long getDone() {
        return done.get();
    }

    public long getFileSize() {
        return fileSize.get();
    }

    public long getFileDone() {
        return fileDone.get();
    }
}
