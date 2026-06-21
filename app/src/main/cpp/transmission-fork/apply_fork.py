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

    # 6. Patch top-level CMakeLists.txt: disable libevent thread support to avoid
    #    FindThreads failure on Android cross-compile.
    top_cmake = os.path.join(src_root, "CMakeLists.txt")
    with open(top_cmake, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: disable event threads" not in text:
        old = "-DEVENT__LIBRARY_TYPE:STRING=STATIC)"
        new = "-DEVENT__LIBRARY_TYPE:STRING=STATIC\n        -DEVENT__DISABLE_THREAD_SUPPORT:BOOL=ON\n        # ANDROID fork: disable event threads\n)"
        text = text.replace(old, new, 1)
        with open(top_cmake, "w", encoding="utf-8") as f:
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

    # 9. Patch session-thread.cc: guard evthread code behind
    #    EVENT__DISABLE_THREAD_SUPPORT so the build works when libevent
    #    thread support is disabled (Android cross-compile).
    st = os.path.join(libt, "session-thread.cc")
    with open(st, "r", encoding="utf-8") as f:
        text = f.read()
    if "ANDROID fork: guard evthread" not in text:
        # Guard #include <event2/thread.h>
        text = text.replace(
            "#include <event2/thread.h>",
            "#include <event2/thread.h>\n// ANDROID fork: guard evthread when thread support disabled",
            1,
        )
        # Wrap the tr_evthread_init_helpers namespace
        old = "#endif\n}\n\nvoid init_evthreads_once()\n{\n    evthread_lock_callbacks constexpr LockCbs{"
        new = "#endif\n}\n\nvoid init_evthreads_once()\n{\n#ifndef EVENT__DISABLE_THREAD_SUPPORT\n    // ANDROID fork: guard evthread types when thread support disabled\n    evthread_lock_callbacks constexpr LockCbs{"
        text = text.replace(old, new, 1)
        # Close the guard before the namespace close
        old2 = "    evthread_set_id_callback(thread_current_id);\n}\n\n} // namespace tr_evthread_init_helpers"
        new2 = "    evthread_set_id_callback(thread_current_id);\n#endif\n}\n\n} // namespace tr_evthread_init_helpers"
        text = text.replace(old2, new2, 1)
        with open(st, "w", encoding="utf-8") as f:
            f.write(text)
    print("apply_fork: OK")

if __name__ == "__main__":
    main()
