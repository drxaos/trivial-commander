package tcm.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Icon files: Windows .ico/.cur (PNG or DIB images) and macOS .icns (PNG, ARGB and the old RLE RGB + 8-bit mask). An icon holds
 * several sizes; the one closest to the requested size is decoded. JPEG 2000 elements of .icns are skipped — ImageIO has no reader.
 * Every image is returned as TYPE_INT_ARGB.
 */
public final class IconImages {

    /** Request for the largest image of the icon (the viewer). */
    public static final int LARGEST = Integer.MAX_VALUE;

    static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIM = 1024;
    private static final int MAX_ICO_ENTRIES = 256;
    private static final int ICNS_HEADER = 8;

    /** .icns element types that hold a PNG (or JPEG 2000) image, by their pixel size. */
    private static final Map<String, Integer> ICNS_PNG = Map.ofEntries(
        Map.entry("icp4", 16), Map.entry("icp5", 32), Map.entry("icp6", 64), Map.entry("ic07", 128), Map.entry("ic08", 256),
        Map.entry("ic09", 512), Map.entry("ic10", 1024), Map.entry("ic11", 32), Map.entry("ic12", 64), Map.entry("ic13", 256),
        Map.entry("ic14", 512), Map.entry("ic04", 16), Map.entry("ic05", 32)
    );
    /** RLE-packed RGB elements and their 8-bit alpha masks. */
    private static final Map<String, Integer> ICNS_RGB = Map.of("is32", 16, "il32", 32, "ih32", 48, "it32", 128);
    private static final Map<String, String> ICNS_MASK = Map.of("is32", "s8mk", "il32", "l8mk", "ih32", "h8mk", "it32", "t8mk");

    private IconImages() {
    }

    /** An .ico/.cur or .icns file by its first bytes. */
    public static boolean isIcon(final byte @NotNull [] data) {
        return isIco(data) || isIcns(data);
    }

    /** The image closest to size (LARGEST — the biggest one), or null if the data is not an icon or holds nothing decodable. */
    public static @Nullable BufferedImage decode(final byte @NotNull [] data, final int size) throws IOException {
        if (isIco(data)) {
            return decodeIco(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN), size);
        }
        if (isIcns(data)) {
            return decodeIcns(ByteBuffer.wrap(data), size);
        }

