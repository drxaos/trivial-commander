package tcm.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tukaani.xz.ARM64Options;
import org.tukaani.xz.ARMOptions;
import org.tukaani.xz.ARMThumbOptions;
import org.tukaani.xz.DeltaInputStream;
import org.tukaani.xz.IA64Options;
import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.PowerPCOptions;
import org.tukaani.xz.RISCVOptions;
import org.tukaani.xz.SPARCOptions;
import org.tukaani.xz.X86Options;

/**
 * The streams of a 7z archive decoded without commons-compress, which cannot unpack folders whose coders have several inputs:
 * BCJ2, 7-Zip's default filter for executables (main, CALL, JUMP and range coder streams). Only the streams info of the header
 * is parsed — the file list still comes from commons-compress; the n-th file with data is the n-th stream here.
 * Coders: Copy, LZMA, LZMA2, BCJ filters, BCJ2, Delta, Deflate, Deflate64, BZip2 and AES-256.
 */
final class SevenZipFolders implements Closeable {

    private static final byte[] SIGNATURE = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    private static final int SIGNATURE_HEADER = 32;
    private static final int MAX_HEADER = 256 * 1024 * 1024;
    private static final int MAX_CYCLES_POWER = 24;

    private static final int K_END = 0x00;
    private static final int K_HEADER = 0x01;
    private static final int K_ARCHIVE_PROPERTIES = 0x02;
    private static final int K_ADDITIONAL_STREAMS_INFO = 0x03;
    private static final int K_MAIN_STREAMS_INFO = 0x04;
    private static final int K_PACK_INFO = 0x06;
    private static final int K_UNPACK_INFO = 0x07;
    private static final int K_SUBSTREAMS_INFO = 0x08;
    private static final int K_SIZE = 0x09;
    private static final int K_CRC = 0x0A;
    private static final int K_FOLDER = 0x0B;
    private static final int K_CODERS_UNPACK_SIZE = 0x0C;
    private static final int K_NUM_UNPACK_STREAM = 0x0D;
    private static final int K_ENCODED_HEADER = 0x17;

    private static final String COPY = "00";
    private static final String DELTA = "03";
    private static final String LZMA = "030101";
    private static final String LZMA2 = "21";
    private static final String BCJ2 = "0303011b";
    private static final String AES = "06f10701";

    private final @NotNull FileChannel channel;
    private final @NotNull BlockSource source;
    private final @NotNull List<Folder> folders;
    /** The streams (files with data) in archive order. */
    private final @NotNull List<SubStream> streams;
    /** Decoded folder kept between entries: a solid folder is read once when its files are opened in order. */
    private @Nullable InputStream current;
    private @Nullable Folder currentFolder;
    private long currentPos;
    private boolean viewOpen;

    private SevenZipFolders(final @NotNull FileChannel channel, final @NotNull BlockSource source, final @NotNull List<Folder> folders) {
        this.channel = channel;
        this.source = source;
        this.folders = folders;
        streams = new ArrayList<>();
        for (final Folder f : folders) {
            long offset = 0;
            for (int i = 0; i < f.streamSizes.length; i++) {
                streams.add(new SubStream(f, offset, f.streamSizes[i], f.streamCrcs[i]));
                offset += f.streamSizes[i];
            }
        }
    }

    /**
     * Parses the streams info of the archive. Encrypted headers need the password: without one PasswordRequiredException,
     * with a wrong one WrongPasswordException.
     */
    static @NotNull SevenZipFolders open(final @NotNull Path archive, final @Nullable String password) throws IOException {
        final FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ);
        try {
            final BlockSource src = BlockSource.of(ch);
            final ByteBuffer start = ByteBuffer.wrap(src.read(0, SIGNATURE_HEADER)).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < SIGNATURE.length; i++) {
                if (start.get(i) != SIGNATURE[i]) {
                    throw new IOException("Not a 7z archive: " + archive.getFileName());
                }
            }
            final long nextOffset = start.getLong(12);
            final long nextSize = start.getLong(20);
            if (nextOffset < 0 || nextSize <= 0 || nextSize > MAX_HEADER || SIGNATURE_HEADER + nextOffset + nextSize > src.size()) {
                throw new IOException("Damaged 7z header: " + archive.getFileName());
            }
            final byte[] next = src.read(SIGNATURE_HEADER + nextOffset, (int) nextSize);
            if (crc(next, next.length) != (start.getInt(28) & 0xFFFFFFFFL)) {
                throw new IOException("7z header CRC error: " + archive.getFileName());
            }
            final List<Folder> folders = parseHeader(src, next, password, String.valueOf(archive.getFileName()));

