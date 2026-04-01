package com.fulcrumgenomics.jlibdeflate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class LibdeflateDecompressorTest {

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    private static byte[] repeatedString(int repeats) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeats; i++) {
            sb.append("Hello, libdeflate! This is a test string that repeats. ");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Compresses with JDK Deflater for cross-compatibility testing. */
    private static byte[] jdkDeflateCompress(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true); // nowrap=true
        deflater.setInput(input);
        deflater.finish();
        byte[] output = new byte[input.length + 100];
        int len = deflater.deflate(output);
        deflater.end();
        return Arrays.copyOf(output, len);
    }

    // ---- DEFLATE decompression ----

    @Test
    void deflateRoundTrip() {
        byte[] input = repeatedString(100);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);
            byte[] decompressed = d.deflateDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    @Test
    void deflateDecompressSubarray() {
        byte[] input = repeatedString(20);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);

            // Embed compressed data in a larger array with padding
            byte[] padded = new byte[compressed.length + 20];
            System.arraycopy(compressed, 0, padded, 5, compressed.length);

            byte[] decompressed = d.deflateDecompress(padded, 5, compressed.length, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    @Test
    void deflateDecompressIntoBuffer() {
        byte[] input = repeatedString(20);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);
            byte[] output = new byte[input.length + 10];
            d.deflateDecompress(compressed, 0, compressed.length, output, 5, input.length);
            assertArrayEquals(input, Arrays.copyOfRange(output, 5, 5 + input.length));
        }
    }

    @Test
    void deflateDecompressFromJdkCompressed() {
        byte[] input = repeatedString(50);
        byte[] compressed = jdkDeflateCompress(input);
        try (var d = new LibdeflateDecompressor()) {
            byte[] decompressed = d.deflateDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    // ---- Extended decompression ----

    @Test
    void deflateDecompressExReturnsCorrectCounts() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);

            // Use a larger output buffer to test that actual_out is reported correctly
            byte[] output = new byte[input.length + 100];
            DecompressionResult result =
                    d.deflateDecompressEx(compressed, 0, compressed.length, output, 0, output.length);

            assertEquals(compressed.length, result.inputBytesConsumed());
            assertEquals(input.length, result.outputBytesProduced());
            assertArrayEquals(input, Arrays.copyOf(output, result.outputBytesProduced()));
        }
    }

    @Test
    void zlibDecompressExReturnsCorrectCounts() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.zlibCompress(input);
            byte[] output = new byte[input.length + 100];
            DecompressionResult result = d.zlibDecompressEx(compressed, 0, compressed.length, output, 0, output.length);

            assertEquals(compressed.length, result.inputBytesConsumed());
            assertEquals(input.length, result.outputBytesProduced());
        }
    }

    @Test
    void gzipDecompressExReturnsCorrectCounts() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.gzipCompress(input);
            byte[] output = new byte[input.length + 100];
            DecompressionResult result = d.gzipDecompressEx(compressed, 0, compressed.length, output, 0, output.length);

            assertEquals(compressed.length, result.inputBytesConsumed());
            assertEquals(input.length, result.outputBytesProduced());
        }
    }

    // ---- ByteBuffer tests ----

    @Test
    void deflateDecompressDirectByteBuffers() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);

            ByteBuffer inBuf = ByteBuffer.allocateDirect(compressed.length);
            inBuf.put(compressed).flip();
            ByteBuffer outBuf = ByteBuffer.allocateDirect(input.length);

            d.deflateDecompress(inBuf, outBuf, input.length);

            outBuf.flip();
            byte[] result = new byte[input.length];
            outBuf.get(result);
            assertArrayEquals(input, result);
        }
    }

    @Test
    void deflateDecompressExDirectByteBuffers() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);

            ByteBuffer inBuf = ByteBuffer.allocateDirect(compressed.length);
            inBuf.put(compressed).flip();
            ByteBuffer outBuf = ByteBuffer.allocateDirect(input.length + 100);

            DecompressionResult result = d.deflateDecompressEx(inBuf, outBuf);

            assertEquals(compressed.length, result.inputBytesConsumed());
            assertEquals(input.length, result.outputBytesProduced());
        }
    }

    // ---- ZLIB and GZIP round-trip ----

    @Test
    void zlibRoundTrip() {
        byte[] input = repeatedString(100);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.zlibCompress(input);
            byte[] decompressed = d.zlibDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    @Test
    void gzipRoundTrip() {
        byte[] input = repeatedString(100);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.gzipCompress(input);
            byte[] decompressed = d.gzipDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    // ---- Error cases ----

    @Test
    void corruptDataThrows() {
        try (var d = new LibdeflateDecompressor()) {
            byte[] corrupt = {0x01, 0x02, 0x03, 0x04, 0x05};
            assertThrows(LibdeflateException.class, () -> d.deflateDecompress(corrupt, 100));
        }
    }

    @Test
    void wrongSizeThrows() {
        byte[] input = repeatedString(20);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);
            // Ask for wrong uncompressed size
            assertThrows(LibdeflateException.class, () -> d.deflateDecompress(compressed, input.length / 2));
        }
    }

    @Test
    void useAfterCloseThrows() {
        LibdeflateDecompressor d = new LibdeflateDecompressor();
        d.close();
        assertThrows(IllegalStateException.class, () -> d.deflateDecompress(new byte[10], 10));
    }

    @Test
    void doubleCloseIsSafe() {
        LibdeflateDecompressor d = new LibdeflateDecompressor();
        d.close();
        d.close(); // should not throw
    }

    @Test
    void negativeSizeThrows() {
        try (var d = new LibdeflateDecompressor()) {
            assertThrows(IllegalArgumentException.class, () -> d.deflateDecompress(new byte[10], -1));
        }
    }

    @Test
    void invalidBoundsThrows() {
        try (var d = new LibdeflateDecompressor()) {
            assertThrows(IndexOutOfBoundsException.class, () -> d.deflateDecompress(new byte[10], -1, 5, 5));
            assertThrows(IndexOutOfBoundsException.class, () -> d.deflateDecompress(new byte[10], 0, 11, 5));
        }
    }

    // ---- DecompressionResult tests ----

    @Test
    void decompressionResultEquality() {
        DecompressionResult a = new DecompressionResult(10, 20);
        DecompressionResult b = new DecompressionResult(10, 20);
        DecompressionResult c = new DecompressionResult(10, 30);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotNull(a.toString());
    }
}
