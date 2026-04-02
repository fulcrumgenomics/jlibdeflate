package com.fulcrumgenomics.jlibdeflate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

/**
 * Loads the jlibdeflate native library.  The library is bundled inside the JAR
 * at {@code /native/{os}-{arch}/libjlibdeflate.{ext}} and is extracted to a
 * temporary file at load time.
 *
 * <p>Set the system property {@code jlibdeflate.library.path} to a directory
 * containing the native library to skip JAR extraction (useful during
 * development).
 */
final class NativeLoader {

    private static volatile boolean loaded = false;

    private NativeLoader() {}

    static synchronized void load() {
        if (loaded) return;

        // Allow overriding with a system property for development
        String overridePath = System.getProperty("jlibdeflate.library.path");
        if (overridePath != null) {
            String libName = System.mapLibraryName("jlibdeflate");
            System.load(Paths.get(overridePath, libName).toString());
            loaded = true;
            return;
        }

        String platform = detectPlatform();
        String libExtension = platform.startsWith("osx") ? "dylib" : platform.startsWith("linux") ? "so" : "dll";
        // Windows DLLs don't use the "lib" prefix; Linux/macOS shared libraries do
        String libName = platform.startsWith("win") ? "jlibdeflate." + libExtension : "libjlibdeflate." + libExtension;
        String resourcePath = "/native/" + platform + "/" + libName;

        try (InputStream is = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new UnsatisfiedLinkError("Native library not found in JAR at " + resourcePath
                        + ". Supported platforms: linux-x86_64, linux-aarch64, osx-x86_64, osx-aarch64,"
                        + " windows-x86_64. Current platform: " + platform);
            }

            // Use restrictive permissions to prevent TOCTOU attacks in shared /tmp
            Path tempFile;
            try {
                tempFile = Files.createTempFile(
                        "jlibdeflate-",
                        "." + libExtension,
                        PosixFilePermissions.asFileAttribute(
                                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } catch (UnsupportedOperationException e) {
                // Non-POSIX filesystem (e.g., Windows) — fall back to default permissions
                tempFile = Files.createTempFile("jlibdeflate-", "." + libExtension);
            }
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            System.load(tempFile.toAbsolutePath().toString());

            // On Unix the file handle keeps the library mapped even after deletion.
            // On Windows this may fail, so we also register deleteOnExit as a fallback.
            try {
                Files.delete(tempFile);
            } catch (IOException ignored) {
                tempFile.toFile().deleteOnExit();
            }
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        }

        loaded = true;
    }

    /**
     * Returns a canonical platform string like "osx-aarch64" or "linux-x86_64".
     */
    private static String detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String os;
        if (osName.contains("mac") || osName.contains("darwin")) {
            os = "osx";
        } else if (osName.contains("linux")) {
            os = "linux";
        } else if (osName.contains("win")) {
            os = "windows";
        } else {
            throw new UnsatisfiedLinkError("Unsupported operating system: " + osName);
        }

        String archName = System.getProperty("os.arch", "").toLowerCase();
        String arch;
        if (archName.equals("amd64") || archName.equals("x86_64")) {
            arch = "x86_64";
        } else if (archName.equals("aarch64") || archName.equals("arm64")) {
            arch = "aarch64";
        } else {
            throw new UnsatisfiedLinkError("Unsupported architecture: " + archName);
        }

        return os + "-" + arch;
    }
}
