package tcm.core;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.RequiredArgsConstructor;

/**
 * Chromium/Electron resource packs (GRIT .pak, versions 4 and 5) — read only. Resources carry only numeric IDs, so an entry
 * is named by its ID plus an extension guessed from the content (1234.png). An alias (v5) is shown as a copy of the
 * resource it points to.
 */
final class PakArchives {

    private static final int V4 = 4;
    private static final int V5 = 5;
    private static final int V4_HEADER = 9;
    private static final int V5_HEADER = 12;
    private static final int ENTRY_SIZE = 6;
    private static final int ALIAS_SIZE = 4;
    /** BINARY, UTF8, UTF16 — the text encoding of the pack. */
    private static final int MAX_ENCODING = 2;
    private static final int SNIFF_SIZE = 256;
    private static final int MAX_COUNT = 0xFFFF;

    private PakArchives() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        try (FileChannel ch = FileChannel.open(index.getArchive(), StandardOpenOption.READ)) {
            for (final Resource r : resources(ch, index.getArchive())) {
                index.add(r.name, false, r.length, 0, "");
            }
        }
    }

    static @NotNull ArchiveReader reader(final @NotNull Path archive) throws IOException {
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        final Map<String, Resource> byName = new HashMap<>();
        try {
            for (final Resource r : resources(ch, archive)) {
                byName.put(r.name, r);
            }
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e;
        }

        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final Resource r = byName.get(entry);
                if (r == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }

                return new ByteArrayInputStream(read(ch, r.offset, r.length));
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }

    /** Signature check for a file with another extension: a known version and a consistent, non-empty offset table. */
    static boolean matches(final @NotNull Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final Table t = table(ch);

            return t != null && t.ids.length > 0;
        }
    }

    private static @NotNull List<Resource> resources(final @NotNull FileChannel ch, final @NotNull Path archive) throws IOException {
        final Table t = table(ch);
        if (t == null) {
            throw new IOException("Not a Chromium resource pack: " + archive.getFileName());
        }
        final List<Resource> result = new ArrayList<>(t.ids.length + t.aliasIds.length);
        final String[] extensions = new String[t.ids.length];
        for (int i = 0; i < t.ids.length; i++) {
            final long offset = t.offsets[i];
            final long length = t.offsets[i + 1] - offset;
            extensions[i] = extension(read(ch, offset, Math.min(length, SNIFF_SIZE)));
            result.add(new Resource(t.ids[i] + extensions[i], offset, length));
        }
        for (int i = 0; i < t.aliasIds.length; i++) {
            final int target = t.aliasTargets[i];
            final long offset = t.offsets[target];
            result.add(new Resource(t.aliasIds[i] + extensions[target], offset, t.offsets[target + 1] - offset));
        }

        return result;
    }

    /** The header and the resource/alias tables; null — not a resource pack. */
    private static @Nullable Table table(final @NotNull FileChannel ch) throws IOException {
        final long size = ch.size();
        if (size < V5_HEADER) {
            return null;
        }
        final ByteBuffer head = ByteBuffer.wrap(read(ch, 0, V5_HEADER)).order(ByteOrder.LITTLE_ENDIAN);
        final int version = head.getInt(0);
        final long count;
        final int aliases;
        final int encoding;
        final int headerSize;
        if (version == V4) {
            count = Integer.toUnsignedLong(head.getInt(4));
            encoding = head.get(8);
            aliases = 0;
            headerSize = V4_HEADER;
        } else if (version == V5) {
            encoding = head.get(4);
            count = Short.toUnsignedInt(head.getShort(8));
            aliases = Short.toUnsignedInt(head.getShort(10));
            headerSize = V5_HEADER;
        } else {
            return null;
        }
        final long dataStart = headerSize + (count + 1) * ENTRY_SIZE + (long) aliases * ALIAS_SIZE;
        // IDs are 16-bit, so a larger count is not a resource pack
        if (encoding < 0 || encoding > MAX_ENCODING || count > MAX_COUNT || dataStart > size) {
            return null;
        }
        final ByteBuffer tables = ByteBuffer.wrap(read(ch, headerSize, dataStart - headerSize)).order(ByteOrder.LITTLE_ENDIAN);

        return parseTables(tables, (int) count, aliases, dataStart, size);
    }

    /** Offsets must start after the tables, never decrease and stay inside the file; aliases must point at a resource. */
    private static @Nullable Table parseTables(
        final @NotNull ByteBuffer tables,
        final int count,
        final int aliases,
        final long dataStart,
        final long size
    ) {
        final Table t = new Table(new int[count], new long[count + 1], new int[aliases], new int[aliases]);
        long previous = dataStart;
        for (int i = 0; i <= count; i++) {
            final int id = Short.toUnsignedInt(tables.getShort());
            final long offset = Integer.toUnsignedLong(tables.getInt());
            if (offset < previous || offset > size) {
                return null;
            }
            if (i < count) {
                t.ids[i] = id;
            }
            t.offsets[i] = offset;
            previous = offset;
        }
        for (int i = 0; i < aliases; i++) {
            t.aliasIds[i] = Short.toUnsignedInt(tables.getShort());
            t.aliasTargets[i] = Short.toUnsignedInt(tables.getShort());
            if (t.aliasTargets[i] >= count) {
                return null;
            }
        }

        return t;
    }

    /** Extension by content: images and compressed data by signature, markup by its first tag, other text — .txt. */
    private static @NotNull String extension(final byte @NotNull [] head) {
        if (head.length == 0) {
            return ".bin";
        }
        if (startsWith(head, 0x89, 'P', 'N', 'G')) {
            return ".png";
        }
        if (startsWith(head, 'G', 'I', 'F', '8')) {
            return ".gif";
        }
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return ".jpg";
        }
        if (startsWith(head, 'R', 'I', 'F', 'F') && head.length >= 12 && new String(head, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP")) {
            return ".webp";
        }
        if (startsWith(head, 'w', 'O', 'F', '2')) {
            return ".woff2";
        }
        if (startsWith(head, 'f', 'L', 'a', 'C')) {
            return ".flac";
        }
        if (head.length >= 8 && new String(head, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")) {
            return ".mp4";
        }
        if (startsWith(head, 0x1F, 0x8B)) {
            return ".gz";
        }
        // Chromium stores brotli-compressed resources behind the magic 1E 9B and a 6-byte uncompressed size
        if (startsWith(head, 0x1E, 0x9B)) {
            return ".br";
        }
        if (!isText(head)) {
            return ".bin";
        }

        return textExtension(new String(head, StandardCharsets.UTF_8).strip().toLowerCase(Locale.ROOT));
    }

    private static @NotNull String textExtension(final @NotNull String text) {
        final String s = text.startsWith("﻿") ? text.substring(1) : text;
        if (s.startsWith("<svg") || s.startsWith("<?xml") && s.contains("<svg")) {
            return ".svg";
        }
        if (s.startsWith("<?xml")) {
            return ".xml";
        }
        if (s.startsWith("<!doctype") || s.startsWith("<html") || s.startsWith("<!--") || s.startsWith("<link")
            || s.startsWith("<style") || s.startsWith("<template") || s.startsWith("<div") || s.startsWith("<meta")) {
            return ".html";
        }
        if (isJson(s)) {
            return ".json";
        }

        return ".txt";
    }

    /** An object starts with a quoted key — unlike ICU messages in locale packs ("{COUNT,plural, =1{...}"). */
    private static boolean isJson(final @NotNull String s) {
        if (!s.startsWith("{") && !s.startsWith("[")) {
            return false;
        }
        final String rest = s.substring(1).stripLeading();

        return rest.isEmpty() || rest.charAt(0) == '"' || s.charAt(0) == '{' && rest.charAt(0) == '}'
            || s.charAt(0) == '[' && "[{]".indexOf(rest.charAt(0)) >= 0;
    }

    /** No control characters except whitespace; bytes above 0x7F are allowed (UTF-8). */
    private static boolean isText(final byte @NotNull [] data) {
        for (final byte b : data) {
            final int c = b & 0xFF;
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f') {
                return false;
            }
        }

        return true;
    }

    private static boolean startsWith(final byte @NotNull [] data, final int... prefix) {
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

    private static byte @NotNull [] read(final @NotNull FileChannel ch, final long position, final long length) throws IOException {
        if (length > Integer.MAX_VALUE - 8) {
            throw new IOException("Resource too large: " + length + " bytes");
        }
        final ByteBuffer buf = ByteBuffer.allocate((int) length);
        while (buf.hasRemaining()) {
            if (ch.read(buf, position + buf.position()) < 0) {
                throw new EOFException("Unexpected end of resource pack");
            }
        }

        return buf.array();
    }

    @RequiredArgsConstructor
    private static final class Resource {

        private final @NotNull String name;
        private final long offset;
        private final long length;
    }

    @RequiredArgsConstructor
    private static final class Table {

        private final int @NotNull [] ids;
        /** count + 1 offsets: the last one marks the end of the last resource. */
        private final long @NotNull [] offsets;
        private final int @NotNull [] aliasIds;
        private final int @NotNull [] aliasTargets;
    }
}
