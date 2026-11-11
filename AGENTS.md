# Build

## JDK

The system default JDK is 26. Android Gradle Plugin 8.8.2 does not support JDK 26.

**Always** use JDK 21 for any Gradle build:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
./gradlew installDebug
```

## Quick reference

| Env | Value |
|---|---|
| `JAVA_HOME` | `/usr/lib/jvm/java-21-openjdk` |
| `ANDROID_HOME` | `$HOME/Android/Sdk` |
| Build command | `./gradlew installDebug` (with both env vars set) |

# Project Structure

**What this is:** an Android app (`com.torrent.app`, single Gradle module `:app`) that runs three
native daemons on-device — a forked **transmission-daemon**, the vendored **dht_crawler**, and a
hand-written **FTP server** — supervised by Kotlin, fronted by a Ktor control dashboard, with all
disk I/O routed through Android's Storage Access Framework (SAF) via a Unix-socket fd bridge.

## Layout

```text
torrent_kotlin_app/
├── AGENTS.md / README.md / LICENSE
├── build.gradle.kts / settings.gradle.kts / gradle.properties   # root Gradle config
├── app/                              # the only module (:app)
│   ├── build.gradle.kts              # Android config + ALL native cross-compile tasks
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   # MainActivity (launcher) + DaemonService (FGS specialUse)
│       │   ├── java/com/torrent/app/ # Kotlin sources (map below)
│       │   ├── cpp/
│       │   │   ├── dht_crawler/          # vendored git repo (own .git + lib/ submodules)
│       │   │   ├── dht_crawler-android/  # CMake wrapper + compat/openssl mbedTLS shim
│       │   │   ├── transmission-fork/     # apply_fork.py + SAF fork sources (tests/)
│       │   │   └── ftp-server/            # hand-written C11 FTP daemon (+ host/ test build)
│       │   ├── assets/dht_crawler/config.ini.default   # sanitized crawler config template
│       │   └── jniLibs/arm64-v8a/    # gitignored lib*.so build outputs (exec'd not dlopen'd)
│       └── test/java/com/torrent/app/ # unit tests mirroring main packages (NO androidTest)
└── tools/
    ├── ftp-host-test.sh              # host smoke test for the FTP daemon
    └── usb-io/{rpc_probe.sh, sample_threads.sh}   # ADB diagnostics for transmission
