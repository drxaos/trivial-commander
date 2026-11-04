package tcm.core;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipExtraField;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipShort;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.jetbrains.annotations.NotNull;
import org.tukaani.xz.LZMAInputStream;

/**
 * ZIP archives with encrypted entries — read only. java.util.zip refuses such an archive as a whole ("invalid CEN header
 * (encrypted entry)"), so it is read with commons-compress, and encrypted entries are decrypted here: WinZip AES (AE-1/AE-2,
 * 128/192/256 bit) and the traditional PKWARE encryption (ZipCrypto). The password is asked via {@link Passwords}.
 */
final class EncryptedZip {

    private static final ZipShort AES_EXTRA = new ZipShort(0x9901);
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;
    private static final int METHOD_DEFLATE64 = 9;
    private static final int METHOD_BZIP2 = 12;
    private static final int METHOD_LZMA = 14;
    private static final int METHOD_AES = 99;
    private static final int FLAG_ENCRYPTED = 1;
    private static final int FLAG_DATA_DESCRIPTOR = 8;
    private static final int FLAG_STRONG_ENCRYPTION = 0x40;
    private static final int FLAG_UTF8 = 0x800;
    private static final int AES_ITERATIONS = 1000;
    private static final int AES_MAC_LENGTH = 10;
    private static final int AES_VERIFIER_LENGTH = 2;
    private static final int ZIPCRYPTO_HEADER = 12;
    private static final int LOCAL_HEADER_TIME = 10;
    private static final int LOCAL_HEADER_SIG = 0x04034b50;

    private EncryptedZip() {
    }

    /** java.util.zip refused the archive because of encrypted entries. */
    static boolean isEncryptionError(final @NotNull IOException e) {
        return e.getMessage() != null && e.getMessage().contains("encrypted entry");
    }

    static @NotNull ZipFile open(final @NotNull Path archive) throws IOException {
        return ZipFile.builder()
            .setPath(archive)
            .setUseUnicodeExtraFields(false)
            .get();
    }

    static @NotNull List<ZipArchiveEntry> entries(final @NotNull ZipFile zip) {
        return new ArrayList<>(Collections.list(zip.getEntries()));
    }

    /** Charset of the names without the UTF-8 flag: UTF-8 if they all are valid UTF-8, otherwise the DOS code page. */
    static @NotNull Charset charset(final @NotNull List<ZipArchiveEntry> entries) {
        boolean utf8 = true;
        final List<String> latin1 = new ArrayList<>();
        for (final ZipArchiveEntry e : entries) {
            if ((e.getRawFlag() & FLAG_UTF8) != 0) {
                continue;
            }
            latin1.add(new String(e.getRawName(), StandardCharsets.ISO_8859_1));
            utf8 &= isUtf8(e.getRawName());
        }

        return utf8 ? StandardCharsets.UTF_8 : ArchiveIndex.legacyCharset(latin1);
    }

    static @NotNull String name(final @NotNull ZipArchiveEntry e, final @NotNull Charset charset) {
        return new String(e.getRawName(), (e.getRawFlag() & FLAG_UTF8) != 0 ? StandardCharsets.UTF_8 : charset);
    }

    static @NotNull ArchiveReader reader(final @NotNull Path archive, final @NotNull Charset charset) throws IOException {
        final ZipFile zip = open(archive);
        final Map<String, ZipArchiveEntry> byName = new HashMap<>();
        for (final ZipArchiveEntry e : entries(zip)) {
            if (!e.isDirectory()) {
                byName.putIfAbsent(ArchiveIndex.entryName(name(e, charset)), e);
            }
        }

        return new ArchiveReader() {
            @Override
            public @NotNull InputStream open(final @NotNull String entry) throws IOException {
                final ZipArchiveEntry e = byName.get(entry);
                if (e == null) {
                    throw new FileNotFoundException(archive + VFile.ARCHIVE_SEPARATOR + entry);
                }
                if ((e.getRawFlag() & FLAG_ENCRYPTED) == 0) {
                    return zip.getInputStream(e);
                }

                return Passwords.withPassword(archive, password -> decrypt(archive, zip, e, entry, password));
            }

            @Override
            public void close() throws IOException {
                zip.close();
            }
        };
    }

