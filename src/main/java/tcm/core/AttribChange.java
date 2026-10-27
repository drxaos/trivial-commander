package tcm.core;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Change of file attributes: bits (POSIX permissions, or DOS attributes where there is no POSIX) and modification time.
 * A bit absent from the change and a null time stay as they were.
 */
@Getter
public final class AttribChange {

    /** POSIX permission bits and DOS attributes; which of them exist depends on the file system. */
    public enum Bit {
        OWNER_READ, OWNER_WRITE, OWNER_EXECUTE,
        GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
        OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE,
        READ_ONLY("dos:readonly"), HIDDEN("dos:hidden"), ARCHIVE("dos:archive"), SYSTEM("dos:system");

        private final @Nullable String dosAttribute;

        Bit() {
            this(null);
        }

        Bit(final @Nullable String dosAttribute) {
            this.dosAttribute = dosAttribute;
        }

        public boolean isPosix() {
            return dosAttribute == null;
        }
    }

    private final @NotNull Map<Bit, Boolean> bits;
    private final @Nullable FileTime modified;
    private final boolean recursive;

    public AttribChange(final @NotNull Map<Bit, Boolean> bits, final @Nullable FileTime modified, final boolean recursive) {
        this.bits = bits.isEmpty() ? new EnumMap<>(Bit.class) : new EnumMap<>(bits);
        this.modified = modified;
        this.recursive = recursive;
    }

    public boolean isEmpty() {
        return bits.isEmpty() && modified == null;
    }

    /** POSIX permissions (Linux, macOS); otherwise DOS attributes (Windows). */
    public static boolean isPosixSystem() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Current value of the bit for the file. */
    public static boolean read(final @NotNull Path path, final @NotNull Bit bit) throws IOException {
        if (bit.isPosix()) {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).contains(PosixFilePermission.valueOf(bit.name()));
        }

        return Boolean.TRUE.equals(Files.getAttribute(path, bit.dosAttribute, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * Applies the change to a single file. onlyAdditions — only permissions being granted: a directory must become
     * readable before traversal, and permissions can be taken away from it only after its contents are processed.
     */
    public void apply(final @NotNull Path path, final boolean onlyAdditions) throws IOException {
        applyPosix(path, onlyAdditions);
        if (onlyAdditions) {
            return;
        }
        for (final Map.Entry<Bit, Boolean> e : bits.entrySet()) {
            if (!e.getKey().isPosix()) {
                Files.setAttribute(path, e.getKey().dosAttribute, e.getValue(), LinkOption.NOFOLLOW_LINKS);
            }
        }
        if (modified != null) {
            Files.setLastModifiedTime(path, modified);
        }
    }

    private void applyPosix(final @NotNull Path path, final boolean onlyAdditions) throws IOException {
        if (bits.keySet().stream().noneMatch(Bit::isPosix)) {
            return;
        }
        final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        boolean changed = false;
        for (final Map.Entry<Bit, Boolean> e : bits.entrySet()) {
            if (!e.getKey().isPosix() || onlyAdditions && !e.getValue()) {
                continue;
            }
            final PosixFilePermission p = PosixFilePermission.valueOf(e.getKey().name());
            changed |= e.getValue() ? perms.add(p) : perms.remove(p);
        }
        if (changed) {
            Files.setPosixFilePermissions(path, perms);
        }
    }
}
