/*
 * jlibdeflate.c -- JNI bindings for libdeflate.
 *
 * This single file contains all JNI native method implementations.  Native
 * methods are registered via RegisterNatives in JNI_OnLoad rather than using
 * the Java_com_... naming convention.
 */

#include <jni.h>
#include <libdeflate.h>
#include <stdint.h>
#include <string.h>

/* ========================================================================== */
/*  Cached class and method references (populated in JNI_OnLoad)              */
/* ========================================================================== */

static jclass cls_LibdeflateException;
static jclass cls_OutOfMemoryError;

/* ========================================================================== */
/*  Helpers                                                                    */
/* ========================================================================== */

static void throw_exception(JNIEnv *env, const char *msg) {
    (*env)->ThrowNew(env, cls_LibdeflateException, msg);
}

static void throw_oom(JNIEnv *env, const char *msg) {
    (*env)->ThrowNew(env, cls_OutOfMemoryError, msg);
}

/** Maps a libdeflate result code to an error message, or NULL on success. */
static const char *result_message(enum libdeflate_result r) {
    switch (r) {
        case LIBDEFLATE_SUCCESS:            return NULL;
        case LIBDEFLATE_BAD_DATA:           return "Invalid or corrupt compressed data";
        case LIBDEFLATE_SHORT_OUTPUT:       return "Decompressed data is shorter than expected";
        case LIBDEFLATE_INSUFFICIENT_SPACE: return "Output buffer too small for decompressed data";
        default:                            return "Unknown decompression error";
    }
}

/*
 * Compression format enum — these values MUST match the FORMAT_DEFLATE,
 * FORMAT_ZLIB, and FORMAT_GZIP constants in LibdeflateCompressor.java and
 * LibdeflateDecompressor.java.
 */
#define FORMAT_DEFLATE 0
#define FORMAT_ZLIB    1
#define FORMAT_GZIP    2

/* ========================================================================== */
/*  Compressor native methods                                                  */
/* ========================================================================== */

static jlong compressor_alloc(JNIEnv *env, jclass cls, jint level) {
    struct libdeflate_compressor *c = libdeflate_alloc_compressor(level);
    return (jlong)(intptr_t)c;  /* 0 if NULL — Java side checks */
}

static void compressor_free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) {
        libdeflate_free_compressor((struct libdeflate_compressor *)(intptr_t)handle);
    }
}

/**
 * Compresses from byte[] to byte[].  Returns the number of compressed bytes
 * written, or 0 if the output buffer was too small.
 */
static jint compress_bytes(JNIEnv *env, jclass cls, jlong handle, jint format,
                           jbyteArray input, jint inOff, jint inLen,
                           jbyteArray output, jint outOff, jint outLen) {
    struct libdeflate_compressor *c = (struct libdeflate_compressor *)(intptr_t)handle;

    jbyte *in_ptr = (*env)->GetPrimitiveArrayCritical(env, input, NULL);
    if (!in_ptr) return 0;

    jbyte *out_ptr = (*env)->GetPrimitiveArrayCritical(env, output, NULL);
    if (!out_ptr) {
        (*env)->ReleasePrimitiveArrayCritical(env, input, in_ptr, JNI_ABORT);
        return 0;
    }

    size_t result;
    const void *src = in_ptr + inOff;
    void *dst = out_ptr + outOff;

    switch (format) {
        case FORMAT_DEFLATE: result = libdeflate_deflate_compress(c, src, inLen, dst, outLen); break;
        case FORMAT_ZLIB:    result = libdeflate_zlib_compress(c, src, inLen, dst, outLen);    break;
        case FORMAT_GZIP:    result = libdeflate_gzip_compress(c, src, inLen, dst, outLen);    break;
        default:             result = 0; break;
    }

    (*env)->ReleasePrimitiveArrayCritical(env, output, out_ptr, 0);        /* commit output */
    (*env)->ReleasePrimitiveArrayCritical(env, input, in_ptr, JNI_ABORT);  /* discard input (read-only) */

    return (jint)result;
}

