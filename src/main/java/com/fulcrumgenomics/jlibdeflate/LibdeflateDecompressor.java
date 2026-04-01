package com.fulcrumgenomics.jlibdeflate;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;

/**
 * A decompressor backed by libdeflate that supports DEFLATE, zlib, and gzip
 * decompression.
 *
 * <p>Instances are <b>not</b> thread-safe.  Each thread should use its own
 * decompressor.  For high-throughput or parallel workloads, prefer the
 * {@link ByteBuffer} overloads with direct byte buffers — these use
 * {@code GetDirectBufferAddress} internally and avoid array pinning, meaning
 * they impose no GC pauses whatsoever.
 *
 * <p>libdeflate is a whole-buffer decompressor — it decompresses an entire
 * block at once rather than streaming.  For the standard (non-{@code Ex})
 * methods, you must know the exact uncompressed size in advance.  Use the
 * {@code Ex} variants when the uncompressed size is not known exactly.
 *
 * <p>Instances hold native memory and <b>must</b> be closed when no longer
 * needed.  Use try-with-resources:
 * <pre>{@code
 * try (var decompressor = new LibdeflateDecompressor()) {
 *     byte[] data = decompressor.deflateDecompress(compressed, originalSize);
 * }
 * }</pre>
 */
public class LibdeflateDecompressor implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private static final int FORMAT_DEFLATE = 0;
    private static final int FORMAT_ZLIB = 1;
    private static final int FORMAT_GZIP = 2;

    static {
        NativeLoader.load();
    }

    private long nativeHandle;
    private final Cleaner.Cleanable cleanable;

    /**
     * Creates a new decompressor.
     *
     * @throws OutOfMemoryError if native memory allocation fails
     */
    public LibdeflateDecompressor() {
        this.nativeHandle = nativeAlloc();
        if (this.nativeHandle == 0) {
            throw new OutOfMemoryError("Failed to allocate libdeflate decompressor");
        }
        this.cleanable = CLEANER.register(this, new CleanAction(this.nativeHandle));
    }

    // ---- DEFLATE ----

    /** Decompresses the entire input array using raw DEFLATE. */
    public byte[] deflateDecompress(byte[] input, int uncompressedSize) {
        return deflateDecompress(input, 0, input.length, uncompressedSize);
    }

    /** Decompresses a region of the input array using raw DEFLATE. */
    public byte[] deflateDecompress(byte[] input, int offset, int length, int uncompressedSize) {
        return decompressToNewArray(FORMAT_DEFLATE, input, offset, length, uncompressedSize);
    }

    /** Decompresses from input into the provided output buffer using raw DEFLATE. */
    public void deflateDecompress(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int uncompressedSize) {
        decompressToBuffer(FORMAT_DEFLATE, input, inputOffset, inputLength, output, outputOffset, uncompressedSize);
    }

    /**
     * Decompresses from input ByteBuffer into output ByteBuffer using raw DEFLATE.
     * Advances the position of both buffers on success.
     */
    public void deflateDecompress(ByteBuffer input, ByteBuffer output, int uncompressedSize) {
        decompressBuffers(FORMAT_DEFLATE, input, output, uncompressedSize);
    }

    /**
     * Extended decompression: decompresses raw DEFLATE data and reports both the
     * number of compressed bytes consumed and uncompressed bytes produced.
     *
     * @param output      output buffer (may be larger than actual uncompressed data)
     * @param outputLength size of the output buffer available for decompression
     * @return the decompression result with byte counts
     * @throws LibdeflateException on decompression failure
     */
    public DecompressionResult deflateDecompressEx(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int outputLength) {
        return decompressEx(FORMAT_DEFLATE, input, inputOffset, inputLength, output, outputOffset, outputLength);
    }

    /**
     * Extended decompression from ByteBuffers using raw DEFLATE.
     * Advances the position of both buffers based on the bytes consumed/produced.
     */
    public DecompressionResult deflateDecompressEx(ByteBuffer input, ByteBuffer output) {
        return decompressExBuffers(FORMAT_DEFLATE, input, output);
    }

    // ---- ZLIB ----

    /** Decompresses the entire input array using the zlib wrapper format. */
    public byte[] zlibDecompress(byte[] input, int uncompressedSize) {
        return zlibDecompress(input, 0, input.length, uncompressedSize);
    }

    /** Decompresses a region of the input array using the zlib wrapper format. */
    public byte[] zlibDecompress(byte[] input, int offset, int length, int uncompressedSize) {
        return decompressToNewArray(FORMAT_ZLIB, input, offset, length, uncompressedSize);
    }

    /** Decompresses from input into the provided output buffer using the zlib wrapper format. */
    public void zlibDecompress(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int uncompressedSize) {
        decompressToBuffer(FORMAT_ZLIB, input, inputOffset, inputLength, output, outputOffset, uncompressedSize);
    }

    /**
     * Decompresses from input ByteBuffer into output ByteBuffer using the zlib wrapper format.
     * Advances the position of both buffers on success.
     */
    public void zlibDecompress(ByteBuffer input, ByteBuffer output, int uncompressedSize) {
        decompressBuffers(FORMAT_ZLIB, input, output, uncompressedSize);
    }

    /**
     * Extended decompression using the zlib wrapper format.
     *
     * @return the decompression result with byte counts
     * @throws LibdeflateException on decompression failure
     */
    public DecompressionResult zlibDecompressEx(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int outputLength) {
        return decompressEx(FORMAT_ZLIB, input, inputOffset, inputLength, output, outputOffset, outputLength);
    }

    /**
     * Extended decompression from ByteBuffers using the zlib wrapper format.
     * Advances the position of both buffers based on the bytes consumed/produced.
     */
    public DecompressionResult zlibDecompressEx(ByteBuffer input, ByteBuffer output) {
        return decompressExBuffers(FORMAT_ZLIB, input, output);
    }

    // ---- GZIP ----

    /** Decompresses the entire input array using the gzip wrapper format. */
    public byte[] gzipDecompress(byte[] input, int uncompressedSize) {
        return gzipDecompress(input, 0, input.length, uncompressedSize);
    }

    /** Decompresses a region of the input array using the gzip wrapper format. */
    public byte[] gzipDecompress(byte[] input, int offset, int length, int uncompressedSize) {
        return decompressToNewArray(FORMAT_GZIP, input, offset, length, uncompressedSize);
    }

    /** Decompresses from input into the provided output buffer using the gzip wrapper format. */
    public void gzipDecompress(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int uncompressedSize) {
        decompressToBuffer(FORMAT_GZIP, input, inputOffset, inputLength, output, outputOffset, uncompressedSize);
    }

    /**
     * Decompresses from input ByteBuffer into output ByteBuffer using the gzip wrapper format.
     * Advances the position of both buffers on success.
     */
    public void gzipDecompress(ByteBuffer input, ByteBuffer output, int uncompressedSize) {
        decompressBuffers(FORMAT_GZIP, input, output, uncompressedSize);
    }

    /**
     * Extended decompression using the gzip wrapper format.
     *
     * @return the decompression result with byte counts
     * @throws LibdeflateException on decompression failure
     */
    public DecompressionResult gzipDecompressEx(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int outputLength) {
        return decompressEx(FORMAT_GZIP, input, inputOffset, inputLength, output, outputOffset, outputLength);
    }

    /**
     * Extended decompression from ByteBuffers using the gzip wrapper format.
     * Advances the position of both buffers based on the bytes consumed/produced.
     */
    public DecompressionResult gzipDecompressEx(ByteBuffer input, ByteBuffer output) {
        return decompressExBuffers(FORMAT_GZIP, input, output);
    }

    // ---- Lifecycle ----

    @Override
    public void close() {
        cleanable.clean(); // idempotent — frees native memory exactly once
        nativeHandle = 0; // for ensureOpen() detection
    }

    // ---- Internals ----

    private void ensureOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Decompressor has been closed");
        }
    }

    private byte[] decompressToNewArray(int format, byte[] input, int offset, int length, int uncompressedSize) {
        ensureOpen();
        checkBounds(input, offset, length);
        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("uncompressedSize must be non-negative, got " + uncompressedSize);
        }
        byte[] output = new byte[uncompressedSize];
        nativeDecompress(nativeHandle, format, input, offset, length, output, 0, uncompressedSize);
        return output;
    }

    private void decompressToBuffer(
            int format,
            byte[] input,
            int inputOffset,
            int inputLength,
            byte[] output,
            int outputOffset,
            int uncompressedSize) {
        ensureOpen();
        checkBounds(input, inputOffset, inputLength);
        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("uncompressedSize must be non-negative, got " + uncompressedSize);
        }
        checkBounds(output, outputOffset, uncompressedSize);
        nativeDecompress(nativeHandle, format, input, inputOffset, inputLength, output, outputOffset, uncompressedSize);
    }

    private void decompressBuffers(int format, ByteBuffer input, ByteBuffer output, int uncompressedSize) {
        ensureOpen();
        if (uncompressedSize < 0) {
            throw new IllegalArgumentException("uncompressedSize must be non-negative, got " + uncompressedSize);
        }
        if (output.remaining() < uncompressedSize) {
            throw new IllegalArgumentException("Output buffer remaining (" + output.remaining()
                    + ") < uncompressedSize (" + uncompressedSize + ")");
        }

        int inputPos = input.position();
        int inputLen = input.remaining();
        int outputPos = output.position();
        long packed;

        if (input.isDirect() && output.isDirect()) {
            packed = nativeDecompressDirect(
                    nativeHandle, format, input, inputPos, inputLen, output, outputPos, uncompressedSize);
        } else if (input.hasArray() && output.hasArray()) {
            packed = nativeDecompress(
                    nativeHandle,
                    format,
                    input.array(),
                    input.arrayOffset() + inputPos,
                    inputLen,
                    output.array(),
                    output.arrayOffset() + outputPos,
                    uncompressedSize);
        } else {
            byte[] inArr = new byte[inputLen];
            input.duplicate().get(inArr);
            byte[] outArr = new byte[uncompressedSize];
            packed = nativeDecompress(nativeHandle, format, inArr, 0, inputLen, outArr, 0, uncompressedSize);
            // Only write to output after successful decompression (native throws on error)
            output.put(outArr, 0, uncompressedSize);
        }

        int actualIn = (int) (packed >>> 32);
        input.position(inputPos + actualIn);
        // Reset output position to the correct location (output.put may have advanced it)
        output.position(outputPos + uncompressedSize);
    }

    private DecompressionResult decompressEx(
            int format,
            byte[] input,
            int inputOffset,
            int inputLength,
            byte[] output,
            int outputOffset,
            int outputLength) {
        ensureOpen();
        checkBounds(input, inputOffset, inputLength);
        checkBounds(output, outputOffset, outputLength);
        long packed = nativeDecompress(
                nativeHandle, format, input, inputOffset, inputLength, output, outputOffset, outputLength);
        return unpackResult(packed);
    }

    private DecompressionResult decompressExBuffers(int format, ByteBuffer input, ByteBuffer output) {
        ensureOpen();
        int inputPos = input.position();
        int inputLen = input.remaining();
        int outputPos = output.position();
        int outputLen = output.remaining();

        long packed;
        if (input.isDirect() && output.isDirect()) {
            packed = nativeDecompressDirect(
                    nativeHandle, format, input, inputPos, inputLen, output, outputPos, outputLen);
        } else if (input.hasArray() && output.hasArray()) {
            packed = nativeDecompress(
                    nativeHandle,
                    format,
                    input.array(),
                    input.arrayOffset() + inputPos,
                    inputLen,
                    output.array(),
                    output.arrayOffset() + outputPos,
                    outputLen);
        } else {
            byte[] inArr = new byte[inputLen];
            input.duplicate().get(inArr);
            byte[] outArr = new byte[outputLen];
            packed = nativeDecompress(nativeHandle, format, inArr, 0, inputLen, outArr, 0, outputLen);
            // Only write to output after successful decompression (native throws on error)
            DecompressionResult result = unpackResult(packed);
            output.put(outArr, 0, result.outputBytesProduced());
            input.position(inputPos + result.inputBytesConsumed());
            // Reset output position (output.put already advanced it)
            output.position(outputPos + result.outputBytesProduced());
            return result;
        }

        DecompressionResult result = unpackResult(packed);
        input.position(inputPos + result.inputBytesConsumed());
        output.position(outputPos + result.outputBytesProduced());
        return result;
    }

    private static DecompressionResult unpackResult(long packed) {
        int actualIn = (int) (packed >>> 32);
        int actualOut = (int) (packed & 0xFFFFFFFFL);
        return new DecompressionResult(actualIn, actualOut);
    }

    private static void checkBounds(byte[] array, int offset, int length) {
        if (offset < 0 || length < 0 || length > array.length - offset) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", array.length=" + array.length);
        }
    }

    // ---- Cleaner action ----

    private static final class CleanAction implements Runnable {
        private long handle;

        CleanAction(long handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            if (handle != 0) {
                nativeFree(handle);
                handle = 0;
            }
        }
    }

    // ---- Native methods ----

    private static native long nativeAlloc();

    private static native void nativeFree(long handle);

    private static native long nativeDecompress(
            long handle,
            int format,
            byte[] input,
            int inputOffset,
            int inputLength,
            byte[] output,
            int outputOffset,
            int outputLength);

    private static native long nativeDecompressDirect(
            long handle,
            int format,
            ByteBuffer input,
            int inputOffset,
            int inputLength,
            ByteBuffer output,
            int outputOffset,
            int outputLength);
}
