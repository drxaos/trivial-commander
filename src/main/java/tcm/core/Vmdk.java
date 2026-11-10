package tcm.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * VMware disk (.vmdk): a hosted sparse extent (monolithicSparse, streamOptimized with deflated grains) or a text descriptor listing
 * flat, sparse and zero extents in files next to it (monolithicFlat, twoGbMaxExtentSparse/Flat). A delta disk reads the unallocated
 * grains from its parent (parentFileNameHint). ESX sparse (COWD) extents are not supported.
 */
final class Vmdk extends SparseDisk {

    static final int MAGIC = 0x564D444B;
    static final String DESCRIPTOR = "# Disk DescriptorFile";
    private static final long GD_AT_END = -1;
    private static final int FLAG_COMPRESSED = 1 << 16;
    private static final int TABLE_CACHE = 1024;
    private static final int MAX_DESCRIPTOR = 1 << 20;
    private static final Pattern EXTENT = Pattern.compile(
        "^\\s*(RW|RDONLY|NOACCESS)\\s+(\\d+)\\s+(\\w+)(?:\\s+\"([^\"]*)\"(?:\\s+(\\d+))?)?",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PARENT_HINT = Pattern.compile("^\\s*parentFileNameHint\\s*=\\s*\"([^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern PARENT_CID = Pattern.compile("^\\s*parentCID\\s*=\\s*(\\w+)", Pattern.MULTILINE);

    private final int[] gd;
    private final int perTable;
    private final boolean compressed;
    private final Map<Long, int[]> tables = lru(TABLE_CACHE);

    private Vmdk(
        final @NotNull FileChannel ch,
        final long size,
        final int grain,
        final int @NotNull [] gd,
        final int perTable,
        final boolean compressed,
        final @Nullable VirtualDisks.Disk parent
    ) throws IOException {
        super(ch, size, grain, parent);
        this.gd = gd;
        this.perTable = perTable;
        this.compressed = compressed;
    }

    static boolean matches(final byte @NotNull [] head) {
        return head.length >= 4 && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == MAGIC
            || head.length >= DESCRIPTOR.length() && DESCRIPTOR.equals(new String(head, 0, DESCRIPTOR.length(), StandardCharsets.ISO_8859_1));
    }

    static @NotNull VirtualDisks.Disk open(final @NotNull FileChannel ch, final @NotNull Path file) throws IOException {
        final byte[] head = new byte[(int) Math.min(ch.size(), 512)];
        readFully(ch, 0, head, 0, head.length);
        if (head.length >= 4 && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == MAGIC) {
            return sparse(ch, file, head, null);
        }
        if (ch.size() > MAX_DESCRIPTOR || !matches(head)) {
            throw new IOException("Not a VMDK image");
        }
        final byte[] text = new byte[(int) ch.size()];
        readFully(ch, 0, text, 0, text.length);
        ch.close();

        return descriptor(file, new String(text, StandardCharsets.UTF_8));
    }

    private static @NotNull Vmdk sparse(
        final @NotNull FileChannel ch,
        final @NotNull Path file,
        final byte @NotNull [] head,
        final @Nullable VirtualDisks.Disk delta
    ) throws IOException {
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getLong(56) == GD_AT_END) {
            // streamOptimized: the real header is the footer before the end-of-stream marker
            final byte[] footer = new byte[512];
            readFully(ch, ch.size() - 1024, footer, 0, footer.length);
            h = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getInt(0) != MAGIC) {
                throw new IOException("Damaged VMDK footer");
            }
        }
        final int flags = h.getInt(8);
        final long capacity = h.getLong(12);
        final long grain = h.getLong(20);
        final long descriptorOffset = h.getLong(28);
        final long descriptorSize = h.getLong(36);
        final int perTable = h.getInt(44);
        final long gdOffset = h.getLong(56);
        if (grain < 1 || grain > 2048 || Long.bitCount(grain) != 1 || perTable < 1 || perTable > (1 << 16) || capacity < 0
            || gdOffset <= 0) {
            throw new IOException("Damaged VMDK header");
        }
        final long tables = (capacity / grain + perTable - 1) / perTable;
        if (tables > (1 << 24)) {
            throw new IOException("Damaged VMDK header");
        }
        final byte[] raw = new byte[(int) tables * 4];
        readFully(ch, gdOffset * 512, raw, 0, raw.length);
        final int[] gd = new int[(int) tables];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(gd);
        VirtualDisks.Disk parent = delta;
        if (parent == null && descriptorOffset > 0 && descriptorSize > 0 && descriptorSize * 512 <= MAX_DESCRIPTOR) {
            final byte[] text = new byte[(int) descriptorSize * 512];
            readFully(ch, descriptorOffset * 512, text, 0, text.length);
            parent = parent(file, new String(text, StandardCharsets.UTF_8));
        }
        try {
            return new Vmdk(ch, capacity * 512, (int) grain * 512, gd, perTable, (flags & FLAG_COMPRESSED) != 0, parent);
        } catch (final IOException | RuntimeException e) {
            closeQuietly(parent);
            throw e;
        }
    }

