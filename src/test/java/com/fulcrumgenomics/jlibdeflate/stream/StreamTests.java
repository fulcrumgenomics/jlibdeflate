package com.fulcrumgenomics.jlibdeflate.stream;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class StreamTests {

    // ---- Test data generators ----

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    private static byte[] repeatedString(int repeats) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeats; i++) {
            sb.append("Hello, BGZF! This is a test string that repeats for compression. ");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] allZeros(int size) {
        return new byte[size];
    }

    /** Helper: write data through BgzfOutputStream and return the compressed bytes. */
    private static byte[] writeBgzf(byte[] data) throws IOException {
        return writeBgzf(data, 6);
    }

    private static byte[] writeBgzf(byte[] data, int level) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var out = new BgzfOutputStream(baos, level)) {
            out.write(data);
        }
        return baos.toByteArray();
    }

    /** Helper: read all bytes from a BgzfInputStream. */
    private static byte[] readBgzf(byte[] compressed) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var in = new BgzfInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                result.write(buf, 0, n);
            }
        }
        return result.toByteArray();
    }

    // ---- Round-trip tests ----

    @Test
    void roundTripEmptyData() throws IOException {
        byte[] input = new byte[0];
        byte[] compressed = writeBgzf(input);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void roundTripSingleByte() throws IOException {
        byte[] input = {42};
        byte[] compressed = writeBgzf(input);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void roundTripExactlyOneBlock() throws IOException {
        byte[] input = randomBytes(Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE);
        byte[] compressed = writeBgzf(input);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void roundTripMultipleBlocks() throws IOException {
        byte[] input = randomBytes(Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE * 3 + 1000);
        byte[] compressed = writeBgzf(input);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void roundTripLargeData() throws IOException {
        byte[] input = randomBytes(1024 * 1024);
        byte[] compressed = writeBgzf(input);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void roundTripHighlyCompressible() throws IOException {
        byte[] input = allZeros(Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE * 2);
        byte[] compressed = writeBgzf(input);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void roundTripRepeatedString() throws IOException {
        byte[] input = repeatedString(500);
        byte[] compressed = writeBgzf(input);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void roundTripAllCompressionLevels() throws IOException {
        byte[] input = repeatedString(100);
        for (int level = 0; level <= 12; level++) {
            byte[] compressed = writeBgzf(input, level);
            byte[] decompressed = readBgzf(compressed);
            assertArrayEquals(input, decompressed, "Failed at level " + level);
        }
    }

    @Test
    void roundTripSingleByteReads() throws IOException {
        byte[] input = randomBytes(1000);
        byte[] compressed = writeBgzf(input);

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var in = new BgzfInputStream(new ByteArrayInputStream(compressed))) {
            int b;
            while ((b = in.read()) >= 0) {
                result.write(b);
            }
        }
        assertArrayEquals(input, result.toByteArray());
    }

    @Test
    void roundTripSingleByteWrites() throws IOException {
        byte[] input = randomBytes(1000);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var out = new BgzfOutputStream(baos)) {
            for (byte b : input) {
                out.write(b);
            }
        }
        byte[] decompressed = readBgzf(baos.toByteArray());
        assertArrayEquals(input, decompressed);
    }

    // ---- Cross-compatibility: BGZF → JDK GZIPInputStream ----

    @Test
    void bgzfReadableByJdkGzipInputStream() throws IOException {
        byte[] input = repeatedString(200);
        byte[] compressed = writeBgzf(input);

        // BGZF is valid gzip, so JDK should be able to read it
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var gzIn = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gzIn.read(buf)) >= 0) {
                result.write(buf, 0, n);
            }
        }
        assertArrayEquals(input, result.toByteArray());
    }

    @Test
    void bgzfLargeDataReadableByJdkGzipInputStream() throws IOException {
        byte[] input = randomBytes(Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE * 5);
        byte[] compressed = writeBgzf(input);

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var gzIn = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gzIn.read(buf)) >= 0) {
                result.write(buf, 0, n);
            }
        }
        assertArrayEquals(input, result.toByteArray());
    }

    // ---- Virtual offset tests ----

    @Test
    void bgzfPositionStartsAtZero() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var out = new BgzfOutputStream(baos)) {
            assertEquals(0L, out.bgzfPosition());
        }
    }

    @Test
    void bgzfPositionTracksWithinBlockOffset() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var out = new BgzfOutputStream(baos)) {
            assertEquals(0L, out.bgzfPosition());

            out.write(new byte[100]);
            // Still in first block: blockOffset=0, withinBlockOffset=100
            long pos = out.bgzfPosition();
            assertEquals(0L, Bgzf.blockOffset(pos));
            assertEquals(100, Bgzf.withinBlockOffset(pos));
        }
    }

    @Test
    void bgzfPositionAdvancesAfterBlockFlush() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var out = new BgzfOutputStream(baos)) {
            // Write exactly one full block
            out.write(new byte[Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE]);

            // Now in second block
            long pos = out.bgzfPosition();
            long blockOffset = Bgzf.blockOffset(pos);
            int withinBlock = Bgzf.withinBlockOffset(pos);

            assertTrue(blockOffset > 0, "Block offset should have advanced");
            assertEquals(0, withinBlock, "Within-block offset should be 0 at start of new block");
        }
    }

    @Test
    void bgzfPositionWriterAndReaderAgreeAtBlockBoundaries() throws IOException {
        // Write exactly N full blocks, recording the position at the start of each block
        int numBlocks = 4;
        int blockDataSize = Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE;
        byte[] input = randomBytes(numBlocks * blockDataSize);
        List<Long> writerPositions = new ArrayList<>();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var out = new BgzfOutputStream(baos)) {
            for (int i = 0; i < numBlocks; i++) {
                writerPositions.add(out.bgzfPosition());
                out.write(input, i * blockDataSize, blockDataSize);
            }
        }

        // Read back and verify block offsets match. The reader lazily loads blocks, so
        // we trigger each block load by reading one byte, then check the position.
        byte[] compressed = baos.toByteArray();
        try (var in = new BgzfInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[blockDataSize];
            for (int i = 0; i < numBlocks; i++) {
                // Read one byte to ensure this block is loaded
                int firstByte = in.read();
                assertTrue(firstByte >= 0, "Unexpected EOF at block " + i);

                // The reader is now inside block i with within-block offset = 1
                long readerPos = in.bgzfPosition();
                assertEquals(
                        Bgzf.blockOffset(writerPositions.get(i)),
                        Bgzf.blockOffset(readerPos),
                        "Block offset mismatch at block " + i);
                assertEquals(1, Bgzf.withinBlockOffset(readerPos));

                // Drain the rest of this block
                int remaining = blockDataSize - 1;
                int totalRead = 0;
                while (totalRead < remaining) {
                    int n = in.read(buf, totalRead, remaining - totalRead);
                    assertTrue(n > 0, "Unexpected EOF mid-block " + i);
                    totalRead += n;
                }
            }
        }
    }

    @Test
    void virtualOffsetUtilities() {
        long vo = Bgzf.makeVirtualOffset(12345L, 678);
        assertEquals(12345L, Bgzf.blockOffset(vo));
        assertEquals(678, Bgzf.withinBlockOffset(vo));
    }

    @Test
    void virtualOffsetEdgeCases() {
        // Zero
        assertEquals(0L, Bgzf.makeVirtualOffset(0, 0));

        // Max within-block offset
        long vo = Bgzf.makeVirtualOffset(0, 65535);
        assertEquals(65535, Bgzf.withinBlockOffset(vo));

        // Large block offset
        long maxBlockOffset = (1L << 48) - 1;
        vo = Bgzf.makeVirtualOffset(maxBlockOffset, 0);
        assertEquals(maxBlockOffset, Bgzf.blockOffset(vo));
    }

    @Test
    void virtualOffsetRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> Bgzf.makeVirtualOffset(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> Bgzf.makeVirtualOffset(1L << 48, 0));
        assertThrows(IllegalArgumentException.class, () -> Bgzf.makeVirtualOffset(0, -1));
        assertThrows(IllegalArgumentException.class, () -> Bgzf.makeVirtualOffset(0, 65536));
    }

    // ---- Detection tests ----

    @Test
    void isBgzfReturnsTrueForBgzfData() throws IOException {
        byte[] compressed = writeBgzf(repeatedString(10));
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        assertTrue(Bgzf.isBgzf(bais));
        // Stream should be reset — reading should still work
        byte[] decompressed = readAllBytes(new BgzfInputStream(bais));
        assertArrayEquals(repeatedString(10), decompressed);
    }

    @Test
    void isBgzfReturnsFalseForStandardGzip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var gzOut = new GZIPOutputStream(baos)) {
            gzOut.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        assertFalse(Bgzf.isBgzf(bais));
    }

    @Test
    void isBgzfReturnsFalseForNonGzipData() throws IOException {
        byte[] plainText = "This is not gzip".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(plainText);
        assertFalse(Bgzf.isBgzf(bais));
    }

    @Test
    void isBgzfReturnsFalseForTruncatedData() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[] {0x1f, (byte) 0x8b});
        assertFalse(Bgzf.isBgzf(bais));
    }

    @Test
    void isBgzfReturnsFalseForEmptyStream() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        assertFalse(Bgzf.isBgzf(bais));
    }

    @Test
    void isBgzfThrowsForNonMarkableStream() {
        InputStream nonMarkable = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> Bgzf.isBgzf(nonMarkable));
    }

    // ---- Stream factory tests ----

    @Test
    void newGzipInputStreamReturnsBgzfForBgzfData() throws IOException {
        byte[] compressed = writeBgzf(repeatedString(10));
        try (var stream = Bgzf.newGzipInputStream(new ByteArrayInputStream(compressed))) {
            assertInstanceOf(BgzfInputStream.class, stream);
            byte[] decompressed = readAllBytes(stream);
            assertArrayEquals(repeatedString(10), decompressed);
        }
    }

    @Test
    void newGzipInputStreamFallsBackForStandardGzip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var gzOut = new GZIPOutputStream(baos)) {
            gzOut.write("hello gzip".getBytes(StandardCharsets.UTF_8));
        }
        try (var stream = Bgzf.newGzipInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            assertInstanceOf(GZIPInputStream.class, stream);
            byte[] decompressed = readAllBytes(stream);
            assertArrayEquals("hello gzip".getBytes(StandardCharsets.UTF_8), decompressed);
        }
    }

    @Test
    void newGzipInputStreamUsesCustomFallback() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var gzOut = new GZIPOutputStream(baos)) {
            gzOut.write("custom".getBytes(StandardCharsets.UTF_8));
        }
        boolean[] fallbackCalled = {false};
        try (var stream = Bgzf.newGzipInputStream(new ByteArrayInputStream(baos.toByteArray()), in -> {
            fallbackCalled[0] = true;
            return new GZIPInputStream(in);
        })) {
            assertTrue(fallbackCalled[0]);
        }
    }

    @Test
    void newGzipInputStreamHandlesNonMarkableStream() throws IOException {
        byte[] compressed = writeBgzf(repeatedString(10));
        // Wrap in a non-markable stream
        InputStream nonMarkable = new InputStream() {
            private final ByteArrayInputStream delegate = new ByteArrayInputStream(compressed);

            @Override
            public int read() throws IOException {
                return delegate.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return delegate.read(b, off, len);
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
        try (var stream = Bgzf.newGzipInputStream(nonMarkable)) {
            assertInstanceOf(BgzfInputStream.class, stream);
        }
    }

    // ---- EOF marker tests ----

    @Test
    void outputStreamWritesEofMarker() throws IOException {
        byte[] compressed = writeBgzf(new byte[] {1, 2, 3});
        // The last 28 bytes should be the EOF marker
        byte[] last28 = Arrays.copyOfRange(compressed, compressed.length - Bgzf.EOF_MARKER.length, compressed.length);
        assertArrayEquals(Bgzf.EOF_MARKER, last28);
    }

    @Test
    void emptyStreamWritesOnlyEofMarker() throws IOException {
        byte[] compressed = writeBgzf(new byte[0]);
        // Should be exactly the EOF marker
        assertArrayEquals(Bgzf.EOF_MARKER, compressed);
    }

    @Test
    void inputStreamHandlesMissingEofMarker() throws IOException {
        byte[] compressed = writeBgzf(repeatedString(10));
        // Strip the EOF marker from the end
        byte[] truncated = Arrays.copyOf(compressed, compressed.length - Bgzf.EOF_MARKER.length);

        // Should still be readable — EOF is detected by end of stream
        byte[] decompressed = readBgzf(truncated);
        assertArrayEquals(repeatedString(10), decompressed);
    }

    // ---- Error handling tests ----

    @Test
    void inputStreamThrowsOnCorruptHeader() {
        byte[] garbage = {
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11
        };
        assertThrows(IOException.class, () -> readBgzf(garbage));
    }

    @Test
    void inputStreamThrowsOnTruncatedBlock() throws IOException {
        byte[] compressed = writeBgzf(repeatedString(10));
        // Truncate in the middle of the first data block (before the EOF marker)
        byte[] truncated = Arrays.copyOf(compressed, Bgzf.HEADER_SIZE + 5);
        assertThrows(IOException.class, () -> readBgzf(truncated));
    }

    @Test
    void inputStreamThrowsOnBadCrc() throws IOException {
        byte[] compressed = writeBgzf(new byte[] {1, 2, 3, 4, 5});

        // Find the CRC in the first block's trailer and corrupt it.
        // The first block's trailer starts at (totalBlockSize - TRAILER_SIZE).
        int bsize = (compressed[16] & 0xFF) | ((compressed[17] & 0xFF) << 8);
        int totalBlockSize = bsize + 1;
        int crcOffset = totalBlockSize - Bgzf.TRAILER_SIZE;
        compressed[crcOffset] ^= 0xFF; // flip bits in first CRC byte

        assertThrows(IOException.class, () -> readBgzf(compressed));
    }

    @Test
    void writeAfterCloseThrows() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BgzfOutputStream out = new BgzfOutputStream(baos);
        out.close();
        assertThrows(IllegalStateException.class, () -> out.write(1));
        assertThrows(IllegalStateException.class, () -> out.write(new byte[10]));
        assertThrows(IllegalStateException.class, out::bgzfPosition);
    }

    @Test
    void readAfterCloseThrows() throws IOException {
        byte[] compressed = writeBgzf(new byte[] {1, 2, 3});
        BgzfInputStream in = new BgzfInputStream(new ByteArrayInputStream(compressed));
        in.close();
        assertThrows(IllegalStateException.class, () -> in.read());
        assertThrows(IllegalStateException.class, () -> in.read(new byte[10]));
        assertThrows(IllegalStateException.class, in::bgzfPosition);
    }

    @Test
    void closeIsIdempotent() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BgzfOutputStream out = new BgzfOutputStream(baos);
        out.close();
        out.close(); // should not throw

        byte[] compressed = baos.toByteArray();
        BgzfInputStream in = new BgzfInputStream(new ByteArrayInputStream(compressed));
        in.close();
        in.close(); // should not throw
    }

    @Test
    void flushCreatesBlockBoundary() throws IOException {
        byte[] input = randomBytes(1000);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var out = new BgzfOutputStream(baos)) {
            out.write(input, 0, 500);
            out.flush();
            // After flush, block offset should have advanced
            long pos = out.bgzfPosition();
            assertTrue(Bgzf.blockOffset(pos) > 0);
            assertEquals(0, Bgzf.withinBlockOffset(pos));
            out.write(input, 500, 500);
        }
        byte[] decompressed = readBgzf(baos.toByteArray());
        assertArrayEquals(input, decompressed);
    }

    @Test
    void availableReturnsRemainingBytesInCurrentBlock() throws IOException {
        byte[] input = randomBytes(1000);
        byte[] compressed = writeBgzf(input);

        try (var in = new BgzfInputStream(new ByteArrayInputStream(compressed))) {
            // Before any reads, available is 0 (no block loaded yet)
            assertEquals(0, in.available());

            // Read one byte to force block load
            in.read();
            // available should be the rest of the block (1000 - 1 = 999)
            assertEquals(999, in.available());

            // Read 499 more bytes
            in.read(new byte[499]);
            assertEquals(500, in.available());
        }
    }

    @Test
    void roundTripLevel0IncompressibleData() throws IOException {
        // Level 0 (stored) on random data is the worst case for block sizing;
        // verifies DEFAULT_UNCOMPRESSED_BLOCK_SIZE fits within MAX_BLOCK_SIZE
        byte[] input = randomBytes(Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE);
        byte[] compressed = writeBgzf(input, 0);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void roundTripLevel0MultipleBlocks() throws IOException {
        byte[] input = randomBytes(Bgzf.DEFAULT_UNCOMPRESSED_BLOCK_SIZE * 3);
        byte[] compressed = writeBgzf(input, 0);
        byte[] decompressed = readBgzf(compressed);
        assertArrayEquals(input, decompressed);
    }

    @Test
    void inputStreamThrowsOnTooSmallBlockSize() throws IOException {
        // Craft a BGZF header with an impossibly small BSIZE (totalBlockSize = 10)
        byte[] header = Bgzf.HEADER_TEMPLATE.clone();
        int bsize = 9; // totalBlockSize = 10, less than MIN_BLOCK_SIZE (26)
        header[16] = (byte) (bsize & 0xFF);
        header[17] = (byte) ((bsize >>> 8) & 0xFF);
        // Pad with enough bytes to avoid truncation errors before the size check
        byte[] data = new byte[header.length + 100];
        System.arraycopy(header, 0, data, 0, header.length);
        assertThrows(IOException.class, () -> readBgzf(data));
    }

    // ---- Helpers ----

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
