package tcm.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.util.Diff;
import tcm.util.Keys;
import tcm.util.TextUtil;

/**
 * Compare by content: two files side by side, differences in red, jumping between differences, synchronized scrolling.
 */
public final class CompareWindow {

    private static final Color DIFF_FG = new Color(0xD03030);
    private static final Color CURRENT_BG = new Color(0x40, 0x90, 0xF0, 0x40);

    private final @NotNull Window window;
    private final JTextField leftPath = new JTextField();
    private final JTextField rightPath = new JTextField();
    private final JCheckBox caseSensitive = new JCheckBox("Case sensitive", true);
    private final JTextPane left = pane();
    private final JTextPane right = pane();
    private final JScrollPane leftScroll = new JScrollPane(left);
    private final JScrollPane rightScroll = new JScrollPane(right);
    private final JLabel status = new JLabel(" ");
    private final JTextField lineLeft = lineField();
    private final JTextField lineRight = lineField();
    private final JPanel linePanel = new JPanel(new GridLayout(2, 1));
    private final TextFinder finder = new TextFinder();
    private @Nullable Diff.Result result;
    private int[] leftStarts = new int[0];
    private int[] rightStarts = new int[0];
    private int currentBlock = -1;
    private @Nullable Object leftHighlight;
    private @Nullable Object rightHighlight;
    private @NotNull JTextPane activePane = left;

    private CompareWindow(final @Nullable Window owner, final @Nullable VFile a, final @Nullable VFile b) {
        window = AppWindows.create(owner, "Compare contents");
        window.setSize(1100, 720);
        window.setLocationRelativeTo(owner);
        final JPanel content = new JPanel(new BorderLayout());
        content.add(createTop(), BorderLayout.NORTH);
        // shared scroll model: the lines are aligned, so the panes scroll synchronously
        rightScroll.getVerticalScrollBar().setModel(leftScroll.getVerticalScrollBar().getModel());
        rightScroll.getHorizontalScrollBar().setModel(leftScroll.getHorizontalScrollBar().getModel());
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightScroll);
        split.setResizeWeight(0.5);
        content.add(split, BorderLayout.CENTER);
        final JPanel bottom = new JPanel(new BorderLayout());
        linePanel.add(lineLeft);
        linePanel.add(lineRight);
        linePanel.setVisible(false);
        bottom.add(linePanel, BorderLayout.NORTH);
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bottom.add(status, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        AppWindows.container(window).setContentPane(content);
        left.addFocusListener(focusTracker(left));
        right.addFocusListener(focusTracker(right));
        left.addCaretListener(e -> updateLinePanel());
        right.addCaretListener(e -> updateLinePanel());
        installKeys();
        if (a != null) {
            leftPath.setText(a.getLocation());
        }
        if (b != null) {
            rightPath.setText(b.getLocation());
        }
        window.setVisible(true);
        if (a != null && b != null) {
            compare();
        }
    }

    public static void open(final @Nullable Window owner, final @Nullable VFile a, final @Nullable VFile b) {
        new CompareWindow(owner, a, b);
    }

    private @NotNull JPanel createTop() {
        final JPanel paths = new JPanel(new GridLayout(1, 2, 6, 0));
        paths.add(pathRow(leftPath));
        paths.add(pathRow(rightPath));
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(button("Compare", this::compare));
        buttons.add(button("Next difference", () -> gotoBlock(currentBlock + 1)));
        buttons.add(button("Previous difference", () -> gotoBlock(currentBlock - 1)));
        buttons.add(button("Swap (Ctrl+U)", this::swap));
        buttons.add(button("Find (F7)", this::find));
        buttons.add(caseSensitive);
        final JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
        top.add(paths, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.SOUTH);

        return top;
    }

    private @NotNull JPanel pathRow(final @NotNull JTextField field) {
        final JPanel row = new JPanel(new BorderLayout(2, 0));
        row.add(field, BorderLayout.CENTER);
        final JButton browse = new JButton(">>");
        browse.setFocusable(false);
        browse.addActionListener(e -> {
            final JFileChooser chooser = new JFileChooser(field.getText());
            if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        row.add(browse, BorderLayout.EAST);
        field.addActionListener(e -> compare());

        return row;
    }

    private static @NotNull JButton button(final @NotNull String text, final @NotNull Runnable action) {
        final JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());

        return b;
    }

    private static @NotNull JTextPane pane() {
        final JTextPane p = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                // no line wrapping: otherwise line alignment between the panes breaks
                return getUI().getPreferredSize(this).width <= getParent().getSize().width;
            }
        };
        p.setEditable(false);
        p.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        p.getCaret().setVisible(true);

