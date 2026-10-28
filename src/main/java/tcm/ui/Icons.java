package tcm.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import javax.swing.Icon;
import javax.swing.UIManager;

import org.jetbrains.annotations.NotNull;

import tcm.core.VFile;

/**
 * Vector icons drawn in code: independent of resources and look the same in light and dark themes.
 */
public final class Icons {

    private static final Color FOLDER_FILL = new Color(0xF5C451);
    private static final Color FOLDER_EDGE = new Color(0xC8962A);
    private static final Color PAGE_FILL = new Color(0xFAFAFA);
    private static final Color PAGE_EDGE = new Color(0x8A8A8A);
    private static final Color ARCHIVE_FILL = new Color(0xB98A4E);
    private static final Color ARCHIVE_EDGE = new Color(0x7A5526);
    private static final Color IMAGE_SKY = new Color(0x6FA8DC);
    private static final Color IMAGE_HILL = new Color(0x5AA45A);
    private static final Color ACCENT = new Color(0x3C82D2);

    public static final Icon FOLDER = new Painted(16, Icons::paintFolder);
    public static final Icon FILE = new Painted(16, Icons::paintFile);
    public static final Icon ARCHIVE = new Painted(16, Icons::paintArchive);
    public static final Icon IMAGE = new Painted(16, Icons::paintImage);
    public static final Icon UP = new Painted(16, Icons::paintUp);

    public static final Icon REFRESH = new Painted(18, Icons::paintRefresh);
    public static final Icon BACK = new Painted(18, (g, s) -> paintArrow(g, s, true));
    public static final Icon FORWARD = new Painted(18, (g, s) -> paintArrow(g, s, false));
    public static final Icon THUMBS = new Painted(18, Icons::paintThumbs);
    public static final Icon SEARCH = new Painted(18, Icons::paintSearch);
    public static final Icon PACK = new Painted(18, (g, s) -> paintBox(g, s, true));
    public static final Icon UNPACK = new Painted(18, (g, s) -> paintBox(g, s, false));
    public static final Icon COMPARE = new Painted(18, Icons::paintCompare);
    public static final Icon NEW_TAB = new Painted(18, Icons::paintPlus);
    public static final Icon TERMINAL = new Painted(18, Icons::paintTerminal);
    public static final Icon HIDDEN = new Painted(18, Icons::paintEye);
    public static final Icon SWAP = new Painted(18, Icons::paintSwap);
    public static final Icon SAME_DIR = new Painted(18, Icons::paintSameDir);

    private Icons() {
    }

    public static @NotNull Icon forFile(final @NotNull VFile file) {
        if (file.isParentLink()) {
            return UP;
        }
        if (file.isDirectory()) {
            return file.isArchiveRoot() ? ARCHIVE : FOLDER;
        }
        if (file.isArchiveFile()) {
            return ARCHIVE;
        }

        return file.isImage() ? IMAGE : FILE;
    }

    /** Window icon in several sizes: the window manager picks the one it needs (title bar, taskbar, Alt+Tab). */
    public static @NotNull List<Image> windowImages(final @NotNull Icon icon) {
        final List<Image> images = new ArrayList<>();
        for (final int size : new int[] {16, 24, 32, 48, 64}) {
            final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = img.createGraphics();
            g.scale((double) size / icon.getIconWidth(), (double) size / icon.getIconHeight());
            icon.paintIcon(null, g, 0, 0);
            g.dispose();
            images.add(img);
        }

        return images;
    }

    private static final class Painted implements Icon {

        private final int size;
        private final BiConsumer<Graphics2D, Integer> painter;

        private Painted(final int size, final @NotNull BiConsumer<Graphics2D, Integer> painter) {
            this.size = size;
            this.painter = painter;
        }

        @Override
        public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.translate(x, y);
                painter.accept(g2, size);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private static @NotNull Color fg() {
        final Color c = UIManager.getColor("Label.foreground");

        return c == null ? Color.DARK_GRAY : c;
    }

