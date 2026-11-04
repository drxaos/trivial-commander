package tcm.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.jetbrains.annotations.NotNull;

/**
 * Squashfs images and AppImages (a squashfs image appended to an ELF runtime) — read only. Nothing from an AppImage is executed.
 * A symbolic link is shown as the file it points to (reading it reads that file), links to directories as empty files.
 */
final class SquashArchives {

    private SquashArchives() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        try (FileChannel ch = FileChannel.open(index.getArchive(), StandardOpenOption.READ)) {
            open(ch, index.getArchive()).walk((path, directory, size, modified, mode) ->
                index.add(path, directory, size, modified, FileAttrs.posix(mode)));
        }
    }

    static @NotNull ArchiveReader reader(final @NotNull Path archive) throws IOException {
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        final SquashFs fs;
        try {
            fs = open(ch, archive);
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e;
        }

        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final InputStream in = fs.open(entry);
                if (in == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }

                return in;
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }

    private static @NotNull SquashFs open(final @NotNull FileChannel ch, final @NotNull Path archive) throws IOException {
        final SquashFs fs = SquashFs.open(ch, SquashFs.imageOffset(ch));
        if (fs == null) {
            throw new IOException("No squashfs image in " + archive.getFileName());
        }

        return fs;
    }
}
