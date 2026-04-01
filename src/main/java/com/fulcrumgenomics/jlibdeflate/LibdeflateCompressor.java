package com.fulcrumgenomics.jlibdeflate;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A compressor backed by libdeflate that supports DEFLATE, zlib, and gzip
 * compression at levels 0–12.
 *
 * <p>Instances are <b>not</b> thread-safe.  Each thread should use its own
 * compressor.  For high-throughput or parallel workloads, prefer the
 * {@link ByteBuffer} overloads with direct byte buffers — these use
 * {@code GetDirectBufferAddress} internally and avoid array pinning, meaning
 * they impose no GC pauses whatsoever.
 *
 * <p>Instances hold native memory and <b>must</b> be closed when no longer
 * needed.  Use try-with-resources:
 * <pre>{@code
 * try (var compressor = new LibdeflateCompressor(6)) {
 *     byte[] compressed = compressor.deflateCompress(data);
 * }
 * }</pre>
 *
 * A {@link Cleaner} is registered as a safety net, but explicit closing is
 * strongly preferred.
 */
public class LibdeflateCompressor implements AutoCloseable {

    /** Minimum compression level (no compression, produces valid DEFLATE output). */
    public static final int MIN_LEVEL = 0;

    /** Maximum compression level (best compression, slowest). */
    public static final int MAX_LEVEL = 12;

    /** Default compression level (balanced speed and compression ratio). */
    public static final int DEFAULT_LEVEL = 6;

    private static final Cleaner CLEANER = Cleaner.create();

    private static final int FORMAT_DEFLATE = 0;
    private static final int FORMAT_ZLIB = 1;
    private static final int FORMAT_GZIP = 2;

    static {
        NativeLoader.load();
    }

    private final int level;
    private long nativeHandle;
    private final Cleaner.Cleanable cleanable;

