-keep class com.google.zxing.qrcode.** { *; }
-keep class com.google.zxing.common.** { *; }
-keep class com.google.zxing.BarcodeFormat { *; }

# Ktor ships a debugger detector (io.ktor.util.debug.IntellijIdeaDebugDetector) that probes the
# JDK's java.lang.management, which does not exist on Android. The code path is unreachable
# on-device, but R8 fails the release build on the missing classes, so silence them.
-dontwarn java.lang.management.**
