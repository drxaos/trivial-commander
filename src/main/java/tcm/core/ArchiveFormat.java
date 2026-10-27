package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Supported archive formats. Only ZIP/JAR can be modified in place; the rest are opened for viewing and unpacking.
 */
public enum ArchiveFormat {

    ZIP(true, ".zip", ".jar"),
    TAR(false, ".tar"),
    TAR_GZ(false, ".tar.gz", ".tgz"),
    TAR_BZ2(false, ".tar.bz2", ".tbz2", ".tbz"),
    TAR_XZ(false, ".tar.xz", ".txz"),
    RAR(false, ".rar");

    private static final int TAR_MAGIC_OFFSET = 257;
    private static final int PROBE_SIZE = 512;

    @Getter
    private final boolean writable;
    private final List<String> suffixes;

    ArchiveFormat(final boolean writable, final @NotNull String... suffixes) {
        this.writable = writable;
        this.suffixes = List.of(suffixes);
    }

    /** Format by file name; null — not an archive extension. */
    public static @Nullable ArchiveFormat of(final @NotNull String fileName) {
        final String lower = fileName.toLowerCase(Locale.ROOT);
        for (final ArchiveFormat f : values()) {
            for (final String s : f.suffixes) {
                if (lower.endsWith(s) && lower.length() > s.length()) {
                    return f;
                }
            }
        }

        return null;
    }

    /** Format by signature at the start of the file — for Ctrl+PgDn on a file with a non-standard extension (.docx, .apk, .cbr...). */
    public static @Nullable ArchiveFormat detect(final @NotNull Path file) throws IOException {
        final byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(PROBE_SIZE);
        }
        if (startsWith(head, 'P', 'K', 3, 4) || startsWith(head, 'P', 'K', 5, 6)) {
            return ZIP;
        }
        if (startsWith(head, 'R', 'a', 'r', '!', 0x1A, 0x07)) {
            return RAR;
        }
        if (startsWith(head, 0x1F, 0x8B)) {
            return TAR_GZ;
        }
        if (startsWith(head, 'B', 'Z', 'h')) {
            return TAR_BZ2;
        }
        if (startsWith(head, 0xFD, '7', 'z', 'X', 'Z', 0)) {
            return TAR_XZ;
        }
        if (head.length >= TAR_MAGIC_OFFSET + 5
            && Arrays.equals(head, TAR_MAGIC_OFFSET, TAR_MAGIC_OFFSET + 5, new byte[] {'u', 's', 't', 'a', 'r'}, 0, 5)) {
            return TAR;
        }

        return null;
    }

    /** File format: by extension first, then by signature. */
    public static @Nullable ArchiveFormat forFile(final @NotNull Path file) throws IOException {
        final ArchiveFormat byName = of(String.valueOf(file.getFileName()));

        return byName != null ? byName : detect(file);
    }

    private static boolean startsWith(final byte[] data, final int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }

        return true;
    }
}
