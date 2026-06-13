# Stage 05 — Transmission SAF storage fork (download directly to USB)

**Builds on:** Stage 03 (SAF gateway + fd bridge, proven seekable), Stage 04 (a known-good
transmission build).
**This is the core stage.** It is why the project exists: make the native daemon write
torrent payloads **directly to USB**, with no internal staging and no per-torrent size
limit. Everything before this exists to de-risk it.

## Goal

Fork transmission's file backend so that, for files under the USB download tree, it obtains
**SAF file descriptors via a JNI bridge** (Stage 03's gateway) instead of `open()`-ing a
path. transmission downloads straight to the USB drive.

## The approach

Transmission centralizes file I/O behind a small portability layer
(`libtransmission/file.h`, implemented in `file-posix.cc`). We add an **Android SAF
backend** selected when a target path lies under the configured USB download root, and fall
through to normal POSIX for everything internal (config, `.resume`, stats, logs).

### Functions to route through the SAF bridge (the fork surface)

- **File handles:** `tr_sys_file_open`, `tr_sys_file_open_temp`, `tr_sys_file_close`.
  - `open` → JNI `openFd(relPath, mode)` → store the returned raw fd in `tr_sys_file_t`.
    Subsequent reads/writes use the fd directly with `pread`/`pwrite`/`lseek` — **these need
    no further bridging** because a SAF fd is a real seekable fd.
- **Sizing / preallocation:** `tr_sys_file_truncate`, `tr_sys_file_preallocate`.
  - Implement preallocate as `ftruncate`/sequential grow on the fd (or **disable
    preallocation**; recommend `preallocation = 0` for the SAF path — `fallocate` may be
    unsupported over FUSE/exFAT).
- **Path metadata:** `tr_sys_path_exists`, `tr_sys_path_get_info` (size/mtime/type),
  `tr_sys_path_rename`, `tr_sys_path_remove`.
  - → JNI `statSize`/`exists`, `renameDoc`, `removeDoc`. The crucial one is **rename**
    (`<name>.part` → final, and partial-dir → complete-dir moves): map to
    `DocumentsContract.renameDocument`/`moveDocument`.
- **Directories:** `tr_sys_dir_create` (recursive), and dir enumeration if used during
  verify.
  - → JNI `mkdirs`, `list`.
- **Free space:** transmission checks available space before/while downloading.
  - → JNI `availableBytes`; if the provider can't report it, return a large value (Stage 03
    decision) and let writes fail naturally.

### Path virtualization

- Set transmission's `download-dir`/`incomplete-dir` to a **sentinel root** (e.g.
  `/saf/usb`). The backend recognizes this prefix, strips it, and treats the remainder as a
  **relative path** handed to the gateway. Internal paths (config dir) keep using POSIX.
- The gateway maintains the relative-path ⇄ DocumentFile-URI cache (Stage 03).

### JNI bridge

- A small JNI layer lets the native backend call the Kotlin gateway. Manage the JVM
  reference for transmission's **disk worker thread** (`AttachCurrentThread`/detach;
  cache method IDs). Calls must be thread-safe and reasonably cheap (cache the path→URI
  lookups on the Kotlin side).

## Key decisions & gotchas

- **`incomplete-dir` strategy:** keep partial files on USB too (so big torrents never touch
  internal), using `.part`. Verify SAF rename works for the `.part`→final transition on the
  target device/filesystem; if rename across is unreliable, write to final names directly
  and rely on resume data.
- **Preallocation off** for the SAF path (see above). Document the throughput/fragmentation
  trade-off.
- **fd lifetime/leaks:** native owns the fd from `detachFd()` and closes it in
  `tr_sys_file_close`. Audit every error path so fds aren't leaked (the FUSE layer has
  limited fd budget).
- **`.resume`/config stay internal** — never route transmission's own state through SAF;
  only payload files.
- **Verification (re-check) reads** large amounts via the fd — confirm read throughput is
  acceptable; this is where SAF overhead shows.
- **Concurrency:** transmission's cache flushes from a disk thread; ensure the JNI
  attach/detach and gateway locking don't serialize all I/O into a bottleneck.
- Keep the diff against Stage 04's build **isolated to the file backend + a JNI module** so
  it stays reviewable and so a future rooted build can `#ifdef` back to POSIX
  `/mnt/media_rw`.

## Out of scope (later stages)

- FTP serving the result (Stage 06).
- Exposing storage settings in the UI (Stage 08).

## Acceptance test

- Configure `download-dir` to the USB root. Add a magnet **larger than the phone's free
  internal space** from the Android remote.
- It downloads to completion **directly on the USB drive** (internal free space barely
  moves), passes transmission's verify, and the files are readable on another computer when
  the drive is moved (or via Stage 06 FTP).
- Pause/resume and an app restart mid-download both resume correctly (resume data internal,
  payload on USB).
- No fd leaks after many torrents (monitor open fds).