    private static void paintFolder(final @NotNull Graphics2D g, final int s) {
        final Path2D p = new Path2D.Float();
        p.moveTo(1.5, 3.5);
        p.lineTo(6, 3.5);
        p.lineTo(7.5, 5);
        p.lineTo(14.5, 5);
        p.lineTo(14.5, 13.5);
        p.lineTo(1.5, 13.5);
        p.closePath();
        g.setColor(FOLDER_FILL);
        g.fill(p);
        g.setColor(FOLDER_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(p);
        g.drawLine(2, 6, 14, 6);
    }

    private static void paintFile(final @NotNull Graphics2D g, final int s) {
        final Path2D p = page();
        g.setColor(PAGE_FILL);
        g.fill(p);
        g.setColor(PAGE_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(p);
        g.drawLine(10, 1, 10, 5);
        g.drawLine(10, 5, 13, 5);
        g.setColor(new Color(0xB0B0B0));
        g.drawLine(5, 8, 11, 8);
        g.drawLine(5, 10, 11, 10);
        g.drawLine(5, 12, 9, 12);
    }

    private static @NotNull Path2D page() {
        final Path2D p = new Path2D.Float();
        p.moveTo(3.5, 1.5);
        p.lineTo(10, 1.5);
        p.lineTo(13.5, 5);
        p.lineTo(13.5, 14.5);
        p.lineTo(3.5, 14.5);
        p.closePath();

        return p;
    }

    private static void paintArchive(final @NotNull Graphics2D g, final int s) {
        g.setColor(ARCHIVE_FILL);
        g.fill(new RoundRectangle2D.Float(2, 3, 12, 11, 2, 2));
        g.setColor(ARCHIVE_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(2.5f, 3.5f, 11, 10, 2, 2));
        g.setColor(Color.WHITE);
        for (int y = 4; y < 12; y += 2) {
            g.drawLine(7, y, 8, y);
            g.drawLine(8, y + 1, 9, y + 1);
        }
        g.fillRect(6, 11, 4, 2);
    }

    private static void paintImage(final @NotNull Graphics2D g, final int s) {
        g.setColor(IMAGE_SKY);
        g.fillRect(1, 3, 14, 11);
        g.setColor(IMAGE_HILL);
        final Path2D p = new Path2D.Float();
        p.moveTo(1, 14);
        p.lineTo(6, 8);
        p.lineTo(9, 11);
        p.lineTo(11, 9);
        p.lineTo(15, 14);
        p.closePath();
        g.fill(p);
        g.setColor(new Color(0xFFE066));
        g.fillOval(10, 4, 3, 3);
        g.setColor(PAGE_EDGE);
        g.drawRect(1, 3, 13, 10);
    }

    private static void paintUp(final @NotNull Graphics2D g, final int s) {
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(8, 3, 8, 13);
        g.drawLine(4, 7, 8, 3);
        g.drawLine(12, 7, 8, 3);
    }

    private static void paintRefresh(final @NotNull Graphics2D g, final int s) {
        g.setColor(new Color(0x3FA34D));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Float(3, 3, 12, 12, 60, 280, Arc2D.OPEN));
        final Path2D p = new Path2D.Float();
        p.moveTo(12, 1.5);
        p.lineTo(13.5, 5.5);
        p.lineTo(9.5, 5.8);
        g.draw(p);
    }

    private static void paintArrow(final @NotNull Graphics2D g, final int s, final boolean left) {
        g.setColor(fg());
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (left) {
            g.drawLine(4, 9, 14, 9);
            g.drawLine(4, 9, 8, 5);
            g.drawLine(4, 9, 8, 13);
        } else {
            g.drawLine(4, 9, 14, 9);
            g.drawLine(14, 9, 10, 5);
            g.drawLine(14, 9, 10, 13);
        }
    }

    private static void paintThumbs(final @NotNull Graphics2D g, final int s) {
        g.setColor(IMAGE_SKY);
        g.fillRect(2, 2, 6, 6);
        g.fillRect(10, 10, 6, 6);
        g.setColor(IMAGE_HILL);
        g.fillRect(10, 2, 6, 6);
        g.fillRect(2, 10, 6, 6);
    }

    private static void paintSearch(final @NotNull Graphics2D g, final int s) {
        g.setColor(fg());
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawOval(3, 3, 9, 9);
        g.drawLine(11, 11, 15, 15);
    }

    private static void paintBox(final @NotNull Graphics2D g, final int s, final boolean in) {
        g.setColor(ARCHIVE_FILL);
        g.fillRect(2, 8, 14, 8);
        g.setColor(ARCHIVE_EDGE);
        g.drawRect(2, 8, 13, 7);
        g.setColor(in ? new Color(0xD04040) : new Color(0x3FA34D));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(9, 1, 9, 9);
        if (in) {
            g.drawLine(6, 6, 9, 9);
            g.drawLine(12, 6, 9, 9);
        } else {
            g.drawLine(6, 4, 9, 1);
            g.drawLine(12, 4, 9, 1);
        }
    }

    private static void paintCompare(final @NotNull Graphics2D g, final int s) {
        g.setColor(PAGE_FILL);
        g.fillRect(1, 2, 7, 13);
        g.fillRect(10, 2, 7, 13);
        g.setColor(PAGE_EDGE);
        g.drawRect(1, 2, 7, 13);
        g.drawRect(10, 2, 7, 13);
        g.setColor(new Color(0xD04040));
        g.drawLine(3, 7, 6, 7);
        g.drawLine(12, 7, 15, 7);
        g.setColor(PAGE_EDGE);
        g.drawLine(3, 10, 6, 10);
        g.drawLine(12, 10, 15, 10);
    }

    private static void paintPlus(final @NotNull Graphics2D g, final int s) {
        g.setColor(fg());
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawRect(2, 5, 14, 10);
        g.drawLine(2, 5, 7, 5);
        g.setColor(ACCENT);
        g.drawLine(9, 7, 9, 13);
        g.drawLine(6, 10, 12, 10);
    }

    private static void paintTerminal(final @NotNull Graphics2D g, final int s) {
        g.setColor(new Color(0x303030));
        g.fillRoundRect(1, 2, 16, 14, 3, 3);
        g.setColor(new Color(0x7CFC7C));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(4, 6, 7, 9);
        g.drawLine(4, 12, 7, 9);
        g.drawLine(9, 12, 13, 12);
    }

    private static void paintEye(final @NotNull Graphics2D g, final int s) {
        g.setColor(fg());
        g.setStroke(new BasicStroke(1.6f));
        g.draw(new Arc2D.Float(1, 4, 16, 14, 20, 140, Arc2D.OPEN));
        g.draw(new Arc2D.Float(1, 0, 16, 14, 200, 140, Arc2D.OPEN));
        g.fillOval(6, 6, 6, 6);
    }

    private static void paintSwap(final @NotNull Graphics2D g, final int s) {
        g.setColor(fg());
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(3, 6, 15, 6);
        g.drawLine(15, 6, 12, 3);
        g.drawLine(3, 12, 15, 12);
        g.drawLine(3, 12, 6, 15);
    }

    private static void paintSameDir(final @NotNull Graphics2D g, final int s) {
        g.translate(1, 0);
        paintFolder(g, s);
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(5, 8, 11, 8);
        g.drawLine(5, 11, 11, 11);
    }
}
