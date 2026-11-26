// Needed by fetchLlamaCpp's tarball pin check (`java` alone is the Gradle Java extension here).
import java.security.MessageDigest
// `java.util.Properties` cannot be spelled inline below: `java` is the Gradle Java extension.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.torrent.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.torrent.app"
        minSdk = 26
        targetSdk = 35
        // The four daemons are cross-compiled for arm64-v8a only. Without this the APK still
        // advertises armeabi-v7a/x86/x86_64 (androidx's graphics-path helper ships those), so a
        // 32-bit or emulator install *succeeds* and then fails at daemon start.
        ndk {
            abiFilters += "arm64-v8a"
        }
        versionCode = 8
        versionName = "1.0.2"
    }

    // Release signing comes from keystore.properties at the repo root, which is gitignored. A
    // builder without that file (F-Droid's buildserver, CI, anyone cloning this repo) must still
    // get a working build, so the file is optional: without it the release build type simply
    // stays unsigned and gets signed by whoever is building.
    val keystoreFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // null when keystore.properties is absent (F-Droid / CI): unsigned, not broken.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Stage 09: keyword files copied from the dht_crawler sources by the
    // copyDhtCrawlerAssets task; config.ini.default is checked in under src/main/assets.
    sourceSets["main"].assets.srcDir("build/generated/assets/dht_crawler")

    // Robolectric unit tests load app resources
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Production code calls android.util.Log (CrawlerProxyServer's bind
            // failure / bad-gateway paths); return defaults instead of throwing
            // "not mocked" in JVM unit tests.
            isReturnDefaultValues = true
            // Robolectric stages ~660 MB of model assets per EnsureLayoutTest case into
            // java.io.tmpdir; on this build host /tmp is a small, shared, quota'd tmpfs
            // ("Disk quota exceeded" with the 478 MiB movie-kind backbone). Keep that
            // churn on the build volume instead.
            all {
                val unitTestTmp = project.layout.buildDirectory.dir("tmp/unit-tests").get().asFile
                unitTestTmp.mkdirs()
                it.systemProperty("java.io.tmpdir", unitTestTmp.absolutePath)
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
        }
    }

// ---- Native build knobs (F-Droid / hermetic builds) ----------------------------------------
// All unset on a developer build, where every fetch task downloads and sha256-verifies its own
// inputs into app/build/native, exactly as before.

// -PnativeRoot=<dir>: where the native source trees and their build/install dirs live. Default
// app/build/native. F-Droid runs `gradle clean` *after* `prebuild`, and clean deletes build/, so
// a srclib tree staged under it would be wiped before the build even starts — the fdroiddata
// recipe points this at app/native-root instead, which clean leaves alone.
val nativeRoot = providers.gradleProperty("nativeRoot").orNull?.let { file(it) }
    ?: file("build/native")

// -PprebuiltNativeSrc: fetchLlamaCpp and fetchCloudflaredSrc accept a pre-staged tree and skip
// their tarball entirely (they are the only two fetches that hash the archive unconditionally).
// The caller that staged the tree owns the pin.
val prebuiltNativeSrc = providers.gradleProperty("prebuiltNativeSrc").isPresent

// GOTOOLCHAIN for the cloudflared build. cloudflared's go.mod requires go1.27.4, so the default
// makes a first build download that toolchain; -PgoToolchain=local pins to the host's Go.
val goToolchain = providers.gradleProperty("goToolchain").orNull ?: "go1.27.4"

// -PgoVendor builds the tunnel with -mod=vendor and GOPROXY=off: no module downloads at all.

// Strip a just-built daemon before it is copied into jniLibs. AGP's own
// stripReleaseDebugSymbols leaves these files alone (they are PIE executables, not shared
// objects) and ships ~90 MB of debug_info per daemon in the APK — a clean fdroid build of the
// same tree is 16.7 MB against our 36 MB. Stripping here, inside the producing task, keeps the
// declared output identical to what gets packaged: no "build writes 94 MB, strip shrinks it"
// churn that would make the native tasks permanently out of date.
fun stripDaemon(binary: File, ndkDir: File) {
    if (!binary.exists()) return
    val strip = File(ndkDir, "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")
    if (!strip.exists()) {
        logger.warn("llvm-strip not found under $ndkDir — ${binary.name} stays unstripped")
        return
    }
    project.exec {
        commandLine(strip.absolutePath, "--strip-unneeded", binary.absolutePath)
    }
}

// Fetch a pinned source archive, unless a hermetic builder (F-Droid) staged the tree itself.
// Deliberately not silent: if the builder claims a staged tree that is not there, fail loudly
// rather than downloading and producing a build whose inputs came from somewhere else.
fun stageOrFetch(srcDir: File, archive: File, url: String) {
    if (prebuiltNativeSrc) {
        if (!srcDir.exists()) {
            error("prebuiltNativeSrc is set but $srcDir is missing — stage ${srcDir.name} there")
        }
        return
    }
    if (archive.exists()) return
    archive.parentFile.mkdirs()
    project.exec {
        commandLine("curl", "-L", "--fail", "-o", archive.absolutePath, url)
    }
}
val goVendor = providers.gradleProperty("goVendor").isPresent

