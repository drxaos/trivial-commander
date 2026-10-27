package tcm.core;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;

/**
 * Progress receiver for long operations. The methods are also cancel/pause points: they may throw an exception.
 */
public interface Progress {

    Progress NONE = new Progress() {
        @Override
        public void file(final @NotNull String name, final long size) {
            // progress not needed
        }

        @Override
        public void bytes(final long count) {
            // progress not needed
        }
    };

    /** Wrapper that does not count bytes but keeps the cancel/pause points (internal copying). */
    static @NotNull Progress ticksOnly(final @NotNull Progress delegate) {
        return new Progress() {
            @Override
            public void file(final @NotNull String name, final long size) {
                // internal entry is not displayed
            }

            @Override
            public void bytes(final long count) throws IOException {
                delegate.bytes(0);
            }
        };
    }

    void file(@NotNull String name, long size) throws IOException;

    void bytes(long count) throws IOException;
}
