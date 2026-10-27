package tcm.ops;

import java.io.IOException;

/**
 * The operation was cancelled by the user. Extends IOException to pass through I/O code.
 */
public class CancelledException extends IOException {

    public CancelledException() {
        super("Cancelled");
    }
}
