package tcm.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Icons of Windows files read without the Windows shell: the main icon of a PE file (exe, dll — the first RT_GROUP_ICON resource,
 * as Explorer shows it) and the best image of an .ico/.cur file. Images are PNG or DIB (1/4/8/16/24/32 bits with an AND mask).
 */
final class PeIcons {

    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIM = 1024;

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
            if (u16(head, 0) == 0 && (type == 1 || type == 2)) {
                return readIco(ch, size);
            }

            return null;
        }
    }

    private static @Nullable BufferedImage readIco(final @NotNull FileChannel ch, final int size) throws IOException {
        final int count = u16(at(ch, 0, 6), 4);
        if (count == 0 || count > 256) {
            return null;
        }
        final ByteBuffer dir = at(ch, 6, 16 * count);
        final List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int o = i * 16;
            entries.add(new Entry(u8(dir, o), u8(dir, o + 2), u16(dir, o + 6), dir.getInt(o + 8), dir.getInt(o + 12)));
        }
        final Entry best = choose(entries, size);

        return best == null ? null : decode(bytes(at(ch, best.ref & 0xFFFFFFFFL, best.bytes)));
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
        final List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int o = 6 + i * 14;
            entries.add(new Entry(u8(group, o), u8(group, o + 2), u16(group, o + 6), group.getInt(o + 8), u16(group, o + 12)));
        }
        final Entry best = choose(entries, size);
        if (best == null) {
            return null;
        }
        final ByteBuffer image = res.byId(RT_ICON, best.ref);

        return image == null ? null : decode(bytes(image));
    }

    /** The deepest color first, then the size: the smallest not below the target, otherwise the largest. */
    private static @Nullable Entry choose(final @NotNull List<Entry> entries, final int size) {
        return entries.stream()
            .filter(e -> e.bytes > 0 && e.bytes <= MAX_IMAGE_BYTES)
            .min(Comparator.<Entry>comparingInt(e -> -Math.min(e.bits(), 32))
                .thenComparingInt(e -> e.width() >= size ? e.width() - size : 10_000 - e.width()))
            .orElse(null);
    }

    private static @Nullable BufferedImage decode(final byte @NotNull [] data) throws IOException {
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return ImageIO.read(new ByteArrayInputStream(data));
        }

        return Dib.decode(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
    }

    private static @NotNull ByteBuffer at(final @NotNull FileChannel ch, final long pos, final int len) throws IOException {
        if (pos < 0 || len < 0 || len > MAX_IMAGE_BYTES || pos + len > ch.size()) {
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

    /** A directory entry: width 0 means 256; ref is the RT_ICON id in a PE file or the image offset in an .ico file. */
    private static final class Entry {

        private final int width;
        private final int colors;
        private final int bitCount;
        private final int bytes;
        private final int ref;

        private Entry(final int width, final int colors, final int bitCount, final int bytes, final int ref) {
            this.width = width;
            this.colors = colors;
            this.bitCount = bitCount;
            this.bytes = bytes;
            this.ref = ref;
        }

        private int width() {
            return width == 0 ? 256 : width;
        }

        private int bits() {
            if (bitCount != 0) {
                return bitCount;
            }

            return colors == 0 ? 32 : 32 - Integer.numberOfLeadingZeros(colors - 1);
        }
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

    /** A device-independent bitmap of an icon: the XOR image of half the stated height, then the 1-bit AND (transparency) mask. */
    private static final class Dib {

        private Dib() {
        }

        static @Nullable BufferedImage decode(final @NotNull ByteBuffer b) {
            if (b.remaining() < 40) {
                return null;
            }
            final int header = b.getInt(0);
            final int w = b.getInt(4);
            final int stated = b.getInt(8);
            final int h = Math.abs(stated) / 2;
            final int bpp = u16(b, 14);
            final int compression = b.getInt(16);
            if (w <= 0 || w > MAX_DIM || h <= 0 || h > MAX_DIM || header < 40 || compression != 0 && compression != 3) {
                return null;
            }
            final int used = b.getInt(32);
            final int paletteSize = bpp <= 8 ? (used > 0 && used <= 256 ? used : 1 << bpp) : 0;
            // BI_BITFIELDS with the 40-byte header: three color masks follow it; standard masks are assumed
            final int palette = header + (compression == 3 && header == 40 ? 12 : 0);
            final int xor = palette + 4 * paletteSize;
            final int xorStride = (w * bpp + 31) / 32 * 4;
            final int and = xor + xorStride * h;
            final int andStride = (w + 31) / 32 * 4;
            if (bpp != 1 && bpp != 4 && bpp != 8 && bpp != 16 && bpp != 24 && bpp != 32 || (long) and > b.remaining()) {
                return null;
            }
            final int[] pixels = pixels(b, w, h, bpp, palette, xor, xorStride);
            applyMask(b, pixels, w, h, bpp, and, andStride);
            final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            // rows are stored bottom up unless the height is negative
            for (int y = 0; y < h; y++) {
                img.setRGB(0, stated > 0 ? h - 1 - y : y, w, 1, pixels, y * w, w);
            }

            return img;
        }

        private static int @NotNull [] pixels(
            final @NotNull ByteBuffer b,
            final int w,
            final int h,
            final int bpp,
            final int palette,
            final int xor,
            final int stride
        ) {
            final int[] result = new int[w * h];
            for (int y = 0; y < h; y++) {
                final int row = xor + y * stride;
                for (int x = 0; x < w; x++) {
                    result[y * w + x] = pixel(b, row, x, bpp, palette);
                }
            }

            return result;
        }

        private static int pixel(final @NotNull ByteBuffer b, final int row, final int x, final int bpp, final int palette) {
            switch (bpp) {
                case 32:
                    return b.getInt(row + x * 4);
                case 24:
                    return 0xFF000000 | u8(b, row + x * 3 + 2) << 16 | u8(b, row + x * 3 + 1) << 8 | u8(b, row + x * 3);
                case 16: {
                    final int v = u16(b, row + x * 2);

                    return 0xFF000000 | (v >> 10 & 0x1F) * 255 / 31 << 16 | (v >> 5 & 0x1F) * 255 / 31 << 8 | (v & 0x1F) * 255 / 31;
                }
                default: {
                    final int bit = x * bpp;
                    final int index = u8(b, row + bit / 8) >> 8 - bpp - bit % 8 & (1 << bpp) - 1;
                    final int pos = palette + index * 4;

                    return pos + 4 <= b.limit() ? 0xFF000000 | b.getInt(pos) & 0xFFFFFF : 0;
                }
            }
        }

        /** 32-bit images carry alpha; older ones (and 32-bit ones with zero alpha everywhere) take it from the AND mask. */
        private static void applyMask(
            final @NotNull ByteBuffer b,
            final int @NotNull [] pixels,
            final int w,
            final int h,
            final int bpp,
            final int and,
            final int stride
        ) {
            if (bpp == 32) {
                for (final int p : pixels) {
                    if (p >>> 24 != 0) {
                        return;
                    }
                }
            }
            final boolean hasMask = (long) and + (long) stride * h <= b.remaining();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    final boolean transparent = hasMask && (u8(b, and + y * stride + x / 8) >> 7 - x % 8 & 1) != 0;
                    final int i = y * w + x;
                    pixels[i] = transparent ? 0 : 0xFF000000 | pixels[i] & 0xFFFFFF;
                }
            }
        }
    }
}