    private static @NotNull InputStream decrypt(
        final @NotNull Path archive,
        final @NotNull ZipFile zip,
        final @NotNull ZipArchiveEntry e,
        final @NotNull String name,
        final @NotNull String password
    ) throws IOException {
        if ((e.getRawFlag() & FLAG_STRONG_ENCRYPTION) != 0) {
            throw new IOException(name + ": PKWARE strong encryption is not supported");
        }
        if (e.getMethod() == METHOD_AES) {
            return aes(zip, e, name, password);
        }
        final InputStream raw = zip.getRawInputStream(e);
        try {
            final ZipCryptoStream plain = new ZipCryptoStream(raw, password.getBytes(StandardCharsets.UTF_8),
                e.getCompressedSize() - ZIPCRYPTO_HEADER);
            final byte[] header = plain.header();
            // the last header byte repeats the high byte of the CRC (or of the DOS time when the CRC follows the data)
            final int check = (e.getRawFlag() & FLAG_DATA_DESCRIPTOR) != 0 ? dosTime(archive, e) >>> 8 & 0xFF : (int) (e.getCrc() >>> 24);
            if ((header[ZIPCRYPTO_HEADER - 1] & 0xFF) != check) {
                throw new WrongPasswordException(name);
            }

            return new CheckedStream(decompress(plain, e.getMethod(), e.getSize(), name), plain, e.getCrc(), e.getSize(), name);
        } catch (final IOException | RuntimeException ex) {
            raw.close();
            throw ex;
        }
    }

    private static @NotNull InputStream aes(final @NotNull ZipFile zip, final @NotNull ZipArchiveEntry e, final @NotNull String name,
        final @NotNull String password) throws IOException {
        final ZipExtraField extra = e.getExtraField(AES_EXTRA);
        final byte[] d = extra == null ? null : extra.getCentralDirectoryData();
        if (d == null || d.length < 7) {
            throw new IOException(name + ": AES entry without the AES header");
        }
        final int version = d[0] & 0xFF | (d[1] & 0xFF) << 8;
        final int keyLength = switch (d[4]) {
            case 1 -> 16;
            case 2 -> 24;
            case 3 -> 32;
            default -> throw new IOException(name + ": unknown AES strength " + d[4]);
        };
        final int method = d[5] & 0xFF | (d[6] & 0xFF) << 8;
        final InputStream raw = zip.getRawInputStream(e);
        try {
            final byte[] salt = readFully(raw, keyLength / 2);
            final byte[] verifier = readFully(raw, AES_VERIFIER_LENGTH);
            final byte[] key = pbkdf2(password.getBytes(StandardCharsets.UTF_8), salt, 2 * keyLength + AES_VERIFIER_LENGTH);
            if (key[2 * keyLength] != verifier[0] || key[2 * keyLength + 1] != verifier[1]) {
                throw new WrongPasswordException(name);
            }
            final long length = e.getCompressedSize() - keyLength / 2 - AES_VERIFIER_LENGTH - AES_MAC_LENGTH;
            final AesStream plain = new AesStream(raw, Arrays.copyOfRange(key, 0, keyLength),
                Arrays.copyOfRange(key, keyLength, 2 * keyLength), length, name);
            // AE-2 stores no CRC: the HMAC protects the data
            final long crc = version == 1 ? e.getCrc() : -1;

            return new CheckedStream(decompress(plain, method, e.getSize(), name), plain, crc, e.getSize(), name);
        } catch (final IOException | RuntimeException ex) {
            raw.close();
            throw ex;
        }
    }

    private static @NotNull InputStream decompress(final @NotNull InputStream in, final int method, final long size, final @NotNull String name)
        throws IOException {
        switch (method) {
            case METHOD_STORED:
                return in;
            case METHOD_DEFLATED:
                return new InflaterInputStream(in, new Inflater(true), 64 * 1024);
            case METHOD_DEFLATE64:
                return new Deflate64CompressorInputStream(in);
            case METHOD_BZIP2:
                return new BZip2CompressorInputStream(in);
            case METHOD_LZMA:
                // version (2 bytes), properties size (2), then the 5 bytes of LZMA properties
                final byte[] header = readFully(in, 4);
                final byte[] props = readFully(in, (header[2] & 0xFF) | (header[3] & 0xFF) << 8);
                if (props.length < 5) {
                    throw new IOException(name + ": bad LZMA header");
                }
                final int dict = ByteBuffer.wrap(props, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

                return new LZMAInputStream(in, size, props[0], dict);
            default:
                throw new IOException(name + ": unsupported compression method " + method);
        }
    }

    /** The DOS time of the local header: with a data descriptor ZipCrypto checks it instead of the CRC. */
    private static int dosTime(final @NotNull Path archive, final @NotNull ZipArchiveEntry e) throws IOException {
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            final ByteBuffer buf = ByteBuffer.allocate(LOCAL_HEADER_TIME + 2).order(ByteOrder.LITTLE_ENDIAN);
            while (buf.hasRemaining()) {
                if (ch.read(buf, e.getLocalHeaderOffset() + buf.position()) < 0) {
                    throw new EOFException();
                }
            }
            if (buf.getInt(0) != LOCAL_HEADER_SIG) {
                throw new IOException("Bad local header of " + e.getName());
            }

            return buf.getShort(LOCAL_HEADER_TIME) & 0xFFFF;
        }
    }

