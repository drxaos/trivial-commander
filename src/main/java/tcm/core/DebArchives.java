package tcm.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarFile;
import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Debian packages (.deb, .udeb) — read only. Shown the way {@code dpkg-deb -R} unpacks them: the files of data.tar at the root,
 * the control files (control, postinst, md5sums...) in DEBIAN. Both tars are decompressed once into temporary files
 * (gzip, xz, zstd, bzip2, lzma or none), which are deleted when the index is disposed.
 */
final class DebArchives {

    static final String CONTROL_DIR = "DEBIAN";
    private static final byte[] MAGIC = "!<arch>\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final int MEMBER_HEADER = 60;

    private DebArchives() {
    }

    /** An ar archive whose first member is debian-binary. */
    static boolean matches(final byte @NotNull [] head) {
        final byte[] sig = "!<arch>\ndebian-binary".getBytes(StandardCharsets.ISO_8859_1);
        if (head.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (head[i] != sig[i]) {
                return false;
            }
        }

        return true;
    }

    @Getter
    @RequiredArgsConstructor
    private static final class Item {

        private final TarFile tar;
        private final TarArchiveEntry entry;
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final List<TarFile> tars = new ArrayList<>();
        final Map<String, Item> byName = new HashMap<>();
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            final BlockSource src = BlockSource.of(ch);
            if (src.size() < MAGIC.length || !Arrays.equals(src.read(0, MAGIC.length), MAGIC)) {
                throw new IOException("Not a Debian package: " + archive.getFileName());
            }
            long pos = MAGIC.length;
            while (pos + MEMBER_HEADER <= src.size()) {
                final String header = new String(src.read(pos, MEMBER_HEADER), StandardCharsets.ISO_8859_1);
                final String name = header.substring(0, 16).strip();
                final long size = Long.parseLong(header.substring(48, 58).strip());
                final long data = pos + MEMBER_HEADER;
                final String prefix = name.startsWith("data.tar") ? "" : name.startsWith("control.tar") ? CONTROL_DIR + "/" : null;
                if (prefix != null) {
                    final TarFile tar = new TarFile(Compression.spill(Compression.decompress(BlockSource.stream(src, data, size))));
                    tars.add(tar);
                    for (final TarArchiveEntry e : tar.getEntries()) {
                        final String entry = prefix + e.getName();
                        final boolean dir = e.isDirectory();
                        index.add(entry, dir, e.isFile() ? e.getSize() : 0, e.getModTime().getTime(), FileAttrs.posix(e.getMode()));
                        if (!dir) {
                            byName.put(ArchiveIndex.entryName(entry), new Item(tar, e));
                        }
                    }
                }
                // members are aligned to 2 bytes
                pos = data + size + (size & 1);
            }
        } catch (final IOException | RuntimeException e) {
            try {
                closeAll(tars);
            } catch (final IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e instanceof IOException io ? io : new IOException("Not a valid Debian package: " + archive.getFileName(), e);
        }
        index.setTar(reader(archive, byName, tars));
    }

    private static @NotNull ArchiveReader reader(
        final @NotNull Path archive,
        final @NotNull Map<String, Item> byName,
        final @NotNull List<TarFile> tars
    ) {
        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final Item item = byName.get(entry);
                if (item == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }
                // links and special files have no content
                return item.getEntry().isFile() ? item.getTar().getInputStream(item.getEntry()) : InputStream.nullInputStream();
            }

            @Override
            public void close() throws IOException {
                closeAll(tars);
            }
        };
    }

    private static void closeAll(final @NotNull List<TarFile> tars) throws IOException {
        IOException error = null;
        for (final TarFile tar : tars) {
            try {
                tar.close();
            } catch (final IOException e) {
                error = e;
            }
        }
        if (error != null) {
            throw error;
        }
    }
}
