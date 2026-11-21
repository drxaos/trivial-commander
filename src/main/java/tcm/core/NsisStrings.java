package tcm.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * Strings of an NSIS script: UTF-16 (NSIS 3 Unicode) or ANSI, with codes for variables ($INSTDIR), special folders
 * ($SMPROGRAMS) and language strings. NSIS 3 codes are 1..4, NSIS 2 (ANSI only) — 252..255.
 */
final class NsisStrings {

    private static final int LANG = 1;
    private static final int SHELL = 2;
    private static final int VAR = 3;
    private static final int SKIP = 4;
    /** NSIS 2 codes count down from 256: 255 — language, 254 — shell, 253 — variable, 252 — skip. */
    private static final int NSIS2_CODES = 256;
    private static final String[] VARS = {"CMDLINE", "INSTDIR", "OUTDIR", "EXEDIR", "LANGUAGE", "TEMP", "PLUGINSDIR", "EXEPATH",
        "EXEFILE", "HWNDPARENT", "_CLICK", "_OUTDIR"};
    private static final int REGISTERS = 20;
    static final String OUTDIR = "$OUTDIR";
    static final String SAVED_OUTDIR = "$_OUTDIR";
    /** CSIDL → NSIS name of the special folder; with the 0x80 bit the folder is read from the registry (the same names). */
    private static final Map<Integer, String> FOLDERS = Map.ofEntries(Map.entry(0x00, "DESKTOP"), Map.entry(0x02, "SMPROGRAMS"),
        Map.entry(0x05, "DOCUMENTS"), Map.entry(0x06, "FAVORITES"), Map.entry(0x07, "SMSTARTUP"), Map.entry(0x08, "RECENT"),
        Map.entry(0x09, "SENDTO"), Map.entry(0x0B, "STARTMENU"), Map.entry(0x0D, "MUSIC"), Map.entry(0x0E, "VIDEOS"),
        Map.entry(0x10, "DESKTOP"), Map.entry(0x13, "NETHOOD"), Map.entry(0x14, "FONTS"), Map.entry(0x15, "TEMPLATES"),
        Map.entry(0x16, "STARTMENU"), Map.entry(0x17, "SMPROGRAMS"), Map.entry(0x18, "SMSTARTUP"), Map.entry(0x19, "DESKTOP"),
        Map.entry(0x1A, "APPDATA"), Map.entry(0x1B, "PRINTHOOD"), Map.entry(0x1C, "LOCALAPPDATA"), Map.entry(0x20, "INTERNET_CACHE"),
        Map.entry(0x21, "COOKIES"), Map.entry(0x22, "HISTORY"), Map.entry(0x23, "APPDATA"), Map.entry(0x24, "WINDIR"),
        Map.entry(0x25, "SYSDIR"), Map.entry(0x26, "PROGRAMFILES"), Map.entry(0x27, "PICTURES"), Map.entry(0x28, "PROFILE"),
        Map.entry(0x2A, "PROGRAMFILES32"), Map.entry(0x2B, "COMMONFILES"), Map.entry(0x2E, "TEMPLATES"), Map.entry(0x2F, "ADMINTOOLS"),
        Map.entry(0x30, "ADMINTOOLS"), Map.entry(0x38, "RESOURCES"), Map.entry(0x39, "RESOURCES_LOCALIZED"),
        Map.entry(0x3B, "CDBURN_AREA"));

    private final byte @NotNull [] header;
    private final int start;
    private final boolean unicode;
    /** ANSI codes are 252..255 (NSIS 2), not 1..4 (NSIS 3). */
    private final boolean nsis2;
    private final @NotNull Charset ansi;

    NsisStrings(final byte @NotNull [] header, final int start) {
        this.header = header;
        this.start = start;
        // the first string is always empty: two zero bytes are an empty UTF-16 string, in ANSI the second string follows
        unicode = start + 1 < header.length && header[start] == 0 && header[start + 1] == 0;
        nsis2 = !unicode && !hasNsis3Codes();
        ansi = Charset.isSupported("windows-1252") ? Charset.forName("windows-1252") : Charset.defaultCharset();
    }

    /** The string at offset (in characters from the table start); a negative offset is a language string. */
    @NotNull String get(final int offset) {
        if (offset < 0) {
            return lang(-(offset + 1));
        }

        return unicode ? unicodeAt(start + offset * 2) : ansiAt(start + offset);
    }