/**
 * Compresses from direct ByteBuffer to direct ByteBuffer.
 */
static jint compress_direct(JNIEnv *env, jclass cls, jlong handle, jint format,
                            jobject inputBuf, jint inOff, jint inLen,
                            jobject outputBuf, jint outOff, jint outLen) {
    struct libdeflate_compressor *c = (struct libdeflate_compressor *)(intptr_t)handle;

    jbyte *in_ptr = (jbyte *)(*env)->GetDirectBufferAddress(env, inputBuf);
    jbyte *out_ptr = (jbyte *)(*env)->GetDirectBufferAddress(env, outputBuf);

    if (!in_ptr || !out_ptr) {
        throw_exception(env, "Invalid direct ByteBuffer");
        return 0;
    }

    size_t result;
    switch (format) {
        case FORMAT_DEFLATE: result = libdeflate_deflate_compress(c, in_ptr + inOff, inLen, out_ptr + outOff, outLen); break;
        case FORMAT_ZLIB:    result = libdeflate_zlib_compress(c, in_ptr + inOff, inLen, out_ptr + outOff, outLen);    break;
        case FORMAT_GZIP:    result = libdeflate_gzip_compress(c, in_ptr + inOff, inLen, out_ptr + outOff, outLen);    break;
        default:             result = 0; break;
    }

    return (jint)result;
}

static jlong compress_bound(JNIEnv *env, jclass cls, jlong handle, jint format, jlong inLen) {
    struct libdeflate_compressor *c = (struct libdeflate_compressor *)(intptr_t)handle;

    switch (format) {
        case FORMAT_DEFLATE: return (jlong)libdeflate_deflate_compress_bound(c, (size_t)inLen);
        case FORMAT_ZLIB:    return (jlong)libdeflate_zlib_compress_bound(c, (size_t)inLen);
        case FORMAT_GZIP:    return (jlong)libdeflate_gzip_compress_bound(c, (size_t)inLen);
        default:             return 0;
    }
}

/* ========================================================================== */
/*  Decompressor native methods                                                */
/* ========================================================================== */

static jlong decompressor_alloc(JNIEnv *env, jclass cls) {
    struct libdeflate_decompressor *d = libdeflate_alloc_decompressor();
    return (jlong)(intptr_t)d;
}

static void decompressor_free(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) {
        libdeflate_free_decompressor((struct libdeflate_decompressor *)(intptr_t)handle);
    }
}

/**
 * Decompresses from byte[] to byte[].  Always calls the _ex variant internally.
 * Returns a packed long: (actual_in_nbytes << 32) | (actual_out_nbytes & 0xFFFFFFFF).
 * On error, throws LibdeflateException and returns 0.
 */
static jlong decompress_bytes(JNIEnv *env, jclass cls, jlong handle, jint format,
                              jbyteArray input, jint inOff, jint inLen,
                              jbyteArray output, jint outOff, jint outLen) {
    struct libdeflate_decompressor *d = (struct libdeflate_decompressor *)(intptr_t)handle;
    size_t actual_in = 0, actual_out = 0;

    jbyte *in_ptr = (*env)->GetPrimitiveArrayCritical(env, input, NULL);
    if (!in_ptr) return 0;

    jbyte *out_ptr = (*env)->GetPrimitiveArrayCritical(env, output, NULL);
    if (!out_ptr) {
        (*env)->ReleasePrimitiveArrayCritical(env, input, in_ptr, JNI_ABORT);
        return 0;
    }

    enum libdeflate_result result;
    const void *src = in_ptr + inOff;
    void *dst = out_ptr + outOff;

    switch (format) {
        case FORMAT_DEFLATE:
            result = libdeflate_deflate_decompress_ex(d, src, inLen, dst, outLen, &actual_in, &actual_out);
            break;
        case FORMAT_ZLIB:
            result = libdeflate_zlib_decompress_ex(d, src, inLen, dst, outLen, &actual_in, &actual_out);
            break;
        case FORMAT_GZIP:
            result = libdeflate_gzip_decompress_ex(d, src, inLen, dst, outLen, &actual_in, &actual_out);
            break;
        default:
            result = LIBDEFLATE_BAD_DATA;
            break;
    }

    /* On error, discard output (JNI_ABORT) to avoid corrupting the caller's buffer. */
    int out_release_mode = (result == LIBDEFLATE_SUCCESS) ? 0 : JNI_ABORT;
    (*env)->ReleasePrimitiveArrayCritical(env, output, out_ptr, out_release_mode);
    (*env)->ReleasePrimitiveArrayCritical(env, input, in_ptr, JNI_ABORT);

    const char *msg = result_message(result);
    if (msg != NULL) {
        throw_exception(env, msg);
        return 0;
    }

    return ((jlong)actual_in << 32) | ((jlong)actual_out & 0xFFFFFFFFL);
}

