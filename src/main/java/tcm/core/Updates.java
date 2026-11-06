package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * New release check: the tag of the latest GitHub release against the version this build was made from (pom version, see version.properties).
 */
public final class Updates {

    public static final String RELEASES_PAGE = "https://github.com/drxaos/trivial-commander/releases/latest";
    private static final String LATEST_API = "https://api.github.com/repos/drxaos/trivial-commander/releases/latest";
    private static final int TIMEOUT_MS = 15_000;
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final String VERSION = readVersion();

    private Updates() {
    }

    /** null — unknown (resource not filtered, e.g. run from the IDE without Maven): such a build is never offered an update. */
    public static @Nullable String currentVersion() {
        return VERSION;
    }

    /** Tag of the newer release, null — this build is the latest or its version is unknown. Blocks on the network. */
    public static @Nullable String newerRelease() throws IOException {
        if (VERSION == null) {
            return null;
        }
        final String tag = latestTag();

        return isNewer(tag, VERSION) ? tag : null;
    }

    static @NotNull String latestTag() throws IOException {
        final HttpURLConnection c = (HttpURLConnection) URI.create(LATEST_API).toURL().openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            // GitHub API rejects requests without User-Agent
            c.setRequestProperty("User-Agent", "trivial-commander/" + VERSION);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            final String body;
            try (InputStream in = c.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final Matcher m = TAG.matcher(body);
            if (!m.find()) {
                throw new IOException("no tag_name in the response");
            }

            return m.group(1);
        } finally {
            c.disconnect();
        }
    }

    /** Numeric parts compared one by one ("0.10" > "0.9", "v1.2" = "1.2.0"); a tag without digits is never newer. */
    static boolean isNewer(final @NotNull String tag, final @NotNull String current) {
        final List<Integer> a = numbers(tag);
        final List<Integer> b = numbers(current);
        if (a.isEmpty()) {
            return false;
        }
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            final int x = i < a.size() ? a.get(i) : 0;
            final int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return x > y;
            }
        }

        return false;
    }

    private static @NotNull List<Integer> numbers(final @NotNull String version) {
        final List<Integer> result = new ArrayList<>();
        final Matcher m = NUMBER.matcher(version);
        while (m.find()) {
            try {
                result.add(Integer.parseInt(m.group()));
            } catch (final NumberFormatException e) {
                // absurdly long digit run — not a version part
                result.add(Integer.MAX_VALUE);
            }
        }

        return result;
    }

    private static @Nullable String readVersion() {
        try (InputStream in = Updates.class.getResourceAsStream("/tcm/version.properties")) {
            if (in == null) {
                return null;
            }
            final Properties p = new Properties();
            p.load(in);
            final String v = p.getProperty("version", "").trim();

            return v.isEmpty() || v.contains("${") ? null : v;
        } catch (final IOException e) {
            return null;
        }
    }
}
