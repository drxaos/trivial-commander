package tcm.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;

/**
 * Apple disk images (.dmg) — read only. The file systems of the partitions are shown (APFS, HFS+, FAT... — see DiskImages);
 * an image with no supported file system lists its partitions as raw .img files, which can be unpacked and opened elsewhere.
 * The image stays open for the index's lifetime: parsing the file system tree again for each copied file would be slow.
 */
final class DmgArchives {

    private static final String FREE = "Apple_Free";

    private DmgArchives() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        try {
            final DmgImage image = DmgImage.open(ch);
            final List<DiskImages.Volume> volumes = new ArrayList<>();
            for (final DmgImage.Partition p : image.getPartitions()) {
                if (!p.getName().contains(FREE) && p.size() > 0) {
                    volumes.add(new DiskImages.Volume(p.getIndex(), p.getName(), p));
                }
            }
            index.setTar(DiskImages.mount(index, volumes, ch, true));
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException io ? io : new IOException("Not a valid disk image: " + archive.getFileName(), e);
        }
    }

    /** True if the file ends with the UDIF trailer — for Ctrl+PgDn on an image with another extension. */
    static boolean matches(final @NotNull Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return DmgImage.matches(ch);
        }
    }
}
