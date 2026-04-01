package com.fulcrumgenomics.jlibdeflate;

/**
 * Exception thrown when a libdeflate operation fails.  Typically indicates
 * corrupt or invalid compressed data, an output buffer that is too small,
 * or a decompressed size mismatch.
 */
public class LibdeflateException extends RuntimeException {

    public LibdeflateException(String message) {
        super(message);
    }

    public LibdeflateException(String message, Throwable cause) {
        super(message, cause);
    }
}
