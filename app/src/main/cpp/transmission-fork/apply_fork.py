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
    for name in ("file-saf.h", "file-saf.cc", "disk-timing.h", "disk-queue.h", "disk-io.h", "disk-io.cc"):
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

    # 4b. CMakeLists.txt: add disk-io.cc to the main add_library source list and
    #     the NOT WIN32 list. Separate guard because block 4's guard
    #     ("file-saf.cc" not in text) is already false on previously-patched trees.
    cm2 = os.path.join(libt, "CMakeLists.txt")
    with open(cm2, "r", encoding="utf-8") as f:
        text = f.read()
    if "disk-io.cc" not in text:
        main_target = "        file-saf.cc\n        file-win32.cc"
        main_repl = "        file-saf.cc\n        disk-io.cc\n        file-win32.cc"
        if main_target not in text:
            sys.exit("ERROR: CMakeLists main source list (file-saf.cc) not found")
        text = text.replace(main_target, main_repl, 1)
        cond_target = "        file-saf.cc\n        subprocess-posix.cc)"
        cond_repl = "        file-saf.cc\n        disk-io.cc\n        subprocess-posix.cc)"
        if cond_target not in text:
            sys.exit("ERROR: CMakeLists NOT WIN32 block (file-saf.cc) not found")
        text = text.replace(cond_target, cond_repl, 1)
        with open(cm2, "w", encoding="utf-8") as f:
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
    #     Note: step 26 (stage 3 async reads) replaces this whole body and removes
    #     the tri-state marker, so also skip when the stage 3 marker is present.
    peer_msgs = os.path.join(libt, "peer-msgs.cc")
    with open(peer_msgs, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: tri-state. -1 = read error (transient): reject this" not in text and \
       "ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads" not in text:
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
    # 12. Patch torrent.cc: drop mtime==0 short-circuit in load_checked_pieces.
    #     ANDROID fork: blanket "mtime == 0" clear forces re-verification of all
    #     SAF pieces on restart (SAF mtime can be 0 when unstatable). With the
    #     Kotlin stat path now populating mtime, only clear pieces on actual
    #     mtime mismatch. If both saved and current mtime are 0, pieces are kept.
    #     Addresses Mechanism D of BUG-SEEDING-CORRUPT-FALSE-POSITIVE.
    torrent_cc3 = os.path.join(libt, "torrent.cc")
    with open(torrent_cc3, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: do NOT clear pieces just because mtime == 0" not in text:
        old = (
            "        // if a file has changed, mark its pieces as unchecked\n"
            "        if (mtime == 0 || mtime != mtimes[file])\n"
            "        {\n"
            "            auto const [piece_begin, piece_end] = tor_.piece_span_for_file(file);\n"
            "            tor_.checked_pieces_.unset_span(piece_begin, piece_end);\n"
            "        }\n"
        )
        new = (
            "        // if a file has changed, mark its pieces as unchecked\n"
            "        // ANDROID fork: do NOT clear pieces just because mtime == 0 —\n"
            "        // SAF files may have mtime == 0 when the Kotlin side cannot stat\n"
            "        // them, and this caused re-verification through the cache path,\n"
            "        // triggering false-positive corruption verdicts. See\n"
            "        // BUG-SEEDING-CORRUPT-FALSE-POSITIVE Mechanism D.\n"
            "        if (mtime != mtimes[file])\n"
            "        {\n"
            "            auto const [piece_begin, piece_end] = tor_.piece_span_for_file(file);\n"
            "            tor_.checked_pieces_.unset_span(piece_begin, piece_end);\n"
            "        }\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc load_checked_pieces mtime check not found")
        text = text.replace(old, new, 1)
        with open(torrent_cc3, "w", encoding="utf-8") as f:
            f.write(text)

    # 13. Patch session-thread.cc + verify.cc: name the threads with prctl so they
    #     can be sampled (TRANSMISSION_USB_SAF stage 0.2).
    st = os.path.join(libt, "session-thread.cc")
    with open(st, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: name the session thread" not in text:
        old_inc = "#include <thread>\n"
        new_inc = "#include <sys/prctl.h>\n#include <thread>\n"
        if old_inc not in text:
            sys.exit("ERROR: session-thread.cc <thread> include not found")
        text = text.replace(old_inc, new_inc, 1)
        old_func = "    void session_thread_func(struct event_base* evbase)\n    {\n"
        new_func = (
            "    void session_thread_func(struct event_base* evbase)\n"
            "    {\n"
            "        // ANDROID fork: name the session thread for sampling (TRANSMISSION_USB_SAF).\n"
            "        prctl(PR_SET_NAME, \"tr-session\", 0, 0, 0);\n"
        )
        if old_func not in text:
            sys.exit("ERROR: session-thread.cc session_thread_func body not found")
        text = text.replace(old_func, new_func, 1)
        with open(st, "w", encoding="utf-8") as f:
            f.write(text)

    ver = os.path.join(libt, "verify.cc")
    with open(ver, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: name the verify thread" not in text:
        old_inc = "#include <thread>\n"
        new_inc = "#include <sys/prctl.h>\n#include <thread>\n"
        if old_inc not in text:
            sys.exit("ERROR: verify.cc <thread> include not found")
        text = text.replace(old_inc, new_inc, 1)
        old_func = "void tr_verify_worker::verify_thread_func()\n{\n    for (;;)\n"
        new_func = (
            "void tr_verify_worker::verify_thread_func()\n"
            "{\n"
            "    // ANDROID fork: name the verify thread for sampling (TRANSMISSION_USB_SAF).\n"
            "    prctl(PR_SET_NAME, \"tr-verify\", 0, 0, 0);\n"
            "    for (;;)\n"
        )
        if old_func not in text:
            sys.exit("ERROR: verify.cc verify_thread_func body not found")
        text = text.replace(old_func, new_func, 1)
        with open(ver, "w", encoding="utf-8") as f:
            f.write(text)

    # 14. Patch inout.cc + session.cc: ANDROID fork: stage 0 timing.
    #     Wrap the session-thread disk I/O calls in tr_disk_timer
    #     (TRANSMISSION_USB_SAF stage 0.3) so slow operations are logged
    #     with thread/op/path/offset/len/ms as [DISKSLOW] lines.
    io2 = os.path.join(libt, "inout.cc")
    with open(io2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 0 timing" not in text:
        old_inc = '#include "libtransmission/utils.h"\n'
        new_inc = '#include "libtransmission/utils.h"\n#include "libtransmission/disk-timing.h"\n'
        if old_inc not in text:
            sys.exit("ERROR: inout.cc utils.h include not found")
        text = text.replace(old_inc, new_inc, 1)
        # 14a. read_or_write_bytes: get_fd -> "open"
        old_open = (
            "    auto const fd = get_fd(session, open_files, tor, writable, file_index, error);\n"
        )
        new_open = (
            "    // ANDROID fork: stage 0 timing.\n"
            "    auto fd = std::optional<tr_sys_file_t>{};\n"
            "    {\n"
            "        tr_disk_timer const disk_timer{ \"open\", tor.file_subpath(file_index) };\n"
            "        fd = get_fd(session, open_files, tor, writable, file_index, error);\n"
            "    }\n"
        )
        if old_open not in text:
            sys.exit("ERROR: inout.cc get_fd call not found")
        text = text.replace(old_open, new_open, 1)
        # 14b. read_or_write_bytes: write_entire_buf -> "pwrite"
        old_pw = (
            '        fmtstr = _("Couldn\'t save \'{path}\': {error} ({error_code})");\n'
            "        write_entire_buf(*fd, file_offset, buf, buflen, error);\n"
        )
        new_pw = (
            '        fmtstr = _("Couldn\'t save \'{path}\': {error} ({error_code})");\n'
            "        {\n"
            "            tr_disk_timer const disk_timer{ \"pwrite\", tor.file_subpath(file_index), file_offset, buflen };\n"
            "            write_entire_buf(*fd, file_offset, buf, buflen, error);\n"
            "        }\n"
        )
        if old_pw not in text:
            sys.exit("ERROR: inout.cc write_entire_buf call not found")
        text = text.replace(old_pw, new_pw, 1)
        # 14c. read_or_write_bytes: read_entire_buf -> "pread"
        old_pr = (
            '        fmtstr = _("Couldn\'t read \'{path}\': {error} ({error_code})");\n'
            "        read_entire_buf(*fd, file_offset, buf, buflen, error);\n"
        )
        new_pr = (
            '        fmtstr = _("Couldn\'t read \'{path}\': {error} ({error_code})");\n'
            "        {\n"
            "            tr_disk_timer const disk_timer{ \"pread\", tor.file_subpath(file_index), file_offset, buflen };\n"
            "            read_entire_buf(*fd, file_offset, buf, buflen, error);\n"
            "        }\n"
        )
        if old_pr not in text:
            sys.exit("ERROR: inout.cc read_entire_buf call not found")
        text = text.replace(old_pr, new_pr, 1)
        # 14d. tr_ioTestPiece whole body -> "testpiece"
        old_tpi = (
            "    auto const [read_ok, hash] = recalculate_hash(tor, piece);\n"
            "    if (!read_ok)\n"
            "    {\n"
            "        return -1;\n"
            "    }\n"
            "    return hash && *hash == tor.piece_hash(piece) ? 1 : 0;\n"
        )
        new_tpi = (
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"testpiece\", tor.name(), piece, tor.piece_size(piece) };\n"
            "    auto const [read_ok, hash] = recalculate_hash(tor, piece);\n"
            "    if (!read_ok)\n"
            "    {\n"
            "        return -1;\n"
            "    }\n"
            "    return hash && *hash == tor.piece_hash(piece) ? 1 : 0;\n"
        )
        if old_tpi not in text:
            sys.exit("ERROR: inout.cc tr_ioTestPiece body not found")
        text = text.replace(old_tpi, new_tpi, 1)
        with open(io2, "w", encoding="utf-8") as f:
            f.write(text)

    sess = os.path.join(libt, "session.cc")
    with open(sess, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 0 timing" not in text:
        old_inc = '#include "libtransmission/utils.h"\n'
        new_inc = '#include "libtransmission/utils.h"\n#include "libtransmission/disk-timing.h"\n'
        if old_inc not in text:
            sys.exit("ERROR: session.cc utils.h include not found")
        text = text.replace(old_inc, new_inc, 1)
        # 14e. tr_session::flush_torrent_files -> "flush"
        old_flush = (
            "void tr_session::flush_torrent_files(tr_torrent_id_t const tor_id) const noexcept\n"
            "{\n"
            "    this->cache->flush_torrent(tor_id);\n"
            "}\n"
        )
        new_flush = (
            "void tr_session::flush_torrent_files(tr_torrent_id_t const tor_id) const noexcept\n"
            "{\n"
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"flush\", \"\", static_cast<uint64_t>(tor_id) };\n"
            "    this->cache->flush_torrent(tor_id);\n"
            "}\n"
        )
        if old_flush not in text:
            sys.exit("ERROR: session.cc flush_torrent_files body not found")
        text = text.replace(old_flush, new_flush, 1)
        # 14f. tr_session::close_torrent_files -> "close"
        old_close = (
            "void tr_session::close_torrent_files(tr_torrent_id_t const tor_id) noexcept\n"
            "{\n"
            "    this->cache->flush_torrent(tor_id);\n"
            "    openFiles().close_torrent(tor_id);\n"
            "}\n"
        )
        new_close = (
            "void tr_session::close_torrent_files(tr_torrent_id_t const tor_id) noexcept\n"
            "{\n"
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"close\", \"\", static_cast<uint64_t>(tor_id) };\n"
            "    this->cache->flush_torrent(tor_id);\n"
            "    openFiles().close_torrent(tor_id);\n"
            "}\n"
        )
        if old_close not in text:
            sys.exit("ERROR: session.cc close_torrent_files body not found")
        text = text.replace(old_close, new_close, 1)
        # 14g. tr_session::close_torrent_file -> "closefile"
        old_closef = (
            "void tr_session::close_torrent_file(tr_torrent const& tor, tr_file_index_t file_num) noexcept\n"
            "{\n"
            "    this->cache->flush_file(tor, file_num);\n"
            "    openFiles().close_file(tor.id(), file_num);\n"
            "}\n"
        )
        new_closef = (
            "void tr_session::close_torrent_file(tr_torrent const& tor, tr_file_index_t file_num) noexcept\n"
            "{\n"
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"closefile\", \"\", file_num };\n"
            "    this->cache->flush_file(tor, file_num);\n"
            "    openFiles().close_file(tor.id(), file_num);\n"
            "}\n"
        )
        if old_closef not in text:
            sys.exit("ERROR: session.cc close_torrent_file body not found")
        text = text.replace(old_closef, new_closef, 1)
        with open(sess, "w", encoding="utf-8") as f:
            f.write(text)

    # 15. Patch cache.h + cache.cc: ANDROID fork: copy_cached_block
    #     (TRANSMISSION_USB_SAF stage 1.4). Copy a cached block without
    #     falling back to disk; used by tr_disk_make_piece_overlay on the
    #     session thread before a piece check is posted to the disk worker.
    cache_h = os.path.join(libt, "cache.h")
    with open(cache_h, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: copy_cached_block" not in text:
        text = text.replace(
            "#include <memory> // for std::unique_ptr\n#include <utility> // for std::pair\n",
            "#include <memory> // for std::unique_ptr\n#include <optional>\n#include <utility> // for std::pair\n",
            1,
        )
        old = "    int flush_file(tr_torrent const& tor, tr_file_index_t file);\n"
        new = (
            "    int flush_file(tr_torrent const& tor, tr_file_index_t file);\n"
            "\n"
            "    // ANDROID fork: copy_cached_block — copy a cached block without\n"
            "    // falling back to disk. Empty if not cached.\n"
            "    [[nodiscard]] std::optional<std::vector<uint8_t>> copy_cached_block(tr_torrent_id_t tor_id, tr_block_index_t block) const;\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.h flush_file declaration not found")
        text = text.replace(old, new, 1)
        with open(cache_h, "w", encoding="utf-8") as f:
            f.write(text)

    cache_cc = os.path.join(libt, "cache.cc")
    with open(cache_cc, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: copy_cached_block" not in text:
        text = text.replace(
            "#include <memory>\n#include <numeric> // std::accumulate()\n",
            "#include <memory>\n#include <optional>\n#include <numeric> // std::accumulate()\n",
            1,
        )
        old = (
            "        begin < end)\n"
            "    {\n"
            "        return begin;\n"
            "    }\n"
            "\n"
            "    return std::end(blocks_);\n"
            "}\n"
        )
        new = (
            "        begin < end)\n"
            "    {\n"
            "        return begin;\n"
            "    }\n"
            "\n"
            "    return std::end(blocks_);\n"
            "}\n"
            "\n"
            "// ANDROID fork: copy_cached_block — never touches the disk\n"
            "// (TRANSMISSION_USB_SAF stage 1.4).\n"
            "std::optional<std::vector<uint8_t>> Cache::copy_cached_block(tr_torrent_id_t tor_id, tr_block_index_t block) const\n"
            "{\n"
            "    if (auto const [begin, end] = std::equal_range(\n"
            "            std::begin(blocks_),\n"
            "            std::end(blocks_),\n"
            "            Key{ tor_id, block },\n"
            "            CompareCacheBlockByKey);\n"
            "        begin < end)\n"
            "    {\n"
            "        auto const& buf = *begin->buf;\n"
            "        return std::vector<uint8_t>{ std::begin(buf), std::end(buf) };\n"
            "    }\n"
            "\n"
            "    return {};\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc get_block body not found")
        text = text.replace(old, new, 1)
        with open(cache_cc, "w", encoding="utf-8") as f:
            f.write(text)

    # 16. Patch session.h + session.cc: ANDROID fork: disk I/O worker wiring
    #     (TRANSMISSION_USB_SAF stage 1.5). Create the worker in initImpl,
    #     shut it down in closeImplPart1, forward close_torrent_files /
    #     close_torrent_file to it (null-checked: it is reset first on close).
    sess_h = os.path.join(libt, "session.h")
    with open(sess_h, "r", encoding="utf-8") as f:
        text = f.read()
    if "disk I/O worker (TRANSMISSION_USB_SAF stage 1.5)" not in text:
        text = text.replace(
            '#include "libtransmission/cache.h"\n',
            '#include "libtransmission/cache.h"\n#include "libtransmission/disk-io.h"\n',
            1,
        )
        old = (
            "    [[nodiscard]] constexpr auto& openFiles() noexcept\n"
            "    {\n"
            "        return open_files_;\n"
            "    }\n"
        )
        new = (
            "    [[nodiscard]] constexpr auto& openFiles() noexcept\n"
            "    {\n"
            "        return open_files_;\n"
            "    }\n"
            "\n"
            "    // ANDROID fork: disk I/O worker (TRANSMISSION_USB_SAF stage 1.5).\n"
            "    [[nodiscard]] tr_disk_io& disk_io() noexcept\n"
            "    {\n"
            "        return *disk_io_;\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: session.h openFiles() accessor not found")
        text = text.replace(old, new, 1)
        old = "    std::unique_ptr<Cache> cache = std::make_unique<Cache>(torrents_, Memory{ 2U, Memory::Units::MBytes });\n"
        new = (
            "    std::unique_ptr<Cache> cache = std::make_unique<Cache>(torrents_, Memory{ 2U, Memory::Units::MBytes });\n"
            "    std::unique_ptr<tr_disk_io> disk_io_; // ANDROID fork\n"
        )
        if old not in text:
            sys.exit("ERROR: session.h cache member not found")
        text = text.replace(old, new, 1)
        with open(sess_h, "w", encoding="utf-8") as f:
            f.write(text)

    sess_cc = os.path.join(libt, "session.cc")
    with open(sess_cc, "r", encoding="utf-8") as f:
        text = f.read()
    if "stage 1.5 session wiring" not in text:
        old = (
            "void tr_session::initImpl(init_data& data)\n"
            "{\n"
            "    auto lock = unique_lock();\n"
            "    TR_ASSERT(am_in_session_thread());\n"
        )
        new = (
            "void tr_session::initImpl(init_data& data)\n"
            "{\n"
            "    auto lock = unique_lock();\n"
            "    TR_ASSERT(am_in_session_thread());\n"
            "\n"
            "    disk_io_ = std::make_unique<tr_disk_io>(*this); // ANDROID fork: stage 1.5 session wiring\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc initImpl body not found")
        text = text.replace(old, new, 1)
        old = (
            "    utp_timer.reset();\n"
            "    verifier_.reset();\n"
            "    save_timer_.reset();\n"
        )
        new = (
            "    utp_timer.reset();\n"
            "    verifier_.reset();\n"
            "    disk_io_->shutdown(); disk_io_.reset(); // ANDROID fork: stage 1.5 session wiring\n"
            "    save_timer_.reset();\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc closeImplPart1 verifier_.reset() not found")
        text = text.replace(old, new, 1)
        old = (
            "    this->cache->flush_torrent(tor_id);\n"
            "    openFiles().close_torrent(tor_id);\n"
            "}\n"
        )
        new = (
            "    this->cache->flush_torrent(tor_id);\n"
            "    openFiles().close_torrent(tor_id);\n"
            "    if (disk_io_) disk_io_->close_torrent(tor_id); // ANDROID fork: stage 1.5 session wiring\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc close_torrent_files body not found")
        text = text.replace(old, new, 1)
        old = (
            "    this->cache->flush_file(tor, file_num);\n"
            "    openFiles().close_file(tor.id(), file_num);\n"
            "}\n"
        )
        new = (
            "    this->cache->flush_file(tor, file_num);\n"
            "    openFiles().close_file(tor.id(), file_num);\n"
            "    if (disk_io_) disk_io_->close_file(tor.id(), file_num); // ANDROID fork: stage 1.5 session wiring\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc close_torrent_file body not found")
        text = text.replace(old, new, 1)
        with open(sess_cc, "w", encoding="utf-8") as f:
            f.write(text)

    # 17. Patch torrent.h + torrent.cc: ANDROID fork: per-torrent pending-check
    #     set + async post-download piece verification (TRANSMISSION_USB_SAF
    #     stages 1.6/1.7). While a piece is pending it is masked from the peer
    #     bitfield, the resume file, HAVE-ALL and upload requests, and
    #     recheck_completeness() is deferred until the checks finish.
    tor_h = os.path.join(libt, "torrent.h")
    with open(tor_h, "r", encoding="utf-8") as f:
        text = f.read()
    if "stage 1.6 pending-check set" not in text:
        text = text.replace(
            "#include <optional>\n#include <string>\n",
            "#include <optional>\n#include <set>\n#include <string>\n",
            1,
        )
        old = "        [[nodiscard]] tr_bitfield const& blocks() const noexcept;\n"
        new = (
            "        // ANDROID fork: stage 1.6/1.7 — by value so pending pieces can be masked.\n"
            "        [[nodiscard]] tr_bitfield blocks() const;\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h ResumeHelper::blocks() declaration not found")
        text = text.replace(old, new, 1)
        # The main tr_torrent body is public (struct default) up to the
        # unindented `private:` label; put the accessors before it and the
        # methods/member inside the private section.
        old = (
            "    time_t lpdAnnounceAt = 0;\n"
            "\n"
            "private:\n"
            "    friend bool tr_torrentSetMetainfoFromFile(tr_torrent* tor, tr_torrent_metainfo const* metainfo, char const* filename);\n"
        )
        new = (
            "    time_t lpdAnnounceAt = 0;\n"
            "\n"
            "    // ANDROID fork: stage 1.6 pending-check set (TRANSMISSION_USB_SAF).\n"
            "    [[nodiscard]] bool is_piece_check_pending(tr_piece_index_t piece) const { return pending_checks_.count(piece) != 0U; }\n"
            "    [[nodiscard]] bool has_pending_checks() const noexcept { return !std::empty(pending_checks_); }\n"
            "    void clear_pending_checks() noexcept { pending_checks_.clear(); }\n"
            "    [[nodiscard]] constexpr bool is_unhaving_for_read_error() const noexcept { return unhaving_for_read_error_; }\n"
            "\n"
            "private:\n"
            "    // ANDROID fork: stage 1.6/1.7 async piece checks (TRANSMISSION_USB_SAF)\n"
            "    void enqueue_piece_check(tr_piece_index_t piece);\n"
            "    void on_piece_check_done(tr_piece_index_t piece, int result);\n"
            "    void on_piece_read_error(tr_piece_index_t piece);\n"
            "    std::set<tr_piece_index_t> pending_checks_; // ANDROID fork\n"
            "    bool unhaving_for_read_error_ = false; // ANDROID fork\n"
            "\n"
            "    friend bool tr_torrentSetMetainfoFromFile(tr_torrent* tor, tr_torrent_metainfo const* metainfo, char const* filename);\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h lpdAnnounceAt/private section not found")
        text = text.replace(old, new, 1)
        old = (
            "    [[nodiscard]] auto create_piece_bitfield() const\n"
            "    {\n"
            "        return completion_.create_piece_bitfield();\n"
            "    }\n"
        )
        new = (
            "    [[nodiscard]] auto create_piece_bitfield() const\n"
            "    {\n"
            "        auto bits = completion_.create_piece_bitfield();\n"
            "        // ANDROID fork: stage 1.6 — mask pieces whose check is pending.\n"
            "        for (auto const piece : pending_checks_)\n"
            "        {\n"
            "            bits[piece / 8U] &= static_cast<uint8_t>(~(0x80U >> (piece % 8U)));\n"
            "        }\n"
            "        return bits;\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h create_piece_bitfield body not found")
        text = text.replace(old, new, 1)
        with open(tor_h, "w", encoding="utf-8") as f:
            f.write(text)

    tor_cc = os.path.join(libt, "torrent.cc")
    with open(tor_cc, "r", encoding="utf-8") as f:
        text = f.read()
    if "stage 1.6/1.7 async piece checks" not in text:
        old = (
            "        if (check_piece(piece))\n"
            "        {\n"
            "            on_piece_completed(piece);\n"
            "        }\n"
            "        else\n"
            "        {\n"
            "            on_piece_failed(piece);\n"
            "        }\n"
        )
        new = (
            "        // ANDROID fork: verify on the disk worker (TRANSMISSION_USB_SAF stage 1).\n"
            "        if (!is_piece_check_pending(piece))\n"
            "        {\n"
            "            enqueue_piece_check(piece);\n"
            "        }\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc on_block_received check_piece block not found")
        text = text.replace(old, new, 1)
        old = (
            "void tr_torrent::recheck_completeness()\n"
            "{\n"
            "    using namespace completeness_helpers;\n"
            "\n"
            "    auto const lock = unique_lock();\n"
        )
        new = (
            "void tr_torrent::recheck_completeness()\n"
            "{\n"
            "    using namespace completeness_helpers;\n"
            "\n"
            "    auto const lock = unique_lock();\n"
            "\n"
            "    if (has_pending_checks())\n"
            "    {\n"
            "        return; // ANDROID fork: re-run when checks finish\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc recheck_completeness body not found")
        text = text.replace(old, new, 1)
        old = "            session->verify_remove(tor);\n"
        new = (
            "            session->verify_remove(tor);\n"
            "            tor->clear_pending_checks(); // ANDROID fork: a full verify recomputes every piece\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc tr_torrentVerify lambda verify_remove not found")
        text = text.replace(old, new, 1)
        old = (
            "tr_bitfield const& tr_torrent::ResumeHelper::blocks() const noexcept\n"
            "{\n"
            "    return tor_.completion_.blocks();\n"
            "}\n"
        )
        new = (
            "// ANDROID fork: stage 1.6/1.7 — exclude pieces whose check is pending.\n"
            "tr_bitfield tr_torrent::ResumeHelper::blocks() const\n"
            "{\n"
            "    auto copy = tor_.completion_.blocks();\n"
            "    for (auto const p : tor_.pending_checks_)\n"
            "    {\n"
            "        auto const [begin, end] = tor_.block_span_for_piece(p);\n"
            "        copy.unset_span(begin, end);\n"
            "    }\n"
            "    return copy;\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc ResumeHelper::blocks() definition not found")
        text = text.replace(old, new, 1)
        old = (
            "void tr_torrent::on_block_received(tr_block_index_t const block)\n"
            "{\n"
        )
        new = (
            "// ANDROID fork: stage 1.6/1.7 async piece checks (TRANSMISSION_USB_SAF).\n"
            "void tr_torrent::enqueue_piece_check(tr_piece_index_t const piece)\n"
            "{\n"
            "    pending_checks_.insert(piece);\n"
            "    session->disk_io().test_piece(\n"
            "        tr_disk_queue::Prio::Low,\n"
            "        tr_disk_make_segments(*this, piece_loc(piece), piece_size(piece), false),\n"
            "        tr_disk_make_piece_overlay(*this, piece),\n"
            "        piece_hash(piece),\n"
            "        [session = session, tor_id = id(), piece](int result)\n"
            "        {\n"
            "            if (auto* const tor = session->torrents().get(tor_id); tor != nullptr)\n"
            "            {\n"
            "                tor->on_piece_check_done(piece, result);\n"
            "            }\n"
            "        });\n"
            "}\n"
            "\n"
            "void tr_torrent::on_piece_check_done(tr_piece_index_t const piece, int const result)\n"
            "{\n"
            "    if (pending_checks_.erase(piece) == 0)\n"
            "    {\n"
            "        return; // stale result (e.g. a full verify cleared the set)\n"
            "    }\n"
            "\n"
            "    if (result == 1)\n"
            "    {\n"
            "        on_piece_completed(piece);\n"
            "    }\n"
            "    else if (result == 0)\n"
            "    {\n"
            "        on_piece_failed(piece);\n"
            "    }\n"
            "    else\n"
            "    {\n"
            "        on_piece_read_error(piece);\n"
            "    }\n"
            "\n"
            "    set_dirty();\n"
            "    set_needs_completeness_check();\n"
            "}\n"
            "\n"
            "void tr_torrent::on_piece_read_error(tr_piece_index_t const piece)\n"
            "{\n"
            '    tr_logAddWarnTor(this, fmt::format("Piece {} could not be read back for verification; re-downloading", piece));\n'
            "    bytes_downloaded_.reduce(piece_size(piece));\n"
            "    set_has_piece(piece, false);\n"
            "    // ANDROID fork: re-add the piece to the peer-mgr wishlist so its blocks are\n"
            "    // requested again. Observers run synchronously, so the flag is visible to\n"
            "    // tr_swarm::on_got_bad_piece, which skips blame for read errors.\n"
            "    unhaving_for_read_error_ = true;\n"
            "    got_bad_piece_.emit(this, piece); // ANDROID fork: re-add to the wishlist; peer-mgr skips blame\n"
            "    unhaving_for_read_error_ = false;\n"
            "}\n"
            "\n"
            "void tr_torrent::on_block_received(tr_block_index_t const block)\n"
            "{\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc on_block_received definition not found")
        text = text.replace(old, new, 1)
        with open(tor_cc, "w", encoding="utf-8") as f:
            f.write(text)

    # 18. Patch peer-msgs.cc: ANDROID fork: peer-facing masking of pieces whose
    #     post-download check is pending (TRANSMISSION_USB_SAF stage 1.8).
    #     No HAVE-ALL while checks are pending (the masked bitfield is sent
    #     instead), and upload requests for pending pieces are rejected/dropped.
    pm = os.path.join(libt, "peer-msgs.cc")
    with open(pm, "r", encoding="utf-8") as f:
        text = f.read()
    if "stage 1.8 peer-facing masking" not in text:
        old = "    if (fext && tor_.has_all())\n"
        new = "    if (fext && tor_.has_all() && !tor_.has_pending_checks()) // ANDROID fork: stage 1.8 peer-facing masking\n"
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc protocol_send_bitfield has_all() not found")
        text = text.replace(old, new, 1)
        old = "    auto ok = is_valid_request(req) && tor_.has_piece(req.index);\n"
        new = (
            "    auto ok = is_valid_request(req) && tor_.has_piece(req.index) && !tor_.is_piece_check_pending(req.index); "
            "// ANDROID fork: stage 1.8 peer-facing masking\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc add_next_block ok= line not found")
        text = text.replace(old, new, 1)
        with open(pm, "w", encoding="utf-8") as f:
            f.write(text)

    # 19. Patch peer-mgr.cc: ANDROID fork: read-error no blame
    #     (TRANSMISSION_USB_SAF stage 1). tr_swarm::on_got_bad_piece normally
    #     strikes peers and counts TR_ANN_CORRUPT bytes; that is forbidden for
    #     a local read error, where only the wishlist re-add is wanted.
    #     tr_torrent::on_piece_read_error sets unhaving_for_read_error_ around
    #     the got_bad_piece_ emit, and observers run synchronously.
    pmgr = os.path.join(libt, "peer-mgr.cc")
    with open(pmgr, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: read-error no blame" not in text:
        old = "    void on_got_bad_piece(tr_piece_index_t piece)\n    {\n"
        new = (
            "    void on_got_bad_piece(tr_piece_index_t piece)\n"
            "    {\n"
            "        if (tor->is_unhaving_for_read_error())\n"
            "        {\n"
            "            return; // ANDROID fork: read-error no blame (TRANSMISSION_USB_SAF stage 1)\n"
            "        }\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-mgr.cc on_got_bad_piece body not found")
        text = text.replace(old, new, 1)
        with open(pmgr, "w", encoding="utf-8") as f:
            f.write(text)

    # 20. Patch cache.h + cache.cc: ANDROID fork: stage 2 async cache writes
    #     (TRANSMISSION_USB_SAF 2.2). The session thread never calls tr_ioWrite:
    #     contiguous block runs are marked in-flight and handed to the disk
    #     worker with shared buffer copies; the write completion (session
    #     thread) erases the blocks. The cache limit counts non-in-flight
    #     blocks only; in-flight blocks stay readable until they are written.
    cache_h2 = os.path.join(libt, "cache.h")
    with open(cache_h2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 2 async cache writes" not in text:
        old = "#include <memory> // for std::unique_ptr\n#include <optional>\n"
        new = "#include <memory> // for std::shared_ptr, std::unique_ptr\n#include <optional>\n"
        if old not in text:
            sys.exit("ERROR: cache.h <memory>/<optional> includes not found")
        text = text.replace(old, new, 1)
        old = "class tr_torrents;\nstruct tr_torrent;\n"
        new = (
            "class tr_torrents;\n"
            "struct tr_torrent;\n"
            "struct tr_disk_write_result; // ANDROID fork: stage 2\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.h forward declarations not found")
        text = text.replace(old, new, 1)
        old = "    int flush_file(tr_torrent const& tor, tr_file_index_t file);\n"
        new = (
            "    int flush_file(tr_torrent const& tor, tr_file_index_t file);\n"
            "    int flush_piece(tr_torrent const& tor, tr_piece_index_t piece); // ANDROID fork: stage 2\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.h flush_file declaration not found")
        text = text.replace(old, new, 1)
        old = "    [[nodiscard]] std::optional<std::vector<uint8_t>> copy_cached_block(tr_torrent_id_t tor_id, tr_block_index_t block) const;\n"
        new = (
            "    [[nodiscard]] std::optional<std::vector<uint8_t>> copy_cached_block(tr_torrent_id_t tor_id, tr_block_index_t block) const;\n"
            "\n"
            "    // ANDROID fork: stage 2 async cache writes — internal; called from\n"
            "    // the disk write completion on the session thread.\n"
            "    using Key = std::pair<tr_torrent_id_t, tr_block_index_t>;\n"
            "    // `written` holds shared_ptr copies so a freed buffer's address can't be reused by a\n"
            "    // re-downloaded block before this runs (ABA).\n"
            "    void on_write_done(std::vector<std::pair<Key, std::shared_ptr<BlockData const>>> const& written, tr_disk_write_result const& result);\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.h copy_cached_block declaration not found")
        text = text.replace(old, new, 1)
        old = (
            "private:\n"
            "    using Key = std::pair<tr_torrent_id_t, tr_block_index_t>;\n"
            "\n"
            "    struct CacheBlock\n"
            "    {\n"
            "        Key key;\n"
            "        std::unique_ptr<BlockData> buf;\n"
            "    };\n"
            "\n"
            "    using Blocks = std::vector<CacheBlock>;\n"
            "    using CIter = Blocks::const_iterator;\n"
            "\n"
            "    [[nodiscard]] static Key make_key(tr_torrent const& tor, tr_block_info::Location loc) noexcept;\n"
            "\n"
            "    [[nodiscard]] static std::pair<CIter, CIter> find_biggest_span(CIter const& begin, CIter const& end) noexcept;\n"
            "\n"
            "    [[nodiscard]] static CIter find_span_end(CIter const& span_begin, CIter const& end) noexcept;\n"
            "\n"
            "    // @return any error code from tr_ioWrite()\n"
            "    [[nodiscard]] int write_contiguous(CIter const& begin, CIter const& end) const;\n"
            "\n"
            "    // @return any error code from writeContiguous()\n"
            "    [[nodiscard]] int flush_span(CIter const& begin, CIter const& end);\n"
            "\n"
            "    // @return any error code from writeContiguous()\n"
            "    [[nodiscard]] int flush_biggest();\n"
            "\n"
            "    // @return any error code from writeContiguous()\n"
            "    [[nodiscard]] int cache_trim();\n"
            "\n"
            "    [[nodiscard]] static constexpr size_t get_max_blocks(Memory const max_size) noexcept\n"
            "    {\n"
            "        return max_size.base_quantity() / tr_block_info::BlockSize;\n"
            "    }\n"
            "\n"
            "    [[nodiscard]] CIter get_block(tr_torrent const& tor, tr_block_info::Location const& loc) noexcept;\n"
            "\n"
            "    tr_torrents const& torrents_;\n"
            "\n"
            "    Blocks blocks_;\n"
            "    size_t max_blocks_ = 0;\n"
            "\n"
            "    mutable size_t disk_writes_ = 0;\n"
            "    mutable size_t disk_write_bytes_ = 0;\n"
            "    mutable size_t cache_writes_ = 0;\n"
            "    mutable size_t cache_write_bytes_ = 0;\n"
        )
        new = (
            "private:\n"
            "    struct CacheBlock\n"
            "    {\n"
            "        Key key;\n"
            "        std::shared_ptr<BlockData const> buf;\n"
            "        // ANDROID fork: stage 2 — mutable so the const span walkers can\n"
            "        // mark blocks in-flight without const_cast.\n"
            "        mutable bool in_flight = false;\n"
            "    };\n"
            "\n"
            "    using Blocks = std::vector<CacheBlock>;\n"
            "    using CIter = Blocks::const_iterator;\n"
            "\n"
            "    [[nodiscard]] static Key make_key(tr_torrent const& tor, tr_block_info::Location loc) noexcept;\n"
            "\n"
            "    [[nodiscard]] static std::pair<CIter, CIter> find_biggest_span(CIter const& begin, CIter const& end) noexcept;\n"
            "\n"
            "    [[nodiscard]] static CIter find_span_end(CIter const& span_begin, CIter const& end) noexcept;\n"
            "\n"
            "    // ANDROID fork: stage 2 — hand the span to the disk worker (no tr_ioWrite).\n"
            "    // @return any error code from enqueueing (EINVAL if the torrent is gone)\n"
            "    [[nodiscard]] int enqueue_write(CIter const& begin, CIter const& end);\n"
            "\n"
            "    // @return any error code from enqueue_write()\n"
            "    [[nodiscard]] int flush_span(CIter const& begin, CIter const& end);\n"
            "\n"
            "    // @return any error code from enqueue_write()\n"
            "    [[nodiscard]] int flush_biggest();\n"
            "\n"
            "    // @return any error code from enqueue_write()\n"
            "    [[nodiscard]] int cache_trim();\n"
            "\n"
            "    [[nodiscard]] static constexpr size_t get_max_blocks(Memory const max_size) noexcept\n"
            "    {\n"
            "        return max_size.base_quantity() / tr_block_info::BlockSize;\n"
            "    }\n"
            "\n"
            "    [[nodiscard]] CIter get_block(tr_torrent const& tor, tr_block_info::Location const& loc) noexcept;\n"
            "\n"
            "    tr_torrents const& torrents_;\n"
            "\n"
            "    Blocks blocks_;\n"
            "    size_t max_blocks_ = 0;\n"
            "    size_t in_flight_count_ = 0; // ANDROID fork: stage 2 — blocks handed to the worker\n"
            "\n"
            "    mutable size_t disk_writes_ = 0;\n"
            "    mutable size_t disk_write_bytes_ = 0;\n"
            "    mutable size_t cache_writes_ = 0;\n"
            "    mutable size_t cache_write_bytes_ = 0;\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.h private section not found")
        text = text.replace(old, new, 1)
        with open(cache_h2, "w", encoding="utf-8") as f:
            f.write(text)

    cache_cc2 = os.path.join(libt, "cache.cc")
    with open(cache_cc2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 2 async cache writes" not in text:
        old = '#include "libtransmission/cache.h"\n#include "libtransmission/inout.h"\n'
        new = '#include "libtransmission/cache.h"\n#include "libtransmission/disk-io.h"\n#include "libtransmission/inout.h"\n'
        if old not in text:
            sys.exit("ERROR: cache.cc cache.h/inout.h includes not found")
        text = text.replace(old, new, 1)
        old = '#include "libtransmission/log.h"\n#include "libtransmission/torrent.h"\n'
        new = '#include "libtransmission/log.h"\n#include "libtransmission/session.h"\n#include "libtransmission/torrent.h"\n'
        if old not in text:
            sys.exit("ERROR: cache.cc log.h/torrent.h includes not found")
        text = text.replace(old, new, 1)
        # find_span_end: in-flight blocks break span adjacency
        old = (
            "Cache::CIter Cache::find_span_end(CIter const& span_begin, CIter const& end) noexcept\n"
            "{\n"
            "    static constexpr auto NotAdjacent = [](CacheBlock const& block1, CacheBlock const& block2)\n"
            "    {\n"
            "        return block1.key.first != block2.key.first || block1.key.second + 1 != block2.key.second;\n"
            "    };\n"
            "    auto const span_end = std::adjacent_find(span_begin, end, NotAdjacent);\n"
            "    return span_end == end ? end : std::next(span_end);\n"
            "}\n"
        )
        new = (
            "Cache::CIter Cache::find_span_end(CIter const& span_begin, CIter const& end) noexcept\n"
            "{\n"
            "    // ANDROID fork: stage 2 — an in-flight block is already on its way\n"
            "    // to the disk, so it cannot join a new write span.\n"
            "    static constexpr auto NotAdjacent = [](CacheBlock const& block1, CacheBlock const& block2)\n"
            "    {\n"
            "        return block1.in_flight || block2.in_flight || block1.key.first != block2.key.first ||\n"
            "            block1.key.second + 1 != block2.key.second;\n"
            "    };\n"
            "    auto const span_end = std::adjacent_find(span_begin, end, NotAdjacent);\n"
            "    return span_end == end ? end : std::next(span_end);\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc find_span_end body not found")
        text = text.replace(old, new, 1)
        # find_biggest_span: skip spans whose first block is in-flight
        old = (
            "std::pair<Cache::CIter, Cache::CIter> Cache::find_biggest_span(CIter const& begin, CIter const& end) noexcept\n"
            "{\n"
            "    auto biggest_begin = begin;\n"
            "    auto biggest_end = begin;\n"
            "    auto biggest_len = std::distance(biggest_begin, biggest_end);\n"
            "\n"
            "    for (auto span_begin = begin; span_begin < end;)\n"
            "    {\n"
            "        auto span_end = find_span_end(span_begin, end);\n"
            "\n"
            "        if (auto const len = std::distance(span_begin, span_end); len > biggest_len)\n"
            "        {\n"
            "            biggest_begin = span_begin;\n"
            "            biggest_end = span_end;\n"
            "            biggest_len = len;\n"
            "        }\n"
            "\n"
            "        span_begin = span_end;\n"
            "    }\n"
            "\n"
            "    return { biggest_begin, biggest_end };\n"
            "}\n"
        )
        new = (
            "std::pair<Cache::CIter, Cache::CIter> Cache::find_biggest_span(CIter const& begin, CIter const& end) noexcept\n"
            "{\n"
            "    auto biggest_begin = begin;\n"
            "    auto biggest_end = begin;\n"
            "    auto biggest_len = std::distance(biggest_begin, biggest_end);\n"
            "\n"
            "    for (auto span_begin = begin; span_begin < end;)\n"
            "    {\n"
            "        auto span_end = find_span_end(span_begin, end);\n"
            "\n"
            "        if (!span_begin->in_flight) // ANDROID fork: stage 2 — skip in-flight spans\n"
            "        {\n"
            "            if (auto const len = std::distance(span_begin, span_end); len > biggest_len)\n"
            "            {\n"
            "                biggest_begin = span_begin;\n"
            "                biggest_end = span_end;\n"
            "                biggest_len = len;\n"
            "            }\n"
            "        }\n"
            "\n"
            "        span_begin = span_end;\n"
            "    }\n"
            "\n"
            "    return { biggest_begin, biggest_end };\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc find_biggest_span body not found")
        text = text.replace(old, new, 1)
        # write_contiguous -> enqueue_write (no tr_ioWrite on the session thread)
        old = (
            "int Cache::write_contiguous(CIter const& begin, CIter const& end) const\n"
            "{\n"
            "    // The most common case without an extra data copy.\n"
            "    auto const* out = std::data(*begin->buf);\n"
            "    auto outlen = std::size(*begin->buf);\n"
            "\n"
            "    // Contiguous area to join more than one block, if any.\n"
            "    auto buf = std::vector<uint8_t>{};\n"
            "\n"
            "    if (end - begin > 1)\n"
            "    {\n"
            "        // copy blocks into contiguous memory\n"
            "        auto const buflen = std::accumulate(\n"
            "            begin,\n"
            "            end,\n"
            "            size_t{},\n"
            "            [](size_t sum, auto const& block) { return sum + std::size(*block.buf); });\n"
            "        buf.resize(buflen);\n"
            "        auto* walk = std::data(buf);\n"
            "        for (auto iter = begin; iter != end; ++iter)\n"
            "        {\n"
            "            TR_ASSERT(begin->key.first == iter->key.first);\n"
            "            TR_ASSERT(begin->key.second + std::distance(begin, iter) == iter->key.second);\n"
            "            walk = std::copy_n(std::data(*iter->buf), std::size(*iter->buf), walk);\n"
            "        }\n"
            "        TR_ASSERT(std::data(buf) + std::size(buf) == walk);\n"
            "        out = std::data(buf);\n"
            "        outlen = std::size(buf);\n"
            "    }\n"
            "\n"
            "    // save it\n"
            "    auto const& [torrent_id, block] = begin->key;\n"
            "    auto* const tor = torrents_.get(torrent_id);\n"
            "    if (tor == nullptr)\n"
            "    {\n"
            "        return EINVAL;\n"
            "    }\n"
            "\n"
            "    auto const loc = tor->block_loc(block);\n"
            "\n"
            "    if (auto const err = tr_ioWrite(*tor, loc, outlen, out); err != 0)\n"
            "    {\n"
            "        return err;\n"
            "    }\n"
            "\n"
            "    ++disk_writes_;\n"
            "    disk_write_bytes_ += outlen;\n"
            "    return {};\n"
            "}\n"
        )
        new = (
            "// ANDROID fork: stage 2 async cache writes (TRANSMISSION_USB_SAF 2.2).\n"
            "// Hands a span of cached blocks to the disk worker; the session\n"
            "// thread no longer performs synchronous block writes. The blocks\n"
            "// stay in the cache\n"
            "// (marked in-flight, so read_block and the overlay still find them)\n"
            "// until the write completion erases them. Must not insert/erase\n"
            "// blocks_, so the span iterators stay valid for the walk.\n"
            "int Cache::enqueue_write(CIter const& begin, CIter const& end)\n"
            "{\n"
            "    if (begin == end)\n"
            "    {\n"
            "        return 0;\n"
            "    }\n"
            "\n"
            "    auto const* const tor = torrents_.get(begin->key.first);\n"
            "    if (tor == nullptr)\n"
            "    {\n"
            "        return EINVAL;\n"
            "    }\n"
            "\n"
            "    auto len = uint64_t{};\n"
            "    auto buffers = std::vector<std::shared_ptr<BlockData const>>{};\n"
            "    auto written = std::vector<std::pair<Key, std::shared_ptr<BlockData const>>>{};\n"
            "    for (auto iter = begin; iter != end; ++iter)\n"
            "    {\n"
            "        len += std::size(*iter->buf);\n"
            "        buffers.push_back(iter->buf);\n"
            "        written.emplace_back(iter->key, iter->buf);\n"
            "        iter->in_flight = true;\n"
            "    }\n"
            "    in_flight_count_ += static_cast<size_t>(std::distance(begin, end));\n"
            "\n"
            "    auto const loc = tor->block_loc(begin->key.second);\n"
            "    tor->session->disk_io().write(\n"
            "        tr_disk_make_segments(*tor, loc, len, true),\n"
            "        std::move(buffers),\n"
            "        [session = tor->session, written = std::move(written)](tr_disk_write_result const& result)\n"
            "        {\n"
            "            if (session->cache == nullptr)\n"
            "            {\n"
            "                return; // shutdown in progress\n"
            "            }\n"
            "            session->cache->on_write_done(written, result);\n"
            "        });\n"
            "    return 0;\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc write_contiguous body not found")
        text = text.replace(old, new, 1)
        # write_block: drop the limit-0 bypass; replacing an in-flight entry
        # leaves the in-flight state (the new data is not handed to the worker)
        old = (
            "int Cache::write_block(tr_torrent_id_t const tor_id, tr_block_index_t const block, std::unique_ptr<BlockData> writeme)\n"
            "{\n"
            "    if (max_blocks_ == 0U)\n"
            "    {\n"
            "        TR_ASSERT(std::empty(blocks_));\n"
            "\n"
            "        // Bypass cache. This may be helpful for those whose filesystem\n"
            "        // already has a cache layer for the very purpose of this cache\n"
            "        // https://github.com/transmission/transmission/pull/5668\n"
            "        auto* const tor = torrents_.get(tor_id);\n"
            "        return tor == nullptr ? EINVAL : tr_ioWrite(*tor, tor->block_loc(block), std::size(*writeme), std::data(*writeme));\n"
            "    }\n"
            "\n"
            "    auto const key = Key{ tor_id, block };\n"
            "    auto iter = std::lower_bound(std::begin(blocks_), std::end(blocks_), key, CompareCacheBlockByKey);\n"
            "    if (iter == std::end(blocks_) || iter->key != key)\n"
            "    {\n"
            "        iter = blocks_.emplace(iter);\n"
            "        iter->key = key;\n"
            "    }\n"
            "\n"
            "    iter->buf = std::move(writeme);\n"
            "\n"
            "    ++cache_writes_;\n"
            "    cache_write_bytes_ += std::size(*iter->buf);\n"
            "\n"
            "    return cache_trim();\n"
            "}\n"
        )
        new = (
            "int Cache::write_block(tr_torrent_id_t const tor_id, tr_block_index_t const block, std::unique_ptr<BlockData> writeme)\n"
            "{\n"
            "    // ANDROID fork: stage 2 — no limit-0 bypass: the block is always\n"
            "    // inserted (it stays readable while in-flight), then trimmed.\n"
            "    auto const key = Key{ tor_id, block };\n"
            "    auto iter = std::lower_bound(std::begin(blocks_), std::end(blocks_), key, CompareCacheBlockByKey);\n"
            "    if (iter == std::end(blocks_) || iter->key != key)\n"
            "    {\n"
            "        iter = blocks_.emplace(iter);\n"
            "        iter->key = key;\n"
            "    }\n"
            "    else if (iter->in_flight)\n"
            "    {\n"
            "        // The old buffer is still on its way to the disk; the new data\n"
            "        // has not been handed to the worker yet.\n"
            "        iter->in_flight = false;\n"
            "        --in_flight_count_;\n"
            "    }\n"
            "\n"
            "    iter->buf = std::move(writeme);\n"
            "\n"
            "    ++cache_writes_;\n"
            "    cache_write_bytes_ += std::size(*iter->buf);\n"
            "\n"
            "    return cache_trim();\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc write_block body not found")
        text = text.replace(old, new, 1)
        # flush_span: enqueue only, no blocks_.erase
        old = (
            "int Cache::flush_span(CIter const& begin, CIter const& end)\n"
            "{\n"
            "    for (auto span_begin = begin; span_begin < end;)\n"
            "    {\n"
            "        auto const span_end = find_span_end(span_begin, end);\n"
            "\n"
            "        if (auto const err = write_contiguous(span_begin, span_end); err != 0)\n"
            "        {\n"
            "            return err;\n"
            "        }\n"
            "\n"
            "        span_begin = span_end;\n"
            "    }\n"
            "\n"
            "    blocks_.erase(begin, end);\n"
            "    return {};\n"
            "}\n"
        )
        new = (
            "int Cache::flush_span(CIter const& begin, CIter const& end)\n"
            "{\n"
            "    // ANDROID fork: stage 2 — enqueue only; in-flight blocks stay in\n"
            "    // the cache (readable) until the write completion erases them.\n"
            "    auto err = 0;\n"
            "    for (auto span_begin = begin; span_begin < end;)\n"
            "    {\n"
            "        auto const span_end = find_span_end(span_begin, end);\n"
            "\n"
            "        if (!span_begin->in_flight)\n"
            "        {\n"
            "            if (auto const span_err = enqueue_write(span_begin, span_end); span_err != 0 && err == 0)\n"
            "            {\n"
            "                err = span_err; // first error; keep walking the rest of the range\n"
            "            }\n"
            "        }\n"
            "\n"
            "        span_begin = span_end;\n"
            "    }\n"
            "\n"
            "    return err;\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc flush_span body not found")
        text = text.replace(old, new, 1)
        # flush_file unchanged; add flush_piece
        old = (
            "int Cache::flush_file(tr_torrent const& tor, tr_file_index_t const file)\n"
            "{\n"
            "    auto const tor_id = tor.id();\n"
            "    auto const [block_begin, block_end] = tor.block_span_for_file(file);\n"
            "\n"
            "    return flush_span(\n"
            "        std::lower_bound(std::begin(blocks_), std::end(blocks_), std::make_pair(tor_id, block_begin), CompareCacheBlockByKey),\n"
            "        std::lower_bound(std::begin(blocks_), std::end(blocks_), std::make_pair(tor_id, block_end), CompareCacheBlockByKey));\n"
            "}\n"
        )
        new = (
            "int Cache::flush_file(tr_torrent const& tor, tr_file_index_t const file)\n"
            "{\n"
            "    auto const tor_id = tor.id();\n"
            "    auto const [block_begin, block_end] = tor.block_span_for_file(file);\n"
            "\n"
            "    return flush_span(\n"
            "        std::lower_bound(std::begin(blocks_), std::end(blocks_), std::make_pair(tor_id, block_begin), CompareCacheBlockByKey),\n"
            "        std::lower_bound(std::begin(blocks_), std::end(blocks_), std::make_pair(tor_id, block_end), CompareCacheBlockByKey));\n"
            "}\n"
            "\n"
            "// ANDROID fork: stage 2 — flush one piece's blocks (queued ahead of\n"
            "// the piece's post-download check).\n"
            "int Cache::flush_piece(tr_torrent const& tor, tr_piece_index_t const piece)\n"
            "{\n"
            "    auto const tor_id = tor.id();\n"
            "    auto const [block_begin, block_end] = tor.block_span_for_piece(piece);\n"
            "\n"
            "    return flush_span(\n"
            "        std::lower_bound(std::begin(blocks_), std::end(blocks_), std::make_pair(tor_id, block_begin), CompareCacheBlockByKey),\n"
            "        std::lower_bound(std::begin(blocks_), std::end(blocks_), std::make_pair(tor_id, block_end), CompareCacheBlockByKey));\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc flush_file body not found")
        text = text.replace(old, new, 1)
        # flush_biggest: enqueue only, no blocks_.erase
        old = (
            "int Cache::flush_biggest()\n"
            "{\n"
            "    auto const [begin, end] = find_biggest_span(std::begin(blocks_), std::end(blocks_));\n"
            "\n"
            "    if (begin == end) // nothing to flush\n"
            "    {\n"
            "        return 0;\n"
            "    }\n"
            "\n"
            "    if (auto const err = write_contiguous(begin, end); err != 0)\n"
            "    {\n"
            "        return err;\n"
            "    }\n"
            "\n"
            "    blocks_.erase(begin, end);\n"
            "    return 0;\n"
            "}\n"
        )
        new = (
            "int Cache::flush_biggest()\n"
            "{\n"
            "    // ANDROID fork: stage 2 — enqueue only; the write completion\n"
            "    // erases the blocks.\n"
            "    auto const [begin, end] = find_biggest_span(std::begin(blocks_), std::end(blocks_));\n"
            "\n"
            "    if (begin == end) // nothing to flush\n"
            "    {\n"
            "        return 0;\n"
            "    }\n"
            "\n"
            "    return enqueue_write(begin, end);\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc flush_biggest body not found")
        text = text.replace(old, new, 1)
        # cache_trim: the limit counts non-in-flight blocks only
        old = (
            "int Cache::cache_trim()\n"
            "{\n"
            "    while (std::size(blocks_) > max_blocks_)\n"
            "    {\n"
            "        if (auto const err = flush_biggest(); err != 0)\n"
            "        {\n"
            "            return err;\n"
            "        }\n"
            "    }\n"
            "\n"
            "    return 0;\n"
            "}\n"
        )
        new = (
            "int Cache::cache_trim()\n"
            "{\n"
            "    // ANDROID fork: stage 2 — the limit counts non-in-flight blocks\n"
            "    // only; in-flight bytes are bounded by write backpressure.\n"
            "    while (std::size(blocks_) - in_flight_count_ > max_blocks_)\n"
            "    {\n"
            "        if (auto const err = flush_biggest(); err != 0)\n"
            "        {\n"
            "            return err;\n"
            "        }\n"
            "    }\n"
            "\n"
            "    return 0;\n"
            "}\n"
            "\n"
            "// ANDROID fork: stage 2 async cache writes — the disk write completion,\n"
            "// delivered on the session thread. Erases the written blocks (only if\n"
            "// their buffer was not replaced meanwhile) and, on failure, flags and\n"
            "// stops the torrent like a synchronous write failure used to.\n"
            "void Cache::on_write_done(std::vector<std::pair<Key, std::shared_ptr<BlockData const>>> const& written, tr_disk_write_result const& result)\n"
            "{\n"
            "    auto written_bytes = size_t{};\n"
            "    for (auto const& [key, buf_ptr] : written)\n"
            "    {\n"
            "        // Re-search per entry: erasing invalidates the other iterators.\n"
            "        if (auto const [begin, end] = std::equal_range(\n"
            "                std::begin(blocks_),\n"
            "                std::end(blocks_),\n"
            "                key,\n"
            "                CompareCacheBlockByKey);\n"
            "            begin < end)\n"
            "        {\n"
            "            if (begin->buf == buf_ptr && begin->in_flight)\n"
            "            {\n"
            "                written_bytes += std::size(*begin->buf);\n"
            "                blocks_.erase(begin);\n"
            "                --in_flight_count_;\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "\n"
            "    if (result.err == 0)\n"
            "    {\n"
            "        ++disk_writes_;\n"
            "        disk_write_bytes_ += written_bytes;\n"
            "    }\n"
            "\n"
            "    if (result.err != 0 && !std::empty(written))\n"
            "    {\n"
            "        // Same handling as a synchronous write failure: flag and stop the torrent.\n"
            "        if (auto* const tor = torrents_.get(written.front().first.first); tor != nullptr &&\n"
            "            tor->error().error_type() != TR_STAT_LOCAL_ERROR)\n"
            "        {\n"
            "            tor->error().set_local_error(result.message);\n"
            "            tr_torrentStop(tor);\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc cache_trim body not found")
        text = text.replace(old, new, 1)
        with open(cache_cc2, "w", encoding="utf-8") as f:
            f.write(text)

    # 21. Patch torrent.cc: ANDROID fork: stage 2 torrent
    #     (TRANSMISSION_USB_SAF 2.4, 2.6). Flush the piece's cached blocks
    #     ahead of its post-download check (the check then runs with an
    #     empty overlay, so it reads exactly what was written), and rename
    #     completed files in a disk barrier so the rename never races
    #     queued writes.
    tor_cc2 = os.path.join(libt, "torrent.cc")
    with open(tor_cc2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 2 torrent" not in text:
        old = (
            "    pending_checks_.insert(piece);\n"
            "    session->disk_io().test_piece(\n"
        )
        new = (
            "    pending_checks_.insert(piece);\n"
            "    // ANDROID fork: stage 2 torrent\n"
            "    session->cache->flush_piece(*this, piece); // ANDROID fork: stage 2: writes queued ahead of the check\n"
            "    session->disk_io().test_piece(\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc enqueue_piece_check test_piece call not found")
        text = text.replace(old, new, 1)
        old = "        tr_disk_make_piece_overlay(*this, piece),\n"
        new = "        {}, // ANDROID fork: stage 2: empty overlay, the check reads what was written\n"
        if old not in text:
            sys.exit("ERROR: torrent.cc enqueue_piece_check piece overlay arg not found")
        text = text.replace(old, new, 1)
        old = (
            "    file_mtimes_[file] = tr_time();\n"
            "\n"
            "    /* if the torrent's current filename isn't the same as the one in the\n"
            "     * metadata -- for example, if it had the \".part\" suffix appended to\n"
            "     * it until now -- then rename it to match the one in the metadata */\n"
            "    update_file_path(file, true);\n"
        )
        new = (
            "    file_mtimes_[file] = tr_time();\n"
            "    // ANDROID fork: rename after queued writes for this file are on disk.\n"
            "    session->disk_io().barrier(\n"
            "        [session = session, tor_id = id(), file]()\n"
            "        {\n"
            "            if (auto* const tor = session->torrents().get(tor_id); tor != nullptr)\n"
            "            {\n"
            "                tor->update_file_path(file, true);\n"
            "            }\n"
            "        });\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc on_file_completed update_file_path not found")
        text = text.replace(old, new, 1)
        with open(tor_cc2, "w", encoding="utf-8") as f:
            f.write(text)

    # 22. Patch peer-msgs.cc + webseed.cc: ANDROID fork: TRANSMISSION_USB_SAF
    #     stage 2 write backpressure (TRANSMISSION_USB_SAF 2.5). While too
    #     many bytes are queued for the disk worker, peers and webseeds
    #     request no new blocks.
    pm2 = os.path.join(libt, "peer-msgs.cc")
    with open(pm2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: TRANSMISSION_USB_SAF stage 2 write backpressure" not in text:
        old = (
            "void tr_peerMsgsImpl::maybe_send_block_requests()\n"
            "{\n"
            "    if (!tor_.client_can_download())\n"
            "    {\n"
            "        return;\n"
            "    }\n"
        )
        new = (
            "void tr_peerMsgsImpl::maybe_send_block_requests()\n"
            "{\n"
            "    if (!tor_.client_can_download())\n"
            "    {\n"
            "        return;\n"
            "    }\n"
            "\n"
            "    // ANDROID fork: TRANSMISSION_USB_SAF stage 2 write backpressure.\n"
            "    if (session->disk_io().writes_backlogged())\n"
            "    {\n"
            "        return;\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc maybe_send_block_requests client_can_download block not found")
        text = text.replace(old, new, 1)
        with open(pm2, "w", encoding="utf-8") as f:
            f.write(text)

    ws = os.path.join(libt, "webseed.cc")
    with open(ws, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: TRANSMISSION_USB_SAF stage 2 write backpressure" not in text:
        old = (
            "    void on_idle()\n"
            "    {\n"
            "        if (is_banned_)\n"
            "        {\n"
            "            return;\n"
            "        }\n"
        )
        new = (
            "    void on_idle()\n"
            "    {\n"
            "        if (is_banned_)\n"
            "        {\n"
            "            return;\n"
            "        }\n"
            "\n"
            "        // ANDROID fork: TRANSMISSION_USB_SAF stage 2 write backpressure.\n"
            "        if (tor.session->disk_io().writes_backlogged()) // no session_ member in tr_webseed_impl\n"
            "        {\n"
            "            return;\n"
            "        }\n"
        )
        if old not in text:
            sys.exit("ERROR: webseed.cc on_idle is_banned_ block not found")
        text = text.replace(old, new, 1)
        with open(ws, "w", encoding="utf-8") as f:
            f.write(text)

    # 23. Patch session.cc: ANDROID fork: stage 2 session
    #     (TRANSMISSION_USB_SAF 2.7, 2.8). close_torrent_files/close_torrent_file
    #     flush through the cache (enqueue only), close the worker fds, and
    #     temporarily wait for the queued writes; closeImplPart1 drains the
    #     worker after the torrents are freed and before the cache goes away.
    ses_cc2 = os.path.join(libt, "session.cc")
    with open(ses_cc2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 2 session" not in text:
        old = (
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"flush\", \"\", static_cast<uint64_t>(tor_id) };\n"
            "    this->cache->flush_torrent(tor_id);\n"
        )
        new = (
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"flush\", \"\", static_cast<uint64_t>(tor_id) };\n"
            "    this->cache->flush_torrent(tor_id); // enqueue only\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc flush_torrent_files body not found")
        text = text.replace(old, new, 1)
        old = (
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"close\", \"\", static_cast<uint64_t>(tor_id) };\n"
            "    this->cache->flush_torrent(tor_id);\n"
            "    openFiles().close_torrent(tor_id);\n"
            "    if (disk_io_) disk_io_->close_torrent(tor_id); // ANDROID fork: stage 1.5 session wiring\n"
        )
        new = (
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"close\", \"\", static_cast<uint64_t>(tor_id) };\n"
            "    this->cache->flush_torrent(tor_id);\n"
            "    if (disk_io_)\n"
            "    {\n"
            "        disk_io_->close_torrent(tor_id);\n"
            "        // ANDROID fork: TEMPORARY synchronous wait so remove/relocate never race queued\n"
            "        // writes. Removed in TRANSMISSION_USB_SAF stage 4.\n"
            "        auto const timeout = isClosing() ? std::chrono::milliseconds{ 20000 } : std::chrono::milliseconds{ 3600000 };\n"
            "        if (!disk_io_->wait_idle(timeout))\n"
            "        {\n"
            "            tr_logAddWarn(\"Timed out waiting for queued disk writes\");\n"
            "        }\n"
            "    }\n"
            "    openFiles().close_torrent(tor_id);\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc close_torrent_files body not found")
        text = text.replace(old, new, 1)
        old = (
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"closefile\", \"\", file_num };\n"
            "    this->cache->flush_file(tor, file_num);\n"
            "    openFiles().close_file(tor.id(), file_num);\n"
            "    if (disk_io_) disk_io_->close_file(tor.id(), file_num); // ANDROID fork: stage 1.5 session wiring\n"
        )
        new = (
            "    // ANDROID fork: stage 0 timing.\n"
            "    tr_disk_timer const disk_timer{ \"closefile\", \"\", file_num };\n"
            "    this->cache->flush_file(tor, file_num);\n"
            "    if (disk_io_)\n"
            "    {\n"
            "        disk_io_->close_file(tor.id(), file_num);\n"
            "    }\n"
            "    openFiles().close_file(tor.id(), file_num);\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc close_torrent_file body not found")
        text = text.replace(old, new, 1)
        old = (
            "    verifier_.reset();\n"
            "    disk_io_->shutdown(); disk_io_.reset(); // ANDROID fork: stage 1.5 session wiring\n"
            "    save_timer_.reset();\n"
        )
        new = (
            "    verifier_.reset();\n"
            "    save_timer_.reset();\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc closeImplPart1 stage 1 disk_io shutdown line not found")
        text = text.replace(old, new, 1)
        old = (
            "    torrents.clear();\n"
            "    // ...now that all the torrents have been closed, any remaining\n"
        )
        new = (
            "    torrents.clear();\n"
            "    // ANDROID fork: stage 2 session\n"
            "    // ANDROID fork: flush queued writes before the cache goes away.\n"
            "    if (disk_io_)\n"
            "    {\n"
            "        disk_io_->shutdown_drain(std::min(deadline, std::chrono::steady_clock::now() + std::chrono::seconds{ 20 }));\n"
            "        disk_io_.reset();\n"
            "    }\n"
            "    // ...now that all the torrents have been closed, any remaining\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc closeImplPart1 torrents.clear() not found")
        text = text.replace(old, new, 1)
        with open(ses_cc2, "w", encoding="utf-8") as f:
            f.write(text)

    # 24. Patch cache.h + cache.cc: ANDROID fork: stage 3 read_block_if_cached
    #     (TRANSMISSION_USB_SAF 3.2). True and fills `setme` only if the
    #     block is in the cache; never reads disk. The existing read_block
    #     (with its disk fallback) is unchanged.
    cache_h3 = os.path.join(libt, "cache.h")
    with open(cache_h3, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 3 read_block_if_cached" not in text:
        old = (
            "    int read_block(tr_torrent const& tor, tr_block_info::Location const& loc, size_t len, uint8_t* setme);\n"
        )
        new = (
            "    int read_block(tr_torrent const& tor, tr_block_info::Location const& loc, size_t len, uint8_t* setme);\n"
            "\n"
            "    // ANDROID fork: stage 3 read_block_if_cached — true and fills `setme` only if\n"
            "    // the block is in the cache; never reads disk.\n"
            "    [[nodiscard]] bool read_block_if_cached(tr_torrent const& tor, tr_block_info::Location const& loc, size_t len, uint8_t* setme);\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.h read_block declaration not found")
        text = text.replace(old, new, 1)
        with open(cache_h3, "w", encoding="utf-8") as f:
            f.write(text)

    cache_cc3 = os.path.join(libt, "cache.cc")
    with open(cache_cc3, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 3 read_block_if_cached" not in text:
        old = (
            "int Cache::read_block(tr_torrent const& tor, tr_block_info::Location const& loc, size_t len, uint8_t* setme)\n"
            "{\n"
            "    if (auto const iter = get_block(tor, loc); iter != std::end(blocks_))\n"
            "    {\n"
            "        std::copy_n(std::begin(*iter->buf), len, setme);\n"
            "        return {};\n"
            "    }\n"
            "\n"
            "    return tr_ioRead(tor, loc, len, setme);\n"
            "}\n"
        )
        new = (
            old
            + "\n"
            + "// ANDROID fork: stage 3 read_block_if_cached (TRANSMISSION_USB_SAF 3.2) —\n"
            + "// never touches the disk.\n"
            + "bool Cache::read_block_if_cached(tr_torrent const& tor, tr_block_info::Location const& loc, size_t len, uint8_t* setme)\n"
            + "{\n"
            + "    if (auto const iter = get_block(tor, loc); iter != std::end(blocks_))\n"
            + "    {\n"
            + "        if (loc.block_offset + len <= std::size(*iter->buf))\n"
            + "        {\n"
            + "            std::copy_n(std::begin(*iter->buf) + loc.block_offset, len, setme);\n"
            + "            return true;\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "    return false;\n"
            + "}\n"
        )
        if old not in text:
            sys.exit("ERROR: cache.cc read_block body not found")
        text = text.replace(old, new, 1)
        with open(cache_cc3, "w", encoding="utf-8") as f:
            f.write(text)

    # 25. Patch torrent.h + torrent.cc: ANDROID fork: stage 3 lazy check
    #     (TRANSMISSION_USB_SAF 3.3). Upload requests for unchecked pieces
    #     start a lazy test_piece job (High queue, with the cache overlay);
    #     the session-thread completion applies what the synchronous
    #     ensure_piece_is_checked used to do after its check_piece call.
    tor_h2 = os.path.join(libt, "torrent.h")
    with open(tor_h2, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 3 lazy check" not in text:
        old = (
            "    void clear_pending_checks() noexcept { pending_checks_.clear(); }\n"
        )
        new = (
            "    void clear_pending_checks() noexcept { pending_checks_.clear(); }\n"
            "    void clear_lazy_checks() noexcept { lazy_checks_.clear(); } // ANDROID fork: stage 3 lazy check\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h clear_pending_checks declaration not found")
        text = text.replace(old, new, 1)
        old = (
            "    [[nodiscard]] constexpr bool is_unhaving_for_read_error() const noexcept { return unhaving_for_read_error_; }\n"
            "\n"
            "private:\n"
        )
        new = (
            "    [[nodiscard]] constexpr bool is_unhaving_for_read_error() const noexcept { return unhaving_for_read_error_; }\n"
            "\n"
            "    // ANDROID fork: stage 3 lazy check (TRANSMISSION_USB_SAF).\n"
            "    void request_lazy_check(tr_piece_index_t piece);\n"
            "\n"
            "private:\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h is_unhaving_for_read_error/private section not found")
        text = text.replace(old, new, 1)
        old = (
            "    std::set<tr_piece_index_t> pending_checks_; // ANDROID fork\n"
            "    bool unhaving_for_read_error_ = false; // ANDROID fork\n"
        )
        new = (
            "    std::set<tr_piece_index_t> pending_checks_; // ANDROID fork\n"
            "    bool unhaving_for_read_error_ = false; // ANDROID fork\n"
            "    // ANDROID fork: stage 3 lazy check (TRANSMISSION_USB_SAF)\n"
            "    void on_lazy_check_done(tr_piece_index_t piece, int result);\n"
            "    std::set<tr_piece_index_t> lazy_checks_;\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h pending_checks_ member not found")
        text = text.replace(old, new, 1)
        with open(tor_h2, "w", encoding="utf-8") as f:
            f.write(text)

    tor_cc3 = os.path.join(libt, "torrent.cc")
    with open(tor_cc3, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 3 lazy check" not in text:
        old = (
            "            session->verify_remove(tor);\n"
            "            tor->clear_pending_checks(); // ANDROID fork: a full verify recomputes every piece\n"
        )
        new = (
            "            session->verify_remove(tor);\n"
            "            tor->clear_pending_checks(); // ANDROID fork: a full verify recomputes every piece\n"
            "            tor->clear_lazy_checks(); // ANDROID fork: stage 3 lazy check\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc tr_torrentVerify clear_pending_checks not found")
        text = text.replace(old, new, 1)
        old = (
            "    unhaving_for_read_error_ = true;\n"
            "    got_bad_piece_.emit(this, piece); // ANDROID fork: re-add to the wishlist; peer-mgr skips blame\n"
            "    unhaving_for_read_error_ = false;\n"
            "}\n"
        )
        new = (
            "    unhaving_for_read_error_ = true;\n"
            "    got_bad_piece_.emit(this, piece); // ANDROID fork: re-add to the wishlist; peer-mgr skips blame\n"
            "    unhaving_for_read_error_ = false;\n"
            "}\n"
            "\n"
            "// ANDROID fork: stage 3 lazy check (TRANSMISSION_USB_SAF 3.3).\n"
            "void tr_torrent::request_lazy_check(tr_piece_index_t const piece)\n"
            "{\n"
            "    if (is_piece_checked(piece) || !lazy_checks_.insert(piece).second)\n"
            "    {\n"
            "        return;\n"
            "    }\n"
            "    session->disk_io().test_piece(\n"
            "        tr_disk_queue::Prio::High,\n"
            "        tr_disk_make_segments(*this, piece_loc(piece), piece_size(piece), false),\n"
            "        tr_disk_make_piece_overlay(*this, piece),\n"
            "        piece_hash(piece),\n"
            "        [session = session, tor_id = id(), piece](int result)\n"
            "        {\n"
            "            if (auto* const tor = session->torrents().get(tor_id); tor != nullptr)\n"
            "            {\n"
            "                tor->on_lazy_check_done(piece, result);\n"
            "            }\n"
            "        });\n"
            "}\n"
            "\n"
            "void tr_torrent::on_lazy_check_done(tr_piece_index_t const piece, int const result)\n"
            "{\n"
            "    if (lazy_checks_.erase(piece) == 0)\n"
            "    {\n"
            "        return; // stale result (e.g. a full verify cleared the set)\n"
            "    }\n"
            "\n"
            "    mark_changed();\n"
            "    set_dirty();\n"
            "\n"
            "    if (result == -1)\n"
            "    {\n"
            "        return; // read error (transient); leave it unchecked, it is retried on the next request\n"
            "    }\n"
            "\n"
            "    checked_pieces_.set(piece, result == 1);\n"
            "    if (result == 0)\n"
            "    {\n"
            '        error().set_local_error(fmt::format("Please Verify Local Data! Piece #{:d} is corrupt.", piece));\n'
            "    }\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc on_piece_read_error body not found")
        text = text.replace(old, new, 1)
        with open(tor_cc3, "w", encoding="utf-8") as f:
            f.write(text)

    # 26. Patch peer-msgs.cc: ANDROID fork: TRANSMISSION_USB_SAF stage 3
    #     async reads (TRANSMISSION_USB_SAF 3.4). Serving a peer's block
    #     request never reads the disk on the session thread: cache hits are
    #     sent synchronously, misses become tr_disk_io::read jobs (High
    #     queue) and the Piece is sent from the session-thread completion.
    #     Unchecked pieces get a lazy test_piece and are rejected once.
    pm3 = os.path.join(libt, "peer-msgs.cc")
    with open(pm3, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads" not in text:
        # 26a. Members next to peer_requested_ (disk-io.h arrives via session.h).
        old = (
            "    std::deque<peer_request> peer_requested_;\n"
            "\n"
            "    std::array<std::vector<tr_pex>, NUM_TR_AF_INET_TYPES> pex_;\n"
        )
        new = (
            "    std::deque<peer_request> peer_requested_;\n"
            "\n"
            "    // ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads.\n"
            "    std::vector<peer_request> reads_pending_;\n"
            "    static constexpr size_t MaxReadsPerPeer = 4U;\n"
            "\n"
            "    std::array<std::vector<tr_pex>, NUM_TR_AF_INET_TYPES> pex_;\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc peer_requested_ member not found")
        text = text.replace(old, new, 1)
        # 26b. on_block_read declaration next to add_next_block.
        old = (
            "    [[nodiscard]] size_t add_next_block(time_t now_sec, uint64_t now_msec);\n"
        )
        new = (
            "    [[nodiscard]] size_t add_next_block(time_t now_sec, uint64_t now_msec);\n"
            "    void on_block_read(peer_request const& req, tr_disk_read_result r); // ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc add_next_block declaration not found")
        text = text.replace(old, new, 1)
        # 26c. reject_all_requests: reject + clear the in-flight reads too.
        old = (
            "    void reject_all_requests()\n"
            "    {\n"
            "        auto& queue = peer_requested_;\n"
            "\n"
            "        if (auto const must_send_rej = io_->supports_fext(); must_send_rej)\n"
            "        {\n"
            "            std::for_each(std::begin(queue), std::end(queue), [this](peer_request const& req) { protocol_send_reject(req); });\n"
            "        }\n"
            "\n"
            "        queue.clear();\n"
            "    }\n"
        )
        new = (
            "    void reject_all_requests()\n"
            "    {\n"
            "        auto& queue = peer_requested_;\n"
            "\n"
            "        if (auto const must_send_rej = io_->supports_fext(); must_send_rej)\n"
            "        {\n"
            "            std::for_each(std::begin(queue), std::end(queue), [this](peer_request const& req) { protocol_send_reject(req); });\n"
            "            std::for_each(std::begin(reads_pending_), std::end(reads_pending_), [this](peer_request const& req) { protocol_send_reject(req); }); // ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads\n"
            "        }\n"
            "\n"
            "        queue.clear();\n"
            "        reads_pending_.clear(); // ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc reject_all_requests body not found")
        text = text.replace(old, new, 1)
        # 26d. Cancel handler: also erase from reads_pending_ (BEP 6 reject).
        old = (
            "            auto& requests = peer_requested_;\n"
            "            if (auto iter = std::find(std::begin(requests), std::end(requests), r); iter != std::end(requests))\n"
            "            {\n"
            "                requests.erase(iter);\n"
            "\n"
            "                // bep6: \"Even when a request is cancelled, the peer\n"
            "                // receiving the cancel should respond with either the\n"
            "                // corresponding reject or the corresponding piece\"\n"
            "                if (fext)\n"
            "                {\n"
            "                    protocol_send_reject(r);\n"
            "                }\n"
            "            }\n"
        )
        new = (
            "            auto& requests = peer_requested_;\n"
            "            if (auto iter = std::find(std::begin(requests), std::end(requests), r); iter != std::end(requests))\n"
            "            {\n"
            "                requests.erase(iter);\n"
            "\n"
            "                // bep6: \"Even when a request is cancelled, the peer\n"
            "                // receiving the cancel should respond with either the\n"
            "                // corresponding reject or the corresponding piece\"\n"
            "                if (fext)\n"
            "                {\n"
            "                    protocol_send_reject(r);\n"
            "                }\n"
            "            }\n"
            "            else if (auto const read_iter = std::find(std::begin(reads_pending_), std::end(reads_pending_), r); read_iter != std::end(reads_pending_))\n"
            "            {\n"
            "                // ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads — the request is in\n"
            "                // flight on the disk worker; erase it (its completion is dropped) and\n"
            "                // reply with a reject like bep6 requires.\n"
            "                reads_pending_.erase(read_iter);\n"
            "                if (fext)\n"
            "                {\n"
            "                    protocol_send_reject(r);\n"
            "                }\n"
            "            }\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc Cancel handler not found")
        text = text.replace(old, new, 1)
        # 26e. add_next_block body: async read path + on_block_read definition.
        #     Replaces the synchronous ensure_piece_is_checked / cache->read_block
        #     path (stage 1.8 masking kept in the ok= condition).
        old = (
            "    auto const req = peer_requested_.front();\n"
            "    peer_requested_.pop_front();\n"
            "\n"
            "    auto buf = std::array<uint8_t, tr_block_info::BlockSize>{};\n"
            "    auto ok = is_valid_request(req) && tor_.has_piece(req.index) && !tor_.is_piece_check_pending(req.index); // ANDROID fork: stage 1.8 peer-facing masking\n"
            "\n"
            "    if (ok)\n"
            "    {\n"
            "        // ANDROID fork: tri-state. -1 = read error (transient): reject this\n"
            "        // request without flagging corruption. 0 = genuine corruption.\n"
            "        auto const checked = tor_.ensure_piece_is_checked(req.index);\n"
            "        ok = (checked == 1);\n"
            "\n"
            "        if (checked == 0)\n"
            "        {\n"
            '            tor_.error().set_local_error(fmt::format("Please Verify Local Data! Piece #{:d} is corrupt.", req.index));\n'
            "        }\n"
            "    }\n"
            "\n"
            "    if (ok)\n"
            "    {\n"
            "        ok = session->cache->read_block(tor_, tor_.piece_loc(req.index, req.offset), req.length, std::data(buf)) == 0;\n"
            "    }\n"
            "\n"
            "    if (ok)\n"
            "    {\n"
            "        blocks_sent_to_peer.add(now_sec, 1);\n"
            "        auto const piece_data = std::string_view{ reinterpret_cast<char const*>(std::data(buf)), req.length };\n"
            "        return protocol_send_message(BtPeerMsgs::Piece, req.index, req.offset, piece_data);\n"
            "    }\n"
            "\n"
            "    if (io_->supports_fext())\n"
            "    {\n"
            "        return protocol_send_reject(req);\n"
            "    }\n"
            "\n"
            "    return {};\n"
            "}\n"
        )
        new = (
            "    // ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads.\n"
            "    auto const req = peer_requested_.front();\n"
            "    peer_requested_.pop_front();\n"
            "\n"
            "    auto ok = is_valid_request(req) && tor_.has_piece(req.index) && !tor_.is_piece_check_pending(req.index);\n"
            "\n"
            "    if (ok && !tor_.is_piece_checked(req.index))\n"
            "    {\n"
            "        // Unchecked piece: start a lazy check on the disk worker (High queue,\n"
            "        // with the cache overlay) and reject/drop this request; the peer\n"
            "        // will re-request once the piece is checked.\n"
            "        tor_.request_lazy_check(req.index);\n"
            "        ok = false;\n"
            "    }\n"
            "\n"
            "    if (ok)\n"
            "    {\n"
            "        auto const loc = tor_.piece_loc(req.index, req.offset);\n"
            "        auto buf = std::array<uint8_t, tr_block_info::BlockSize>{};\n"
            "        if (session->cache->read_block_if_cached(tor_, loc, req.length, std::data(buf)))\n"
            "        {\n"
            "            blocks_sent_to_peer.add(now_sec, 1);\n"
            "            auto const piece_data = std::string_view{ reinterpret_cast<char const*>(std::data(buf)), req.length };\n"
            "            return protocol_send_message(BtPeerMsgs::Piece, req.index, req.offset, piece_data);\n"
            "        }\n"
            "\n"
            "        if (std::size(reads_pending_) >= MaxReadsPerPeer || !session->disk_io().can_start_read())\n"
            "        {\n"
            "            // Cap hit: put the request back at the front and stop filling; the\n"
            "            // next pulse()/fill_output_buffer retries. Returning 0 stops the\n"
            "            // fill_output_buffer_impl loop even with requests still queued.\n"
            "            peer_requested_.push_front(req);\n"
            "            return {};\n"
            "        }\n"
            "\n"
            "        reads_pending_.push_back(req);\n"
            "        session->disk_io().read(\n"
            "            tr_disk_make_segments(tor_, loc, req.length, false),\n"
            "            [weak = weak_from_this(), req](tr_disk_read_result r)\n"
            "            {\n"
            "                if (auto self = weak.lock())\n"
            "                {\n"
            "                    self->on_block_read(req, std::move(r));\n"
            "                }\n"
            "            });\n"
            "        return {};\n"
            "    }\n"
            "\n"
            "    if (io_->supports_fext())\n"
            "    {\n"
            "        return protocol_send_reject(req);\n"
            "    }\n"
            "\n"
            "    return {};\n"
            "}\n"
            "\n"
            "// ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads. The disk read\n"
            "// completion, delivered on the session thread.\n"
            "void tr_peerMsgsImpl::on_block_read(peer_request const& req, tr_disk_read_result r)\n"
            "{\n"
            "    auto iter = std::find(std::begin(reads_pending_), std::end(reads_pending_), req);\n"
            "    if (iter == std::end(reads_pending_))\n"
            "    {\n"
            "        return; // cancelled or cleared meanwhile\n"
            "    }\n"
            "    reads_pending_.erase(iter);\n"
            "\n"
            "    if (r.err != 0 || std::size(r.data) != static_cast<size_t>(req.length))\n"
            "    {\n"
            "        // Read errors are transient: reject if fext, do not flag the torrent\n"
            "        // (matches the tri-state fix).\n"
            "        if (io_->supports_fext())\n"
            "        {\n"
            "            protocol_send_reject(req);\n"
            "        }\n"
            "    }\n"
            "    else\n"
            "    {\n"
            "        blocks_sent_to_peer.add(tr_time(), 1);\n"
            "        auto const piece_data = std::string_view{ reinterpret_cast<char const*>(std::data(r.data)), req.length };\n"
            "        protocol_send_message(BtPeerMsgs::Piece, req.index, req.offset, piece_data);\n"
            "    }\n"
            "\n"
            "    // Serve the next queued requests without waiting for the next pulse\n"
            "    // (completions are always posted to the session thread, no re-entrancy).\n"
            "    fill_output_buffer(tr_time(), tr_time_msec());\n"
            "}\n"
        )
        if old not in text:
            sys.exit("ERROR: peer-msgs.cc add_next_block body not found")
        text = text.replace(old, new, 1)
        with open(pm3, "w", encoding="utf-8") as f:
            f.write(text)
        # 26f. torrent.h: move is_piece_checked to the public section — the peer
        # upload path (add_next_block) needs it to choose between a lazy check
        # and a read. The private inline copy is removed.
        # 4.1.4 upstream already declares it publicly right after has_piece, so
        # both halves are conditional: insert only when the public slot is empty
        # and drop the private copy only when this tree still has one.
        tor_h3 = os.path.join(libt, "torrent.h")
        with open(tor_h3, "r", encoding="utf-8") as f:
            text = f.read()
        old = (
            "    [[nodiscard]] auto has_piece(tr_piece_index_t piece) const\n"
            "    {\n"
            "        return completion_.has_piece(piece);\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h has_piece body not found")
        # Everything between has_piece and has_block is the public slot.
        public_slot = text.split(old, 1)[1].split("has_block", 1)[0]
        if "is_piece_checked" not in public_slot:
            new = (
                "    [[nodiscard]] auto has_piece(tr_piece_index_t piece) const\n"
                "    {\n"
                "        return completion_.has_piece(piece);\n"
                "    }\n"
                "\n"
                "    // ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads — the peer upload\n"
                "    // path needs this to choose between a lazy check and a read.\n"
                "    [[nodiscard]] TR_CONSTEXPR20 bool is_piece_checked(tr_piece_index_t piece) const\n"
                "    {\n"
                "        return checked_pieces_.test(piece);\n"
                "    }\n"
            )
            text = text.replace(old, new, 1)
        old = (
            "    [[nodiscard]] TR_CONSTEXPR20 bool is_piece_checked(tr_piece_index_t piece) const\n"
            "    {\n"
            "        return checked_pieces_.test(piece);\n"
            "    }\n"
            "\n"
            "    // ANDROID fork: tri-state: 1=good, 0=corrupt, -1=read error.\n"
            "    [[nodiscard]] int check_piece(tr_piece_index_t piece) const;\n"
        )
        new = (
            "    // ANDROID fork: tri-state: 1=good, 0=corrupt, -1=read error.\n"
            "    [[nodiscard]] int check_piece(tr_piece_index_t piece) const;\n"
        )
        if old in text:
            text = text.replace(old, new, 1)
        with open(tor_h3, "w", encoding="utf-8") as f:
            f.write(text)

    # 27. Patch session.cc: ANDROID fork: stage 4 (TRANSMISSION_USB_SAF 4.3).
    #     close_torrent_files no longer waits for the queued writes: the flush
    #     and the worker close are only enqueued. The Low-queue remove/relocate
    #     jobs (stage 4) run after them (FIFO), so they never race the writes.
    ses_cc3 = os.path.join(libt, "session.cc")
    with open(ses_cc3, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 4 no wait" not in text:
        old = (
            "    this->cache->flush_torrent(tor_id);\n"
            "    if (disk_io_)\n"
            "    {\n"
            "        disk_io_->close_torrent(tor_id);\n"
            "        // ANDROID fork: TEMPORARY synchronous wait so remove/relocate never race queued\n"
            "        // writes. Removed in TRANSMISSION_USB_SAF stage 4.\n"
            "        auto const timeout = isClosing() ? std::chrono::milliseconds{ 20000 } : std::chrono::milliseconds{ 3600000 };\n"
            "        if (!disk_io_->wait_idle(timeout))\n"
            "        {\n"
            "            tr_logAddWarn(\"Timed out waiting for queued disk writes\");\n"
            "        }\n"
            "    }\n"
            "    openFiles().close_torrent(tor_id);\n"
        )
        new = (
            "    this->cache->flush_torrent(tor_id);\n"
            "    if (disk_io_)\n"
            "    {\n"
            "        disk_io_->close_torrent(tor_id);\n"
            "        // ANDROID fork: stage 4 no wait\n"
            "    }\n"
            "    openFiles().close_torrent(tor_id);\n"
        )
        if old not in text:
            sys.exit("ERROR: session.cc close_torrent_files stage 2 wait block not found")
        text = text.replace(old, new, 1)
        with open(ses_cc3, "w", encoding="utf-8") as f:
            f.write(text)

    # 28. Patch torrent.h + torrent.cc: ANDROID fork: stage 4 two-phase
    #     remove and relocate (TRANSMISSION_USB_SAF 4.4, 4.5). Removing with
    #     data and relocating with a move no longer block the session thread:
    #     phase 1 stops/closes the torrent (enqueued) and enqueues a Low job
    #     for the file deletion/move; phase 2 (the job's completion, on the
    #     session thread) frees the torrent / applies the new location and
    #     restarts it. Between the phases the torrent is flagged so it can't
    #     be started, removed again or relocated.
    tor_h4 = os.path.join(libt, "torrent.h")
    with open(tor_h4, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 4 two-phase remove/relocate" not in text:
        # 28a. Public getters before the private section.
        old = (
            "    // ANDROID fork: stage 3 lazy check (TRANSMISSION_USB_SAF).\n"
            "    void request_lazy_check(tr_piece_index_t piece);\n"
            "\n"
            "private:\n"
        )
        new = (
            "    // ANDROID fork: stage 3 lazy check (TRANSMISSION_USB_SAF).\n"
            "    void request_lazy_check(tr_piece_index_t piece);\n"
            "\n"
            "    // ANDROID fork: stage 4 two-phase remove/relocate (TRANSMISSION_USB_SAF).\n"
            "    [[nodiscard]] constexpr auto is_remove_pending() const noexcept { return remove_pending_; }\n"
            "    [[nodiscard]] constexpr auto is_relocate_pending() const noexcept { return relocate_pending_; }\n"
            "\n"
            "private:\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h request_lazy_check/private section not found")
        text = text.replace(old, new, 1)
        # 28b. Members next to is_deleting_.
        old = "    bool is_deleting_ = false;\n"
        new = (
            "    bool is_deleting_ = false;\n"
            "    bool remove_pending_ = false; // ANDROID fork\n"
            "    bool relocate_pending_ = false; // ANDROID fork\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h is_deleting_ member not found")
        text = text.replace(old, new, 1)
        # 28c. finish_set_location declaration next to set_location_in_session_thread.
        old = "    void set_location_in_session_thread(std::string_view path, bool move_from_old_path, int volatile* setme_state);\n"
        new = (
            "    void set_location_in_session_thread(std::string_view path, bool move_from_old_path, int volatile* setme_state);\n"
            "    void finish_set_location(std::string_view path, bool move_from_old_path, int err, std::string const& msg, int volatile* setme_state); // ANDROID fork\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.h set_location_in_session_thread declaration not found")
        text = text.replace(old, new, 1)
        with open(tor_h4, "w", encoding="utf-8") as f:
            f.write(text)

    tor_cc4 = os.path.join(libt, "torrent.cc")
    with open(tor_cc4, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: stage 4 two-phase" not in text:
        # 28d. tr_torrent::start: don't start while a remove/relocate job is in flight.
        old = (
            "void tr_torrent::start(bool bypass_queue, std::optional<bool> has_any_local_data)\n"
            "{\n"
            "    using namespace start_stop_helpers;\n"
            "\n"
            "    auto const lock = unique_lock();\n"
            "\n"
            "    switch (activity())\n"
        )
        new = (
            "void tr_torrent::start(bool bypass_queue, std::optional<bool> has_any_local_data)\n"
            "{\n"
            "    using namespace start_stop_helpers;\n"
            "\n"
            "    auto const lock = unique_lock();\n"
            "\n"
            "    // ANDROID fork: stage 4 - a remove/relocate job is in flight.\n"
            "    if (is_remove_pending() || is_relocate_pending())\n"
            "    {\n"
            "        return;\n"
            "    }\n"
            "\n"
            "    switch (activity())\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc tr_torrent::start body not found")
        text = text.replace(old, new, 1)
        # 28e. start_in_session_thread: the same guard, after the lock.
        old = (
            "void tr_torrent::start_in_session_thread()\n"
            "{\n"
            "    using namespace start_stop_helpers;\n"
            "\n"
            "    TR_ASSERT(session->am_in_session_thread());\n"
            "    auto const lock = unique_lock();\n"
            "\n"
            "    // We are after `torrentStart` and before announcing to trackers/peers,\n"
        )
        new = (
            "void tr_torrent::start_in_session_thread()\n"
            "{\n"
            "    using namespace start_stop_helpers;\n"
            "\n"
            "    TR_ASSERT(session->am_in_session_thread());\n"
            "    auto const lock = unique_lock();\n"
            "\n"
            "    // ANDROID fork: stage 4 - a remove/relocate job is in flight.\n"
            "    if (is_remove_pending() || is_relocate_pending())\n"
            "    {\n"
            "        return;\n"
            "    }\n"
            "\n"
            "    // We are after `torrentStart` and before announcing to trackers/peers,\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc start_in_session_thread body not found")
        text = text.replace(old, new, 1)
        # 28f. tr_torrentRemoveInSessionThread: two-phase remove.
        old = (
            "void tr_torrentRemoveInSessionThread(tr_torrent* tor, bool delete_flag, tr_fileFunc delete_func, void* delete_user_data)\n"
            "{\n"
            "    auto const lock = tor->unique_lock();\n"
            "\n"
            "    if (delete_flag && tor->has_metainfo())\n"
            "    {\n"
            "        // ensure the files are all closed and idle before moving\n"
            "        tor->session->close_torrent_files(tor->id());\n"
            "        tor->session->verify_remove(tor);\n"
            "\n"
            "        if (delete_func == nullptr)\n"
            "        {\n"
            "            delete_func = start_stop_helpers::removeTorrentFile;\n"
            "        }\n"
            "\n"
            "        auto const delete_func_wrapper = [&delete_func, delete_user_data](char const* filename)\n"
            "        {\n"
            "            delete_func(filename, delete_user_data, nullptr);\n"
            "        };\n"
            "\n"
            "        auto error = tr_error{};\n"
            "        tor->files().remove(tor->current_dir(), tor->name(), delete_func_wrapper, &error);\n"
            "        if (error)\n"
            "        {\n"
            "            tr_logAddWarnTor(\n"
            "                tor,\n"
            "                fmt::format(\n"
            "                    fmt::runtime(_(\"Couldn't remove all torrent files: {error} ({error_code})\")),\n"
            "                    fmt::arg(\"error\", error.message()),\n"
            "                    fmt::arg(\"error_code\", error.code())));\n"
            "        }\n"
            "    }\n"
            "\n"
            "    tr_torrentFreeInSessionThread(tor);\n"
            "}"
        )
        new = (
            "void tr_torrentRemoveInSessionThread(tr_torrent* tor, bool delete_flag, tr_fileFunc delete_func, void* delete_user_data)\n"
            "{\n"
            "    auto const lock = tor->unique_lock();\n"
            "\n"
            "    if (tor->is_remove_pending())\n"
            "    {\n"
            "        return; // ANDROID fork: stage 4 - a remove job is already in flight\n"
            "    }\n"
            "\n"
            "    if (tor->is_relocate_pending())\n"
            "    {\n"
            "        // ANDROID fork: stage 4 - remove requested while a move job is queued;\n"
            "        // don't drop it (tr_torrentRemove already set is_deleting_). Enqueue a\n"
            "        // no-op Low job: the Low queue is FIFO and completions post to the\n"
            "        // session thread in order, so its completion runs after the move job's\n"
            "        // completion has cleared relocate_pending_ and updated current_dir_.\n"
            "        auto* const sess = tor->session;\n"
            "        auto const tor_id = tor->id();\n"
            "        sess->disk_io().run_low(\n"
            "            []() -> std::pair<int, std::string>\n"
            "            { return { 0, {} }; },\n"
            "            [sess, tor_id, delete_flag, delete_func, delete_user_data](int, std::string)\n"
            "            {\n"
            "                if (auto* const t = sess->torrents().get(tor_id); t != nullptr)\n"
            "                {\n"
            "                    tr_torrentRemoveInSessionThread(t, delete_flag, delete_func, delete_user_data);\n"
            "                }\n"
            "            });\n"
            "        return;\n"
            "    }\n"
            "\n"
            "    if (delete_flag && tor->has_metainfo())\n"
            "    {\n"
            "        // ANDROID fork: stage 4 two-phase remove (TRANSMISSION_USB_SAF 4.4).\n"
            "        // Phase 1: flag the torrent, stop it (or close its files) so the flush\n"
            "        // and close are enqueued, then delete the files on the disk worker\n"
            "        // (Low queue: after every queued write). Phase 2 (the completion, on\n"
            "        // the session thread) frees the torrent.\n"
            "        tor->remove_pending_ = true;\n"
            "        tor->session->verify_remove(tor);\n"
            "\n"
            "        if (tor->is_running())\n"
            "        {\n"
            "            tor->stop_now();\n"
            "        }\n"
            "        else\n"
            "        {\n"
            "            tor->session->close_torrent_files(tor->id());\n"
            "        }\n"
            "\n"
            "        if (delete_func == nullptr)\n"
            "        {\n"
            "            delete_func = start_stop_helpers::removeTorrentFile;\n"
            "        }\n"
            "\n"
            "        // Copies for the worker (it must not touch the torrent/session).\n"
            "        auto const files = tor->files();\n"
            "        auto const dir = std::string{ tor->current_dir() };\n"
            "        auto const name = std::string{ tor->name() };\n"
            "        auto* const session = tor->session;\n"
            "        auto const tor_id = tor->id();\n"
            "\n"
            "        session->disk_io().run_low(\n"
            "            [files, dir, name, delete_func, delete_user_data]() -> std::pair<int, std::string>\n"
            "            {\n"
            "                auto error = tr_error{};\n"
            "                files.remove(dir, name, [delete_func, delete_user_data](char const* f)\n"
            "                             { delete_func(f, delete_user_data, nullptr); }, &error);\n"
            "                return { error.code(), std::string{ error.message() } };\n"
            "            },\n"
            "            [session, tor_id, name](int err, std::string msg)\n"
            "            {\n"
            "                if (err != 0)\n"
            "                {\n"
            "                    tr_logAddWarn(fmt::format(\n"
            "                        fmt::runtime(_(\"Couldn't remove all torrent files for '{name}': {error} ({error_code})\")),\n"
            "                        fmt::arg(\"name\", name),\n"
            "                        fmt::arg(\"error\", msg),\n"
            "                        fmt::arg(\"error_code\", err)));\n"
            "                }\n"
            "                if (auto* const t = session->torrents().get(tor_id); t != nullptr)\n"
            "                {\n"
            "                    tr_torrentFreeInSessionThread(t);\n"
            "                }\n"
            "            });\n"
            "        return;\n"
            "    }\n"
            "\n"
            "    tr_torrentFreeInSessionThread(tor);\n"
            "}"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc tr_torrentRemoveInSessionThread body not found")
        text = text.replace(old, new, 1)
        # 28g. set_location: don't relocate while a remove/relocate job is in flight.
        old = (
            "void tr_torrent::set_location(std::string_view location, bool move_from_old_path, int volatile* setme_state)\n"
            "{\n"
            "    if (setme_state != nullptr)\n"
            "    {\n"
            "        *setme_state = TR_LOC_MOVING;\n"
            "    }\n"
        )
        new = (
            "void tr_torrent::set_location(std::string_view location, bool move_from_old_path, int volatile* setme_state)\n"
            "{\n"
            "    // ANDROID fork: stage 4 - a remove/relocate job is in flight.\n"
            "    if (is_remove_pending() || is_relocate_pending())\n"
            "    {\n"
            "        if (setme_state != nullptr)\n"
            "        {\n"
            "            *setme_state = TR_LOC_ERROR;\n"
            "        }\n"
            "        return;\n"
            "    }\n"
            "\n"
            "    if (setme_state != nullptr)\n"
            "    {\n"
            "        *setme_state = TR_LOC_MOVING;\n"
            "    }\n"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc tr_torrent::set_location body not found")
        text = text.replace(old, new, 1)
        # 28h. tr_torrentSetLocation: the same guard, report the error to the caller.
        old = (
            "    tor->set_location(location, move_from_old_path, setme_state);\n"
            "}"
        )
        new = (
            "    // ANDROID fork: stage 4 - a remove/relocate job is in flight.\n"
            "    if (tor->is_remove_pending() || tor->is_relocate_pending())\n"
            "    {\n"
            "        if (setme_state != nullptr)\n"
            "        {\n"
            "            *setme_state = TR_LOC_ERROR;\n"
            "        }\n"
            "        return;\n"
            "    }\n"
            "\n"
            "    tor->set_location(location, move_from_old_path, setme_state);\n"
            "}"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc tr_torrentSetLocation body not found")
        text = text.replace(old, new, 1)
        # 28i. set_location_in_session_thread: two-phase relocate; the old tail
        #     moves to finish_set_location (shared with the non-move path).
        old = (
            "void tr_torrent::set_location_in_session_thread(std::string_view const path, bool move_from_old_path, int volatile* setme_state)\n"
            "{\n"
            "    TR_ASSERT(session->am_in_session_thread());\n"
            "\n"
            "    auto ok = true;\n"
            "    if (move_from_old_path)\n"
            "    {\n"
            "        if (setme_state != nullptr)\n"
            "        {\n"
            "            *setme_state = TR_LOC_MOVING;\n"
            "        }\n"
            "\n"
            "        // ensure the files are all closed and idle before moving\n"
            "        session->close_torrent_files(id());\n"
            "        session->verify_remove(this);\n"
            "\n"
            "        auto error = tr_error{};\n"
            "        ok = files().move(current_dir(), path, name(), &error);\n"
            "        if (error)\n"
            "        {\n"
            "            this->error().set_local_error(\n"
            "                fmt::format(\n"
            "                    fmt::runtime(_(\"Couldn't move '{old_path}' to '{path}': {error} ({error_code})\")),\n"
            "                    fmt::arg(\"old_path\", current_dir()),\n"
            "                    fmt::arg(\"path\", path),\n"
            "                    fmt::arg(\"error\", error.message()),\n"
            "                    fmt::arg(\"error_code\", error.code())));\n"
            "            tr_torrentStop(this);\n"
            "        }\n"
            "    }\n"
            "\n"
            "    // tell the torrent where the files are\n"
            "    if (ok)\n"
            "    {\n"
            "        set_download_dir(path);\n"
            "\n"
            "        if (move_from_old_path)\n"
            "        {\n"
            "            incomplete_dir_.clear();\n"
            "            current_dir_ = download_dir();\n"
            "        }\n"
            "    }\n"
            "\n"
            "    if (setme_state != nullptr)\n"
            "    {\n"
            "        *setme_state = ok ? TR_LOC_DONE : TR_LOC_ERROR;\n"
            "    }\n"
            "}"
        )
        new = (
            "void tr_torrent::set_location_in_session_thread(std::string_view const path, bool move_from_old_path, int volatile* setme_state)\n"
            "{\n"
            "    TR_ASSERT(session->am_in_session_thread());\n"
            "\n"
            "    if (!move_from_old_path)\n"
            "    {\n"
            "        finish_set_location(path, false, 0, {}, setme_state);\n"
            "        return;\n"
            "    }\n"
            "\n"
            "    // ANDROID fork: stage 4 two-phase relocate (TRANSMISSION_USB_SAF 4.5).\n"
            "    // Phase 1: remember if the torrent was running, stop it (or close its\n"
            "    // files) so the flush and close are enqueued, then move the files on\n"
            "    // the disk worker (Low queue: after every queued write). Phase 2 (the\n"
            "    // completion, on the session thread) applies the new location and\n"
            "    // restarts the torrent if it was running.\n"
            "    if (setme_state != nullptr)\n"
            "    {\n"
            "        *setme_state = TR_LOC_MOVING;\n"
            "    }\n"
            "\n"
            "    relocate_pending_ = true; // ANDROID fork: guard until the move job completes\n"
            "    auto const was_running = is_running();\n"
            "    if (was_running)\n"
            "    {\n"
            "        stop_now();\n"
            "    }\n"
            "    else\n"
            "    {\n"
            "        session->close_torrent_files(id());\n"
            "    }\n"
            "    session->verify_remove(this);\n"
            "\n"
            "    // Copies for the worker (it must not touch the torrent/session).\n"
            "    auto const files = this->files();\n"
            "    auto const old_dir = std::string{ current_dir() };\n"
            "    auto const new_dir = std::string{ path };\n"
            "    auto const nm = std::string{ name() };\n"
            "    auto* const sess = session;\n"
            "    auto const tor_id = id();\n"
            "\n"
            "    sess->disk_io().run_low(\n"
            "        [files, old_dir, new_dir, nm]() -> std::pair<int, std::string>\n"
            "        {\n"
            "            auto error = tr_error{};\n"
            "            auto const ok = files.move(old_dir, new_dir, nm, &error);\n"
            "            return { ok ? 0 : (error.code() != 0 ? error.code() : EIO), std::string{ error.message() } };\n"
            "        },\n"
            "        [sess, tor_id, new_dir, was_running, setme_state](int err, std::string msg)\n"
            "        {\n"
            "            auto* tor = sess->torrents().get(tor_id);\n"
            "            if (tor == nullptr)\n"
            "            {\n"
            "                return; // the session closed before the move finished\n"
            "            }\n"
            "            auto const lock = tor->unique_lock();\n"
            "            tor->relocate_pending_ = false;\n"
            "            tor->finish_set_location(new_dir, true, err, msg, setme_state);\n"
            "            if (was_running && err == 0 && !tor->is_deleting_)\n"
            "            {\n"
            "                tr_torrentStart(tor);\n"
            "            }\n"
            "        });\n"
            "}\n"
            "\n"
            "// ANDROID fork: stage 4 two-phase relocate (TRANSMISSION_USB_SAF 4.5).\n"
            "// The old tail of set_location_in_session_thread, run on the session thread\n"
            "// from the move job's completion (or directly for the non-move path).\n"
            "void tr_torrent::finish_set_location(std::string_view path, bool move_from_old_path, int err, std::string const& msg, int volatile* setme_state)\n"
            "{\n"
            "    if (err != 0)\n"
            "    {\n"
            "        this->error().set_local_error(\n"
            "            fmt::format(\n"
            "                fmt::runtime(_(\"Couldn't move '{old_path}' to '{path}': {error} ({error_code})\")),\n"
            "                fmt::arg(\"old_path\", current_dir()),\n"
            "                fmt::arg(\"path\", path),\n"
            "                fmt::arg(\"error\", msg),\n"
            "                fmt::arg(\"error_code\", err)));\n"
            "        tr_torrentStop(this);\n"
            "    }\n"
            "\n"
            "    // tell the torrent where the files are\n"
            "    if (err == 0)\n"
            "    {\n"
            "        set_download_dir(path);\n"
            "\n"
            "        if (move_from_old_path)\n"
            "        {\n"
            "            incomplete_dir_.clear();\n"
            "            current_dir_ = download_dir();\n"
            "        }\n"
            "    }\n"
            "\n"
            "    if (setme_state != nullptr)\n"
            "    {\n"
            "        *setme_state = err == 0 ? TR_LOC_DONE : TR_LOC_ERROR;\n"
            "    }\n"
            "}"
        )
        if old not in text:
            sys.exit("ERROR: torrent.cc set_location_in_session_thread body not found")
        text = text.replace(old, new, 1)
        with open(tor_cc4, "w", encoding="utf-8") as f:
            f.write(text)
    print("apply_fork: OK")

if __name__ == "__main__":
    main()