            return new SevenZipFolders(ch, src, folders);
        } catch (final BufferUnderflowException | IndexOutOfBoundsException | NegativeArraySizeException e) {
            ch.close();
            throw new IOException("Damaged 7z header: " + archive.getFileName(), e);
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    private static @NotNull List<Folder> parseHeader(final @NotNull BlockSource src, final byte @NotNull [] first,
        final @Nullable String password, final @NotNull String name) throws IOException {
        byte[] header = first;
        while (true) {
            final ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            final int id = u8(b);
            if (id == K_HEADER) {
                return readHeader(b);
            }
            if (id != K_ENCODED_HEADER) {
                throw new IOException("Damaged 7z header: " + name);
            }
            final List<Folder> encoded = readStreamsInfo(b);
            if (encoded.isEmpty()) {
                throw new IOException("Damaged 7z header: " + name);
            }
            final Folder f = encoded.get(0);
            if (f.needsPassword() && password == null) {
                throw new PasswordRequiredException(name);
            }
            final long size = f.size();
            if (size <= 0 || size > MAX_HEADER) {
                throw new IOException("Damaged 7z header: " + name);
            }
            try (InputStream in = decode(src, f, password)) {
                header = in.readNBytes((int) size);
            } catch (final IOException | RuntimeException e) {
                if (f.needsPassword()) {
                    throw new WrongPasswordException(name);
                }
                throw e;
            }
            if (header.length != size || f.crc >= 0 && crc(header, header.length) != f.crc) {
                if (f.needsPassword()) {
                    throw new WrongPasswordException(name);
                }
                throw new IOException("7z header CRC error: " + name);
            }
        }
    }

    private static @NotNull List<Folder> readHeader(final @NotNull ByteBuffer b) throws IOException {
        int id = u8(b);
        if (id == K_ARCHIVE_PROPERTIES) {
            while (u8(b) != K_END) {
                skip(b, number(b));
            }
            id = u8(b);
        }
        if (id == K_ADDITIONAL_STREAMS_INFO) {
            readStreamsInfo(b);
            id = u8(b);
        }

        return id == K_MAIN_STREAMS_INFO ? readStreamsInfo(b) : List.of();
    }

    private static @NotNull List<Folder> readStreamsInfo(final @NotNull ByteBuffer b) throws IOException {
        long packPos = 0;
        long[] packSizes = new long[0];
        List<Folder> folders = List.of();
        int id = u8(b);
        if (id == K_PACK_INFO) {
            packPos = number(b);
            packSizes = new long[count(b)];
            for (id = u8(b); id != K_END; id = u8(b)) {
                if (id == K_SIZE) {
                    for (int i = 0; i < packSizes.length; i++) {
                        packSizes[i] = number(b);
                    }
                } else if (id == K_CRC) {
                    digests(b, packSizes.length);
                } else {
                    throw new IOException("Unexpected 7z property " + id);
                }
            }
            id = u8(b);
        }
        if (id == K_UNPACK_INFO) {
            folders = readUnpackInfo(b);
            id = u8(b);
        }
        if (id == K_SUBSTREAMS_INFO) {
            readSubStreamsInfo(b, folders);
            id = u8(b);
        } else {
            for (final Folder f : folders) {
                f.streamSizes = new long[] {f.size()};
                f.streamCrcs = new long[] {f.crc};
            }
        }
        if (id != K_END) {
            throw new IOException("Unexpected 7z property " + id);
        }
        long offset = SIGNATURE_HEADER + packPos;
        int pack = 0;
        for (final Folder f : folders) {
            f.packOffsets = new long[f.packed.length];
            f.packSizes = new long[f.packed.length];
            for (int i = 0; i < f.packed.length; i++) {
                if (pack >= packSizes.length) {
                    throw new IOException("Damaged 7z header: pack streams");
                }
                f.packOffsets[i] = offset;
                f.packSizes[i] = packSizes[pack];
                offset += packSizes[pack++];
            }
        }

        return folders;
    }

    private static @NotNull List<Folder> readUnpackInfo(final @NotNull ByteBuffer b) throws IOException {
        expect(b, K_FOLDER);
        final int n = count(b);
        if (u8(b) != 0) {
            throw new IOException("7z external folders are not supported");
        }
        final List<Folder> folders = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            folders.add(readFolder(b));
        }
        expect(b, K_CODERS_UNPACK_SIZE);
        for (final Folder f : folders) {
            f.unpackSizes = new long[f.totalOut];
            for (int i = 0; i < f.totalOut; i++) {
                f.unpackSizes[i] = number(b);
            }
        }
        for (int id = u8(b); id != K_END; id = u8(b)) {
            if (id != K_CRC) {
                throw new IOException("Unexpected 7z property " + id);
            }
            final long[] crcs = digests(b, n);
            for (int i = 0; i < n; i++) {
                folders.get(i).crc = crcs[i];
            }
        }

        return folders;
    }

    private static @NotNull Folder readFolder(final @NotNull ByteBuffer b) throws IOException {
        final Folder f = new Folder();
        f.coders = new Coder[count(b)];
        if (f.coders.length == 0 || f.coders.length > 64) {
            throw new IOException("Damaged 7z header: coders");
        }
        for (int i = 0; i < f.coders.length; i++) {
            final int flags = u8(b);
            if ((flags & 0x80) != 0) {
                throw new IOException("7z alternative coder methods are not supported");
            }
            final byte[] id = new byte[flags & 0x0F];
            b.get(id);
            final boolean complex = (flags & 0x10) != 0;
            final int numIn = complex ? count(b) : 1;
            final int numOut = complex ? count(b) : 1;
            final byte[] props = new byte[(flags & 0x20) != 0 ? count(b) : 0];
            b.get(props);
            f.coders[i] = new Coder(HexFormat.of().formatHex(id), numIn, numOut, props, f.totalIn, f.totalOut);
            f.totalIn += numIn;
            f.totalOut += numOut;
        }
        if (f.totalOut == 0 || f.totalIn > 64 || f.totalOut > 64) {
            throw new IOException("Damaged 7z header: coders");
        }
        f.bindIn = new int[f.totalOut - 1];
        f.bindOut = new int[f.totalOut - 1];
        for (int i = 0; i < f.bindIn.length; i++) {
            f.bindIn[i] = index(b, f.totalIn);
            f.bindOut[i] = index(b, f.totalOut);
        }
        final int numPacked = f.totalIn - f.bindIn.length;
        if (numPacked < 1) {
            throw new IOException("Damaged 7z header: packed streams");
        }
        f.packed = new int[numPacked];
        if (numPacked == 1) {
            f.packed[0] = -1;
            for (int i = 0; i < f.totalIn && f.packed[0] < 0; i++) {
                if (f.bindForIn(i) < 0) {
                    f.packed[0] = i;
                }
            }
            if (f.packed[0] < 0) {
                throw new IOException("Damaged 7z header: packed streams");
            }
        } else {
            for (int i = 0; i < numPacked; i++) {
                f.packed[i] = index(b, f.totalIn);
            }
        }

        return f;
    }

    private static void readSubStreamsInfo(final @NotNull ByteBuffer b, final @NotNull List<Folder> folders) throws IOException {
        final int[] counts = new int[folders.size()];
        Arrays.fill(counts, 1);
        int id = u8(b);
        if (id == K_NUM_UNPACK_STREAM) {
            for (int i = 0; i < counts.length; i++) {
                counts[i] = count(b);
            }
            id = u8(b);
        }
        final boolean sizes = id == K_SIZE;
        for (int i = 0; i < counts.length; i++) {
            final Folder f = folders.get(i);
            f.streamSizes = new long[counts[i]];
            f.streamCrcs = new long[counts[i]];
            Arrays.fill(f.streamCrcs, -1);
            if (counts[i] == 0) {
                continue;
            }
            long sum = 0;
            if (sizes) {
                for (int j = 0; j < counts[i] - 1; j++) {
                    f.streamSizes[j] = number(b);
                    sum += f.streamSizes[j];
                }
            }
            f.streamSizes[counts[i] - 1] = f.size() - sum;
            if (f.streamSizes[counts[i] - 1] < 0) {
                throw new IOException("Damaged 7z header: stream sizes");
            }
            if (counts[i] == 1) {
                f.streamCrcs[0] = f.crc;
            }
        }
        if (sizes) {
            id = u8(b);
        }
        for (; id != K_END; id = u8(b)) {
            if (id != K_CRC) {
                skip(b, number(b));
                continue;
            }
            int unknown = 0;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] != 1 || folders.get(i).crc < 0) {
                    unknown += counts[i];
                }
            }
            final long[] crcs = digests(b, unknown);
            int k = 0;
            for (int i = 0; i < counts.length; i++) {
                final Folder f = folders.get(i);
                if (counts[i] != 1 || f.crc < 0) {
                    for (int j = 0; j < counts[i]; j++) {
                        f.streamCrcs[j] = crcs[k++];
                    }
                }
            }
        }
    }

    /** CRCs of n items; -1 for an item without one. */
    private static long @NotNull [] digests(final @NotNull ByteBuffer b, final int n) {
        final boolean[] defined = bits(b, n);
        final long[] crcs = new long[n];
        for (int i = 0; i < n; i++) {
            crcs[i] = defined[i] ? b.getInt() & 0xFFFFFFFFL : -1;
        }

        return crcs;
    }

    private static boolean @NotNull [] bits(final @NotNull ByteBuffer b, final int n) {
        final boolean[] r = new boolean[n];
        if (u8(b) != 0) {
            Arrays.fill(r, true);

            return r;
        }
        int mask = 0;
        int cur = 0;
        for (int i = 0; i < n; i++) {
            if (mask == 0) {
                cur = u8(b);
                mask = 0x80;
            }
            r[i] = (cur & mask) != 0;
            mask >>>= 1;
        }

        return r;
    }

    private static long number(final @NotNull ByteBuffer b) {
        final int first = u8(b);
        int mask = 0x80;
        long value = 0;
        for (int i = 0; i < 8; i++) {
            if ((first & mask) == 0) {
                return value | (long) (first & mask - 1) << 8 * i;
            }
            value |= (long) u8(b) << 8 * i;
            mask >>>= 1;
        }

        return value;
    }

    private static int count(final @NotNull ByteBuffer b) throws IOException {
        final long n = number(b);
        if (n < 0 || n > b.capacity() + 64L) {
            throw new IOException("Damaged 7z header: count " + n);
        }

        return (int) n;
    }

    private static int index(final @NotNull ByteBuffer b, final int limit) throws IOException {
        final long i = number(b);
        if (i < 0 || i >= limit) {
            throw new IOException("Damaged 7z header: index " + i);
        }

        return (int) i;
    }

    private static void expect(final @NotNull ByteBuffer b, final int id) throws IOException {
        final int got = u8(b);
        if (got != id) {
            throw new IOException("Unexpected 7z property " + got + ", expected " + id);
        }
    }

    private static void skip(final @NotNull ByteBuffer b, final long n) throws IOException {
        if (n < 0 || n > b.remaining()) {
            throw new IOException("Damaged 7z header");
        }
        b.position(b.position() + (int) n);
    }

    private static int u8(final @NotNull ByteBuffer b) {
        return b.get() & 0xFF;
    }

    private static long crc(final byte @NotNull [] data, final int len) {
        final CRC32 c = new CRC32();
        c.update(data, 0, len);

        return c.getValue();
    }

    // ---- streams ----

    int streamCount() {
        return streams.size();
    }

    /** The folder of the n-th stream has coders commons-compress cannot decode. */
    boolean isComplex(final int stream) {
        return stream >= 0 && stream < streams.size() && streams.get(stream).folder.isComplex();
    }

    boolean needsPassword(final int stream) {
        return streams.get(stream).folder.needsPassword();
    }

    /** The content of the n-th stream, its CRC is checked at the end; closed by the caller. */
    synchronized @NotNull InputStream open(final int stream, final @Nullable String password) throws IOException {
        final SubStream s = streams.get(stream);
        if (s.folder.needsPassword() && password == null) {
            throw new PasswordRequiredException(String.valueOf(stream));
        }
        if (current == null || currentFolder != s.folder || currentPos > s.offset || viewOpen) {
            dropCurrent();
            current = decode(source, s.folder, password);
            currentFolder = s.folder;
            currentPos = 0;
        }
        try {
            current.skipNBytes(s.offset - currentPos);
        } catch (final IOException | RuntimeException e) {
            dropCurrent();
            throw e;
        }
        currentPos = s.offset;
        viewOpen = true;

        return new View(s);
    }

    private void dropCurrent() {
        final InputStream in = current;
        current = null;
        currentFolder = null;
        viewOpen = false;
        if (in != null) {
            try {
                in.close();
            } catch (final IOException e) {
                // the decoder is discarded anyway
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        dropCurrent();
        channel.close();
    }

    /** The folder's final output: the coder output not bound to another coder's input. */
    private static @NotNull InputStream decode(final @NotNull BlockSource src, final @NotNull Folder f, final @Nullable String password)
        throws IOException {
        for (int out = 0; out < f.totalOut; out++) {
            if (f.bindForOut(out) < 0) {
                return output(src, f, out, password, 0);
            }
        }
        throw new IOException("Damaged 7z header: no folder output");
    }

    private static @NotNull InputStream output(final @NotNull BlockSource src, final @NotNull Folder f, final int out,
        final @Nullable String password, final int depth) throws IOException {
        if (depth > f.coders.length) {
            throw new IOException("Damaged 7z header: coder cycle");
        }
        Coder coder = null;
        for (final Coder c : f.coders) {
            if (out >= c.outStart && out < c.outStart + c.numOut) {
                coder = c;
            }
        }
        if (coder == null || coder.numOut != 1) {
            throw new IOException("Unsupported 7z coder layout");
        }
        final InputStream[] inputs = new InputStream[coder.numIn];
        try {
            for (int i = 0; i < coder.numIn; i++) {
                inputs[i] = input(src, f, coder.inStart + i, password, depth);
            }

            return limit(coder(coder, inputs, f.unpackSizes[out], password), f.unpackSizes[out]);
        } catch (final IOException | RuntimeException e) {
            for (final InputStream in : inputs) {
                if (in != null) {
                    in.close();
                }
            }
            throw e;
        }
    }

    private static @NotNull InputStream input(final @NotNull BlockSource src, final @NotNull Folder f, final int in,
        final @Nullable String password, final int depth) throws IOException {
        for (int p = 0; p < f.packed.length; p++) {
            if (f.packed[p] == in) {
                if (f.packOffsets[p] + f.packSizes[p] > src.size()) {
                    throw new EOFException("7z packed stream past the end of the archive");
                }

                return new BufferedInputStream(BlockSource.stream(src, f.packOffsets[p], f.packSizes[p]), 64 * 1024);
            }
        }
        final int bind = f.bindForIn(in);
        if (bind < 0) {
            throw new IOException("Damaged 7z header: unbound coder input");
        }

        return output(src, f, f.bindOut[bind], password, depth + 1);
    }

    private static @NotNull InputStream coder(final @NotNull Coder c, final @NotNull InputStream @NotNull [] in, final long size,
        final @Nullable String password) throws IOException {
        if (c.id.equals(BCJ2)) {
            if (in.length != 4) {
                throw new IOException("Unsupported 7z BCJ2 layout");
            }

            return new Bcj2InputStream(in[0], in[1], in[2], in[3], size);
        }
        if (in.length != 1) {
            throw new IOException("Unsupported 7z coder " + c.id);
        }
        final InputStream i = in[0];
        final byte[] p = c.props;
        switch (c.id) {
            case COPY:
                return i;
            case LZMA:
                if (p.length < 5) {
                    throw new IOException("Damaged 7z LZMA properties");
                }

                return new LZMAInputStream(i, size, p[0], ByteBuffer.wrap(p, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            case LZMA2:
                if (p.length < 1 || (p[0] & 0xFF) > 40) {
                    throw new IOException("Damaged 7z LZMA2 properties");
                }

                final long dict = p[0] == 40 ? 0xFFFFFFFFL : (2L | p[0] & 1) << (p[0] / 2 + 11);

                return new LZMA2InputStream(i, (int) Math.min(dict, LZMA2InputStream.DICT_SIZE_MAX));
            case DELTA:
                return new DeltaInputStream(i, p.length > 0 ? (p[0] & 0xFF) + 1 : 1);
            case "03030103": {
                final X86Options o = new X86Options();
                o.setStartOffset(startOffset(p));

                return o.getInputStream(i);
            }
            case "03030205": {
                final PowerPCOptions o = new PowerPCOptions();
                o.setStartOffset(startOffset(p));

                return o.getInputStream(i);
            }
            case "03030401": {
                final IA64Options o = new IA64Options();
                o.setStartOffset(startOffset(p));

                return o.getInputStream(i);
            }
            case "03030501": {
                final ARMOptions o = new ARMOptions();
                o.setStartOffset(startOffset(p));

                return o.getInputStream(i);
            }
            case "03030701": {
                final ARMThumbOptions o = new ARMThumbOptions();
                o.setStartOffset(startOffset(p));

                return o.getInputStream(i);
            }
            case "03030805": {
                final SPARCOptions o = new SPARCOptions();
                o.setStartOffset(startOffset(p));

                return o.getInputStream(i);
            }
            case "0a": {
                final ARM64Options o = new ARM64Options();
                o.setStartOffset(startOffset(p));

                return o.getInputStream(i);
            }
            case "0b": {
                final RISCVOptions o = new RISCVOptions();
                o.setStartOffset(startOffset(p));

                return o.getInputStream(i);
            }
            case "040108":
                return new InflaterInputStream(i, new Inflater(true), 64 * 1024);
            case "040109":
                return new Deflate64CompressorInputStream(i);
            case "040202":
                return new BZip2CompressorInputStream(i);
            case AES:
                return aes(i, p, password);
            default:
                throw new IOException("Unsupported 7z method " + c.id);
        }
    }

    /** Branch converters may carry the start offset as their property. */
    private static int startOffset(final byte @NotNull [] p) {
        return p.length >= 4 ? ByteBuffer.wrap(p, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() : 0;
    }

    /** 7-Zip AES-256-CBC: the key is SHA-256 of 2^cycles rounds of salt + UTF-16LE password + a 64-bit counter. */
    private static @NotNull InputStream aes(final @NotNull InputStream in, final byte @NotNull [] p, final @Nullable String password)
        throws IOException {
        if (password == null) {
            throw new PasswordRequiredException("7z");
        }
        if (p.length < 1) {
            throw new IOException("Damaged 7z AES properties");
        }
        final int b0 = p[0] & 0xFF;
        final int cycles = b0 & 0x3F;
        byte[] salt = new byte[0];
        final byte[] iv = new byte[16];
        if ((b0 & 0xC0) != 0) {
            if (p.length < 2) {
                throw new IOException("Damaged 7z AES properties");
            }
            final int b1 = p[1] & 0xFF;
            final int saltSize = (b0 >>> 7 & 1) + (b1 >>> 4);
            final int ivSize = (b0 >>> 6 & 1) + (b1 & 0x0F);
            if (p.length < 2 + saltSize + ivSize) {
                throw new IOException("Damaged 7z AES properties");
            }
            salt = Arrays.copyOfRange(p, 2, 2 + saltSize);
            System.arraycopy(p, 2 + saltSize, iv, 0, ivSize);
        }
        final byte[] pw = password.getBytes(StandardCharsets.UTF_16LE);
        final byte[] key;
        if (cycles == 0x3F) {
            key = new byte[32];
            System.arraycopy(salt, 0, key, 0, Math.min(salt.length, 32));
            System.arraycopy(pw, 0, key, Math.min(salt.length, 32), Math.min(pw.length, 32 - Math.min(salt.length, 32)));
        } else {
            if (cycles > MAX_CYCLES_POWER) {
                throw new IOException("Unsupported 7z AES key derivation: 2^" + cycles + " rounds");
            }
            try {
                final MessageDigest sha = MessageDigest.getInstance("SHA-256");
                final byte[] counter = new byte[8];
                for (long round = 0; round < 1L << cycles; round++) {
                    sha.update(salt);
                    sha.update(pw);
                    sha.update(counter);
                    for (int k = 0; k < counter.length && ++counter[k] == 0; k++) {
                        // carry into the next byte
                    }
                }
                key = sha.digest();
            } catch (final GeneralSecurityException e) {
                throw new IOException(e);
            }
        }
        try {
            final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));

            return new CipherInputStream(in, cipher);
        } catch (final GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Exactly size bytes of a coder's output: AES pads the data, LZMA2 does not know the size. */
    private static @NotNull InputStream limit(final @NotNull InputStream in, final long size) {
        return new InputStream() {
            private long left = size;

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
                if (left <= 0) {
                    return -1;
                }
                final int n = in.read(b, off, (int) Math.min(len, left));
                if (n < 0) {
                    throw new EOFException("Truncated 7z data");
                }
                left -= n;

                return n;
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
    }

    /** One stream of the shared folder decoder; checks the CRC at the end. Closing it keeps the decoder for the next stream. */
    private final class View extends InputStream {

        private final @NotNull SubStream stream;
        private final CRC32 crc = new CRC32();
        private long left;
        private boolean closed;

        private View(final @NotNull SubStream stream) {
            this.stream = stream;
            left = stream.size;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];

            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(final byte @NotNull [] b, final int off, final int len) throws IOException {
            synchronized (SevenZipFolders.this) {
                if (closed || current == null) {
                    throw new IOException("Stream closed");
                }
                if (len == 0) {
                    return 0;
                }
                if (left <= 0) {
                    return -1;
                }
                final int n;
                try {
                    n = current.read(b, off, (int) Math.min(len, left));
                    if (n < 0) {
                        throw new EOFException("Truncated 7z data");
                    }
                } catch (final IOException | RuntimeException e) {
                    dropCurrent();
                    throw e;
                }
                crc.update(b, off, n);
                left -= n;
                currentPos += n;
                if (left == 0 && stream.crc >= 0 && crc.getValue() != stream.crc) {
                    dropCurrent();
                    throw new IOException("CRC error");
                }

                return n;
            }
        }

        @Override
        public void close() {
            synchronized (SevenZipFolders.this) {
                if (!closed) {
                    closed = true;
                    viewOpen = false;
                }
            }
        }
    }

    private static final class Coder {

        private final @NotNull String id;
        private final int numIn;
        private final int numOut;
        private final byte @NotNull [] props;
        private final int inStart;
        private final int outStart;

        private Coder(final @NotNull String id, final int numIn, final int numOut, final byte @NotNull [] props, final int inStart,
            final int outStart) {
            this.id = id;
            this.numIn = numIn;
            this.numOut = numOut;
            this.props = props;
            this.inStart = inStart;
            this.outStart = outStart;
        }
    }

    private static final class Folder {

        private Coder[] coders;
        private int totalIn;
        private int totalOut;
        private int[] bindIn;
        private int[] bindOut;
        private int[] packed;
        private long[] packOffsets;
        private long[] packSizes;
        private long[] unpackSizes;
        private long crc = -1;
        private long[] streamSizes;
        private long[] streamCrcs;

        private int bindForIn(final int in) {
            for (int i = 0; i < bindIn.length; i++) {
                if (bindIn[i] == in) {
                    return i;
                }
            }

            return -1;
        }

        private int bindForOut(final int out) {
            for (int i = 0; i < bindOut.length; i++) {
                if (bindOut[i] == out) {
                    return i;
                }
            }

            return -1;
        }

        private long size() {
            for (int out = 0; out < totalOut; out++) {
                if (bindForOut(out) < 0) {
                    return unpackSizes[out];
                }
            }

            return 0;
        }

        private boolean isComplex() {
            for (final Coder c : coders) {
                if (c.numIn != 1 || c.numOut != 1) {
                    return true;
                }
            }

            return false;
        }

        private boolean needsPassword() {
            for (final Coder c : coders) {
                if (c.id.equals(AES)) {
                    return true;
                }
            }

            return false;
        }
    }

    private static final class SubStream {

        private final @NotNull Folder folder;
        private final long offset;
        private final long size;
        private final long crc;

        private SubStream(final @NotNull Folder folder, final long offset, final long size, final long crc) {
            this.folder = folder;
            this.offset = offset;
            this.size = size;
            this.crc = crc;
        }
    }
}
