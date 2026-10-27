package tcm.ops;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/**
 * Background operation on files.
 */
public abstract class Job {

    @Getter
    private final @NotNull String title;

    protected Job(final @NotNull String title) {
        this.title = title;
    }

    public abstract void run(@NotNull JobContext ctx) throws Exception;
}
