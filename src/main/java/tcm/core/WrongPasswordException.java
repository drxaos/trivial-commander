package tcm.core;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;

/**
 * The password does not fit the encrypted archive or entry: {@link Passwords} asks for another one.
 */
final class WrongPasswordException extends IOException {

    WrongPasswordException(final @NotNull String message) {
        super(message);
    }
}
