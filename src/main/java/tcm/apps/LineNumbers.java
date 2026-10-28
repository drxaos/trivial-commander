package tcm.apps;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.Document;
import javax.swing.text.Element;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Line number column to the left of the text (row header of the JScrollPane). A click on a number selects the whole line
 * together with its line break, dragging over the numbers — several lines, Shift+click extends the selection.
 * With word wrap the number stands at the first row of the line.
 */
final class LineNumbers extends JComponent {

    private static final int PAD = 6;
    private static final int MIN_DIGITS = 2;

    private final @NotNull JTextArea area;
    /** Document line → number to show, 0 — no number (continuation of a line cut by the viewer); null — line index + 1. */
    private int @Nullable [] numbers;
    private int digits = MIN_DIGITS;
    /** Bounds of the line where the selection with the mouse started. */
    private int anchorStart;
    private int anchorEnd;
    private final DocumentListener documentListener = new DocumentListener() {
        @Override
        public void insertUpdate(final DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(final DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(final DocumentEvent e) {
            // attributes do not change in plain text
        }
    };

    LineNumbers(final @NotNull JTextArea area) {
        this.area = area;
        area.getDocument().addDocumentListener(documentListener);
        area.addPropertyChangeListener("document", e -> {
            if (e.getOldValue() instanceof Document) {
                ((Document) e.getOldValue()).removeDocumentListener(documentListener);
            }
            if (e.getNewValue() instanceof Document) {
                ((Document) e.getNewValue()).addDocumentListener(documentListener);
            }
            numbers = null;
            changed();
        });
        area.addPropertyChangeListener("font", e -> changed());
        // the column is as tall as the text: otherwise it scrolls only within its own height
        area.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                revalidate();
                repaint();
            }
        });
        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                area.requestFocusInWindow();
                final int[] line = lineAt(e.getY());
                if (!e.isShiftDown() || area.getSelectionStart() == area.getSelectionEnd()) {
                    anchorStart = line[0];
                    anchorEnd = line[1];
                }
                select(line);
            }

            @Override
            public void mouseDragged(final MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                select(lineAt(e.getY()));
                area.scrollRectToVisible(new Rectangle(area.getVisibleRect().x, e.getY(), 1, 1));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Numbers for a document whose long lines the viewer cut into pieces (see LongLines.split). */
    void setNumbers(final int @Nullable [] value) {
        numbers = value;
        changed();
    }

    private void changed() {
        final int n = Math.max(MIN_DIGITS, String.valueOf(maxNumber()).length());
        if (n != digits) {
            digits = n;
            revalidate();
        }
        repaint();
    }

    private int maxNumber() {
        final int[] nums = numbers;
        if (nums == null) {
            return area.getLineCount();
        }
        for (int i = nums.length - 1; i >= 0; i--) {
            if (nums[i] > 0) {
                return nums[i];
            }
        }

        return 1;
    }

    @Override
    public Dimension getPreferredSize() {
        final FontMetrics fm = getFontMetrics(area.getFont());

        return new Dimension(PAD * 2 + digits * fm.charWidth('0'), area.getHeight());
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            final Rectangle clip = g2.getClipBounds();
            g2.setColor(color("Panel.background", new Color(0xF2F2F2)));
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);
            g2.setColor(color("Separator.foreground", Color.LIGHT_GRAY));
            g2.drawLine(getWidth() - 1, clip.y, getWidth() - 1, clip.y + clip.height);
            final Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map) {
                g2.addRenderingHints((Map<?, ?>) hints);
            }
            g2.setFont(area.getFont());
            g2.setColor(color("Label.disabledForeground", Color.GRAY));
            paintNumbers(g2, clip);
        } finally {
            g2.dispose();
        }
    }

    private void paintNumbers(final @NotNull Graphics2D g, final @NotNull Rectangle clip) {
        final FontMetrics fm = g.getFontMetrics();
        final Element root = area.getDocument().getDefaultRootElement();
        final int first = root.getElementIndex(area.viewToModel2D(new Point(0, clip.y)));
        final int last = root.getElementIndex(area.viewToModel2D(new Point(0, clip.y + clip.height)));
        final int[] nums = numbers;
        for (int line = first; line <= last; line++) {
            final int n = nums == null ? line + 1 : line < nums.length ? nums[line] : 0;
            if (n == 0) {
                continue;
            }
            final Rectangle2D r;
            try {
                r = area.modelToView2D(root.getElement(line).getStartOffset());
            } catch (final BadLocationException e) {
                continue;
            }
            if (r == null) {
                continue;
            }
            final String s = Integer.toString(n);
            g.drawString(s, getWidth() - PAD - fm.stringWidth(s), (int) r.getY() + fm.getAscent());
        }
    }

    /** Start and end of the line at height y; a line cut by the viewer is taken whole, with all its pieces. */
    private int @NotNull [] lineAt(final int y) {
        final Document doc = area.getDocument();
        final Element root = doc.getDefaultRootElement();
        final int line = root.getElementIndex(area.viewToModel2D(new Point(0, Math.max(0, y))));
        int first = line;
        int last = line;
        final int[] nums = numbers;
        if (nums != null) {
            while (first > 0 && first < nums.length && nums[first] == 0) {
                first--;
            }
            while (last + 1 < nums.length && nums[last + 1] == 0) {
                last++;
            }
        }

        return new int[] {root.getElement(first).getStartOffset(), Math.min(root.getElement(last).getEndOffset(), doc.getLength())};
    }

    /** From the anchor line to the given one, in either direction. */
    private void select(final int @NotNull [] line) {
        final Caret caret = area.getCaret();
        if (line[0] >= anchorStart) {
            caret.setDot(anchorStart);
            caret.moveDot(line[1]);
        } else {
            caret.setDot(anchorEnd);
            caret.moveDot(line[0]);
        }
    }

    private static @NotNull Color color(final @NotNull String key, final @NotNull Color fallback) {
        final Color c = UIManager.getColor(key);

        return c == null ? fallback : c;
    }
}
