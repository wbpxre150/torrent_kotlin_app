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
        versionCode = 1
        versionName = "0.1.0"
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

tasks.register<Exec>("buildProbe") {
    description = "Compile probe.c → libprobe.so (arm64, static)"

    // Find NDK: prefer ANDROID_NDK_HOME env, fall back to SDK ndk dir
    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val toolchain = "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
    val cc = file("$ndkDir/$toolchain")
    val jniLibsDir = file("src/main/jniLibs/arm64-v8a")

    inputs.file("src/main/cpp/probe.c")
    outputs.file("$jniLibsDir/libprobe.so")

    commandLine(
        cc.absolutePath,
        "-static",
        "-o", "$jniLibsDir/libprobe.so",
        "src/main/cpp/probe.c"
    )

    // ARM64 Bionic requires PT_TLS p_align >= 64. NDK static builds via emutls produce
    // a stub TLS segment with alignment 8. Patch the ELF program header directly.
    doLast {
        fun leU16(b: ByteArray, off: Int) =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
        fun leU32(b: ByteArray, off: Int) =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
        fun leU64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (s in 0..7) v = v or ((b[off + s].toLong() and 0xff) shl (s * 8))
            return v
        }
        fun putU64(b: ByteArray, off: Int, v: Long) {
            for (s in 0..7) b[off + s] = ((v ushr (s * 8)) and 0xff).toByte()
        }

        val binary = file("$jniLibsDir/libprobe.so")
        val bytes = binary.readBytes()
        check(bytes[0] == 0x7f.toByte() && bytes[1] == 0x45.toByte() &&
              bytes[2] == 0x4c.toByte() && bytes[3] == 0x46.toByte()) { "Not an ELF file" }
        val phoff    = leU64(bytes, 32).toInt()   // e_phoff
        val phentsize = leU16(bytes, 54)           // e_phentsize
        val phnum    = leU16(bytes, 56)            // e_phnum
        var patched  = false
        for (i in 0 until phnum) {
            val hdrOff = phoff + i * phentsize
            if (leU32(bytes, hdrOff) == 7) {       // PT_TLS
                val palign = leU64(bytes, hdrOff + 48)
                if (palign < 64L) {
                    logger.lifecycle("Patching PT_TLS p_align $palign -> 64 in ${binary.name}")
                    putU64(bytes, hdrOff + 48, 64L)
                    binary.writeBytes(bytes)
                    patched = true
                }
                break
            }
        }
        if (!patched) logger.lifecycle("PT_TLS p_align already >= 64, no patch needed")
    }
}
tasks.register<Exec>("buildSafTest") {
    description = "Compile saf_test.c -> libsaftest.so (arm64, shared JNI lib)"

    val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.provider {
            val sdk = android.sdkDirectory
            val ndks = file("$sdk/ndk").listFiles()?.sortedByDescending { it.name }
            ndks?.firstOrNull()?.absolutePath ?: error("NDK not found")
        })
    val ndkDir = file(ndk.get())
    val cc = file("$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang")
    val jniLibsDir = file("src/main/jniLibs/arm64-v8a")

    inputs.file("src/main/cpp/saf_test.c")
    outputs.file("$jniLibsDir/libsaftest.so")

    commandLine(
        cc.absolutePath,
        "-shared",
        "-fPIC",
        "-o", "$jniLibsDir/libsaftest.so",
        "src/main/cpp/saf_test.c"
    )

    // NOTE: no PT_TLS p_align patch — that patch is for static executables only.
    // libsaftest.so is a shared library; Bionic provides TLS via the dynamic linker.
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

// Ensure buildTransmission runs before jniLibs are merged into the APK
afterEvaluate {
    tasks.named("mergeDebugJniLibFolders").configure {
        dependsOn("buildTransmission")
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
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Ktor server (CIO engine — pure Kotlin, no native deps)
    implementation("io.ktor:ktor-server-cio:3.5.0")
    implementation("io.ktor:ktor-server-core:3.5.0")
    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.3")
    // SAF DocumentFile helper (used by SafStorageGateway)
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
