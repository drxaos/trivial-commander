package tcm.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Virtual machine disk images — read only: VirtualBox .vdi, Virtual PC / Hyper-V .vhd and .vhdx, VMware .vmdk, QEMU .qcow, .qcow2
 * and .qed, Parallels .hdd. The partitions of the disk are listed as raw .img files (see DiskImages), which open as nested archives.
 */
final class VirtualDisks {

    private static final int PROBE = 512;
    private static final int MAX_CHAIN = 16;
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** A virtual disk: its bytes and the files it keeps open. */
    interface Disk extends BlockSource, Closeable {
    }

    private VirtualDisks() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        // a Parallels "plain" disk is raw data under the same extension as an expanding one
        final String name = String.valueOf(archive.getFileName()).toLowerCase(Locale.ROOT);
        final Disk disk = open(archive, name.endsWith(".hdd") || name.endsWith(".hds"));
        try {
            index.setTar(DiskImages.listDisk(index, disk, disk));
        } catch (final IOException | RuntimeException e) {
            disk.close();
            throw e instanceof IOException io ? io : new IOException("Damaged disk image: " + archive.getFileName(), e);
        }
    }

    /** The head of the file is a virtual disk header — for Ctrl+PgDn on an image with another extension. */
    static boolean matches(final byte @NotNull [] head) {
        return Vdi.matches(head) || Vhdx.matches(head) || Vhd.matches(head) || Vmdk.matches(head) || Qcow.matches(head)
            || Qed.matches(head) || ParallelsDisk.matches(head);
    }

    /** The same plus a fixed VHD, recognized by its footer only. */
    static boolean matches(final @NotNull Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final byte[] head = new byte[(int) Math.min(ch.size(), PROBE)];
            SparseDisk.readFully(ch, 0, head, 0, head.length);

            return matches(head) || Vhd.hasFooter(ch);
        }
    }

    /** Opens the disk image; rawFallback — a file of no known format is a raw disk (a backing file). */
    static @NotNull Disk open(final @NotNull Path file, final boolean rawFallback) throws IOException {
        final FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
        try {
            final byte[] head = new byte[(int) Math.min(ch.size(), PROBE)];
            SparseDisk.readFully(ch, 0, head, 0, head.length);
            if (Vdi.matches(head)) {
                return Vdi.open(ch);
            }
            if (Vhdx.matches(head)) {
                return Vhdx.open(ch);
            }
            if (Vhd.matches(head) || Vhd.hasFooter(ch)) {
                return Vhd.open(ch);
            }
            if (Vmdk.matches(head)) {
                return Vmdk.open(ch, file);
            }
            if (Qcow.matches(head)) {
                return Qcow.open(ch, file);
            }
            if (Qed.matches(head)) {
                return Qed.open(ch, file);
            }
            if (ParallelsDisk.matches(head)) {
                return ParallelsDisk.open(ch);
            }
            if (rawFallback) {
                return raw(ch, ch.size());
            }

            throw new IOException("Unknown virtual disk format: " + file.getFileName());
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    /** The parent image of a differencing disk: a name relative to the child's directory or an absolute path. */
    static @NotNull Disk backing(final @NotNull Path child, final @NotNull String name) throws IOException {
        final Path path = sibling(child, name.replace("\0", "").strip());
        if (!Files.isRegularFile(path)) {
            throw new IOException("Backing file not found: " + name);
        }
        final int[] depth = DEPTH.get();
        if (depth[0] >= MAX_CHAIN) {
            throw new IOException("Backing file chain is too long: " + name);
        }
        depth[0]++;
        try {
            return open(path, true);
        } finally {
            depth[0]--;
        }
    }

    static @NotNull Path sibling(final @NotNull Path file, final @NotNull String name) throws IOException {
        try {
            final Path p = Path.of(name);
            final Path dir = file.toAbsolutePath().getParent();

            return p.isAbsolute() || dir == null ? p : dir.resolve(p).normalize();
        } catch (final InvalidPathException e) {
            throw new IOException("Invalid file name in the disk image: " + name, e);
        }
    }

    /** The first size bytes of the file as they are. */
    static @NotNull Disk raw(final @NotNull FileChannel ch, final long size) throws IOException {
        final BlockSource file = BlockSource.of(ch);
        final long length = Math.min(size, file.size());

        return new Disk() {
            @Override
            public long size() {
                return length;
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                if (pos < 0 || pos + len > length) {
                    throw new EOFException("read past the end of the disk");
                }
                file.read(pos, b, off, len);
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }

    /** The range of the disk as a disk that leaves the original open on close: the part of a parent under one extent. */
    static @NotNull Disk view(final @NotNull BlockSource disk, final long offset, final long length) {
        final BlockSource range = BlockSource.slice(disk, offset, length);

        return new Disk() {
            @Override
            public long size() {
                return range.size();
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                range.read(pos, b, off, len);
            }

            @Override
            public long dataFrom(final long pos) throws IOException {
                return range.dataFrom(pos);
            }

            @Override
            public void close() {
                // the disk belongs to the caller
            }
        };
    }

    /** size zero bytes. */
    static @NotNull Disk zeros(final long size) {
        return new Disk() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                if (pos < 0 || pos + len > size) {
                    throw new EOFException("read past the end of the disk");
                }
                Arrays.fill(b, off, off + len, (byte) 0);
            }

            @Override
            public long dataFrom(final long pos) {
                return size;
            }

            @Override
            public void close() {
                // nothing is open
            }
        };
    }

    /** A range of a disk, padded with zeros past its end; closes the disk. */
    static @NotNull Disk slice(final @NotNull Disk disk, final long offset, final long length) {
        final BlockSource range = BlockSource.slice(disk, offset, length);

        return new Disk() {
            @Override
            public long size() {
                return length;
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                if (pos < 0 || pos + len > length) {
                    throw new EOFException("read past the end of the disk");
                }
                final int n = (int) Math.max(0, Math.min(len, range.size() - pos));
                if (n > 0) {
                    range.read(pos, b, off, n);
                }
                Arrays.fill(b, off + n, off + len, (byte) 0);
            }

            @Override
            public long dataFrom(final long pos) throws IOException {
                return pos >= range.size() ? length : range.dataFrom(pos);
            }

            @Override
            public void close() throws IOException {
                disk.close();
            }
        };
    }

    /** Extents one after another; the parent of a delta disk (read by the extents through views) is closed with them. */
    static @NotNull Disk concat(final @NotNull List<Disk> extents, final @Nullable Disk parent) {
        final long[] starts = new long[extents.size() + 1];
        for (int i = 0; i < extents.size(); i++) {
            starts[i + 1] = starts[i] + extents.get(i).size();
        }
        final long size = starts[extents.size()];

        return new Disk() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public void read(final long pos, final byte @NotNull [] b, final int off, final int len) throws IOException {
                if (pos < 0 || pos + len > size) {
                    throw new EOFException("read past the end of the disk");
                }
                long p = pos;
                int o = off;
                int left = len;
                while (left > 0) {
                    final int i = extent(p);
                    final int n = (int) Math.min(left, starts[i + 1] - p);
                    extents.get(i).read(p - starts[i], b, o, n);
                    p += n;
                    o += n;
                    left -= n;
                }
            }

            @Override
            public long dataFrom(final long pos) throws IOException {
                long p = Math.max(0, pos);
                while (p < size) {
                    final int i = extent(p);
                    final long data = starts[i] + extents.get(i).dataFrom(p - starts[i]);
                    if (data < starts[i + 1]) {
                        return data;
                    }
                    p = starts[i + 1];
                }

                return size;
            }

            private int extent(final long pos) {
                int i = Arrays.binarySearch(starts, pos);
                if (i < 0) {
                    i = -i - 2;
                }
                // skip empty extents
                while (starts[i + 1] <= pos) {
                    i++;
                }

                return i;
            }

            @Override
            public void close() throws IOException {
                IOException failure = null;
                for (final Disk d : extents) {
                    try {
                        d.close();
                    } catch (final IOException e) {
                        failure = e;
                    }
                }
                if (parent != null) {
                    parent.close();
                }
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    /** The whole disk as a stream — for tests. */
    static @NotNull InputStream stream(final @NotNull BlockSource disk) {
        return BlockSource.stream(disk, 0, disk.size());
    }
}
