package tcm.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;

/**
 * Archive entry tree (ZIP/JAR, TAR and compressed TAR, RAR). Directories not explicitly present in the archive are reconstructed from file paths.
 */
public final class ArchiveIndex {

    private static final Charset LEGACY_CHARSET = Charset.forName("IBM437");
    private static final Charset CYRILLIC_CHARSET = Charset.forName("IBM866");

    @Getter
    private final @NotNull Path archive;
    @Getter
    private final @NotNull ArchiveFormat format;
    @Getter
    private final @NotNull Charset charset;
    @Getter
    private final long archiveModified;
    @Getter
    private final long archiveSize;
    private final Map<String, VFile> entries = new HashMap<>();
    private final Map<String, List<VFile>> children = new HashMap<>();
    /** TAR: kept open for the index's whole lifetime — entries are read by offset without re-parsing headers. */
    private @Nullable ArchiveReader tar;

    private ArchiveIndex(
        final @NotNull Path archive,
        final @NotNull ArchiveFormat format,
        final @NotNull Charset charset,
        final long archiveModified,
        final long archiveSize
    ) {
        this.archive = archive;
        this.format = format;
        this.charset = charset;
        this.archiveModified = archiveModified;
        this.archiveSize = archiveSize;
        entries.put("", VFile.archiveEntry(archive, "", true, 0, archiveModified, ""));
    }

    public static @NotNull ArchiveIndex load(final @NotNull Path archive) throws IOException {
        final long modified = Files.getLastModifiedTime(archive).toMillis();
        final long size = Files.size(archive);
        final ArchiveFormat format = ArchiveFormat.forFile(archive);
        if (format == null) {
            throw new IOException("Not an archive or unsupported format: " + archive.getFileName());
        }
        switch (format) {
            case ZIP:
                return loadZip(archive, modified, size);
            case RAR:
                final ArchiveIndex rar = new ArchiveIndex(archive, format, StandardCharsets.UTF_8, modified, size);
                RarArchives.load(rar);

                return rar;
            default:
                final ArchiveIndex index = new ArchiveIndex(archive, format, StandardCharsets.UTF_8, modified, size);
                TarArchives.load(index);

                return index;
        }
    }

    private static @NotNull ArchiveIndex loadZip(final @NotNull Path archive, final long modified, final long size) throws IOException {
        if (size == 0) {
            // empty file — a new archive that is yet to be written
            return new ArchiveIndex(archive, ArchiveFormat.ZIP, StandardCharsets.UTF_8, modified, size);
        }
        try {
            return loadZip(archive, StandardCharsets.UTF_8, modified, size);
        } catch (final IllegalArgumentException | ZipException e) {
            // entry names not in UTF-8 — old archives write them in a DOS code page; JDK 21 reports this with ZipException
            // already on open, so real archive corruption will surface on the second attempt
            return loadZip(archive, legacyCharset(archive), modified, size);
        }
    }

