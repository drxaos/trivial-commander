package tcm.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Apple disk images (.dmg) — read only. The files of the APFS volume(s) are shown; an image with a file system that is not
 * supported (HFS+, FAT...) or none at all lists its partitions as raw .img files, which can be unpacked and opened elsewhere.
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
            final Apfs apfs = apfs(image);
            final Map<String, DmgImage.Partition> raw = new LinkedHashMap<>();
            if (apfs != null) {
                apfs.walk((path, directory, size, modified, mode) -> index.add(path, directory, size, modified, FileAttrs.posix(mode)));
            } else {
                for (final DmgImage.Partition p : image.getPartitions()) {
                    if (!p.getName().contains(FREE) && p.size() > 0) {
                        final String name = rawName(p);
                        raw.put(name, p);
                        index.add(name, false, p.size(), 0, "");
                    }
                }
            }
            index.setTar(reader(ch, archive, apfs, raw));
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

    /** The first partition holding an APFS container; null — none. */
    private static @Nullable Apfs apfs(final @NotNull DmgImage image) throws IOException {
        for (final DmgImage.Partition p : image.getPartitions()) {
            if (!p.getName().contains(FREE) && Apfs.matches(p)) {
                return Apfs.open(p);
            }
        }

        return null;
    }

    private static @NotNull String rawName(final @NotNull DmgImage.Partition p) {
        final String name = p.getName().replaceAll("[/\\\\:*?\"<>|]", " ").replaceAll("\\s+", " ").strip();

        return String.format(Locale.ROOT, "%d %s.img", p.getIndex(), name.isEmpty() ? "partition" : name);
    }

    private static @NotNull ArchiveReader reader(
        final @NotNull FileChannel ch,
        final @NotNull Path archive,
        final @Nullable Apfs apfs,
        final @NotNull Map<String, DmgImage.Partition> raw
    ) {
        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final DmgImage.Partition partition = raw.get(entry);
                final InputStream in = apfs != null
                    ? apfs.open(entry)
                    : partition != null ? BlockSource.stream(partition, 0, partition.size()) : null;
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
}
