# Stage 09 — app-side CMake build for dht_crawler (arm64-v8a, NDK).
#
# Invoked by the Gradle `buildDhtCrawler` task with:
#   -D DHT_CRAWLER_SRC=<patched copy at build/native/src/dht_crawler>
#   -D SQLITE_DIR=<sqlite amalgamation dir with sqlite3.c/sqlite3.h>
#   -D LIBUV_INSTALL=<libuv install prefix: lib/libuv.a, include/>
#   -D MBEDTLS_INSTALL=<mbedTLS install prefix: lib/libmbedcrypto.a, include/>
#
# Builds a dynamic PIE executable `dhtcrawler` against Bionic with all
# third-party deps static (same shape as libtransmissiond.so). Gradle
# copies/renames the binary to src/main/jniLibs/arm64-v8a/libdhtcrawler.so.
cmake_minimum_required(VERSION 3.16)
project(dhtcrawler C)

set(DHT_CRAWLER_SRC "" CACHE PATH "Patched dht_crawler source tree")
set(SQLITE_DIR "" CACHE PATH "SQLite amalgamation directory (sqlite3.c, sqlite3.h)")
set(LIBUV_INSTALL "" CACHE PATH "libuv install prefix (static libuv.a)")
set(MBEDTLS_INSTALL "" CACHE PATH "mbedTLS install prefix (static libmbedcrypto.a)")

if(NOT DHT_CRAWLER_SRC OR NOT SQLITE_DIR OR NOT LIBUV_INSTALL OR NOT MBEDTLS_INSTALL)
    message(FATAL_ERROR
        "DHT_CRAWLER_SRC, SQLITE_DIR, LIBUV_INSTALL and MBEDTLS_INSTALL must be set "
        "(the Gradle buildDhtCrawler task passes them)")
endif()

file(GLOB CRAWLER_SOURCES ${DHT_CRAWLER_SRC}/src/*.c)

add_executable(dhtcrawler
    ${CRAWLER_SOURCES}
    ${DHT_CRAWLER_SRC}/lib/bencode-c/bencode.c
    ${DHT_CRAWLER_SRC}/lib/cJSON/cJSON.c
    ${DHT_CRAWLER_SRC}/lib/civetweb/src/civetweb.c
    ${DHT_CRAWLER_SRC}/lib/libbloom/bloom.c
    ${DHT_CRAWLER_SRC}/lib/libbloom/murmur2/MurmurHash2.c
    ${SQLITE_DIR}/sqlite3.c
)

set_target_properties(dhtcrawler PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_STANDARD 99
)

# Match the upstream Makefile: -Wall -Wextra -O2 -std=c99
# -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE (-D__BSD_VISIBLE is a
# BSD/glibc-ism with no effect on Bionic; omitted).
target_compile_options(dhtcrawler PRIVATE -O2 -Wall -Wextra)
target_compile_definitions(dhtcrawler PRIVATE
    _POSIX_C_SOURCE=200809L
    _DEFAULT_SOURCE
)

# civetweb without TLS (upstream Makefile compiles it with -DNO_SSL)
set_source_files_properties(
    ${DHT_CRAWLER_SRC}/lib/civetweb/src/civetweb.c
    PROPERTIES COMPILE_DEFINITIONS NO_SSL
)

# libbloom: upstream compiles bloom.c via libbloom/Makefile, which injects
# the version macros below; replicate them here since we build it directly.
set_source_files_properties(
    ${DHT_CRAWLER_SRC}/lib/libbloom/bloom.c
    PROPERTIES
        COMPILE_DEFINITIONS
            "BLOOM_VERSION=\"2.0\";BLOOM_VERSION_MAJOR=2;BLOOM_VERSION_MINOR=0"
)

# SQLite: FTS5 is required for the torrent_search/file_search virtual
# tables; THREADSAFE=1 matches the desktop build. sqlite3.c is noisy,
# silence its warnings.
set_source_files_properties(
    ${SQLITE_DIR}/sqlite3.c
    PROPERTIES
        COMPILE_DEFINITIONS "SQLITE_ENABLE_FTS5;SQLITE_THREADSAFE=1"
        COMPILE_OPTIONS "-w"
)

# Compat include dir FIRST so <openssl/sha.h> resolves to the mbedTLS shim.
target_include_directories(dhtcrawler PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/compat
    ${DHT_CRAWLER_SRC}/include
    ${DHT_CRAWLER_SRC}/lib/bencode-c
    ${DHT_CRAWLER_SRC}/lib/cJSON
    ${DHT_CRAWLER_SRC}/lib/civetweb/include
    ${DHT_CRAWLER_SRC}/lib/libbloom
    # bloom.c does #include "murmurhash2.h"; upstream builds libbloom via
    # its own Makefile, which adds -Ilib/libbloom/murmur2.
    ${DHT_CRAWLER_SRC}/lib/libbloom/murmur2
    ${DHT_CRAWLER_SRC}/lib/uthash/src
    ${SQLITE_DIR}
    ${LIBUV_INSTALL}/include
    ${MBEDTLS_INSTALL}/include
)

# Static third-party deps, then Bionic system libs. pthread lives in
# Bionic libc; -llog for __android_log_write (civetweb).
target_link_libraries(dhtcrawler PRIVATE
    ${LIBUV_INSTALL}/lib/libuv.a
    ${MBEDTLS_INSTALL}/lib/libmbedcrypto.a
    m
    dl
    log
)

# Strip release binaries (smaller .so in the APK)
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(dhtcrawler PRIVATE -s)
endif()
