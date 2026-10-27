package tcm.apps;

import java.util.concurrent.CancellationException;

import org.jetbrains.annotations.NotNull;

/**
 * Pretty-print JSON for the viewer. Parsing is not strict: the text is reformatted by brackets, commas and colons
 * outside strings, so both truncated (first 32 MB) and slightly broken JSON is shown rather than failing with an error.
 */
final class JsonPretty {

    private static final String INDENT = "  ";

    private JsonPretty() {
    }

    static @NotNull String format(final @NotNull String json) {
        final StringBuilder sb = new StringBuilder(json.length() + json.length() / 4);
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            if ((i & 0xFFFF) == 0 && Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            final char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    sb.append(c);
                    break;
                case '{':
                case '[':
                    sb.append(c);
                    final int next = nextSignificant(json, i + 1);
                    if (next < json.length() && json.charAt(next) == (c == '{' ? '}' : ']')) {
                        // an empty object or array stays on one line: {} and []
                        sb.append(json.charAt(next));
                        i = next;
                    } else {
                        depth++;
                        newLine(sb, depth);
                    }
                    break;
                case '}':
                case ']':
                    depth = Math.max(0, depth - 1);
                    newLine(sb, depth);
                    sb.append(c);
                    break;
                case ',':
                    sb.append(c);
                    newLine(sb, depth);
                    break;
                case ':':
                    sb.append(": ");
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    // original indentation is replaced with our own
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }

        return sb.toString();
    }

    private static int nextSignificant(final @NotNull String json, final int from) {
        int i = from;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }

        return i;
    }

    private static void newLine(final @NotNull StringBuilder sb, final int depth) {
        sb.append('\n');
        for (int k = 0; k < depth; k++) {
            sb.append(INDENT);
        }
    }
}