        return null;
    }

    private static boolean isIco(final byte @NotNull [] d) {
        // reserved 0, type 1 (icon) or 2 (cursor), a non-zero count
        return d.length >= 22 && d[0] == 0 && d[1] == 0 && (d[2] == 1 || d[2] == 2) && d[3] == 0 && (d[4] != 0 || d[5] != 0);
    }

    private static boolean isIcns(final byte @NotNull [] d) {
        return d.length >= ICNS_HEADER && d[0] == 'i' && d[1] == 'c' && d[2] == 'n' && d[3] == 's';
    }

    private static @Nullable BufferedImage decodeIco(final @NotNull ByteBuffer b, final int size) throws IOException {
        final int count = u16(b, 4);
        if (count > MAX_ICO_ENTRIES || 6 + 16L * count > b.limit()) {
            return null;
        }
        final List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int o = 6 + i * 16;
            entries.add(new Entry(u8(b, o), u8(b, o + 2), u16(b, o + 6), b.getInt(o + 8), b.getInt(o + 12)));
        }
        final Entry best = choose(entries, size);
        if (best == null) {
            return null;
        }
        final long offset = best.ref & 0xFFFFFFFFL;
        if (offset + best.bytes > b.limit()) {
            return null;
        }
        final byte[] image = new byte[best.bytes];
        b.get((int) offset, image);

        return decodeImage(image);
    }

    /** The deepest color first, then the size: the smallest not below the target, otherwise the largest. */
    static @Nullable Entry choose(final @NotNull List<Entry> entries, final int size) {
        return entries.stream()
            .filter(e -> e.bytes > 0 && e.bytes <= MAX_IMAGE_BYTES)
            .min(Comparator.<Entry>comparingInt(e -> -Math.min(e.bits(), 32))
                .thenComparingInt(e -> e.width() >= size ? e.width() - size : 10_000 - e.width()))
            .orElse(null);
    }

    /** One image of an icon: PNG or a DIB with an AND mask. */
    static @Nullable BufferedImage decodeImage(final byte @NotNull [] data) throws IOException {
        if (isPng(data)) {
            return argb(ImageIO.read(new ByteArrayInputStream(data)));
        }

        return Dib.decode(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
    }

    private static @Nullable BufferedImage decodeIcns(final @NotNull ByteBuffer b, final int size) throws IOException {
        final List<IcnsElement> candidates = new ArrayList<>();
        final Map<String, IcnsElement> masks = new HashMap<>();
        final int end = (int) Math.min(b.limit(), Integer.toUnsignedLong(b.getInt(4)));
        int pos = ICNS_HEADER;
        while (pos + 8 <= end) {
            final String type = new String(new byte[] {b.get(pos), b.get(pos + 1), b.get(pos + 2), b.get(pos + 3)}, StandardCharsets.ISO_8859_1);
            final int length = b.getInt(pos + 4);
            if (length < 8 || length > end - pos) {
                break;
            }
            final IcnsElement e = new IcnsElement(type, pos + 8, length - 8);
            final Integer pixels = ICNS_PNG.containsKey(type) ? ICNS_PNG.get(type) : ICNS_RGB.get(type);
            if (pixels != null) {
                e.size = pixels;
                candidates.add(e);
            } else if (ICNS_MASK.containsValue(type)) {
                masks.put(type, e);
            }
            pos += length;
        }
        // the closest size first; a failed decode (JPEG 2000, damaged data) falls back to the next one
        candidates.sort(Comparator.<IcnsElement>comparingInt(e -> e.size >= size ? e.size - size : 10_000 - e.size)
            .thenComparingInt(e -> ICNS_RGB.containsKey(e.type) ? 1 : 0));
        for (final IcnsElement e : candidates) {
            final BufferedImage img = decodeIcnsElement(b, e, masks);
            if (img != null) {
                return img;
            }
        }

        return null;
    }

    private static @Nullable BufferedImage decodeIcnsElement(
        final @NotNull ByteBuffer b,
        final @NotNull IcnsElement e,
        final @NotNull Map<String, IcnsElement> masks
    ) {
        if (e.length <= 0 || e.length > MAX_IMAGE_BYTES) {
            return null;
        }
        final byte[] data = new byte[e.length];
        b.get(e.offset, data);
        try {
            if (isPng(data)) {
                return argb(ImageIO.read(new ByteArrayInputStream(data)));
            }
            if (data.length > 4 && data[0] == 'A' && data[1] == 'R' && data[2] == 'G' && data[3] == 'B') {
                return planes(data, 4, e.size, 4, null);
            }
            if (ICNS_RGB.containsKey(e.type)) {
                final IcnsElement maskElement = masks.get(ICNS_MASK.get(e.type));
                byte[] mask = null;
                if (maskElement != null && maskElement.length == e.size * e.size) {
                    mask = new byte[maskElement.length];
                    b.get(maskElement.offset, mask);
                }
                // it32 data starts with four zero bytes
                final int start = e.type.equals("it32") && data.length > 4 && data[0] == 0 && data[1] == 0 ? 4 : 0;

                return planes(data, start, e.size, 3, mask);
            }
        } catch (final IOException | RuntimeException ex) {
            // damaged element — try another size
            return null;
        }

        return null;
    }

    /**
     * Separate channel planes (A, R, G, B or R, G, B), each RLE-packed one after another, or stored raw when the data has
     * exactly w * h * channels bytes. RLE: a byte below 0x80 copies n + 1 bytes, otherwise repeats the next byte n - 0x80 + 3 times.
     */
    private static @Nullable BufferedImage planes(
        final byte @NotNull [] data,
        final int start,
        final int dim,
        final int channels,
        final byte @Nullable [] mask
    ) {
        final int count = dim * dim;
        final byte[] out = new byte[count * channels];
        if (data.length - start == out.length) {
            System.arraycopy(data, start, out, 0, out.length);
        } else {
            int in = start;
            int o = 0;
            while (o < out.length && in < data.length) {
                final int n = data[in++] & 0xFF;
                if (n < 0x80) {
                    final int len = Math.min(n + 1, Math.min(out.length - o, data.length - in));
                    System.arraycopy(data, in, out, o, len);
                    in += len;
                    o += len;
                } else if (in < data.length) {
                    final int len = Math.min(n - 0x80 + 3, out.length - o);
                    Arrays.fill(out, o, o + len, data[in++]);
                    o += len;
                }
            }
            if (o < out.length) {
                return null;
            }
        }
        final int[] pixels = new int[count];
        final int base = channels == 4 ? count : 0;
        for (int i = 0; i < count; i++) {
            final int a = channels == 4 ? out[i] & 0xFF : mask != null ? mask[i] & 0xFF : 0xFF;
            pixels[i] = a << 24 | (out[base + i] & 0xFF) << 16 | (out[base + count + i] & 0xFF) << 8 | out[base + 2 * count + i] & 0xFF;
        }
        final BufferedImage img = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, dim, dim, pixels, 0, dim);

        return img;
    }

    /**
     * Copies the pixels into TYPE_INT_ARGB with getRGB: the native image lacks java2d loops for the custom types ImageIO
     * returns for PNG, so drawing such an image would throw.
     */
    private static @Nullable BufferedImage argb(final @Nullable BufferedImage img) {
        if (img == null || img.getType() == BufferedImage.TYPE_INT_ARGB) {
            return img;
        }
        final int w = img.getWidth();
        final int h = img.getHeight();
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }

        return out;
    }

    private static boolean isPng(final byte @NotNull [] data) {
        return data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    static int u8(final @NotNull ByteBuffer b, final int pos) {
        return b.get(pos) & 0xFF;
    }

    static int u16(final @NotNull ByteBuffer b, final int pos) {
        return b.getShort(pos) & 0xFFFF;
    }

    /** A directory entry: width 0 means 256; ref is the RT_ICON id in a PE file or the image offset in an .ico file. */
    static final class Entry {

        private final int width;
        private final int colors;
        private final int bitCount;
        private final int bytes;
        private final int ref;

        Entry(final int width, final int colors, final int bitCount, final int bytes, final int ref) {
            this.width = width;
            this.colors = colors;
            this.bitCount = bitCount;
            this.bytes = bytes;
            this.ref = ref;
        }

        int ref() {
            return ref;
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

    private static final class IcnsElement {

        private final @NotNull String type;
        private final int offset;
        private final int length;
        private int size;

        private IcnsElement(final @NotNull String type, final int offset, final int length) {
            this.type = type;
            this.offset = offset;
            this.length = length;
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
