package tcm.apps;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import tcm.core.TreeStats;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.util.Keys;
import tcm.util.TextUtil;

/**
 * Viewer content: text, binary, hex, HTML without tags, images. Used both in the F3 window
 * and in quick view (Ctrl+Q).
 */
public final class ViewerPanel extends JPanel {

    public enum Mode { AUTO, TEXT, BINARY, HEX, IMAGE, HTML, MARKDOWN, JSON }

    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DUMP_BYTES = 4 * 1024 * 1024;
    private static final int BINARY_WIDTH = 80;
    private static final int FAST_WHEEL = 5;
    /** Document property (AbstractDocument.I18NProperty is package-private): bidi/complex text views. */
    private static final String I18N_PROPERTY = "i18n";
    private static final int MAX_I18N_CHARS = 64 * 1024;
    private static final int READ_CHUNK = 64 * 1024;
    private static final int REWRAP_DELAY = 300;
    private static final int DEFAULT_WRAP_WIDTH = 800;
    private static final int MIN_WRAP_WIDTH = 100;
    private static final Charset CP1251 = Charset.forName("windows-1251");
    private static final Charset CP866 = Charset.forName("IBM866");

    private final CardLayout cards = new CardLayout();
    private final ViewerText text = new ViewerText();
    /** Large text: measured and drawn without shaping (see PlainMetrics, UnshapedViews). */
    private boolean unshapedMetrics;
    private final JScrollPane textScroll = new JScrollPane(text);
    private final LineNumbers lineNumbers = new LineNumbers(text);
    private final JEditorPane markdown = new JEditorPane() {
        @Override
        public void updateUI() {
            super.updateUI();
            useEditableBackground(this);
        }
    };
    private final JScrollPane markdownScroll = new JScrollPane(markdown);
    private final ImageView imageView = new ImageView(this::updateImageStatus);
    private final JLabel status = new JLabel(" ");
    private final TextFinder finder = new TextFinder();
    @Getter
    private @Nullable VFile file;
    private byte[] data = new byte[0];
    private boolean truncated;
    private @Nullable BufferedImage picture;
    private Mode mode = Mode.AUTO;
    /** Mode of the text on screen: AUTO resolved (binary data — BINARY, otherwise TEXT). */
    private Mode shownMode = Mode.TEXT;
    private @Nullable Charset charset;
    private float fontSize;
    private int loadGeneration;
    private @Nullable SwingWorker<?, ?> worker;
    private boolean showingImage;
    private boolean showingMarkdown;
    private boolean variableWidth;
    /** Viewer window menus available only for text / only for images; empty in quick view. */
    private final List<JMenu> textMenus = new ArrayList<>();
    private final List<JMenu> imageMenus = new ArrayList<>();
    /** The viewer window flips through the directory's images with ←/→; not set in quick view. */
    private @Nullable IntConsumer imageNavigator;
    /** Line length the shown text was cut at (see LongLines) and its longest line before cutting. */
    private int shownMaxLine;
    private int shownLongest;
    /** Row width in pixels a large text was wrapped at in the background (see LongLines.wrap); 0 — not wrapped so. */
    private int shownWrapWidth;
    /** After the viewer is resized, a text wrapped in the background is wrapped anew — once the size settles. */
    private final Timer rewrapTimer = new Timer(REWRAP_DELAY, e -> rewrap());
    /** Scroll position to return to after rewrapping, as a fraction of the text height; negative — to the start. */
    private float restoreScroll = -1;

    /** Text area of the viewer: a large text is measured and drawn without shaping (see PlainMetrics, UnshapedViews). */
    private final class ViewerText extends JTextArea {

        /** The same instance for the same font: the text view re-measures all lines whenever its metrics object changes. */
        private @Nullable PlainMetrics plain;

        @Override
        public void updateUI() {
            setUI(new UnshapedViews.AreaUI(() -> unshapedMetrics));
            useEditableBackground(this);
        }

        @Override
        public FontMetrics getFontMetrics(final Font f) {
            return unshapedMetrics ? plainMetrics(f) : super.getFontMetrics(f);
        }

        @NotNull PlainMetrics plainMetrics(final @NotNull Font f) {
            final PlainMetrics pm = plain;
            if (pm != null && pm.getFont().equals(f)) {
                return pm;
            }
            final PlainMetrics created = new PlainMetrics(super.getFontMetrics(f));
            plain = created;

            return created;
        }
    }

