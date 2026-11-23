# Stage 09 — app-side CMake build for dht_crawler (arm64-v8a, NDK).
#
# Invoked by the Gradle `buildDhtCrawler` task with:
#   -D DHT_CRAWLER_SRC=<patched copy at build/native/src/dht_crawler>
#   -D SQLITE_DIR=<sqlite amalgamation dir with sqlite3.c/sqlite3.h>
#   -D LIBUV_INSTALL=<libuv install prefix: lib/libuv.a, include/>
#   -D MBEDTLS_INSTALL=<mbedTLS install prefix: lib/libmbedcrypto.a, include/>
#   -D LLAMA_INSTALL=<llama.cpp install prefix: lib/libllama.a + ggml libs>  (optional)
#   -D UTF8PROC_DIR=<vendored utf8proc sources>  (defaults to ./utf8proc)
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
# llama.cpp (pinned 18ab62a8, CPU-only) install prefix produced by Gradle's buildLlama.
# Optional: when unset the movie-kind support is not compiled in (no MK_ENABLED).
set(LLAMA_INSTALL "" CACHE PATH "llama.cpp install prefix (static libllama.a + ggml libs); unset = no movie-kind")
set(UTF8PROC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/utf8proc" CACHE PATH "Vendored utf8proc sources (utf8proc.c, utf8proc.h)")

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

# utf8proc 2.12.0 (MIT -- see utf8proc/LICENSE.md): Unicode NFC normalisation for
# the movie-kind serving text. utf8proc.c #includes the generated utf8proc_data.c,
# so that file is a header-like part of the single translation unit, not a source.
add_library(utf8proc STATIC ${UTF8PROC_DIR}/utf8proc.c)
target_include_directories(utf8proc PUBLIC ${UTF8PROC_DIR})
set_target_properties(utf8proc PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    # -std=c11, as upstream builds it (not the toolchain's gnu11).
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
# build (which does not use this file) stays plain C. Directory scope means it
# reaches the targets created after this call and nothing else: tparse/ftc are
# added above, only the dhtcrawler target below is defined after it, so the
# vendored sources never see CLF_ENABLED.
add_compile_definitions(CLF_ENABLED)
# src/http_fetch.c (the BEP51 published-cache download) speaks TLS through mbedTLS
# here, because this build has no OpenSSL at all: the crawler's sha256 comes from
# compat/openssl/sha.h over libmbedcrypto. The host Makefile build uses OpenSSL.
add_compile_definitions(HTTP_FETCH_MBEDTLS)
# Movie-kind gating: MK_ENABLED exists only when Gradle's buildLlama handed us a
# llama.cpp install prefix. Defined after tparse/ftc/utf8proc and before
# add_executable, so it reaches the dhtcrawler target only -- a plain `cmake`
# configure (or the vendored repo's host Makefile build) stays llama-free.
if(LLAMA_INSTALL)
    add_compile_definitions(MK_ENABLED)
    # The serving-text renderer (src/movie_kind_render.c) is deliberately ungated
    # so the vendored host build tests the real thing; only its NFC step needs
    # utf8proc, and the shipped binary always gets it. Defined here beside
    # MK_ENABLED, i.e. after the utf8proc target above, so it reaches the
    # dhtcrawler target only.
    add_compile_definitions(MK_NFC_ENABLED)
endif()
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

# SQLite: FTS5 is required for the torrent_search/torrent_docs virtual
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
    ${UTF8PROC_DIR}
)

# Static third-party deps, then Bionic system libs. pthread lives in
# Bionic libc; -llog for __android_log_write (civetweb).
target_link_libraries(dhtcrawler PRIVATE
    # Unicode NFC for the movie-kind serving text (used by
    # src/movie_kind_render.c under MK_NFC_ENABLED; unused archives cost nothing
    # in a static link).
    utf8proc
    tparse
    ftc
    # The crawler is C and ftc is C++11: c++_static (the NDK's default STL)
    # keeps the PIE binary self-contained, exactly as the reference NDK build
    # (classifier-model scripts/build_ftc_ndk.sh) does.
    c++_static
    ${LIBUV_INSTALL}/lib/libuv.a
    ${MBEDTLS_INSTALL}/lib/libmbedcrypto.a
    # TLS + X.509 for src/http_fetch.c. Only the crawler target links these; the
    # tparse/ftc/utf8proc targets above never see the defines.
    ${MBEDTLS_INSTALL}/lib/libmbedtls.a
    ${MBEDTLS_INSTALL}/lib/libmbedx509.a
    m
    dl
    log
)

# Movie-kind (Stage B+): llama.cpp static libs. Archives resolve left to right, so
# llama comes before ggml, ggml-cpu and ggml-base; all four are static at this pin
# (BUILD_SHARED_LIBS=OFF), which is why the aggregate ggml archive is listed too.
if(LLAMA_INSTALL)
    target_include_directories(dhtcrawler PRIVATE ${LLAMA_INSTALL}/include)
    target_link_libraries(dhtcrawler PRIVATE
        ${LLAMA_INSTALL}/lib/libllama.a
        ${LLAMA_INSTALL}/lib/libggml.a
        ${LLAMA_INSTALL}/lib/libggml-cpu.a
        ${LLAMA_INSTALL}/lib/libggml-base.a
        Threads::Threads
    )
endif()

# Strip release binaries (smaller .so in the APK)
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(dhtcrawler PRIVATE -s)
endif()