tasks.register("buildMbedtls") {
    description = "Cross-compile mbedTLS 3.6.6 static libs for arm64 (Stage 04 dep)"

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainFile = file("$ndkDir/build/cmake/android.toolchain.cmake")

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/mbedtls-3.6.6")
    val buildDir = file("$nativeDir/build-mbedtls")
    val installDir = file("$nativeDir/mbedtls-install")
    val tarball = file("$nativeDir/src/mbedtls-3.6.6.tar.bz2")
    val url = "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.6/mbedtls-3.6.6.tar.bz2"

    outputs.dir(installDir)

    doLast {
        // 1. Fetch the tarball, unless a hermetic builder staged the tree (step 2 then no-ops).
        stageOrFetch(srcDir, tarball, url)
        // 2. Extract if missing
        if (!srcDir.exists()) {
            project.exec {
                commandLine("tar", "-xjf", tarball.absolutePath, "-C", srcDir.parentFile.absolutePath)
            }
        }
        // 3. Configure (Ninja generator + NDK toolchain, static only)
        project.exec {
            commandLine(
                "cmake", "-G", "Ninja",
                "-S", srcDir.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_INSTALL_PREFIX=${installDir.absolutePath}",
                "-DUSE_SHARED_MBEDTLS_LIBRARY=OFF",
                "-DUSE_STATIC_MBEDTLS_LIBRARY=ON",
                "-DENABLE_TESTING=OFF",
                "-DENABLE_PROGRAMS=OFF"
            )
        }
        // 4. Build + install
        project.exec {
            commandLine("cmake", "--build", buildDir.absolutePath, "--target", "install", "-j8")
        }
    }
}
tasks.register("buildCurl") {
    description = "Cross-compile libcurl 8.20.0 static with mbedTLS for arm64 (Stage 04 dep)"
    dependsOn("buildMbedtls")

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainBin = file("$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/bin")
    val cc = file("$toolchainBin/aarch64-linux-android24-clang")
    val ar = file("$toolchainBin/llvm-ar")
    val ranlib = file("$toolchainBin/llvm-ranlib")

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/curl-8.20.0")
    val mbedtlsInstall = file("$nativeDir/mbedtls-install")
    val installDir = file("$nativeDir/curl-install")
    val tarball = file("$nativeDir/src/curl-8.20.0.tar.gz")
    val curlUrl = "https://curl.se/download/curl-8.20.0.tar.gz"

    outputs.dir(installDir)

    doLast {
        // 1. Fetch the tarball, unless a hermetic builder staged the tree (step 2 then no-ops).
        stageOrFetch(srcDir, tarball, curlUrl)
        // 2. Extract if missing
        if (!srcDir.exists()) {
            project.exec {
                commandLine("tar", "-xzf", tarball.absolutePath, "-C", srcDir.parentFile.absolutePath)
            }
        }
        // 3. Configure (autotools, cross-compile via --host + env vars)
        project.exec {
            workingDir = srcDir
            environment("CC", cc.absolutePath)
            environment("AR", ar.absolutePath)
            environment("RANLIB", ranlib.absolutePath)
            environment("CFLAGS", "-fPIC -Os")
            environment("LDFLAGS", "-L${mbedtlsInstall.absolutePath}/lib")
            environment("CPPFLAGS", "-I${mbedtlsInstall.absolutePath}/include")
            environment("PKG_CONFIG_LIBDIR", "/dev/null")
            commandLine(
                "./configure",
                "--host=aarch64-linux-android",
                "--prefix=${installDir.absolutePath}",
                "--with-mbedtls=${mbedtlsInstall.absolutePath}",
                "--without-nghttp2", "--without-ngtcp2", "--without-nghttp3",
                "--without-librtmp", "--without-libssh2",
                "--without-zlib", "--without-libpsl",
                "--disable-shared", "--enable-static",
                "--disable-ldap", "--disable-ldaps",
                "--disable-manual", "--disable-docs",
                "--disable-rtsp", "--disable-dict", "--disable-telnet", "--disable-tftp",
                "--disable-pop3", "--disable-imap", "--disable-smtp", "--disable-gopher",
                "--disable-mqtt", "--disable-smb"
            )
        }
        // 4. Build + install
        project.exec {
            workingDir = srcDir
            commandLine("make", "-j8")
        }
        project.exec {
            workingDir = srcDir
            commandLine("make", "install")
        }
    }
}
tasks.register("buildTransmission") {
    val txVersion = "4.1.4"
    description = "Cross-compile transmission-daemon $txVersion for arm64, package libtransmissiond.so"
    dependsOn("buildCurl")

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainFile = file("$ndkDir/build/cmake/android.toolchain.cmake")

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/transmission-$txVersion")
    val curlInstall = file("$nativeDir/curl-install")
    val mbedtlsInstall = file("$nativeDir/mbedtls-install")
    val buildDir = file("$nativeDir/build-transmission")
    val daemonOut = file("$buildDir/daemon/transmission-daemon")
    val jniLibsArm64 = file("src/main/jniLibs/arm64-v8a")
    val tarball = file("$nativeDir/src/transmission-$txVersion.tar.xz")
    // Official release tarball (includes vendored third-party submodules)
    val url = "https://github.com/transmission/transmission/releases/download/$txVersion/transmission-$txVersion.tar.xz"

    outputs.file("$jniLibsArm64/libtransmissiond.so")
    inputs.file("src/main/cpp/transmission-fork/file-saf.h")
    inputs.file("src/main/cpp/transmission-fork/file-saf.cc")
    inputs.file("src/main/cpp/transmission-fork/disk-timing.h")
    inputs.file("src/main/cpp/transmission-fork/disk-queue.h")
    inputs.file("src/main/cpp/transmission-fork/disk-io.h")
    inputs.file("src/main/cpp/transmission-fork/disk-io.cc")
    inputs.file("src/main/cpp/transmission-fork/apply_fork.py")
    // Host harness for the BEP51 responder (tests/run_dht_bep51.sh compiles the
    // patched third-party/dht/dht.c with plain gcc; not Gradle-invoked, but a
    // fork file must be registered so a fork change is visible to this task).
    inputs.file("src/main/cpp/transmission-fork/tests/dht_bep51_test.c")
    inputs.file("src/main/cpp/transmission-fork/tests/run_dht_bep51.sh")
    // A version bump must invalidate the cached .so: the doLast below is the
    // only thing that runs the fetch/patch/configure/build steps.
    inputs.property("transmissionVersion", txVersion)

    doLast {
        // 1. Fetch the tarball, unless a hermetic builder staged the tree (step 2 then no-ops).
        stageOrFetch(srcDir, tarball, url)
        // 2. Extract if missing
        if (!srcDir.exists()) {
            project.exec {
                commandLine("tar", "-xJf", tarball.absolutePath, "-C", srcDir.parentFile.absolutePath)
            }
        }
        // 2a. A CMake cache left over from a different source tree (version bump)
        //     makes configure fail with a home-directory mismatch; drop it so the
        //     tree is configured from scratch.
        val cacheFile = file("$buildDir/CMakeCache.txt")
        if (cacheFile.exists()) {
            val cachedSrc = cacheFile.readLines()
                .firstOrNull { it.startsWith("CMAKE_HOME_DIRECTORY:") }
                ?.substringAfter("=")
            if (cachedSrc != srcDir.absolutePath) {
                buildDir.deleteRecursively()
            }
        }
        // 2b. Apply the SAF storage fork (copy file-saf.* + patch file-posix/file-capacity/CMakeLists).
        project.exec {
            commandLine("python3", file("src/main/cpp/transmission-fork/apply_fork.py").absolutePath, srcDir.absolutePath)
        }
        // 3. Configure (Ninja + NDK toolchain, dynamic PIE, static deps, mbedtls crypto)
        project.exec {
            commandLine(
                "cmake", "-G", "Ninja",
                "-S", srcDir.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_PREFIX_PATH=${curlInstall.absolutePath};${mbedtlsInstall.absolutePath}",
                // Force our static curl
                "-DCURL_INCLUDE_DIR=${curlInstall.absolutePath}/include",
                "-DCURL_LIBRARY=${curlInstall.absolutePath}/lib/libcurl.a",
                // mbedTLS (FindMbedTLS.cmake vars)
                "-DWITH_CRYPTO=mbedtls",
                "-DMBEDTLS_INCLUDE_DIR=${mbedtlsInstall.absolutePath}/include",
                "-DMBEDTLS_LIBRARY=${mbedtlsInstall.absolutePath}/lib/libmbedtls.a",
                "-DMBEDCRYPTO_LIBRARY=${mbedtlsInstall.absolutePath}/lib/libmbedcrypto.a",
                // Build scope: daemon only
                "-DENABLE_DAEMON=ON",
                "-DENABLE_GTK=OFF", "-DENABLE_QT=OFF", "-DENABLE_CLI=OFF",
                "-DENABLE_UTILS=OFF", "-DENABLE_TESTS=OFF", "-DENABLE_NLS=OFF",
                "-DRUN_CLANG_TIDY=OFF",
                // Force vendored third-party (built static), so no system libevent/etc needed
                "-DUSE_SYSTEM_DHT=OFF", "-DUSE_SYSTEM_MINIUPNPC=OFF", "-DUSE_SYSTEM_NATPMP=OFF",
                "-DUSE_SYSTEM_UTP=OFF", "-DUSE_SYSTEM_B64=OFF", "-DUSE_SYSTEM_PSL=OFF",
                "-DUSE_SYSTEM_DEFLATE=OFF", "-DUSE_SYSTEM_EVENT2=OFF",
                // Resolve mbedTLS symbols referenced by static libcurl; -llog for __android_log
                // Help FindThreads during cross-compilation (Bionic pthreads in libc, no -lpthread)
                "-DCMAKE_USE_PTHREADS_INIT=1", "-DCMAKE_THREAD_LIBS_INIT=",
                "-DCMAKE_EXE_LINKER_FLAGS=-L${mbedtlsInstall.absolutePath}/lib -L${curlInstall.absolutePath}/lib -lmbedx509 -lmbedcrypto -llog"
            )
        }
        // 4. Build only the daemon target
        project.exec {
            commandLine("cmake", "--build", buildDir.absolutePath, "--target", "transmission-daemon", "-j8")
        }
        // 5. Strip + copy + rename to jniLibs (lib*.so name required for packaging)
        stripDaemon(daemonOut, ndkDir)
        copy {
            from(daemonOut)
            into(jniLibsArm64)
            rename { "libtransmissiond.so" }
        }
    }
}

