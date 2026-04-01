package com.fulcrumgenomics.jlibdeflate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

class LibdeflateCompressorTest {

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

    // ---- DEFLATE round-trip ----

    @Test
    void deflateRoundTripRepeatedString() {
        byte[] input = repeatedString(100);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);
            assertTrue(compressed.length < input.length, "Should compress well");
            byte[] decompressed = d.deflateDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    @Test
    void deflateRoundTripEmptyInput() {
        byte[] input = new byte[0];
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);
            assertNotNull(compressed);
            byte[] decompressed = d.deflateDecompress(compressed, 0);
            assertArrayEquals(input, decompressed);
        }
    }

    @Test
    void deflateRoundTripLargeRandomData() {
        byte[] input = randomBytes(1024 * 1024);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);
            byte[] decompressed = d.deflateDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    @Test
    void deflateRoundTripHighlyCompressible() {
        byte[] input = new byte[64 * 1024]; // all zeros
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input);
            assertTrue(compressed.length < input.length / 5, "Zeros should compress > 5:1");
            byte[] decompressed = d.deflateDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    @Test
    void deflateRoundTripAllLevels() {
        byte[] input = repeatedString(50);
        try (var d = new LibdeflateDecompressor()) {
            for (int level = 0; level <= 12; level++) {
                try (var c = new LibdeflateCompressor(level)) {
                    byte[] compressed = c.deflateCompress(input);
                    byte[] decompressed = d.deflateDecompress(compressed, input.length);
                    assertArrayEquals(input, decompressed, "Failed at level " + level);
                }
            }
        }
    }

    @Test
    void deflateRoundTripSubarray() {
        byte[] input = repeatedString(20);
        int offset = 10;
        int length = input.length - 20;
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            byte[] compressed = c.deflateCompress(input, offset, length);
            byte[] decompressed = d.deflateDecompress(compressed, length);
            assertArrayEquals(Arrays.copyOfRange(input, offset, offset + length), decompressed);
        }
    }

    @Test
    void deflateCompressToBuffer() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor()) {
            int bound = c.deflateCompressBound(input.length);
            byte[] output = new byte[bound];
            int written = c.deflateCompress(input, 0, input.length, output, 0, output.length);
            assertTrue(written > 0);
            assertTrue(written <= bound);
        }
    }

    @Test
    void deflateCompressToBufferTooSmall() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor()) {
            byte[] output = new byte[1]; // too small
            int written = c.deflateCompress(input, 0, input.length, output, 0, output.length);
            assertEquals(-1, written);
        }
    }

    @Test
    void deflateCompressBoundIsReasonable() {
        try (var c = new LibdeflateCompressor()) {
            int bound = c.deflateCompressBound(1000);
            assertTrue(bound >= 1000, "Bound should be >= input size");
            assertTrue(bound < 2000, "Bound should be reasonable for 1000 bytes");
        }
    }

    // ---- ZLIB round-trip ----

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
    void zlibCompressBound() {
        try (var c = new LibdeflateCompressor()) {
            int bound = c.zlibCompressBound(1000);
            assertTrue(bound >= 1000);
        }
    }

    // ---- GZIP round-trip ----

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

    @Test
    void gzipCompressBound() {
        try (var c = new LibdeflateCompressor()) {
            int bound = c.gzipCompressBound(1000);
            assertTrue(bound >= 1000);
        }
    }

    // ---- Cross-compatibility with java.util.zip ----

    @Test
    void deflateDecompressibleByJdkInflater() throws DataFormatException {
        byte[] input = repeatedString(100);
        try (var c = new LibdeflateCompressor()) {
            byte[] compressed = c.deflateCompress(input);

            // Decompress with JDK Inflater
            Inflater inflater = new Inflater(true); // nowrap=true for raw DEFLATE
            inflater.setInput(compressed);
            byte[] output = new byte[input.length];
            int len = inflater.inflate(output);
            inflater.end();
            assertEquals(input.length, len);
            assertArrayEquals(input, output);
        }
    }

    @Test
    void gzipDecompressibleByJdkGzip() throws Exception {
        byte[] input = repeatedString(100);
        try (var c = new LibdeflateCompressor()) {
            byte[] compressed = c.gzipCompress(input);

            // Decompress with JDK GZIPInputStream
            var bais = new java.io.ByteArrayInputStream(compressed);
            var gis = new java.util.zip.GZIPInputStream(bais);
            byte[] output = gis.readAllBytes();
            gis.close();
            assertArrayEquals(input, output);
        }
    }

    // ---- ByteBuffer tests ----

    @Test
    void deflateCompressDirectByteBuffers() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            ByteBuffer inBuf = ByteBuffer.allocateDirect(input.length);
            inBuf.put(input).flip();

            int bound = c.deflateCompressBound(input.length);
            ByteBuffer outBuf = ByteBuffer.allocateDirect(bound);

            int written = c.deflateCompress(inBuf, outBuf);
            assertTrue(written > 0);
            assertEquals(input.length, inBuf.position()); // input fully consumed
            assertEquals(written, outBuf.position()); // output advanced by written

            // Read back and decompress
            outBuf.flip();
            byte[] compressed = new byte[written];
            outBuf.get(compressed);
            byte[] decompressed = d.deflateDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    @Test
    void deflateCompressHeapByteBuffers() {
        byte[] input = repeatedString(50);
        try (var c = new LibdeflateCompressor();
                var d = new LibdeflateDecompressor()) {
            ByteBuffer inBuf = ByteBuffer.wrap(input);
            int bound = c.deflateCompressBound(input.length);
            ByteBuffer outBuf = ByteBuffer.allocate(bound);

            int written = c.deflateCompress(inBuf, outBuf);
            assertTrue(written > 0);

            outBuf.flip();
            byte[] compressed = new byte[written];
            outBuf.get(compressed);
            byte[] decompressed = d.deflateDecompress(compressed, input.length);
            assertArrayEquals(input, decompressed);
        }
    }

    // ---- Error cases ----

    @Test
    void invalidLevelThrows() {
        assertThrows(IllegalArgumentException.class, () -> new LibdeflateCompressor(-1));
        assertThrows(IllegalArgumentException.class, () -> new LibdeflateCompressor(13));
    }

    @Test
    void useAfterCloseThrows() {
        LibdeflateCompressor c = new LibdeflateCompressor();
        c.close();
        assertThrows(IllegalStateException.class, () -> c.deflateCompress(new byte[10]));
    }

    @Test
    void doubleCloseIsSafe() {
        LibdeflateCompressor c = new LibdeflateCompressor();
        c.close();
        c.close(); // should not throw
    }

    @Test
    void invalidBoundsThrows() {
        try (var c = new LibdeflateCompressor()) {
            assertThrows(IndexOutOfBoundsException.class, () -> c.deflateCompress(new byte[10], -1, 5));
            assertThrows(IndexOutOfBoundsException.class, () -> c.deflateCompress(new byte[10], 0, 11));
            assertThrows(IndexOutOfBoundsException.class, () -> c.deflateCompress(new byte[10], 8, 5));
        }
    }

    @Test
    void levelAccessor() {
        try (var c = new LibdeflateCompressor(9)) {
            assertEquals(9, c.level());
        }
        try (var c = new LibdeflateCompressor()) {
            assertEquals(6, c.level());
        }
    }
}