/**
 * Decompresses from direct ByteBuffer to direct ByteBuffer.
 */
static jlong decompress_direct(JNIEnv *env, jclass cls, jlong handle, jint format,
                               jobject inputBuf, jint inOff, jint inLen,
                               jobject outputBuf, jint outOff, jint outLen) {
    struct libdeflate_decompressor *d = (struct libdeflate_decompressor *)(intptr_t)handle;
    size_t actual_in = 0, actual_out = 0;

    jbyte *in_ptr = (jbyte *)(*env)->GetDirectBufferAddress(env, inputBuf);
    jbyte *out_ptr = (jbyte *)(*env)->GetDirectBufferAddress(env, outputBuf);

    if (!in_ptr || !out_ptr) {
        throw_exception(env, "Invalid direct ByteBuffer");
        return 0;
    }

    enum libdeflate_result result;
    switch (format) {
        case FORMAT_DEFLATE:
            result = libdeflate_deflate_decompress_ex(d, in_ptr + inOff, inLen, out_ptr + outOff, outLen, &actual_in, &actual_out);
            break;
        case FORMAT_ZLIB:
            result = libdeflate_zlib_decompress_ex(d, in_ptr + inOff, inLen, out_ptr + outOff, outLen, &actual_in, &actual_out);
            break;
        case FORMAT_GZIP:
            result = libdeflate_gzip_decompress_ex(d, in_ptr + inOff, inLen, out_ptr + outOff, outLen, &actual_in, &actual_out);
            break;
        default:
            result = LIBDEFLATE_BAD_DATA;
            break;
    }

    const char *msg = result_message(result);
    if (msg != NULL) {
        throw_exception(env, msg);
        return 0;
    }

    return ((jlong)actual_in << 32) | ((jlong)actual_out & 0xFFFFFFFFL);
}

/* ========================================================================== */
/*  Checksum native methods                                                    */
/* ========================================================================== */

static jint checksum_crc32(JNIEnv *env, jclass cls,
                           jint initial, jbyteArray data, jint off, jint len) {
    jbyte *ptr = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (!ptr) {
        /* GetPrimitiveArrayCritical may have thrown OOM; if not, throw explicitly. */
        if (!(*env)->ExceptionCheck(env)) {
            throw_oom(env, "Failed to pin array for CRC-32 computation");
        }
        return 0;
    }

    uint32_t result = libdeflate_crc32((uint32_t)initial, ptr + off, (size_t)len);

    (*env)->ReleasePrimitiveArrayCritical(env, data, ptr, JNI_ABORT);
    return (jint)result;
}

static jint checksum_crc32_direct(JNIEnv *env, jclass cls,
                                  jint initial, jobject buf, jint off, jint len) {
    jbyte *ptr = (jbyte *)(*env)->GetDirectBufferAddress(env, buf);
    if (!ptr) {
        throw_exception(env, "Invalid direct ByteBuffer");
        return 0;
    }
    return (jint)libdeflate_crc32((uint32_t)initial, ptr + off, (size_t)len);
}

