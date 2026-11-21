package tcm.ui;

import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

import org.jetbrains.annotations.NotNull;

import tcm.util.Keys;

/**
 * Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) and Ctrl+A in every text field of the program: Swing text components have no undo of their own.
 * Attached when a component first gets focus, so text put in before the user starts typing (the name in the rename field,
 * the path in a dialog) is not undone. A component that binds Ctrl+Z itself (the editor) is left alone.
 */
public final class TextUndo {

    private static final String ATTACHED = "tcm.textUndo";
    private static final KeyStroke CTRL_Z = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke CTRL_A = KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK);

    private TextUndo() {
    }

    public static void install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", e -> {
            if (e.getNewValue() instanceof JTextComponent c && c.getClientProperty(ATTACHED) == null) {
                attach(c);
            }
        });
    }

    private static void attach(final @NotNull JTextComponent c) {
        c.putClientProperty(ATTACHED, Boolean.TRUE);
        final InputMap keys = c.getInputMap(JComponent.WHEN_FOCUSED);
        if (keys.get(CTRL_A) == null) {
            Keys.bind(c, JComponent.WHEN_FOCUSED, "ctrl A", c::selectAll);
        }
        if (keys.get(CTRL_Z) != null) {
            return;
        }
        final UndoManager undo = new UndoManager();
        // text set by the program into a read-only component (the viewer) is not an edit the user could take back
        final UndoableEditListener recorder = e -> {
            if (c.isEditable()) {
                undo.addEdit(e.getEdit());
            }
        };
        c.getDocument().addUndoableEditListener(recorder);
        c.addPropertyChangeListener("document", e -> {
            if (e.getOldValue() instanceof Document old) {
                old.removeUndoableEditListener(recorder);
            }
            if (e.getNewValue() instanceof Document doc) {
                doc.addUndoableEditListener(recorder);
            }
            undo.discardAllEdits();
        });
        Keys.bind(c, JComponent.WHEN_FOCUSED, "ctrl Z", () -> {
            if (c.isEditable() && c.isEnabled() && undo.canUndo()) {
                try {
                    undo.undo();
                } catch (final CannotUndoException ex) {
                    // the document changed under the edit: nothing to take back
                }
            }
        });
        final Runnable redo = () -> {
            if (c.isEditable() && c.isEnabled() && undo.canRedo()) {
                try {
                    undo.redo();
                } catch (final CannotRedoException ex) {
                    // the document changed under the edit: nothing to redo
                }
            }
        };
        Keys.bind(c, JComponent.WHEN_FOCUSED, "ctrl Y", redo);
        Keys.bind(c, JComponent.WHEN_FOCUSED, "ctrl shift Z", redo);
    }
}
