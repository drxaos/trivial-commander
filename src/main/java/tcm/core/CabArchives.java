package tcm.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/** Microsoft cabinets (.cab) — read only. The cabinet stays open for the index's lifetime with its decoded folders cached. */
final class CabArchives {

    private CabArchives() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        try {
            final CabFile cab = CabFile.open(BlockSource.of(ch));
            final Map<String, CabFile.Entry> byName = new HashMap<>();
            for (final CabFile.Entry e : cab.getEntries()) {
                index.add(e.getName(), false, e.getSize(), e.getTime(), FileAttrs.dos(e.getAttributes()));
                byName.put(ArchiveIndex.entryName(e.getName()), e);
            }
            index.setTar(new ArchiveReader() {
                @Override
                public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                    final CabFile.Entry e = byName.get(entry);
                    if (e == null) {
                        throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                    }

                    return cab.open(e);
                }

                @Override
                public void close() throws IOException {
                    try {
                        cab.close();
                    } finally {
                        ch.close();
                    }
                }
            });
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException io ? io : new IOException("Not a valid cabinet: " + archive.getFileName(), e);
        }
    }
}
