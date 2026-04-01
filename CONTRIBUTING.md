# Contributing to jlibdeflate

## Prerequisites

To build jlibdeflate from source, you need:

| Tool | Minimum Version | Notes |
|------|----------------|-------|
| JDK | 11 | Any distribution (Temurin, Corretto, etc.). JDK 17+ also works. |
| CMake | 3.14 | For building the native library. `brew install cmake` (macOS) or `apt install cmake` (Linux). |
| C compiler | GCC 4.9+, Clang 3.9+, or MSVC 2015+ | Xcode Command Line Tools on macOS (`xcode-select --install`). |
| Gradle | 8.13 | Bundled via the Gradle wrapper — no separate install needed. |

## Building

### Full build (Java + native + tests)

```bash
./gradlew clean build
```

This will:
1. Compile the JNI C code and libdeflate for your current platform
2. Copy the native library into `src/main/resources/native/{platform}/`
3. Compile the Java source code
4. Run all tests

### Native library only

```bash
./gradlew buildNative
```

Builds the native shared library for the current platform.  Output goes to `src/main/resources/native/{os}-{arch}/`.

### Tests only

```bash
./gradlew test
```

Requires the native library to be built first (happens automatically).

### Formatting

Java source code is formatted with [Palantir Java Format](https://github.com/palantir/palantir-java-format).

```bash
# Check formatting
./gradlew spotlessCheck

# Auto-fix formatting
./gradlew spotlessApply
```

## Project Structure

```
jlibdeflate/
├── build.gradle.kts              # Gradle build configuration
├── settings.gradle.kts           # Gradle settings
├── native/
│   ├── CMakeLists.txt            # CMake build for JNI + libdeflate
│   ├── jlibdeflate.c             # JNI native method implementations
│   └── libdeflate/               # Git submodule (pinned to release tag)
├── src/
│   ├── main/java/...             # Java source code
│   ├── main/resources/native/    # Platform-specific native libraries
│   └── test/java/...             # Test source code
├── README.md
├── CONTRIBUTING.md
└── LICENSE
```

## Native Code Architecture

All JNI code lives in a single file: `native/jlibdeflate.c`.

- Native methods are registered via `RegisterNatives` in `JNI_OnLoad` (not the traditional `Java_com_...` naming convention)
- libdeflate is built as a **static** library and linked into the JNI shared library, producing a single self-contained `.so`/`.dylib` per platform
- The shared library is extracted from the JAR at runtime and loaded via `System.load()`

### JNI Buffer Strategy

- **`byte[]` inputs**: `GetPrimitiveArrayCritical` pins the array in JVM memory.  The critical section is held only during the compress/decompress call (microseconds), then released immediately.
- **Direct `ByteBuffer` inputs**: `GetDirectBufferAddress` provides zero-copy access with no GC impact.
- **Heap `ByteBuffer` inputs**: Java extracts the backing array and delegates to the `byte[]` native method.

## Bumping the libdeflate Version

libdeflate is included as a git submodule pinned to a specific release tag.

```bash
cd native/libdeflate
git fetch --tags
git checkout v1.XX       # desired version tag
cd ../..
git add native/libdeflate
./gradlew clean build    # verify everything works
git commit -m "Bump libdeflate to v1.XX"
```

After bumping:
1. Run the full test suite
2. Update the version reference in `README.md`
3. Test on all supported platforms (via CI or manual testing)

## Testing Guidelines

- **Generate test data programmatically** — do not commit test data files
- **Test function, not implementation** — tests should survive refactoring
- **Prefer many small individual tests** over parameterized/table-driven tests
- **Cross-compatibility**: verify that jlibdeflate output is decompressible by `java.util.zip` and vice versa
- Test expected results, error conditions, and boundary cases

## Versioning

Versions are derived automatically from git tags using the [axion-release-plugin](https://github.com/allegro/axion-release-plugin).  There is no version string in any build file.

- **Tagged commits** (e.g., `v0.2.0`) produce release version `0.2.0`
- **Untagged commits** after a tag produce `{next-patch}-SNAPSHOT` (e.g., `0.2.1-SNAPSHOT`)
- **No tags at all** produces `0.1.0-SNAPSHOT` (the default initial version)

Check the current version:

```bash
./gradlew currentVersion
```

## Release Process

Releases are fully automated via GitHub Actions.

### SNAPSHOT Releases

Every push to `main` automatically publishes a SNAPSHOT to Sonatype Central's snapshot repository.  No manual steps needed.

### Production Releases

1. Ensure CI is green on `main`
2. Tag the commit and push:
   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```
3. The [Release workflow](.github/workflows/release.yml) will:
   - Build native libraries for all 4 platforms (linux-x86_64, linux-aarch64, osx-x86_64, osx-aarch64)
   - Assemble a fat JAR containing all native libraries
   - Sign and publish to Maven Central
   - Create a GitHub Release with the JAR attached

### Required Repository Secrets

Publishing requires the following secrets in the GitHub repository settings:

| Secret | Purpose |
|--------|---------|
| `SONATYPE_USER` | Sonatype Central Portal token username |
| `SONATYPE_PASS` | Sonatype Central Portal token password |
| `PGP_SECRET` | ASCII-armored GPG private key for artifact signing |
| `PGP_PASSPHRASE` | GPG key passphrase |

Generate Sonatype tokens at [central.sonatype.com/usertoken](https://central.sonatype.com/usertoken).

The GPG public key must be published to `keyserver.ubuntu.com` for Maven Central verification:

```bash
gpg --send-keys --keyserver keyserver.ubuntu.com YOUR_KEY_ID
```