static jint checksum_adler32(JNIEnv *env, jclass cls,
                             jint initial, jbyteArray data, jint off, jint len) {
    jbyte *ptr = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (!ptr) {
        if (!(*env)->ExceptionCheck(env)) {
            throw_oom(env, "Failed to pin array for Adler-32 computation");
        }
        return 0;
    }

    uint32_t result = libdeflate_adler32((uint32_t)initial, ptr + off, (size_t)len);

    (*env)->ReleasePrimitiveArrayCritical(env, data, ptr, JNI_ABORT);
    return (jint)result;
}

static jint checksum_adler32_direct(JNIEnv *env, jclass cls,
                                    jint initial, jobject buf, jint off, jint len) {
    jbyte *ptr = (jbyte *)(*env)->GetDirectBufferAddress(env, buf);
    if (!ptr) {
        throw_exception(env, "Invalid direct ByteBuffer");
        return 0;
    }
    return (jint)libdeflate_adler32((uint32_t)initial, ptr + off, (size_t)len);
}

/* ========================================================================== */
/*  JNI_OnLoad — register all native methods                                   */
/* ========================================================================== */

#define REGISTER(env, className, methods) do {                                 \
    jclass cls = (*env)->FindClass(env, className);                            \
    if (!cls) return JNI_ERR;                                                  \
    if ((*env)->RegisterNatives(env, cls, methods,                             \
            (jint)(sizeof(methods) / sizeof(methods[0]))) < 0) return JNI_ERR; \
    (*env)->DeleteLocalRef(env, cls);                                          \
} while (0)

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    /* Cache global references to exception classes. */
    jclass local;

    local = (*env)->FindClass(env, "com/fulcrumgenomics/jlibdeflate/LibdeflateException");
    if (!local) return JNI_ERR;
    cls_LibdeflateException = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);

    local = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
    if (!local) return JNI_ERR;
    cls_OutOfMemoryError = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);

    /* ----- LibdeflateCompressor ----- */
    static const JNINativeMethod compressorMethods[] = {
        {"nativeAlloc",         "(I)J",                                (void *)compressor_alloc},
        {"nativeFree",          "(J)V",                                (void *)compressor_free},
        {"nativeCompress",      "(JI[BII[BII)I",                      (void *)compress_bytes},
        {"nativeCompressDirect","(JILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;II)I", (void *)compress_direct},
        {"nativeCompressBound", "(JIJ)J",                              (void *)compress_bound},
    };
    REGISTER(env, "com/fulcrumgenomics/jlibdeflate/LibdeflateCompressor", compressorMethods);

    /* ----- LibdeflateDecompressor ----- */
    static const JNINativeMethod decompressorMethods[] = {
        {"nativeAlloc",            "()J",                                (void *)decompressor_alloc},
        {"nativeFree",             "(J)V",                               (void *)decompressor_free},
        {"nativeDecompress",       "(JI[BII[BII)J",                     (void *)decompress_bytes},
        {"nativeDecompressDirect", "(JILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;II)J", (void *)decompress_direct},
    };
    REGISTER(env, "com/fulcrumgenomics/jlibdeflate/LibdeflateDecompressor", decompressorMethods);

    /* ----- LibdeflateChecksum ----- */
    static const JNINativeMethod checksumMethods[] = {
        {"nativeCrc32",          "(I[BII)I",                            (void *)checksum_crc32},
        {"nativeCrc32Direct",    "(ILjava/nio/ByteBuffer;II)I",        (void *)checksum_crc32_direct},
        {"nativeAdler32",        "(I[BII)I",                            (void *)checksum_adler32},
        {"nativeAdler32Direct",  "(ILjava/nio/ByteBuffer;II)I",        (void *)checksum_adler32_direct},
    };
    REGISTER(env, "com/fulcrumgenomics/jlibdeflate/LibdeflateChecksum", checksumMethods);

    return JNI_VERSION_1_6;
}
