package tcm.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;

/**
 * TC-style name masks: "*.txt;*.md", "a?c*". A mask without '*' and '?' is treated as a name substring.
 * An empty mask, "*" and "*.*" match everything.
 */
public final class Masks {

    private final List<Pattern> patterns = new ArrayList<>();
    private final List<Pattern> excludes = new ArrayList<>();
    private final boolean matchAll;

    private Masks(final @NotNull String mask) {
        final String trimmed = mask.trim();
        final int bar = trimmed.indexOf('|');
        final String include = bar < 0 ? trimmed : trimmed.substring(0, bar);
        final String exclude = bar < 0 ? "" : trimmed.substring(bar + 1);
        for (final String part : split(include)) {
            if (!isAll(part)) {
                patterns.add(compile(part));
            }
        }
        for (final String part : split(exclude)) {
            excludes.add(compile(part));
        }
        matchAll = patterns.isEmpty();
    }

    public static @NotNull Masks of(final @NotNull String mask) {
        return new Masks(mask);
    }

    public static boolean isAll(final @NotNull String mask) {
        final String m = mask.trim();

        return m.isEmpty() || "*".equals(m) || "*.*".equals(m);
    }

    public boolean matches(final @NotNull String name) {
        for (final Pattern p : excludes) {
            if (p.matcher(name).matches()) {
                return false;
            }
        }
        if (matchAll) {
            return true;
        }
        for (final Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }

        return false;
    }

    public boolean isMatchAll() {
        return matchAll && excludes.isEmpty();
    }

    private static @NotNull List<String> split(final @NotNull String masks) {
        final List<String> result = new ArrayList<>();
        for (final String part : masks.split("[;,]")) {
            final String p = part.trim();
            if (!p.isEmpty()) {
                result.add(p);
            }
        }

        return result;
    }

    private static @NotNull Pattern compile(final @NotNull String mask) {
        final boolean wildcard = mask.indexOf('*') >= 0 || mask.indexOf('?') >= 0;
        final StringBuilder regex = new StringBuilder();
        if (!wildcard) {
            regex.append(".*").append(Pattern.quote(mask)).append(".*");

            return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        }
        // in TC "*.*" also matches names without a dot
        final String m = mask.endsWith(".*") ? mask.substring(0, mask.length() - 2) + "*" : mask;
        final StringBuilder literal = new StringBuilder();
        for (final char c : m.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }

        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    @Override
    public String toString() {
        return patterns.toString().toLowerCase(Locale.ROOT);
    }
}
