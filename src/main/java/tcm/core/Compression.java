package tcm.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jetbrains.annotations.NotNull;
import org.tukaani.xz.LZMAInputStream;

import tcm.util.Zstd;

/** Members of packages (.deb, .rpm) compressed by whatever the packager chose: recognized by signature, not by name. */
final class Compression {

    private static final int PROBE = 6;
    /** Zstandard is decoded in memory: the whole output must fit into an array. */
    private static final int ZSTD_MAX = Integer.MAX_VALUE - 16;

    private Compression() {
    }

    /** The decompressed stream: gzip, bzip2, xz, lzma (alone), zstd; anything else is returned as is. */
    static @NotNull InputStream decompress(final @NotNull InputStream raw) throws IOException {
        final BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(PROBE);
        final byte[] h = in.readNBytes(PROBE);
        in.reset();
        if (h.length >= 2 && (h[0] & 0xFF) == 0x1F && (h[1] & 0xFF) == 0x8B) {
            return new GzipCompressorInputStream(in, true);
        }
        if (h.length >= 3 && h[0] == 'B' && h[1] == 'Z' && h[2] == 'h') {
            return new BZip2CompressorInputStream(in, true);
        }
        if (h.length >= 6 && (h[0] & 0xFF) == 0xFD && h[1] == '7' && h[2] == 'z' && h[3] == 'X' && h[4] == 'Z' && h[5] == 0) {
            return new XZCompressorInputStream(in, true);
        }
        if (h.length >= 4 && (h[0] & 0xFF) == 0x28 && (h[1] & 0xFF) == 0xB5 && (h[2] & 0xFF) == 0x2F && (h[3] & 0xFF) == 0xFD) {
            return new ByteArrayInputStream(Zstd.decompress(in.readAllBytes(), ZSTD_MAX));
        }
        // lzma "alone": properties byte 0x5D (lc=3, lp=0, pb=2) and a power-of-two dictionary size
        if (h.length >= 3 && (h[0] & 0xFF) == 0x5D && h[1] == 0 && h[2] == 0) {
            return new LZMAInputStream(in);
        }

        return in;
    }

    /** Copies the stream into a temporary file that is deleted when the returned channel is closed. */
    static @NotNull FileChannel spill(final @NotNull InputStream in) throws IOException {
        final Path file = Files.createTempFile("tcm-", ".tmp");
        final FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
        try {
            in.transferTo(Channels.newOutputStream(ch));
            ch.position(0);
        } catch (final IOException | RuntimeException e) {
            ch.close();
            throw e;
        }

        return ch;
    }
}