    /** PBKDF2 with HMAC-SHA1 (WinZip AES key derivation). */
    static byte @NotNull [] pbkdf2(final byte @NotNull [] password, final byte @NotNull [] salt, final int length) throws IOException {
        try {
            final Mac mac = Mac.getInstance("HmacSHA1");
            // HMAC pads the key with zeros: an empty password is the key {0}, which SecretKeySpec accepts
            mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA1"));
            final int hash = mac.getMacLength();
            final byte[] result = new byte[length];
            for (int block = 1, pos = 0; pos < length; block++, pos += hash) {
                mac.update(salt);
                final byte[] u = mac.doFinal(new byte[] {(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
                final byte[] t = u.clone();
                byte[] prev = u;
                for (int i = 1; i < AES_ITERATIONS; i++) {
                    prev = mac.doFinal(prev);
                    for (int j = 0; j < t.length; j++) {
                        t[j] ^= prev[j];
                    }
                }
                System.arraycopy(t, 0, result, pos, Math.min(hash, length - pos));
            }

            return result;
        } catch (final GeneralSecurityException e) {
            throw new IOException("HMAC-SHA1 is not available", e);
        }
    }

    private static byte @NotNull [] readFully(final @NotNull InputStream in, final int n) throws IOException {
        final byte[] data = in.readNBytes(n);
        if (data.length < n) {
            throw new EOFException("Truncated encrypted entry");
        }

        return data;
    }

    private static boolean isUtf8(final byte @NotNull [] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));

            return true;
        } catch (final CharacterCodingException e) {
            return false;
        }
    }

    /** Decrypted data of a known length; the source stream is not read past it. */
    private abstract static class DecryptingStream extends FilterInputStream {

        protected long remaining;

        DecryptingStream(final @NotNull InputStream in, final long length) {
            super(in);
            this.remaining = Math.max(length, 0);
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];

            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(final byte @NotNull [] b, final int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (remaining == 0) {
                finish();

                return -1;
            }
            final int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) {
                throw new EOFException("Truncated encrypted entry");
            }
            remaining -= n;
            decrypt(b, off, n);

            return n;
        }

        /** Skipping still decrypts: the cipher state depends on every byte. */
        @Override
        public long skip(final long n) throws IOException {
            final byte[] buf = new byte[(int) Math.min(Math.max(n, 0), 8192)];
            final int got = read(buf, 0, buf.length);

            return Math.max(got, 0);
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        protected abstract void decrypt(byte @NotNull [] b, int off, int len) throws IOException;

        /** End of the data: e.g. the authentication code check. */
        protected void finish() throws IOException {
            // nothing by default
        }
    }

    /** AES in CTR mode with a little-endian counter starting at 1, HMAC-SHA1 of the encrypted data checked at the end. */
    private static final class AesStream extends DecryptingStream {

        private final Cipher aes;
        private final Mac mac;
        private final @NotNull String name;
        private final byte[] counter = new byte[16];
        private final byte[] keystream = new byte[16];
        private int used = keystream.length;
        private boolean verified;

        AesStream(final @NotNull InputStream in, final byte @NotNull [] key, final byte @NotNull [] macKey, final long length,
            final @NotNull String name) throws IOException {
            super(in, length);
            this.name = name;
            try {
                aes = Cipher.getInstance("AES/ECB/NoPadding");
                aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
                mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(macKey, "HmacSHA1"));
            } catch (final GeneralSecurityException e) {
                throw new IOException("AES is not available", e);
            }
        }

        @Override
        protected void decrypt(final byte @NotNull [] b, final int off, final int len) throws IOException {
            mac.update(b, off, len);
            for (int i = off; i < off + len; i++) {
                if (used == keystream.length) {
                    nextBlock();
                }
                b[i] ^= keystream[used++];
            }
        }

