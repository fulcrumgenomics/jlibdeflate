# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

jlibdeflate provides Java JNI bindings for [libdeflate](https://github.com/ebiggers/libdeflate), a high-performance DEFLATE/zlib/gzip compression library. It ships platform-specific native libraries bundled inside the JAR.

## Build Commands

```bash
./gradlew clean build        # Full build: compile native + Java, run tests
./gradlew test               # Run tests only (native must be built first)
./gradlew spotlessCheck      # Check Java formatting (Palantir Java Format)
./gradlew spotlessApply      # Auto-fix Java formatting
./gradlew buildNative        # Build native library only for current platform
./gradlew javadoc            # Build javadoc
./gradlew currentVersion     # Show current version (derived from git tags)
```

**Full verification before considering work done:** `./gradlew spotlessApply build`

There is no single-test Gradle filter configured; use `./gradlew test --tests 'com.fulcrumgenomics.jlibdeflate.LibdeflateCompressorTest'` to run a specific test class or `--tests '*.LibdeflateCompressorTest.testMethodName'` for a single method.

## Architecture

The codebase has two layers: a C JNI layer and a Java API layer.

### Native Layer (`native/`)
- **`jlibdeflate.c`** — single file containing all JNI native method implementations. Methods are registered via `RegisterNatives` in `JNI_OnLoad` (not `Java_com_...` naming).
- **`libdeflate/`** — git submodule pinned to a release tag. Built as a static library and linked into the JNI shared library to produce one self-contained `.so`/`.dylib`/`.dll` per platform.
- **`CMakeLists.txt`** — CMake build that compiles both libdeflate (static) and the JNI shared library.

### Java Layer (`src/main/java/com/fulcrumgenomics/jlibdeflate/`)
- **`NativeLoader`** — extracts the platform-specific native library from the JAR at runtime and loads it via `System.load()`.
- **`LibdeflateCompressor`** / **`LibdeflateDecompressor`** — `AutoCloseable` wrappers around native compressor/decompressor handles. Support `byte[]` and `ByteBuffer` (both heap and direct). Not thread-safe; use one per thread.
- **`LibdeflateChecksum`** — hardware-accelerated CRC-32 and Adler-32. Static one-shot methods plus `java.util.zip.Checksum` instances for streaming.
- **`DecompressionResult`** — record returned by extended decompression methods reporting bytes consumed/produced.

### JNI Buffer Strategy
- `byte[]` → `GetPrimitiveArrayCritical` (pins array, critical section held only during libdeflate call)
- Direct `ByteBuffer` → `GetDirectBufferAddress` (zero-copy, no GC impact)
- Heap `ByteBuffer` → Java-side extracts backing array, delegates to `byte[]` native method

### Build Integration
Gradle's `buildNative` task runs CMake configure + build, copies the output to `src/main/resources/native/{os}-{arch}/`, and `processResources` depends on it. The `JLIBDEFLATE_PLATFORM` env var can override platform detection for cross-compilation.

## Prerequisites

JDK 11+, CMake 3.14+, C compiler (GCC/Clang/MSVC). Gradle 8.13 is bundled via wrapper.

## Testing Guidelines

- Generate test data programmatically — never commit test data files
- Verify cross-compatibility: jlibdeflate output must be decompressible by `java.util.zip` and vice versa
- Tests use JUnit 5

## Formatting

Java code uses [Palantir Java Format](https://github.com/palantir/palantir-java-format). CI enforces formatting via `spotlessCheck`.

## Versioning

Versions are derived from git tags via axion-release-plugin. Tags like `v0.2.0` produce release version `0.2.0`; untagged commits produce `{next-patch}-SNAPSHOT`.
