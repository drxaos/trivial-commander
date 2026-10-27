package tcm.apps;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.UIManager;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Markdown to HTML for the viewer. JEditorPane understands only HTML 3.2 and simple CSS, so raw HTML
 * from the document is shown as text, and styling is defined by a minimal style sheet in theme colors.
 */
final class MarkdownHtml {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder()
        .extensions(EXTENSIONS)
        .build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
        .extensions(EXTENSIONS)
        .escapeHtml(true)
        // Swing draws table borders only from HTML attributes, it does not understand CSS border
        .attributeProviderFactory(context -> (node, tag, attributes) -> {
            if (node instanceof TableBlock) {
                attributes.put("border", "1");
                attributes.put("cellspacing", "0");
                attributes.put("cellpadding", "4");
            }
        })
        .build();

    private MarkdownHtml() {
    }

    /** dir — the file's directory for relative images; null — the file is in an archive, images have nowhere to come from. */
    static @NotNull String toHtml(final @NotNull String markdown, final float fontSize, final @Nullable Path dir) {
        final Node document = PARSER.parse(markdown);
        dropRemoteImages(document);
        final String base = dir == null ? "" : "<base href=\"" + dir.toUri() + "\">";

        return "<html><head>" + base + "<style>" + css(fontSize) + "</style></head><body>" + RENDERER.render(document) + "</body></html>";
    }

    /** Images from the network are not loaded: viewing a file must not go to the internet (the URL may be a tracker). */
    private static void dropRemoteImages(final @NotNull Node document) {
        final List<Image> remote = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(final Image image) {
                final String src = image.getDestination().toLowerCase(Locale.ROOT);
                if (src.startsWith("http:") || src.startsWith("https:") || src.startsWith("ftp:") || src.startsWith("//")) {
                    remote.add(image);
                }
            }
        });
        for (final Image image : remote) {
            final StringBuilder alt = new StringBuilder();
            for (Node n = image.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof Text) {
                    alt.append(((Text) n).getLiteral());
                }
            }
            image.insertBefore(new Text("[image: " + (alt.isEmpty() ? image.getDestination() : alt) + "]"));
            image.unlink();
        }
    }

    private static @NotNull String css(final float fontSize) {
        final Color bg = color("TextArea.background", Color.WHITE);
        final Color fg = color("TextArea.foreground", Color.BLACK);
        final Color code = mix(bg, fg, 0.08);
        final Color quote = mix(bg, fg, 0.55);
        final int size = Math.round(fontSize);

        return "body { font-family: sans-serif; font-size: " + size + "pt; margin: 8px 12px; background-color: " + hex(bg) + "; color: "
            + hex(fg) + "; }"
            // in Swing monospaced text is smaller than the main text by default
            + " pre { font-family: monospaced; font-size: " + size + "pt; background-color: " + hex(code) + "; padding: 6px; }"
            + " code { font-family: monospaced; font-size: " + size + "pt; background-color: " + hex(code) + "; }"
            + " blockquote { color: " + hex(quote) + "; margin-left: 12px; }"
            + " h1 { font-size: " + (size + 8) + "pt; } h2 { font-size: " + (size + 5) + "pt; } h3 { font-size: " + (size + 3) + "pt; }";
    }

    private static @NotNull Color color(final @NotNull String key, final @NotNull Color fallback) {
        final Color c = UIManager.getColor(key);

        return c != null ? c : fallback;
    }

    private static @NotNull Color mix(final @NotNull Color a, final @NotNull Color b, final double k) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * k), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * k),
            (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * k));
    }

    private static @NotNull String hex(final @NotNull Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
