package tcm.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import tcm.core.ArchiveFormat;
import tcm.core.ArchiveReader;
import tcm.core.Streams;
import tcm.core.TarArchives;
import tcm.core.VFile;
import tcm.core.Vfs;

/**
 * Archive testing: every entry is read in full. ZIP is checked against CRC32 (ZipFile does not verify the checksum itself),
 * compressed TAR — by the gzip/bzip2/xz checksum while reading the stream, RAR — by the CRC check in junrar.
 */
public final class TestJob extends Job {

    private final @NotNull List<VFile> archives;
    @Getter
    private final List<String> report = new ArrayList<>();

    public TestJob(final @NotNull List<VFile> archives) {
        super("Test " + archives.size() + " archive(s)");
        this.archives = List.copyOf(archives);
    }

    @Override
    public void run(final @NotNull JobContext ctx) throws Exception {
        long total = 0;
        for (final VFile a : archives) {
            total += a.getSize();
        }
        ctx.setTotal(total);
        for (final VFile a : archives) {
            report.add(test(a, ctx));
        }
    }

    private static @NotNull String test(final @NotNull VFile archive, final @NotNull JobContext ctx) throws CancelledException {
        try {
            final ArchiveFormat format = ArchiveFormat.forFile(archive.getHostPath());
            final int entries;
            if (format == ArchiveFormat.ZIP) {
                entries = testZip(archive, ctx);
            } else if (format == ArchiveFormat.RAR) {
                entries = testEntries(archive, ctx);
            } else if (format != null) {
                entries = testTar(archive, format, ctx);
            } else {
                return "ERROR  " + archive.getLocation() + ": not an archive";
            }

            return "OK     " + archive.getLocation() + " (" + entries + " file(s))";
        } catch (final CancelledException e) {
            throw e;
        } catch (final IOException e) {
            return "ERROR  " + archive.getLocation() + ": " + e.getMessage();
        }
    }

    private static int testZip(final @NotNull VFile archive, final @NotNull JobContext ctx) throws IOException {
        int entries = 0;
        try (ZipFile zip = Vfs.openZip(archive.getHostPath())) {
            final Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                final ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) {
                    continue;
                }
                ctx.file(archive.getName() + ": " + ze.getName(), ze.getSize());
                final CRC32 crc = new CRC32();
                final long size;
                try (InputStream in = new CheckedInputStream(zip.getInputStream(ze), crc)) {
                    size = Streams.copy(in, OutputStream.nullOutputStream(), ctx);
                }
                if (ze.getCrc() >= 0 && crc.getValue() != ze.getCrc() || ze.getSize() >= 0 && size != ze.getSize()) {
                    throw new IOException("CRC error in " + ze.getName());
                }
                entries++;
            }
        }

        return entries;
    }

    /** Compressed TAR is re-read as a stream, not from the cache: only the decompressor reports a checksum error. */
    private static int testTar(final @NotNull VFile archive, final @NotNull ArchiveFormat format, final @NotNull JobContext ctx)
        throws IOException {
        int entries = 0;
        try (TarArchiveInputStream in = TarArchives.openStream(archive.getHostPath(), format)) {
            TarArchiveEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.isFile()) {
                    continue;
                }
                ctx.file(archive.getName() + ": " + e.getName(), e.getSize());
                final long size = Streams.copy(in, OutputStream.nullOutputStream(), ctx);
                if (size != e.getSize()) {
                    throw new IOException("Truncated entry " + e.getName());
                }
                entries++;
            }
            // the rest of the stream after the last entry — so the decompressor reaches the checksum at the end
            in.transferTo(OutputStream.nullOutputStream());
        }

        return entries;
    }

    private static int testEntries(final @NotNull VFile archive, final @NotNull JobContext ctx) throws IOException {
        int entries = 0;
        try (ArchiveReader reader = Vfs.openReader(archive.getHostPath())) {
            for (final VFile f : Vfs.index(archive.getHostPath()).subtree("").values()) {
                if (f.isDirectory()) {
                    continue;
                }
                ctx.file(archive.getName() + ": " + f.getEntry(), f.getSize());
                try (InputStream in = reader.open(f.getEntry())) {
                    Streams.copy(in, OutputStream.nullOutputStream(), ctx);
                }
                entries++;
            }
        }

        return entries;
    }
}
