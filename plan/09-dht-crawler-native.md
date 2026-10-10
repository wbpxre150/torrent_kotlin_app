# Stage 09 — dht_crawler native build, packaging & supervision

**Builds on:** Stage 02 (packaging & `ProcessSupervisor`), Stage 04 (NDK/Gradle cross-compile
pattern, mbedTLS build), Stage 07 (config ownership), Stage 08 (resilience).
**Replaces:** the old Stage 09 (crawler + rclone + import) and the backup stages 11/12, which are
dropped. **The crawler runs with all backups turned off.** rclone, sqlite3 and zstd are not shipped.
**Followed by:** Stage 10 (crawler web control panel).

## Goal

Run the same `dht_crawler` that runs today in Termux on the phone (16 thread trees)
**inside the app** as a supervised native child process next to transmission. It should
reach parity with Termux: it crawls, writes `data/torrents.db` on internal storage, and serves
its search UI on `:8080`. Stage 09 is the C side plus the minimum Kotlin wiring (config seeding,
supervisor registration, enable flag). The full settings UI comes in Stage 10.

## Source facts (verified in `~/apps/dht_crawler`)

- ~19k lines of C in `src/`; submodules `lib/{bencode-c,cJSON,civetweb,libbloom,uthash}`; the
  upstream `Makefile` applies 3 patches from `patches/` to the submodules **in place**.
- External deps: **libuv**, **sqlite3 (FTS5 required** — `torrent_search`/`file_search` virtual
  tables), **OpenSSL used only for `SHA1()`** (2 call sites in `tree_metadata.c`), pthread, m, dl.
- No `pthread_cancel` (not available on Bionic). `SO_REUSEPORT` is behind `#ifdef`.
- Reads `config.ini` and `torrent_search_keywords.txt` **relative to cwd**. Default paths are
  relative (`data/torrents.db`, `data/bloom.dat`, `data/bep51_cache.dat`, `porn_filter_keywords.txt`).
- Logs go to **stderr** (`log_msg`), so the supervisor captures them (no self-logging).
- Handles SIGINT/SIGTERM for clean shutdown (flushes batch writer, persists bloom filters).
- HTTP port is hard-coded: `"listening_ports", "8080"` in `http_api.c` (`HTTP_API_PORT` in
  `http_api.h`); binds all interfaces, **no auth**. Absolute links (`/search`, `/torrent`, …).
- Maintenance CLI modes that exit: `--rebuild-bloom-filter`, `--porn-filter-update`, `--compact`,
  `--check-database`, `--recover-database`; plus `--dont-check-database` (skip startup check).
- **Backups default OFF in `config.c`** (`backup_enabled`, `ssh_backup_enabled`,
  `rclone_backup_enabled` all `0`). Backup code only runs if a flag is set, so turning them off
  in `config.ini` is all we need. **No source change for backups.**

## What gets built

### 1. Getting the source into the build

- Add `dht_crawler` as a **git submodule** pinned to a commit, e.g. `app/src/main/cpp/dht_crawler`
  (`--recursive` for its `lib/*` submodules). Allow a Gradle property override
  (`-PdhtCrawlerSrc=../../dht_crawler`) for local development against the sibling repo.
- The build **copies** the source to `app/build/native/src/dht_crawler/` and applies the three
  `patches/*.patch` there. Never patch the submodule in place (today's Makefile leaves `lib/`
  dirty).

### 2. Small upstream changes to dht_crawler (commit to the dht_crawler repo; desktop defaults unchanged)

1. **`http_port` / `http_bind` config keys** (defaults `8080` / `0.0.0.0`). Build the civetweb
   `listening_ports` string from them (`127.0.0.1:8080` form). Stage 10 needs loopback binding
   behind the authenticated proxy.
2. **`nice_level` config key** (default `0`): `setpriority(PRIO_PROCESS, 0, n)` early in `main`
   **before any thread is spawned**, so every thread inherits it. This keeps the crawler from
   starving transmission and the SAF IPC threads. Seed `10` on Android.
3. *(Verify, fix if needed)* Clean SIGTERM shutdown finishes in a bounded time. Measure it on the
   phone with a real-size DB and bloom filters.

Everything else (the SHA1 shim, the build files) lives **app-side** so upstream stays portable.

### 3. Cross-compile (new Gradle tasks, same style as `buildMbedtls`/`buildTransmission`)

- **Linking: dynamic PIE against Bionic with static third-party deps**, the same as
  `libtransmissiond.so` today (`ELF pie executable, interpreter /system/bin/linker64`). This
  avoids the PT_TLS `p_align` patch that fully static NDK binaries need (the `buildProbe`
  reference task no longer exists). It also keeps DNS (`getaddrinfo` via netd) working for the
  cold-start URL bootstrap. Target `android-24`, `arm64-v8a`, NDK r27.
- `buildLibuv`: libuv release tarball → CMake + NDK toolchain → static `libuv.a`.
- **sqlite3:** compile the amalgamation (`sqlite3.c`) straight into the crawler with
  `-DSQLITE_ENABLE_FTS5 -DSQLITE_THREADSAFE=1` (match the desktop build's defaults where it
  matters, e.g. WAL).
- **SHA1 without OpenSSL:** add an app-side compat include dir with `openssl/sha.h` that maps
  `SHA1(d, n, md)` → `mbedtls_sha1(d, n, md)`. Link `libmbedcrypto.a` from the existing
  `buildMbedtls` task. No OpenSSL build.
