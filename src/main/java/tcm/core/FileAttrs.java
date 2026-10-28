package tcm.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

/**
 * Attr column text and its application to unpacked files. Two forms: Unix permissions "rwxr-xr-x"
 * and DOS flags "rahs" (read-only, archive, hidden, system; "-" — the flag is not set). Empty — attributes are unknown.
 */
public final class FileAttrs {

    private static final int POSIX_LENGTH = 9;
    private static final int DOS_LENGTH = 4;
    private static final int PERMISSION_BITS = 0777;
    private static final int DOS_READ_ONLY = 0x01;
    private static final int DOS_HIDDEN = 0x02;
    private static final int DOS_SYSTEM = 0x04;
    private static final int DOS_ARCHIVE = 0x20;

    private FileAttrs() {
    }

    /** Unix mode (type bits are ignored); 0 — the archiver did not store permissions. */
    static @NotNull String posix(final int mode) {
        final int bits = mode & PERMISSION_BITS;
        if (bits == 0) {
            return "";
        }
        final char[] s = "rwxrwxrwx".toCharArray();
        for (int i = 0; i < POSIX_LENGTH; i++) {
            if ((bits & 1 << POSIX_LENGTH - 1 - i) == 0) {
                s[i] = '-';
            }
        }

        return new String(s);
    }

    /** DOS/Windows attribute byte. */
    static @NotNull String dos(final int attrs) {
        return dos((attrs & DOS_READ_ONLY) != 0, (attrs & DOS_ARCHIVE) != 0, (attrs & DOS_HIDDEN) != 0, (attrs & DOS_SYSTEM) != 0);
    }

    static @NotNull String dos(final boolean readOnly, final boolean archive, final boolean hidden, final boolean system) {
        return (readOnly ? "r" : "-") + (archive ? "a" : "-") + (hidden ? "h" : "-") + (system ? "s" : "-");
    }

    /**
     * Sets attributes from an archive on an unpacked file. Unix permissions on Windows and DOS flags on Unix are carried over
     * as far as they map: only "read-only". Errors are ignored — the FS may not support the attributes (FAT, network shares).
     */
    public static void apply(final @NotNull Path file, final @NotNull String attrs) {
        try {
            final PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            final DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.length() == POSIX_LENGTH) {
                final Set<PosixFilePermission> perms = PosixFilePermissions.fromString(attrs);
                if (posix != null) {
                    posix.setPermissions(perms);
                } else if (dos != null) {
                    dos.setReadOnly(!perms.contains(PosixFilePermission.OWNER_WRITE));
                }
            } else if (attrs.length() == DOS_LENGTH) {
                final boolean readOnly = attrs.charAt(0) == 'r';
                if (posix != null) {
                    if (readOnly) {
                        final Set<PosixFilePermission> perms = posix.readAttributes().permissions();
                        perms.remove(PosixFilePermission.OWNER_WRITE);
                        perms.remove(PosixFilePermission.GROUP_WRITE);
                        perms.remove(PosixFilePermission.OTHERS_WRITE);
                        posix.setPermissions(perms);
                    }
                } else if (dos != null) {
                    dos.setReadOnly(readOnly);
                    dos.setArchive(attrs.charAt(1) == 'a');
                    dos.setHidden(attrs.charAt(2) == 'h');
                    dos.setSystem(attrs.charAt(3) == 's');
                }
            }
        } catch (final IOException | RuntimeException e) {
            // the target FS does not keep such attributes — the file itself is unpacked
        }
    }
}
