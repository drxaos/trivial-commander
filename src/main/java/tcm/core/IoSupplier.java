package tcm.core;

import java.io.IOException;

@FunctionalInterface
public interface IoSupplier<T> {

    T get() throws IOException;
}
