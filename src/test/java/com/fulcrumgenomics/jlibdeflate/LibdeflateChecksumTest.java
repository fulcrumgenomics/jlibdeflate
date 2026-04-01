package com.fulcrumgenomics.jlibdeflate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.zip.Checksum;
import org.junit.jupiter.api.Test;

class LibdeflateChecksumTest {

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    // ---- CRC-32 ----

    @Test
    void crc32MatchesJdk() {
        byte[] data = randomBytes(10_000);
        java.util.zip.CRC32 jdkCrc = new java.util.zip.CRC32();
        jdkCrc.update(data);
        long jdkValue = jdkCrc.getValue();

        int libValue = LibdeflateChecksum.crc32(data);
        assertEquals(jdkValue, Integer.toUnsignedLong(libValue));
    }

    @Test
    void crc32SubarrayMatchesJdk() {
        byte[] data = randomBytes(10_000);
        int offset = 100;
        int length = 5000;

        java.util.zip.CRC32 jdkCrc = new java.util.zip.CRC32();
        jdkCrc.update(data, offset, length);
        long jdkValue = jdkCrc.getValue();

        int libValue = LibdeflateChecksum.crc32(data, offset, length);
        assertEquals(jdkValue, Integer.toUnsignedLong(libValue));
    }

    @Test
    void crc32EmptyInput() {
        int result = LibdeflateChecksum.crc32(new byte[0]);
        assertEquals(0, result);
    }

    @Test
    void crc32Incremental() {
        byte[] data = randomBytes(10_000);
        int split = 4000;

        int value = LibdeflateChecksum.crc32(data, 0, split);
        value = LibdeflateChecksum.crc32(value, data, split, data.length - split);

        int oneShot = LibdeflateChecksum.crc32(data);
        assertEquals(oneShot, value);
    }

    @Test
    void crc32DirectByteBuffer() {
        byte[] data = randomBytes(1_000);
        ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
        buf.put(data).flip();

        int bufResult = LibdeflateChecksum.crc32(buf);
        int arrayResult = LibdeflateChecksum.crc32(data);
        assertEquals(arrayResult, bufResult);
    }

    @Test
    void crc32HeapByteBuffer() {
        byte[] data = randomBytes(1_000);
        ByteBuffer buf = ByteBuffer.wrap(data);

        int bufResult = LibdeflateChecksum.crc32(buf);
        int arrayResult = LibdeflateChecksum.crc32(data);
        assertEquals(arrayResult, bufResult);
    }

    @Test
    void crc32ChecksumInterface() {
        byte[] data = randomBytes(10_000);

        Checksum libChecksum = LibdeflateChecksum.newCrc32();
        libChecksum.update(data, 0, data.length);
        long libValue = libChecksum.getValue();

        java.util.zip.CRC32 jdkCrc = new java.util.zip.CRC32();
        jdkCrc.update(data);
        long jdkValue = jdkCrc.getValue();

        assertEquals(jdkValue, libValue);
    }

    @Test
    void crc32ChecksumInterfaceReset() {
        byte[] data = randomBytes(100);
        Checksum checksum = LibdeflateChecksum.newCrc32();
        checksum.update(data, 0, data.length);
        long first = checksum.getValue();
        checksum.reset();
        checksum.update(data, 0, data.length);
        assertEquals(first, checksum.getValue());
    }

    @Test
    void crc32ChecksumInterfaceSingleByte() {
        Checksum libChecksum = LibdeflateChecksum.newCrc32();
        java.util.zip.CRC32 jdkCrc = new java.util.zip.CRC32();

        for (int b = 0; b < 256; b++) {
            libChecksum.update(b);
            jdkCrc.update(b);
        }

        assertEquals(jdkCrc.getValue(), libChecksum.getValue());
    }

    // ---- Adler-32 ----

    @Test
    void adler32MatchesJdk() {
        byte[] data = randomBytes(10_000);
        java.util.zip.Adler32 jdkAdler = new java.util.zip.Adler32();
        jdkAdler.update(data);
        long jdkValue = jdkAdler.getValue();

        int libValue = LibdeflateChecksum.adler32(data);
        assertEquals(jdkValue, Integer.toUnsignedLong(libValue));
    }

    @Test
    void adler32SubarrayMatchesJdk() {
        byte[] data = randomBytes(10_000);
        int offset = 200;
        int length = 3000;

        java.util.zip.Adler32 jdkAdler = new java.util.zip.Adler32();
        jdkAdler.update(data, offset, length);
        long jdkValue = jdkAdler.getValue();

        int libValue = LibdeflateChecksum.adler32(data, offset, length);
        assertEquals(jdkValue, Integer.toUnsignedLong(libValue));
    }

    @Test
    void adler32EmptyInput() {
        int result = LibdeflateChecksum.adler32(new byte[0]);
        // Adler-32 of empty input is the initial value 1
        assertEquals(1, result);
    }

    @Test
    void adler32Incremental() {
        byte[] data = randomBytes(10_000);
        int split = 6000;

        int value = LibdeflateChecksum.adler32(data, 0, split);
        value = LibdeflateChecksum.adler32(value, data, split, data.length - split);

        int oneShot = LibdeflateChecksum.adler32(data);
        assertEquals(oneShot, value);
    }

    @Test
    void adler32DirectByteBuffer() {
        byte[] data = randomBytes(1_000);
        ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
        buf.put(data).flip();

        int bufResult = LibdeflateChecksum.adler32(buf);
        int arrayResult = LibdeflateChecksum.adler32(data);
        assertEquals(arrayResult, bufResult);
    }

    @Test
    void adler32ChecksumInterface() {
        byte[] data = randomBytes(10_000);

        Checksum libChecksum = LibdeflateChecksum.newAdler32();
        libChecksum.update(data, 0, data.length);
        long libValue = libChecksum.getValue();

        java.util.zip.Adler32 jdkAdler = new java.util.zip.Adler32();
        jdkAdler.update(data);
        long jdkValue = jdkAdler.getValue();

        assertEquals(jdkValue, libValue);
    }

    @Test
    void adler32ChecksumInterfaceSingleByte() {
        Checksum libChecksum = LibdeflateChecksum.newAdler32();
        java.util.zip.Adler32 jdkAdler = new java.util.zip.Adler32();

        for (int b = 0; b < 256; b++) {
            libChecksum.update(b);
            jdkAdler.update(b);
        }

        assertEquals(jdkAdler.getValue(), libChecksum.getValue());
    }

    // ---- Error cases ----

    @Test
    void crc32InvalidBoundsThrows() {
        assertThrows(IndexOutOfBoundsException.class, () -> LibdeflateChecksum.crc32(new byte[10], -1, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> LibdeflateChecksum.crc32(new byte[10], 0, 11));
    }

    @Test
    void adler32InvalidBoundsThrows() {
        assertThrows(IndexOutOfBoundsException.class, () -> LibdeflateChecksum.adler32(new byte[10], -1, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> LibdeflateChecksum.adler32(new byte[10], 0, 11));
    }
}
