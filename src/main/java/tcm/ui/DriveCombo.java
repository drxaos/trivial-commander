package tcm.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;

import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Drive selector: roots, ~ and device mount points. The popup shows full paths (FlatLaf sizes it by the widest item),
 * the closed combo — only the last name of a mount point, so long paths do not widen the drive bar.
 */
final class DriveCombo extends JComboBox<String> {

    /** Longest short label the closed combo makes room for; longer ones are cut with "...". */
    private static final String MAX_FACE = "MMMMMMMM";
    private static final String MIN_FACE = "C:\\";

    /** The combo is painting its closed face (the renderer gets index -1 both there and when sizing the popup). */
    private boolean paintingFace;

    @Override
    public void updateUI() {
        // the look and feel replaces only its own renderer: unwrap it, let the theme install a fresh one, wrap that
        if (getRenderer() instanceof FaceRenderer wrapper) {
            setRenderer(wrapper.base);
        }
        super.updateUI();
        setRenderer(new FaceRenderer(getRenderer()));
    }

    @Override
    public Dimension getPreferredSize() {
        final Dimension d = super.getPreferredSize();
        if (isPreferredSizeSet()) {
            return d;
        }
        final FontMetrics fm = getFontMetrics(getFont());
        int full = 0;
        int face = fm.stringWidth(MIN_FACE);
        for (int i = 0; i < getItemCount(); i++) {
            final String item = getItemAt(i);
            full = Math.max(full, fm.stringWidth(item));
            face = Math.max(face, fm.stringWidth(faceText(item)));
        }
        face = Math.min(face, fm.stringWidth(MAX_FACE));
        if (full > face) {
            d.width -= full - face;
        }

        return d;
    }

    @Override
    protected void paintComponent(final Graphics g) {
        paintingFace = true;
        try {
            super.paintComponent(g);
        } finally {
            paintingFace = false;
        }
    }

    /** "/media/user/CARD" → "CARD"; roots and ~ as they are. */
    static @NotNull String faceText(final @NotNull String item) {
        final int slash = item.lastIndexOf('/');

        return item.length() > 1 && slash >= 0 && slash < item.length() - 1 ? item.substring(slash + 1) : item;
    }

    private final class FaceRenderer implements ListCellRenderer<String> {

        private final @NotNull ListCellRenderer<? super String> base;

        private FaceRenderer(final @NotNull ListCellRenderer<? super String> base) {
            this.base = base;
        }

        @Override
        public Component getListCellRendererComponent(
            final JList<? extends String> list,
            final @Nullable String value,
            final int index,
            final boolean selected,
            final boolean focused
        ) {
            final boolean face = index < 0 && paintingFace && value != null;

            return base.getListCellRendererComponent(list, face ? faceText(value) : value, index, selected, focused);
        }
    }
}
