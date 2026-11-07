package tcm.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ImageIO.read with a fallback for JPEGs with a broken embedded color profile: the JDK reader rejects the whole image
 * ("Invalid icc profile: invalid number of icc markers" — e.g. a profile announced in 2 APP2 chunks with only one present).
 * Such a JPEG is decoded again without its ICC_PROFILE segments, as other viewers show it.
 */
public final class RasterImages {

    private static final byte[] ICC_ID = "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final int APP2 = 0xE2;
    private static final int SOS = 0xDA;

    private RasterImages() {
    }

    /** The decoded image or null — no ImageIO reader for the data. */
    public static @Nullable BufferedImage read(final byte @NotNull [] data) throws IOException {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (final IIOException e) {
            final byte[] stripped = isJpeg(data) ? withoutIcc(data) : null;
            if (stripped == null) {
                throw e;
            }

            return ImageIO.read(new ByteArrayInputStream(stripped));
        }
    }

    private static boolean isJpeg(final byte @NotNull [] d) {
        return d.length > 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8;
    }

    /** The JPEG without APP2 ICC_PROFILE segments; null — it has none (or the header segments are damaged). */
    static byte @Nullable [] withoutIcc(final byte @NotNull [] d) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 2);
        int pos = 2;
        boolean removed = false;
        while (pos + 4 <= d.length) {
            if ((d[pos] & 0xFF) != 0xFF) {
                return null;
            }
            final int marker = d[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                // fill byte before a marker
                out.write(0xFF);
                pos++;
                continue;
            }
            if (marker == SOS) {
                break;
            }
            final int length = (d[pos + 2] & 0xFF) << 8 | d[pos + 3] & 0xFF;
            if (length < 2 || pos + 2 + length > d.length) {
                return null;
            }
            if (marker == APP2 && isIcc(d, pos + 4, length - 2)) {
                removed = true;
            } else {
                out.write(d, pos, 2 + length);
            }
            pos += 2 + length;
        }
        if (!removed) {
            return null;
        }
        out.write(d, pos, d.length - pos);

        return out.toByteArray();
    }

    private static boolean isIcc(final byte @NotNull [] d, final int offset, final int length) {
        if (length < ICC_ID.length) {
            return false;
        }
        for (int i = 0; i < ICC_ID.length; i++) {
            if (d[offset + i] != ICC_ID[i]) {
                return false;
            }
        }

        return true;
    }
}