        private void nextBlock() throws IOException {
            for (int i = 0; i < counter.length && ++counter[i] == 0; i++) {
                // carry into the next byte
            }
            try {
                aes.doFinal(counter, 0, counter.length, keystream, 0);
            } catch (final GeneralSecurityException e) {
                throw new IOException(e);
            }
            used = 0;
        }

        @Override
        protected void finish() throws IOException {
            if (verified) {
                return;
            }
            verified = true;
            final byte[] stored = readFully(in, AES_MAC_LENGTH);
            final byte[] computed = Arrays.copyOf(mac.doFinal(), AES_MAC_LENGTH);
            if (!MessageDigest.isEqual(stored, computed)) {
                throw new IOException(name + ": authentication failed, the data is corrupted");
            }
        }
    }

    /** The traditional PKWARE stream cipher: three keys updated with CRC-32 steps. */
    private static final class ZipCryptoStream extends DecryptingStream {

        private static final int[] CRC_TABLE = crcTable();

        private int key0 = 0x12345678;
        private int key1 = 0x23456789;
        private int key2 = 0x34567890;

        ZipCryptoStream(final @NotNull InputStream in, final byte @NotNull [] password, final long length) {
            super(in, length);
            for (final byte b : password) {
                update(b);
            }
        }

        /** Decrypts the 12-byte encryption header in front of the data. */
        byte @NotNull [] header() throws IOException {
            final byte[] header = readFully(in, ZIPCRYPTO_HEADER);
            decrypt(header, 0, header.length);

            return header;
        }

        @Override
        protected void decrypt(final byte @NotNull [] b, final int off, final int len) {
            for (int i = off; i < off + len; i++) {
                final int temp = key2 & 0xFFFF | 2;
                final byte plain = (byte) (b[i] ^ (temp * (temp ^ 1) >>> 8));
                b[i] = plain;
                update(plain);
            }
        }

        private void update(final byte b) {
            key0 = crc(key0, b);
            key1 = (key1 + (key0 & 0xFF)) * 134775813 + 1;
            key2 = crc(key2, (byte) (key1 >>> 24));
        }

        private static int crc(final int crc, final byte b) {
            return crc >>> 8 ^ CRC_TABLE[(crc ^ b) & 0xFF];
        }

        private static int @NotNull [] crcTable() {
            final int[] table = new int[256];
            for (int n = 0; n < table.length; n++) {
                int c = n;
                for (int k = 0; k < 8; k++) {
                    c = (c & 1) != 0 ? 0xEDB88320 ^ c >>> 1 : c >>> 1;
                }
                table[n] = c;
            }

            return table;
        }
    }

    /**
     * At the end of the unpacked data: the decrypted stream is read to its end (so the AES authentication code gets checked even if
     * the decompressor stopped earlier), then the size and the CRC (-1 — not stored) are compared.
     */
    private static final class CheckedStream extends FilterInputStream {

        private final @NotNull InputStream decrypted;
        private final CRC32 crc = new CRC32();
        private final long expectedCrc;
        private final long expectedSize;
        private final @NotNull String name;
        private long size;
        private boolean checked;

        CheckedStream(final @NotNull InputStream in, final @NotNull InputStream decrypted, final long expectedCrc, final long expectedSize,
            final @NotNull String name) {
            super(in);
            this.decrypted = decrypted;
            this.expectedCrc = expectedCrc;
            this.expectedSize = expectedSize;
            this.name = name;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];

            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(final byte @NotNull [] b, final int off, final int len) throws IOException {
            final int n = in.read(b, off, len);
            if (n < 0) {
                check();

                return -1;
            }
            crc.update(b, off, n);
            size += n;

            return n;
        }

        @Override
        public long skip(final long n) throws IOException {
            final byte[] buf = new byte[(int) Math.min(Math.max(n, 0), 8192)];
            final int got = read(buf, 0, buf.length);

            return Math.max(got, 0);
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private void check() throws IOException {
            if (checked) {
                return;
            }
            checked = true;
            decrypted.transferTo(OutputStream.nullOutputStream());
            if (expectedSize >= 0 && size != expectedSize || expectedCrc >= 0 && crc.getValue() != expectedCrc) {
                throw new IOException("CRC error in " + name + " (wrong password?)");
            }
        }
    }
}
