package com.fulcrumgenomics.jlibdeflate;

import java.nio.ByteBuffer;
import java.util.zip.Checksum;

/**
 * Hardware-accelerated CRC-32 and Adler-32 checksum computation backed by
 * libdeflate.  On modern x86_64 hardware this uses PCLMUL for CRC-32 and
 * SIMD for Adler-32, which can be significantly faster than the JDK
 * implementations.
 *
 * <p>This class provides both static utility methods for one-shot computation
 * and factory methods for {@link Checksum} instances that support incremental
 * (streaming) updates.
 */
public final class LibdeflateChecksum {

    static {
        NativeLoader.load();
    }

    private LibdeflateChecksum() {}

    // ---- CRC-32 static methods ----

    /** Computes the CRC-32 of the entire byte array. */
    public static int crc32(byte[] data) {
        return crc32(data, 0, data.length);
    }

    /** Computes the CRC-32 of a region of the byte array. */
    public static int crc32(byte[] data, int offset, int length) {
        return crc32(0, data, offset, length);
    }

    /** Updates a running CRC-32 with a region of the byte array. */
    public static int crc32(int currentValue, byte[] data, int offset, int length) {
        checkBounds(data, offset, length);
        return nativeCrc32(currentValue, data, offset, length);
    }

    /** Computes the CRC-32 of the remaining bytes in a direct ByteBuffer. */
    public static int crc32(ByteBuffer buffer) {
        return crc32(0, buffer);
    }

    /** Updates a running CRC-32 with the remaining bytes in a ByteBuffer. */
    public static int crc32(int currentValue, ByteBuffer buffer) {
        if (buffer.isDirect()) {
            return nativeCrc32Direct(currentValue, buffer, buffer.position(), buffer.remaining());
        } else if (buffer.hasArray()) {
            return nativeCrc32(
                    currentValue, buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        } else {
            byte[] tmp = new byte[buffer.remaining()];
            buffer.duplicate().get(tmp);
            return nativeCrc32(currentValue, tmp, 0, tmp.length);
        }
    }

    // ---- Adler-32 static methods ----

    /** Computes the Adler-32 of the entire byte array. */
    public static int adler32(byte[] data) {
        return adler32(data, 0, data.length);
    }

    /** Computes the Adler-32 of a region of the byte array. */
    public static int adler32(byte[] data, int offset, int length) {
        return adler32(1, data, offset, length);
    }

    /** Updates a running Adler-32 with a region of the byte array. Initial value should be 1. */
    public static int adler32(int currentValue, byte[] data, int offset, int length) {
        checkBounds(data, offset, length);
        return nativeAdler32(currentValue, data, offset, length);
    }

    /** Computes the Adler-32 of the remaining bytes in a ByteBuffer. */
    public static int adler32(ByteBuffer buffer) {
        return adler32(1, buffer);
    }

    /** Updates a running Adler-32 with the remaining bytes in a ByteBuffer. */
    public static int adler32(int currentValue, ByteBuffer buffer) {
        if (buffer.isDirect()) {
            return nativeAdler32Direct(currentValue, buffer, buffer.position(), buffer.remaining());
        } else if (buffer.hasArray()) {
            return nativeAdler32(
                    currentValue, buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        } else {
            byte[] tmp = new byte[buffer.remaining()];
            buffer.duplicate().get(tmp);
            return nativeAdler32(currentValue, tmp, 0, tmp.length);
        }
    }

    // ---- Checksum interface factories ----

    /** Creates a new {@link Checksum} that computes CRC-32 using libdeflate. */
    public static Checksum newCrc32() {
        return new Crc32Checksum();
    }

    /** Creates a new {@link Checksum} that computes Adler-32 using libdeflate. */
    public static Checksum newAdler32() {
        return new Adler32Checksum();
    }

    // ---- Checksum implementations ----

    private static final class Crc32Checksum implements Checksum {
        private int value = 0;

        @Override
        public void update(int b) {
            byte[] single = {(byte) b};
            value = nativeCrc32(value, single, 0, 1);
        }

        @Override
        public void update(byte[] b, int off, int len) {
            value = nativeCrc32(value, b, off, len);
        }

        @Override
        public long getValue() {
            return Integer.toUnsignedLong(value);
        }

        @Override
        public void reset() {
            value = 0;
        }
    }

    private static final class Adler32Checksum implements Checksum {
        private int value = 1; // Adler-32 initial value is 1

        @Override
        public void update(int b) {
            byte[] single = {(byte) b};
            value = nativeAdler32(value, single, 0, 1);
        }

        @Override
        public void update(byte[] b, int off, int len) {
            value = nativeAdler32(value, b, off, len);
        }

        @Override
        public long getValue() {
            return Integer.toUnsignedLong(value);
        }

        @Override
        public void reset() {
            value = 1;
        }
    }

    // ---- Helpers ----

    private static void checkBounds(byte[] array, int offset, int length) {
        if (offset < 0 || length < 0 || length > array.length - offset) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", array.length=" + array.length);
        }
    }

    // ---- Native methods ----

    private static native int nativeCrc32(int initial, byte[] data, int offset, int length);

    private static native int nativeCrc32Direct(int initial, ByteBuffer buffer, int offset, int length);

    private static native int nativeAdler32(int initial, byte[] data, int offset, int length);

    private static native int nativeAdler32Direct(int initial, ByteBuffer buffer, int offset, int length);
}
