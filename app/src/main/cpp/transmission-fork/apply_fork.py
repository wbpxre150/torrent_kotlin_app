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

    # 4. CMakeLists.txt: add file-saf.cc to the main add_library source list (REQUIRED —
    #    tr_allow_compile_if only DISABLES sources; they must already be in the target's
    #    source list to compile) and to the NOT WIN32 tr_allow_compile_if block (so a
    #    hypothetical Win32 build disables it, mirroring file-posix.cc).
    cm = os.path.join(libt, "CMakeLists.txt")
    with open(cm, "r", encoding="utf-8") as f:
        text = f.read()
    if "file-saf.cc" not in text:
        # 4a. Main add_library list: insert after file-posix.cc (appears before file-win32.cc).
        main_target = "        file-posix.cc\n        file-win32.cc"
        main_repl = "        file-posix.cc\n        file-saf.cc\n        file-win32.cc"
        if main_target not in text:
            sys.exit("ERROR: CMakeLists main source list not found")
        text = text.replace(main_target, main_repl, 1)
        # 4b. tr_allow_compile_if NOT WIN32 block: insert after file-posix.cc there too.
        cond_target = "        file-posix.cc\n        subprocess-posix.cc)"
        cond_repl = "        file-posix.cc\n        file-saf.cc\n        subprocess-posix.cc)"
        if cond_target not in text:
            sys.exit("ERROR: CMakeLists NOT WIN32 block not found")
        text = text.replace(cond_target, cond_repl, 1)
        with open(cm, "w", encoding="utf-8") as f:
            f.write(text)

    print("apply_fork: OK")

if __name__ == "__main__":
    main()