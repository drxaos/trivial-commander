package tcm.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractListModel;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ComboBoxEditor;
import javax.swing.ComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.text.BadLocationException;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import tcm.util.Keys;

/**
 * Command line at the bottom of the window: history, word-wise editing and TC keys (Ctrl+E, Ctrl+K, Ctrl+W, Ctrl+Y etc.).
 */
public final class CommandLine extends JPanel {

    private static final int HISTORY_LIMIT = 50;
    private static final String SPECIAL = " /\\:'\".,<>!@-=|";

    /** Actions performed by the main window. */
    public interface Host {

        void execute(@NotNull String command, boolean keepOpen);

        void returnToPanel();

        @NotNull
        String cursorName();

        @NotNull
        String cursorPath();
    }

    private final JLabel prompt = new JLabel();
    @Getter
    private final JTextField field = new JTextField();
    @Getter
    private final List<String> history = new ArrayList<>();
    private final HistoryModel historyModel = new HistoryModel();
    private final JComboBox<String> combo = new JComboBox<>(historyModel) {
        // JComboBox remembers the previous selection and skips re-selecting the same command (after editing the line),
        // so a selection from the list bypasses its logic and goes straight into the line
        @Override
        public void setSelectedItem(final Object item) {
            putFromHistory(item);
        }
    };
    private final @NotNull Host host;
    private int historyPos = -1;

    public CommandLine(final @NotNull Host host, final @NotNull List<String> savedHistory) {
        super(new BorderLayout(4, 0));
        this.host = host;
        history.addAll(savedHistory);
        historyModel.changed();
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(prompt, BorderLayout.WEST);
        // the input line is the editor of the history combo box: the button on the right is part of the same component
        combo.setEditable(true);
        combo.setEditor(new FieldEditor());
        combo.setMaximumRowCount(20);
        add(combo, BorderLayout.CENTER);
        field.setFocusTraversalKeysEnabled(false);
        // FlatLaf selects all text on focus gain — the first letter typed in the panel would be replaced by the second
        field.putClientProperty("JTextField.selectAllOnFocusPolicy", "never");
        installKeys();
    }

    public void setPrompt(final @NotNull String text) {
        // a long path would eat the whole input line — show the tail, the full path in the tooltip
        final int max = 48;
        prompt.setText((text.length() > max ? "\u2026" + text.substring(text.length() - max) : text) + ">");
        prompt.setToolTipText(text);
    }

    public boolean isEmpty() {
        return field.getText().isEmpty();
    }

    public void clear() {
        field.setText("");
        historyPos = -1;
    }

    public void type(final char c) {
        field.requestFocusInWindow();
        field.replaceSelection(String.valueOf(c));
    }

    /** Ctrl+Enter / Ctrl+J: append the name (or full path) under the cursor, or complete the text from history. */
    public void appendCursor(final boolean fullPath) {
        final String text = field.getText();
        if (!text.isEmpty() && SPECIAL.indexOf(text.charAt(text.length() - 1)) < 0) {
            completeFromHistory();

            return;
        }
        appendQuoted(fullPath ? host.cursorPath() : host.cursorName());
    }

    public void appendQuoted(final @NotNull String value) {
        if (value.isEmpty()) {
            return;
        }
        final String quoted = value.contains(" ") ? "\"" + value + "\"" : value;
        final String text = field.getText();
        field.setText(text + (text.isEmpty() || text.endsWith(" ") ? "" : " ") + quoted + " ");
    }

