package tcm.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
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
    private static final Set<String> PENDING = new HashSet<>();
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        final Thread t = new Thread(r, "thumb-loader");
        t.setDaemon(true);

        return t;
    });

    private ThumbCache() {
    }

    /** A ready thumbnail or null; on a miss starts loading and then calls onLoaded on the EDT. */
    public static @Nullable ImageIcon get(final @NotNull VFile file, final @NotNull Runnable onLoaded) {
        final String key = file.getLocation() + "@" + file.getModified() + ":" + file.getSize();
        synchronized (CACHE) {
            final ImageIcon icon = CACHE.get(key);
            if (icon != null) {
                return icon == FAILED ? null : icon;
            }
            if (!PENDING.add(key)) {
                return null;
            }
        }
        LOADER.submit(() -> {
            final ImageIcon loaded = load(file);
            synchronized (CACHE) {
                PENDING.remove(key);
                CACHE.put(key, loaded == null ? FAILED : loaded);
            }
            if (loaded != null) {
                SwingUtilities.invokeLater(onLoaded);
            }
        });

        return null;
    }

    private static @Nullable ImageIcon load(final @NotNull VFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            return null;
        }
        try (InputStream in = Vfs.openInput(file)) {
            final BufferedImage img = ImageIO.read(in);
            if (img == null) {
                return null;
            }

            return new ImageIcon(scale(img, SIZE));
        } catch (final Exception e) {
            // corrupted or unsupported image — show the regular icon
            return null;
        }
    }

    public static @NotNull BufferedImage scale(final @NotNull BufferedImage img, final int box) {
        final double k = Math.min((double) box / img.getWidth(), (double) box / img.getHeight());
        final double factor = Math.min(k, 1.0);
        final int w = Math.max(1, (int) Math.round(img.getWidth() * factor));
        final int h = Math.max(1, (int) Math.round(img.getHeight() * factor));
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }

        return out;
    }
}
