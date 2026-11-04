package tcm.core;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;

/**
 * The user cancelled the password prompt of an encrypted archive.
 */
public final class PasswordCancelledException extends IOException {

    public PasswordCancelledException(final @NotNull String archiveName) {
        super("Password not entered for encrypted archive " + archiveName);
    }
}