    /** A code is followed by one UTF-16 unit: the character itself (skip), a CSIDL pair or a number coded as 0x8080 | 7+7 bits. */
    private @NotNull String unicodeAt(final int from) {
        final StringBuilder sb = new StringBuilder();
        int pos = from;
        while (pos + 1 < header.length) {
            final int c = u16(pos);
            pos += 2;
            if (c == 0) {
                break;
            }
            if (c > SKIP || pos + 1 >= header.length) {
                sb.append((char) c);
                continue;
            }
            final int v = u16(pos);
            pos += 2;
            final int number = v & 0x7F | (v & 0x7F00) >> 1;
            switch (c) {
                case SKIP -> sb.append((char) v);
                case VAR -> sb.append(variable(number));
                case SHELL -> sb.append(folder(v & 0xFF, v >> 8));
                default -> sb.append(lang(number));
            }
        }

        return sb.toString();
    }

    /** A code is followed by two bytes (one for skip): the character, a CSIDL pair or a number of 7+7 bits. */
    private @NotNull String ansiAt(final int from) {
        final StringBuilder sb = new StringBuilder();
        final ByteArrayOutputStream text = new ByteArrayOutputStream();
        int pos = from;
        while (pos < header.length) {
            final int c = header[pos++] & 0xFF;
            if (c == 0) {
                break;
            }
            final int code = !nsis2 ? c : c >= NSIS2_CODES - SKIP ? NSIS2_CODES - c : 0;
            if (code < LANG || code > SKIP || pos >= header.length) {
                text.write(c);
                continue;
            }
            if (code == SKIP) {
                text.write(header[pos++]);
                continue;
            }
            if (pos + 1 >= header.length) {
                break;
            }
            final int a = header[pos] & 0xFF;
            final int b = header[pos + 1] & 0xFF;
            pos += 2;
            sb.append(text.toString(ansi));
            text.reset();
            switch (code) {
                case VAR -> sb.append(variable(a & 0x7F | (b & 0x7F) << 7));
                case SHELL -> sb.append(folder(a, b));
                default -> sb.append(lang(a & 0x7F | (b & 0x7F) << 7));
            }
        }

        return sb.append(text.toString(ansi)).toString();
    }

    private int u16(final int pos) {
        return header[pos] & 0xFF | (header[pos + 1] & 0xFF) << 8;
    }

    private static @NotNull String lang(final int index) {
        return "$(LSTR_" + index + ")";
    }

    /** The variable is $_OUTDIR (File /r saves the output directory in it). */
    static boolean isSavedOutDir(final int index) {
        return SAVED_OUTDIR.equals(variable(index));
    }

    private static @NotNull String variable(final int index) {
        if (index < 10) {
            return "$" + index;
        }
        if (index < REGISTERS) {
            return "$R" + (index - 10);
        }

        return index - REGISTERS < VARS.length ? "$" + VARS[index - REGISTERS] : "$_" + (index - REGISTERS - VARS.length) + "_";
    }

    /** Two CSIDLs: of the current user and of all users ($SMPROGRAMS for both); the first one known names the folder. */
    private static @NotNull String folder(final int first, final int second) {
        for (final int csidl : new int[] {first, second}) {
            final String name = FOLDERS.get(csidl & 0x7F);
            if (name != null) {
                return "$" + name;
            }
        }

        return "$SHELL_" + Integer.toHexString(first | second << 8);
    }

    /** NSIS 3 ANSI: bytes 1..4 (codes) occur in the strings; text never has them, while 252..255 are letters in many code pages. */
    private boolean hasNsis3Codes() {
        for (int i = start; i < header.length; i++) {
            final int c = header[i] & 0xFF;
            if (c >= LANG && c <= SKIP) {
                return true;
            }
        }

        return false;
    }

    /**
     * Entry name of an extracted file: the name is joined to the output directory unless it is a full path itself, backslashes
     * become slashes, "$INSTDIR/" is dropped (7-Zip names them so), "." and ".." parts are dropped.
     */
    static @NotNull String path(final @NotNull String outDir, final @NotNull String name) {
        final boolean absolute = name.startsWith("$") || name.startsWith("\\") || name.length() > 1 && name.charAt(1) == ':';
        final String full = absolute ? name : outDir + "\\" + name;
        final StringBuilder sb = new StringBuilder();
        for (final String part : full.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || sb.isEmpty() && part.equals("$INSTDIR")) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(part.replace(":", ""));
        }

        return sb.isEmpty() ? "_" : sb.toString();
    }
}
