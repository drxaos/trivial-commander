package tcm.apps;

import java.awt.Font;
import java.awt.FontMetrics;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

import javax.swing.JComponent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Lines too wide for the screen. With XRender (Linux) glyph coordinates are 16-bit: text further than ~32000 px from the line start
 * is drawn over its beginning as a jumble of characters. And JTextArea measures and paints a whole line on every repaint,
 * so a multi-megabyte line (minified JSON, a log without line breaks) locks up the window for good.
 */
final class LongLines {

    /** Safe line width in pixels, with a margin for wide characters and tabs. */
    private static final int SAFE_WIDTH = 24_000;
    private static final int MIN_CHARS = 200;
    private static final int MAX_CHARS = 10_000;

    private LongLines() {
    }

    /** How many characters of the component's font fit into a safe line width. */
    static int maxChars(final @NotNull JComponent c, final @NotNull Font font) {
        final FontMetrics fm = c.getFontMetrics(font);
        final int w = Math.max(1, fm.charWidth('W'));

        return Math.max(MIN_CHARS, Math.min(MAX_CHARS, SAFE_WIDTH / w));
    }

    /** Length of the longest line in characters. */
    static int longest(final @NotNull CharSequence text) {
        int max = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            checkCancelled(i);
            run = text.charAt(i) == '\n' ? 0 : run + 1;
            max = Math.max(max, run);
        }

        return max;
    }

    /** Lines longer than maxChars are cut into pieces of maxChars; the original line numbers are kept for the line number column. */
    static @NotNull Split split(final @NotNull String text, final int maxChars) {
        final int longest = longest(text);
        if (longest <= maxChars) {
            // fast path: no long lines — no copy needed
            return new Split(text, null, longest);
        }
        final StringBuilder sb = new StringBuilder(text.length() + text.length() / maxChars + 16);
        int[] numbers = new int[1024];
        int lines = 0;
        int number = 1;
        numbers[lines++] = number;
        int run = 0;
        for (int k = 0; k < text.length(); k++) {
            checkCancelled(k);
            final char c = text.charAt(k);
            if (c != '\n' && run == maxChars) {
                sb.append('\n');
                run = 0;
                if (lines == numbers.length) {
                    numbers = Arrays.copyOf(numbers, lines * 2);
                }
                numbers[lines++] = 0;
            }
            sb.append(c);
            if (c == '\n') {
                run = 0;
                if (lines == numbers.length) {
                    numbers = Arrays.copyOf(numbers, lines * 2);
                }
                numbers[lines++] = ++number;
            } else {
                run++;
            }
        }

        return new Split(sb.toString(), Arrays.copyOf(numbers, lines), longest);
    }

    /**
     * Breaks lines into rows no wider than width pixels: wrapping of a large text, done in the background. WrappedPlainView
     * re-breaks the whole document on the EDT several times per layout — seconds of a frozen application for megabytes.
     * With words a row ends after its last whitespace, if it has one. Tabs stop every tabWidth pixels, as in PlainView.
     */
    static @NotNull Split wrap(final @NotNull String text, final @NotNull FontMetrics fm, final int width, final int tabWidth,
        final boolean words) {
        final Rows rows = new Rows(text);
        int x = 0;
        int lastSpace = -1;
        for (int k = 0; k < text.length(); k++) {
            checkCancelled(k);
            final char c = text.charAt(k);
            if (c == '\n') {
                rows.lineEnd(k + 1);
                x = 0;
                lastSpace = -1;
                continue;
            }
            int w = advance(text, k, x, fm, tabWidth);
            // the low half of a surrogate pair stays with the high one
            if (x + w > width && k > rows.start && !Character.isLowSurrogate(c)) {
                final int cut = words && lastSpace >= rows.start ? lastSpace + 1 : k;
                rows.rowEnd(cut);
                lastSpace = -1;
                x = 0;
                for (int j = cut; j < k; j++) {
                    x += advance(text, j, x, fm, tabWidth);
                }
                w = advance(text, k, x, fm, tabWidth);
                if (x + w > width && k > rows.start) {
                    // the word before the character fills the whole row
                    rows.rowEnd(k);
                    x = 0;
                    w = advance(text, k, x, fm, tabWidth);
                }
            }
            if (Character.isWhitespace(c)) {
                lastSpace = k;
            }
            x += w;
        }

        return rows.finish();
    }

    private static int advance(final @NotNull String text, final int k, final int x, final @NotNull FontMetrics fm, final int tabWidth) {
        return text.charAt(k) == '\t' ? tabWidth - x % tabWidth : PlainMetrics.charWidth(fm, text, k);
    }

    /** Rows being collected by wrap: text with inserted breaks and the line number of every row. */
    private static final class Rows {

        private final @NotNull String text;
        private final @NotNull StringBuilder sb;
        private int[] numbers = new int[1024];
        private int count;
        private int number = 1;
        /** Start of the current row in the text. */
        private int start;
        private int lineStart;
        private int longest;

        Rows(final @NotNull String text) {
            this.text = text;
            sb = new StringBuilder(text.length() + text.length() / 64 + 16);
            add(number);
        }

        /** The line ends with its line break at end; the next row is the next line. */
        void lineEnd(final int end) {
            sb.append(text, start, end);
            longest = Math.max(longest, end - 1 - lineStart);
            start = end;
            lineStart = end;
            add(++number);
        }

        /** The row ends before cut, the rest of the line continues on a new row. */
        void rowEnd(final int cut) {
            sb.append(text, start, cut).append('\n');
            start = cut;
            add(0);
        }

        @NotNull Split finish() {
            sb.append(text, start, text.length());
            longest = Math.max(longest, text.length() - lineStart);

            return new Split(sb.toString(), Arrays.copyOf(numbers, count), longest);
        }

        private void add(final int value) {
            if (count == numbers.length) {
                numbers = Arrays.copyOf(numbers, count * 2);
            }
            numbers[count++] = value;
        }
    }

    /** Long loops check now and then whether they were cancelled (viewer closed, file changed). */
    static void checkCancelled(final int position) {
        if ((position & 0xFFFF) == 0 && Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    @Getter
    static final class Split {

        private final @NotNull String text;
        /** Document line → line number in the file, 0 — continuation of a cut line; null — the lines were not cut. */
        private final int @Nullable [] numbers;
        /** The longest line before cutting. */
        private final int longest;

        private Split(final @NotNull String text, final int @Nullable [] numbers, final int longest) {
            this.text = text;
            this.numbers = numbers;
            this.longest = longest;
        }
    }
}