        return p;
    }

    private static @NotNull JTextField lineField() {
        final JTextField f = new JTextField();
        f.setEditable(false);
        f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        return f;
    }

    private @NotNull FocusAdapter focusTracker(final @NotNull JTextPane pane) {
        return new FocusAdapter() {
            @Override
            public void focusGained(final FocusEvent e) {
                activePane = pane;
            }
        };
    }

    private void installKeys() {
        final JComponent root = AppWindows.container(window).getRootPane();
        Keys.bindWindow(root, "ESCAPE", window::dispose);
        Keys.bindWindow(root, "alt DOWN", () -> gotoBlock(currentBlock + 1));
        Keys.bindWindow(root, "alt UP", () -> gotoBlock(currentBlock - 1));
        Keys.bindWindow(root, "ctrl U", this::swap);
        Keys.bindWindow(root, "F9", () -> {
            linePanel.setVisible(!linePanel.isVisible());
            updateLinePanel();
            window.validate();
        });
        Keys.bindWindow(root, "F7", this::find);
        Keys.bindWindow(root, "ctrl F", this::find);
        for (final String k : new String[] {"F3", "F5"}) {
            Keys.bindWindow(root, k, () -> finder.findNext(activePane, false));
            Keys.bindWindow(root, "shift " + k, () -> {
                finder.findNext(activePane, !finder.isBackward());
                finder.setBackward(false);
            });
            Keys.bindWindow(root, "ctrl " + k, () -> {
                finder.setBackward(!finder.isBackward());
                finder.findNext(activePane, false);
            });
        }
        Keys.bindWindow(root, "ctrl TAB", () -> (activePane == left ? right : left).requestFocusInWindow());
        for (final JTextPane p : new JTextPane[] {left, right}) {
            Keys.bind(p, JComponent.WHEN_FOCUSED, "HOME", () -> p.setCaretPosition(0));
            Keys.bind(p, JComponent.WHEN_FOCUSED, "END", () -> p.setCaretPosition(p.getDocument().getLength()));
            Keys.bind(p, JComponent.WHEN_FOCUSED, "LEFT", () -> scrollChars(-1));
            Keys.bind(p, JComponent.WHEN_FOCUSED, "RIGHT", () -> scrollChars(1));
            Keys.bind(p, JComponent.WHEN_FOCUSED, "alt LEFT", () -> scrollChars(-30));
            Keys.bind(p, JComponent.WHEN_FOCUSED, "alt RIGHT", () -> scrollChars(30));
        }
    }

    private void scrollChars(final int chars) {
        final JScrollBar bar = leftScroll.getHorizontalScrollBar();
        bar.setValue(bar.getValue() + chars * left.getFontMetrics(left.getFont()).charWidth('m'));
    }

    private void find() {
        if (finder.ask(window, false)) {
            finder.findNext(activePane, false);
        }
    }

    private void swap() {
        final String t = leftPath.getText();
        leftPath.setText(rightPath.getText());
        rightPath.setText(t);
        compare();
    }

    private void compare() {
        final List<String> a;
        final List<String> b;
        try {
            a = readLines(leftPath.getText().trim());
            b = readLines(rightPath.getText().trim());
        } catch (final IOException e) {
            status.setText("Error: " + e.getMessage());

            return;
        }
        final Diff.Result r = Diff.compare(a, b, caseSensitive.isSelected());
        result = r;
        leftStarts = fill(left, a, r, true);
        rightStarts = fill(right, b, r, false);
        currentBlock = -1;
        final int n = r.getBlocks().size();
        status.setText(n == 0 ? "Files are identical" : n + " difference(s) found");
        if (n > 0) {
            gotoBlock(0);
        } else {
            left.setCaretPosition(0);
        }
        AppWindows.setTitle(window, "Compare contents - " + leftPath.getText() + " / " + rightPath.getText());
    }

    private static @NotNull List<String> readLines(final @NotNull String location) throws IOException {
        if (location.isEmpty()) {
            return List.of();
        }
        final String text = TextUtil.decode(Vfs.readAll(Vfs.file(location))).getText();
        final List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r\n|\r|\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        return lines;
    }

    /** Fills the pane with aligned lines; returns the start offsets of each alignment line. */
    private static int @NotNull [] fill(final @NotNull JTextPane pane, final @NotNull List<String> lines, final @NotNull Diff.Result r,
        final boolean leftSide) {
        final int width = String.valueOf(lines.size()).length();
        final StringBuilder sb = new StringBuilder();
        final int[] starts = new int[r.getRows().size() + 1];
        final List<int[]> red = new ArrayList<>();
        for (int i = 0; i < r.getRows().size(); i++) {
            final Diff.Row row = r.getRows().get(i);
            starts[i] = sb.length();
            final int idx = leftSide ? row.getLeft() : row.getRight();
            if (idx >= 0) {
                sb.append(String.format("%" + width + "d: ", idx + 1)).append(lines.get(idx).replace('\t', ' '));
                if (!row.isEqual()) {
                    red.add(new int[] {starts[i], sb.length()});
                }
            }
            sb.append('\n');
        }
        starts[r.getRows().size()] = sb.length();
        pane.setText(sb.toString());
        final StyledDocument doc = pane.getStyledDocument();
        final SimpleAttributeSet diff = new SimpleAttributeSet();
        StyleConstants.setForeground(diff, DIFF_FG);
        for (final int[] range : red) {
            doc.setCharacterAttributes(range[0], range[1] - range[0], diff, false);
        }

        return starts;
    }

    private void gotoBlock(final int block) {
        final Diff.Result r = result;
        if (r == null || r.getBlocks().isEmpty()) {
            return;
        }
        currentBlock = Math.max(0, Math.min(block, r.getBlocks().size() - 1));
        final int startRow = r.getBlocks().get(currentBlock);
        int endRow = startRow;
        while (endRow < r.getRows().size() && !r.getRows().get(endRow).isEqual()) {
            endRow++;
        }
        leftHighlight = highlight(left, leftHighlight, leftStarts[startRow], leftStarts[endRow]);
        rightHighlight = highlight(right, rightHighlight, rightStarts[startRow], rightStarts[endRow]);
        left.setCaretPosition(leftStarts[startRow]);
        right.setCaretPosition(rightStarts[startRow]);
        try {
            final Rectangle rect = left.modelToView2D(leftStarts[startRow]).getBounds();
            rect.y = Math.max(0, rect.y - leftScroll.getViewport().getHeight() / 3);
            rect.height = leftScroll.getViewport().getHeight();
            left.scrollRectToVisible(rect);
        } catch (final BadLocationException e) {
            // the offset was obtained from the same document
        }
        status.setText("Difference " + (currentBlock + 1) + " of " + r.getBlocks().size());
    }

    private static @Nullable Object highlight(final @NotNull JTextPane pane, final @Nullable Object old, final int from, final int to) {
        if (old != null) {
            pane.getHighlighter().removeHighlight(old);
        }
        try {
            return pane.getHighlighter().addHighlight(from, Math.max(from, to - 1), new DefaultHighlighter.DefaultHighlightPainter(CURRENT_BG));
        } catch (final BadLocationException e) {
            return null;
        }
    }

    /** F9: two lines below the panes — the current line on the left and on the right in full. */
    private void updateLinePanel() {
        if (!linePanel.isVisible() || result == null) {
            return;
        }
        final int row = rowAt(activePane == left ? leftStarts : rightStarts, activePane.getCaretPosition());
        lineLeft.setText(lineText(left, leftStarts, row));
        lineRight.setText(lineText(right, rightStarts, row));
    }

    private static int rowAt(final int @NotNull [] starts, final int offset) {
        final int idx = Arrays.binarySearch(starts, offset);

        return Math.max(0, idx >= 0 ? idx : -idx - 2);
    }

    private static @NotNull String lineText(final @NotNull JTextPane pane, final int @NotNull [] starts, final int row) {
        if (row + 1 >= starts.length) {
            return "";
        }
        try {
            return pane.getDocument().getText(starts[row], Math.max(0, starts[row + 1] - starts[row] - 1));
        } catch (final BadLocationException e) {
            return "";
        }
    }
}
