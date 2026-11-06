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
 * Supported archive formats. Only ZIP/JAR can be modified in place; the rest are opened for viewing and unpacking
 * (TAR and compressed TAR can also be created anew, see TarWriter).
 */
public enum ArchiveFormat {

    ZIP(true, ".zip", ".jar"),
    TAR(false, ".tar"),
    TAR_GZ(false, ".tar.gz", ".tgz"),
    TAR_BZ2(false, ".tar.bz2", ".tbz2", ".tbz"),
    TAR_XZ(false, ".tar.xz", ".txz"),
    RAR(false, ".rar"),
    SEVEN_ZIP(false, ".7z"),
    ISO(false, ".iso"),
    /** A squashfs image; an AppImage is one appended to an ELF runtime. */
    SQUASHFS(false, ".appimage", ".squashfs", ".sfs", ".snap"),
    /** A Chromium/Electron resource pack (GRIT). */
    PAK(false, ".pak"),
    /** An Apple disk image (UDIF). */
    DMG(false, ".dmg"),
    /** A Debian package: an ar archive with control.tar and data.tar. */
    DEB(false, ".deb", ".udeb"),
    /** An RPM package: headers and a compressed cpio payload. */
    RPM(false, ".rpm"),
    /** Compiled HTML Help. */
    CHM(false, ".chm"),
    /** A Windows Installer package or merge module (and, by signature, any OLE2 compound file). */
    MSI(false, ".msi", ".msm"),
    /** A Microsoft cabinet. */
    CAB(false, ".cab"),
    /** Single compressed files, not TAR (after the TAR formats: ".tar.gz" etc. are matched first). */
    GZ(false, ".gz"),
    BZ2(false, ".bz2"),
    XZ(false, ".xz");

    private static final int TAR_MAGIC_OFFSET = 257;
    private static final int PROBE_SIZE = 512;
    private static final int ISO_MAGIC_OFFSET = 0x8001;
    private static final int ELF_ABI_OFFSET = 8;

    @Getter
    private final boolean writable;
    /** Lower-case extensions with the dot. */
    @Getter
    private final List<String> suffixes;

    ArchiveFormat(final boolean writable, final @NotNull String... suffixes) {
        this.writable = writable;
        this.suffixes = List.of(suffixes);
    }

    public boolean isTar() {
        return this == TAR || this == TAR_GZ || this == TAR_BZ2 || this == TAR_XZ;
    }

    /** A program that is also an archive (AppImage): Enter runs it, Ctrl+PgDn opens it as an archive. */
    public static boolean isLaunchable(final @NotNull String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".appimage");
    }

    /**
     * A document or installer that is also an archive (CHM help, MSI package): Enter opens it with the associated program,
     * if there is one, Ctrl+PgDn opens it as an archive.
     */
    public static boolean opensWithSystem(final @NotNull String fileName) {
        final ArchiveFormat format = of(fileName);

        return format == CHM || format == MSI;
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
            return CompressedFiles.containsTar(file, TAR_GZ) ? TAR_GZ : GZ;
        }
        if (startsWith(head, 'B', 'Z', 'h')) {
            return CompressedFiles.containsTar(file, TAR_BZ2) ? TAR_BZ2 : BZ2;
        }
        if (startsWith(head, 0xFD, '7', 'z', 'X', 'Z', 0)) {
            return CompressedFiles.containsTar(file, TAR_XZ) ? TAR_XZ : XZ;
        }
        if (startsWith(head, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C)) {
            return SEVEN_ZIP;
        }
        if (DebArchives.matches(head)) {
            return DEB;
        }
        if (RpmArchives.matches(head)) {
            return RPM;
        }
        if (ChmArchives.matches(head)) {
            return CHM;
        }
        if (CompoundFile.matches(head)) {
            return MSI;
        }
        if (CabFile.matches(head)) {
            return CAB;
        }
        if (startsWith(head, 'h', 's', 'q', 's')) {
            return SQUASHFS;
        }
        // AppImage type 2: the ELF header carries "AI" 2 in its padding
        if (startsWith(head, 0x7F, 'E', 'L', 'F') && head.length > ELF_ABI_OFFSET + 2 && head[ELF_ABI_OFFSET] == 'A'
            && head[ELF_ABI_OFFSET + 1] == 'I' && head[ELF_ABI_OFFSET + 2] == 2) {
            return SQUASHFS;
        }
        if (head.length >= TAR_MAGIC_OFFSET + 5
            && Arrays.equals(head, TAR_MAGIC_OFFSET, TAR_MAGIC_OFFSET + 5, new byte[] {'u', 's', 't', 'a', 'r'}, 0, 5)) {
            return TAR;
        }

        if (isIso(file)) {
            return ISO;
        }

        if (DmgArchives.matches(file)) {
            return DMG;
        }

        return PakArchives.matches(file) ? PAK : null;
    }

    /** ISO 9660: the volume descriptors start at sector 16 with the identifier "CD001". */
    private static boolean isIso(final @NotNull Path file) throws IOException {
        if (Files.size(file) < ISO_MAGIC_OFFSET + 5) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(ISO_MAGIC_OFFSET);

            return Arrays.equals(in.readNBytes(5), new byte[] {'C', 'D', '0', '0', '1'});
        }
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
