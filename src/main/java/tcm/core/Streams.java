package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.jetbrains.annotations.NotNull;

public final class Streams {

    private static final int BUFFER_SIZE = 64 * 1024;

    private Streams() {
    }

    public static long copy(final @NotNull InputStream in, final @NotNull OutputStream out, final @NotNull Progress progress)
        throws IOException {
        final byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            progress.bytes(n);
        }

        return total;
    }
}