tasks.register("buildLibuv") {
    description = "Cross-compile libuv 1.48.0 static lib for arm64 (Stage 09 dep)"

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainFile = file("$ndkDir/build/cmake/android.toolchain.cmake")

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/libuv-v1.48.0")
    val buildDir = file("$nativeDir/build-libuv")
    val installDir = file("$nativeDir/libuv-install")
    val tarball = file("$nativeDir/src/libuv-v1.48.0.tar.gz")
    val url = "https://dist.libuv.org/dist/v1.48.0/libuv-v1.48.0.tar.gz"

    outputs.dir(installDir)

    doLast {
        // 1. Fetch the tarball, unless a hermetic builder staged the tree (step 2 then no-ops).
        stageOrFetch(srcDir, tarball, url)
        // 2. Extract if missing
        if (!srcDir.exists()) {
            project.exec {
                commandLine("tar", "-xzf", tarball.absolutePath, "-C", srcDir.parentFile.absolutePath)
            }
        }
        // 3. Configure (Ninja generator + NDK toolchain, static only)
        project.exec {
            commandLine(
                "cmake", "-G", "Ninja",
                "-S", srcDir.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_INSTALL_PREFIX=${installDir.absolutePath}",
                "-DLIBUV_BUILD_SHARED=OFF",
                "-DLIBUV_BUILD_TESTS=OFF",
                "-DLIBUV_BUILD_BENCH=OFF"
            )
        }
        // 4. Build + install
        project.exec {
            commandLine("cmake", "--build", buildDir.absolutePath, "--target", "install", "-j8")
        }
    }
}
tasks.register("fetchSqlite") {
    description = "Download the SQLite 3.49.1 amalgamation sources (Stage 09 dep)"

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/sqlite-amalgamation")
    val zipFile = file("$nativeDir/src/sqlite-amalgamation-3490100.zip")
    val url = "https://www.sqlite.org/2025/sqlite-amalgamation-3490100.zip"

    outputs.dir(srcDir)

    doLast {
        // 1. Fetch zip if missing. Not routed through stageOrFetch: the amalgamation is a
        // generated release artifact, not something sqlite's git tree contains (it needs tclsh),
        // so a staged srclib checkout cannot replace this download.
        if (!zipFile.exists()) {
            zipFile.parentFile.mkdirs()
            project.exec {
                commandLine("curl", "-L", "--fail", "-o", zipFile.absolutePath, url)
            }
        }
        // 2. Extract + flatten so sqlite3.c/sqlite3.h sit directly under srcDir
        if (!file("$srcDir/sqlite3.c").exists()) {
            project.exec {
                commandLine("unzip", "-o", zipFile.absolutePath, "-d", nativeDir.absolutePath)
            }
            srcDir.deleteRecursively()
            project.exec {
                commandLine("mv", file("$nativeDir/sqlite-amalgamation-3490100").absolutePath, srcDir.absolutePath)
            }
        }
    }
}
// The movie-kind model is trained and quantised against this exact llama.cpp commit, so
// the tarball is pinned by sha256 -- unlike the existence-only fetches above, a mismatched
// download is deleted and re-fetched rather than used (a tree of unclear provenance would
// silently produce a binary the 153 MB model was not fitted to).
tasks.register("fetchLlamaCpp") {
    description = "Download the pinned llama.cpp sources (18ab62a8) for the movie-kind dep"

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/llama-18ab62a8")
    val tarball = file("$nativeDir/src/llama-18ab62a8.tar.gz")
    val stamp = file("$srcDir/.sha")
    val url =
        "https://github.com/ggml-org/llama.cpp/archive/18ab62a8ec5144ae5d2f65e78374ba69652d75dd.tar.gz"
    val sha256 = "34bd70e229bf0439575b7865e986209d23584bd35274d95c256f9ccf398ab46a"
    // tar's own top-level directory name; renamed to srcDir below so the path is pin-stable.
    val archiveRoot = file("$nativeDir/src/llama.cpp-18ab62a8ec5144ae5d2f65e78374ba69652d75dd")

    outputs.dir(srcDir)

    fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    doLast {
        // Hermetic builder (F-Droid): the tree was staged from a pinned checkout by a prebuild
        // step, so there is no tarball to hash and nothing to download.
        if (prebuiltNativeSrc) {
            if (!file("$srcDir/CMakeLists.txt").exists()) {
                error("prebuiltNativeSrc is set but $srcDir has no CMakeLists.txt — stage the " +
                    "pinned llama.cpp checkout (18ab62a8) there")
            }
            stamp.writeText("prebuilt\n")
            return@doLast
        }
        // 1. Fetch the tarball, discarding a cached copy whose bytes are not the pin
        if (tarball.exists() && sha256Of(tarball) != sha256) {
            logger.warn("llama.cpp tarball sha256 mismatch, re-downloading $url")
            tarball.delete()
        }
        if (!tarball.exists()) {
            tarball.parentFile.mkdirs()
            project.exec {
                commandLine("curl", "-L", "--fail", "-o", tarball.absolutePath, url)
            }
        }
        // 2. Verify before extracting -- this is the pin every later stage builds against
        val actual = sha256Of(tarball)
        if (actual != sha256) {
            error("llama.cpp tarball sha256 mismatch: expected $sha256, got $actual ($url)")
        }
        // 3. Extract (re-extract when the tree is missing or came from a different pin)
        if (srcDir.exists() && (!stamp.exists() || stamp.readText().trim() != sha256)) {
            srcDir.deleteRecursively()
        }
        if (!file("$srcDir/CMakeLists.txt").exists()) {
            archiveRoot.deleteRecursively()
            project.exec {
                commandLine("tar", "-xzf", tarball.absolutePath, "-C", file("$nativeDir/src").absolutePath)
            }
            srcDir.deleteRecursively()
            project.exec {
                commandLine("mv", archiveRoot.absolutePath, srcDir.absolutePath)
            }
        }
        stamp.writeText("$sha256\n")
    }
}
tasks.register("buildLlama") {
    description = "Cross-compile llama.cpp (CPU-only, pinned 18ab62a8) static libs for arm64 (movie-kind dep)"
    dependsOn("fetchLlamaCpp")

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainFile = file("$ndkDir/build/cmake/android.toolchain.cmake")

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/llama-18ab62a8")
    val buildDir = file("$nativeDir/build-llama")
    val installDir = file("$nativeDir/llama-install")

    outputs.dir(installDir)

    doLast {
        // 1. Configure (Ninja generator + NDK toolchain, CPU-only inference, static libs)
        project.exec {
            commandLine(
                "cmake", "-G", "Ninja",
                "-S", srcDir.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_INSTALL_PREFIX=${installDir.absolutePath}",
                // The crawler is a PIE executable with every dep linked in.
                "-DBUILD_SHARED_LIBS=OFF",
                // Inference only: no tools, examples, server, unified app, common helpers, tests.
                "-DLLAMA_BUILD_TOOLS=OFF",
                "-DLLAMA_BUILD_TESTS=OFF",
                "-DLLAMA_BUILD_EXAMPLES=OFF",
                "-DLLAMA_BUILD_SERVER=OFF",
                "-DLLAMA_BUILD_APP=OFF",
                "-DLLAMA_BUILD_COMMON=OFF",
                // CPU backend only, and never tuned for the build host.
                "-DGGML_CPU=ON",
                "-DGGML_NATIVE=OFF",
                // At this pin GGML_CPU_ARM_ARCH becomes -march=<value>
                // (ggml/src/ggml-cpu/CMakeLists.txt:210). The dotprod/i8mm quant kernels are
                // compile-time gated on it, so a wrong value silently costs the dotprod 2x.
                "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm",
                // The NDK toolchain ships no OpenMP runtime.
                "-DGGML_OPENMP=OFF",
                "-DGGML_LLAMAFILE=OFF",
                "-DGGML_BLAS=OFF",
                "-DGGML_CUDA=OFF",
                "-DGGML_VULKAN=OFF",
                "-DGGML_OPENCL=OFF"
            )
        }
        // 2. Build + install the static archives and public headers
        project.exec {
            commandLine("cmake", "--build", buildDir.absolutePath, "--target", "install", "-j8")
        }
    }
}
tasks.register("prepareDhtCrawlerSrc") {
    description = "Copy dht_crawler sources to build/native/src/dht_crawler and apply the submodule patches"

    val crawlerSrc = providers.gradleProperty("dhtCrawlerSrc").orNull?.let { file(it).absoluteFile }
        ?: file("src/main/cpp/dht_crawler")
    val nativeDir = nativeRoot
    val destDir = file("$nativeDir/src/dht_crawler")

    inputs.dir(file("$crawlerSrc/src"))
    inputs.dir(file("$crawlerSrc/include"))
    inputs.files(fileTree(file("$crawlerSrc/lib")) { exclude(".git") })
    // tests/ carries the host suite AND its fixtures (including the 11 MB CRAM
    // v15 container under tests/fixtures/cram/models/v15 and the vendored
    // utf8proc). Without this the task stays UP-TO-DATE across a fixture change
    // and the prepared copy silently keeps the old model.
    inputs.dir(file("$crawlerSrc/tests"))
    inputs.dir(file("$crawlerSrc/patches"))
    outputs.dir(destDir)

    doLast {
        // 1. Fail early if the source tree is incomplete (submodules not initialized)
        if (!file("$crawlerSrc/src/main.c").exists()) {
            error(
                "dht_crawler source is missing at $crawlerSrc (no src/main.c). " +
                    "Run 'git submodule update --init --recursive' or pass -PdhtCrawlerSrc=<path>."
            )
        }
        // 2. Delete + re-copy sources (exclude .git dirs, build/data artifacts, object files, binary)
        destDir.deleteRecursively()
        copy {
            from(crawlerSrc)
            into(destDir)
            exclude(".git", "build", "data", "backup.data", "**/*.o", "dht_crawler")
            // civetweb test fixtures (not compiled into the crawler); one filename
            // also trips the daemon JVM's non-UTF-8 filename encoding on this host
            exclude("lib/civetweb/test")
        }
        // 3. Apply the remaining patches in the fixed order below (new patches go LAST).
        //
        // Only the three fixes that patch third-party *submodules* live here. Every
        // other crawler change that used to be applied at this point is committed
        // directly in the dht_crawler repository (one commit per former patch), so a
        // standalone clone of dht_crawler builds without this pipeline. The crawler's
        // own `make` applies these same three (guarded and idempotent); this list
        // exists because the Android build compiles the copied tree with CMake/NDK and
        // never runs that Makefile.
        for (patchName in listOf(
            "bencode-cap-fix.patch",
            "bencode-init-fix.patch",
            "civetweb-stop-flag-atomic.patch"
        )) {
            project.exec {
                commandLine("patch", "-p1", "-d", destDir.absolutePath, "-i", file("$destDir/patches/$patchName").absolutePath)
            }
        }
    }
}

