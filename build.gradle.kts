plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.diffplug.spotless") version "7.0.2"
    id("pl.allegro.tech.build.axion-release") version "1.21.1"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "com.fulcrumgenomics"
version = scmVersion.version

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
}

spotless {
    java {
        palantirJavaFormat("2.50.0")
        targetExclude("build/**")
    }
}

// ---------------------------------------------------------------------------
// Version management (axion-release-plugin)
// ---------------------------------------------------------------------------

scmVersion {
    tag {
        prefix.set("v")         // tags are v0.1.0, v0.2.0, etc.
        versionSeparator.set("") // no separator between prefix and version
    }
    versionIncrementer("incrementPatch")  // default: bump patch for next SNAPSHOT
}

// ---------------------------------------------------------------------------
// Native build task — compiles JNI glue + libdeflate for the current platform
// ---------------------------------------------------------------------------

/** Returns the canonical platform string, e.g. "osx-aarch64" or "linux-x86_64". */
fun detectPlatform(): String {
    val osName = System.getProperty("os.name").lowercase()
    val os = when {
        osName.contains("mac") || osName.contains("darwin") -> "osx"
        osName.contains("linux") -> "linux"
        osName.contains("win") -> "windows"
        else -> error("Unsupported OS: $osName")
    }
    val archName = System.getProperty("os.arch").lowercase()
    val arch = when {
        archName == "amd64" || archName == "x86_64" -> "x86_64"
        archName == "aarch64" || archName == "arm64" -> "aarch64"
        else -> error("Unsupported architecture: $archName")
    }
    return "$os-$arch"
}

// Allow CI to override the target platform (e.g., cross-compiling osx-x86_64 on an aarch64 runner)
val platform = System.getenv("JLIBDEFLATE_PLATFORM") ?: detectPlatform()
val cmakeBuildDir = layout.buildDirectory.dir("native/$platform").map { it.asFile }
val nativeOutputDir = file("src/main/resources/native/$platform")

val libExtension = when {
    platform.startsWith("osx") -> "dylib"
    platform.startsWith("linux") -> "so"
    else -> "dll"
}
val libName = if (platform.startsWith("win")) "jlibdeflate.$libExtension"
              else "libjlibdeflate.$libExtension"

val cmakeConfigure by tasks.registering(Exec::class) {
    inputs.dir("native")
    outputs.dir(cmakeBuildDir)

    // Skip if the native library was already provided (e.g., by CI artifact download)
    onlyIf { !nativeOutputDir.resolve(libName).exists() }

    doFirst { cmakeBuildDir.get().mkdirs() }

    workingDir = cmakeBuildDir.get()

    // Support cross-compilation on macOS (e.g., building x86_64 on an aarch64 runner)
    val cmakeArgs = mutableListOf("cmake", "-DCMAKE_BUILD_TYPE=Release")
    val targetArch = platform.substringAfter("-")
    val hostArch = detectPlatform().substringAfter("-")
    if (platform.startsWith("osx") && targetArch != hostArch) {
        val osxArch = if (targetArch == "x86_64") "x86_64" else "arm64"
        cmakeArgs.add("-DCMAKE_OSX_ARCHITECTURES=$osxArch")
    }
    cmakeArgs.add(file("native").absolutePath)
    commandLine(cmakeArgs)
}

val buildNative by tasks.registering(Exec::class) {
    dependsOn(cmakeConfigure)

    inputs.dir("native")
    outputs.file(nativeOutputDir.resolve(libName))

    // Skip if the native library was already provided (e.g., by CI artifact download)
    onlyIf { !nativeOutputDir.resolve(libName).exists() }

    workingDir = cmakeBuildDir.get()
    commandLine("cmake", "--build", ".", "--config", "Release")

    doLast {
        nativeOutputDir.mkdirs()
        cmakeBuildDir.get().resolve(libName).copyTo(nativeOutputDir.resolve(libName), overwrite = true)
    }
}

tasks.named("processResources") {
    dependsOn(buildNative)
}

tasks.named("sourcesJar") {
    dependsOn(buildNative)
}

// ---------------------------------------------------------------------------
// Publishing & signing
// ---------------------------------------------------------------------------

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("jlibdeflate")
                description.set("High-performance Java JNI bindings for libdeflate")
                url.set("https://github.com/fulcrumgenomics/jlibdeflate")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("tfenne")
                        name.set("Tim Fennell")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/fulcrumgenomics/jlibdeflate.git")
                    developerConnection.set("scm:git:ssh://github.com/fulcrumgenomics/jlibdeflate.git")
                    url.set("https://github.com/fulcrumgenomics/jlibdeflate")
                }
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            // This URL is the Central Portal's compatibility shim for the Nexus staging API.
            // It is the correct endpoint for gradle-nexus-publish-plugin 2.x with Central Portal accounts.
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(providers.environmentVariable("SONATYPE_USER"))
            password.set(providers.environmentVariable("SONATYPE_PASS"))
        }
    }
}

signing {
    // Only sign release builds when PGP_SECRET is available.
    // SNAPSHOTs do not need signing and Sonatype Central rejects signed SNAPSHOTs.
    val signingKey = providers.environmentVariable("PGP_SECRET")
    val signingPassword = providers.environmentVariable("PGP_PASSPHRASE")
    if (signingKey.isPresent && !version.toString().endsWith("-SNAPSHOT")) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.getOrElse(""))
        sign(publishing.publications["mavenJava"])
    }
}