```

## Kotlin app layer (`app/src/main/java/com/torrent/app/`)

**Root package** — orchestration + native process layer:

| File | Role |
|---|---|
| `DaemonService.kt` (~1300) | The hub: foreground service wiring ~12 collaborators in `onCreate` (servers, executors, gateways); sealed `ServiceState`; one single-thread executor per supervised process so lifecycle ops never block main thread |
| `MainActivity.kt` (398) | Compose UI; first-run wizard; binds `DaemonService` via `ServiceConnection` |
| `ControlServer.kt` (1140) | Ktor/CIO dashboard + JSON API on `0.0.0.0:8090` (auth via `control_token` cookie) |
| `CrawlerProxyServer.kt` (301) | Ktor/CIO authed reverse proxy to crawler's loopback HTTP API on 8081; strips auth when forwarding |
| `NativeProcess.kt` | Shared exec primitives — runs binaries **in place from `nativeLibraryDir`** |
| `ProcessSupervisor.kt` (363) | Supervises one child with restart/backoff; graceful SIGTERM; serialized start/stop |
| `OneShotRunner.kt` (77) | Single run of a native binary, cancellable (SIGTERM→SIGKILL) — used for maintenance steps |
| `RollbackPolicy.kt` (11) | Pure threshold-based rollback decision |
| `TokenManager.kt` (34) | Constant-time control-token validation |
| `CrawlerConfig.kt` | Parses/writes crawler `config.ini`; atomic writes; pinned keys (lives in root package, not `config/`!) |
| `TransmissionConfig.kt` (161) | Owns `transmission/` config dir; generates/merges `settings.json`; SSHA1 password hash |
| `CrawlerAddHandler.kt` (175) | Adds a crawler-DB torrent to transmission via magnet; sealed `CrawlerAddOutcome` (same file) maps to fixed HTTP statuses |
| `CrawlerApplyWatch.kt` / `CrawlerApplyStatus.kt` | Pure early-exit watch: detects crawler early exit during config apply |
| `CrawlerMaintenance.kt` (162) | One-shot crawler maintenance jobs (`MaintenanceAction`) |

**Subpackages:**

- `config/` — app settings pipeline: `AppConfig` (data classes), `ConfigStore` (SharedPreferences),
  `ConfigValidator`, `WizardSubmit` (form→outcome), `CrawlerGate` (pure wifi/enabled gating),
  `CrawlerKeys` (allow-list mirrored from crawler `config.c`), `TransmissionApplyMode`, `ApplyResult`.
- `net/` — `WifiGateMonitor` (default-network → wifi+ip), `WifiGateReducer` (pure), `FtpHangWatchdog`
  (TCP `220` greeting watchdog, injectable clock/sleeper).
- `storage/` — SAF/USB layer: `SafStorageGateway` (643; the single choke point: path→URI cache,
  per-path locks, read-only FTP cache, free-space executor), `UsbPermissionManager` (persisted tree
  URI), `SafIpcServer` (Unix-socket bridge, fds via `SCM_RIGHTS`), `SafIpcRequestHandler` /
  `FtpSafIpcRequestHandler`, `SafIpcCodec` (little-endian wire codec), `UsbFdBridge`, `SafModels`,
  `SafNames` (pure: FAT-illegal filename transliteration). `SafPaths`, `SafNames` and `SafIpcCodec`
  are pure (no Android deps) and unit-testable as plain JUnit.
- `ftp/` — `FtpCredentials` (user from store, password from settings), `FtpStatus`.
- `transmission/` — `TransmissionRpcClient` (narrow RPC surface on 127.0.0.1:9091, Basic auth + 409
  session-id handling, `TorrentAdder` interface) + summary/result models.

**Ports & sockets (defaults):** control dashboard **8090**; transmission RPC **9091**; crawler HTTP API
**8081 on loopback** (proxied by `CrawlerProxyServer`); FTP **2121** (wizard-configurable);
SAF bridge sockets at `filesDir/saf-bridge.sock` (transmission) and `filesDir/ftp-saf.sock` (FTP).

**WebUI control token:** 64-hex-char token in SharedPreferences `control_auth` (key `auth_token`,
generated on first use by `TokenManager.kt`). Read it from a connected phone:
`adb shell run-as com.torrent.app cat /data/data/com.torrent.app/shared_prefs/control_auth.xml`
(then open `http://<phone-ip>:8090/?token=<value>`).

**Transmission RPC credentials (this install):** username `torrent`, password `testtest`
(wizard-configured; also in SharedPreferences `app_config` keys `tx_username`/`tx_password`).
Query via `adb forward tcp:9091 tcp:9091` then `curl -u torrent:testtest http://127.0.0.1:9091/transmission/rpc`
(handle the 409 `X-Transmission-Session-Id` handshake).

## Native daemons & build wiring

All three native components are cross-compiled for `arm64-v8a` / `android-24` by Gradle tasks in
`app/build.gradle.kts` and shipped as renamed `lib*.so` in `jniLibs/` (legacy packaging; the `lib`
prefix is required by the packaging step — they are **exec'd as PIE binaries, never dlopen'd**).

| Output | Source | Built by | Role |
|---|---|---|---|
| `libtransmissiond.so` | transmission **4.1.2** tarball + `src/main/cpp/transmission-fork/` | `buildTransmission` → `apply_fork.py` → CMake/NDK | BitTorrent daemon; disk I/O routed to SAF gateway via `/saf/usb` sentinel path |
| `libdhtcrawler.so` | vendored `src/main/cpp/dht_crawler/` | `buildDhtCrawler` → `prepareDhtCrawlerSrc` (copy + 15 patches) → CMake | DHT keyspace crawler; SQLite DB; civetweb HTTP API; bloom-filter dedup |
| `libftpserver.so` | `src/main/cpp/ftp-server/` (C11) | `buildFtpServer` → CMake/NDK | FTP server for media clients; posix or SAF backend (SAF talks to `SafIpcServer`) |

