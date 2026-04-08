package com.fulcrumgenomics.jlibdeflate.stream;

import com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * An {@link OutputStream} that writes data in BGZF (Blocked GNU Zip Format).
 *
 * <p>Incoming data is buffered into blocks of up to {@link Bgzf#DEFAULT_UNCOMPRESSED_BLOCK_SIZE}
 * bytes. Each full block is compressed with libdeflate as a raw DEFLATE stream and wrapped in
 * a BGZF gzip member (header with BC extra field + compressed payload + CRC32/ISIZE trailer).
 *
 * <p>The output is valid gzip and can be read by any gzip-aware tool, but BGZF-aware tools
 * can additionally perform random access using virtual file offsets.
 *
 * <p>Instances are <b>not</b> thread-safe. Call {@link #close()} when done to flush the
 * final block, write the EOF marker, and release the native compressor.
 *
 * <p>Example:
 * <pre>{@code
 * try (var out = new BgzfOutputStream(new FileOutputStream("output.bam"))) {
 *     long pos = out.bgzfPosition(); // virtual offset before this write
 *     out.write(data);
 * }
 * }</pre>
 */
public class BgzfOutputStream extends OutputStream {

    /** The underlying output stream that receives compressed BGZF blocks. */
    private final OutputStream out;

    /** The libdeflate compressor used to DEFLATE each block's payload. */
    private final LibdeflateCompressor compressor;

    /** Buffer that accumulates uncompressed data until a full block is ready. */
    private final byte[] uncompressedBuffer;

    /** Reusable buffer for the compressed DEFLATE payload of the current block. */
    private final byte[] compressedBuffer;

    /** Reusable header buffer, cloned from {@link Bgzf#HEADER_TEMPLATE} and filled per block. */
    private final byte[] headerBuffer = Bgzf.HEADER_TEMPLATE.clone();

    /** Little-endian view of {@link #headerBuffer} for writing the BSIZE field. */
    private final ByteBuffer headerBuf = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN);

    /** Reusable trailer buffer for CRC32 + ISIZE (8 bytes). */
    private final byte[] trailerBuffer = new byte[Bgzf.TRAILER_SIZE];

    /** Little-endian view of {@link #trailerBuffer} for writing CRC32 and ISIZE fields. */
    private final ByteBuffer trailerBuf = ByteBuffer.wrap(trailerBuffer).order(ByteOrder.LITTLE_ENDIAN);

    /** Reusable CRC32 instance for computing each block's checksum. */
    private final CRC32 crc = new CRC32();

    /** Current write position within {@link #uncompressedBuffer}. */
    private int uncompressedOffset;

    /** Total bytes written to the underlying stream so far; used as the block offset for virtual offsets. */
    private long compressedFilePosition;

    /** Whether {@link #close()} has been called. */
    private boolean closed;

    /**
     * Creates a new BGZF output stream at the given compression level.
     *
     * @param out the underlying output stream to write BGZF blocks to
     * @param compressionLevel compression level from {@link LibdeflateCompressor#MIN_LEVEL}
     *                         to {@link LibdeflateCompressor#MAX_LEVEL}
     */
    public BgzfOutputStream(OutputStream out, int compressionLevel) {
        this.out = out;
        this.compressor = new LibdeflateCompressor(compressionLevel);
        this.uncompressedBuffer = new byte[Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE];
        this.compressedBuffer = new byte[Bgzf.MAX_BLOCK_SIZE];
        this.uncompressedOffset = 0;
        this.compressedFilePosition = 0;
        this.closed = false;
    }

    /**
     * Creates a new BGZF output stream at the default compression level.
     *
     * @param out the underlying output stream to write BGZF blocks to
     */
    public BgzfOutputStream(OutputStream out) {
        this(out, LibdeflateCompressor.DEFAULT_LEVEL);
    }

    /**
     * Returns the BGZF virtual file offset for the next byte that will be written.
     * The upper 48 bits are the compressed file offset of the current block start,
     * and the lower 16 bits are the uncompressed offset within that block.
     *
     * @return the current virtual file offset
     * @throws IllegalStateException if the stream has been closed
     */
    public long bgzfPosition() {
        ensureOpen();
        return Bgzf.makeVirtualOffset(compressedFilePosition, uncompressedOffset);
    }

    /**
     * Writes a single byte. If the internal buffer is full after this write,
     * the block is flushed to the underlying stream.
     *
     * @param b the byte to write (only the low 8 bits are used)
     * @throws IOException on I/O errors writing a completed block
     * @throws IllegalStateException if the stream has been closed
     */
    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        uncompressedBuffer[uncompressedOffset++] = (byte) b;
        if (uncompressedOffset >= uncompressedBuffer.length) {
            flushBlock();
        }
    }

    /**
     * Writes a region of a byte array. Data is buffered internally and flushed as
     * complete BGZF blocks whenever the buffer fills.
     *
     * @param b the source byte array
     * @param off the start offset in {@code b}
     * @param len the number of bytes to write
     * @throws IOException on I/O errors writing completed blocks
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} are out of range
     * @throws IllegalStateException if the stream has been closed
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException("off=" + off + ", len=" + len + ", b.length=" + b.length);
        }

        int remaining = len;
        int srcOffset = off;
        while (remaining > 0) {
            int space = uncompressedBuffer.length - uncompressedOffset;
            int toCopy = Math.min(remaining, space);
            System.arraycopy(b, srcOffset, uncompressedBuffer, uncompressedOffset, toCopy);
            uncompressedOffset += toCopy;
            srcOffset += toCopy;
            remaining -= toCopy;
            if (uncompressedOffset >= uncompressedBuffer.length) {
                flushBlock();
            }
        }
    }

    /**
     * Flushes any buffered data as a BGZF block, then flushes the underlying stream.
     * Note: calling flush mid-stream creates a block boundary, which may result in
     * smaller-than-optimal blocks.
     */
    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (uncompressedOffset > 0) {
            flushBlock();
        }
        out.flush();
    }

    /**
     * Flushes any remaining data, writes the BGZF EOF marker, closes the compressor,
     * and closes the underlying stream.  If the flush or EOF write fails, the
     * compressor and underlying stream are still closed but the stream is not marked
     * as closed, so a second {@code close()} call will retry the flush.
     *
     * @throws IOException on I/O errors flushing data or closing the underlying stream
     */
    @Override
    public void close() throws IOException {
        if (closed) return;

        try {
            if (uncompressedOffset > 0) {
                flushBlock();
            }
            out.write(Bgzf.EOF_MARKER);
            out.flush();
            closed = true;
        } finally {
            try {
                compressor.close();
            } finally {
                out.close();
            }
        }
    }

    /**
     * Compresses the current uncompressed buffer as a single BGZF block and writes the
     * header, compressed DEFLATE payload, and CRC32/ISIZE trailer to the underlying stream.
     * Resets the uncompressed buffer for the next block.
     *
     * @throws IOException on I/O errors writing to the underlying stream
     * @throws IllegalStateException if the compressed block exceeds {@link Bgzf#MAX_BLOCK_SIZE}
     */
    private void flushBlock() throws IOException {
        int inputLen = uncompressedOffset;

        // Compress with raw DEFLATE
        int compressedLen = compressor.deflateCompress(
                uncompressedBuffer, 0, inputLen, compressedBuffer, 0, compressedBuffer.length);
        if (compressedLen < 0) {
            throw new IllegalStateException(
                    "Compressed output exceeds buffer capacity; this should not happen with DEFAULT_UNCOMPRESSED_BLOCK_SIZE");
        }

        int totalBlockSize = Bgzf.HEADER_SIZE + compressedLen + Bgzf.TRAILER_SIZE;
        if (totalBlockSize > Bgzf.MAX_BLOCK_SIZE) {
            throw new IllegalStateException("BGZF block size " + totalBlockSize + " exceeds maximum "
                    + Bgzf.MAX_BLOCK_SIZE + "; uncompressed input was " + inputLen + " bytes");
        }

        // Write header with BSIZE filled in
        headerBuf.putShort(16, (short) (totalBlockSize - 1));
        out.write(headerBuffer);

        // Write compressed payload
        out.write(compressedBuffer, 0, compressedLen);

        // Write trailer: CRC32 + ISIZE (both little-endian)
        crc.reset();
        crc.update(uncompressedBuffer, 0, inputLen);
        trailerBuf.putInt(0, (int) crc.getValue());
        trailerBuf.putInt(4, inputLen);
        out.write(trailerBuffer);

        compressedFilePosition += totalBlockSize;
        uncompressedOffset = 0;
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