    /**
     * Creates a new compressor at the given compression level.
     *
     * @param level compression level from {@value #MIN_LEVEL} to {@value #MAX_LEVEL}
     * @throws IllegalArgumentException if level is out of range
     * @throws OutOfMemoryError if native memory allocation fails
     */
    public LibdeflateCompressor(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "Compression level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + ", got " + level);
        }
        this.level = level;
        this.nativeHandle = nativeAlloc(level);
        if (this.nativeHandle == 0) {
            throw new OutOfMemoryError("Failed to allocate libdeflate compressor");
        }
        this.cleanable = CLEANER.register(this, new CleanAction(this.nativeHandle));
    }

    /** Creates a new compressor at {@link #DEFAULT_LEVEL}. */
    public LibdeflateCompressor() {
        this(DEFAULT_LEVEL);
    }

    /** Returns the compression level of this compressor. */
    public int level() {
        return level;
    }

    // ---- DEFLATE ----

    /** Compresses the entire input array using raw DEFLATE. */
    public byte[] deflateCompress(byte[] input) {
        return deflateCompress(input, 0, input.length);
    }

    /** Compresses a region of the input array using raw DEFLATE. */
    public byte[] deflateCompress(byte[] input, int offset, int length) {
        return compressToNewArray(FORMAT_DEFLATE, input, offset, length);
    }

    /**
     * Compresses from input to output using raw DEFLATE.
     *
     * @return the number of compressed bytes written, or -1 if the output buffer is too small
     */
    public int deflateCompress(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int outputLength) {
        return compressToBuffer(FORMAT_DEFLATE, input, inputOffset, inputLength, output, outputOffset, outputLength);
    }

    /**
     * Compresses from input ByteBuffer to output ByteBuffer using raw DEFLATE.
     * Advances the position of both buffers on success.
     *
     * @return the number of compressed bytes written, or -1 if the output buffer is too small
     */
    public int deflateCompress(ByteBuffer input, ByteBuffer output) {
        return compressBuffers(FORMAT_DEFLATE, input, output);
    }

    /** Returns a worst-case upper bound on the compressed size for raw DEFLATE. */
    public int deflateCompressBound(int inputLength) {
        return compressBound(FORMAT_DEFLATE, inputLength);
    }

    // ---- ZLIB ----

    /** Compresses the entire input array using the zlib wrapper format. */
    public byte[] zlibCompress(byte[] input) {
        return zlibCompress(input, 0, input.length);
    }

    /** Compresses a region of the input array using the zlib wrapper format. */
    public byte[] zlibCompress(byte[] input, int offset, int length) {
        return compressToNewArray(FORMAT_ZLIB, input, offset, length);
    }

    /**
     * Compresses from input to output using the zlib wrapper format.
     *
     * @return the number of compressed bytes written, or -1 if the output buffer is too small
     */
    public int zlibCompress(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int outputLength) {
        return compressToBuffer(FORMAT_ZLIB, input, inputOffset, inputLength, output, outputOffset, outputLength);
    }

    /**
     * Compresses from input ByteBuffer to output ByteBuffer using the zlib wrapper format.
     * Advances the position of both buffers on success.
     *
     * @return the number of compressed bytes written, or -1 if the output buffer is too small
     */
    public int zlibCompress(ByteBuffer input, ByteBuffer output) {
        return compressBuffers(FORMAT_ZLIB, input, output);
    }

    /** Returns a worst-case upper bound on the compressed size for zlib. */
    public int zlibCompressBound(int inputLength) {
        return compressBound(FORMAT_ZLIB, inputLength);
    }

    // ---- GZIP ----

    /** Compresses the entire input array using the gzip wrapper format. */
    public byte[] gzipCompress(byte[] input) {
        return gzipCompress(input, 0, input.length);
    }

    /** Compresses a region of the input array using the gzip wrapper format. */
    public byte[] gzipCompress(byte[] input, int offset, int length) {
        return compressToNewArray(FORMAT_GZIP, input, offset, length);
    }

    /**
     * Compresses from input to output using the gzip wrapper format.
     *
     * @return the number of compressed bytes written, or -1 if the output buffer is too small
     */
    public int gzipCompress(
            byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int outputLength) {
        return compressToBuffer(FORMAT_GZIP, input, inputOffset, inputLength, output, outputOffset, outputLength);
    }

    /**
     * Compresses from input ByteBuffer to output ByteBuffer using the gzip wrapper format.
     * Advances the position of both buffers on success.
     *
     * @return the number of compressed bytes written, or -1 if the output buffer is too small
     */
    public int gzipCompress(ByteBuffer input, ByteBuffer output) {
        return compressBuffers(FORMAT_GZIP, input, output);
    }

    /** Returns a worst-case upper bound on the compressed size for gzip. */
    public int gzipCompressBound(int inputLength) {
        return compressBound(FORMAT_GZIP, inputLength);
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
            throw new IllegalStateException("Compressor has been closed");
        }
    }

    private int compressBound(int format, int inputLength) {
        ensureOpen();
        if (inputLength < 0) {
            throw new IllegalArgumentException("inputLength must be non-negative, got " + inputLength);
        }
        long bound = nativeCompressBound(nativeHandle, format, inputLength);
        if (bound > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Compressed bound (" + bound + ") exceeds Integer.MAX_VALUE for inputLength " + inputLength);
        }
        return (int) bound;
    }

    private byte[] compressToNewArray(int format, byte[] input, int offset, int length) {
        ensureOpen();
        checkBounds(input, offset, length);
        int bound = compressBound(format, length);
        byte[] output = new byte[bound];
        int written = nativeCompress(nativeHandle, format, input, offset, length, output, 0, bound);
        if (written == 0) {
            throw new LibdeflateException(
                    "Compression failed: output buffer too small (should not happen with compressBound)");
        }
        return Arrays.copyOf(output, written);
    }

    private int compressToBuffer(
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
        int written = nativeCompress(
                nativeHandle, format, input, inputOffset, inputLength, output, outputOffset, outputLength);
        return written == 0 ? -1 : written;
    }

    private int compressBuffers(int format, ByteBuffer input, ByteBuffer output) {
        ensureOpen();
        int inputPos = input.position();
        int inputLen = input.remaining();
        int outputPos = output.position();
        int outputLen = output.remaining();

        int written;
        if (input.isDirect() && output.isDirect()) {
            written =
                    nativeCompressDirect(nativeHandle, format, input, inputPos, inputLen, output, outputPos, outputLen);
        } else if (input.hasArray() && output.hasArray()) {
            written = nativeCompress(
                    nativeHandle,
                    format,
                    input.array(),
                    input.arrayOffset() + inputPos,
                    inputLen,
                    output.array(),
                    output.arrayOffset() + outputPos,
                    outputLen);
        } else {
            // Mixed direct/heap or read-only heap buffer: copy to temp arrays
            byte[] inArr = new byte[inputLen];
            input.duplicate().get(inArr);
            byte[] outArr = new byte[outputLen];
            written = nativeCompress(nativeHandle, format, inArr, 0, inputLen, outArr, 0, outputLen);
            if (written > 0) {
                output.put(outArr, 0, written);
            }
        }

        if (written > 0) {
            input.position(inputPos + inputLen);
            output.position(outputPos + written);
        }

        return written == 0 ? -1 : written;
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

    private static native long nativeAlloc(int level);

    private static native void nativeFree(long handle);

    private static native int nativeCompress(
            long handle,
            int format,
            byte[] input,
            int inputOffset,
            int inputLength,
            byte[] output,
            int outputOffset,
            int outputLength);

    private static native int nativeCompressDirect(
            long handle,
            int format,
            ByteBuffer input,
            int inputOffset,
            int inputLength,
            ByteBuffer output,
            int outputOffset,
            int outputLength);

    private static native long nativeCompressBound(long handle, int format, long inputLength);
}