**Task graph:** `buildMbedtls` (mbedTLS **3.6.6**) → `buildCurl` (curl **8.20.0**) → `buildTransmission`;
`buildLibuv` (**1.48.0**) + `fetchSqlite` (**3.49.1** amalgamation) + `prepareDhtCrawlerSrc` →
`buildDhtCrawler`; `fetchLlamaCpp` (llama.cpp pin `18ab62a8ec5144ae5d2f65e78374ba69652d75dd`, tarball
sha256 `34bd70e229bf0439575b7865e986209d23584bd35274d95c256f9ccf398ab46a`) → `buildLlama` →
`buildDhtCrawler`;
`buildFtpServer` (leaf). `copyDhtCrawlerAssets` copies the crawler's keyword files and both model
generations (~504 MiB, ~6 s) into
`build/generated/assets/dht_crawler/` (wired into merge assets). `afterEvaluate` makes
`mergeDebugJniLibFolders` depend on all three build tasks. `-PdhtCrawlerSrc=<path>` overrides the
crawler source root. Native build inputs/outputs land under `app/build/native/`.

**Movie-kind classifier (v10).** The crawler links a CPU-only llama.cpp (`buildLlama`) and the vendored
`dht_crawler-android/utf8proc/` (**2.12.0**); the NDK wrapper defines `MK_ENABLED` and
`MK_NFC_ENABLED` only when `LLAMA_INSTALL` is set, so a plain host configure stays plain C99. Two
model generations ship side by side: `models/v6` (fastText, pinned by `classifier_model_dir`) and
`models/v10` (Qwen3.5-0.8B nemotron arm decoder — Q4_0 backbone 501,452,416 B / 478.2 MiB, sha256
`02555750…`, plus the JEVH head 65,712 B, 1024×16, sha256 `077aaf11…`, hidden 1024 — pinned by
`movie_kind_model_dir`). `ensureAssets` stages both, the sweep keeps the active *set* `{v6, v10}` and
removes retired dirs, and `.assets_version` is `<versionCode>|models/v6|models/v10` — a stamp change is
what triggers restaging, no versionCode bump required; the staged models total 528,149,134 B (~504 MiB)
and the debug APK measures 541,786,478 B at `versionCode` 2 / `versionName` "0.2.0".
Pooling is last-token: `src/movie_kind.c` sets `context_params.pooling_type = LLAMA_POOLING_TYPE_LAST`,
the read stays `llama_get_embeddings_seq(ctx, 0)`, and `embd_normalize` is not set — at the llama.cpp
pin the pooled vector comes back unnormalised, which is what the v10 head was fitted on. v9 shipped the
CLS/`llama_get_embeddings()` pairing, whose P0 (one constant mask on every name) was invisible to host
tests, review and `/stats`, so the on-device mask-parity spot check is mandatory before any production
write is trusted.
Classification happens inline at ingest: `movie_kind_classify()` is called in
`tree_metadata_worker_func` between the survivor classification block and the adult drop — outside
`db->mutex` and outside `batch_writer_add` — for movie category + `adult = 0` + `movie_kind_enabled()`
rows only. The verdict rides `torrent_metadata_t` (`movie_kind_mask`, `has_movie_kind`,
`movie_kind_clf_ver`) into the 14-column `torrent_class` INSERT, stamped `movie_kind_clf_ver` = 10; a
classify failure stores the row unlabelled (both columns NULL) and is non-fatal. The backfill daemon is
**not** started in production — only the `movie_backfill_daemon_start` call in `src/main.c` was removed,
while `request_shutdown`/`destroy` stay and are no-op-safe when never started; the engine,
`--movie-kind-backfill`, `--reset`, `--movie-kind-bench`, the tests and the `/stats
movie_kind_backfill` block stay compiled, so that block reads `idle`/disabled permanently and the
dashboard panel is left as-is on purpose.
`/stats` carries `movie_kind` (enabled/status/rows/model shas) and `movie_kind_backfill`
(state/rows_processed/rows_total/failures/thermal_pauses) beside the unchanged fastText
`classifier`/`classifier_backfill`. The `movie-kind-bench` maintenance action runs
`--movie-kind-bench data/torrents.db` (every maintenance action stops the supervised crawler first);
the reset one-shot is `--movie-kind-backfill <db> --reset` with the app force-stopped. G6 bars are
median ≤ 300 ms, p95 ≤ 600 ms, peak RSS ≤ 1.2 GiB and the 478.2 MiB backbone file — the on-device bench
has not been run yet, so no measured figure is quoted here.

