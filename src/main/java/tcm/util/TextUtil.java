package tcm.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Text encoding detection and number/date formatting for panels.
 */
public final class TextUtil {

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF16_BE = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] BOM_UTF16_LE = {(byte) 0xFF, (byte) 0xFE};
    private static final String DATE_PATTERN = "dd.MM.yyyy HH:mm";

    private TextUtil() {
    }

    /** Decoded text + encoding and BOM, so the editor saves the file in the same form. */
    @Getter
    public static final class Decoded {

        private final @NotNull String text;
        private final @NotNull Charset charset;
        private final byte @Nullable [] bom;

        public Decoded(final @NotNull String text, final @NotNull Charset charset, final byte @Nullable [] bom) {
            this.text = text;
            this.charset = charset;
            this.bom = bom;
        }
    }

    public static @NotNull Decoded decode(final byte @NotNull [] data) {
        if (startsWith(data, BOM_UTF8)) {
            return new Decoded(new String(data, 3, data.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8, BOM_UTF8);
        }
        if (startsWith(data, BOM_UTF16_BE)) {
            return new Decoded(new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE, BOM_UTF16_BE);
        }
        if (startsWith(data, BOM_UTF16_LE)) {
            return new Decoded(new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, BOM_UTF16_LE);
        }
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return new Decoded(decoder.decode(ByteBuffer.wrap(data)).toString(), StandardCharsets.UTF_8, null);
        } catch (final CharacterCodingException e) {
            // not UTF-8: ISO-8859-1 maps any byte, nothing is lost on save
            return new Decoded(new String(data, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1, null);
        }
    }

    public static byte @NotNull [] encode(final @NotNull String text, final @NotNull Charset charset, final byte @Nullable [] bom) {
        final byte[] body = text.getBytes(charset);
        if (bom == null) {
            return body;
        }
        final byte[] result = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(body, 0, result, bom.length, body.length);

        return result;
    }

    /** "This is a binary file" heuristic: zero bytes at the beginning. */
    public static boolean looksBinary(final byte @NotNull [] data) {
        if (startsWith(data, BOM_UTF16_BE) || startsWith(data, BOM_UTF16_LE)) {
            return false;
        }
        final int limit = Math.min(data.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }

        return false;
    }

    public static @NotNull String groupDigits(final long value) {
        return String.format("%,d", value);
    }

    public static @NotNull String formatDate(final long millis) {
        return new SimpleDateFormat(DATE_PATTERN).format(new Date(millis));
    }

    /** Short size for the status line: 12 k, 3.4 M. */
    public static @NotNull String shortSize(final long bytes) {
        if (bytes < 1024 * 1024) {
            return groupDigits((bytes + 1023) / 1024) + " k";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f M", bytes / 1024.0 / 1024.0);
        }

        return String.format("%.2f G", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private static boolean startsWith(final byte @NotNull [] data, final byte @NotNull [] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }
}
