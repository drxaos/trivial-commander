package tcm.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Windows Installer packages (.msi, .msm) and other compound files — read only. An MSI shows the files it installs, in the
 * directory tree of its Directory table (standard folders under their property names: ProgramFiles64Folder...), with the data
 * taken from its cabinets: embedded streams or .cab files next to the package. A compound file without that (an old Office
 * document, a damaged package) lists its raw streams instead.
 */
final class MsiArchives {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._";
    private static final char TABLE_PREFIX = 0x4840;
    private static final String ROOT_DIR = "TARGETDIR";

    private MsiArchives() {
    }

    /** Where an entry's data comes from: a file in a cabinet or a raw stream. */
    private interface Source {

        @NotNull InputStream open() throws IOException;
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        final Path archive = index.getArchive();
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        final List<AutoCloseable> resources = new ArrayList<>();
        resources.add(ch);
        try {
            final CompoundFile cf = CompoundFile.open(BlockSource.of(ch));
            final Map<String, CompoundFile.Stream> streams = new HashMap<>();
            for (final CompoundFile.Stream s : cf.getStreams()) {
                streams.put(decodeName(s.getPath()), s);
            }
            Map<String, Source> sources = installedFiles(index, archive, streams, resources);
            if (sources == null) {
                sources = rawStreams(index, streams);
            }
            index.setTar(reader(archive, sources, resources));
        } catch (final IOException | RuntimeException e) {
            closeAll(resources);
            throw e instanceof IOException io ? io : new IOException("Not a valid MSI package: " + archive.getFileName(), e);
        }
    }

