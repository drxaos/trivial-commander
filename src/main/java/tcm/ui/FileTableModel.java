package tcm.ui;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.swing.table.AbstractTableModel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import tcm.core.VFile;
import tcm.util.Masks;
import tcm.util.TextUtil;

/**
 * Panel rows: "..", directories, files; the mark (red highlight) is stored separately from the table cursor.
 */
public final class FileTableModel extends AbstractTableModel {

    public static final int COL_NAME = 0;
    public static final int COL_EXT = 1;
    public static final int COL_SIZE = 2;
    public static final int COL_DATE = 3;
    public static final int COL_ATTR = 4;
    /** Unsorted (Ctrl+F7): the order in which the FS returned the files. */
    public static final int UNSORTED = -1;

    private static final String[] COLUMNS = {"Name", "Ext", "Size", "Date", "Attr"};
    private static final Collator COLLATOR = Collator.getInstance();

    private final List<VFile> rows = new ArrayList<>();
    private final List<VFile> natural = new ArrayList<>();
    private final Set<VFile> marked = new HashSet<>();
    private final Set<VFile> savedMarks = new HashSet<>();
    /** Computed directory sizes — shown instead of <DIR>. */
    private final Map<VFile, Long> dirSizes = new HashMap<>();
    @Getter
    private int sortColumn = COL_NAME;
    @Getter
    private boolean ascending = true;
    /** "Feed to listbox" / "flat list" mode: the Name column shows the full path. */
    @Getter
    private boolean feedMode;
    /** Base directory of the flat list: names are shown relative to it. */
    private @NotNull String feedBase = "";

    public void setFiles(final @NotNull List<VFile> files, final boolean feed) {
        final Set<VFile> keep = new HashSet<>(files);
        marked.retainAll(keep);
        dirSizes.keySet().retainAll(keep);
        rows.clear();
        rows.addAll(files);
        natural.clear();
        natural.addAll(files);
        feedMode = feed;
        sortRows();
        fireTableDataChanged();
    }

    public void setFeedBase(final @NotNull String base) {
        feedBase = base.endsWith("/") || base.endsWith(VFile.ARCHIVE_SEPARATOR) ? base : base + "/";
    }

    /** Name in the flat list: path relative to the base directory, otherwise full. */
    public @NotNull String feedName(final @NotNull VFile f) {
        final String loc = f.getLocation();

        return loc.startsWith(feedBase) ? loc.substring(feedBase.length()) : loc;
    }

    public void setSort(final int column, final boolean asc) {
        sortColumn = column;
        ascending = asc;
        sortRows();
        fireTableDataChanged();
    }

    /** Ctrl+F3..F6: pressing the same column again reverses the direction. */
    public void toggleSort(final int column) {
        setSort(column, column != sortColumn || !ascending);
    }

    public @NotNull VFile getFile(final int row) {
        return rows.get(row);
    }

    public @NotNull List<VFile> getFiles() {
        return List.copyOf(rows);
    }

