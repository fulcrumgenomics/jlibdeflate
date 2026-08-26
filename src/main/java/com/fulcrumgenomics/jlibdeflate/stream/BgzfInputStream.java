package com.fulcrumgenomics.jlibdeflate.stream;

import com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor;
import com.fulcrumgenomics.jlibdeflate.LibdeflateException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * An {@link InputStream} that reads BGZF (Blocked GNU Zip Format) data.
 *
 * <p>BGZF data consists of concatenated gzip members, each with a {@code BC} extra field
 * declaring the block size. This stream reads one block at a time, decompresses it with
 * libdeflate, and serves the uncompressed bytes sequentially.
 *
 * <p>Instances are <b>not</b> thread-safe. Call {@link #close()} when done to release
 * the native decompressor.
 *
 * <p>Example:
 * <pre>{@code
 * try (var in = new BgzfInputStream(new FileInputStream("input.bam"))) {
 *     long pos = in.bgzfPosition(); // virtual offset before this read
 *     int b = in.read();
 * }
 * }</pre>
 */
public class BgzfInputStream extends InputStream {

    /** The underlying input stream containing compressed BGZF data. */
    private final InputStream in;

    /** The libdeflate decompressor used to inflate each block's DEFLATE payload. */
    private final LibdeflateDecompressor decompressor;

    /** Reusable CRC32 instance for verifying each block's checksum. */
    private final CRC32 crc = new CRC32();

    /** Buffer for reading one complete compressed BGZF block (header + payload + trailer). */
    private final byte[] blockBuffer = new byte[Bgzf.MAX_BLOCK_SIZE];

    /** Little-endian view of {@link #blockBuffer} for reading header and trailer fields. */
    private final ByteBuffer blockBuf = ByteBuffer.wrap(blockBuffer).order(ByteOrder.LITTLE_ENDIAN);

    /** Buffer holding the decompressed data from the current block. */
    private final byte[] uncompressedBuffer = new byte[Bgzf.MAX_BLOCK_SIZE];

    /** Current read position within {@link #uncompressedBuffer}. */
    private int uncompressedOffset;

    /** Number of valid decompressed bytes in {@link #uncompressedBuffer}. */
    private int uncompressedLimit;

    /** Compressed file offset of the block currently being served to the caller. */
    private long currentBlockStart;

    /** Total compressed bytes consumed so far; equals the compressed offset of the next block. */
    private long compressedFilePosition;

    /** Whether the EOF marker block has been reached or the underlying stream is exhausted. */
    private boolean eof;

    /** Whether {@link #close()} has been called. */
    private boolean closed;

    /**
     * Creates a new BGZF input stream.
     *
     * @param in the underlying input stream containing BGZF data
     */
    public BgzfInputStream(InputStream in) {
        this.in = in;
        this.decompressor = new LibdeflateDecompressor();
        this.uncompressedOffset = 0;
        this.uncompressedLimit = 0;
        this.currentBlockStart = 0;
        this.compressedFilePosition = 0;
        this.eof = false;
        this.closed = false;
    }

    /**
     * Returns the BGZF virtual file offset for the next byte that will be read.
     * The upper 48 bits are the compressed block offset and the lower 16 bits are
     * the uncompressed offset within that block.
     *
     * @return the current virtual file offset
     * @throws IllegalStateException if the stream has been closed
     */
    public long bgzfPosition() {
        ensureOpen();
        return Bgzf.makeVirtualOffset(currentBlockStart, uncompressedOffset);
    }

    /**
     * Reads a single uncompressed byte. Loads the next BGZF block from the
     * underlying stream if the current block is exhausted.
     *
     * @return the byte read (0–255), or -1 if the end of the BGZF stream has been reached
     * @throws IOException on I/O or decompression errors
     * @throws IllegalStateException if the stream has been closed
     */
    @Override
    public int read() throws IOException {
        ensureOpen();
        if (!ensureBlock()) return -1;
        return uncompressedBuffer[uncompressedOffset++] & 0xFF;
    }

    /**
     * Reads up to {@code len} uncompressed bytes into a byte array. May read fewer
     * bytes if fewer are immediately available, but always reads at least one byte
     * (or returns -1 at EOF). Transparently decompresses BGZF blocks as needed.
     *
     * @param b the destination byte array
     * @param off the start offset in {@code b}
     * @param len the maximum number of bytes to read
     * @return the number of bytes read, or -1 if the end of the BGZF stream has been reached
     * @throws IOException on I/O or decompression errors
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are out of range
     * @throws IllegalStateException if the stream has been closed
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException("off=" + off + ", len=" + len + ", b.length=" + b.length);
        }
        if (len == 0) return 0;

        int totalRead = 0;
        while (totalRead < len) {
            if (!ensureBlock()) break;
            int available = uncompressedLimit - uncompressedOffset;
            int toCopy = Math.min(len - totalRead, available);
            System.arraycopy(uncompressedBuffer, uncompressedOffset, b, off + totalRead, toCopy);
            uncompressedOffset += toCopy;
            totalRead += toCopy;
        }
        return totalRead > 0 ? totalRead : -1;
    }

    /**
     * Returns the number of uncompressed bytes remaining in the current block that
     * can be read without triggering another block read from the underlying stream.
     *
     * @return the number of immediately available bytes, or 0 if the current block
     *         is exhausted, the stream is closed, or EOF has been reached
     */
    @Override
    public int available() {
        if (closed || eof) return 0;
        return uncompressedLimit - uncompressedOffset;
    }

    /**
     * Closes this stream, releasing the native decompressor and closing the
     * underlying input stream. Subsequent reads will throw {@link IllegalStateException}.
     * This method is idempotent.
     *
     * @throws IOException if closing the underlying stream fails
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            decompressor.close();
        } finally {
            in.close();
        }
    }

    /**
     * Ensures there is decompressed data available to read. If the current block is
     * exhausted, reads and decompresses the next block.
     *
     * @return {@code true} if data is available, {@code false} at EOF
     * @throws IOException on I/O or decompression errors
     */
    private boolean ensureBlock() throws IOException {
        if (uncompressedOffset < uncompressedLimit) return true;
        return readNextBlock();
    }

    /**
     * Reads the next BGZF block from the underlying stream, validates the header,
     * decompresses the DEFLATE payload, and verifies the CRC32 checksum.
     *
     * @return {@code true} if a block was successfully read and decompressed,
     *         {@code false} if there are no more blocks (EOF marker or end of stream)
     * @throws IOException on I/O errors, invalid BGZF headers, truncated blocks,
     *         decompression failures, or CRC32 mismatches
     */
    private boolean readNextBlock() throws IOException {
        if (eof) return false;

        // Record where this block starts in the compressed stream
        currentBlockStart = compressedFilePosition;

        // Read the 18-byte header
        int headerRead = Bgzf.readFully(in, blockBuffer, 0, Bgzf.HEADER_SIZE);
        if (headerRead == 0) {
            eof = true;
            return false;
        }
        if (headerRead < Bgzf.HEADER_SIZE) {
            throw new IOException("Truncated BGZF header: expected " + Bgzf.HEADER_SIZE + " bytes, got " + headerRead);
        }

        // Validate header
        if (!Bgzf.isBgzfHeader(blockBuffer)) {
            throw new IOException("Invalid BGZF header at compressed offset " + compressedFilePosition);
        }

        // Extract BSIZE (total block size - 1) as unsigned 16-bit LE
        int bsize = blockBuf.getShort(16) & 0xFFFF;
        int totalBlockSize = bsize + 1;

        if (totalBlockSize < Bgzf.MIN_BLOCK_SIZE) {
            throw new IOException(
                    "BGZF block size " + totalBlockSize + " is too small (minimum " + Bgzf.MIN_BLOCK_SIZE + ")");
        }
        if (totalBlockSize > Bgzf.MAX_BLOCK_SIZE) {
            throw new IOException("BGZF block size " + totalBlockSize + " exceeds maximum " + Bgzf.MAX_BLOCK_SIZE);
        }

        // Read the remaining bytes (compressed data + trailer)
        int remainingBytes = totalBlockSize - Bgzf.HEADER_SIZE;
        int remainingRead = Bgzf.readFully(in, blockBuffer, Bgzf.HEADER_SIZE, remainingBytes);
        if (remainingRead < remainingBytes) {
            throw new IOException("Truncated BGZF block at compressed offset " + compressedFilePosition);
        }

        // Extract CRC32 and ISIZE from trailer using the LE ByteBuffer view
        int trailerStart = totalBlockSize - Bgzf.TRAILER_SIZE;
        int expectedCrc = blockBuf.getInt(trailerStart);
        int isize = blockBuf.getInt(trailerStart + 4);

        // A zero ISIZE signals end-of-data. The canonical EOF marker is the specific
        // 28-byte sequence in Bgzf.EOF_MARKER, but any block with ISIZE=0 is treated as EOF
        // since a zero-byte uncompressed payload carries no data.
        if (isize == 0) {
            eof = true;
            compressedFilePosition += totalBlockSize;
            return false;
        }

        // Decompress the raw DEFLATE payload
        int compressedDataLen = totalBlockSize - Bgzf.HEADER_SIZE - Bgzf.TRAILER_SIZE;
        try {
            decompressor.deflateDecompress(
                    blockBuffer, Bgzf.HEADER_SIZE, compressedDataLen, uncompressedBuffer, 0, isize);
        } catch (LibdeflateException e) {
            throw new IOException("Failed to decompress BGZF block at compressed offset " + compressedFilePosition, e);
        }

        // Verify CRC32
        crc.reset();
        crc.update(uncompressedBuffer, 0, isize);
        int actualCrc = (int) crc.getValue();
        if (actualCrc != expectedCrc) {
            throw new IOException(String.format(
                    "CRC32 mismatch in BGZF block at compressed offset %d: expected 0x%08X, got 0x%08X",
                    compressedFilePosition, expectedCrc, actualCrc));
        }

        compressedFilePosition += totalBlockSize;
        uncompressedOffset = 0;
        uncompressedLimit = isize;
        return true;
    }

    /**
     * Throws {@link IllegalStateException} if the stream has been closed.
     *
     * @throws IllegalStateException if closed
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Stream has been closed");
        }
    }
}