tasks.register<Copy>("copyDhtCrawlerAssets") {
    description = "Copy dht_crawler keyword files into generated assets (Stage 09/5)"
    // Same source-resolution logic as prepareDhtCrawlerSrc
    val crawlerSrc = providers.gradleProperty("dhtCrawlerSrc").orNull?.let { file(it).absoluteFile }
        ?: file("src/main/cpp/dht_crawler")
    from(crawlerSrc) {
        include("torrent_search_keywords.txt", "hard_block.txt")
    }
    // The classifier weights are NOT assets any more: ~489 MB of CRAM (v15) + movie-kind
    // (v13) weights used to be copied here and staged into files/crawler/models at
    // startup. The crawler now downloads them itself on first run
    // (`dht_crawler --fetch-models`, sha256-verified against each generation's
    // manifest), so only the keyword files are packaged. Keep this task's include list
    // keyword-only — an accidental `v*/**` would put the weights back in the APK.
    into(file("build/generated/assets/dht_crawler/dht_crawler"))
    // A Copy task leaves files behind that its sources no longer produce: after a
    // keyword file was dropped from the crawler repo its stale copy stayed here and got
    // packaged into the APK — and every build before the APK diet left the ~489 MB of
    // classifier weights under models/ here. This destination dir is exclusively this
    // task's output (keyword files at the top level), so dropping it wholesale before
    // the copy re-populates it is safe and stays correct for future removals too.
    doFirst {
        delete(file("build/generated/assets/dht_crawler/dht_crawler"))
    }
    // Gradle does not treat a file *added* to a Copy task's destination as "out of date",
    // so without this the whole task (and the cleanup above) is skipped on a warm tree.
    // Always re-run: the copy is ~1 MB locally, and the native builds dominate anyway.
    // Still a `Copy` task — keyword-file semantics unchanged.
    outputs.upToDateWhen { false }
}