    public void addToHistory(final @NotNull String command) {
        history.remove(command);
        history.add(0, command);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(history.size() - 1);
        }
        historyPos = -1;
        historyModel.changed();
    }

    /** Ctrl+E / Shift+↑: previous command. */
    public void previousHistory() {
        if (historyPos + 1 < history.size()) {
            historyPos++;
            field.setText(history.get(historyPos));
        }
    }

    public void nextHistory() {
        if (historyPos > 0) {
            historyPos--;
            field.setText(history.get(historyPos));
        } else {
            historyPos = -1;
            field.setText("");
        }
    }

    /** Ctrl+↓ in the panel: last command into the line for editing. */
    public void editLast() {
        field.requestFocusInWindow();
        historyPos = -1;
        previousHistory();
    }

    /** Alt+F8 / Ctrl+↓ in the line: history list. */
    public void showHistoryPopup() {
        field.requestFocusInWindow();
        if (!history.isEmpty()) {
            combo.showPopup();
        }
    }

    private void popupOrPanel(final @NotNull String comboAction) {
        if (!combo.isPopupVisible()) {
            host.returnToPanel();

            return;
        }
        final Action action = combo.getActionMap().get(comboAction);
        if (action != null) {
            action.actionPerformed(new ActionEvent(combo, ActionEvent.ACTION_PERFORMED, comboAction));
        }
    }

    private void completeFromHistory() {
        final String text = field.getText();
        for (final String cmd : history) {
            if (cmd.startsWith(text) && cmd.length() > text.length()) {
                field.setText(cmd);

                return;
            }
        }
    }

    private void execute(final boolean keepOpen) {
        final String cmd = field.getText().trim();
        if (cmd.isEmpty()) {
            return;
        }
        addToHistory(cmd);
        clear();
        host.execute(cmd, keepOpen);
    }

    private void installKeys() {
        bind("ENTER", () -> {
            if (combo.isPopupVisible()) {
                // the command from the list is already in the line — it can be edited before running
                combo.hidePopup();

                return;
            }
            execute(false);
        });
        bind("shift ENTER", () -> execute(true));
        bind("ESCAPE", () -> {
            if (combo.isPopupVisible()) {
                combo.hidePopup();

                return;
            }
            clear();
            host.returnToPanel();
        });
        // with the history list open, arrows move through it, otherwise they return to the panel
        bind("UP", () -> popupOrPanel("selectPrevious"));
        bind("DOWN", () -> popupOrPanel("selectNext"));
        bind("TAB", host::returnToPanel);
        bind("shift TAB", host::returnToPanel);
        bind("shift UP", this::previousHistory);
        bind("shift DOWN", this::nextHistory);
        bind("ctrl E", this::previousHistory);
        bind("ctrl DOWN", this::showHistoryPopup);
        bind("ctrl K", this::deleteToEnd);
        bind("ctrl DELETE", this::deleteToEnd);
        bind("ctrl T", this::deleteWordRight);
        bind("ctrl W", this::deleteWordLeft);
        bind("ctrl BACK_SPACE", this::deleteWordLeft);
        bind("ctrl Y", this::clear);
        bind("ctrl ENTER", () -> appendCursor(false));
        bind("ctrl shift ENTER", () -> appendCursor(true));
        bind("ctrl J", () -> appendCursor(false));
        bind("ctrl shift J", () -> appendCursor(true));
    }

    private void bind(final @NotNull String key, final @NotNull Runnable action) {
        Keys.bind(field, JComponent.WHEN_FOCUSED, key, action);
    }

    private void deleteToEnd() {
        final int pos = field.getCaretPosition();
        field.setText(field.getText().substring(0, pos));
    }

    private void deleteWordRight() {
        final String t = field.getText();
        final int pos = field.getCaretPosition();
        int end = pos;
        while (end < t.length() && t.charAt(end) == ' ') {
            end++;
        }
        while (end < t.length() && t.charAt(end) != ' ') {
            end++;
        }
        remove(pos, end);
    }

    private void deleteWordLeft() {
        final String t = field.getText();
        final int pos = field.getCaretPosition();
        int start = pos;
        while (start > 0 && t.charAt(start - 1) == ' ') {
            start--;
        }
        while (start > 0 && t.charAt(start - 1) != ' ') {
            start--;
        }
        remove(start, pos);
    }

    private void remove(final int from, final int to) {
        try {
            field.getDocument().remove(from, to - from);
        } catch (final BadLocationException e) {
            // bounds were obtained from the current text
        }
    }

    private void putFromHistory(final Object item) {
        if (item != null && !item.equals(field.getText())) {
            field.setText(item.toString());
            historyPos = history.indexOf(item.toString());
        }
    }

    /** The list model is the history itself; the selected item is the line text, so the open list highlights the typed command. */
    private final class HistoryModel extends AbstractListModel<String> implements ComboBoxModel<String> {

        @Override
        public int getSize() {
            return history.size();
        }

        @Override
        public String getElementAt(final int index) {
            return history.get(index);
        }

        @Override
        public void setSelectedItem(final Object item) {
            putFromHistory(item);
        }

        @Override
        public Object getSelectedItem() {
            return field.getText();
        }

        private void changed() {
            fireContentsChanged(this, 0, Math.max(0, history.size() - 1));
        }
    }

    /** The list editor is a permanent line field: theme change recreates the UI, but key bindings stay in place. */
    private final class FieldEditor implements ComboBoxEditor {

        @Override
        public Component getEditorComponent() {
            return field;
        }

        @Override
        public void setItem(final Object item) {
            putFromHistory(item);
        }

        @Override
        public Object getItem() {
            return field.getText();
        }

        @Override
        public void selectAll() {
            field.selectAll();
        }

        @Override
        public void addActionListener(final ActionListener l) {
            // Enter is handled by the line itself (run the command), the list must not know about it
        }

        @Override
        public void removeActionListener(final ActionListener l) {
            // listeners were not added
        }
    }
}
