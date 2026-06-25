#!/usr/bin/env python3
"""Apply the SAF storage fork to an extracted transmission source tree.

Usage: apply_fork.py <transmission_src_root>
Idempotent: safe to run repeatedly.
"""
import os
import shutil
import sys

def main():
    src_root = sys.argv[1]
    libt = os.path.join(src_root, "libtransmission")
    fork_dir = os.path.dirname(os.path.abspath(__file__))

    # 1. Copy fork sources.
    for name in ("file-saf.h", "file-saf.cc"):
        shutil.copy2(os.path.join(fork_dir, name), os.path.join(libt, name))

    # 2. Patch file-posix.cc: include + dispatch at top of 6 functions.
    fp = os.path.join(libt, "file-posix.cc")
    with open(fp, "r", encoding="utf-8") as f:
        text = f.read()
    if "file-saf.h" not in text:
        text = text.replace(
            '#include "libtransmission/tr-strbuf.h"\n',
            '#include "libtransmission/tr-strbuf.h"\n#include "libtransmission/file-saf.h"\n',
            1,
        )
        patches = [
            ("tr_sys_file_t tr_sys_file_open(char const* path, int flags, int permissions, tr_error* error)\n{\n",
             "    if (tr_saf_is_path(path)) return tr_saf_file_open(path, flags, permissions, error);\n"),
            ("bool tr_sys_path_exists(char const* path, tr_error* error)\n{\n",
             "    if (tr_saf_is_path(path)) return tr_saf_path_exists(path, error);\n"),
            ("std::optional<tr_sys_path_info> tr_sys_path_get_info(std::string_view path, int flags, tr_error* error)\n{\n",
             "    if (tr_saf_is_path(path)) return tr_saf_path_get_info(path, flags, error);\n"),
            ("bool tr_sys_path_rename(char const* src_path, char const* dst_path, tr_error* error)\n{\n",
             "    if (tr_saf_is_path(src_path)) return tr_saf_path_rename(src_path, dst_path, error);\n"),
            ("bool tr_sys_path_remove(char const* path, tr_error* error)\n{\n",
             "    if (tr_saf_is_path(path)) return tr_saf_path_remove(path, error);\n"),
            ("bool tr_sys_dir_create(char const* path, int flags, int permissions, tr_error* error)\n{\n",
             "    if (tr_saf_is_path(path)) return tr_saf_dir_create(path, flags, permissions, error);\n"),
        ]
        for sig, dispatch in patches:
            if sig not in text:
                sys.exit("ERROR: signature not found in file-posix.cc: " + sig)
            text = text.replace(sig, sig + dispatch, 1)
        with open(fp, "w", encoding="utf-8") as f:
            f.write(text)

    # 2b. Patch file-posix.cc: dispatch tr_sys_path_is_same to SAF.
    #     Separate guard because the include was already added by block 2
    #     on previously-patched trees.
    with open(fp, "r", encoding="utf-8") as f:
        text = f.read()
    if "tr_saf_path_is_same" not in text:
        sig = "bool tr_sys_path_is_same(char const* path1, char const* path2, tr_error* error)\n{\n"
        dispatch = "    if (tr_saf_is_path(path1) || tr_saf_is_path(path2)) return tr_saf_path_is_same(path1, path2, error);\n"
        if sig not in text:
            sys.exit("ERROR: signature not found in file-posix.cc: " + sig)
        text = text.replace(sig, sig + dispatch, 1)
        with open(fp, "w", encoding="utf-8") as f:
            f.write(text)

    # 3. Patch file-capacity.cc: include + dispatch in tr_sys_path_get_capacity.
    fc = os.path.join(libt, "file-capacity.cc")
    with open(fc, "r", encoding="utf-8") as f:
        text = f.read()
    if "file-saf.h" not in text:
        text = text.replace(
            '#include "libtransmission/file.h"\n',
            '#include "libtransmission/file.h"\n#include "libtransmission/file-saf.h"\n',
            1,
        )
        sig = "std::optional<tr_sys_path_capacity> tr_sys_path_get_capacity(std::string_view path, tr_error* error)\n{\n"
        dispatch = "    if (tr_saf_is_path(path)) return tr_saf_path_get_capacity(path, error);\n"
        if sig not in text:
            sys.exit("ERROR: signature not found in file-capacity.cc")
        text = text.replace(sig, sig + dispatch, 1)
        with open(fc, "w", encoding="utf-8") as f:
            f.write(text)

    # 3b. Patch torrent-files.cc: include + SAF short-circuit in remove().
    tf = os.path.join(libt, "torrent-files.cc")
    with open(tf, "r", encoding="utf-8") as f:
        text = f.read()
    if "file-saf.h" not in text:
        text = text.replace(
            '#include "libtransmission/file.h"\n',
            '#include "libtransmission/file.h"\n#include "libtransmission/file-saf.h"\n',
            1,
        )
        # Short-circuit: for SAF paths, skip the tmpdir-move-junk-filter dance
        # (mkdtemp/opendir don't work on the /saf/usb sentinel) and do a single
        # recursive tr_sys_path_remove on the torrent's own folder.
        # DocumentsContract.deleteDocument is recursive for directory documents.
        old = (
            "void tr_torrent_files::remove(std::string_view parent_in, std::string_view tmpdir_prefix, FileFunc const& func, tr_error* error)\n"
            "    const\n"
            "{\n"
            "    auto const parent = tr_pathbuf{ parent_in };\n"
        )
        new = (
            "void tr_torrent_files::remove(std::string_view parent_in, std::string_view tmpdir_prefix, FileFunc const& func, tr_error* error)\n"
            "    const\n"
            "{\n"
            "    auto const parent = tr_pathbuf{ parent_in };\n"
            "    // SAF short-circuit: the native tmpdir-move-and-junk-filter dance uses\n"
            "    // mkdtemp/opendir/readdir which don't work on the /saf/usb sentinel.\n"
            "    // DocumentsContract.deleteDocument is recursive, so one remove on the\n"
            "    // torrent's own folder deletes the entire subtree.\n"
            "    auto const saf_target = tr_pathbuf{ parent_in, '/', tmpdir_prefix };\n"
            "    if (tr_saf_is_path(saf_target))\n"
            "    {\n"
            "        tr_sys_path_remove(saf_target, error);\n"
            "        return;\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: signature not found in torrent-files.cc for remove() short-circuit")
        text = text.replace(old, new, 1)
        with open(tf, "w", encoding="utf-8") as f:
            f.write(text)

    # 4. CMakeLists.txt: add file-saf.cc to the main add_library source list.
    cm = os.path.join(libt, "CMakeLists.txt")
    with open(cm, "r", encoding="utf-8") as f:
        text = f.read()
    if "file-saf.cc" not in text:
        main_target = "        file-posix.cc\n        file-win32.cc"
        main_repl = "        file-posix.cc\n        file-saf.cc\n        file-win32.cc"
        if main_target not in text:
            sys.exit("ERROR: CMakeLists main source list not found")
        text = text.replace(main_target, main_repl, 1)
        cond_target = "        file-posix.cc\n        subprocess-posix.cc)"
        cond_repl = "        file-posix.cc\n        file-saf.cc\n        subprocess-posix.cc)"
        if cond_target not in text:
            sys.exit("ERROR: CMakeLists NOT WIN32 block not found")
        text = text.replace(cond_target, cond_repl, 1)
        with open(cm, "w", encoding="utf-8") as f:
            f.write(text)

    # 5. Patch log.cc: under __ANDROID__, logAddImpl only writes to logcat and
    #    never enqueues, so the daemon's --logfile stays empty. Also enqueue
    #    when queue_enabled_ so the log file is populated.
    log_cc = os.path.join(libt, "log.cc")
    with open(log_cc, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: also enqueue" not in text:
        anchor = '    __android_log_write(prio, "transmission", szmsg.c_str());\n\n#else'
        replacement = (
            '    __android_log_write(prio, "transmission", szmsg.c_str());\n\n'
            '    // ANDROID fork: also enqueue so the daemon\'s --logfile (drained via\n'
            '    // pumpLogMessages -> tr_logGetQueue) is populated.\n'
            '    if (log_state.queue_enabled_)\n'
            '    {\n'
            '        auto* const newmsg = new tr_log_message{};\n'
            '        newmsg->level = level;\n'
            '        newmsg->when = std::chrono::system_clock::now();\n'
            '        newmsg->message = std::move(msg);\n'
            '        newmsg->file = file;\n'
            '        newmsg->line = line;\n'
            '        newmsg->name = name;\n\n'
            '        *log_state.queue_tail_ = newmsg;\n'
            '        log_state.queue_tail_ = &newmsg->next;\n'
            '        ++log_state.queue_length_;\n\n'
            '        if (log_state.queue_length_ > TrLogMaxQueueLength)\n'
            '        {\n'
            '            tr_log_message* old = log_state.queue_;\n'
            '            log_state.queue_ = old->next;\n'
            '            old->next = nullptr;\n'
            '            tr_logFreeQueue(old);\n'
            '            --log_state.queue_length_;\n'
            '        }\n'
            '    }\n\n'
            '#else'
        )
        if anchor not in text:
            sys.exit("ERROR: log.cc Android anchor not found")
        text = text.replace(anchor, replacement, 1)
        with open(log_cc, "w", encoding="utf-8") as f:
            f.write(text)

    # 6. Patch top-level CMakeLists.txt: pre-seed EVENT__HAVE_PTHREADS so the
    #    libevent sub-build can skip find_package(Threads) (which fails on
    #    Android cross-compilation when inheriting parent's
    #    CMAKE_USE_PTHREADS_INIT=1).
    top_cmake = os.path.join(src_root, "CMakeLists.txt")
    with open(top_cmake, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: fix libevent thread detection" not in text:
        old = "-DEVENT__LIBRARY_TYPE:STRING=STATIC)"
        new = (
            "-DEVENT__LIBRARY_TYPE:STRING=STATIC\n"
            "        -DEVENT__HAVE_PTHREADS:BOOL=ON\n"
            "        # ANDROID fork: fix libevent thread detection\n"
            ")"
        )
        text = text.replace(old, new, 1)
        with open(top_cmake, "w", encoding="utf-8") as f:
            f.write(text)

    # 6b. Patch libevent/CMakeLists.txt: on Android, skip find_package(Threads)
    #    and use EVENT__HAVE_PTHREADS pre-seed. The parent build forces
    #    CMAKE_USE_PTHREADS_INIT=1 which breaks the sub-build's thread
    #    detection. Bionic has pthreads in libc (no -lpthread needed).
    le_cmake = os.path.join(src_root, "third-party", "libevent", "CMakeLists.txt")
    with open(le_cmake, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: skip find_package(Threads) for libevent" not in text:
        old = (
            "if (NOT EVENT__DISABLE_THREAD_SUPPORT)\n"
            "    if (WIN32)\n"
            "        list(APPEND SRC_CORE evthread_win32.c)\n"
            "    else()\n"
            "        find_package(Threads REQUIRED)\n"
            "        if (NOT CMAKE_USE_PTHREADS_INIT)\n"
            "            message(FATAL_ERROR\n"
            "                    \"Failed to find Pthreads, set EVENT__DISABLE_THREAD_SUPPORT to disable\")\n"
            "        endif()\n"
            "\n"
            "        set(EVENT__HAVE_PTHREADS 1)\n"
            "        list(APPEND LIB_APPS ${CMAKE_THREAD_LIBS_INIT})\n"
            "    endif()\n"
            "endif()"
        )
        new = (
            "if (NOT EVENT__DISABLE_THREAD_SUPPORT)\n"
            "    if (WIN32)\n"
            "        list(APPEND SRC_CORE evthread_win32.c)\n"
            "    else()\n"
            "        # ANDROID fork: skip find_package(Threads) for libevent\n"
            "        # Bionic has pthreads in libc; CMAKE_USE_PTHREADS_INIT=1\n"
            "        # from parent cache breaks the sub-build detection.\n"
            "        if(NOT DEFINED EVENT__HAVE_PTHREADS)\n"
            "            find_package(Threads REQUIRED)\n"
            "            if (NOT CMAKE_USE_PTHREADS_INIT)\n"
            "                message(FATAL_ERROR\n"
            "                        \"Failed to find Pthreads, set EVENT__DISABLE_THREAD_SUPPORT to disable\")\n"
            "            endif()\n"
            "        endif()\n"
            "        set(EVENT__HAVE_PTHREADS 1)\n"
            "        list(APPEND LIB_APPS ${CMAKE_THREAD_LIBS_INIT})\n"
            "    endif()\n"
            "endif()"
        )
        text = text.replace(old, new, 1)
        with open(le_cmake, "w", encoding="utf-8") as f:
            f.write(text)

    # 7. Patch file-capacity.cc: guard sys/quota.h on Android.
    fc2 = os.path.join(libt, "file-capacity.cc")
    with open(fc2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: skip quota" not in text:
        old = "#else\n#include <sys/quota.h> /* quotactl() */"
        new = "#elif !defined(__ANDROID__)\n#include <sys/quota.h> /* quotactl() */"
        text = text.replace(old, new, 1)
        old2 = "#include <sys/types.h> /* types needed by quota.h */"
        new2 = "#include <sys/types.h> /* types needed by quota.h */\n// ANDROID fork: skip quota on Android"
        text = text.replace(old2, new2, 1)
        # Also guard the getquota() #else body which uses quota.h types
        old3 = "#else\n\n[[nodiscard]] tr_sys_path_capacity getquota(char const* device)\n{\n#if defined(__DragonFly__)"
        new3 = "#else\n#if !defined(__ANDROID__)\n\n[[nodiscard]] tr_sys_path_capacity getquota(char const* device)\n{\n#if defined(__DragonFly__)"
        text = text.replace(old3, new3, 1)
        # Close the #if !defined(__ANDROID__) after getquota() closes
        old4 = "#endif\n}\n\n#endif\n\n#ifdef HAVE_XQM"
        new4 = "#endif\n}\n\n#endif\n#endif\n\n#ifdef HAVE_XQM"
        text = text.replace(old4, new4, 1)
        # Guard the getquota() call site
        old5 = "    else\n    {\n        ret = getquota(info.device.c_str());\n    }"
        new5 = "    else\n    {\n#if defined(__ANDROID__)\n        // No quota support on Android\n#else\n        ret = getquota(info.device.c_str());\n#endif\n    }"
        text = text.replace(old5, new5, 1)
        with open(fc2, "w", encoding="utf-8") as f:
            f.write(text)

    # 8. Patch platform.cc: tr_getSessionIdDir() hardcodes "/tmp" on non-Windows,
    #    which is not writable on Android. Under __ANDROID__, honor $TMPDIR
    #    (already exported by DaemonService to filesDir/tmp) with /tmp fallback.
    plat = os.path.join(libt, "platform.cc")
    with open(plat, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: honor TMPDIR for session-id dir" not in text:
        old = (
            'std::string tr_getSessionIdDir()\n'
            '{\n'
            '#ifndef _WIN32\n\n'
            '    return std::string{ "/tmp"sv };'
        )
        new = (
            'std::string tr_getSessionIdDir()\n'
            '{\n'
            '#ifndef _WIN32\n\n'
            '#ifdef __ANDROID__\n'
            '    // ANDROID fork: honor TMPDIR for session-id dir (/tmp not writable).\n'
            '    if (auto dir = tr_env_get_string("TMPDIR"sv); !std::empty(dir))\n'
            '    {\n'
            '        return dir;\n'
            '    }\n'
            '#endif\n\n'
            '    return std::string{ "/tmp"sv };'
        )
        if old not in text:
            sys.exit("apply_fork: could not find tr_getSessionIdDir anchor in platform.cc")
        text = text.replace(old, new)
        with open(plat, "w", encoding="utf-8") as f:
            f.write(text)
    # 9. Patch inout.cc, torrent.cc, torrent.h, peer-msgs.cc:
    #    ANDROID fork: distinguish read error from hash mismatch.
    #    recalculate_hash / tr_ioTestPiece / check_piece / ensure_piece_is_checked
    #    return a tri-state so a transient I/O failure is not misreported as
    #    data corruption. Only a genuine hash mismatch sets the torrent error.
    inout = os.path.join(libt, "inout.cc")
    with open(inout, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: distinguish read error from hash mismatch" not in text:
        # 9a. include <utility> for std::pair
        text = text.replace(
            "#include <optional>\n#include <string_view>\n",
            "#include <optional>\n#include <string_view>\n#include <utility>\n",
            1,
        )
        # 9b. recalculate_hash signature + read-failure return
        old_sig = (
            "std::optional<tr_sha1_digest_t> recalculate_hash(tr_torrent const& tor, tr_piece_index_t const piece)\n"
            "{\n"
            "    TR_ASSERT(piece < tor.piece_count());\n\n"
            "    auto sha = tr_sha1{};\n"
            "    auto buffer = std::array<uint8_t, tr_block_info::BlockSize>{};\n\n"
            "    auto& cache = tor.session->cache;\n"
            "    auto const [begin_byte, end_byte] = tor.block_info().byte_span_for_piece(piece);\n"
            "    auto const [begin_block, end_block] = tor.block_span_for_piece(piece);\n"
            "    [[maybe_unused]] auto n_bytes_checked = size_t{};\n"
            "    for (auto block = begin_block; block < end_block; ++block)\n"
            "    {\n"
            "        auto const block_loc = tor.block_loc(block);\n"
            "        auto const block_len = tor.block_size(block);\n"
            "        if (auto const success = cache->read_block(tor, block_loc, block_len, std::data(buffer)) == 0; !success)\n"
            "        {\n"
            "            return {};\n"
            "        }\n"
        )
        new_sig = (
            "// ANDROID fork: distinguish read error from hash mismatch.\n"
            "// Returns {read_ok=false, {}} on read failure; {read_ok=true, hash} on success.\n"
            "std::pair<bool, std::optional<tr_sha1_digest_t>> recalculate_hash(tr_torrent const& tor, tr_piece_index_t const piece)\n"
            "{\n"
            "    TR_ASSERT(piece < tor.piece_count());\n\n"
            "    auto sha = tr_sha1{};\n"
            "    auto buffer = std::array<uint8_t, tr_block_info::BlockSize>{};\n\n"
            "    auto& cache = tor.session->cache;\n"
            "    auto const [begin_byte, end_byte] = tor.block_info().byte_span_for_piece(piece);\n"
            "    auto const [begin_block, end_block] = tor.block_span_for_piece(piece);\n"
            "    [[maybe_unused]] auto n_bytes_checked = size_t{};\n"
            "    for (auto block = begin_block; block < end_block; ++block)\n"
            "    {\n"
            "        auto const block_loc = tor.block_loc(block);\n"
            "        auto const block_len = tor.block_size(block);\n"
            "        if (auto const success = cache->read_block(tor, block_loc, block_len, std::data(buffer)) == 0; !success)\n"
            "        {\n"
            "            return { false, {} };\n"
            "        }\n"
        )
        if old_sig not in text:
            sys.exit("ERROR: inout.cc recalculate_hash signature not found")
        text = text.replace(old_sig, new_sig, 1)
        # 9c. recalculate_hash return at end of function
        old_end = (
            "    TR_ASSERT(tor.piece_size(piece) == n_bytes_checked);\n"
            "    return sha.finish();\n"
            "}\n"
        )
        new_end = (
            "    TR_ASSERT(tor.piece_size(piece) == n_bytes_checked);\n"
            "    return { true, sha.finish() };\n"
            "}\n"
        )
        if old_end not in text:
            sys.exit("ERROR: inout.cc recalculate_hash end not found")
        text = text.replace(old_end, new_end, 1)
        # 9d. tr_ioTestPiece
        old_tpi = (
            "bool tr_ioTestPiece(tr_torrent const& tor, tr_piece_index_t const piece)\n"
            "{\n"
            "    auto const hash = recalculate_hash(tor, piece);\n"
            "    return hash && *hash == tor.piece_hash(piece);\n"
            "}\n"
        )
        new_tpi = (
            "// ANDROID fork: distinguish read error from hash mismatch.\n"
            "// Returns 1 = piece good, 0 = hash mismatch (corrupt), -1 = read error (transient).\n"
            "int tr_ioTestPiece(tr_torrent const& tor, tr_piece_index_t const piece)\n"
            "{\n"
            "    auto const [read_ok, hash] = recalculate_hash(tor, piece);\n"
            "    if (!read_ok)\n"
            "    {\n"
            "        return -1;\n"
            "    }\n"
            "    return hash && *hash == tor.piece_hash(piece) ? 1 : 0;\n"
            "}\n"
        )
        if old_tpi not in text:
            sys.exit("ERROR: inout.cc tr_ioTestPiece not found")
        text = text.replace(old_tpi, new_tpi, 1)
        with open(inout, "w", encoding="utf-8") as f:
            f.write(text)

    # 9e. inout.h declaration
    inout_h = os.path.join(libt, "inout.h")
    with open(inout_h, "r", encoding="utf-8") as f:
        text = f.read()
    if "int tr_ioTestPiece" not in text:
        old_decl = "[[nodiscard]] bool tr_ioTestPiece(tr_torrent const& tor, tr_piece_index_t piece);\n"
        new_decl = "[[nodiscard]] int tr_ioTestPiece(tr_torrent const& tor, tr_piece_index_t piece);\n"
        if old_decl not in text:
            sys.exit("ERROR: inout.h tr_ioTestPiece declaration not found")
        text = text.replace(old_decl, new_decl, 1)
        with open(inout_h, "w", encoding="utf-8") as f:
            f.write(text)

    # 9f. torrent.h: check_piece + ensure_piece_is_checked declarations
    torrent_h = os.path.join(libt, "torrent.h")
    with open(torrent_h, "r", encoding="utf-8") as f:
        text = f.read()
    h_changed = False
    if "int tr_torrent::check_piece" not in text and "int check_piece" not in text:
        # check_piece
        old = "    [[nodiscard]] bool check_piece(tr_piece_index_t piece) const;\n"
        new = "    // ANDROID fork: tri-state: 1=good, 0=corrupt, -1=read error.\n    [[nodiscard]] int check_piece(tr_piece_index_t piece) const;\n"
        if old not in text:
            sys.exit("ERROR: torrent.h check_piece declaration not found")
        text = text.replace(old, new, 1)
        h_changed = True
    if "int ensure_piece_is_checked" not in text:
        old = "    [[nodiscard]] bool ensure_piece_is_checked(tr_piece_index_t piece);\n"
        new = "    // ANDROID fork: tri-state: 1=good, 0=corrupt, -1=read error (transient).\n    [[nodiscard]] int ensure_piece_is_checked(tr_piece_index_t piece);\n"
        if old not in text:
            sys.exit("ERROR: torrent.h ensure_piece_is_checked declaration not found")
        text = text.replace(old, new, 1)
        h_changed = True
    if h_changed:
        with open(torrent_h, "w", encoding="utf-8") as f:
            f.write(text)

    # 9g. torrent.cc: check_piece body
    torrent_cc = os.path.join(libt, "torrent.cc")
    with open(torrent_cc, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: tri-state return" not in text:
        old = (
            "bool tr_torrent::check_piece(tr_piece_index_t const piece) const\n"
            "{\n"
            "    auto const pass = tr_ioTestPiece(*this, piece);\n"
            '    tr_logAddTraceTor(this, fmt::format("[LAZY] tr_torrent.checkPiece tested piece {}, pass=={}", piece, pass));\n'
            "    return pass;\n"
            "}\n"
        )
        new = (
            "// ANDROID fork: tri-state return: 1=good, 0=corrupt, -1=read error.\n"
            "int tr_torrent::check_piece(tr_piece_index_t const piece) const\n"
            "{\n"
            "    auto const pass = tr_ioTestPiece(*this, piece);\n"
            '    tr_logAddTraceTor(this, fmt::format("[LAZY] tr_torrent.checkPiece tested piece {}, pass=={}", piece, pass));\n'
            "    return pass;\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc check_piece body not found")
        text = text.replace(old, new, 1)

        # 9h. ensure_piece_is_checked body
        old = (
            "[[nodiscard]] bool tr_torrent::ensure_piece_is_checked(tr_piece_index_t piece)\n"
            "{\n"
            "    TR_ASSERT(piece < this->piece_count());\n\n"
            "    if (is_piece_checked(piece))\n"
            "    {\n"
            "        return true;\n"
            "    }\n\n"
            "    bool const checked = check_piece(piece);\n"
            "    mark_changed();\n"
            "    set_dirty();\n\n"
            "    checked_pieces_.set(piece, checked);\n"
            "    return checked;\n"
            "}\n"
        )
        new = (
            "[[nodiscard]] int tr_torrent::ensure_piece_is_checked(tr_piece_index_t piece)\n"
            "{\n"
            "    TR_ASSERT(piece < this->piece_count());\n\n"
            "    if (is_piece_checked(piece))\n"
            "    {\n"
            "        return 1;\n"
            "    }\n\n"
            "    // ANDROID fork: tri-state. -1 = read error (transient); do not mark the\n"
            "    // piece corrupt, leave it unchecked so it is retried later.\n"
            "    auto const result = check_piece(piece);\n"
            "    mark_changed();\n"
            "    set_dirty();\n\n"
            "    if (result == -1)\n"
            "    {\n"
            "        return -1;\n"
            "    }\n\n"
            "    checked_pieces_.set(piece, result == 1);\n"
            "    return result;\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc ensure_piece_is_checked body not found")
        text = text.replace(old, new, 1)

        # 9i. caller at line ~853 (new-seed check)
        old_call = (
            "    // check the first piece\n"
            "    return ensure_piece_is_checked(0);\n"
            "}\n"
        )
        new_call = (
            '    // check the first piece. Treat read error (-1) as "not a new seed".\n'
            "    auto const r = ensure_piece_is_checked(0);\n"
            "    return r == 1;\n"
            "}\n"
        )
        if old_call not in text:
            sys.exit("ERROR: torrent.cc ensure_piece_is_checked(0) call site not found")
        text = text.replace(old_call, new_call, 1)
        with open(torrent_cc, "w", encoding="utf-8") as f:
            f.write(text)

    # 9j. peer-msgs.cc: add_next_block
    peer_msgs = os.path.join(libt, "peer-msgs.cc")
    with open(peer_msgs, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: tri-state. -1 = read error (transient): reject this" not in text:
        old = (
            "    auto buf = std::array<uint8_t, tr_block_info::BlockSize>{};\n"
            "    auto ok = is_valid_request(req) && tor_.has_piece(req.index);\n\n"
            "    if (ok)\n"
            "    {\n"
            "        ok = tor_.ensure_piece_is_checked(req.index);\n\n"
            "        if (!ok)\n"
            "        {\n"
            '            tor_.error().set_local_error(fmt::format("Please Verify Local Data! Piece #{:d} is corrupt.", req.index));\n'
            "        }\n"
            "    }\n\n"
            "    if (ok)\n"
            "    {\n"
            "        ok = session->cache->read_block(tor_, tor_.piece_loc(req.index, req.offset), req.length, std::data(buf)) == 0;\n"
            "    }\n"
        )
        new = (
            "    auto buf = std::array<uint8_t, tr_block_info::BlockSize>{};\n"
            "    auto ok = is_valid_request(req) && tor_.has_piece(req.index);\n\n"
            "    if (ok)\n"
            "    {\n"
            "        // ANDROID fork: tri-state. -1 = read error (transient): reject this\n"
            "        // request without flagging corruption. 0 = genuine corruption.\n"
            "        auto const checked = tor_.ensure_piece_is_checked(req.index);\n"
            "        ok = (checked == 1);\n\n"
            "        if (checked == 0)\n"
            "        {\n"
            '            tor_.error().set_local_error(fmt::format("Please Verify Local Data! Piece #{:d} is corrupt.", req.index));\n'
            "        }\n"
            "    }\n\n"
            "    if (ok)\n"
            "    {\n"
            "        ok = session->cache->read_block(tor_, tor_.piece_loc(req.index, req.offset), req.length, std::data(buf)) == 0;\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc add_next_block body not found")
        text = text.replace(old, new, 1)
        with open(peer_msgs, "w", encoding="utf-8") as f:
            f.write(text)
    # 10. Patch file-posix.cc: set tr_error on 0-byte read in
    #     tr_sys_file_read_at. ANDROID fork: a stale SAF fd can return 0
    #     (EOF) on pread without an errno; surface this as EIO so the caller
    #     treats it as a read error instead of hashing an uninitialized buffer.
    fp2 = os.path.join(libt, "file-posix.cc")
    with open(fp2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: a 0-byte read when bytes were requested" not in text:
        old = (
            "    if (my_bytes_read > 0)\n"
            "    {\n"
            "        if (bytes_read != nullptr)\n"
            "        {\n"
            "            *bytes_read = my_bytes_read;\n"
            "        }\n\n"
            "        ret = true;\n"
            "    }\n"
            "    else if (error != nullptr && my_bytes_read == -1)\n"
            "    {\n"
            "        error->set_from_errno(errno);\n"
            "    }\n\n"
            "    return ret;\n"
            "}\n"
        )
        new = (
            "    if (my_bytes_read > 0)\n"
            "    {\n"
            "        if (bytes_read != nullptr)\n"
            "        {\n"
            "            *bytes_read = my_bytes_read;\n"
            "        }\n\n"
            "        ret = true;\n"
            "    }\n"
            "    else if (error != nullptr && my_bytes_read == -1)\n"
            "    {\n"
            "        error->set_from_errno(errno);\n"
            "    }\n"
            "    else if (error != nullptr && my_bytes_read == 0 && size > 0)\n"
            "    {\n"
            "        // ANDROID fork: a 0-byte read when bytes were requested is an\n"
            "        // unexpected EOF (e.g. stale SAF fd, truncated file, FUSE reset).\n"
            "        // Set an explicit error so callers distinguish read failure from a\n"
            "        // short read, instead of hashing an uninitialized buffer.\n"
            "        error->set_from_errno(EIO);\n"
            "    }\n\n"
            "    return ret;\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: file-posix.cc tr_sys_file_read_at body not found")
        text = text.replace(old, new, 1)
        with open(fp2, "w", encoding="utf-8") as f:
            f.write(text)

    # 11. Patch torrent.cc: set checked_pieces_ in on_piece_completed.
    #     ANDROID fork: a piece that just passed check_piece during download
    #     is verified against current on-disk state; mark it checked so it is
    #     not re-verified through the cache path on the first seeding request.
    #     Addresses Mechanism C of BUG-SEEDING-CORRUPT-FALSE-POSITIVE.
    torrent_cc2 = os.path.join(libt, "torrent.cc")
    with open(torrent_cc2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: the piece just passed check_piece in on_block_received" not in text:
        old = (
            "    // if this piece completes any file, invoke the fileCompleted func for it\n"
            "    for (auto [file, file_end] = fpm_.file_span_for_piece(piece); file < file_end; ++file)\n"
            "    {\n"
            "        if (has_file(file))\n"
            "        {\n"
            "            on_file_completed(file);\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        new = (
            "    // if this piece completes any file, invoke the fileCompleted func for it\n"
            "    for (auto [file, file_end] = fpm_.file_span_for_piece(piece); file < file_end; ++file)\n"
            "    {\n"
            "        if (has_file(file))\n"
            "        {\n"
            "            on_file_completed(file);\n"
            "        }\n"
            "    }\n\n"
            "    // ANDROID fork: the piece just passed check_piece in on_block_received,\n"
            "    // so mark it checked. Without this, every piece is re-verified through\n"
            "    // the cache-based path on the first seeding request, where a transient\n"
            "    // I/O error or cache inconsistency can produce a false-positive corruption\n"
            "    // verdict. See BUG-SEEDING-CORRUPT-FALSE-POSITIVE Mechanism C.\n"
            "    checked_pieces_.set(piece, true);\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc on_piece_completed body not found")
        text = text.replace(old, new, 1)
        with open(torrent_cc2, "w", encoding="utf-8") as f:
            f.write(text)

    print("apply_fork: OK")

if __name__ == "__main__":
    main()
