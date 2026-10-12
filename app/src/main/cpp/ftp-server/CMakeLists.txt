# NDK build root for the native FTP daemon (stage 2).
#
# Driven by the `buildFtpServer` task in app/build.gradle.kts:
#
#   cmake -G Ninja -S <this dir> -B <build dir> \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
#     -DCMAKE_BUILD_TYPE=Release
#   cmake --build <build dir> --target ftpserver -j8
#
# Produces an executable that is renamed libftpserver.so for APK packaging
# (the lib*.so name is required; the binary is exec'd by ProcessSupervisor in
# stage 3, not dlopen'ed). The SAF backend reaches the app's SafIpcServer
# over the Unix socket passed as --saf-socket at runtime; the posix backend
# stays host-only (see host/CMakeLists.txt).

cmake_minimum_required(VERSION 3.16)
project(ftpserver C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

add_executable(ftpserver
  ftpd.c
  session.c
  util.c
  storage_saf.c
)
target_compile_options(ftpserver PRIVATE -O2 -Wall -Wextra)
target_compile_definitions(ftpserver PRIVATE FTPD_STORAGE_SAF)
# No -lpthread: Bionic folds pthread into libc (unlike the host glibc build).
