package tcm.util;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.jetbrains.annotations.NotNull;

/**
 * Application settings in java.util.prefs (tab paths, window, theme, favorites).
 */
public final class Settings {

    private static final Preferences PREFS = Preferences.userRoot().node("tcm");
    private static final String LIST_SEPARATOR = "\n";

    private Settings() {
    }

    public static @NotNull String get(final @NotNull String key, final @NotNull String def) {
        return PREFS.get(key, def);
    }

    /** An unchanged value is not written: settings are saved on a timer, and flush without changes does not touch the disk. */
    public static void put(final @NotNull String key, final @NotNull String value) {
        if (!value.equals(PREFS.get(key, null))) {
            PREFS.put(key, value);
        }
    }

    public static int getInt(final @NotNull String key, final int def) {
        return PREFS.getInt(key, def);
    }

    public static void putInt(final @NotNull String key, final int value) {
        put(key, Integer.toString(value));
    }

    public static boolean getBoolean(final @NotNull String key, final boolean def) {
        return PREFS.getBoolean(key, def);
    }

    public static void putBoolean(final @NotNull String key, final boolean value) {
        put(key, String.valueOf(value));
    }

    public static @NotNull List<String> getList(final @NotNull String key) {
        final String raw = PREFS.get(key, "");
        final List<String> result = new ArrayList<>();
        for (final String s : raw.split(LIST_SEPARATOR)) {
            if (!s.isBlank()) {
                result.add(s);
            }
        }

        return result;
    }

    public static void putList(final @NotNull String key, final @NotNull List<String> values) {
        put(key, String.join(LIST_SEPARATOR, values));
    }

    public static void flush() {
        try {
            PREFS.flush();
        } catch (final BackingStoreException e) {
            // settings are not critical: on a storage error simply don't save
        }
    }
}