**dht_crawler patches (order is fixed, applied by `prepareDhtCrawlerSrc`):** the 15-entry list in
`app/build.gradle.kts` opens with the three upstream fixes — `bencode-cap-fix.patch` →
`bencode-init-fix.patch` → `civetweb-stop-flag-atomic.patch` — and closes with
`movie-kind-v10-streaming.patch` (last-token pooling + inline ingest classification); new patches go
LAST. Upstream fixes belong in `patches/`.
See the vendored repo's `CLAUDE.md` for its architecture (thread trees, bloom filters, schema).

## Tests

- Location: `app/src/test/java/com/torrent/app/` mirroring main packages. Run with
  `./gradlew :app:testDebugUnitTest` (same JDK/env as above). There is **no `androidTest`**.
- JUnit 4.13.2; Robolectric 4.14.1 for `Context`/prefs-dependent tests; Mockito 5.12.0.
- House style: extract logic into pure classes with **no Android deps** (plain JUnit4), or use
  constructor injection (gateways/RPC/executors as `() -> T` lambdas, injectable clock/sleeper).
  Robolectric runs with `isReturnDefaultValues = true` (prod code calls `android.util.Log`).

## Invariants — respect these when changing code

1. **Submodules first:** run `git submodule update --init --recursive` inside
   `app/src/main/cpp/dht_crawler/` before building — `prepareDhtCrawlerSrc` hard-fails without it.
2. **Never edit vendored sources** (`dht_crawler/lib/`, tarball-extracted transmission): changes are
   wiped on rebuild. Crawler fixes go in `dht_crawler/patches/`; transmission changes go in
   `transmission-fork/apply_fork.py` (idempotent, marker-gated) or its fork files.
3. **Exec binaries in place** from `nativeLibraryDir` — do not extract to a writable work dir
   (W^X / SELinux `apk_data_file`); both `ProcessSupervisor` and `OneShotRunner` rely on this.
4. **`jniLibs/arm64-v8a/*.so` are gitignored build outputs** (only `.gitkeep` is tracked);
   `mergeDebugJniLibFolders` depends on `buildTransmission`/`buildDhtCrawler`/`buildFtpServer`,
   so every build regenerates them — after native source changes, re-run the Gradle build tasks.
5. **`mergeSettingsJson` overwrites only app-managed keys**; daemon-owned session keys must be
   preserved verbatim (a full overwrite would reset live state).
6. **SAF locking:** per-path `ReentrantLock` always acquired descendant→ancestor; all SAF access
   goes through `SafStorageGateway` (single choke point).
7. **Crawler settings form is allow-listed** by `CrawlerKeys` — path/backup/http_* keys are rejected.
8. **Auth:** token checks are constant-time (`TokenManager`); `ControlServer`/`CrawlerProxyServer`
   share `resolveAuthToken` (cookie > `?token=` > `Authorization`); the proxy strips `control_token`
   and never forwards `Authorization` to the crawler.
9. **Crawler apply watch:** baseline `restartCount` must be taken from the launch the apply actually
   started, not pre-`start()` (past bug: every apply was rolled back) — see `CrawlerApplyWatch` KDoc.
10. **Shared data dir** drives both the transmission download dir and the FTP root.
11. **Vendored `dht_crawler/config.ini` contains live backup credentials/hosts.** Never print,
    commit, or copy its values; the sanitized template is
    `app/src/main/assets/dht_crawler/config.ini.default` (loopback bind, backups disabled).
12. **New servers/executors are wired in `DaemonService`** — it is the single composition point.
13. **FAT-illegal filenames are aliases, not collisions.** The USB volume is exFAT behind
    `ExternalStorageProvider`, which rewrites `" * / : < > ? \ |` and control chars to `_` on
    create. `SafNames.sanitize` mirrors that rewrite; `ensureDocument` accepts a created document
    whose name equals the sanitized leaf and caches it under the *requested* path, and both resolve
    walks match the sanitized leaf so the alias survives cache loss. Only a provider `" (1)"` dedup
    suffix is a real collision (deleted + loud failure). Rejecting transliteration makes torrents
    with such files unsaveable (torrent pauses with `I/O error (5)` forever).