tasks.register("buildDhtCrawler") {
    description = "Cross-compile dht_crawler for arm64, package libdhtcrawler.so (Stage 09)"
    dependsOn("buildMbedtls", "buildLibuv", "fetchSqlite", "prepareDhtCrawlerSrc", "buildLlama")

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainFile = file("$ndkDir/build/cmake/android.toolchain.cmake")

    val nativeDir = nativeRoot
    val crawlerCmakeDir = file("src/main/cpp/dht_crawler-android")
    val crawlerSrcDir = file("$nativeDir/src/dht_crawler")
    val sqliteDir = file("$nativeDir/src/sqlite-amalgamation")
    val libuvInstall = file("$nativeDir/libuv-install")
    val mbedtlsInstall = file("$nativeDir/mbedtls-install")
    val llamaInstall = file("$nativeDir/llama-install")
    val utf8procDir = file("src/main/cpp/dht_crawler-android/utf8proc")
    val buildDir = file("$nativeDir/dht_crawler-build")
    val crawlerOut = file("$buildDir/dhtcrawler")
    val jniLibsArm64 = file("src/main/jniLibs/arm64-v8a")

    outputs.file("$jniLibsArm64/libdhtcrawler.so")
    inputs.dir(crawlerCmakeDir)
    inputs.dir(crawlerSrcDir)
    inputs.dir(sqliteDir)
    inputs.dir(libuvInstall)
    inputs.file("$mbedtlsInstall/lib/libmbedcrypto.a")
    inputs.dir(llamaInstall)

    doLast {
        // 1. Configure (Ninja + NDK toolchain, dynamic PIE, static deps)
        project.exec {
            commandLine(
                "cmake", "-G", "Ninja",
                "-S", crawlerCmakeDir.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DDHT_CRAWLER_SRC=${crawlerSrcDir.absolutePath}",
                "-DSQLITE_DIR=${sqliteDir.absolutePath}",
                "-DLIBUV_INSTALL=${libuvInstall.absolutePath}",
                "-DMBEDTLS_INSTALL=${mbedtlsInstall.absolutePath}",
                // Movie-kind (Stage A): llama.cpp install prefix + vendored utf8proc. The
                // wrapper defines MK_ENABLED only when LLAMA_INSTALL is non-empty.
                "-DLLAMA_INSTALL=${llamaInstall.absolutePath}",
                "-DUTF8PROC_DIR=${utf8procDir.absolutePath}"
            )
        }
        // 2. Build the crawler executable
        project.exec {
            commandLine("cmake", "--build", buildDir.absolutePath, "--target", "dhtcrawler", "-j8")
        }
        // 3. Strip + copy + rename to jniLibs (lib*.so name required for packaging)
        stripDaemon(crawlerOut, ndkDir)
        copy {
            from(crawlerOut)
            into(jniLibsArm64)
            rename { "libdhtcrawler.so" }
        }
    }
}

