# Stage 0: instrumentation (baseline)

Part of `TRANSMISSION_USB_SAF.md` (master plan). Stages: **0** instrumentation → 1 worker + async
verify → 2 async writes → 3 async upload reads → 4 SAF sockets + two-phase stop/remove →
5 preallocation + cleanup. Each stage builds on the previous one. Do them in order.

**Goal**: log every slow disk operation with its thread, op, path, offset, length and duration,
name the threads so they can be sampled, and add two device scripts. No behavior change.

**Exit criterion**: daemon builds, runs, and `[DISKSLOW]` lines appear in the daemon log during a
download to USB. Baseline numbers are recorded in `TRANSMISSION_USB_SAF.md`.

---

## Upstream check (done 2026-10-07, don't repeat it)

Upstream transmission has **not** moved disk I/O off the session thread. The latest release is
4.1.3 (I/O still synchronous). On `main`, issue #8742 started an async series: #8669 deleted the
write cache, and #8743 added an unused `tr::LocalData` class. That work stalled in March 2026,
and main still calls `tr_ioWrite`/`tr_ioRead`/`tr_ioTestPiece` on the session thread. Decision:
**stay on 4.1.2 and write our own worker** (stages 1–4). Our worker API loosely mirrors
upstream's `LocalData` (callback-style `read`/`write`/`test_piece`), so a future rebase can swap
the backend.

---

## Conventions (same for every stage)

- Transmission source: `app/build/native/src/transmission-4.1.2/` (extracted from the tarball by
  the `buildTransmission` Gradle task). **Never edit it by hand.** All changes go through
  `app/src/main/cpp/transmission-fork/apply_fork.py` (anchor-string replacements) plus new fork
  files in `app/src/main/cpp/transmission-fork/`.
- `apply_fork.py` style: read the file, check for a unique marker comment (`ANDROID fork: ...`),
  `replace(old, new, 1)`, and `sys.exit("ERROR: ...")` if the anchor is missing. Copy the style
  of existing steps 9–12. Number new steps after the last existing one.
- Anchors must match the **already patched** text. Earlier steps (9–12 and earlier stages)
  change some of these functions, for example `check_piece` returns `int`.
- New fork `.cc`/`.h` files need three registrations, the same as `file-saf.cc`:
  1. add the names to the copy tuple in step 1 of `apply_fork.py`;
  2. add the `.cc` to **both** CMake source lists in step 4 (the main list and the `NOT WIN32`
     list). Extend the existing `file-saf.cc` replacement strings, or add a new guarded block;
  3. add `inputs.file("src/main/cpp/transmission-fork/<name>")` in `app/build.gradle.kts` next to
     the existing `file-saf.*` lines (task `buildTransmission`).
- **Re-applying patches**: steps are guarded by markers, so a changed step does not re-apply to
  a tree that is already patched. After changing `apply_fork.py`, delete the extracted tree
  (`rm -rf app/build/native/src/transmission-4.1.2`, but keep the `.tar.xz`) and rebuild.
- Build (JDK 21 is mandatory):
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
  export ANDROID_HOME="$HOME/Android/Sdk"
  export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
  ./gradlew :app:buildTransmission   # native only, quick compile check
  ./gradlew installDebug             # full build + install on the device
  ```
- To read the patched source while working, open the files under
  `app/build/native/src/transmission-4.1.2/libtransmission/` after a build.
- Don't regress the existing fork fixes: tri-state `tr_ioTestPiece`/`check_piece`/
  `ensure_piece_is_checked` (step 9), EOF → EIO (step 10), `checked_pieces_.set` in
  `on_piece_completed` (step 11, Mechanism C), and the mtime check in `load_checked_pieces`
  (step 12, Mechanism D).
- One commit per stage. Commit message prefix: `feat(transmission): stage N ...`.
- If no device is attached (`adb devices` shows nothing), finish the code and build, then stop
  and ask the user to run the device scenarios.

---

## Steps

### 0.1 New fork header `disk-timing.h`

Create `app/src/main/cpp/transmission-fork/disk-timing.h` (header only, so no CMake entry. It
still needs the copy tuple and the Gradle `inputs.file`):

```cpp
// ANDROID fork: slow disk-operation logging (TRANSMISSION_USB_SAF stage 0).
#pragma once

#include <sys/prctl.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>

#include <fmt/format.h>

#include "libtransmission/log.h"

class tr_disk_timer
{
public:
    tr_disk_timer(char const* op, std::string_view path, uint64_t offset = 0, uint64_t len = 0)
        : op_{ op }
        , path_{ path }
        , offset_{ offset }
        , len_{ len }
        , start_{ std::chrono::steady_clock::now() }
    {
    }

    tr_disk_timer(tr_disk_timer const&) = delete;
    tr_disk_timer& operator=(tr_disk_timer const&) = delete;

    ~tr_disk_timer()
    {
        auto const ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start_)
                            .count();
        char name[16] = {};
        prctl(PR_GET_NAME, name, 0, 0, 0);
        auto const on_session = std::strcmp(name, "tr-session") == 0;
        auto const threshold = on_session ? SessionThresholdMs : OtherThresholdMs;
        if (ms >= threshold)
        {
            tr_logAddWarn(fmt::format(
                "[DISKSLOW] thread={} op={} path={} offset={} len={} ms={}",
                name,
                op_,
                path_,
                offset_,
                len_,
                ms));
        }
    }

    static constexpr long long SessionThresholdMs = 100;
    static constexpr long long OtherThresholdMs = 100; // stage 5 raises this

