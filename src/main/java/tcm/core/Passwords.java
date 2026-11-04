package tcm.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Passwords of encrypted archives. The user is asked when an encrypted archive or entry is opened (the dialog is supplied by the UI);
 * an accepted password is remembered for the archive until the program exits, so that copying many entries asks once.
 */
public final class Passwords {

    /** Asks for the password of an archive; null — cancelled. retry — the previous password was wrong. */
    public interface Prompt {

        @Nullable String ask(@NotNull String archiveName, boolean retry);
    }

    /** One try of an operation with a password: throws {@link WrongPasswordException} if the password does not fit. */
    interface Attempt<T> {

        T run(@NotNull String password) throws IOException;
    }

    private static final Map<Path, String> KNOWN = new ConcurrentHashMap<>();
    private static volatile Prompt prompt = (name, retry) -> null;

    private Passwords() {
    }

    public static void setPrompt(final @NotNull Prompt value) {
        prompt = value;
    }

    /**
     * Runs the attempt with the remembered password, otherwise asks for one, until a password fits. Cancel throws
     * {@link PasswordCancelledException}. No lock is held while asking: the prompt may wait for the event thread.
     */
    static <T> T withPassword(final @NotNull Path archive, final @NotNull Attempt<T> attempt) throws IOException {
        final Path key = archive.toAbsolutePath().normalize();
        String password = KNOWN.get(key);
        boolean retry = false;
        while (true) {
            if (password == null) {
                password = prompt.ask(String.valueOf(key.getFileName()), retry);
                if (password == null) {
                    throw new PasswordCancelledException(String.valueOf(key.getFileName()));
                }
            }
            try {
                final T result = attempt.run(password);
                KNOWN.put(key, password);

                return result;
            } catch (final WrongPasswordException e) {
                KNOWN.remove(key, password);
                password = null;
                retry = true;
            }
        }
    }
}
