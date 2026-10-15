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
project(dhtcrawler C CXX)

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

# ---------------------------------------------------------------------------
# Stage 5.1: vendored classifier libraries (tparse + ftc over fastText 0.9.2).
#
# Linked into the crawler executable but not called yet: a later stage's
# src/classifier.c uses them behind -DCLF_ENABLED. Sources are copied verbatim
# from the classifier-model repo (native/tparse, native/ftc and
# native/third_party/fastText at v0.9.2, MIT -- see fasttext/LICENSE).
# ---------------------------------------------------------------------------

# tparse: libc-only C11 release-name / file-list parser. Compiles as C so the
# crawler's own C sources can call it directly.
add_library(tparse STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/tparse/tparse.c
    ${CMAKE_CURRENT_SOURCE_DIR}/tparse/name.c
)
target_include_directories(tparse PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/tparse)
set_target_properties(tparse PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    # -std=c11, as the reference builds it (not the toolchain's gnu11).
    C_EXTENSIONS OFF
    # Linked into the PIE crawler executable.
    POSITION_INDEPENDENT_CODE ON
)

# ftc: tiny C ABI over the fastText inference path (C++11). The training/CLI
# fastText sources (main.cc, autotune.cc) are deliberately absent: this
# library predicts, it never trains. meter.cc is still compiled because
# fasttext.cc keeps FastText::test(), which references Meter.
set(FTC_FASTTEXT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/fasttext)
set(FTC_FASTTEXT_SOURCES
    ${FTC_FASTTEXT_DIR}/args.cc
    ${FTC_FASTTEXT_DIR}/densematrix.cc
    ${FTC_FASTTEXT_DIR}/dictionary.cc
    ${FTC_FASTTEXT_DIR}/fasttext.cc
    ${FTC_FASTTEXT_DIR}/loss.cc
    ${FTC_FASTTEXT_DIR}/matrix.cc
    ${FTC_FASTTEXT_DIR}/meter.cc
    ${FTC_FASTTEXT_DIR}/model.cc
    ${FTC_FASTTEXT_DIR}/productquantizer.cc
    ${FTC_FASTTEXT_DIR}/quantmatrix.cc
    ${FTC_FASTTEXT_DIR}/utils.cc
    ${FTC_FASTTEXT_DIR}/vector.cc
)

find_package(Threads REQUIRED)

add_library(ftc STATIC
    ${FTC_FASTTEXT_SOURCES}
    ${CMAKE_CURRENT_SOURCE_DIR}/ftc/ftc.cc
)
target_include_directories(ftc
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/ftc
    # The vendored fastText sources include each other by bare name.
    PRIVATE ${FTC_FASTTEXT_DIR}
    # taxonomy.h: the generated label-name tables that turn `__label__tv`
    # into an int.
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/tparse
)
set_target_properties(ftc PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    # -std=c++11, as the reference builds it (not the toolchain's gnu++11).
    CXX_EXTENSIONS OFF
)
# fastText's inference path is const, but fasttext.cc still references
# std::thread, so the library carries the threads dependency to its users.
target_link_libraries(ftc PUBLIC Threads::Threads)

# fastText 0.9.2 predates today's libc++ header hygiene: args.cc uses uint64_t
# without including <cstdint>. Force the include from the build description
# rather than patching the pinned third-party tree.
target_compile_options(ftc PRIVATE -include cstdint)
# ftc.cc is ours and stays warning-clean; the pinned fastText sources are not
# (sign-compare et al.), so -Wall -Wextra goes on the wrapper file only.
set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/ftc/ftc.cc
    PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra")

# Stage 5.1 gating define for the crawler sources: a later stage's
# src/classifier.c compiles inert without it, and the upstream host Makefile
# build (which does not use this file) stays plain C. It is a directory-wide
# definition, so the vendored tparse/ftc sources see it as well -- neither
# tests it.
add_compile_definitions(CLF_ENABLED)
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
    # The executable carries C++11 objects (ftc/fastText): link it with the C++
    # driver so the c++_static runtime is resolved and initialised.
    LINKER_LANGUAGE CXX
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
    tparse
    ftc
    # The crawler is C and ftc is C++11: c++_static (the NDK's default STL)
    # keeps the PIE binary self-contained, exactly as the reference NDK build
    # (classifier-model scripts/build_ftc_ndk.sh) does.
    c++_static
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
