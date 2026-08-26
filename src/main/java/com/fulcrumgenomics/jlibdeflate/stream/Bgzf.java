package com.fulcrumgenomics.jlibdeflate.stream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Constants, virtual offset utilities, format detection, and stream factory methods
 * for the BGZF (Blocked GNU Zip Format) used in bioinformatics (BAM, VCF, etc.).
 *
 * <p>BGZF is a series of concatenated gzip members, each containing an extra field
 * with subfield ID {@code BC} that stores the total block size. Each block compresses
 * at most {@value #DEFAULT_UNCOMPRESSED_BLOCK_SIZE} bytes of uncompressed data.
 *
 * <p>Virtual file offsets encode both the compressed block position (upper 48 bits)
 * and the uncompressed offset within that block (lower 16 bits), enabling random
 * access via external indexes (e.g., BAI, CSI, TBI).
 */
public final class Bgzf {

    /** Non-instantiable utility class. */
    private Bgzf() {}

    /** Maximum total size of a single BGZF block in bytes (header + compressed data + trailer). */
    public static final int MAX_BLOCK_SIZE = 65536;

    /** Size of the BGZF gzip header: 10-byte standard gzip header + 8-byte BGZF extra field. */
    public static final int HEADER_SIZE = 18;

    /** Size of the gzip trailer: 4-byte CRC32 + 4-byte ISIZE. */
    public static final int TRAILER_SIZE = 8;

    /** Minimum valid BGZF block size: header + trailer with no compressed payload. */
    public static final int MIN_BLOCK_SIZE = HEADER_SIZE + TRAILER_SIZE;

    /** Maximum compressed payload that fits in a BGZF block. */
    public static final int MAX_COMPRESSED_DATA = MAX_BLOCK_SIZE - HEADER_SIZE - TRAILER_SIZE;

    /**
     * Default maximum uncompressed data per block. Set conservatively below 64 KB to
     * ensure the compressed output (including header and trailer) always fits within
     * {@link #MAX_BLOCK_SIZE}, even at compression level 0 (stored).
     */
    public static final int DEFAULT_UNCOMPRESSED_BLOCK_SIZE = 65280;

    /**
     * Fixed BGZF header template (18 bytes). Bytes 16–17 are the BSIZE placeholder
     * and must be filled per block with {@code (totalBlockSize - 1)} as a 16-bit
     * little-endian value.
     */
    public static final byte[] HEADER_TEMPLATE = {
        0x1f,
        (byte) 0x8b, // ID1, ID2 (gzip magic)
        0x08, // CM = deflate
        0x04, // FLG = FEXTRA
        0x00,
        0x00,
        0x00,
        0x00, // MTIME
        0x00, // XFL
        (byte) 0xff, // OS = unknown
        0x06,
        0x00, // XLEN = 6 (little-endian)
        0x42,
        0x43, // SI1='B', SI2='C' (BGZF identifier)
        0x02,
        0x00, // SLEN = 2
        0x00,
        0x00 // BSIZE placeholder
    };

    /**
     * Standard 28-byte BGZF EOF marker — an empty BGZF block with BSIZE=27 and ISIZE=0.
     * Every valid BGZF file should end with this marker.
     */
    public static final byte[] EOF_MARKER = {
        0x1f,
        (byte) 0x8b,
        0x08,
        0x04, // gzip header
        0x00,
        0x00,
        0x00,
        0x00, // MTIME
        0x00,
        (byte) 0xff, // XFL, OS
        0x06,
        0x00, // XLEN = 6
        0x42,
        0x43, // SI1='B', SI2='C'
        0x02,
        0x00, // SLEN = 2
        0x1b,
        0x00, // BSIZE = 27 (block size - 1 = 28 - 1)
        0x03,
        0x00, // compressed empty DEFLATE block
        0x00,
        0x00,
        0x00,
        0x00, // CRC32 = 0
        0x00,
        0x00,
        0x00,
        0x00 // ISIZE = 0
    };

    // ---- Virtual offset utilities ----

    /** Maximum value for the compressed block offset component of a virtual file offset. */
    private static final long MAX_BLOCK_OFFSET = (1L << 48) - 1;

    /** Maximum value for the uncompressed within-block offset component of a virtual file offset. */
    private static final int MAX_WITHIN_BLOCK_OFFSET = (1 << 16) - 1;

    /**
     * Creates a virtual file offset from a compressed block offset and an
     * uncompressed offset within the block.
     *
     * @param blockOffset compressed file offset of the block start (must fit in 48 bits)
     * @param withinBlockOffset uncompressed byte offset within the block (0–65535)
     * @return the virtual file offset
     * @throws IllegalArgumentException if either value is out of range
     */
    public static long makeVirtualOffset(long blockOffset, int withinBlockOffset) {
        if (blockOffset < 0 || blockOffset > MAX_BLOCK_OFFSET) {
            throw new IllegalArgumentException(
                    "blockOffset must be in [0, " + MAX_BLOCK_OFFSET + "], got " + blockOffset);
        }
        if (withinBlockOffset < 0 || withinBlockOffset > MAX_WITHIN_BLOCK_OFFSET) {
            throw new IllegalArgumentException(
                    "withinBlockOffset must be in [0, " + MAX_WITHIN_BLOCK_OFFSET + "], got " + withinBlockOffset);
        }
        return (blockOffset << 16) | withinBlockOffset;
    }

    /**
     * Extracts the compressed block offset (upper 48 bits) from a virtual file offset.
     *
     * @param virtualOffset a BGZF virtual file offset
     * @return the compressed file offset of the block start
     */
    public static long blockOffset(long virtualOffset) {
        return virtualOffset >>> 16;
    }

    /**
     * Extracts the uncompressed within-block offset (lower 16 bits) from a virtual file offset.
     *
     * @param virtualOffset a BGZF virtual file offset
     * @return the uncompressed byte offset within the block (0–65535)
     */
    public static int withinBlockOffset(long virtualOffset) {
        return (int) (virtualOffset & 0xFFFF);
    }

    // ---- Format detection ----

    /**
     * Peeks at the stream header to determine if it contains BGZF-formatted data.
     * The stream is left at its original position after this call.
     *
     * @param in an input stream that supports {@link InputStream#mark(int)} and
     *           {@link InputStream#reset()}
     * @return {@code true} if the first bytes match a BGZF header (gzip magic + FEXTRA + BC subfield)
     * @throws IllegalArgumentException if the stream does not support mark/reset
     * @throws IOException on read errors
     */
    public static boolean isBgzf(InputStream in) throws IOException {
        if (!in.markSupported()) {
            throw new IllegalArgumentException(
                    "InputStream must support mark/reset; wrap in BufferedInputStream first");
        }

        in.mark(HEADER_SIZE);
        try {
            byte[] header = new byte[HEADER_SIZE];
            int read = readFully(in, header, 0, HEADER_SIZE);
            if (read < HEADER_SIZE) return false;
            return isBgzfHeader(header);
        } finally {
            in.reset();
        }
    }

    /**
     * Checks whether a byte array contains a valid BGZF header: gzip magic ({@code 0x1F 0x8B}),
     * CM=8 (DEFLATE), FEXTRA flag set, XLEN=6 (BC is the only extra subfield), and
     * SI1={@code 'B'}, SI2={@code 'C'}, SLEN=2.  The XLEN check matches htsjdk's validation
     * and ensures the BSIZE field is at the expected fixed offset (bytes 16–17).
     *
     * @param header a byte array of at least {@link #HEADER_SIZE} bytes to check
     * @return {@code true} if the bytes represent a valid BGZF header
     */
    static boolean isBgzfHeader(byte[] header) {
        return header.length >= HEADER_SIZE
                && (header[0] & 0xFF) == 0x1F
                && (header[1] & 0xFF) == 0x8B // gzip magic
                && header[2] == 0x08 // CM = deflate
                && (header[3] & 0x04) != 0 // FEXTRA flag set
                && (header[10] & 0xFF) == 0x06
                && (header[11] & 0xFF) == 0x00 // XLEN = 6 (BC is the only subfield)
                && header[12] == 0x42
                && header[13] == 0x43 // SI1='B', SI2='C'
                && (header[14] & 0xFF) == 0x02
                && (header[15] & 0xFF) == 0x00; // SLEN = 2
    }

    // ---- Stream factory ----

    /**
     * A function that may throw {@link IOException}, for use with
     * {@link #newGzipInputStream(InputStream, IOFunction)}.
     *
     * @param <T> the input type
     * @param <R> the result type
     */
    @FunctionalInterface
    public interface IOFunction<T, R> {
        /**
         * Applies this function to the given argument.
         *
         * @param t the input argument
         * @return the result
         * @throws IOException if the operation fails due to an I/O error
         */
        R apply(T t) throws IOException;
    }

    /**
     * Returns a {@link BgzfInputStream} if the stream starts with a BGZF header, otherwise
     * delegates to the given factory to create a non-BGZF input stream.
     *
     * <p>If the stream does not support mark/reset, it is automatically wrapped in a
     * {@link BufferedInputStream}.
     *
     * @param in the raw input stream
     * @param nonBgzfFactory creates a decompression stream for non-BGZF gzip data
     * @return a decompressing input stream
     * @throws IOException on I/O errors
     */
    public static InputStream newGzipInputStream(InputStream in, IOFunction<InputStream, InputStream> nonBgzfFactory)
            throws IOException {
        InputStream buffered = in.markSupported() ? in : new BufferedInputStream(in);
        if (isBgzf(buffered)) {
            return new BgzfInputStream(buffered);
        } else {
            return nonBgzfFactory.apply(buffered);
        }
    }

    /**
     * Returns a {@link BgzfInputStream} if the stream starts with a BGZF header, otherwise
     * falls back to {@link GZIPInputStream}.
     *
     * <p>If the stream does not support mark/reset, it is automatically wrapped in a
     * {@link BufferedInputStream}.
     *
     * @param in the raw input stream
     * @return a decompressing input stream
     * @throws IOException on I/O errors
     */
    public static InputStream newGzipInputStream(InputStream in) throws IOException {
        return newGzipInputStream(in, GZIPInputStream::new);
    }

    // ---- Internal helpers ----

    /**
     * Reads exactly {@code len} bytes from the stream into {@code buf}, starting at {@code offset}.
     * Blocks until the requested number of bytes have been read, or EOF is reached.
     *
     * @param in the input stream to read from
     * @param buf the buffer to fill
     * @param offset the start offset in {@code buf}
     * @param len the number of bytes to read
     * @return the number of bytes actually read, which may be less than {@code len} only at EOF
     * @throws IOException on read errors
     */
    static int readFully(InputStream in, byte[] buf, int offset, int len) throws IOException {
        int totalRead = 0;
        while (totalRead < len) {
            int n = in.read(buf, offset + totalRead, len - totalRead);
            if (n < 0) break;
            totalRead += n;
        }
        return totalRead;
    }
}
