package tcm.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Writing to ZIP/JAR. java.util.zip cannot modify an archive in place, so any change is a rewrite
 * into a temporary file alongside and an atomic swap: on error or cancel the original archive stays intact.
 */
public final class ZipOps {

    private ZipOps() {
    }

    public static void apply(final @NotNull Path archive, final @NotNull ZipEdit edit, final @NotNull Progress progress) throws IOException {
        if (edit.isEmpty()) {
            return;
        }
        final Path target = archive.toAbsolutePath().normalize();
        Vfs.checkWritable(target);
        final Path tmp = Files.createTempFile(target.getParent(), ".tcm-", ".tmp");
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                final Set<String> added = new HashSet<>();
                for (final ZipAddition a : edit.getAdditions()) {
                    added.add(a.getEntry());
                }
                final Set<String> written = new HashSet<>();
                if (Files.exists(target) && Files.size(target) > 0) {
                    copyExisting(target, edit, added, written, out, progress);
                }
                for (final ZipAddition a : edit.getAdditions()) {
                    writeAddition(a, written, out, progress);
                }
            }
            replace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
            Vfs.invalidate(target);
        }
    }

    public static void add(
        final @NotNull Path archive,
        final @NotNull String dirEntry,
        final @NotNull List<Path> files,
        final @NotNull Progress progress
    ) throws IOException {
        final ZipEdit edit = new ZipEdit();
        for (final Path file : files) {
            addTree(edit, file, ArchiveIndex.join(ArchiveIndex.normalize(dirEntry), String.valueOf(file.getFileName())));
        }
        apply(archive, edit, progress);
    }

    public static void addTree(final @NotNull ZipEdit edit, final @NotNull Path file, final @NotNull String entry) throws IOException {
        final long modified = Files.getLastModifiedTime(file).toMillis();
        if (Files.isDirectory(file)) {
            edit.add(ZipAddition.dir(entry, modified));
            try (Stream<Path> stream = Files.list(file)) {
                for (final Path child : stream.toList()) {
                    addTree(edit, child, entry + "/" + child.getFileName());
                }
            }

            return;
        }
        edit.add(ZipAddition.file(entry, Files.size(file), modified, () -> Files.newInputStream(file)));
    }

    public static void remove(final @NotNull Path archive, final @NotNull List<String> entries) throws IOException {
        final ZipEdit edit = new ZipEdit();
        for (final String e : entries) {
            edit.remove(e);
        }
        apply(archive, edit, Progress.NONE);
    }

    public static void rename(final @NotNull Path archive, final @NotNull String from, final @NotNull String to) throws IOException {
        apply(archive, new ZipEdit().rename(from, to), Progress.NONE);
    }

    public static void mkdir(final @NotNull Path archive, final @NotNull String entry) throws IOException {
        apply(archive, new ZipEdit().add(ZipAddition.dir(entry, System.currentTimeMillis())), Progress.NONE);
    }

    /** Unpacks an entry (a file or a whole directory) into a local directory; the archive may be in any supported format. */
    public static void extract(
        final @NotNull Path archive,
        final @NotNull String entry,
        final @NotNull Path targetDir,
        final @NotNull Progress progress
    ) throws IOException {
        final String root = ArchiveIndex.normalize(entry);
        final String base = ArchiveIndex.parentOf(root);
        final Map<String, VFile> subtree = Vfs.index(archive).subtree(root);
        try (ArchiveReader reader = Vfs.openReader(archive)) {
            for (final VFile f : subtree.values()) {
                if (f.getEntry().isEmpty()) {
                    continue;
                }
                final String relative = base.isEmpty() ? f.getEntry() : f.getEntry().substring(base.length() + 1);
                final Path out = safeResolve(targetDir, relative);
                if (f.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                progress.file(f.getEntry(), f.getSize());
                try (InputStream in = reader.open(f.getEntry());
                     OutputStream os = Files.newOutputStream(out)) {
                    Streams.copy(in, os, progress);
                }
                Files.setLastModifiedTime(out, FileTime.fromMillis(f.getModified()));
            }
        }
    }

    /** "Zip slip" protection: an entry with "../" must not escape the unpack directory. */
    public static @NotNull Path safeResolve(final @NotNull Path dir, final @NotNull String relative) throws IOException {
        final Path base = dir.toAbsolutePath().normalize();
        final Path out = base.resolve(relative).normalize();
        if (!out.startsWith(base)) {
            throw new IOException("Entry is outside of target directory: " + relative);
        }

        return out;
    }

    static @Nullable String mapName(final @NotNull String name, final @NotNull ZipEdit edit) {
        for (final String r : edit.getRemovals()) {
            if (name.equals(r) || name.startsWith(r + "/") || r.isEmpty()) {
                return null;
            }
        }
        for (final Map.Entry<String, String> r : edit.getRenames().entrySet()) {
            final String from = r.getKey();
            if (name.equals(from)) {
                return r.getValue();
            }
            if (name.startsWith(from + "/")) {
                return r.getValue() + name.substring(from.length());
            }
        }

        return name;
    }

    private static void copyExisting(
        final @NotNull Path archive,
        final @NotNull ZipEdit edit,
        final @NotNull Set<String> added,
        final @NotNull Set<String> written,
        final @NotNull ZipOutputStream out,
        final @NotNull Progress progress
    ) throws IOException {
        try (ZipFile zip = Vfs.openZip(archive)) {
            final Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                final ZipEntry ze = en.nextElement();
                final String name = ArchiveIndex.entryName(ze.getName());
                final String mapped = name.isEmpty() ? null : mapName(name, edit);
                if (mapped == null || added.contains(mapped) || !written.add(mapped)) {
                    continue;
                }
                final ZipEntry copy = new ZipEntry(ze.isDirectory() ? mapped + "/" : mapped);
                copy.setTime(ze.getTime());
                out.putNextEntry(copy);
                if (!ze.isDirectory()) {
                    try (InputStream in = zip.getInputStream(ze)) {
                        Streams.copy(in, out, Progress.ticksOnly(progress));
                    }
                }
                out.closeEntry();
                progress.bytes(0);
            }
        }
    }

    private static void writeAddition(
        final @NotNull ZipAddition a,
        final @NotNull Set<String> written,
        final @NotNull ZipOutputStream out,
        final @NotNull Progress progress
    ) throws IOException {
        if (a.getEntry().isEmpty() || !written.add(a.getEntry())) {
            return;
        }
        final ZipEntry ze = new ZipEntry(a.isDirectory() ? a.getEntry() + "/" : a.getEntry());
        ze.setTime(a.getModified());
        out.putNextEntry(ze);
        if (!a.isDirectory() && a.getSource() != null) {
            progress.file(a.getEntry(), a.getSize());
            try (InputStream in = a.getSource().get()) {
                Streams.copy(in, out, progress);
            }
        }
        out.closeEntry();
    }

    private static void replace(final @NotNull Path tmp, final @NotNull Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
