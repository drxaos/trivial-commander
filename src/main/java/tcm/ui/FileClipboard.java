package tcm.ui;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import tcm.core.VFile;
import tcm.core.Vfs;

/**
 * Clipboard for files (Ctrl+C / Ctrl+X / Ctrl+V). Local files are also put on the system clipboard
 * (file list, text/uri-list, GNOME format), archive entries only on the internal one.
 */
public final class FileClipboard {

    private static final DataFlavor URI_LIST = flavor("text/uri-list;class=java.lang.String");
    private static final DataFlavor GNOME = flavor("x-special/gnome-copied-files;class=java.io.InputStream");

    private @NotNull List<VFile> files = List.of();
    private boolean cut;
    private @Nullable Transferable published;

    /** What to paste: files and the move flag. */
    @Getter
    public static final class Content {

        private final @NotNull List<VFile> files;
        private final boolean move;

        public Content(final @NotNull List<VFile> files, final boolean move) {
            this.files = files;
            this.move = move;
        }
    }

    public void put(final @NotNull List<VFile> selection, final boolean isCut) {
        files = List.copyOf(selection);
        cut = isCut;
        final List<File> local = new ArrayList<>();
        for (final VFile f : selection) {
            if (f.isLocal()) {
                local.add(f.getHostPath().toFile());
            }
        }
        final Transferable t = new FilesTransferable(local, isCut);
        published = t;
        try {
            system().setContents(t, null);
        } catch (final IllegalStateException e) {
            // the system clipboard is held by another application — the internal one remains
        }
    }

    public @Nullable Content take() {
        final Clipboard cb = system();
        try {
            final Transferable current = cb.getContents(null);
            if (current != null && current != published && current.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return new Content(statAll(current.getTransferData(DataFlavor.javaFileListFlavor)), false);
            }
            if (current != null && current != published && current.isDataFlavorSupported(URI_LIST)) {
                return new Content(fromUris((String) current.getTransferData(URI_LIST)), false);
            }
        } catch (final UnsupportedFlavorException | IOException | IllegalStateException e) {
            // the system clipboard has no files — use the internal one
        }
        if (files.isEmpty()) {
            return null;
        }
        final Content content = new Content(files, cut);
        if (cut) {
            files = List.of();
            cut = false;
        }

        return content;
    }

    private static @NotNull List<VFile> statAll(final @NotNull Object data) {
        final List<VFile> result = new ArrayList<>();
        for (final Object o : (List<?>) data) {
            try {
                result.add(Vfs.stat(((File) o).toPath()));
            } catch (final IOException e) {
                // the file disappeared from the FS after being copied to the clipboard
            }
        }

        return result;
    }

    private static @NotNull List<VFile> fromUris(final @NotNull String uris) {
        final List<VFile> result = new ArrayList<>();
        for (final String line : uris.split("\r?\n")) {
            if (line.startsWith("file:")) {
                try {
                    result.add(Vfs.stat(Path.of(URI.create(line.trim()))));
                } catch (final IOException | IllegalArgumentException e) {
                    // malformed or stale link
                }
            }
        }

        return result;
    }

    private static @NotNull Clipboard system() {
        return Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    private static @Nullable DataFlavor flavor(final @NotNull String mime) {
        try {
            return new DataFlavor(mime);
        } catch (final ClassNotFoundException e) {
            return null;
        }
    }

    private static final class FilesTransferable implements Transferable {

        private final @NotNull List<File> files;
        private final boolean cut;

        private FilesTransferable(final @NotNull List<File> files, final boolean cut) {
            this.files = files;
            this.cut = cut;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            final List<DataFlavor> list = new ArrayList<>();
            list.add(DataFlavor.javaFileListFlavor);
            if (URI_LIST != null) {
                list.add(URI_LIST);
            }
            if (GNOME != null) {
                list.add(GNOME);
            }
            list.add(DataFlavor.stringFlavor);

            return list.toArray(new DataFlavor[0]);
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            for (final DataFlavor f : getTransferDataFlavors()) {
                if (f.equals(flavor)) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public @NotNull Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
            if (DataFlavor.javaFileListFlavor.equals(flavor)) {
                return files;
            }
            final StringBuilder uris = new StringBuilder();
            final StringBuilder paths = new StringBuilder();
            for (final File f : files) {
                uris.append(f.toURI()).append("\r\n");
                paths.append(f.getAbsolutePath()).append('\n');
            }
            if (flavor.equals(URI_LIST)) {
                return uris.toString();
            }
            if (flavor.equals(GNOME)) {
                return new ByteArrayInputStream(((cut ? "cut" : "copy") + "\n" + uris.toString().replace("\r\n", "\n").trim())
                    .getBytes(StandardCharsets.UTF_8));
            }
            if (DataFlavor.stringFlavor.equals(flavor)) {
                return paths.toString().trim();
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }
}
