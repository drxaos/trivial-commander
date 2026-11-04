package tcm.ui;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileSystemView;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;

/**
 * File icons associated by the system: on Linux from the freedesktop icon theme by MIME type, on Windows from the shell,
 * on macOS from Launch Services. Outside Windows the icons of exe/dll, ico/cur and AppImage files are read from the files themselves.
 * Directories and files without a system icon get null — the caller draws its own icon. Icons are looked up in the
 * background and cached per type (extension, MIME type), so the table does not lag on the first paint of a directory.
 */
public final class SystemIcons {

    public static final int SIZE = 16;

    private static final int MAX_ENTRIES = 2000;
    private static final Icon NONE = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
    private static final Provider NO_PROVIDER = new Provider() {
        @Override
        public @Nullable String key(final @NotNull VFile file) {
            return null;
        }

        @Override
        public @Nullable Icon load(final @NotNull String key, final @NotNull VFile file) {
            return null;
        }
    };

    /** Per-type keys stay small; per-file keys (programs) are many, hence LRU. */
    private static final Map<String, Icon> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Icon> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    /** Keys being looked up → components to repaint then: both panels may show the same directory and ask for the same icon. */
    private static final Map<String, Set<Component>> PENDING = new HashMap<>();
    /** Components painted before the provider was ready: repainted once it is. */
    private static final Set<Component> WAITERS = new LinkedHashSet<>();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "system-icons");
        t.setDaemon(true);

        return t;
    });

    private static volatile @Nullable Provider provider;
    private static boolean initStarted;

    private SystemIcons() {
    }

    /** Starts reading the MIME database and the icon theme in the background, before the first paint needs them. */
    public static void preload() {
        synchronized (CACHE) {
            startInit();
        }
    }

    /** A ready system icon or null; on a miss starts the lookup and then repaints the component. */
    public static @Nullable Icon get(final @NotNull VFile file, final @NotNull Component repaint) {
        if (file.isDirectory() || file.isParentLink()) {
            return null;
        }
        final Provider p = provider;
        if (p == null) {
            synchronized (CACHE) {
                WAITERS.add(repaint);
                startInit();
            }

            return null;
        }
        final String key = p.key(file);
        if (key == null) {
            return null;
        }
        synchronized (CACHE) {
            final Icon icon = CACHE.get(key);
            if (icon != null) {
                return icon == NONE ? null : icon;
            }
            final Set<Component> waiting = PENDING.get(key);
            if (waiting != null) {
                waiting.add(repaint);

                return null;
            }
            PENDING.put(key, new LinkedHashSet<>(List.of(repaint)));
        }
        LOADER.submit(() -> load(p, key, file));

        return null;
    }

    private static void load(final @NotNull Provider p, final @NotNull String key, final @NotNull VFile file) {
        Icon icon;
        try {
            icon = p.load(key, file);
        } catch (final Throwable e) {
            // a broken theme file, damaged exe or shell failure: the type keeps the program's own icon
            icon = null;
        }
        final Set<Component> waiting;
        synchronized (CACHE) {
            waiting = PENDING.remove(key);
            CACHE.put(key, icon == null ? NONE : icon);
        }
        if (icon != null && waiting != null) {
            SwingUtilities.invokeLater(() -> waiting.forEach(Component::repaint));
        }
    }

    private static void startInit() {
        if (initStarted) {
            return;
        }
        initStarted = true;
        LOADER.submit(() -> {
            final Provider p = createProvider();
            final List<Component> waiters;
            synchronized (CACHE) {
                provider = p;
                waiters = new ArrayList<>(WAITERS);
                WAITERS.clear();
            }
            SwingUtilities.invokeLater(() -> waiters.forEach(Component::repaint));
        });
    }

    private static @NotNull Provider createProvider() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return create(WindowsProvider::new);
        }
        final Provider base = os.contains("mac") ? create(MacProvider::new) : create(() -> new FreedesktopIcons(SIZE));

        return new EmbeddedProvider(base);
    }

    private static @NotNull Provider create(final @NotNull ProviderFactory factory) {
        try {
            return factory.create();
        } catch (final Throwable e) {
            // no MIME database / icon theme, or no access to the platform API — own icons for these types
            return NO_PROVIDER;
        }
    }

    /**
     * The image as TYPE_INT_ARGB centered on a SIZE x SIZE canvas: the name text in the table starts at the same offset in every row.
     * Pixels are copied through getRGB, not drawn: theme PNGs come in custom image types, and a native image has no Java2D loops
     * for drawing them (ClassNotFoundException: sun.java2d.loops.OpaqueCopyAnyToArgb).
     */
    static @NotNull Icon toIcon(final @NotNull BufferedImage img) {
        final BufferedImage fit = img.getWidth() > SIZE || img.getHeight() > SIZE ? ThumbCache.scale(img, SIZE) : toArgb(img);
        final int w = fit.getWidth();
        final int h = fit.getHeight();
        if (w == SIZE && h == SIZE) {
            return new ImageIcon(fit);
        }
        final BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        out.setRGB((SIZE - w) / 2, (SIZE - h) / 2, w, h, fit.getRGB(0, 0, w, h, null, 0, w), 0, w);

        return new ImageIcon(out);
    }

    private static @NotNull BufferedImage toArgb(final @NotNull BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB) {
            return img;
        }
        final int w = img.getWidth();
        final int h = img.getHeight();
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, img.getRGB(0, 0, w, h, null, 0, w), 0, w);

        return out;
    }

    /** A platform icon (shell, AppKit) painted into an image; null if it has no size. */
    private static @Nullable Icon fromPlatform(final @Nullable Icon icon) {
        if (icon == null || icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0) {
            return null;
        }
        final BufferedImage img = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }

        return toIcon(img);
    }

    /** The extension in lower case if it can be a file name part everywhere, otherwise null. */
    private static @Nullable String safeExtension(final @NotNull VFile file) {
        final String ext = file.getExtension().toLowerCase(Locale.ROOT);
        if (ext.isEmpty() || !ext.chars().allMatch(c -> c > ' ' && "<>:\"/\\|?*".indexOf(c) < 0)) {
            return null;
        }

        return ext;
    }

    interface Provider {

        /** Cache key of the file's icon type, or null if the system has no icon for it. Called on the EDT: must be cheap. */
        @Nullable String key(@NotNull VFile file);

        /** Looks the icon up; runs in the background. */
        @Nullable Icon load(@NotNull String key, @NotNull VFile file) throws Exception;
    }

    private interface ProviderFactory {

        @NotNull Provider create() throws Exception;
    }

    /**
     * Shell icons are asked by file, and an icon per type is wanted (also for archive entries): an empty sample file "x.ext"
     * in a temporary directory stands for the type.
     */
    private static final class Samples {

        private @Nullable Path dir;

        private @Nullable File of(final @NotNull String dotExt) {
            try {
                if (dir == null) {
                    dir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "tcm-icons"));
                }
                final Path p = dir.resolve("x" + dotExt);
                if (!Files.exists(p)) {
                    Files.createFile(p);
                }

                return p.toFile();
            } catch (final IOException e) {
                return null;
            }
        }
    }

    /** Windows shell icons; programs, shortcuts and icon files have their own icon each. */
    private static final class WindowsProvider implements Provider {

        private static final Set<String> OWN_ICON = Set.of("exe", "lnk", "ico", "cur", "ani", "url", "scr", "cpl", "msc");

        private final FileSystemView view = FileSystemView.getFileSystemView();
        private final Samples samples = new Samples();

        @Override
        public @Nullable String key(final @NotNull VFile file) {
            final String ext = safeExtension(file);
            if (ext == null) {
                return null;
            }

            return OWN_ICON.contains(ext) && file.isLocal() ? file.getLocation() : "." + ext;
        }

        @Override
        public @Nullable Icon load(final @NotNull String key, final @NotNull VFile file) {
            final File f = key.startsWith(".") ? samples.of(key) : file.getHostPath().toFile();

            return f == null ? null : fromPlatform(view.getSystemIcon(f, SIZE, SIZE));
        }
    }

    /**
     * macOS: Launch Services icons through the JDK's internal CImage.createImageOfFile — the same call the Aqua file chooser uses
     * ([NSWorkspace iconForFile:]). Needs "Add-Exports: java.desktop/sun.lwawt.macosx" (set in the jar manifest); without it
     * the constructor fails and our own icons stay.
     */
    private static final class MacProvider implements Provider {

        private final Method createImageOfFile;
        private final Samples samples = new Samples();

        private MacProvider() throws Exception {
            createImageOfFile = Class.forName("sun.lwawt.macosx.CImage").getMethod("createImageOfFile", String.class, int.class, int.class);
            final File probe = samples.of(".txt");
            if (probe == null || imageOf(probe) == null) {
                throw new IllegalStateException("no Launch Services icons");
            }
        }

        @Override
        public @Nullable String key(final @NotNull VFile file) {
            final String ext = safeExtension(file);

            return ext == null ? null : "." + ext;
        }

        @Override
        public @Nullable Icon load(final @NotNull String key, final @NotNull VFile file) throws Exception {
            final File f = samples.of(key);
            final Image img = f == null ? null : imageOf(f);

            // a multi-resolution image of SIZE x SIZE: painted at scale 1 it gives its base variant
            return img == null ? null : fromPlatform(new ImageIcon(img));
        }

        private @Nullable Image imageOf(final @NotNull File f) throws Exception {
            return (Image) createImageOfFile.invoke(null, f.getAbsolutePath(), SIZE, SIZE);
        }
    }

    /**
     * Icons stored in the files themselves, which Linux and macOS do not show: Windows programs (exe/dll), ico/cur files and
     * AppImages; only local files. A file without an icon (or not readable) gets the system icon of its type.
     */
    private static final class EmbeddedProvider implements Provider {

        private static final Set<String> PE = Set.of("exe", "dll", "cpl", "scr", "ocx", "ico", "cur", "icns");
        private static final String APPIMAGE = "appimage";
        private static final String PREFIX = "file:";

        private final Provider base;

        private EmbeddedProvider(final @NotNull Provider base) {
            this.base = base;
        }

        @Override
        public @Nullable String key(final @NotNull VFile file) {
            final String ext = file.getExtension().toLowerCase(Locale.ROOT);
            if (file.isLocal() && (PE.contains(ext) || ext.equals(APPIMAGE))) {
                return PREFIX + file.getLocation() + "@" + file.getModified() + ":" + file.getSize();
            }

            return base.key(file);
        }

        @Override
        public @Nullable Icon load(final @NotNull String key, final @NotNull VFile file) throws Exception {
            if (!key.startsWith(PREFIX)) {
                return base.load(key, file);
            }
            BufferedImage img;
            try {
                img = APPIMAGE.equals(file.getExtension().toLowerCase(Locale.ROOT))
                    ? AppImageIcons.read(file.getHostPath(), SIZE)
                    : PeIcons.read(file.getHostPath(), SIZE);
            } catch (final IOException e) {
                // truncated or damaged, or not of that format despite the extension
                img = null;
            }
            if (img != null) {
                return toIcon(img);
            }
            final String typeKey = base.key(file);

            return typeKey == null ? null : base.load(typeKey, file);
        }
    }
}
