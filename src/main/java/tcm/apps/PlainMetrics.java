package tcm.apps;

import java.awt.FontMetrics;
import java.awt.font.FontRenderContext;

import org.jetbrains.annotations.NotNull;

/**
 * Font metrics that measure text without shaping. For a run with a combining mark (e.g. a stress mark U+0301)
 * FontDesignMetrics builds a TextLayout and shapes it with HarfBuzz; in the native image those calls go through
 * interpreted method handles, and the text area measuring every line of a large text froze the EDT for tens of seconds.
 * Here combining marks have no width (as in a monospaced font they sit on the previous letter), other characters —
 * their own advance.
 */
final class PlainMetrics extends FontMetrics {

    private final @NotNull FontMetrics base;
    /**
     * Width + 1 of BMP characters, 0 — not measured yet. FontDesignMetrics caches only the first 256 characters,
     * the rest it asks the font strike for every time — through native memory, slow in the native image.
     */
    private final int[] bmpWidths = new int[Character.MAX_VALUE + 1];

    PlainMetrics(final @NotNull FontMetrics base) {
        super(base.getFont());
        this.base = base;
    }

    @Override
    public FontRenderContext getFontRenderContext() {
        return base.getFontRenderContext();
    }

    @Override
    public int getLeading() {
        return base.getLeading();
    }

    @Override
    public int getAscent() {
        return base.getAscent();
    }

    @Override
    public int getDescent() {
        return base.getDescent();
    }

    @Override
    public int getMaxAscent() {
        return base.getMaxAscent();
    }

    @Override
    public int getMaxDescent() {
        return base.getMaxDescent();
    }

    @Override
    public int getMaxAdvance() {
        return base.getMaxAdvance();
    }

    @Override
    public boolean hasUniformLineMetrics() {
        return base.hasUniformLineMetrics();
    }

    @Override
    public int[] getWidths() {
        return base.getWidths();
    }

    @Override
    public int charWidth(final char ch) {
        return charWidth((int) ch);
    }

    @Override
    public int charWidth(final int codePoint) {
        if (codePoint < 0 || codePoint >= bmpWidths.length) {
            return isMark(codePoint) ? 0 : base.charWidth(codePoint);
        }
        final int cached = bmpWidths[codePoint];
        if (cached != 0) {
            return cached - 1;
        }
        final int width = isMark(codePoint) ? 0 : base.charWidth(codePoint);
        bmpWidths[codePoint] = width + 1;

        return width;
    }

    @Override
    public int charsWidth(final char @NotNull [] data, final int off, final int len) {
        int width = 0;
        final int end = off + len;
        for (int i = off; i < end; ) {
            final int cp = Character.codePointAt(data, i, end);
            width += charWidth(cp);
            i += Character.charCount(cp);
        }

        return width;
    }

    @Override
    public int stringWidth(final @NotNull String str) {
        return charsWidth(str.toCharArray(), 0, str.length());
    }

    /**
     * Measures every character of the text in advance, off the EDT: the first measurement of a glyph renders it, and for
     * a text of all alphabets (a binary file decoded as UTF-16) that is tens of thousands of glyphs — seconds of a frozen window.
     * The EDT may measure at the same time: a width is written whole and always the same.
     */
    void measureAll(final @NotNull String text) {
        for (int i = 0; i < text.length(); i++) {
            LongLines.checkCancelled(i);
            final char c = text.charAt(i);
            if (bmpWidths[c] == 0 && !Character.isSurrogate(c)) {
                charWidth(c);
            }
        }
    }

    /** Width of the character at i; a surrogate pair is measured as one code point on its first half, the second half is 0. */
    static int charWidth(final @NotNull FontMetrics fm, final @NotNull CharSequence text, final int i) {
        final char c = text.charAt(i);
        if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
            return fm.charWidth(Character.toCodePoint(c, text.charAt(i + 1)));
        }

        return Character.isLowSurrogate(c) ? 0 : fm.charWidth(c);
    }

    private static boolean isMark(final int codePoint) {
        final int type = Character.getType(codePoint);

        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }
}
