package tcm.ui;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.FloatSize;
import com.github.weisj.jsvg.view.ViewBox;

/**
 * SVG rendered with jsvg into TYPE_INT_ARGB: icons, thumbnails and the image viewer. External resources are never loaded:
 * the SVG may come from a foreign file (an AppImage, a downloaded drawing), and showing it must not reach the network or the disk.
 * A .svgz (gzip-compressed SVG) is recognized by its content.
 */
public final class SvgImages {

    /** Without width/height and viewBox a drawing is shown in this square. */
    private static final int DEFAULT_SIDE = 512;
    /** A drawing declared huge (width="10000mm") is scaled down to fit this side: 64 MiB of ARGB at most. */
    private static final int MAX_SIDE = 4096;

    private static final LoaderContext CONTEXT = LoaderContext.builder()
        .externalResourcePolicy(ResourcePolicy.DENY_ALL)
        .build();

    private SvgImages() {
    }

    static @Nullable BufferedImage render(final @NotNull InputStream in, final @NotNull URI base, final int size) throws IOException {
        final SVGDocument doc = new SVGLoader().load(in, base, CONTEXT);

        return doc == null ? null : draw(doc, size, size);
    }

    public static boolean isSvg(final @NotNull String fileName) {
        final String lower = fileName.toLowerCase(Locale.ROOT);

        return lower.endsWith(".svg") || lower.endsWith(".svgz");
    }

    /** The drawing at its own size (width/height, otherwise the viewBox) for the image viewer; null — not an SVG. */
    public static @Nullable BufferedImage render(final byte @NotNull [] data) throws IOException {
        return render(data, MAX_SIDE, false);
    }

    /** The drawing fitted into box x box, keeping its proportions (enlarged too: it is vector); null — not an SVG. */
    public static @Nullable BufferedImage thumbnail(final byte @NotNull [] data, final int box) throws IOException {
        return render(data, box, true);
    }

    private static @Nullable BufferedImage render(final byte @NotNull [] data, final int box, final boolean enlarge) throws IOException {
        final SVGDocument doc;
        try (InputStream in = open(data)) {
            doc = new SVGLoader().load(in, URI.create("file:/"), CONTEXT);
        }
        if (doc == null) {
            return null;
        }
        final FloatSize size = doc.size();
        double w = size.width;
        double h = size.height;
        if (!(w >= 1 && h >= 1 && Double.isFinite(w) && Double.isFinite(h))) {
            w = DEFAULT_SIDE;
            h = DEFAULT_SIDE;
        }
        final double scale = enlarge || Math.max(w, h) > box ? box / Math.max(w, h) : 1.0;

        return draw(doc, Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)));
    }

    private static @NotNull InputStream open(final byte @NotNull [] data) throws IOException {
        final InputStream in = new ByteArrayInputStream(data);

        return data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B ? new GZIPInputStream(in) : in;
    }

    private static @NotNull BufferedImage draw(final @NotNull SVGDocument doc, final int width, final int height) {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            doc.render((Component) null, g, new ViewBox(0, 0, width, height));
        } finally {
            g.dispose();
        }

        return img;
    }
}
