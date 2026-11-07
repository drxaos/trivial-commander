package tcm.core;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 7z (via commons-compress) — read only. Encrypted headers (the listing) and encrypted entries ask for the password
 * via {@link Passwords}. 7z has no password check value: a wrong password shows up as a decoding error in the first bytes
 * (or as a CRC error at the end of a stored entry). Folders with multi-stream coders (BCJ2), which commons-compress
 * cannot unpack, are decoded by {@link SevenZipFolders}.
 */
final class SevenZipArchives {

    private static final int DOS_ATTRIBUTES = 0xFFFF;
    private static final int UNIX_EXTENSION = 0x8000;
    /** The first bytes of an encrypted entry are unpacked right away: a wrong password breaks the decoder there. */
    private static final int PROBE = 64 * 1024;

    private SevenZipArchives() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        try (SevenZFile z = open(index.getArchive())) {
            for (final SevenZArchiveEntry e : z.getEntries()) {
                if (e.isAntiItem()) {
                    continue;
                }
                final long time = e.getHasLastModifiedDate() ? e.getLastModifiedDate().getTime() : 0;
                index.add(e.getName(), e.isDirectory(), e.getSize(), time, attrs(e));
            }
        }
    }

    /** 7-Zip keeps the DOS attributes; p7zip on Unix adds the mode in the high 16 bits with the 0x8000 flag. */
    private static @NotNull String attrs(final @NotNull SevenZArchiveEntry e) {
        if (!e.getHasWindowsAttributes()) {
            return "";
        }
        final int a = e.getWindowsAttributes();
        if ((a & UNIX_EXTENSION) != 0) {
            final String posix = FileAttrs.posix(a >>> 16);
            if (!posix.isEmpty()) {
                return posix;
            }
        }

        return FileAttrs.dos(a & DOS_ATTRIBUTES);
    }

    static @NotNull ArchiveReader reader(final @NotNull Path archive) throws IOException {
        return new ArchiveReader() {
            private @NotNull SevenZFile z = SevenZipArchives.open(archive);
            private @Nullable Map<String, SevenZArchiveEntry> byName;
            private boolean withPassword;
            private @Nullable String password;
            /** Entry name -> its stream number, for the folders commons-compress cannot decode. */
            private @Nullable Map<String, Integer> streamOf;
            private @Nullable SevenZipFolders folders;
            private boolean foldersFailed;

            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final SevenZArchiveEntry e = entries().get(entry);
                if (e == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }
                if (!e.hasStream()) {
                    return InputStream.nullInputStream();
                }
                final SevenZipFolders own = folders();
                final Integer stream = streamOf().get(entry);
                if (own != null && stream != null && own.isComplex(stream)) {
                    return openOwn(own, stream, entry);
                }
                if (withPassword) {
                    return z.getInputStream(e);
                }
                if (!isEncrypted(e)) {
                    try {
                        return prefetch(z.getInputStream(e));
                    } catch (final PasswordRequiredException ex) {
                        // entries do not always list their coders: the decoder found the encryption itself
                    }
                }

                // the archive is reopened with the password, the other entries are read through it too
                return Passwords.withPassword(archive, password -> {
                    final SevenZFile reopened = SevenZipArchives.open(archive, password);
                    try {
                        final SevenZArchiveEntry same = index(reopened).get(entry);
                        if (same == null) {
                            throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                        }
                        final InputStream in = probe(reopened, same, entry);
                        z.close();
                        z = reopened;
                        byName = index(reopened);
                        withPassword = true;
                        this.password = password;

                        return in;
                    } catch (final IOException | RuntimeException ex) {
                        reopened.close();
                        throw ex;
                    }
                });
            }

            private @NotNull InputStream openOwn(final @NotNull SevenZipFolders own, final int stream, final @NotNull String entry)
                throws IOException {
                if (!own.needsPassword(stream)) {
                    return prefetch(own.open(stream, null));
                }
                final String known = password;
                if (known != null) {
                    return own.open(stream, known);
                }

                return Passwords.withPassword(archive, pw -> {
                    final InputStream in = probe(() -> own.open(stream, pw), entry);
                    password = pw;

                    return in;
                });
            }

            /**
             * Parsed on the first entry; null — the parser does not understand the archive, then commons-compress
             * reads everything it can as before.
             */
            private @Nullable SevenZipFolders folders() throws IOException {
                if (folders == null && !foldersFailed) {
                    try {
                        final SevenZipFolders parsed = openFolders(archive, password);
                        int withData = 0;
                        for (final SevenZArchiveEntry e : z.getEntries()) {
                            withData += e.hasStream() ? 1 : 0;
                        }
                        if (parsed.streamCount() == withData) {
                            folders = parsed;
                        } else {
                            parsed.close();
                            foldersFailed = true;
                        }
                    } catch (final PasswordCancelledException e) {
                        throw e;
                    } catch (final IOException | RuntimeException e) {
                        foldersFailed = true;
                    }
                }

                return folders;
            }

            private @NotNull Map<String, Integer> streamOf() {
                if (streamOf == null) {
                    final Map<String, Integer> map = new HashMap<>();
                    int stream = 0;
                    for (final SevenZArchiveEntry e : z.getEntries()) {
                        if (e.hasStream()) {
                            if (!e.isDirectory() && !e.isAntiItem()) {
                                map.putIfAbsent(ArchiveIndex.entryName(e.getName()), stream);
                            }
                            stream++;
                        }
                    }
                    streamOf = map;
                }

                return streamOf;
            }

            private @NotNull Map<String, SevenZArchiveEntry> entries() {
                if (byName == null) {
                    byName = index(z);
                }

                return byName;
            }

            @Override
            public void close() throws IOException {
                try {
                    z.close();
                } finally {
                    if (folders != null) {
                        folders.close();
                    }
                }
            }
        };
    }

    private static @NotNull Map<String, SevenZArchiveEntry> index(final @NotNull SevenZFile z) {
        final Map<String, SevenZArchiveEntry> map = new HashMap<>();
        for (final SevenZArchiveEntry e : z.getEntries()) {
            if (!e.isDirectory() && !e.isAntiItem()) {
                map.putIfAbsent(ArchiveIndex.entryName(e.getName()), e);
            }
        }

        return map;
    }

    private static boolean isEncrypted(final @NotNull SevenZArchiveEntry e) {
        final Iterable<? extends SevenZMethodConfiguration> methods = e.getContentMethods();
        if (methods == null) {
            return false;
        }
        for (final SevenZMethodConfiguration m : methods) {
            if (m.getMethod() == SevenZMethod.AES256SHA256) {
                return true;
            }
        }

        return false;
    }

    /** Unpacks the first bytes right away: a missing password shows up here, not in the middle of copying. */
    private static @NotNull InputStream prefetch(final @NotNull InputStream in) throws IOException {
        try {
            return new SequenceInputStream(new ByteArrayInputStream(in.readNBytes(PROBE)), in);
        } catch (final IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /**
     * Unpacks the first bytes: a decoder error there means a wrong password. In a solid block getInputStream itself unpacks
     * the entries before this one, so its errors count too.
     */
    private static @NotNull InputStream probe(final @NotNull SevenZFile z, final @NotNull SevenZArchiveEntry e, final @NotNull String entry)
        throws IOException {
        return probe(() -> z.getInputStream(e), entry);
    }

    private static @NotNull InputStream probe(final @NotNull IoSupplier<InputStream> open, final @NotNull String entry) throws IOException {
        final InputStream in;
        try {
            in = open.get();
        } catch (final PasswordRequiredException ex) {
            throw ex;
        } catch (final IOException | RuntimeException ex) {
            throw new WrongPasswordException(entry);
        }
        final byte[] head;
        try {
            head = in.readNBytes(PROBE);
        } catch (final IOException | RuntimeException ex) {
            in.close();
            throw new WrongPasswordException(entry);
        }

        return new SequenceInputStream(new ByteArrayInputStream(head), in);
    }

    /**
     * The archive's folders for the streams commons-compress cannot decode (BCJ2). Encrypted headers take the password
     * already given for the listing.
     */
    private static @NotNull SevenZipFolders openFolders(final @NotNull Path archive, final @Nullable String password) throws IOException {
        try {
            return SevenZipFolders.open(archive, password);
        } catch (final PasswordRequiredException e) {
            return Passwords.withPassword(archive, pw -> SevenZipFolders.open(archive, pw));
        }
    }

    /** Without a password; encrypted headers ask for it. */
    private static @NotNull SevenZFile open(final @NotNull Path archive) throws IOException {
        try {
            return SevenZFile.builder()
                .setPath(archive)
                .get();
        } catch (final PasswordRequiredException e) {
            return Passwords.withPassword(archive, password -> open(archive, password));
        }
    }

    private static @NotNull SevenZFile open(final @NotNull Path archive, final @NotNull String password) throws IOException {
        try {
            return SevenZFile.builder()
                .setPath(archive)
                .setPassword(password)
                .get();
        } catch (final PasswordRequiredException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            // the headers decrypted with a wrong password are garbage
            throw new WrongPasswordException(String.valueOf(archive.getFileName()));
        }
    }
}