    /**
     * Stream names of MSI are packed: two characters of [0-9A-Za-z._] per UTF-16 unit in 0x3800..0x47FF, one in 0x4800..0x483F;
     * 0x4840 marks the tables ("!"). Control characters of other compound files (\u0005SummaryInformation) become "[5]".
     */
    static @NotNull String decodeName(final @NotNull String raw) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c >= 0x3800 && c < 0x4800) {
                sb.append(ALPHABET.charAt((c - 0x3800) & 0x3F)).append(ALPHABET.charAt(((c - 0x3800) >> 6) & 0x3F));
            } else if (c >= 0x4800 && c < TABLE_PREFIX) {
                sb.append(ALPHABET.charAt(c - 0x4800));
            } else if (c == TABLE_PREFIX) {
                sb.append('!');
            } else if (c < 0x20) {
                sb.append('[').append((int) c).append(']');
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    private static @NotNull Map<String, Source> rawStreams(
        final @NotNull ArchiveIndex index,
        final @NotNull Map<String, CompoundFile.Stream> streams
    ) {
        final Map<String, Source> sources = new HashMap<>();
        for (final Map.Entry<String, CompoundFile.Stream> e : streams.entrySet()) {
            final CompoundFile.Stream s = e.getValue();
            index.add(e.getKey(), false, s.getSize(), 0, "");
            sources.put(ArchiveIndex.entryName(e.getKey()), () -> BlockSource.stream(s.getData(), 0, s.getSize()));
        }

        return sources;
    }

    /** null — not an installer database with files (no File table or no cabinets). */
    private static @Nullable Map<String, Source> installedFiles(
        final @NotNull ArchiveIndex index,
        final @NotNull Path archive,
        final @NotNull Map<String, CompoundFile.Stream> streams,
        final @NotNull List<AutoCloseable> resources
    ) throws IOException {
        if (!streams.containsKey("!_StringPool") || !streams.containsKey("!File")) {
            return null;
        }
        final MsiDatabase db = new MsiDatabase(streams);
        final Map<String, String> componentDirs = new HashMap<>();
        for (final Map<String, Object> row : db.rows("Component")) {
            componentDirs.put(str(row, "Component"), str(row, "Directory_"));
        }
        final Map<String, Map<String, Object>> dirs = new HashMap<>();
        for (final Map<String, Object> row : db.rows("Directory")) {
            dirs.put(str(row, "Directory"), row);
        }
        final List<Map<String, Object>> media = new ArrayList<>(db.rows("Media"));
        media.sort(Comparator.comparingInt(r -> num(r, "LastSequence")));
        final Map<String, CabFile> cabs = new HashMap<>();
        final Map<CabFile, Map<String, CabFile.Entry>> byKey = new HashMap<>();
        final Map<String, Source> sources = new HashMap<>();
        final Map<String, String> paths = new HashMap<>();
        for (final Map<String, Object> file : db.rows("File")) {
            final String key = str(file, "File");
            final CabFile cab = cabinet(archive, streams, media, num(file, "Sequence"), cabs, resources);
            final CabFile.Entry entry = cab == null ? null : find(cab, key, byKey);
            if (entry == null) {
                continue;
            }
            final String dir = dirPath(componentDirs.get(str(file, "Component_")), dirs, paths, new HashSet<>());
            String path = ArchiveIndex.join(dir, longName(str(file, "FileName")));
            if (sources.containsKey(ArchiveIndex.entryName(path))) {
                // two components install a file of the same name (32/64-bit variants): both stay visible
                path = path + "." + key;
            }
            index.add(path, false, entry.getSize(), entry.getTime(), FileAttrs.dos(entry.getAttributes()));
            sources.put(ArchiveIndex.entryName(path), () -> cab.open(entry));
        }

        return sources.isEmpty() ? null : sources;
    }

    private static @Nullable CabFile.Entry find(
        final @NotNull CabFile cab,
        final @NotNull String key,
        final @NotNull Map<CabFile, Map<String, CabFile.Entry>> byKey
    ) {
        return byKey.computeIfAbsent(cab, c -> {
            final Map<String, CabFile.Entry> map = new HashMap<>();
            for (final CabFile.Entry e : c.getEntries()) {
                map.putIfAbsent(e.getName(), e);
            }

            return map;
        }).get(key);
    }

    /** The cabinet of the media (disk) whose LastSequence covers the file's sequence number. */
    private static @Nullable CabFile cabinet(
        final @NotNull Path archive,
        final @NotNull Map<String, CompoundFile.Stream> streams,
        final @NotNull List<Map<String, Object>> media,
        final int sequence,
        final @NotNull Map<String, CabFile> cabs,
        final @NotNull List<AutoCloseable> resources
    ) throws IOException {
        for (final Map<String, Object> m : media) {
            if (num(m, "LastSequence") < sequence) {
                continue;
            }
            final String name = str(m, "Cabinet");
            if (name.isEmpty()) {
                return null;
            }
            if (!cabs.containsKey(name)) {
                cabs.put(name, openCabinet(archive, streams, name, resources));
            }

            return cabs.get(name);
        }

        return null;
    }

    /** "#name" — a stream of the package, otherwise a file next to it; null — missing. */
    private static @Nullable CabFile openCabinet(
        final @NotNull Path archive,
        final @NotNull Map<String, CompoundFile.Stream> streams,
        final @NotNull String name,
        final @NotNull List<AutoCloseable> resources
    ) throws IOException {
        if (name.startsWith("#")) {
            final CompoundFile.Stream s = streams.get(name.substring(1));
            if (s == null) {
                return null;
            }
            final CabFile cab = CabFile.open(s.getData());
            resources.add(cab);

            return cab;
        }
        final Path parent = archive.getParent();
        final Path file = parent == null ? null : parent.resolve(name);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        final FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
        resources.add(ch);
        final CabFile cab = CabFile.open(BlockSource.of(ch));
        resources.add(cab);

        return cab;
    }

    /** The path of a directory: TARGETDIR is the root, the standard folders under it keep their property names. */
    private static @NotNull String dirPath(
        final @Nullable String key,
        final @NotNull Map<String, Map<String, Object>> dirs,
        final @NotNull Map<String, String> paths,
        final @NotNull Set<String> visiting
    ) {
        if (key == null) {
            return "";
        }
        final String known = paths.get(key);
        if (known != null) {
            return known;
        }
        final Map<String, Object> row = dirs.get(key);
        final String parent = row == null ? null : (String) row.get("Directory_Parent");
        String path = "";
        if (row != null && parent != null && !parent.isEmpty() && !parent.equals(key) && visiting.add(key)) {
            final String parentPath = dirPath(parent, dirs, paths, visiting);
            final boolean standard = ROOT_DIR.equals(parent) && key.endsWith("Folder");
            final String name = standard ? key : longName(targetDir(str(row, "DefaultDir")));
            path = ".".equals(name) || name.isEmpty() ? parentPath : ArchiveIndex.join(parentPath, name);
        }
        paths.put(key, path);

        return path;
    }

    /** DefaultDir is "target:source"; the target directory is the one installed. */
    private static @NotNull String targetDir(final @NotNull String defaultDir) {
        final int colon = defaultDir.indexOf(':');

        return colon < 0 ? defaultDir : defaultDir.substring(0, colon);
    }

    /** Names are "SHORT~1.EXT|long name" or just one name. */
    private static @NotNull String longName(final @NotNull String name) {
        final int bar = name.indexOf('|');

        return (bar < 0 ? name : name.substring(bar + 1)).replace('/', '_').replace('\\', '_');
    }

    private static @NotNull String str(final @NotNull Map<String, Object> row, final @NotNull String column) {
        return row.get(column) instanceof String s ? s : "";
    }

    private static int num(final @NotNull Map<String, Object> row, final @NotNull String column) {
        return row.get(column) instanceof Integer i ? i : 0;
    }

    private static @NotNull ArchiveReader reader(
        final @NotNull Path archive,
        final @NotNull Map<String, Source> sources,
        final @NotNull List<AutoCloseable> resources
    ) {
        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final Source s = sources.get(entry);
                if (s == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }

                return s.open();
            }

            @Override
            public void close() throws IOException {
                closeAll(resources);
            }
        };
    }

    private static void closeAll(final @NotNull List<AutoCloseable> resources) throws IOException {
        IOException error = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (final Exception e) {
                error = e instanceof IOException io ? io : new IOException(e);
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /**
     * The tables of an installer database. Each table is a stream stored column by column; strings are references into
     * the string pool, integers are stored with the high bit flipped, 0 is NULL. Column layouts come from the _Columns table.
     */
    private static final class MsiDatabase {

        private static final int TYPE_STRING = 0x0800;
        private static final int TYPE_VALID = 0x0100;
        private static final int TYPE_NULLABLE = 0x1000;
        private static final int TYPE_SIZE = 0xFF;

        private final Map<String, CompoundFile.Stream> streams;
        private final List<String> strings = new ArrayList<>();
        private final int refSize;
        /** Table name → its columns as {name, type}, in order. */
        private final Map<String, List<Object[]>> columns = new HashMap<>();

        MsiDatabase(final @NotNull Map<String, CompoundFile.Stream> streams) throws IOException {
            this.streams = streams;
            final byte[] pool = read("!_StringPool");
            final byte[] data = streams.containsKey("!_StringData") ? read("!_StringData") : new byte[0];
            if (pool.length < 4) {
                throw new IOException("Bad MSI string pool");
            }
            refSize = (u16(pool, 2) & 0x8000) != 0 ? 3 : 2;
            final Charset charset = charset(u16(pool, 0) | (u16(pool, 2) & 0x7FFF) << 16);
            strings.add(null);
            int offset = 0;
            for (int i = 4; i + 4 <= pool.length; i += 4) {
                int length = u16(pool, i);
                final int refs = u16(pool, i + 2);
                if (length == 0 && refs != 0 && i + 8 <= pool.length) {
                    // a string over 64 KiB: the length continues in the next entry
                    length = u16(pool, i + 6) << 16 | u16(pool, i + 4);
                    i += 4;
                }
                final int n = Math.min(length, data.length - offset);
                strings.add(n > 0 ? new String(data, offset, n, charset) : length == 0 && refs == 0 ? null : "");
                offset += Math.max(n, 0);
            }
            loadColumns();
        }

        private static @NotNull Charset charset(final int codePage) {
            if (codePage == 65001) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(codePage == 0 ? "windows-1252" : "windows-" + codePage);
            } catch (final IllegalArgumentException e) {
                return Charset.forName("windows-1252");
            }
        }

        private byte @NotNull [] read(final @NotNull String stream) throws IOException {
            final CompoundFile.Stream s = streams.get(stream);
            if (s == null) {
                return new byte[0];
            }
            if (s.getSize() > Integer.MAX_VALUE - 16) {
                throw new IOException("MSI table is too large: " + stream);
            }

            return s.getData().read(0, (int) s.getSize());
        }

        private void loadColumns() throws IOException {
            final List<Object[]> layout = List.of(
                new Object[] {"Table", TYPE_STRING | TYPE_VALID | 64},
                new Object[] {"Number", TYPE_VALID | 2},
                new Object[] {"Name", TYPE_STRING | TYPE_VALID | 64},
                new Object[] {"Type", TYPE_VALID | 2});
            final List<Map<String, Object>> rows = rows(read("!_Columns"), layout);
            rows.sort(Comparator.comparingInt(r -> r.get("Number") instanceof Integer i ? i : 0));
            for (final Map<String, Object> r : rows) {
                if (r.get("Table") instanceof String table && r.get("Name") instanceof String name && r.get("Type") instanceof Integer type) {
                    columns.computeIfAbsent(table, k -> new ArrayList<>()).add(new Object[] {name, type & 0xFFFF});
                }
            }
        }

        /** Rows of a table as column name → String/Integer/null; an absent table has none. */
        @NotNull List<Map<String, Object>> rows(final @NotNull String table) throws IOException {
            final List<Object[]> layout = columns.get(table);

            return layout == null ? new ArrayList<>() : rows(read("!" + table), layout);
        }

        private @NotNull List<Map<String, Object>> rows(final byte @NotNull [] data, final @NotNull List<Object[]> layout) {
            final int[] sizes = new int[layout.size()];
            int rowSize = 0;
            for (int c = 0; c < sizes.length; c++) {
                sizes[c] = columnSize((Integer) layout.get(c)[1]);
                rowSize += sizes[c];
            }
            final List<Map<String, Object>> rows = new ArrayList<>();
            final int count = rowSize == 0 ? 0 : data.length / rowSize;
            for (int r = 0; r < count; r++) {
                rows.add(new HashMap<>());
            }
            int base = 0;
            for (int c = 0; c < sizes.length; c++) {
                final String name = (String) layout.get(c)[0];
                final int type = (Integer) layout.get(c)[1];
                for (int r = 0; r < count; r++) {
                    rows.get(r).put(name, value(data, base + r * sizes[c], sizes[c], type));
                }
                base += sizes[c] * count;
            }

            return rows;
        }

        /** A binary column refers to a stream ("Table.key"); its stored value means nothing here. */
        private static boolean isBinary(final int type) {
            return (type & ~TYPE_NULLABLE) == (TYPE_STRING | TYPE_VALID);
        }

        private int columnSize(final int type) {
            if (isBinary(type)) {
                return 2;
            }
            if ((type & TYPE_STRING) != 0) {
                return refSize;
            }

            return (type & TYPE_SIZE) <= 2 ? 2 : 4;
        }

        private @Nullable Object value(final byte @NotNull [] data, final int pos, final int size, final int type) {
            int raw = 0;
            for (int i = size - 1; i >= 0; i--) {
                raw = raw << 8 | data[pos + i] & 0xFF;
            }
            if (isBinary(type)) {
                return null;
            }
            if ((type & TYPE_STRING) != 0) {
                return raw > 0 && raw < strings.size() ? strings.get(raw) : null;
            }
            if (raw == 0) {
                return null;
            }

            return size == 2 ? raw - 0x8000 : raw ^ 0x80000000;
        }

        private static int u16(final byte @NotNull [] b, final int pos) {
            return b[pos] & 0xFF | (b[pos + 1] & 0xFF) << 8;
        }
    }
}
