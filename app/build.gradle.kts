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
    // Apache FtpServer (Stage 06): embedded wifi-only FTP serving USB to Kodi.
    // ftpserver-core pulls ftplet-api + mina-core transitively; do not pin them separately.
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    // slf4j binding for FtpServer/MINA logging on Android (java.util.logging).
    implementation("org.slf4j:slf4j-jdk14:1.7.36")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