    /**
     * DOS name encoding: CP866 if all bytes above 0x7F are Russian letters in CP866 and lowercase er–ya (0xE0–0xEF) occur among them
     * (in CP437 those positions hold Greek and math symbols, which never occur in file names; 0xE1 is also German ß),
     * otherwise CP437.
     */
    private static @NotNull Charset legacyCharset(final @NotNull Path archive) throws IOException {
        boolean cyrillic = false;
        // ISO-8859-1 decodes any bytes one to one: this exposes the original bytes of the names
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.ISO_8859_1)) {
            final Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                for (final char c : en.nextElement().getName().toCharArray()) {
                    if (c < 0x80 || c > 0xFF) {
                        // ASCII or a name with the UTF-8 flag that is already decoded correctly
                        continue;
                    }
                    if (c >= 0xB0 && c < 0xE0 || c > 0xF7) {
                        // CP866 box-drawing characters: so these are not Russian names
                        return LEGACY_CHARSET;
                    }
                    cyrillic |= c >= 0xE0 && c <= 0xEF && c != 0xE1;
                }
            }
        }

        return cyrillic ? CYRILLIC_CHARSET : LEGACY_CHARSET;
    }

    private static @NotNull ArchiveIndex loadZip(
        final @NotNull Path archive,
        final @NotNull Charset charset,
        final long modified,
        final long size
    ) throws IOException {
        final ArchiveIndex index = new ArchiveIndex(archive, ArchiveFormat.ZIP, charset, modified, size);
        final Map<String, String> attrs = ZipAttributes.read(archive, charset);
        try (ZipFile zip = new ZipFile(archive.toFile(), charset)) {
            final Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                final ZipEntry ze = en.nextElement();
                index.add(ze.getName(), ze.isDirectory(), ze.getSize(), ze.getTime(), attrs.getOrDefault(entryName(ze.getName()), ""));
            }
        }

        return index;
    }

    public static @NotNull String normalize(final @NotNull String rawName) {
        String name = rawName.replace('\\', '/');
        while (name.startsWith("/") || name.startsWith("./")) {
            name = name.substring(name.startsWith("/") ? 1 : 2);
        }
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }

        return ".".equals(name) ? "" : name;
    }

    /**
     * Entry name in the tree: without "." and "..". Otherwise entry "../../x" copied from an archive would end up
     * outside the target directory (zip slip).
     */
    public static @NotNull String entryName(final @NotNull String rawName) {
        final StringBuilder sb = new StringBuilder();
        for (final String part : rawName.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(part);
        }

        return sb.toString();
    }

    public static @NotNull String parentOf(final @NotNull String entry) {
        final int slash = entry.lastIndexOf('/');

        return slash < 0 ? "" : entry.substring(0, slash);
    }

    public static @NotNull String join(final @NotNull String dirEntry, final @NotNull String name) {
        return dirEntry.isEmpty() ? name : dirEntry + "/" + name;
    }

    public @Nullable VFile get(final @NotNull String entry) {
        return entries.get(entry);
    }

    public @NotNull List<VFile> list(final @NotNull String dirEntry) {
        final List<VFile> list = children.get(dirEntry);

        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** All entries with the given prefix (including the directory itself) — for recursive operations. */
    public @NotNull Map<String, VFile> subtree(final @NotNull String entry) {
        final Map<String, VFile> result = new LinkedHashMap<>();
        final String prefix = entry + "/";
        for (final Map.Entry<String, VFile> e : entries.entrySet()) {
            if (e.getKey().equals(entry) || entry.isEmpty() || e.getKey().startsWith(prefix)) {
                result.put(e.getKey(), e.getValue());
            }
        }

        return result;
    }

    public int size() {
        return entries.size() - 1;
    }

    /** Opens the archive for reading entries; must be closed. */
    public @NotNull ArchiveReader openReader() throws IOException {
        switch (format) {
            case ZIP:
                return zipReader();
            case RAR:
                return RarArchives.reader(archive);
            default:
                return tarReader();
        }
    }

    /** Releases the open TAR and the temporary unpacked copy; called when the index is outdated. */
    void dispose() {
        try {
            if (tar != null) {
                tar.close();
            }
        } catch (final IOException e) {
            // the temporary file will be deleted on exit anyway (deleteOnExit)
        }
        tar = null;
    }

    void setTar(final @NotNull ArchiveReader reader) {
        this.tar = reader;
    }

    /** Adds an entry by its raw name from the archive; attrs — Attr column text (see FileAttrs), "" — unknown. */
    void add(final @NotNull String rawName, final boolean directory, final long size, final long time, final @NotNull String attrs) {
        final String name = entryName(rawName);
        if (name.isEmpty()) {
            return;
        }
        final long t = time <= 0 ? archiveModified : time;
        ensureParents(name, t);
        if (!directory) {
            put(VFile.archiveEntry(archive, name, false, Math.max(size, 0), t, attrs));
        } else if (!entries.containsKey(name) || !attrs.isEmpty()) {
            // a directory already reconstructed from an earlier file path gets its own attributes and time
            put(VFile.archiveEntry(archive, name, true, 0, t, attrs));
        }
    }

    private @NotNull ArchiveReader zipReader() throws IOException {
        final ZipFile zip = new ZipFile(archive.toFile(), charset);

        return new ArchiveReader() {
            /** Entries with "non-standard" names ("./a", "..\\a"), built on the first miss. */
            private @Nullable Map<String, ZipEntry> byName;

            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                ZipEntry ze = zip.getEntry(entry);
                if (ze == null) {
                    ze = lookup(entry);
                }

                return zip.getInputStream(ze);
            }

            private @NotNull ZipEntry lookup(final @NotNull String entry) throws FileNotFoundException {
                if (byName == null) {
                    final Map<String, ZipEntry> map = new HashMap<>();
                    final Enumeration<? extends ZipEntry> en = zip.entries();
                    while (en.hasMoreElements()) {
                        final ZipEntry e = en.nextElement();
                        map.putIfAbsent(entryName(e.getName()), e);
                    }
                    byName = map;
                }
                final ZipEntry ze = byName.get(entry);
                if (ze == null || ze.isDirectory()) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }

                return ze;
            }

            @Override
            public void close() throws IOException {
                zip.close();
            }
        };
    }

    private @NotNull ArchiveReader tarReader() throws IOException {
        final ArchiveReader shared = tar;
        if (shared == null) {
            throw new IOException("Archive is closed: " + archive);
        }

        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                return shared.open(entry);
            }

            @Override
            public void close() {
                // the TAR belongs to the index and is closed together with it
            }
        };
    }

    private void ensureParents(final @NotNull String name, final long time) {
        String parent = parentOf(name);
        final List<String> missing = new ArrayList<>();
        while (!parent.isEmpty() && !entries.containsKey(parent)) {
            missing.add(parent);
            parent = parentOf(parent);
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            put(VFile.archiveEntry(archive, missing.get(i), true, 0, time, ""));
        }
    }

    private void put(final @NotNull VFile file) {
        final VFile old = entries.put(file.getEntry(), file);
        final List<VFile> siblings = children.computeIfAbsent(parentOf(file.getEntry()), k -> new ArrayList<>());
        if (old != null) {
            siblings.remove(old);
        }
        siblings.add(file);
    }
}