- `buildDhtCrawler`: an app-side `CMakeLists.txt` (e.g. `app/src/main/cpp/dht_crawler-android/`)
  compiles `src/*.c` + `bencode.c`, `cJSON.c`, `civetweb.c` (`-DNO_SSL`) + libbloom sources, with
  the same `CFLAGS` defines as the upstream Makefile. Output is copied as
  `src/main/jniLibs/arm64-v8a/libdhtcrawler.so`. Hook it into `mergeDebugJniLibFolders` like
  `buildTransmission`, and declare inputs so it rebuilds incrementally.
- Expect small Bionic portability fixes (missing headers, `_GNU_SOURCE`, `rlim_t` printf).
  Fix them app-side with defines/shims first. Only upstream a fix if it is a real bug.

### 4. Runtime layout & seeded config

- Working dir: `filesDir/crawler/` (contains `config.ini`, `data/`, keyword files).
- Ship `porn_filter_keywords.txt` and `torrent_search_keywords.txt` as assets under
  `assets/dht_crawler/`. Copy them on first run and when the app version changes.
- **Seed a default `config.ini`** (`assets/dht_crawler/config.ini.default`) from the README's
  Android tuning: `num_trees = 16`, reduced worker counts. The phone's real Termux config and
  data are pulled in by the Stage 10 Termux migration. Then force:
  ```ini
  backup_enabled = 0
  ssh_backup_enabled = 0
  rclone_backup_enabled = 0
  http_port = 8080
  http_bind = 0.0.0.0      ; Stage 10 switches this to 127.0.0.1 behind the proxy
  nice_level = 10
  ```
  Keep all path keys relative (`data/...`). Remove any desktop absolute paths (e.g. the
  `backup_path` under `/home/adam/...`).
- A Kotlin `CrawlerConfig` class (mirrors `TransmissionConfig`) owns the file. It has
  `ensureConfigIni()` (seed if missing) and a writer that **always** emits the three backup keys
  as `0`, whatever the input. Atomic write: temp file + rename.

### 5. Supervisor integration (Kotlin, minimal)

- Register in `DaemonService` next to transmission:
  `ProcessSupervisor(label = "dht_crawler", binaryName = "libdhtcrawler.so", workDir = filesDir/crawler,
  logDir = filesDir/logs, selfLogging = false)`. The existing `/daemon/{label}/{action}` routes
  and dashboard status give start/stop/restart/log tail with no extra work.
- **Make the grace period configurable** in `ProcessSupervisor` (it is a hard-coded 5 s today).
  Give the crawler a longer grace (start at 60 s, then tune to the measured shutdown time). A
  SIGKILL mid-flush loses the current batch and can leave the bloom files stale.
- Add `CrawlerSettings(enabled: Boolean = false)` to `AppConfig`/`ConfigStore`. Start the
  crawler on service start only when enabled. Default off, so existing installs and the
  transmission-only setup are unchanged.
- Cold-start bootstrap abort (empty BEP51 cache + bootstrap failure → exit) is covered by the
  existing exponential backoff.
- Stop order in `onDestroy`: stop the crawler (long grace) in parallel with transmission. Never
  block the SAF gateway shutdown on it, because the crawler does not use SAF.

## Key decisions & gotchas

- **Backups are off, not removed.** Config-only. The writer pins them to `0`. No rclone, ssh,
  sqlite3 or zstd binaries are shipped. No `system()` call path gets exercised.
- **Internal storage only.** The crawler never touches USB or SAF.
- **Resource parity with Termux.** About 16 trees × ~140 threads ≈ 2,200 threads, plus bloom
  filters (~50 MB each at 30M capacity). This is now in the same app UID/cgroup as transmission.
  Watch for thread-creation failures (`pthread_create` EAGAIN) in the log.
- **Phantom process killer (Android 12+)** can kill CPU-heavy child processes. Termux already
  works on this phone, so the device is probably already configured, but confirm (logcat
  `PhantomProcessRecord`). If needed: Developer options → "Disable child process
  restrictions", or `device_config put activity_manager max_phantom_processes 2147483647`.
  Document this in Stage 08's OEM guidance.
- **Don't run Termux and the app crawler at the same time.** Both would bind `:8080` and UDP
  `6881` and crawl the same DHT twice.
- **Protect transmission.** The crawler is CPU and network heavy. Use `nice_level` and verify
  transmission's disk-I/O latency (`[DISKREAD]` log) and download speed with the crawler on vs
  off.
- The startup integrity check on a large DB can take minutes. The supervisor shows RUNNING
  during that time, which is fine. Stage 10 exposes `--dont-check-database` as a toggle.

## Out of scope

- Crawler settings panel, maintenance actions, authenticated search UI proxy, stats card, wifi
  gating, migrating `data/` + `config.ini` from Termux's home (Stage 10). Stage 09 can be tested
  on a cold-start empty DB. Stop the Termux crawler while testing (ports clash).
- Any backup or restore functionality (dropped).

## Acceptance test

- `./gradlew installDebug` (JDK 21) builds `libdhtcrawler.so` for arm64 (dynamic PIE,
  `interpreter /system/bin/linker64`), with incremental rebuilds working.
- With `crawler.enabled=true`, the service starts the crawler. The dashboard shows it RUNNING
  with PID, uptime, and log tail.
- Cold start on an empty `data/` bootstraps (DNS works) and `torrents.db` starts growing.
  `curl http://<phone-ip>:8080/stats` and `/search?q=ubuntu` work from the LAN.
- `config.ini` on device has all three backup keys `= 0`. The log shows no backup thread
  activity over 24 h.
- `kill -9` of the crawler → the supervisor restarts it. Dashboard Stop → clean SIGTERM shutdown
  inside the grace period (log shows the final flush; no SIGKILL).
- 24 h soak with the crawler and an active transmission download: no crashes or phantom kills;
  transmission speed and `[DISKREAD]` latency stay within ~10% of crawler-off; metadata rate is
  comparable to Termux.
