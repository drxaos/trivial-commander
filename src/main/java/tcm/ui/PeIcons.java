package tcm.ui;

import java.awt.image.BufferedImage;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;


import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Icons of Windows files read without the Windows shell: the main icon of a PE file (exe, dll — the first RT_GROUP_ICON resource,
 * as Explorer shows it) and the best image of an .ico/.cur or .icns file (decoded by IconImages).
 */
final class PeIcons {

    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_ICON_FILE = 16 * 1024 * 1024;

    private PeIcons() {
    }

    /** The icon image closest to the size, or null if the file has none. */
    static @Nullable BufferedImage read(final @NotNull Path file, final int size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            if (ch.size() < 64) {
                return null;
            }
            final ByteBuffer head = at(ch, 0, 6);
            if (head.get(0) == 'M' && head.get(1) == 'Z') {
                return readPe(ch, size);
            }
            final int type = u16(head, 2);
            if (u16(head, 0) == 0 && (type == 1 || type == 2) || head.get(0) == 'i' && head.get(1) == 'c' && head.get(2) == 'n'
                && head.get(3) == 's') {
                return readIcon(ch, size);
            }

            return null;
        }
    }

    private static @Nullable BufferedImage readIcon(final @NotNull FileChannel ch, final int size) throws IOException {
        if (ch.size() > MAX_ICON_FILE) {
            return null;
        }

        return IconImages.decode(bytes(at(ch, 0, (int) ch.size())), size);
    }

    private static @Nullable BufferedImage readPe(final @NotNull FileChannel ch, final int size) throws IOException {
        final Resources res = Resources.open(ch);
        if (res == null) {
            return null;
        }
        final ByteBuffer group = res.first(RT_GROUP_ICON);
        if (group == null || group.remaining() < 6) {
            return null;
        }
        final int count = Math.min(u16(group, 4), (group.remaining() - 6) / 14);
        final List<IconImages.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int o = 6 + i * 14;
            entries.add(new IconImages.Entry(u8(group, o), u8(group, o + 2), u16(group, o + 6), group.getInt(o + 8), u16(group, o + 12)));
        }
        final IconImages.Entry best = IconImages.choose(entries, size);
        if (best == null) {
            return null;
        }
        final ByteBuffer image = res.byId(RT_ICON, best.ref());

        return image == null ? null : IconImages.decodeImage(bytes(image));
    }

    private static @NotNull ByteBuffer at(final @NotNull FileChannel ch, final long pos, final int len) throws IOException {
        if (pos < 0 || len < 0 || len > MAX_ICON_FILE || pos + len > ch.size()) {
            throw new EOFException("outside the file");
        }
        final ByteBuffer buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.hasRemaining()) {
            if (ch.read(buf, pos + buf.position()) < 0) {
                throw new EOFException();
            }
        }

        return buf.flip();
    }

    private static byte @NotNull [] bytes(final @NotNull ByteBuffer buf) {
        final byte[] result = new byte[buf.remaining()];
        buf.duplicate().get(result);

        return result;
    }

    private static int u8(final @NotNull ByteBuffer b, final int pos) {
        return b.get(pos) & 0xFF;
    }

    private static int u16(final @NotNull ByteBuffer b, final int pos) {
        return b.getShort(pos) & 0xFFFF;
    }

    /** The resource tree of a PE file: type → name → language → data. */
    private static final class Resources {

        private final FileChannel ch;
        private final long base;
        private final List<int[]> sections;

        private Resources(final @NotNull FileChannel ch, final long base, final @NotNull List<int[]> sections) {
            this.ch = ch;
            this.base = base;
            this.sections = sections;
        }

        static @Nullable Resources open(final @NotNull FileChannel ch) throws IOException {
            final long pe = at(ch, 0x3C, 4).getInt(0) & 0xFFFFFFFFL;
            final ByteBuffer coff = at(ch, pe, 24);
            if (coff.getInt(0) != 0x00004550) {
                return null;
            }
            final int sectionCount = u16(coff, 6);
            final int optSize = u16(coff, 20);
            final ByteBuffer opt = at(ch, pe + 24, optSize);
            final int dirs = optSize >= 2 && u16(opt, 0) == 0x20B ? 112 : 96;
            if (optSize < dirs + 24 || opt.getInt(dirs - 4) < 3) {
                return null;
            }
            final int rva = opt.getInt(dirs + 16);
            if (rva == 0) {
                return null;
            }
            final ByteBuffer table = at(ch, pe + 24 + optSize, 40 * sectionCount);
            final List<int[]> sections = new ArrayList<>();
            for (int i = 0; i < sectionCount; i++) {
                final int o = i * 40;
                // virtual address, mapped size, raw data pointer
                sections.add(new int[] {table.getInt(o + 12), Math.max(table.getInt(o + 8), table.getInt(o + 16)), table.getInt(o + 20)});
            }
            final Resources res = new Resources(ch, 0, sections);
            final long base = res.offset(rva);

            return base < 0 ? null : new Resources(ch, base, sections);
        }

        /** The data of the first resource of the type (first name, first language). */
        @Nullable ByteBuffer first(final int type) throws IOException {
            final int dir = find(0, type, false);
            if (dir < 0) {
                return null;
            }
            final List<int[]> names = entries(dir);

            return names.isEmpty() ? null : leaf(names.get(0)[1]);
        }

        @Nullable ByteBuffer byId(final int type, final int id) throws IOException {
            final int dir = find(0, type, false);
            final int name = dir < 0 ? -1 : find(dir, id, true);

            return name < 0 ? null : leaf(name);
        }

        /** Offset of the subdirectory with the numeric id, or -1. */
        private int find(final int dir, final int id, final boolean any) throws IOException {
            for (final int[] e : entries(dir)) {
                if (e[0] == id && (any || e[2] == 1)) {
                    return e[1];
                }
            }

            return -1;
        }

        /** Entries {id (-1 for named), offset with the subdirectory flag cleared, 1 if a subdirectory}. */
        private @NotNull List<int[]> entries(final int dir) throws IOException {
            final ByteBuffer head = at(ch, base + dir, 16);
            final int named = u16(head, 12);
            final int count = Math.min(named + u16(head, 14), MAX_ENTRIES);
            final ByteBuffer list = at(ch, base + dir + 16, 8 * count);
            final List<int[]> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int name = list.getInt(i * 8);
                final int off = list.getInt(i * 8 + 4);
                result.add(new int[] {name < 0 ? -1 : name, off & 0x7FFFFFFF, off < 0 ? 1 : 0});
            }

            return result;
        }

        /** Descends by the first entries (language) to the data entry and reads the data. */
        private @Nullable ByteBuffer leaf(final int dirOrData) throws IOException {
            int off = dirOrData;
            for (int depth = 0; depth < 3; depth++) {
                final List<int[]> list = entries(off);
                if (list.isEmpty()) {
                    return null;
                }
                off = list.get(0)[1];
                if (list.get(0)[2] == 0) {
                    final ByteBuffer data = at(ch, base + off, 16);
                    final long pos = offset(data.getInt(0));

                    return pos < 0 ? null : at(ch, pos, data.getInt(4));
                }
            }

            return null;
        }

        private long offset(final int rva) {
            final long r = rva & 0xFFFFFFFFL;
            for (final int[] s : sections) {
                final long va = s[0] & 0xFFFFFFFFL;
                if (r >= va && r < va + (s[1] & 0xFFFFFFFFL)) {
                    return (s[2] & 0xFFFFFFFFL) + r - va;
                }
            }

            return -1;
        }
    }
}