tasks.register("buildFtpServer") {
    description = "Cross-compile the native FTP daemon for arm64, package libftpserver.so (Stage 2)"

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainFile = file("$ndkDir/build/cmake/android.toolchain.cmake")

    val nativeDir = nativeRoot
    val srcDir = file("src/main/cpp/ftp-server")
    val buildDir = file("$nativeDir/build-ftpserver")
    val serverOut = file("$buildDir/ftpserver")
    val jniLibsArm64 = file("src/main/jniLibs/arm64-v8a")

    outputs.file("$jniLibsArm64/libftpserver.so")
    inputs.dir(srcDir)
    inputs.file(toolchainFile)

    doLast {
        // 1. Configure (Ninja + NDK toolchain)
        project.exec {
            commandLine(
                "cmake", "-G", "Ninja",
                "-S", srcDir.absolutePath, "-B", buildDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release"
            )
        }
        // 2. Build the daemon executable
        project.exec {
            commandLine("cmake", "--build", buildDir.absolutePath, "--target", "ftpserver", "-j8")
        }
        // 3. Strip + copy + rename to jniLibs (lib*.so name required for packaging)
        stripDaemon(serverOut, ndkDir)
        copy {
            from(serverOut)
            into(jniLibsArm64)
            rename { "libftpserver.so" }
        }
    }
}