    public int indexOf(final @NotNull String name) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getLocation().equals(name)) {
                return i;
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isParentLink() && rows.get(i).getName().equals(name)) {
                return i;
            }
        }

        return -1;
    }

    public boolean isMarked(final @NotNull VFile file) {
        return marked.contains(file);
    }

    public void setMarked(final int row, final boolean mark) {
        final VFile f = rows.get(row);
        if (f.isParentLink()) {
            return;
        }
        if (mark) {
            marked.add(f);
        } else {
            marked.remove(f);
        }
        fireTableRowsUpdated(row, row);
    }

    public void toggleMark(final int row) {
        setMarked(row, !marked.contains(rows.get(row)));
    }

    public void markAll(final boolean mark, final boolean filesOnly) {
        mark(f -> !filesOnly || !f.isDirectory(), mark);
    }

    public void invertMarks(final boolean filesOnly) {
        saveMarks();
        for (final VFile f : rows) {
            if (!f.isParentLink() && (!filesOnly || !f.isDirectory()) && !marked.remove(f)) {
                marked.add(f);
            }
        }
        fireTableDataChanged();
    }

    public void markByMask(final @NotNull Masks mask, final boolean mark, final boolean filesOnly) {
        mark(f -> (!filesOnly || !f.isDirectory()) && mask.matches(f.getName()), mark);
    }

    public void markByExtension(final @NotNull String ext, final boolean mark) {
        final String lower = ext.toLowerCase(Locale.ROOT);
        mark(f -> !f.isDirectory() && f.getExtension().toLowerCase(Locale.ROOT).equals(lower), mark);
    }

    /** Num /: restore the selection that existed before the last group change. */
    public void restoreMarks() {
        final Set<VFile> current = new HashSet<>(marked);
        marked.clear();
        for (final VFile f : rows) {
            if (savedMarks.contains(f)) {
                marked.add(f);
            }
        }
        savedMarks.clear();
        savedMarks.addAll(current);
        fireTableDataChanged();
    }

    public void setDirSize(final @NotNull VFile dir, final long size) {
        dirSizes.put(dir, size);
        final int row = rows.indexOf(dir);
        if (row >= 0) {
            fireTableRowsUpdated(row, row);
        }
    }

    public @Nullable Long getDirSize(final @NotNull VFile dir) {
        return dirSizes.get(dir);
    }

    /** Marked files in display order. */
    public @NotNull List<VFile> getMarked() {
        final List<VFile> result = new ArrayList<>();
        for (final VFile f : rows) {
            if (marked.contains(f)) {
                result.add(f);
            }
        }

        return result;
    }

    /** Status line as in TC: "12 k / 40 k in 1 / 5 file(s), 0 / 3 dir(s)". */
    public @NotNull String getSummary() {
        long totalSize = 0;
        long markedSize = 0;
        int files = 0;
        int dirs = 0;
        int markedFiles = 0;
        int markedDirs = 0;
        for (final VFile f : rows) {
            if (f.isParentLink()) {
                continue;
            }
            final boolean m = marked.contains(f);
            final long size = f.isDirectory() ? dirSizes.getOrDefault(f, 0L) : f.getSize();
            totalSize += size;
            markedSize += m ? size : 0;
            if (f.isDirectory()) {
                dirs++;
                markedDirs += m ? 1 : 0;
            } else {
                files++;
                markedFiles += m ? 1 : 0;
            }
        }

        return TextUtil.shortSize(markedSize) + " / " + TextUtil.shortSize(totalSize) + " in " + markedFiles + " / " + files + " file(s), "
            + markedDirs + " / " + dirs + " dir(s)";
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(final int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(final int column) {
        return column == COL_NAME ? VFile.class : String.class;
    }

    @Override
    public @Nullable Object getValueAt(final int row, final int column) {
        final VFile f = rows.get(row);
        switch (column) {
            case COL_NAME:
                return f;
            case COL_EXT:
                return feedMode ? "" : f.getExtension();
            case COL_SIZE:
                return sizeText(f);
            case COL_DATE:
                return f.getModified() > 0 ? TextUtil.formatDate(f.getModified()) : "";
            case COL_ATTR:
                return f.getAttrs();
            default:
                return null;
        }
    }

    private @NotNull String sizeText(final @NotNull VFile f) {
        if (f.isParentLink()) {
            return "<DIR>";
        }
        if (f.isDirectory()) {
            final Long size = dirSizes.get(f);

            return size == null ? "<DIR>" : TextUtil.groupDigits(size);
        }

        return TextUtil.groupDigits(f.getSize());
    }

    private void mark(final @NotNull Predicate<VFile> which, final boolean mark) {
        saveMarks();
        for (final VFile f : rows) {
            if (!f.isParentLink() && which.test(f)) {
                if (mark) {
                    marked.add(f);
                } else {
                    marked.remove(f);
                }
            }
        }
        fireTableDataChanged();
    }

    private void saveMarks() {
        savedMarks.clear();
        savedMarks.addAll(marked);
    }

    private void sortRows() {
        rows.clear();
        rows.addAll(natural);
        if (sortColumn == UNSORTED) {
            rows.sort((a, b) -> Boolean.compare(b.isParentLink(), a.isParentLink()));

            return;
        }
        Comparator<VFile> cmp = comparator(sortColumn);
        if (!ascending) {
            cmp = cmp.reversed();
        }
        final Comparator<VFile> order = cmp;
        // ".." always first, directories always above files
        rows.sort((a, b) -> {
            if (a.isParentLink() != b.isParentLink()) {
                return a.isParentLink() ? -1 : 1;
            }
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }

            return order.compare(a, b);
        });
    }

    private @NotNull Comparator<VFile> comparator(final int column) {
        final Comparator<VFile> byName = (a, b) -> COLLATOR.compare(displayName(a), displayName(b));
        switch (column) {
            case COL_EXT:
                return Comparator.<VFile, String>comparing(VFile::getExtension, COLLATOR::compare).thenComparing(byName);
            case COL_SIZE:
                return Comparator.comparingLong(this::sizeOf).thenComparing(byName);
            case COL_DATE:
                return Comparator.comparingLong(VFile::getModified).thenComparing(byName);
            case COL_ATTR:
                return Comparator.comparing(VFile::getAttrs).thenComparing(byName);
            default:
                return byName;
        }
    }

    private long sizeOf(final @NotNull VFile f) {
        return f.isDirectory() ? dirSizes.getOrDefault(f, 0L) : f.getSize();
    }

    private @NotNull String displayName(final @NotNull VFile f) {
        return feedMode ? feedName(f) : f.getName();
    }
}
