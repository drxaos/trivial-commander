package tcm.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/**
 * A set of archive changes applied in a single rewrite of the file.
 */
@Getter
public final class ZipEdit {

    private final Set<String> removals = new LinkedHashSet<>();
    private final Map<String, String> renames = new LinkedHashMap<>();
    private final List<ZipAddition> additions = new ArrayList<>();

    public @NotNull ZipEdit remove(final @NotNull String entry) {
        removals.add(ArchiveIndex.normalize(entry));

        return this;
    }

    public @NotNull ZipEdit rename(final @NotNull String from, final @NotNull String to) {
        renames.put(ArchiveIndex.normalize(from), ArchiveIndex.normalize(to));

        return this;
    }

    public @NotNull ZipEdit add(final @NotNull ZipAddition addition) {
        additions.add(addition);

        return this;
    }

    public boolean isEmpty() {
        return removals.isEmpty() && renames.isEmpty() && additions.isEmpty();
    }

    public long getAdditionsSize() {
        long total = 0;
        for (final ZipAddition a : additions) {
            total += a.getSize();
        }

        return total;
    }
}
