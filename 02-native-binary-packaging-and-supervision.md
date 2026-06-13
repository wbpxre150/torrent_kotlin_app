# Stage 02 — Native binary packaging & process supervision

**Builds on:** Stage 01 (the foreground service / supervisor host).
**De-risks:** the single most failure-prone Android mechanic — executing bundled native
binaries — *before* the hard transmission work depends on it. Proven here with a **minimal
test binary** (a tiny statically-linked C program). dht_crawler and rclone cross-compiles
are **deferred to post-PoC** — they are not needed to get transmission writing to USB.

## Goal

Ship a native arm64 binary inside the APK, exec it as a supervised child process that
restarts on crash, and capture its logs. The packaging and supervisor machinery proven here
is what Stage 04 will reuse for `libtransmissiond.so`.

## What gets built

1. **Minimal test binary** — a tiny C program (`probe.c`) that accepts a `--version` flag,
   prints a version string plus its PID, then sleeps indefinitely (so it behaves like a
   daemon the supervisor can kill/restart). Statically linked, no external deps.
   - Compiled for `arm64-v8a` with the NDK: `aarch64-linux-android24-clang -static`.
   - Placed at `src/main/jniLibs/arm64-v8a/libprobe.so`. At install it extracts
     (because `useLegacyPackaging = true`) to `applicationInfo.nativeLibraryDir`, which is
     the **only** location an app may `execve()` on Android 10+.
2. **Kotlin process supervisor**, one instance per daemon:
   - Launch via `ProcessBuilder(File(nativeLibraryDir, "libX.so").absolutePath, ...args)`,
     `directory()` = a writable working dir in `filesDir`, env includes
     `LD_LIBRARY_PATH=nativeLibraryDir` if any companion `.so` deps are shipped.
   - **Drain `stdout`/`stderr`** on a coroutine into a rotating log file + an in-memory ring
     buffer (an unread pipe will hang the child).
   - Liveness via `Process.isAlive()` / `waitFor()`; on unexpected exit, **exponential
     backoff restart** (capped), with an "intentional stop" flag to prevent restart storms.
   - Track child PID (`Process.pid()`), expose status to the control server.
   - Clean shutdown: `destroy()` (SIGTERM) → grace period → `destroyForcibly()` (SIGKILL).
     Clean stale lock/pid files on restart.
3. **Control page** lists each supervised process with state, PID, uptime, last 200 log lines,
   and start/stop/restart buttons (extends Stage 01's stub).

## Key decisions & gotchas

- **`useLegacyPackaging = true`** (set in Stage 01) — without it the `.so` is never written
  to disk and exec dies with `ENOENT`/`EACCES`. This is *the* classic "works in debug,
  breaks in release/AAB" bug.
- Filename **must** match `lib*.so` or packaging drops it from `jniLibs`.
- Prefer **static linking**; dynamic deps against the wrong libc fail at exec.
- Build with a **recent NDK (r27+)** for forward-compatible 16 KB page alignment (Android
  15+).
- The test binary must genuinely sleep (not exit immediately) to exercise the supervisor's
  liveness check and restart path.

## Deferred — dht_crawler & rclone (post-PoC, after Stage 05)

When dht_crawler is eventually added, the cross-compile specifics are:
- **Native deps:** `libuv`, `libsqlite3`, `libssl`/`libcrypto`, `libpthread`, `libm`,
  `libdl`. Bundled submodules in `lib/` (`bencode-c`, `cJSON`, `civetweb`, `libbloom`,
  `uthash`); replicate the Makefile with an NDK toolchain + cross-built deps (crib from
  `termux-packages`).
- **HTTP port 8080 is effectively hardcoded** (`http_api.c:99`); binds all interfaces.
- **Working directory matters:** `config.ini` paths are relative; set `directory()` to the
  dir holding `data/`, `config.ini`, and keyword files.
- **The crawler has its own internal supervisor** (`supervisor.c`); the Kotlin supervisor
  only needs to keep the process alive and restart on full exit.
- **Resource tuning:** defaults (~32 trees, ~4,500 threads, ~500 MB RAM) must be reduced
  for mobile via a seeded `config.ini` (`num_trees=16`, lower worker counts).
- **Cold-start bootstrap abort:** if the BEP51 cache is empty and bootstrap fails, the
  process exits. Supervisor should retry; importing an existing `data/` (Stage 03) avoids
  this.
- **rclone:** `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build` → static binary as
  `librclone.so`. Configured and used in Stage 07.

## Out of scope (later stages)

- transmission build (Stage 04) and its SAF fork (Stage 05).
- USB (Stage 03).
- dht_crawler and rclone cross-compiles (post-PoC, after Stage 05).
- rclone configuration & the crawler's built-in backup wiring (Stage 07).

## Acceptance test

- After launch, the supervisor starts `libprobe.so`; the control page shows it running
  with its PID and uptime.
- `kill` the child → supervisor restarts it within the backoff window; the restart event
  is visible in the control page log.
- Release (minified/AAB) build still execs the binary — this is the packaging regression
  guard that `useLegacyPackaging = true` protects against.
