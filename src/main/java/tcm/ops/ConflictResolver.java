package tcm.ops;

import org.jetbrains.annotations.NotNull;

/**
 * Questions to the user from the operation's background thread.
 */
public interface ConflictResolver {

    ConflictResolver OVERWRITE_ALL = new ConflictResolver() {
        @Override
        public @NotNull Decision ask(final @NotNull String source, final @NotNull String target) {
            return Decision.OVERWRITE;
        }

        @Override
        public boolean confirm(final @NotNull String message) {
            return true;
        }
    };

    @NotNull
    Decision ask(@NotNull String source, @NotNull String target);

    boolean confirm(@NotNull String message);
}
