package tcm.apps;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Image in the viewer: zoom (mouse wheel, +/-, 1:1, fit), mouse dragging, rotation and flipping,
 * copying to the clipboard. Rotations and flips apply to the displayed picture, not to the file.
 */
final class ImageView extends JScrollPane {

    private static final double STEP = 1.25;
    private static final double MIN_SCALE = 0.02;
    private static final double MAX_SCALE = 32;

    private final Canvas canvas = new Canvas();
    private final @NotNull Runnable onChange;
    private @Nullable BufferedImage shown;
    /** Zoom chosen by the user (wheel, +/-, 1:1); while unset, the fit mode applies. */
    private boolean manual;
    private double zoom = 1.0;
    private boolean fitAll;
    private boolean fitLarge = true;
    private boolean center = true;
    private @Nullable Point dragStart;
    private @Nullable Point viewStart;

    ImageView(final @NotNull Runnable onChange) {
        this.onChange = onChange;
        setViewportView(canvas);
        getVerticalScrollBar().setUnitIncrement(24);
        getHorizontalScrollBar().setUnitIncrement(24);
        // left/right arrows flip through the directory's images rather than scroll the picture
        for (final String key : new String[] {"LEFT", "RIGHT", "KP_LEFT", "KP_RIGHT"}) {
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), "none");
        }
        canvas.setFocusable(true);
        installMouse();
    }

    @NotNull JComponent getFocusTarget() {
        return canvas;
    }

    /** New image: rotation/flip and manual zoom are reset, the fit mode is kept. */
    void setImage(final @Nullable BufferedImage image) {
        shown = image;
        manual = false;
        zoom = 1.0;
        refresh();
    }

    boolean hasImage() {
        return shown != null;
    }

    void refresh() {
        canvas.revalidate();
        canvas.repaint();
        onChange.run();
    }

    @NotNull String describe() {
        final BufferedImage img = shown;
        if (img == null) {
            return "";
        }
        final String fit = manual ? "" : fitAll ? ", fit" : fitLarge ? ", fit large" : "";

        return img.getWidth() + "x" + img.getHeight() + ", " + Math.round(scale() * 100) + "%" + fit;
    }

    // ---- zoom ----

    void zoomIn() {
        zoomAt(STEP, null);
    }

    void zoomOut() {
        zoomAt(1 / STEP, null);
    }

    void actualSize() {
        manual = true;
        zoom = 1.0;
        refresh();
    }

    void fit() {
        manual = false;
        fitAll = true;
        refresh();
    }

    void toggleFitAll() {
        manual = false;
        fitAll = !fitAll;
        refresh();
    }

    void toggleFitLarge() {
        manual = false;
        fitLarge = !fitLarge;
        fitAll = false;
        refresh();
    }

    void toggleCenter() {
        center = !center;
        refresh();
    }

    private double scale() {
        final BufferedImage img = shown;
        if (img == null || manual) {
            return zoom;
        }
        final Dimension avail = getViewport().getExtentSize();
        final int w = Math.max(50, avail.width - 4);
        final int h = Math.max(50, avail.height - 4);
        final boolean large = img.getWidth() > w || img.getHeight() > h;

        return fitAll || fitLarge && large ? Math.min((double) w / img.getWidth(), (double) h / img.getHeight()) : 1.0;
    }

    /** Zoom keeping the point under the mouse fixed (for keys — the center of the visible area). */
    private void zoomAt(final double factor, final @Nullable Point at) {
        final BufferedImage img = shown;
        if (img == null) {
            return;
        }
        final JViewport vp = getViewport();
        final Rectangle view = vp.getViewRect();
        final Point anchor = at != null ? at : new Point(view.x + view.width / 2, view.y + view.height / 2);
        final double old = scale();
        final Point origin = canvas.origin();
        final double imgX = (anchor.x - origin.x) / old;
        final double imgY = (anchor.y - origin.y) / old;
        manual = true;
        zoom = Math.max(MIN_SCALE, Math.min(MAX_SCALE, old * factor));
        canvas.revalidate();
        validate();
        final Point newOrigin = canvas.origin();
        final int x = (int) Math.round(newOrigin.x + imgX * zoom) - (anchor.x - view.x);
        final int y = (int) Math.round(newOrigin.y + imgY * zoom) - (anchor.y - view.y);
        scrollTo(x, y);
        canvas.repaint();
        onChange.run();
    }

    private void scrollTo(final int x, final int y) {
        final JViewport vp = getViewport();
        final Dimension size = vp.getViewSize();
        final Dimension extent = vp.getExtentSize();
        vp.setViewPosition(new Point(Math.max(0, Math.min(x, size.width - extent.width)), Math.max(0, Math.min(y, size.height - extent.height))));
    }

    // ---- rotation, flipping, clipboard ----

    void rotate(final boolean clockwise) {
        final BufferedImage img = shown;
        if (img == null) {
            return;
        }
        final AffineTransform t = new AffineTransform();
        if (clockwise) {
            t.translate(img.getHeight(), 0);
            t.quadrantRotate(1);
        } else {
            t.translate(0, img.getWidth());
            t.quadrantRotate(-1);
        }
        shown = transform(img, t, img.getHeight(), img.getWidth());
        refresh();
    }

    void flip(final boolean horizontal) {
        final BufferedImage img = shown;
        if (img == null) {
            return;
        }
        final AffineTransform t = horizontal
            ? new AffineTransform(-1, 0, 0, 1, img.getWidth(), 0)
            : new AffineTransform(1, 0, 0, -1, 0, img.getHeight());
        shown = transform(img, t, img.getWidth(), img.getHeight());
        refresh();
    }

    void copyToClipboard() {
        final BufferedImage img = shown;
        if (img != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(img), null);
        }
    }

    private static @NotNull BufferedImage transform(final @NotNull BufferedImage src, final @NotNull AffineTransform t, final int w, final int h) {
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();

        return out;
    }

    // ---- menu ----

    /** "Image" menu for the viewer window menu bar. */
    @NotNull JMenu createMenu() {
        final JMenu menu = new JMenu("Image");
        menu.setMnemonic('I');
        addItems(menu);

        return menu;
    }

    private void addItems(final @NotNull JComponent menu) {
        final JMenu zoomMenu = new JMenu("Zoom");
        zoomMenu.add(AppWindows.item("1:1", "ctrl 0", this::actualSize));
        zoomMenu.add(AppWindows.item("Fit", "F", this::fit));
        zoomMenu.add(AppWindows.item("Zoom in", "ADD", this::zoomIn));
        zoomMenu.add(AppWindows.item("Zoom out", "SUBTRACT", this::zoomOut));
        final JMenu flipMenu = new JMenu("Flip");
        flipMenu.add(AppWindows.item("Horizontal", "H", () -> flip(true)));
        flipMenu.add(AppWindows.item("Vertical", "shift H", () -> flip(false)));
        final JMenu rotateMenu = new JMenu("Rotate");
        rotateMenu.add(AppWindows.item("90° clockwise", "R", () -> rotate(true)));
        rotateMenu.add(AppWindows.item("90° counter-clockwise", "shift R", () -> rotate(false)));
        menu.add(zoomMenu);
        menu.add(flipMenu);
        menu.add(rotateMenu);
        menu.add(AppWindows.item("Copy image", "ctrl C", this::copyToClipboard));
    }

    // ---- mouse ----

    private void installMouse() {
        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                canvas.requestFocusInWindow();
                if (e.isPopupTrigger()) {
                    showPopup(e);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    dragStart = e.getLocationOnScreen();
                    viewStart = getViewport().getViewPosition();
                    canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(final MouseEvent e) {
                final Point start = dragStart;
                final Point view = viewStart;
                if (start != null && view != null) {
                    final Point now = e.getLocationOnScreen();
                    scrollTo(view.x - (now.x - start.x), view.y - (now.y - start.y));
                }
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
                dragStart = null;
                viewStart = null;
                canvas.setCursor(null);
            }

            @Override
            public void mouseWheelMoved(final MouseWheelEvent e) {
                zoomAt(Math.pow(STEP, -e.getPreciseWheelRotation()), e.getPoint());
            }
        };
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(mouse);
    }

    private void showPopup(final @NotNull MouseEvent e) {
        final JPopupMenu popup = new JPopupMenu();
        addItems(popup);
        popup.show(canvas, e.getX(), e.getY());
    }

    /** Canvas: the picture at the current zoom, centered in the view area if smaller than it. */
    private final class Canvas extends JComponent implements Scrollable {

        @Override
        public Dimension getPreferredSize() {
            final BufferedImage img = shown;
            if (img == null) {
                return new Dimension(0, 0);
            }
            final double k = scale();

            return new Dimension(Math.max(1, (int) Math.round(img.getWidth() * k)), Math.max(1, (int) Math.round(img.getHeight() * k)));
        }

        @NotNull Point origin() {
            final Dimension pref = getPreferredSize();
            if (!center) {
                return new Point(0, 0);
            }

            return new Point(Math.max(0, (getWidth() - pref.width) / 2), Math.max(0, (getHeight() - pref.height) / 2));
        }

        @Override
        protected void paintComponent(final Graphics g) {
            final BufferedImage img = shown;
            if (img == null) {
                return;
            }
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            final Dimension pref = getPreferredSize();
            final Point o = origin();
            g2.drawImage(img, o.x, o.y, pref.width, pref.height, null);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(final Rectangle visibleRect, final int orientation, final int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(final Rectangle visibleRect, final int orientation, final int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport && getParent().getWidth() > getPreferredSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
        }
    }

    private static final class ImageSelection implements Transferable {

        private final @NotNull BufferedImage image;

        private ImageSelection(final @NotNull BufferedImage image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public @NotNull Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }

            return image;
        }
    }
}