## tools/ scripts

- `tools/ftp-host-test.sh` — host smoke test: builds `ftp-server/host` (posix + SAF), runs the daemon
  on `127.0.0.1:22121`, asserts ~14+ curl cases (LIST/REST/ABOR/…), then a SAF-backend pass against
  a python3 mock of the `SafIpcServer` wire protocol.
- `tools/usb-io/rpc_probe.sh USER PASS [SECONDS]` — transmission RPC latency probe over
  `adb forward tcp:9091` (p50/p99/max).
- `tools/usb-io/sample_threads.sh [SECONDS]` — samples `/proc/<pid>/task/*` for the daemon's
  `tr-*` threads via `run-as com.torrent.app` (D-state, pread/pwrite stalls).
- `tools/rebuild-db-v2.sh --source <v1.db> --target <v2.db>` — rebuilds a crawler database into
  schema v2 (drops the v1 trigram `file_search*`, re-derives the contentless `torrent_docs`) and
  verifies it; `--check-schema` diffs the crawler DDL against `tools/schema-v2.sql`, `--verify-only`
  re-runs the checks on an existing target. See *Rebuilding the search database* below.
- `tools/push-torrents-db.sh [SOURCE_DB]` — streams a crawler database into the phone's
  `files/crawler/data/torrents.db` in resumable, size-verified 1 GiB chunks (aborts if the crawler
  is running; `ANDROID_SERIAL` picks a device).

## Rebuilding the search database

The v1 database carried a 51.2 GB trigram `file_search*` index. Schema v2 drops it and keeps the base
and `torrent_docs` FTS tables: the phone snapshot `~/torrents-phone.db` (82,977,325,056 B, ~83 GB, WAL
checkpointed) rebuilds to **28.57 GiB** — 30,675,599,360 B on disk, 30,670,880,768 B by `dbstat`,
with the `torrent_docs*` shadows at 4,325,539,840 B (4.03 GiB; the design README's 6.4 GB was a
sample-based estimate). Preserved: `torrents` 6,098,645 · `torrent_files` 286,750,070 ·
`path_prefixes` 11,210,522 · `torrent_class` 6,098,645 · `classifier_meta` 6, `torrent_docs` = the
torrents count, `file_search%` = 0, `sqlite_sequence` high-water marks (34,776,141 / 1,424,727,769 /
11,210,522). A four-term `torrent_docs` match-set INSERT is **16–19 ms** (627 rows) where the v1
trigram+GLOB candidate scan took **16.5–21.7 s**.

**Rebuild (host, `sqlite3` ≥ 3.43).** ~44 min full (26 copy + ~20 `torrent_docs` chunks + 5.5 VACUUM
plus ~10 integrity check); a `--verify-only` re-check is ~17 min.

```bash
./gradlew :app:prepareDhtCrawlerSrc && make -j"$(nproc)" -C app/build/native/src/dht_crawler
tools/rebuild-db-v2.sh --check-schema
tools/rebuild-db-v2.sh --source ~/torrents-phone.db --target ~/torrents-v2.db
tools/rebuild-db-v2.sh --verify-only --source ~/torrents-phone.db --target ~/torrents-v2.db
```

The first line is optional — the script builds a missing/stale crawler binary itself (it needs the
JDK 21 env above). It reads the source `mode=ro`, builds the target schema from the crawler's own
`--print-schema` output (never from the committed baseline; `--check-schema` only diffs the two), and
refuses an existing target or a non-empty source `-wal`. One result looks like a failure but is
intentional: 72,866 pre-existing `torrent_files` orphans (1,212 missing parents) are copied verbatim,
so the FK check compares the target's violation set against the source's instead of demanding zero.

**Upload (manual — never unattended, and only after the PC-side checks pass).** The phone has ~115 GB
free against an 83 GB old DB plus a 28.6 GiB new one, so free space first: deleting the phone DB is
safe because `~/torrents-phone.db` is the rollback copy.

