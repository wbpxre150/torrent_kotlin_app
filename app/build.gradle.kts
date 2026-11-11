// Needed by fetchLlamaCpp's tarball pin check (`java` alone is the Gradle Java extension here).
import java.security.MessageDigest

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
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    val nativeDir = file("build/native")
    val srcDir = file("$nativeDir/src/mbedtls-3.6.6")
    val buildDir = file("$nativeDir/build-mbedtls")
    val installDir = file("$nativeDir/mbedtls-install")
    val tarball = file("$nativeDir/src/mbedtls-3.6.6.tar.bz2")
    val url = "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.6/mbedtls-3.6.6.tar.bz2"

    outputs.dir(installDir)

    doLast {
        // 1. Fetch tarball if missing
        if (!tarball.exists()) {
            tarball.parentFile.mkdirs()
            project.exec {
                commandLine("curl", "-L", "--fail", "-o", tarball.absolutePath, url)
            }
        }
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

    val nativeDir = file("build/native")
    val srcDir = file("$nativeDir/src/curl-8.20.0")
    val mbedtlsInstall = file("$nativeDir/mbedtls-install")
    val installDir = file("$nativeDir/curl-install")
    val tarball = file("$nativeDir/src/curl-8.20.0.tar.gz")
    val curlUrl = "https://curl.se/download/curl-8.20.0.tar.gz"

    outputs.dir(installDir)

    doLast {
        // 1. Fetch tarball if missing
        if (!tarball.exists()) {
            tarball.parentFile.mkdirs()
            project.exec {
                commandLine("curl", "-L", "--fail", "-o", tarball.absolutePath, curlUrl)
            }
        }
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
    description = "Cross-compile transmission-daemon 4.1.2 for arm64, package libtransmissiond.so"
    dependsOn("buildCurl")

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchainFile = file("$ndkDir/build/cmake/android.toolchain.cmake")

    val nativeDir = file("build/native")
    val srcDir = file("$nativeDir/src/transmission-4.1.2")
    val curlInstall = file("$nativeDir/curl-install")
    val mbedtlsInstall = file("$nativeDir/mbedtls-install")
    val buildDir = file("$nativeDir/build-transmission")
    val daemonOut = file("$buildDir/daemon/transmission-daemon")
    val jniLibsArm64 = file("src/main/jniLibs/arm64-v8a")
    val tarball = file("$nativeDir/src/transmission-4.1.2.tar.xz")
    // Official release tarball (includes vendored third-party submodules)
    val url = "https://github.com/transmission/transmission/releases/download/4.1.2/transmission-4.1.2.tar.xz"

    outputs.file("$jniLibsArm64/libtransmissiond.so")
    inputs.file("src/main/cpp/transmission-fork/file-saf.h")
    inputs.file("src/main/cpp/transmission-fork/file-saf.cc")
    inputs.file("src/main/cpp/transmission-fork/disk-timing.h")
    inputs.file("src/main/cpp/transmission-fork/disk-queue.h")
    inputs.file("src/main/cpp/transmission-fork/disk-io.h")
    inputs.file("src/main/cpp/transmission-fork/disk-io.cc")
    inputs.file("src/main/cpp/transmission-fork/apply_fork.py")

    doLast {
        // 1. Fetch tarball if missing
        if (!tarball.exists()) {
            tarball.parentFile.mkdirs()
            project.exec {
                commandLine("curl", "-L", "--fail", "-o", tarball.absolutePath, url)
            }
        }
        // 2. Extract if missing
        if (!srcDir.exists()) {
            project.exec {
                commandLine("tar", "-xJf", tarball.absolutePath, "-C", srcDir.parentFile.absolutePath)
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
        // 5. Copy + rename to jniLibs (lib*.so name required for packaging)
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

    val nativeDir = file("build/native")
    val srcDir = file("$nativeDir/src/libuv-v1.48.0")
    val buildDir = file("$nativeDir/build-libuv")
    val installDir = file("$nativeDir/libuv-install")
    val tarball = file("$nativeDir/src/libuv-v1.48.0.tar.gz")
    val url = "https://dist.libuv.org/dist/v1.48.0/libuv-v1.48.0.tar.gz"

    outputs.dir(installDir)

    doLast {
        // 1. Fetch tarball if missing
        if (!tarball.exists()) {
            tarball.parentFile.mkdirs()
            project.exec {
                commandLine("curl", "-L", "--fail", "-o", tarball.absolutePath, url)
            }
        }
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

    val nativeDir = file("build/native")
    val srcDir = file("$nativeDir/src/sqlite-amalgamation")
    val zipFile = file("$nativeDir/src/sqlite-amalgamation-3490100.zip")
    val url = "https://www.sqlite.org/2025/sqlite-amalgamation-3490100.zip"

    outputs.dir(srcDir)

    doLast {
        // 1. Fetch zip if missing
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

    val nativeDir = file("build/native")
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

    val nativeDir = file("build/native")
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
    description = "Copy dht_crawler sources to build/native/src/dht_crawler and apply the patches"

    val crawlerSrc = providers.gradleProperty("dhtCrawlerSrc").orNull?.let { file(it).absoluteFile }
        ?: file("src/main/cpp/dht_crawler")
    val nativeDir = file("build/native")
    val destDir = file("$nativeDir/src/dht_crawler")

    inputs.dir(file("$crawlerSrc/src"))
    inputs.dir(file("$crawlerSrc/include"))
    inputs.files(fileTree(file("$crawlerSrc/lib")) { exclude(".git") })
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
        // 3. Apply the upstream patches in the fixed order below (new patches go LAST)
        for (patchName in listOf(
            "bencode-cap-fix.patch",
            "bencode-init-fix.patch",
            "civetweb-stop-flag-atomic.patch",
            "bep51-periodic-cache-save.patch",
            "restore-database.patch",
            "clear-failure-filter.patch",
            "remove-porn-filter.patch",
            "save-categories.patch",
            "search-rows-filtered-name.patch",
            "rebuild-fts-database.patch",
            "search-movie-kind-facet.patch",
            "random-facet-pool.patch",
            "movie-kind-conjunctive.patch",
            "maintenance-progress.patch",
            "movie-kind-v10-streaming.patch"
        )) {
            project.exec {
                commandLine("patch", "-p1", "-d", destDir.absolutePath, "-i", file("$destDir/patches/$patchName").absolutePath)
            }
        }
    }
}

tasks.register<Copy>("copyDhtCrawlerAssets") {
    description = "Copy dht_crawler keyword files + classifier models into generated assets (Stage 09/5)"
    // Same source-resolution logic as prepareDhtCrawlerSrc
    val crawlerSrc = providers.gradleProperty("dhtCrawlerSrc").orNull?.let { file(it).absoluteFile }
        ?: file("src/main/cpp/dht_crawler")
    from(crawlerSrc) {
        include("torrent_search_keywords.txt", "hard_block.txt")
    }
    // Stage 5: fastText classifier model files; Stage D adds the movie-kind (JEVH)
    // generation. Binary — the Copy task is byte-exact (no text filtering / charset
    // conversion), so a plain copy is correct here. The include list is explicit on
    // purpose: `v*/**` would ship the retired v4 generation too.
    // `into("models")` is relative to the task destination above, giving the asset
    // paths dht_crawler/models/v6/<file> and dht_crawler/models/v10/<file> (overriding)
    // the crawler's compiled-in default dirs).
    val modelSrc = file("src/main/cpp/dht_crawler-android/models")
    from(modelSrc) {
        include("v6/**", "v10/**")
        into("models")
    }
    into(file("build/generated/assets/dht_crawler/dht_crawler"))
    // A Copy task leaves files behind that its sources no longer produce: after a
    // v3 -> v4 models bump a warm build tree still held the previous generation's dir, and after a
    // keyword file was dropped from the crawler repo its stale copy stayed here — both
    // got packaged into the APK. This destination dir is exclusively this task's output
    // (keyword files at the top level, models/ below), so dropping it wholesale before
    // the copy re-populates it is safe and stays correct for future removals too.
    doFirst {
        delete(file("build/generated/assets/dht_crawler/dht_crawler"))
    }
    // Gradle does not treat a file *added* to a Copy task's destination as "out of date",
    // so without this the whole task (and the cleanup above) is skipped on a warm tree and
    // a stale models generation is packaged. Always re-run: the copy is ~26 MB locally, and the
    // native builds dominate anyway. Still a `Copy` task — keyword-file semantics unchanged.
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

    val nativeDir = file("build/native")
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
        // 3. Copy + rename to jniLibs (lib*.so name required for packaging)
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

    val nativeDir = file("build/native")
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
        // 3. Copy + rename to jniLibs (lib*.so name required for packaging)
        copy {
            from(serverOut)
            into(jniLibsArm64)
            rename { "libftpserver.so" }
        }
    }
}

// Ensure buildTransmission runs before jniLibs are merged into the APK
afterEvaluate {
    // Ensure native libs and generated crawler assets are ready before packaging
    tasks.named("mergeDebugJniLibFolders").configure {
        dependsOn("buildTransmission")
        dependsOn("buildDhtCrawler")
        dependsOn("buildFtpServer")
    }
    tasks.named("mergeDebugAssets").configure {
        dependsOn("copyDhtCrawlerAssets")
    }
    tasks.named("mergeReleaseAssets").configure {
        dependsOn("copyDhtCrawlerAssets")
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