private:
    char const* op_;
    std::string path_;
    uint64_t offset_;
    uint64_t len_;
    std::chrono::steady_clock::time_point start_;
};
```

### 0.2 Name the threads

New `apply_fork.py` step: patch `libtransmission/session-thread.cc`. In
`void session_thread_func(struct event_base* evbase)`, insert this as the first statement of
the body:

```cpp
    // ANDROID fork: name the session thread for sampling (TRANSMISSION_USB_SAF).
    prctl(PR_SET_NAME, "tr-session", 0, 0, 0);
```

Also add `#include <sys/prctl.h>` to that file's includes. Use the existing
`#include <thread>` (or another include near the top) as the anchor.

In `libtransmission/verify.cc`, at the top of `void tr_verify_worker::verify_thread_func()`,
add `prctl(PR_SET_NAME, "tr-verify", 0, 0, 0);` the same way. Later stages name the disk
worker `tr-diskio`.

### 0.3 Instrument the session-thread disk paths

New `apply_fork.py` step (marker `ANDROID fork: stage 0 timing`). Add
`#include "libtransmission/disk-timing.h"` to each patched file. Wrap these calls in a scoped
`tr_disk_timer`. Add a `{ ... }` block if needed so the timer covers only the call:

| File | Function | Wrap | `op` | `path` / `offset` / `len` |
|---|---|---|---|---|
| `inout.cc` | `read_or_write_bytes` | the `get_fd(...)` call | `"open"` | `tor.file_subpath(file_index)`, 0, 0 |
| `inout.cc` | `read_or_write_bytes` | `write_entire_buf(...)` | `"pwrite"` | subpath, `file_offset`, `buflen` |
| `inout.cc` | `read_or_write_bytes` | `read_entire_buf(...)` | `"pread"` | subpath, `file_offset`, `buflen` |
| `inout.cc` | `tr_ioTestPiece` | whole body | `"testpiece"` | `tor.name()`, `piece`, `tor.piece_size(piece)` |
| `session.cc` | `tr_session::flush_torrent_files`, `close_torrent_files`, `close_torrent_file` | whole body | `"flush"` / `"close"` / `"closefile"` | `""`, `tor_id` (or file index), 0 |
| `file-saf.cc` (fork file, edit directly) | every `tr_saf_*` function that takes `g_mutex` | construct the timer **before** the `lock_guard`, so lock wait counts | `"saf-open"`, `"saf-exists"`, `"saf-stat"`, `"saf-rename"`, `"saf-remove"`, `"saf-mkdir"`, `"saf-capacity"` | the path argument |

### 0.4 Device scripts

Create `tools/usb-io/rpc_probe.sh` (executable). It takes `USER PASS [SECONDS]` and:
- runs `adb forward tcp:9091 tcp:9091` once;
- every 1 s runs `curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -u USER:PASS -X POST
  -d '{"method":"session-get"}' http://127.0.0.1:9091/transmission/rpc` with `--max-time 600`;
- counts **only** HTTP 200 and 409 responses (connection refused and timeouts return `000`. Count
  them separately, never as latency samples);
- on exit (Ctrl-C or SECONDS elapsed) prints count, p50, p99 and max latency in ms, plus the
  number of non-200/409 results.

Create `tools/usb-io/sample_threads.sh` (executable). It:
- finds the daemon pid: `adb shell pidof libtransmissiond.so`, falling back to
  `adb shell ps -A | grep -i transmission`;
- finds the tids whose `comm` is `tr-session`, `tr-diskio` or `tr-verify`:
  `adb shell run-as com.torrent.app sh -c 'for t in /proc/PID/task/*; do echo $t $(cat $t/comm); done'`;
- every 200 ms reads `/proc/PID/task/TID/syscall` and `/proc/PID/task/TID/stat` (field 3 = state)
  for the session thread (and `tr-diskio` once it exists);
- prints a line whenever the session thread is in state `D` or its syscall number is 67
  (`pread64`) or 68 (`pwrite64`), and a summary of counts at exit.

If `run-as` is denied for `/proc/PID/task` (the daemon runs as the app uid, so it should work),
report that to the user instead of working around it.

RPC credentials live in the app's settings. If you can't find them, ask the user.

### 0.5 Baseline measurements

With the stage 0 build installed, run the scenarios from the master plan's **Verification**
section (1: add a new multi-GB multi-file torrent with preallocation 0; 2: heavy seeding while
downloading; 3: stop/remove/relocate under load) while running both scripts. Pull the daemon log
(ask the user where the app writes it, or use `adb logcat -s transmission`) and summarize the
`[DISKSLOW]` lines per op: count, p50, max.

Add a `## Measurements` section at the end of `TRANSMISSION_USB_SAF.md` with a table
`stage | scenario | RPC p50 | RPC p99 | RPC max | session-thread D/67/68 samples | worst DISKSLOW op`
and fill in the stage 0 rows. Later stages append rows.

---

## Out of scope

Any behavior change. Leave all I/O where it is.