```bash
adb -s 51404fe4 shell am force-stop com.torrent.app
adb -s 51404fe4 exec-out run-as com.torrent.app sh -c 'cd files/crawler/data && rm -f torrents.db torrents.db-wal torrents.db-shm torrents.db.incoming torrents.db.incoming.meta'
ANDROID_SERIAL=51404fe4 tools/push-torrents-db.sh /home/adam/torrents-v2.db   # ~40+ min, app stopped
adb -s 51404fe4 shell am start -n com.torrent.app/.MainActivity
```

Keep the app force-stopped through the push: the script assembles `torrents.db.incoming` and swaps it
in only after the full byte count verifies.

**Verify on the phone** (there is no `sqlite3` binary on the device):

```bash
adb -s 51404fe4 exec-out run-as com.torrent.app du -h files/crawler/data/torrents.db   # ≤ 40 GiB
adb forward tcp:8081 tcp:8081
curl -s http://127.0.0.1:8081/stats        # torrents_indexed ≈ 6,098,645 within ~15 s
adb -s 51404fe4 exec-out run-as com.torrent.app grep -cE 'SQLITE_CORRUPT|database disk image is malformed' files/logs/dht_crawler.log   # 0
```

**Rollback** — keep `~/torrents-phone.db` until the phone-side criteria pass:

```bash
adb -s 51404fe4 shell am force-stop com.torrent.app
adb -s 51404fe4 exec-out run-as com.torrent.app sh -c 'cd files/crawler/data && rm -f torrents.db torrents.db-wal torrents.db-shm torrents.db.incoming torrents.db.incoming.meta'
ANDROID_SERIAL=51404fe4 tools/push-torrents-db.sh /home/adam/torrents-phone.db
adb -s 51404fe4 shell am start -n com.torrent.app/.MainActivity
```

The swap keeps the replaced file as `torrents.db.bak`; delete it once the new database is confirmed
good: `adb -s 51404fe4 exec-out run-as com.torrent.app sh -c 'rm files/crawler/data/torrents.db.bak'`.

<!-- gortex:communities:start -->
## Community Skills

| Area | Description | Explore |
|------|-------------|---------|
| Dht Crawler Src Log Msg | 293 symbols | `analyze(operation:"communities", id:"community-24")` |
| App Storage 2 Dirs Safipccodec | 266 symbols | `analyze(operation:"communities", id:"community-80")` |
| App Backup 2 Dirs Dbbookmark | 199 symbols | `analyze(operation:"communities", id:"community-67")` |
| Cpp Dht Crawler Android | 193 symbols | `analyze(operation:"communities", id:"community-3")` |
| App Config 10 Dirs | 179 symbols | `analyze(operation:"communities", id:"community-77")` |
| App Backup 3 Dirs | 170 symbols | `analyze(operation:"communities", id:"community-69")` |
| App Config 4 Dirs | 167 symbols | `analyze(operation:"communities", id:"community-134")` |
| App Config 5 Dirs | 158 symbols | `analyze(operation:"communities", id:"community-135")` |
| App S3 2 Dirs | 144 symbols | `analyze(operation:"communities", id:"community-143")` |
| App Wizard 4 Dirs | 137 symbols | `analyze(operation:"communities", id:"community-86")` |
| Torrent App 1 Dirs Controlserver | 135 symbols | `analyze(operation:"communities", id:"community-96")` |
| Torrent App 2 Dirs Crawlerconfig | 131 symbols | `analyze(operation:"communities", id:"community-102")` |
| 1 Dirs Get | 89 symbols | `analyze(operation:"communities", id:"community-157")` |
| App Backup 1 Dirs Runcheck | 87 symbols | `analyze(operation:"communities", id:"community-65")` |
| App Storage 1 Dirs Renamedoc | 70 symbols | `analyze(operation:"communities", id:"community-82")` |
| App Backup 1 Dirs Sqlvalue | 67 symbols | `analyze(operation:"communities", id:"community-74")` |
| App Config 1 Dirs Submit | 67 symbols | `analyze(operation:"communities", id:"community-139")` |
| Torrent App 1 Dirs Crawlerproxyserver | 63 symbols | `analyze(operation:"communities", id:"community-104")` |
| Torrent App 4 Dirs Start | 58 symbols | `analyze(operation:"communities", id:"community-50")` |
| Cpp Ftp Server Reply | 54 symbols | `analyze(operation:"communities", id:"community-40")` |

<!-- gortex:communities:end -->