    public ViewerPanel() {
        super(new BorderLayout());
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        fontSize = text.getFont().getSize2D();
        text.getCaret().setVisible(false);
        final JPanel content = new JPanel(cards);
        textScroll.setRowHeaderView(lineNumbers);
        rewrapTimer.setRepeats(false);
        textScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                if (shownWrapWidth > 0 && wrapWidth() != shownWrapWidth) {
                    rewrapTimer.restart();
                }
            }
        });
        content.add(textScroll, "text");
        markdown.setEditable(false);
        markdown.setContentType("text/html");
        markdown.addHyperlinkListener(this::openLink);
        content.add(markdownScroll, "markdown");
        installFastWheel(textScroll);
        installFastWheel(markdownScroll);
        content.add(imageView, "image");
        add(content, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(status, BorderLayout.SOUTH);
        installKeys();
    }

    /** Background like editable text (white in the light theme): FlatLaf paints non-editable text gray. */
    private static void useEditableBackground(final @NotNull JTextComponent c) {
        final Color bg = UIManager.getColor("TextArea.background");
        if (bg != null) {
            // not UIResource: otherwise FlatLaf replaces it again with the non-editable text background
            c.setBackground(new Color(bg.getRGB(), true));
        }
    }

    public @NotNull JComponent getFocusTarget() {
        return showingImage ? imageView.getFocusTarget() : shownText();
    }

    public boolean isShowingImage() {
        return showingImage;
    }

    public void setImageNavigator(final @Nullable IntConsumer navigator) {
        imageNavigator = navigator;
    }

    /**
     * Viewer window menu bar. Image is available only for images, Edit and Options — only for text;
     * item keys work without the menu too.
     */
    public @NotNull JMenuBar createMenuBar(final @NotNull Runnable close) {
        final JMenuBar bar = new JMenuBar();
        bar.add(createFileMenu(close));
        final JMenu edit = createEditMenu();
        final JMenu options = createOptionsMenu();
        final JMenu image = imageView.createMenu();
        textMenus.add(edit);
        textMenus.add(options);
        imageMenus.add(image);
        bar.add(edit);
        bar.add(options);
        bar.add(image);
        final JMenu help = menu("Help", 'H');
        help.add(AppWindows.item("Keyboard reference", "F1", () -> AppWindows.showHelp(SwingUtilities.getWindowAncestor(this))));
        bar.add(help);
        updateMenus();

        return bar;
    }

    private @NotNull JMenu createFileMenu(final @NotNull Runnable close) {
        final JMenu menu = menu("File", 'F');
        menu.add(AppWindows.item("Save as...", null, this::saveAs));
        menu.add(AppWindows.item("Reload file", "F2", this::reload));
        menu.addSeparator();
        menu.add(AppWindows.item("Close", "ESCAPE", close));

        return menu;
    }

    private @NotNull JMenu createEditMenu() {
        final JMenu menu = menu("Edit", 'E');
        menu.add(AppWindows.item("Copy as text", "ctrl C", () -> shownText().copy()));
        menu.add(AppWindows.item("Select all", "ctrl A", () -> shownText().selectAll()));
        menu.addSeparator();
        menu.add(AppWindows.item("Find", "F7", () -> find(false)));
        menu.add(AppWindows.item("Find next", "F5", () -> finder.findNext(shownText(), false)));

        return menu;
    }

    private @NotNull JMenu createOptionsMenu() {
        final JMenu menu = menu("Options", 'O');
        final JCheckBoxMenuItem[] views = {
            numbered(1, "Text only", () -> setMode(Mode.TEXT)),
            numbered(2, "Binary (fixed line length)", () -> setMode(Mode.BINARY)),
            numbered(3, "Hex", () -> setMode(Mode.HEX)),
            numbered(4, "Image/Multimedia", () -> setMode(picture != null ? Mode.IMAGE : Mode.AUTO)),
            numbered(5, "HTML text (strip tags)", () -> setMode(Mode.HTML)),
            numbered(6, "Unicode", () -> setCharset(StandardCharsets.UTF_16LE)),
            numbered(7, "UTF-8", () -> setCharset(StandardCharsets.UTF_8)),
            numbered(8, "Markdown", () -> setMode(Mode.MARKDOWN)),
            numbered(9, "JSON (pretty-print)", () -> setMode(Mode.JSON)),
        };
        final JCheckBoxMenuItem ansi = AppWindows.check("ANSI (Windows charset)", "A", () -> setCharset(CP1251));
        final JCheckBoxMenuItem ascii = AppWindows.check("ASCII (DOS charset)", "S", () -> setCharset(CP866));
        final JCheckBoxMenuItem variable = AppWindows.check("Variable char width", "V", this::toggleVariableWidth);
        final JCheckBoxMenuItem wrap = AppWindows.check("Wrap text", "W", this::toggleWrap);
        final JCheckBoxMenuItem cursor = AppWindows.check("Show text cursor", "F6", this::toggleCursor);
        for (final JCheckBoxMenuItem it : views) {
            menu.add(it);
        }
        menu.addSeparator();
        menu.add(ansi);
        menu.add(ascii);
        menu.add(AppWindows.item("Other charset...", null, this::askCharset));
        menu.add(variable);
        menu.addSeparator();
        menu.add(wrap);
        menu.add(cursor);
        onMenuOpen(menu, () -> {
            final int view = currentView();
            for (int i = 0; i < views.length; i++) {
                views[i].setSelected(i + 1 == view);
            }
            final String cs = charsetName();
            ansi.setSelected(CP1251.name().equals(cs));
            ascii.setSelected(CP866.name().equals(cs));
            variable.setSelected(variableWidth);
            wrap.setSelected(text.getLineWrap());
            cursor.setSelected(text.getCaret().isVisible());
        });

        return menu;
    }

    /** Item number 1–9 in Options, same as the mode keys. */
    private int currentView() {
        if (showingImage) {
            return 4;
        }
        if (showingMarkdown) {
            return 8;
        }
        switch (shownMode) {
            case BINARY:
                return 2;
            case HEX:
                return 3;
            case HTML:
                return 5;
            case JSON:
                return 9;
            default:
                if (StandardCharsets.UTF_16LE.equals(charset)) {
                    return 6;
                }

                return StandardCharsets.UTF_8.equals(charset) ? 7 : 1;
        }
    }

    private static @NotNull JCheckBoxMenuItem numbered(final int n, final @NotNull String text, final @NotNull Runnable action) {
        final JCheckBoxMenuItem it = AppWindows.check(n + "  " + text, String.valueOf(n), action);
        it.setMnemonic('0' + n);

        return it;
    }

    private static @NotNull JMenu menu(final @NotNull String text, final char mnemonic) {
        final JMenu menu = new JMenu(text);
        menu.setMnemonic(mnemonic);

        return menu;
    }

    private static void onMenuOpen(final @NotNull JMenu menu, final @NotNull Runnable action) {
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(final MenuEvent e) {
                action.run();
            }

            @Override
            public void menuDeselected(final MenuEvent e) {
                // check marks are updated on the next open
            }

            @Override
            public void menuCanceled(final MenuEvent e) {
                // check marks are updated on the next open
            }
        });
    }

    private void updateMenus() {
        for (final JMenu m : textMenus) {
            m.setEnabled(!showingImage);
        }
        for (final JMenu m : imageMenus) {
            m.setEnabled(showingImage);
        }
    }

    /** Saves the viewed file in full (only its beginning may be shown). */
    private void saveAs() {
        final VFile f = file;
        if (f == null || f.isDirectory()) {
            return;
        }
        final Path target = AppWindows.askSaveAs(this, f);
        if (target == null || f.isLocal() && target.equals(f.getHostPath())) {
            return;
        }
        try (InputStream in = Vfs.openInput(f)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            JOptionPane.showMessageDialog(this, "Cannot save " + target + "\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Loading in the background; in quick view files change often — stale loads are discarded.
     * A viewer window closed during loading cancels it (cancel).
     */
    public void load(final @Nullable VFile target) {
        file = target;
        final int gen = startGeneration();
        if (target == null) {
            showText(new PlainDocument(), "", "");

            return;
        }
        status.setText("Loading " + target.getName() + "...");
        start(new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                return read(target);
            }

            @Override
            protected void done() {
                if (gen != loadGeneration || isCancelled()) {
                    return;
                }
                try {
                    final Object[] r = get();
                    data = (byte[]) r[0];
                    truncated = (Boolean) r[1];
                    picture = (BufferedImage) r[2];
                    imageView.setImage(picture);
                    if (r[3] != null) {
                        shownMode = Mode.TEXT;
                        showText(plainDocument((String) r[3]), target.getLocation(), "");
                        shownLongest = 0;
                        shownWrapWidth = 0;

                        return;
                    }
                    render();
                } catch (final InterruptedException | CancellationException e) {
                    // loading was cancelled — nothing to show
                } catch (final ExecutionException e) {
                    showText(plainDocument("Cannot read file:\n" + e.getCause().getMessage()), target.getLocation(), "");
                }
            }
        });
    }

    /** Stop loading and conversion: the viewer window is closed, the result is no longer needed. */
    public void cancel() {
        startGeneration();
    }

    /** New load generation: previous background work is interrupted, its result will be discarded. */
    private int startGeneration() {
        final SwingWorker<?, ?> w = worker;
        if (w != null) {
            w.cancel(true);
            worker = null;
        }

        return ++loadGeneration;
    }

    private void start(final @NotNull SwingWorker<?, ?> w) {
        worker = w;
        w.execute();
    }

    private static Object[] read(final @NotNull VFile target) throws IOException {
        if (target.isDirectory()) {
            final TreeStats st = TreeStats.of(target.isParentLink() ? List.of() : List.of(target), () -> Thread.currentThread().isInterrupted());
            final String info = "Directory: " + target.getLocation() + "\n\n" + TextUtil.groupDigits(st.getFiles()) + " file(s), "
                + TextUtil.groupDigits(Math.max(0, st.getDirs() - 1)) + " dir(s)\n" + TextUtil.groupDigits(st.getBytes()) + " bytes";

            return new Object[] {new byte[0], Boolean.FALSE, null, info};
        }
        final byte[] bytes;
        final boolean cut;
        try (InputStream in = Vfs.openInput(target)) {
            bytes = readCancellable(in);
            cut = in.read() >= 0;
        }
        BufferedImage img = null;
        if (target.isImage()) {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        }

        return new Object[] {bytes, cut, img, null};
    }

    /**
     * The first MAX_BYTES in chunks: unpacking from an archive does not react to interruption by itself,
     * and a closed viewer window must not keep reading.
     */
    private static byte[] readCancellable(final @NotNull InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[READ_CHUNK];
        while (out.size() < MAX_BYTES) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            final int n = in.read(buf, 0, Math.min(buf.length, MAX_BYTES - out.size()));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
        }

        return out.toByteArray();
    }

    /** Recompute image scale after the window is resized. */
    public void refit() {
        if (showingImage) {
            imageView.refresh();
        }
    }

    public void reload() {
        load(file);
    }

    public void setMode(final @NotNull Mode value) {
        mode = value;
        render();
    }

    public void setCharset(final @Nullable Charset value) {
        charset = value;
        if (mode == Mode.IMAGE || mode == Mode.HEX || mode == Mode.BINARY) {
            mode = Mode.TEXT;
        }
        render();
    }

    /**
     * Display in the selected mode. Conversion (decoding, hex, markup) and document building run in the background:
     * for 32 MB that takes seconds, and neither the viewer nor the main window may freeze all that time.
     */
    private void render() {
        final VFile f = file;
        if (f == null) {
            return;
        }
        final Mode effective = mode == Mode.AUTO ? autoMode(f) : mode;
        if (effective == Mode.IMAGE && picture != null) {
            startGeneration();
            showImage();

            return;
        }
        final int gen = startGeneration();
        final byte[] bytes = data;
        final Charset cs = charset;
        final float size = fontSize;
        final int maxLine = LongLines.maxChars(text, text.getFont());
        final PlainMetrics widths = text.plainMetrics(text.getFont());
        final boolean wrap = text.getLineWrap();
        final boolean words = text.getWrapStyleWord();
        final int wrapWidth = wrapWidth();
        final int tabWidth = text.getTabSize() * widths.charWidth('m');
        final float scroll = restoreScroll;
        restoreScroll = -1;
        final HTMLEditorKit kit = (HTMLEditorKit) markdown.getEditorKit();
        status.setText("Converting " + f.getName() + "...");
        start(new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                final String name = charsetName(bytes, cs);
                if (effective == Mode.MARKDOWN) {
                    final Path dir = f.isLocal() ? f.getHostPath().getParent() : null;

                    return new Object[] {htmlDocument(kit, MarkdownHtml.toHtml(decode(bytes, cs), size, dir)), name};
                }
                final String converted = convert(effective, bytes, cs);
                final boolean large = converted.length() > MAX_I18N_CHARS;
                if (large) {
                    widths.measureAll(converted);
                }
                final LongLines.Split split = large && wrap
                    ? LongLines.wrap(converted, widths, wrapWidth, tabWidth, words)
                    : LongLines.split(converted, maxLine);

                return new Object[] {plainDocument(split.getText()), name, split.getNumbers(), split.getLongest(), large && wrap ? wrapWidth : 0};
            }

            @Override
            protected void done() {
                if (gen != loadGeneration || isCancelled()) {
                    return;
                }
                try {
                    final Object[] r = get();
                    if (effective == Mode.MARKDOWN) {
                        showMarkdown((HTMLDocument) r[0], f.getLocation(), (String) r[1]);
                    } else {
                        shownMode = effective;
                        showText((Document) r[0], f.getLocation(), (String) r[1]);
                        lineNumbers.setNumbers((int[]) r[2]);
                        // the hex dump has its own offsets
                        textScroll.setRowHeaderView(effective == Mode.HEX ? null : lineNumbers);
                        shownMaxLine = maxLine;
                        shownLongest = (Integer) r[3];
                        shownWrapWidth = (Integer) r[4];
                        if (scroll >= 0) {
                            final JScrollBar bar = textScroll.getVerticalScrollBar();
                            // after the new document is laid out
                            SwingUtilities.invokeLater(() -> bar.setValue(Math.round(scroll * bar.getMaximum())));
                        }
                    }
                } catch (final InterruptedException | CancellationException e) {
                    // conversion was cancelled — nothing to show
                } catch (final ExecutionException e) {
                    showText(plainDocument("Cannot show file:\n" + e.getCause()), f.getLocation(), "");
                }
            }
        });
    }

    /**
     * Without an explicit mode: an image, rendered Markdown, a dump of binary data (as text it is megabytes of control characters
     * and random letters of all alphabets), otherwise text. A charset chosen by hand (6, 7, A, S) means text.
     */
    private @NotNull Mode autoMode(final @NotNull VFile f) {
        if (picture != null) {
            return Mode.IMAGE;
        }
        if (isMarkdownFile(f)) {
            return Mode.MARKDOWN;
        }

        return charset == null && TextUtil.looksBinary(data) ? Mode.BINARY : Mode.TEXT;
    }

    private static @NotNull String convert(final @NotNull Mode effective, final byte @NotNull [] bytes, final @Nullable Charset cs) {
        switch (effective) {
            case HEX:
                return hexDump(bytes);
            case BINARY:
                return binaryDump(bytes, cs);
            case HTML:
                return stripHtml(decode(bytes, cs));
            case JSON:
                return JsonPretty.format(decode(bytes, cs));
            default:
                return decode(bytes, cs);
        }
    }

    /**
     * The document is built off the EDT: until it is shown, Swing does not access it.
     * A combining mark (e.g. a stress mark U+0301) or RTL text sets the "i18n" property, and the text area then lays out
     * every glyph of the whole document on the EDT — tens of seconds for megabytes, the whole application freezes.
     * A large text gets the plain view: drawString still draws such characters itself.
     */
    private static @NotNull Document plainDocument(final @NotNull String body) {
        final PlainDocument doc = new PlainDocument();
        try {
            doc.insertString(0, body, null);
        } catch (final BadLocationException e) {
            // insertion at the start of an empty document
            throw new IllegalStateException(e);
        }
        if (body.length() > MAX_I18N_CHARS) {
            doc.putProperty(I18N_PROPERTY, Boolean.FALSE);
        }

        return doc;
    }

    private static @NotNull HTMLDocument htmlDocument(final @NotNull HTMLEditorKit kit, final @NotNull String html)
        throws IOException, BadLocationException {
        final HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
        kit.read(new StringReader(html), doc, 0);

        return doc;
    }

    private static @NotNull String decode(final byte @NotNull [] bytes, final @Nullable Charset cs) {
        if (cs != null) {
            return new String(bytes, cs);
        }

        return TextUtil.decode(bytes).getText();
    }

    private @NotNull String charsetName() {
        return charsetName(data, charset);
    }

    private static @NotNull String charsetName(final byte @NotNull [] bytes, final @Nullable Charset cs) {
        return cs != null ? cs.name() : TextUtil.decode(bytes.length > 65536 ? Arrays.copyOf(bytes, 65536) : bytes)
            .getCharset()
            .name();
    }

    /** .md opens already rendered (as in TC viewer plugins); 1 — source text. */
    private static boolean isMarkdownFile(final @NotNull VFile f) {
        final String name = f.getName().toLowerCase(Locale.ROOT);

        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private void showMarkdown(final @NotNull HTMLDocument doc, final @NotNull String location, final @NotNull String charsetName) {
        final boolean focused = getFocusTarget().isFocusOwner();
        showingImage = false;
        showingMarkdown = true;
        updateMenus();
        markdown.setDocument(doc);
        markdown.setCaretPosition(0);
        cards.show((JPanel) markdownScroll.getParent(), "markdown");
        if (focused) {
            markdown.requestFocusInWindow();
        }
        status.setText(location + "   [markdown, " + charsetName + "]" + (truncated ? "   (first " + TextUtil.shortSize(MAX_BYTES) + " shown)" : ""));
    }

    /** Links from Markdown: web and mail — to the browser, everything else (files, anchors) is not opened. */
    private void openLink(final @NotNull HyperlinkEvent e) {
        if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED || e.getURL() == null) {
            return;
        }
        final String scheme = e.getURL().getProtocol().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) {
            return;
        }
        try {
            Desktop.getDesktop().browse(e.getURL().toURI());
        } catch (final IOException | URISyntaxException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Cannot open " + e.getURL() + "\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Text currently on screen: plain or rendered Markdown. */
    private @NotNull JTextComponent shownText() {
        return showingMarkdown ? markdown : text;
    }

    /** Empty charsetName — what is shown is not the file content (directory info, an error). */
    private void showText(final @NotNull Document doc, final @NotNull String location, final @NotNull String charsetName) {
        final boolean focused = getFocusTarget().isFocusOwner();
        showingImage = false;
        showingMarkdown = false;
        updateMenus();
        // before setDocument: the new view takes its metrics once
        unshapedMetrics = doc.getLength() > MAX_I18N_CHARS;
        text.setDocument(doc);
        text.setCaretPosition(0);
        cards.show((JPanel) textScroll.getParent(), "text");
        if (focused) {
            text.requestFocusInWindow();
        }
        if (charsetName.isEmpty()) {
            status.setText(location);

            return;
        }
        final String modeName = shownMode.name().toLowerCase(Locale.ROOT);
        status.setText(location + "   [" + modeName + ", " + charsetName + (text.getLineWrap() ? ", wrap" : "") + "]"
            + (truncated ? "   (first " + TextUtil.shortSize(MAX_BYTES) + " shown)" : ""));
    }

    private void showImage() {
        final boolean focused = shownText().isFocusOwner();
        cards.show((JPanel) imageView.getParent(), "image");
        showingImage = true;
        showingMarkdown = false;
        updateMenus();
        imageView.refresh();
        if (focused) {
            imageView.getFocusTarget().requestFocusInWindow();
        }
    }

    private void updateImageStatus() {
        if (showingImage) {
            status.setText(file + "   [" + imageView.describe() + "]");
        }
    }

    private static @NotNull String hexDump(final byte @NotNull [] data) {
        final int n = Math.min(data.length, MAX_DUMP_BYTES);
        final StringBuilder sb = new StringBuilder(n * 4 + 64);
        for (int off = 0; off < n; off += 16) {
            checkCancelled(off);
            sb.append(String.format("%08X: ", off));
            final StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (off + i < n) {
                    final int b = data[off + i] & 0xFF;
                    sb.append(String.format("%02X ", b));
                    ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(" ").append(ascii).append('\n');
        }
        if (n < data.length) {
            sb.append("... (first ").append(TextUtil.shortSize(n)).append(" shown)\n");
        }

        return sb.toString();
    }

    private static @NotNull String binaryDump(final byte @NotNull [] data, final @Nullable Charset cs) {
        final int n = Math.min(data.length, MAX_DUMP_BYTES);
        final String s = new String(data, 0, n, cs != null ? cs : StandardCharsets.ISO_8859_1);
        final StringBuilder sb = new StringBuilder(n + n / BINARY_WIDTH + 16);
        for (int i = 0; i < s.length(); i += BINARY_WIDTH) {
            checkCancelled(i);
            final String line = s.substring(i, Math.min(s.length(), i + BINARY_WIDTH));
            for (final char c : line.toCharArray()) {
                // C0 and C1 (ISO-8859-1 0x80–0x9F) control characters have no glyph
                sb.append(c < 32 || c >= 0x7F && c < 0xA0 ? '.' : c);
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /** Long conversion loops check now and then whether they were cancelled (viewer closed, file changed). */
    private static void checkCancelled(final int position) {
        if ((position & 0xFFFF) == 0 && Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    private static @NotNull String stripHtml(final @NotNull String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
            .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</h\\d>|</li>|</tr>", "\n")
            .replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replaceAll("\n{3,}", "\n\n");
    }

    // ---- viewer keys ----

    private void installKeys() {
        Keys.bindAncestor(this, "F2", this::reload);
        Keys.bindAncestor(this, "BACK_SPACE", () -> {
            mode = Mode.AUTO;
            charset = null;
            reload();
        });
        Keys.bindAncestor(this, "F7", () -> find(false));
        Keys.bindAncestor(this, "ctrl F", () -> find(false));
        Keys.bindAncestor(this, "ctrl F7", () -> find(true));
        for (final String k : new String[] {"F3", "F5"}) {
            Keys.bindAncestor(this, k, () -> finder.findNext(shownText(), false));
            Keys.bindAncestor(this, "shift " + k, () -> {
                finder.findNext(shownText(), !finder.isBackward());
                finder.setBackward(false);
            });
            Keys.bindAncestor(this, "ctrl " + k, () -> {
                finder.setBackward(!finder.isBackward());
                finder.findNext(shownText(), false);
            });
        }
        Keys.bindAncestor(this, "shift F7", () -> {
            finder.findNext(shownText(), false);
            finder.setBackward(false);
        });
        Keys.bindAncestor(this, "F6", this::toggleCursor);
        Keys.bindAncestor(this, "SPACE", () -> page(1));
        Keys.bindAncestor(this, "shift SPACE", () -> page(-1));
        Keys.bindAncestor(this, "LEFT", () -> leftRight(-1));
        Keys.bindAncestor(this, "RIGHT", () -> leftRight(1));
        Keys.bindAncestor(this, "shift LEFT", () -> scrollHorizontal(-1));
        Keys.bindAncestor(this, "shift RIGHT", () -> scrollHorizontal(1));
        Keys.bindAncestor(this, "ctrl A", () -> shownText().selectAll());
        Keys.bindAncestor(this, "ctrl C", () -> {
            if (showingImage) {
                imageView.copyToClipboard();
            } else {
                shownText().copy();
            }
        });
        Keys.bindAncestor(this, "ctrl shift C", this::copyHex);
        Keys.bindAncestor(this, "ctrl P", this::print);
        Keys.bindAncestor(this, "A", () -> setCharset(CP1251));
        Keys.bindAncestor(this, "S", () -> setCharset(CP866));
        Keys.bindAncestor(this, "V", this::toggleVariableWidth);
        Keys.bindAncestor(this, "W", this::toggleWrap);
        installModeKeys();
        installImageKeys();
    }

    private void installImageKeys() {
        imageKey("F", imageView::toggleFitAll);
        imageKey("L", imageView::toggleFitLarge);
        imageKey("C", imageView::toggleCenter);
        imageKey("H", () -> imageView.flip(true));
        imageKey("shift H", () -> imageView.flip(false));
        imageKey("R", () -> imageView.rotate(true));
        imageKey("shift R", () -> imageView.rotate(false));
    }

    private void imageKey(final @NotNull String key, final @NotNull Runnable action) {
        Keys.bindAncestor(this, key, () -> {
            if (showingImage) {
                action.run();
            }
        });
    }

    /** ←/→: in an image — the neighboring image of the directory (in the viewer window), in text — horizontal scroll. */
    private void leftRight(final int direction) {
        final IntConsumer navigator = imageNavigator;
        if (showingImage && navigator != null) {
            navigator.accept(direction);

            return;
        }
        scrollHorizontal(direction * 10);
    }

    private void installModeKeys() {
        Keys.bindAncestor(this, "1", () -> setMode(Mode.TEXT));
        Keys.bindAncestor(this, "2", () -> setMode(Mode.BINARY));
        Keys.bindAncestor(this, "3", () -> setMode(Mode.HEX));
        Keys.bindAncestor(this, "4", () -> setMode(picture != null ? Mode.IMAGE : Mode.AUTO));
        Keys.bindAncestor(this, "5", () -> setMode(Mode.HTML));
        Keys.bindAncestor(this, "8", () -> setMode(Mode.MARKDOWN));
        Keys.bindAncestor(this, "9", () -> setMode(Mode.JSON));
        Keys.bindAncestor(this, "6", () -> setCharset(StandardCharsets.UTF_16LE));
        Keys.bindAncestor(this, "7", () -> setCharset(StandardCharsets.UTF_8));
        Keys.bindAncestor(this, "ADD", () -> zoom(1.25f));
        Keys.bindAncestor(this, "SUBTRACT", () -> zoom(0.8f));
        Keys.bindAncestor(this, "ctrl 0", () -> zoom(0));
        Keys.bindAncestor(this, "ctrl NUMPAD0", () -> zoom(0));
    }

    private void find(final boolean backward) {
        final String sel = shownText().getSelectedText();
        if (sel != null && !sel.isEmpty() && sel.length() < 100) {
            finder.setQuery(sel);
        }
        if (finder.ask(this, backward)) {
            finder.findNext(shownText(), false);
        }
    }

    /** The wheel rolled with the wheel pressed scrolls FAST_WHEEL times more lines: extra is added to normal scrolling. */
    private static void installFastWheel(final @NotNull JScrollPane sp) {
        sp.addMouseWheelListener(e -> {
            if ((e.getModifiersEx() & InputEvent.BUTTON2_DOWN_MASK) == 0 || e.getWheelRotation() == 0) {
                return;
            }
            final JScrollBar bar = sp.getVerticalScrollBar();
            final int direction = e.getWheelRotation() < 0 ? -1 : 1;
            bar.setValue(bar.getValue() + (FAST_WHEEL - 1) * e.getUnitsToScroll() * bar.getUnitIncrement(direction));
        });
    }

    private @NotNull JScrollPane shownScroll() {
        return showingImage ? imageView : showingMarkdown ? markdownScroll : textScroll;
    }

    private void page(final int direction) {
        final JScrollPane sp = shownScroll();
        final JScrollBar bar = sp.getVerticalScrollBar();
        bar.setValue(bar.getValue() + direction * bar.getVisibleAmount());
    }

    private void scrollHorizontal(final int chars) {
        final JScrollPane sp = shownScroll();
        final JScrollBar bar = sp.getHorizontalScrollBar();
        final int unit = text.getFontMetrics(text.getFont()).charWidth('m');
        bar.setValue(bar.getValue() + chars * unit);
    }

    private void toggleWrap() {
        text.setLineWrap(!text.getLineWrap());
        text.setWrapStyleWord(text.getLineWrap());
        render();
    }

    private void toggleCursor() {
        text.getCaret().setVisible(!text.getCaret().isVisible());
    }

    /** V: proportional font instead of monospaced (like Variable char width in TC). */
    private void toggleVariableWidth() {
        variableWidth = !variableWidth;
        text.setFont(new Font(variableWidth ? Font.SANS_SERIF : Font.MONOSPACED, Font.PLAIN, 13).deriveFont(fontSize));
        recutLongLines();
    }

    /** A wider font fits fewer characters into a safe line width: long lines are cut anew, a text wrapped in the background is rewrapped. */
    private void recutLongLines() {
        final int maxLine = LongLines.maxChars(text, text.getFont());
        if (showingImage || showingMarkdown) {
            return;
        }
        if (shownWrapWidth > 0 || maxLine != shownMaxLine && shownLongest > Math.min(maxLine, shownMaxLine)) {
            render();
        }
    }

    /** Row width for wrapping in the background: the visible width of the text, with the vertical scroll bar a large text will have. */
    private int wrapWidth() {
        final JScrollBar bar = textScroll.getVerticalScrollBar();
        int width = textScroll.getViewport().getExtentSize().width;
        if (width <= 0) {
            width = DEFAULT_WRAP_WIDTH;
        } else if (!bar.isVisible()) {
            width -= bar.getPreferredSize().width;
        }
        final Insets in = text.getInsets();

        return Math.max(MIN_WRAP_WIDTH, width - in.left - in.right - 1);
    }

    /** The viewer was resized: the text wrapped in the background is wrapped at the new width, keeping the scroll position. */
    private void rewrap() {
        if (shownWrapWidth == 0 || showingImage || showingMarkdown || wrapWidth() == shownWrapWidth) {
            return;
        }
        final JScrollBar bar = textScroll.getVerticalScrollBar();
        restoreScroll = bar.getMaximum() > 0 ? (float) bar.getValue() / bar.getMaximum() : 0;
        render();
    }

    private void zoom(final float factor) {
        if (showingImage) {
            if (factor == 0) {
                imageView.actualSize();
            } else if (factor > 1) {
                imageView.zoomIn();
            } else {
                imageView.zoomOut();
            }

            return;
        }
        fontSize = factor == 0 ? 13f : Math.max(6f, Math.min(72f, fontSize * factor));
        text.setFont(text.getFont().deriveFont(fontSize));
        if (showingMarkdown) {
            render();
        } else {
            recutLongLines();
        }
    }

    private void askCharset() {
        final String name = (String) JOptionPane.showInputDialog(this, "Charset:", "Encoding", JOptionPane.PLAIN_MESSAGE, null,
            Charset.availableCharsets().keySet().toArray(), charsetName());
        if (name != null) {
            setCharset(Charset.forName(name));
        }
    }

    private void copyHex() {
        final String sel = text.getSelectedText();
        if (sel == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (final byte b : sel.getBytes(charset != null ? charset : StandardCharsets.UTF_8)) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString().trim()), null);
    }

    private void print() {
        try {
            text.print();
        } catch (final PrinterException e) {
            JOptionPane.showMessageDialog(this, "Print failed: " + e.getMessage(), "Print", JOptionPane.ERROR_MESSAGE);
        }
    }
}
