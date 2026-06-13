# Stage 04 — Transmission build baseline (NDK), running to internal storage

**Builds on:** Stage 02 (packaging + supervision).
**De-risks:** the transmission **build** (its dependency chain is the heaviest in the
project) *separately* from the storage **fork** (Stage 05). Here it runs unmodified,
writing to internal storage, so we confirm it builds, runs, and talks to the remote before
touching its file layer.

## Goal

A stock (or lightly patched) `transmission-daemon` cross-compiled for arm64, supervised by
the app, downloading to **internal** storage, controllable from the user's existing
Transmission Android remote via RPC on `:9091`.

## What gets built

1. **Cross-compile transmission 4.x for arm64** with the NDK (CMake +
   `android.toolchain.cmake`, `-DANDROID_ABI=arm64-v8a`, `-DANDROID_PLATFORM=android-24`):
   - Build static deps first: **OpenSSL** (or mbedTLS — smaller/easier static build),
     **libcurl** (`--with-openssl`/`--with-mbedtls`), **libevent**. Disable GTK/Qt UI,
     systemd, NLS.
   - Build only `transmission-daemon` (+ optionally `transmission-remote` for debugging) as
     a static executable.
   - **Crib configure/CMake flags from `termux-packages`'** transmission `build.sh` — it
     already cross-compiles transmission for arm64 Android.
2. **Package** the binary as `jniLibs/arm64-v8a/libtransmissiond.so` (+ any companion `.so`
   if not fully static). Supervise it with the Stage 02 supervisor.
3. **Config management:** generate `settings.json` in internal `filesDir`:
   - `rpc-enabled`, `rpc-bind-address` `0.0.0.0` (so the LAN remote can reach it) or
     loopback + control reverse-proxy — pick one; **`rpc-authentication-required`,
     `rpc-username`, `rpc-password`** set (loopback isn't UID-isolated).
   - `rpc-whitelist` to the LAN subnet.
   - `download-dir` / `incomplete-dir` = **internal** staging path **for this stage only**.
   - Run with `--foreground -g <config-dir>`.
4. **Verify the remote loop:** add a magnet from the Android remote; it downloads to
   internal storage; status reflects in the remote and the control UI.

## Key decisions & gotchas

- This stage intentionally **does not** write to USB. Internal-only keeps the build
  decoupled from the fork. Small test torrents only (internal space is limited — that
  limitation is exactly what Stage 05 fixes).
- Decide RPC exposure: simplest is bind `0.0.0.0:9091` + auth + whitelist so the existing
  remote works unchanged. Document the choice; Stage 08 surfaces these as settings.
- Most settings can change **live over RPC**; only some (peer port, RPC bind) need a daemon
  restart. The control server (Stage 08) will prefer RPC, fall back to restart.
- Keep the patch footprint here near-zero so Stage 05's fork is a clean, reviewable diff
  against a known-good build.

## Out of scope (later stages)

- Writing to USB / the SAF-fd file backend (Stage 05) — the whole point, deferred so the
  build is proven first.
- Settings UI for transmission (Stage 08).

## Acceptance test

- The daemon starts under the supervisor; the user's Transmission Android remote connects
  (with auth) and shows it.
- Adding a small magnet downloads it to internal storage and completes; files verify.
- Crash/restart via the supervisor preserves state (resume files in internal config dir).
- Release build still execs the binary (packaging regression guard).
