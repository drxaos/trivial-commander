package tcm.apps;

import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;

import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import lombok.Setter;

/**
 * Text search in viewer/editor/compare: F7 — dialog, F3/Shift+F3 — next/previous, Ctrl+F3 — reverse direction.
 */
@Getter
@Setter
public final class TextFinder {

    private @NotNull String query = "";
    private boolean caseSensitive;
    private boolean backward;

    public boolean ask(final @NotNull Component parent, final boolean backwardDefault) {
        final JTextField field = new JTextField(query, 30);
        final JCheckBox caseBox = new JCheckBox("Case sensitive", caseSensitive);
        final JCheckBox backBox = new JCheckBox("Search backwards", backwardDefault);
        field.selectAll();
        final Object[] message = {"Search for:", field, caseBox, backBox};
        final JOptionPane pane = new JOptionPane(message, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        final JDialog dialog = pane.createDialog(parent, "Find");
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(final WindowEvent e) {
                field.requestFocusInWindow();
            }
        });
        field.addActionListener(e -> {
            pane.setValue(JOptionPane.OK_OPTION);
            dialog.dispose();
        });
        dialog.setVisible(true);
        final Object value = pane.getValue();
        if (!(value instanceof Integer) || (Integer) value != JOptionPane.OK_OPTION || field.getText().isEmpty()) {
            return false;
        }
        query = field.getText();
        caseSensitive = caseBox.isSelected();
        backward = backBox.isSelected();

        return true;
    }

    /** reverse — search in the direction opposite to the current one. */
    public boolean findNext(final @NotNull JTextComponent area, final boolean reverse) {
        if (query.isEmpty()) {
            return false;
        }
        final String full = documentText(area);
        final String text = caseSensitive ? full : full.toLowerCase(Locale.ROOT);
        final String q = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        final boolean back = backward != reverse;
        int idx;
        if (back) {
            idx = text.lastIndexOf(q, area.getSelectionStart() - 1);
            if (idx < 0) {
                idx = text.lastIndexOf(q);
            }
        } else {
            idx = text.indexOf(q, area.getSelectionEnd());
            if (idx < 0) {
                idx = text.indexOf(q);
            }
        }
        if (idx < 0) {
            JOptionPane.showMessageDialog(area, "\"" + query + "\" not found.", "Find", JOptionPane.INFORMATION_MESSAGE);

            return false;
        }
        area.setCaretPosition(idx);
        area.moveCaretPosition(idx + q.length());
        area.getCaret().setSelectionVisible(true);
        try {
            final Rectangle r = area.modelToView2D(idx).getBounds();
            area.scrollRectToVisible(r);
        } catch (final BadLocationException e) {
            // the position is guaranteed to be inside the text
        }

        return true;
    }

    /** Model text: for HTML (Markdown in the viewer) getText() would return markup, and positions would not match. */
    private static @NotNull String documentText(final @NotNull JTextComponent area) {
        try {
            return area.getDocument().getText(0, area.getDocument().getLength());
        } catch (final BadLocationException e) {
            // the range is taken from the document itself
            return area.getText();
        }
    }
}
