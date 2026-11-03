package tcm.ui;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.ViewBox;

/**
 * SVG icons rendered with jsvg into TYPE_INT_ARGB. External resources are never loaded: the SVG may come from a foreign file
 * (an AppImage), and an icon must not reach the network or the disk.
 */
final class SvgImages {

    private static final LoaderContext CONTEXT = LoaderContext.builder()
        .externalResourcePolicy(ResourcePolicy.DENY_ALL)
        .build();

    private SvgImages() {
    }

    static @Nullable BufferedImage render(final @NotNull InputStream in, final @NotNull URI base, final int size) throws IOException {
        final SVGDocument doc = new SVGLoader().load(in, base, CONTEXT);
        if (doc == null) {
            return null;
        }
        final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            doc.render((Component) null, g, new ViewBox(0, 0, size, size));
        } finally {
            g.dispose();
        }

        return img;
    }
}
