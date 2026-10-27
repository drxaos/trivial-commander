package tcm.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * A virtual FS file or directory: either a local path or an entry inside an archive ("archive.zip!/dir/file").
 */
@Getter
public final class VFile {

    public static final String ARCHIVE_SEPARATOR = "!/";

    /** Local path; null for archive entries. */
    private final @Nullable Path path;
    /** Archive file; null for local files. */
    private final @Nullable Path archive;
    /** Entry path inside the archive without a trailing "/", "" — the archive root. */
    private final @NotNull String entry;
    private final @NotNull String name;
    private final boolean directory;
    private final long size;
    private final long modified;
    private final @NotNull String attrs;
    private final boolean hidden;
    /** Special ".." entry: points to the parent directory. */
    private final boolean parentLink;

    private VFile(
        final @Nullable Path path,
        final @Nullable Path archive,
        final @NotNull String entry,
        final @NotNull String name,
        final boolean directory,
        final long size,
        final long modified,
        final @NotNull String attrs,
        final boolean hidden,
        final boolean parentLink
    ) {
        this.path = path;
        this.archive = archive;
        this.entry = entry;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.modified = modified;
        this.attrs = attrs;
        this.hidden = hidden;
        this.parentLink = parentLink;
    }

    public static @NotNull VFile local(
        final @NotNull Path path,
        final boolean directory,
        final long size,
        final long modified,
        final @NotNull String attrs,
        final boolean hidden
    ) {
        final Path fileName = path.getFileName();
        final String name = fileName == null ? path.toString() : fileName.toString();

        return new VFile(path, null, "", name, directory, size, modified, attrs, hidden, false);
    }

    public static @NotNull VFile archiveEntry(
        final @NotNull Path archive,
        final @NotNull String entry,
        final boolean directory,
        final long size,
        final long modified
    ) {
        final String name = entry.isEmpty() ? String.valueOf(archive.getFileName()) : entry.substring(entry.lastIndexOf('/') + 1);

        return new VFile(null, archive, entry, name, directory, size, modified, "", false, false);
    }

    public static @NotNull VFile parentLink(final @NotNull VFile target) {
        return new VFile(target.path, target.archive, target.entry, "..", true, 0, target.modified, "", false, true);
    }

    public boolean isLocal() {
        return archive == null;
    }

    public boolean isInArchive() {
        return archive != null;
    }

    public boolean isArchiveRoot() {
        return archive != null && entry.isEmpty();
    }

    /** Local path or path to the archive file — for operations that need a real file. */
    public @NotNull Path getHostPath() {
        return Objects.requireNonNull(path != null ? path : archive);
    }

    public @NotNull String getLocation() {
        if (archive == null) {
            return String.valueOf(path);
        }

        return archive + ARCHIVE_SEPARATOR + entry;
    }

    /** Name without extension (as in the Name column in TC). */
    public @NotNull String getBaseName() {
        if (directory || parentLink) {
            return name;
        }
        final int dot = name.lastIndexOf('.');

        return dot <= 0 ? name : name.substring(0, dot);
    }

    public @NotNull String getExtension() {
        if (directory || parentLink) {
            return "";
        }
        final int dot = name.lastIndexOf('.');

        return dot <= 0 ? "" : name.substring(dot + 1);
    }

    public boolean isArchiveFile() {
        if (directory) {
            return false;
        }
        return ArchiveFormat.of(name) != null;
    }

    public boolean isImage() {
        if (directory) {
            return false;
        }
        final String ext = getExtension().toLowerCase(Locale.ROOT);

        return Vfs.IMAGE_EXTENSIONS.contains(ext);
    }

    public @NotNull String getKey() {
        return getLocation();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VFile)) {
            return false;
        }
        final VFile other = (VFile) o;

        return parentLink == other.parentLink && Objects.equals(path, other.path) && Objects.equals(archive, other.archive)
            && entry.equals(other.entry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, archive, entry, parentLink);
    }

    @Override
    public String toString() {
        return getLocation();
    }
}