    private static @Nullable VirtualDisks.Disk parent(final @NotNull Path file, final @NotNull String descriptor) throws IOException {
        final Matcher cid = PARENT_CID.matcher(descriptor);
        final Matcher hint = PARENT_HINT.matcher(descriptor);
        if (!cid.find() || "ffffffff".equalsIgnoreCase(cid.group(1)) || !hint.find()) {
            return null;
        }

        return VirtualDisks.backing(file, hint.group(1));
    }

    /** A text descriptor: the disk is its extents one after another. */
    private static @NotNull VirtualDisks.Disk descriptor(final @NotNull Path file, final @NotNull String text) throws IOException {
        final List<VirtualDisks.Disk> extents = new ArrayList<>();
        final VirtualDisks.Disk parent = parent(file, text);
        try {
            long offset = 0;
            for (final String line : text.split("\\R")) {
                final Matcher m = EXTENT.matcher(line);
                if (m.find()) {
                    final long size = Long.parseLong(m.group(2)) * 512;
                    extents.add(extent(file, m, size, parent == null ? null : VirtualDisks.view(parent, offset, size)));
                    offset += size;
                }
            }
            if (extents.isEmpty()) {
                throw new IOException("VMDK descriptor lists no extents");
            }

            return VirtualDisks.concat(extents, parent);
        } catch (final IOException | RuntimeException e) {
            for (final VirtualDisks.Disk d : extents) {
                closeQuietly(d);
            }
            closeQuietly(parent);
            throw e;
        }
    }

    private static @NotNull VirtualDisks.Disk extent(
        final @NotNull Path file,
        final @NotNull Matcher m,
        final long size,
        final @Nullable VirtualDisks.Disk parent
    ) throws IOException {
        final String type = m.group(3).toUpperCase(Locale.ROOT);
        if ("ZERO".equals(type) || "NOACCESS".equalsIgnoreCase(m.group(1))) {
            return VirtualDisks.zeros(size);
        }
        final String name = m.group(4);
        if (name == null) {
            throw new IOException("VMDK extent without a file: " + m.group());
        }
        final Path path = VirtualDisks.sibling(file, name);
        if (!Files.isRegularFile(path)) {
            throw new IOException("VMDK extent not found: " + name);
        }
        final FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return switch (type) {
                case "FLAT", "VMFS" -> {
                    final long offset = m.group(5) == null ? 0 : Long.parseLong(m.group(5)) * 512;
                    yield VirtualDisks.slice(VirtualDisks.raw(ch, ch.size()), offset, size);
                }
                case "SPARSE" -> {
                    final byte[] head = new byte[(int) Math.min(ch.size(), 512)];
                    readFully(ch, 0, head, 0, head.length);
                    yield VirtualDisks.slice(sparse(ch, path, head, parent), 0, size);
                }
                default -> throw new IOException("Unsupported VMDK extent type " + type);
            };
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    @Override
    protected long map(final long index) throws IOException {
        final long e = entry(index);
        if (e == 0) {
            return HOLE;
        }
        if (e == 1) {
            return ZERO;
        }

        return compressed ? COMPRESSED : e * 512;
    }

    @Override
    protected long holeEnd(final long index) {
        final long table = index / perTable;

        return table >= gd.length || gd[(int) table] == 0 ? (table + 1) * perTable : index + 1;
    }

    private long entry(final long index) throws IOException {
        final long table = index / perTable;
        if (table >= gd.length || gd[(int) table] == 0) {
            return 0;
        }
        final long at = Integer.toUnsignedLong(gd[(int) table]) * 512;
        int[] gt = tables.get(at);
        if (gt == null) {
            final byte[] raw = readFile(at, perTable * 4);
            gt = new int[perTable];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(gt);
            tables.put(at, gt);
        }

        return Integer.toUnsignedLong(gt[(int) (index % perTable)]);
    }

    @Override
    protected byte @NotNull [] inflate(final long index) throws IOException {
        final long at = entry(index) * 512;
        // a grain marker: the grain's LBA (8 bytes) and the compressed size (4 bytes)
        final ByteBuffer marker = ByteBuffer.wrap(readFile(at, 12)).order(ByteOrder.LITTLE_ENDIAN);
        final int length = marker.getInt(8);
        if (length <= 0 || length > unit() * 2 + 1024) {
            throw new IOException("Damaged VMDK grain " + index);
        }

        return inflate(readFile(at + 12, length), unit(), false);
    }
}
