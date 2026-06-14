# Build

## JDK

The system default JDK is 26. Android Gradle Plugin 8.8.2 does not support JDK 26.

**Always** use JDK 21 for any Gradle build:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
./gradlew installDebug
```

## Quick reference

| Env | Value |
|---|---|
| `JAVA_HOME` | `/usr/lib/jvm/java-21-openjdk` |
| `ANDROID_HOME` | `$HOME/Android/Sdk` |
| Build command | `./gradlew installDebug` (with both env vars set) |