// cloudflared 2026.11.0 is built from Go source, so -- like llama.cpp above -- the tarball is
// pinned by sha256: a mismatched download is deleted and re-fetched rather than used.
tasks.register("fetchCloudflaredSrc") {
    description = "Download the pinned cloudflared 2026.11.0 sources (Go) for the tunnel binary"

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/cloudflared-2026.11.0")
    val tarball = file("$nativeDir/src/cloudflared-2026.11.0.tar.gz")
    val stamp = file("$srcDir/.sha")
    val url = "https://github.com/cloudflare/cloudflared/archive/refs/tags/2026.11.0.tar.gz"
    // One-time bootstrap: sha256sum of the single download of the pinned tag, hardcoded from then on.
    val sha256 = "9913452304215e1e7b0ecd75b3e1ca400d5c8b64156e176c5e0aaaca4238c539"

    outputs.dir(srcDir)

    fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    doLast {
        // Hermetic builder (F-Droid): the tree was staged from a pinned checkout by a prebuild
        // step, so there is no tarball to hash and nothing to download.
        if (prebuiltNativeSrc) {
            if (!file("$srcDir/go.mod").exists()) {
                error("prebuiltNativeSrc is set but $srcDir has no go.mod — stage the pinned " +
                    "cloudflared checkout (2026.11.0) there")
            }
            stamp.writeText("prebuilt\n")
            return@doLast
        }
        // 1. Fetch the tarball, discarding a cached copy whose bytes are not the pin
        if (tarball.exists() && sha256Of(tarball) != sha256) {
            logger.warn("cloudflared tarball sha256 mismatch, re-downloading $url")
            tarball.delete()
        }
        if (!tarball.exists()) {
            tarball.parentFile.mkdirs()
            project.exec {
                commandLine("curl", "-L", "--fail", "-o", tarball.absolutePath, url)
            }
        }
        // 2. Verify before extracting -- this is the pin the Go build compiles against
        val actual = sha256Of(tarball)
        if (actual != sha256) {
            error("cloudflared tarball sha256 mismatch: expected $sha256, got $actual ($url)")
        }
        // 3. Extract (re-extract when the tree is missing or came from a different pin).
        // The archive's own top-level directory is already cloudflared-2026.11.0, i.e. srcDir.
        if (srcDir.exists() && (!stamp.exists() || stamp.readText().trim() != sha256)) {
            srcDir.deleteRecursively()
        }
        if (!file("$srcDir/go.mod").exists()) {
            srcDir.deleteRecursively()
            project.exec {
                commandLine("tar", "-xzf", tarball.absolutePath, "-C", file("$nativeDir/src").absolutePath)
            }
        }
        stamp.writeText("$sha256\n")
    }
}

