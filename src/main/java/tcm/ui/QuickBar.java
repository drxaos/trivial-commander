package tcm.ui;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/**
 * Quick search/filter box below the table (Ctrl+S, Ctrl+Alt+letter).
 */
final class QuickBar extends JPanel {

    /** Actions performed by the tab. */
    interface Listener {

        void quickTextChanged(@NotNull String text, boolean filter);

        void quickMove(int direction, boolean mark);

        void quickEnter();

        void quickClosed();
    }

    private final JLabel label = new JLabel();
    @Getter
    private final JTextField field = new JTextField();
    private final @NotNull Listener listener;
    @Getter
    private boolean filterMode;

    QuickBar(final @NotNull Listener listener) {
        super(new BorderLayout(4, 0));
        this.listener = listener;
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(label, BorderLayout.WEST);
        add(field, BorderLayout.CENTER);
        setVisible(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                fire();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                fire();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                fire();
            }
        });
        bind("UP", () -> listener.quickMove(-1, false));
        bind("DOWN", () -> listener.quickMove(1, false));
        bind("ctrl ENTER", () -> listener.quickMove(1, false));
        bind("shift UP", () -> listener.quickMove(-1, true));
        bind("shift DOWN", () -> listener.quickMove(1, true));
        bind("INSERT", () -> listener.quickMove(1, true));
        bind("ENTER", () -> {
            close();
            listener.quickEnter();
        });
        bind("ESCAPE", this::close);
        bind("ctrl S", () -> open(!filterMode, field.getText()));
        bind("ctrl Y", () -> field.setText(""));
        bind("ctrl Z", () -> field.setText(""));
        bind("TAB", this::close);
    }

    void open(final boolean filter, final @NotNull String text) {
        filterMode = filter;
        label.setText(filter ? "Filter:" : "Quick search:");
        setVisible(true);
        field.setText(text);
        field.requestFocusInWindow();
        fire();
    }

    void close() {
        if (isVisible()) {
            setVisible(false);
            listener.quickClosed();
        }
    }

    private void fire() {
        if (isVisible()) {
            listener.quickTextChanged(field.getText(), filterMode);
        }
    }

    private void bind(final @NotNull String key, final @NotNull Runnable action) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        field.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                action.run();
            }
        });
    }
}
