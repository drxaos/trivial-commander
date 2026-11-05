package tcm.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.UIResource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.formdev.flatlaf.ui.FlatUIUtils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import tcm.core.VFile;

/**
 * Panel header as a breadcrumb path: every directory (and archive) of the path is underlined under the mouse and opens on a click.
 * A path wider than the header loses its leading parts, the end with the current directory stays visible.
 */
final class PathBar extends JComponent {

    private static final String ELLIPSIS = "…";

    /** A piece of the header text; target null — plain text (separators, mask, filter). */
    @Getter
    @RequiredArgsConstructor
    static final class Piece {
        private final @NotNull String text;
        private final @Nullable VFile target;
        /** Name to put the cursor on after opening the target: the next part of the path. */
        private final @Nullable String select;
    }

    interface Listener {

        void crumbClicked(@NotNull VFile target, @Nullable String select);

        /** A click outside the crumbs (count — click count). */
        void plainClicked(int count);
    }

    private final @NotNull Listener listener;
    private @NotNull List<Piece> pieces = List.of();
    /** X of the left edge of each piece in the last painting; -1 — the piece did not fit. */
    private int[] starts = new int[0];
    private int hover = -1;

    PathBar(final @NotNull Listener listener) {
        this.listener = listener;
        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(final MouseEvent e) {
                setHover(pieceAt(e.getX()));
            }

            @Override
            public void mouseExited(final MouseEvent e) {
                setHover(-1);
            }

            @Override
            public void mouseClicked(final MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                final int i = pieceAt(e.getX());
                if (i >= 0) {
                    // the second click of a double click comes to the directory already opened by the first one
                    if (e.getClickCount() == 1) {
                        final Piece p = pieces.get(i);
                        listener.crumbClicked(p.target, p.select);
                    }

                    return;
                }
                listener.plainClicked(e.getClickCount());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        updateUI();
    }

    /**
     * A bare JComponent has no UI delegate: the font follows the theme as a label's does. Text antialiasing as well — JComponent
     * takes it from the theme only in setUI, never called here, and without it text is drawn without antialiasing.
     */
    @Override
    public void updateUI() {
        super.updateUI();
        if (getFont() == null || getFont() instanceof UIResource) {
            setFont(UIManager.getFont("Label.font"));
        }
        putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING));
        putClientProperty(RenderingHints.KEY_TEXT_LCD_CONTRAST, UIManager.get(RenderingHints.KEY_TEXT_LCD_CONTRAST));
    }

    void setPieces(final @NotNull List<Piece> value) {
        pieces = List.copyOf(value);
        starts = new int[0];
        hover = -1;
        setCursor(Cursor.getDefaultCursor());
        revalidate();
        repaint();
    }

    /** The whole header as one string (tests, accessibility). */
    @NotNull String getText() {
        final StringBuilder sb = new StringBuilder();
        for (final Piece p : pieces) {
            sb.append(p.text);
        }

        return sb.toString();
    }

    private void setHover(final int index) {
        if (index == hover) {
            return;
        }
        hover = index;
        setCursor(index >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        repaint();
    }

    /** Index of the clickable piece under x, -1 — none. */
    private int pieceAt(final int x) {
        if (starts.length != pieces.size()) {
            return -1;
        }
        final FontMetrics fm = getFontMetrics(getFont());
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).target != null && starts[i] >= 0 && x >= starts[i] && x < starts[i] + fm.stringWidth(pieces.get(i).text)) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        final FontMetrics fm = getFontMetrics(getFont());
        final Insets in = getInsets();

        return new Dimension(in.left + in.right + fm.stringWidth(getText()), in.top + in.bottom + fm.getHeight());
    }

    @Override
    public Dimension getMinimumSize() {
        if (isMinimumSizeSet()) {
            return super.getMinimumSize();
        }
        final Dimension d = getPreferredSize();

        return new Dimension(0, d.height);
    }

    @Override
    protected void paintComponent(final Graphics g) {
        if (isOpaque()) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setFont(getFont());
            g2.setColor(getForeground());
            final FontMetrics fm = getFontMetrics(getFont());
            final Insets in = getInsets();
            final int avail = getWidth() - in.left - in.right;
            final int first = firstVisible(fm, avail);
            final int baseline = in.top + (getHeight() - in.top - in.bottom - fm.getHeight()) / 2 + fm.getAscent();
            int x = in.left;
            if (first > 0) {
                FlatUIUtils.drawString(this, g2, ELLIPSIS, x, baseline);
                x += fm.stringWidth(ELLIPSIS);
            }
            final int[] xs = new int[pieces.size()];
            for (int i = 0; i < pieces.size(); i++) {
                if (i < first) {
                    xs[i] = -1;
                    continue;
                }
                final String s = pieces.get(i).text;
                final int w = fm.stringWidth(s);
                xs[i] = x;
                FlatUIUtils.drawString(this, g2, s, x, baseline);
                if (i == hover) {
                    g2.fillRect(x, baseline + 1, w, 1);
                }
                x += w;
            }
            starts = xs;
        } finally {
            g2.dispose();
        }
    }

    /** The first piece to draw so that the rest (after the ellipsis) fits into avail; the last piece is always drawn. */
    private int firstVisible(final @NotNull FontMetrics fm, final int avail) {
        int total = 0;
        for (final Piece p : pieces) {
            total += fm.stringWidth(p.text);
        }
        if (total <= avail) {
            return 0;
        }
        final int ellipsis = fm.stringWidth(ELLIPSIS);
        int first = 0;
        while (first < pieces.size() - 1 && total + ellipsis > avail) {
            total -= fm.stringWidth(pieces.get(first).text);
            first++;
        }

        return first;
    }

    static @NotNull List<Piece> plain(final @NotNull String text) {
        final List<Piece> list = new ArrayList<>();
        list.add(new Piece(text, null, null));

        return list;
    }
}
