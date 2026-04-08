[![Build](https://github.com/fulcrumgenomics/jlibdeflate/actions/workflows/ci.yml/badge.svg)](https://github.com/fulcrumgenomics/jlibdeflate/actions/workflows/ci.yml)
[![License](http://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/fulcrumgenomics/jlibdeflate/blob/main/LICENSE)

# jlibdeflate

High-performance Java JNI bindings for [libdeflate](https://github.com/ebiggers/libdeflate), a heavily optimized library for DEFLATE, zlib, and gzip compression and decompression.

<p>
<a href="https://fulcrumgenomics.com">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/logos/fulcrumgenomics-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset=".github/logos/fulcrumgenomics-light.svg">
  <img alt="Fulcrum Genomics" src=".github/logos/fulcrumgenomics-light.svg" height="100">
</picture>
</a>
</p>

[Fulcrum Genomics](https://www.fulcrumgenomics.com) - supporting the bioinformatics and computational biology community.

<a href="mailto:contact@fulcrumgenomics.com?subject=[GitHub inquiry]"><img src="https://img.shields.io/badge/Email_us-%2338b44a.svg?&style=for-the-badge&logo=gmail&logoColor=white"/></a>
<a href="https://www.fulcrumgenomics.com"><img src="https://img.shields.io/badge/Visit_Us-%2326a8e0.svg?&style=for-the-badge&logo=wordpress&logoColor=white"/></a>

## Features

- **Fast**: libdeflate is significantly faster than `java.util.zip` for both compression and decompression, using hardware-accelerated SIMD instructions (SSE/AVX2/AVX-512 on x86_64, NEON on ARM)
- **Full format support**: raw DEFLATE (RFC 1951), zlib (RFC 1950), and gzip (RFC 1952)
- **Hardware-accelerated checksums**: CRC-32 and Adler-32 using PCLMUL/SIMD instructions
- **Zero-copy JNI**: uses `GetPrimitiveArrayCritical` for `byte[]` and `GetDirectBufferAddress` for direct `ByteBuffer` to minimize memory copies
- **Compression levels 0–12**: more granularity than zlib's 0–9 range; level 12 uses optimal parsing for best compression ratios
- **Clean resource management**: `AutoCloseable` with `Cleaner` safety net
- **Self-contained**: native libraries for all supported platforms are bundled in the JAR — no system installation required
- **Java 11+**: no preview features, no JDK 17+ dependencies

## Supported Platforms

| OS | Architecture | Status |
|----|-------------|--------|
| Linux | x86_64 | Supported |
| Linux | aarch64 | Supported |
| macOS | x86_64 | Supported |
| macOS | aarch64 (Apple Silicon) | Supported |
| Windows | x86_64 | Supported |

libdeflate automatically detects and uses the best available SIMD instructions at runtime.  A single binary per (OS, architecture) pair handles all CPU variants.

## Installation

### Gradle

```kotlin
dependencies {
    implementation("com.fulcrumgenomics:jlibdeflate:0.1.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.fulcrumgenomics</groupId>
    <artifactId>jlibdeflate</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Usage

### Compression

```java
try (var compressor = new LibdeflateCompressor(6)) {
    // Compress to a new byte[]
    byte[] compressed = compressor.deflateCompress(data);

    // Or compress into a pre-allocated buffer
    int bound = compressor.deflateCompressBound(data.length);
    byte[] output = new byte[bound];
    int written = compressor.deflateCompress(data, 0, data.length, output, 0, output.length);
    // written == -1 if output buffer is too small

    // zlib and gzip formats are also supported
    byte[] zlibData = compressor.zlibCompress(data);
    byte[] gzipData = compressor.gzipCompress(data);
}
```

### Decompression

```java
try (var decompressor = new LibdeflateDecompressor()) {
    // Decompress when you know the exact uncompressed size
    byte[] original = decompressor.deflateDecompress(compressed, originalSize);

    // Extended decompression reports bytes consumed and produced
    byte[] output = new byte[maxSize];
    DecompressionResult result = decompressor.deflateDecompressEx(
        compressed, 0, compressed.length, output, 0, output.length);
    int bytesConsumed = result.inputBytesConsumed();
    int bytesProduced = result.outputBytesProduced();
}
```

### ByteBuffer Support

For high-throughput or parallel workloads, prefer direct `ByteBuffer`s.  Direct buffers use `GetDirectBufferAddress` internally, which avoids array pinning and imposes zero GC pauses.

```java
try (var compressor = new LibdeflateCompressor()) {
    ByteBuffer input = ByteBuffer.allocateDirect(data.length);
    input.put(data).flip();

    ByteBuffer output = ByteBuffer.allocateDirect(compressor.deflateCompressBound(data.length));
    int written = compressor.deflateCompress(input, output);
    output.flip();
}
```

### Checksums

```java
// One-shot computation
int crc = LibdeflateChecksum.crc32(data);
int adler = LibdeflateChecksum.adler32(data);

// Incremental (streaming) via java.util.zip.Checksum interface
Checksum crc32 = LibdeflateChecksum.newCrc32();
crc32.update(chunk1, 0, chunk1.length);
crc32.update(chunk2, 0, chunk2.length);
long value = crc32.getValue();
```

### BGZF Streaming I/O

libdeflate is a whole-buffer compressor/decompressor — it processes an entire block at once rather than streaming through data incrementally.  This makes it unsuitable for reading or writing arbitrary gzip streams.

However, the [BGZF](https://samtools.github.io/hts-specs/SAMv1.pdf) (Blocked GNU Zip Format) format is specifically designed as a series of small, independent gzip members — each at most 64 KB — making it a natural fit for libdeflate's block-at-a-time model.  BGZF is the de facto standard compression format for high-throughput sequencing data in bioinformatics (BAM, CRAM, VCF, BCF, etc.) as defined in the [SAM/BAM format specification](https://samtools.github.io/hts-specs/SAMv1.pdf) maintained by the [hts-specs](https://github.com/samtools/hts-specs) project.

jlibdeflate provides `BgzfOutputStream` and `BgzfInputStream` for streaming BGZF I/O, with full virtual file offset tracking for use with genomic indexes (BAI, CSI, TBI).

> **Important:** `BgzfInputStream` reads only BGZF-formatted data.  It cannot read standard gzip or zlib streams.  Because BGZF files are valid gzip, standard tools like `java.util.zip.GZIPInputStream` and command-line `gzip -d` can read BGZF files, but the reverse is not true — a standard gzip file is a single opaque member that cannot be decomposed into blocks.

#### Writing BGZF

```java
try (var out = new BgzfOutputStream(new FileOutputStream("output.bam"), 6)) {
    long offsetBeforeWrite = out.bgzfPosition(); // virtual file offset
    out.write(record);
    // ...
}
// Stream is automatically closed with EOF marker
```

#### Reading BGZF

```java
try (var in = new BgzfInputStream(new FileInputStream("input.bam"))) {
    long offset = in.bgzfPosition(); // virtual file offset for indexing
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) >= 0) {
        // process decompressed data
    }
}
```

#### Detecting BGZF vs. Standard Gzip

Use `Bgzf.isBgzf()` to peek at a stream's header, or `Bgzf.newGzipInputStream()` to automatically select the right decompression stream:

```java
// Automatic: returns BgzfInputStream for BGZF, GZIPInputStream otherwise
InputStream in = Bgzf.newGzipInputStream(new FileInputStream("input.gz"));

// With a custom fallback for non-BGZF data
InputStream in = Bgzf.newGzipInputStream(rawStream, rawStream -> {
    return new GZIPInputStream(rawStream);
});

// Manual detection (stream must support mark/reset)
BufferedInputStream buffered = new BufferedInputStream(rawStream);
if (Bgzf.isBgzf(buffered)) {
    // BGZF path with virtual offset tracking
} else {
    // standard gzip path
}
```

## Thread Safety

`LibdeflateCompressor` and `LibdeflateDecompressor` instances are **not** thread-safe.  Each thread should use its own instance.  Different threads may safely use different instances concurrently.

`LibdeflateChecksum` static methods and `Checksum` instances follow the same model — static methods are inherently thread-safe, but individual `Checksum` instances are not.

## Performance Notes

- **Buffer pinning**: `byte[]` methods use `GetPrimitiveArrayCritical`, which pins the array in the JVM heap during the compress/decompress call.  The critical section is held only for the duration of the libdeflate call itself (microseconds for typical 64KB blocks) and does not block GC for any appreciable time.
- **Direct ByteBuffer**: For applications that process many buffers in parallel, direct `ByteBuffer` methods are preferable because they use `GetDirectBufferAddress`, which involves no pinning and no GC interaction at all.
- **Reuse instances**: Allocating a compressor or decompressor has modest overhead.  For best performance, create one per thread and reuse it across many operations.

## Development

To build from source you need JDK 11+, CMake 3.14+, and a C compiler (GCC, Clang, or MSVC).

```bash
./gradlew clean build      # compile, build native library, run tests
./gradlew spotlessApply     # auto-format Java code
```

The Gradle build automatically compiles the JNI native library for your current platform via CMake.  No separate native build step is required.

See [CONTRIBUTING.md](CONTRIBUTING.md) for full build instructions, how to bump the bundled libdeflate version, and testing guidelines.

## libdeflate Version

This release bundles **libdeflate v1.25**.

## License

MIT License.  See [LICENSE](LICENSE) for details.

libdeflate itself is also MIT licensed.
