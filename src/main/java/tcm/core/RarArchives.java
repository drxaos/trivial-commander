package tcm.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.github.junrar.Archive;
import com.github.junrar.UnrarCallback;
import com.github.junrar.exception.CrcErrorException;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.HostSystem;

/**
 * RAR (via junrar) — read only. junrar picks up multi-volume archives by volume names itself.
 * Encrypted entries and encrypted headers (the listing itself) ask for the password via {@link Passwords}.
 */
final class RarArchives {

    /** Entries up to this size are unpacked into memory, larger ones — into a temporary file. */
    private static final long MEMORY_LIMIT = 16L * 1024 * 1024;

    private RarArchives() {
    }

    static void load(final @NotNull ArchiveIndex index) throws IOException {
        try (Archive rar = open(index.getArchive())) {
            for (final FileHeader fh : rar.getFileHeaders()) {
                final long time = fh.getMTime() != null ? fh.getMTime().getTime() : 0;
                index.add(fh.getFileName(), fh.isDirectory(), fh.getFullUnpackSize(), time, attrs(fh));
            }
        }
    }

    /** RAR keeps the attributes of the system it was packed on: the Unix mode or the DOS attribute byte. */
    private static @NotNull String attrs(final @NotNull FileHeader fh) {
        final HostSystem host = fh.getHostOS();
        if (host == null) {
            return "";
        }
        if (host == HostSystem.unix || host == HostSystem.macos || host == HostSystem.beos) {
            return FileAttrs.posix(fh.getFileAttr());
        }

        return FileAttrs.dos(fh.getFileAttr());
    }

    static @NotNull ArchiveReader reader(final @NotNull Path archive) throws IOException {
        final Archive rar = open(archive);
        final Map<String, FileHeader> byName = new HashMap<>();
        for (final FileHeader fh : rar.getFileHeaders()) {
            if (!fh.isDirectory()) {
                byName.put(ArchiveIndex.entryName(fh.getFileName()), fh);
            }
        }

        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final FileHeader fh = byName.get(entry);
                if (fh == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }
                if (!fh.isEncrypted()) {
                    return extract(rar, fh);
                }

                return Passwords.withPassword(archive, password -> {
                    rar.setPassword(password);

                    return extract(rar, fh);
                });
            }

            @Override
            public void close() throws IOException {
                rar.close();
            }
        };
    }

    /**
     * The entry is unpacked entirely before the stream is returned: junrar provides data only via extractFile,
     * and its streaming variant starts a separate thread and breaks the unpacking order of a solid archive on early close.
     */
    private static @NotNull InputStream extract(final @NotNull Archive rar, final @NotNull FileHeader fh) throws IOException {
        if (fh.getFullUnpackSize() <= MEMORY_LIMIT) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(fh.getFullUnpackSize(), 32));
            extractTo(rar, fh, out);

            return new ByteArrayInputStream(out.toByteArray());
        }
        final Path tmp = Files.createTempFile("tcm-rar-", ".tmp");
        tmp.toFile().deleteOnExit();
        try (OutputStream out = Files.newOutputStream(tmp)) {
            extractTo(rar, fh, out);
        } catch (final IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        return new FilterInputStream(Files.newInputStream(tmp)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        };
    }

    private static void extractTo(final @NotNull Archive rar, final @NotNull FileHeader fh, final @NotNull OutputStream out) throws IOException {
        try {
            rar.extractFile(fh, out);
        } catch (final com.github.junrar.exception.WrongPasswordException e) {
            throw new WrongPasswordException(fh.getFileName());
        } catch (final CrcErrorException e) {
            // RAR4 keeps no password check: a wrong password shows up only as a CRC error
            if (fh.isEncrypted()) {
                throw new WrongPasswordException(fh.getFileName());
            }
            throw new IOException(fh.getFileName() + ": " + describe(e), e);
        } catch (final RarException e) {
            throw new IOException(fh.getFileName() + ": " + describe(e), e);
        }
    }

    /** Encrypted headers: even the listing needs the password. */
    private static @NotNull Archive open(final @NotNull Path archive) throws IOException {
        try {
            return new Archive(archive.toFile());
        } catch (final com.github.junrar.exception.WrongPasswordException e) {
            return Passwords.withPassword(archive, password -> open(archive, password));
        } catch (final RarException e) {
            throw new IOException("Cannot open RAR archive " + archive.getFileName() + ": " + describe(e), e);
        }
    }

    private static @NotNull Archive open(final @NotNull Path archive, final @NotNull String password) throws IOException {
        try {
            return new Archive(archive.toFile(), (UnrarCallback) null, password);
        } catch (final com.github.junrar.exception.WrongPasswordException e) {
            throw new WrongPasswordException(String.valueOf(archive.getFileName()));
        } catch (final RarException e) {
            throw new IOException("Cannot open RAR archive " + archive.getFileName() + ": " + describe(e), e);
        }
    }

    private static @NotNull String describe(final @NotNull RarException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
