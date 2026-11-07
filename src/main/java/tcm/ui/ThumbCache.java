package tcm.ui;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;
import tcm.core.Vfs;

/**
 * Image thumbnails: LRU cache and background loading, so table scrolling does not lag.
 */
public final class ThumbCache {

    public static final int SIZE = 40;

    private static final int MAX_ENTRIES = 1000;
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final ImageIcon FAILED = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));

    private static final Map<String, ImageIcon> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, ImageIcon> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    /** Components waiting for a thumbnail: both panels may show the same directory. */
    private static final Map<String, Set<Component>> PENDING = new HashMap<>();
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        final Thread t = new Thread(r, "thumb-loader");
        t.setDaemon(true);

        return t;
    });

    private ThumbCache() {
    }

    /** A ready thumbnail or null; on a miss starts loading and then repaints every component that asked for it. */
    public static @Nullable ImageIcon get(final @NotNull VFile file, final @NotNull Component repaint) {
        final String key = file.getLocation() + "@" + file.getModified() + ":" + file.getSize();
        synchronized (CACHE) {
            final ImageIcon icon = CACHE.get(key);
            if (icon != null) {
                return icon == FAILED ? null : icon;
            }
            final Set<Component> waiting = PENDING.get(key);
            if (waiting != null) {
                waiting.add(repaint);

                return null;
            }
            PENDING.put(key, new LinkedHashSet<>(List.of(repaint)));
        }
        LOADER.submit(() -> {
            final ImageIcon loaded = load(file);
            final Set<Component> waiting;
            synchronized (CACHE) {
                waiting = PENDING.remove(key);
                CACHE.put(key, loaded == null ? FAILED : loaded);
            }
            if (loaded != null && waiting != null) {
                SwingUtilities.invokeLater(() -> waiting.forEach(Component::repaint));
            }
        });

        return null;
    }

    private static @Nullable ImageIcon load(final @NotNull VFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            return null;
        }
        try (InputStream in = Vfs.openInput(file)) {
            final String ext = file.getExtension().toLowerCase(Locale.ROOT);
            final BufferedImage img = ext.equals("svg") || ext.equals("svgz") ? SvgImages.thumbnail(in.readAllBytes(), SIZE)
                : ext.equals("ico") || ext.equals("icns") ? IconImages.decode(in.readAllBytes(), SIZE) : RasterImages.read(in.readAllBytes());
            if (img == null) {
                return null;
            }

            return new ImageIcon(scale(img, SIZE));
        } catch (final Exception | LinkageError e) {
            // corrupted or unsupported image, or a raster type missing from the native image — show the regular icon
            return null;
        }
    }

    /**
     * Fits the image into box x box (never enlarges) by averaging the source pixels of each target pixel. No Graphics: the native
     * image lacks java2d loops for the custom types ImageIO returns (3BYTE_BGR, palettes, 16-bit), and drawing them throws.
     */
    public static @NotNull BufferedImage scale(final @NotNull BufferedImage img, final int box) {
        final int sw = img.getWidth();
        final int sh = img.getHeight();
        final double k = Math.min(1.0, Math.min((double) box / sw, (double) box / sh));
        final int w = Math.max(1, (int) Math.round(sw * k));
        final int h = Math.max(1, (int) Math.round(sh * k));
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        // premultiplied sums, otherwise transparent pixels darken the edges
        final long[] a = new long[w];
        final long[] r = new long[w];
        final long[] g = new long[w];
        final long[] b = new long[w];
        final int[] n = new int[w];
        final int[] src = new int[sw];
        final int[] dst = new int[w];
        int ty = 0;
        for (int y = 0; y < sh; y++) {
            img.getRGB(0, y, sw, 1, src, 0, sw);
            for (int x = 0; x < sw; x++) {
                final int tx = (int) ((long) x * w / sw);
                final int p = src[x];
                final int pa = p >>> 24;
                a[tx] += pa;
                r[tx] += (long) ((p >> 16) & 0xFF) * pa;
                g[tx] += (long) ((p >> 8) & 0xFF) * pa;
                b[tx] += (long) (p & 0xFF) * pa;
                n[tx]++;
            }
            final int next = y + 1 < sh ? (int) ((long) (y + 1) * h / sh) : h;
            if (next != ty) {
                for (int x = 0; x < w; x++) {
                    dst[x] = a[x] == 0 ? 0 : (int) ((a[x] + n[x] / 2) / n[x]) << 24
                        | (int) (r[x] / a[x]) << 16
                        | (int) (g[x] / a[x]) << 8
                        | (int) (b[x] / a[x]);
                }
                out.setRGB(0, ty, w, 1, dst, 0, w);
                Arrays.fill(a, 0);
                Arrays.fill(r, 0);
                Arrays.fill(g, 0);
                Arrays.fill(b, 0);
                Arrays.fill(n, 0);
                ty = next;
            }
        }

        return out;
    }
}
