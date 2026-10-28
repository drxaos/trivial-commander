package tcm.apps;

import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainView;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.View;

import org.jetbrains.annotations.NotNull;

import com.formdev.flatlaf.ui.FlatTextAreaUI;

/**
 * Text area views that draw characters as plain glyphs, without shaping. Graphics.drawString of a run with
 * a complex script character (Arabic, Indic, Thai, a combining mark — every line of a binary file decoded as UTF-16)
 * builds a TextLayout and shapes the whole line with HarfBuzz, and measures it once more the same way; in the native image
 * those calls go through interpreted method handles, and each repaint of the window took seconds — the whole application froze.
 * Here only the visible part of a line is drawn, glyphs placed at the advances of the component's metrics (see PlainMetrics).
 * <p>
 * Only the int drawing methods are overridden: PlainView takes overriding of the float ones as a sign to measure text with
 * Font.getStringBounds — again a TextLayout.
 */
final class UnshapedViews {

    private UnshapedViews() {
    }

    /** UI of the viewer text area: while {@code unshaped} is true, the document gets an unshaped view. */
    static final class AreaUI extends FlatTextAreaUI {

        private final @NotNull BooleanSupplier unshaped;

        AreaUI(final @NotNull BooleanSupplier unshaped) {
            this.unshaped = unshaped;
        }

        /** A large text is wrapped in the background (LongLines.wrap): no WrappedPlainView, it re-breaks the whole document on the EDT. */
        @Override
        public View create(final Element elem) {
            return unshaped.getAsBoolean() ? new Plain(elem) : super.create(elem);
        }
    }

    private static final class Plain extends PlainView {

        Plain(final @NotNull Element elem) {
            super(elem);
        }

        @Override
        @SuppressWarnings("deprecation")
        protected int drawUnselectedText(final Graphics g, final int x, final int y, final int p0, final int p1)
            throws BadLocationException {
            return draw(this, this, g, x, y, p0, p1, unselectedColor(getContainer()));
        }

        @Override
        @SuppressWarnings("deprecation")
        protected int drawSelectedText(final Graphics g, final int x, final int y, final int p0, final int p1)
            throws BadLocationException {
            return draw(this, this, g, x, y, p0, p1, selectedColor(getContainer()));
        }
    }

    /** The same colors PlainView.paint picks (its fields are package-private). */
    private static @NotNull Color unselectedColor(final @NotNull Container c) {
        final JTextComponent host = (JTextComponent) c;

        return host.isEnabled() ? host.getForeground() : host.getDisabledTextColor();
    }

    private static @NotNull Color selectedColor(final @NotNull Container c) {
        final JTextComponent host = (JTextComponent) c;

        return host.getCaret().isSelectionVisible() && host.getHighlighter() != null ? host.getSelectedTextColor() : unselectedColor(c);
    }

    /**
     * Draws [p0, p1) from x on the baseline y, expanding tabs like Utilities.drawTabbedText; returns the x after the range.
     * Characters outside the clip are only measured.
     */
    private static int draw(final @NotNull View view, final @NotNull TabExpander tabs, final @NotNull Graphics g, final int x, final int y,
        final int p0, final int p1, final @NotNull Color color) throws BadLocationException {
        final JTextComponent host = (JTextComponent) view.getContainer();
        final Font font = host.getFont();
        final FontMetrics fm = host.getFontMetrics(font);
        final Segment s = new Segment();
        view.getDocument().getText(p0, p1 - p0, s);
        final Rectangle clip = g.getClipBounds();
        final int left = clip == null ? Integer.MIN_VALUE : clip.x;
        final int right = clip == null ? Integer.MAX_VALUE : clip.x + clip.width;
        final char[] glyphs = new char[s.count];
        final int[] xs = new int[s.count];
        int n = 0;
        int cx = x;
        final int end = s.offset + s.count;
        for (int i = s.offset; i < end; i++) {
            final char c = s.array[i];
            if (c == '\t') {
                cx = (int) tabs.nextTabStop(cx, p0 + i - s.offset);
                continue;
            }
            if (c == '\n' || c == '\r') {
                continue;
            }
            final int w = PlainMetrics.charWidth(fm, s, i - s.offset);
            if (cx + w >= left && cx <= right) {
                glyphs[n] = c;
                xs[n] = cx;
                n++;
            }
            cx += w;
        }
        if (n > 0 && g instanceof Graphics2D) {
            drawGlyphs((Graphics2D) g, host, font, glyphs, xs, n, y, color);
        }

        return cx;
    }

    private static void drawGlyphs(final @NotNull Graphics2D g, final @NotNull JTextComponent host, final @NotNull Font font,
        final char @NotNull [] glyphs, final int @NotNull [] xs, final int n, final int y, final @NotNull Color color) {
        // text antialiasing of the component, as SwingUtilities2.drawChars sets it
        final Object aa = host.getClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING);
        final Object contrast = host.getClientProperty(RenderingHints.KEY_TEXT_LCD_CONTRAST);
        final Object oldAa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        final Object oldContrast = g.getRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST);
        if (aa != null) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
        }
        if (contrast != null) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, contrast);
        }
        final char[] run = n == glyphs.length ? glyphs : Arrays.copyOf(glyphs, n);
        // createGlyphVector maps characters to glyphs one to one (fallback fonts included) — no layout, no shaping
        final GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), run);
        final int count = Math.min(n, gv.getNumGlyphs());
        final Point2D.Float p = new Point2D.Float();
        for (int i = 0; i < count; i++) {
            p.setLocation(xs[i] - xs[0], 0);
            gv.setGlyphPosition(i, p);
        }
        g.setColor(color);
        g.setFont(font);
        g.drawGlyphVector(gv, xs[0], y);
        if (aa != null && oldAa != null) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldAa);
        }
        if (contrast != null && oldContrast != null) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, oldContrast);
        }
    }
}
