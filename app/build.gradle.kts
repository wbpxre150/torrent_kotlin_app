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
}
