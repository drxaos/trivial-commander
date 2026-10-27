package tcm.core;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Watches for changes in local directories. Events accumulate in the background and are collected by {@link #watch} —
 * so frequent changes (a file being downloaded) turn into one update per polling period.
 */
public final class DirWatcher {

    private final @Nullable WatchService service;
    /** Only from the thread that calls {@link #watch}. */
    private final Map<Path, WatchKey> keys = new HashMap<>();
    private final Set<Path> changed = ConcurrentHashMap.newKeySet();

    public DirWatcher() {
        service = createService();
        if (service != null) {
            final WatchService s = service;
            final Thread t = new Thread(() -> poll(s), "dir-watcher");
            t.setDaemon(true);
            t.start();
        }
    }

    private static @Nullable WatchService createService() {
        try {
            return FileSystems.getDefault().newWatchService();
        } catch (final IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** Watch exactly these directories; returns those of them that changed since the previous call. */
    public @NotNull Set<Path> watch(final @NotNull Set<Path> dirs) {
        if (service == null) {
            return Set.of();
        }
        final Iterator<Map.Entry<Path, WatchKey>> it = keys.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Path, WatchKey> e = it.next();
            // the key becomes invalid if the directory was deleted: register again when it reappears
            if (!dirs.contains(e.getKey()) || !e.getValue().isValid()) {
                e.getValue().cancel();
                it.remove();
            }
        }
        for (final Path dir : dirs) {
            if (!keys.containsKey(dir)) {
                try {
                    keys.put(dir, dir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY));
                } catch (final IOException | RuntimeException e) {
                    // directory inaccessible or deleted — retry on the next call
                }
            }
        }
        // events of directories no longer watched are not needed
        changed.removeIf(p -> !dirs.contains(p));
        final Set<Path> result = new HashSet<>();
        for (final Path dir : dirs) {
            if (changed.remove(dir)) {
                result.add(dir);
            }
        }

        return result;
    }

    private void poll(final @NotNull WatchService s) {
        while (true) {
            final WatchKey key;
            try {
                key = s.take();
            } catch (final InterruptedException | RuntimeException e) {
                return;
            }
            // the events themselves are not needed: the directory is re-read entirely anyway, OVERFLOW also means "changed"
            key.pollEvents();
            changed.add((Path) key.watchable());
            key.reset();
        }
    }

}