tasks.register("buildCloudflared") {
    description = "Cross-compile cloudflared 2026.11.0 for arm64, package libcloudflared.so"
    dependsOn("fetchCloudflaredSrc")

    val nativeDir = nativeRoot
    val srcDir = file("$nativeDir/src/cloudflared-2026.11.0")
    val buildDir = file("$nativeDir/build-cloudflared")
    val binaryOut = file("$buildDir/cloudflared")
    val jniLibsArm64 = file("src/main/jniLibs/arm64-v8a")

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainBin = file("$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/bin")
    // minSdk is 26; the per-API wrapper form keeps cgo linked against bionic at the right level
    val cc = file("$toolchainBin/aarch64-linux-android26-clang")
    val cxx = file("$toolchainBin/aarch64-linux-android26-clang++")

    outputs.file("$jniLibsArm64/libcloudflared.so")
    inputs.dir(srcDir)

    doLast {
        // 0. Host Go guard. GOTOOLCHAIN makes Go download 1.27.4 itself (go.mod requires it, the
        //    host ships 1.27.3), so the first build needs network for the toolchain and modules.
        var goOk = true
        try {
            project.exec { commandLine("go", "version") }
        } catch (e: Exception) {
            goOk = false
        }
        if (!goOk) error(
            "Go toolchain not found — install Go, or set -PgoToolchain to the version " +
                "cloudflared's go.mod requires (default $goToolchain)"
        )
        buildDir.mkdirs()
        // 1. Cross-compile: android/arm64, cgo against the NDK's bionic (stock Go's pure resolver
        //    is broken on Android), PIE because Android refuses ET_EXEC children.
        project.exec {
            workingDir = srcDir
            environment("GOOS", "android")
            environment("GOARCH", "arm64")
            environment("CGO_ENABLED", "1")
            environment("CC", cc.absolutePath)
            environment("CXX", cxx.absolutePath)
            environment("GOTOOLCHAIN", goToolchain)
            if (goVendor) {
                // Hermetic: every module comes from the staged vendor/ tree, nothing is fetched.
                if (!file("$srcDir/vendor").exists()) {
                    error("goVendor is set but $srcDir/vendor does not exist — run " +
                        "`go mod vendor` in that tree first")
                }
                environment("GOFLAGS", "-mod=vendor")
                environment("GOPROXY", "off")
            }
            commandLine(
                "go", "build",
                "-trimpath",
                "-buildmode=pie",
                "-ldflags", "-s -w -X main.Version=2026.11.0",
                "-o", binaryOut.absolutePath,
                "./cmd/cloudflared"
            )
        }
        // 2. Strip + copy + rename to jniLibs (lib*.so name required for packaging)
        stripDaemon(binaryOut, ndkDir)
        copy {
            from(binaryOut)
            into(jniLibsArm64)
            rename { "libcloudflared.so" }
        }
    }
}

// Ensure buildTransmission runs before jniLibs are merged into the APK
afterEvaluate {
    // Ensure native libs are ready before they are merged into the APK. Both variants are wired:
    // only mergeDebugJniLibFolders used to be, so a release build packaged whatever stale .so
    // files happened to be sitting in jniLibs/ from an earlier debug build (or none at all).
    listOf("mergeDebugJniLibFolders", "mergeReleaseJniLibFolders").forEach { taskName ->
        tasks.named(taskName).configure {
            dependsOn("buildTransmission")
            dependsOn("buildDhtCrawler")
            dependsOn("buildFtpServer")
            dependsOn("buildCloudflared")
        }
    }
    // Generated crawler assets (keyword files) are variant-independent, so both are wired.
    tasks.named("mergeDebugAssets").configure {
        dependsOn("copyDhtCrawlerAssets")
    }
    tasks.named("mergeReleaseAssets").configure {
        dependsOn("copyDhtCrawlerAssets")
    }
    // Release-only lint reads the generated asset dir without declaring that dependency. Gradle 8
    // treats an undeclared producer/consumer pair as a hard error, and debug builds never run
    // lintVital, so this only ever bites on the release path.
    listOf("lintVitalAnalyzeRelease", "generateReleaseLintVitalReportModel").forEach { taskName ->
        tasks.matching { it.name == taskName }.configureEach {
            dependsOn("copyDhtCrawlerAssets")
        }
    }
}
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Ktor server (CIO engine — pure Kotlin, no native deps)
    implementation("io.ktor:ktor-server-cio:3.5.0")
    implementation("io.ktor:ktor-server-core:3.5.0")
    implementation("io.ktor:ktor-server-websockets:3.5.0")
    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.3")
    // SAF DocumentFile helper (used by SafStorageGateway)
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
