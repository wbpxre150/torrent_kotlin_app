# AGENTS.md

**What this is:** an Android app (`com.torrent.app`, single Gradle module `:app`) that runs four
native daemons on-device — a forked **transmission-daemon**, the vendored **dht_crawler**, a
hand-written **FTP server** and a **cloudflared** tunnel — supervised by Kotlin, fronted by a Ktor
control dashboard, with all disk I/O routed through Android's Storage Access Framework (SAF) via a
Unix-socket fd bridge.

This file is architecture, invariants and gotchas. Benchmarks, gate results and dated device
snapshots live in [`plan/measured-evidence.md`](plan/measured-evidence.md).

## Build

The system default JDK is 26. Android Gradle Plugin 8.8.2 does not support JDK 26, so **always**
build with JDK 21:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
./gradlew installDebug          # tests: ./gradlew :app:testDebugUnitTest
```

## Layout

```text
torrent_kotlin_app/
├── AGENTS.md / README.md / LICENSE
├── build.gradle.kts / settings.gradle.kts / gradle.properties   # root Gradle config
├── docs/screenshots/                 # README images
├── plan/                             # design + measurement records (see Measured evidence)
├── app/                              # the only module (:app)
│   ├── build.gradle.kts              # Android config + ALL native cross-compile tasks
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   # MainActivity (launcher) + DaemonService (FGS specialUse)
│       │   ├── java/com/torrent/app/ # Kotlin sources (map below)
│       │   ├── cpp/
│       │   │   ├── dht_crawler/          # vendored git repo (own .git + lib/ submodules)
│       │   │   ├── dht_crawler-android/  # CMake wrapper + compat/openssl mbedTLS shim + utf8proc
│       │   │   ├── transmission-fork/    # apply_fork.py + SAF fork sources
│       │   │   └── ftp-server/           # hand-written C11 FTP daemon (+ host/ test build)
│       │   ├── assets/dht_crawler/config.ini.default   # sanitized crawler config template
│       │   └── jniLibs/arm64-v8a/    # gitignored lib*.so build outputs (exec'd, not dlopen'd)
│       └── test/java/com/torrent/app/ # unit tests mirroring main packages (NO androidTest)
└── tools/
    ├── ftp-host-test.sh              # host smoke test for the FTP daemon
    ├── rebuild-db-v2.sh              # crawler DB schema v2 rebuild/verify
    ├── push-torrents-db.sh           # chunked DB push to the phone
    ├── crawler/gp_probe.py           # /stats window probe for get_peers + metadata
    └── usb-io/{rpc_probe.sh, sample_threads.sh}   # ADB diagnostics for transmission
```

## Kotlin app layer (`app/src/main/java/com/torrent/app/`)

**Root package** — orchestration + native process layer (line counts drift; treat them as scale
hints, not facts):

| File | Role |
|---|---|
| `DaemonService.kt` (~2.7k) | The hub: foreground service that wires the collaborators in `onCreate` (servers, executors, gateways); sealed `ServiceState`; one single-thread executor per supervised process so lifecycle ops never block the main thread |
| `ControlServer.kt` (~5.7k) | Ktor/CIO dashboard, JSON API and WebSocket push on `0.0.0.0:8090` (auth via `control_token` cookie); serves `/`, `/downloads`, `/search`, `/stats`, `/torrent`, `/settings/{transmission,ftp,storage,crawler}`, `/api/*`, `/crawler/maintenance/{action}` |
| `MainActivity.kt` (~370) | Compose UI: USB grant, dashboard URL + QR, start/shutdown; first-run wizard; binds `DaemonService` via `ServiceConnection` |
| `CrawlerProxyServer.kt` (~300) | Ktor/CIO authed reverse proxy to the crawler's loopback HTTP API on 8081; strips auth when forwarding |
| `NativeProcess.kt` | Shared exec primitives — runs binaries **in place from `nativeLibraryDir`** |
| `ProcessSupervisor.kt` (~470) | Supervises one child with restart/backoff; graceful SIGTERM; serialized start/stop |
| `OneShotRunner.kt` | Single run of a native binary, cancellable (SIGTERM→SIGKILL) — maintenance steps and `--fetch-models` |
| `GracefulShutdownPlanner.kt` / `ShutdownExitGate.kt` | Pure shutdown ordering and exit gating |
| `RollbackPolicy.kt` | Pure threshold-based rollback decision |
| `TokenManager.kt` | Constant-time control-token validation |
| `CrawlerConfig.kt` | Parses/writes crawler `config.ini`; atomic writes; pinned keys (lives in the root package, not `config/`) |
| `CrawlerModels.kt` | `--fetch-models` orchestration, timeouts and failure retry |
| `TransmissionConfig.kt` | Owns the `transmission/` config dir; generates/merges `settings.json`; SSHA1 password hash |
| `CrawlerAddHandler.kt` | Adds a crawler-DB torrent to transmission via magnet; sealed `CrawlerAddOutcome` maps to fixed HTTP statuses |
| `CrawlerApplyWatch.kt` / `CrawlerApplyStatus.kt` | Pure early-exit watch: detects crawler early exit during a config apply |
| `CrawlerMaintenance.kt` | One-shot crawler maintenance jobs (`MaintenanceAction`) |
| `CrawlerRestartPolicy.kt` / `MaintenanceProgress.kt` | Restart backoff policy; `[MPROG]` progress parsing |

**Subpackages:**

- `config/` — settings pipeline: `AppConfig` (data classes), `ConfigStore` (SharedPreferences, ~530),
  `ConfigValidator`, `WizardSubmit` (form→outcome), `CrawlerGate` (pure wifi/enabled gating),
  `CrawlerKeys` (allow-list mirrored from crawler `config.c`), `TransmissionApplyMode`, `ApplyResult`.
- `net/` — `WifiGateMonitor` (default-network → wifi+ip), `WifiGateReducer` (pure), `FtpHangWatchdog`
  (TCP `220` greeting watchdog, injectable clock/sleeper), `CloudflaredTunnel`.
- `storage/` — SAF/USB layer: `SafStorageGateway` (~720; the single choke point: path→URI cache,
  per-path locks, read-only FTP cache, free-space executor), `UsbPermissionManager` (persisted tree
  URI), `SafIpcServer` (Unix-socket bridge, fds via `SCM_RIGHTS`), `SafIpcRequestHandler` /
  `FtpSafIpcRequestHandler`, `SafIpcCodec` (little-endian wire codec), `UsbFdBridge`, `SafModels`,
  `SafNames` (pure: FAT-illegal filename transliteration). `SafPaths`, `SafNames` and `SafIpcCodec`
  are pure (no Android deps) and unit-testable as plain JUnit.
- `backup/` — crawler DB backup/restore: `DbBackupManager` (~880), `DbRestoreManager` (~810),
  `Bep51BackupManager`, `Bep51BootstrapDownloader`, `CrawlerDbDumper`, `CrawlerDbSchema` (the
  exact-string mirror of the crawler DDL — see invariant 14), `SqlDumpWriter`,
  `SqlStatementSplitter`, `SqliteRestoreSink`, `RestoreDiskGuard`, `RestoreCategoryFilter`,
  `BootstrapStatus`.
- `s3/` — `S3Client` (~470) + `SigV4` for the R2 backup bucket.
- `wizard/` — first-run flow: `WizardFlow`, `WizardNav`, `WizardStep`, `WizardFormViewModel`,
  `StepScreens`, `StepDbDownload`, `RestoreProgressScreen`, `RestoreProgress`.
- `ftp/` — `FtpCredentials` (user from store, password from settings), `FtpStatus`.
- `transmission/` — `TransmissionRpcClient` (narrow RPC surface on 127.0.0.1:9091, Basic auth + 409
  session-id handling, `TorrentAdder` interface), `SelectionWatcher`, summary/result models.
- `ui/theme/` — `TorrentTheme`.

**Ports & sockets (defaults):** control dashboard **8090**; transmission RPC **9091**; crawler HTTP
API **8081 on loopback** (proxied by `CrawlerProxyServer`); FTP **2121** (wizard-configurable); SAF
bridge sockets at `filesDir/saf-bridge.sock` (transmission) and `filesDir/ftp-saf.sock` (FTP).
cloudflared makes an outbound tunnel only (public hostname is wizard-configured).

**WebUI control token:** 64-hex-char token in SharedPreferences `control_auth` (key `auth_token`,
generated on first use by `TokenManager.kt`). Read it from a connected phone:

```bash
adb shell run-as com.torrent.app cat /data/data/com.torrent.app/shared_prefs/control_auth.xml
# then open http://<phone-ip>:8090/?token=<value>
```

The phone UI shows the same URL as text and a QR code, so **any screenshot of `MainActivity` leaks
the token** — redact before publishing.

**Transmission RPC credentials** are wizard-configured per install; read them from SharedPreferences
`app_config` keys `tx_username` / `tx_password`. Query via `adb forward tcp:9091 tcp:9091` then
`curl -u <user>:<pass> http://127.0.0.1:9091/transmission/rpc` (handle the 409
`X-Transmission-Session-Id` handshake).

## Native daemons & build wiring

All four native components are cross-compiled for `arm64-v8a` / `android-24` by Gradle tasks in
`app/build.gradle.kts` and shipped as renamed `lib*.so` in `jniLibs/` (legacy packaging; the `lib`
prefix is required by the packaging step — they are **exec'd as PIE binaries, never dlopen'd**).

| Output | Source | Built by | Role |
|---|---|---|---|
| `libtransmissiond.so` | transmission **4.1.4** tarball + `src/main/cpp/transmission-fork/` | `buildTransmission` → `apply_fork.py` → CMake/NDK | BitTorrent daemon; disk I/O routed to the SAF gateway via the `/saf/usb` sentinel path |
| `libdhtcrawler.so` | vendored `src/main/cpp/dht_crawler/` | `buildDhtCrawler` → `prepareDhtCrawlerSrc` (copy + 3 submodule patches) → CMake | DHT keyspace crawler; SQLite DB; civetweb HTTP API; bloom-filter dedup |
| `libftpserver.so` | `src/main/cpp/ftp-server/` (C11) | `buildFtpServer` → CMake/NDK | FTP server for media clients; posix or SAF backend (SAF talks to `SafIpcServer`) |
| `libcloudflared.so` | cloudflared **2026.11.0** tarball | `fetchCloudflaredSrc` → `buildCloudflared` | Outbound tunnel for the dashboard |

**Task graph:** `buildMbedtls` (mbedTLS **3.6.6**) → `buildCurl` (curl **8.20.0**) →
`buildTransmission`; `buildLibuv` (**1.48.0**) + `fetchSqlite` (**3.49.1** amalgamation) +
`prepareDhtCrawlerSrc` → `buildDhtCrawler`; `fetchLlamaCpp` (llama.cpp pin
`18ab62a8ec5144ae5d2f65e78374ba69652d75dd`) → `buildLlama` → `buildDhtCrawler`; `buildFtpServer` and
`fetchCloudflaredSrc` → `buildCloudflared` are leaves. `copyDhtCrawlerAssets` copies the crawler's
keyword files (~1 MB) into `build/generated/assets/dht_crawler/` (wired into merge assets) — the
classifier weights are **not** assets. `afterEvaluate` makes `mergeDebugJniLibFolders` depend on all
four build tasks. `-PdhtCrawlerSrc=<path>` overrides the crawler source root. Native build
inputs/outputs land under `app/build/native/`.

## Classifiers

Both engines run in the crawler and are **downloaded, never bundled**. `DaemonService.maybeFetchCrawlerModels`
runs `dht_crawler --fetch-models` through a dedicated `OneShotRunner` **before** the crawler starts
and blocks that start (not the service) until it exits, bounded by `CrawlerModels.FETCH_TIMEOUT_MS`
(12 min). A non-zero exit, launch failure or timeout still starts the crawler — inert — and retries
after `FAILURE_RETRY_INTERVAL_MS` (1 h). The wait is deliberate: models load only at process start,
so starting first would stamp `clf_ver` 0 on every ingested row for the whole session.

- **CRAM v15** (`classifier_model_dir` = `models/v15`) scores **all three** fields in one forward
  pass: `category` (masked 48-state argmin over `(cat, kind, adult)` energies), `tv_kind` (the
  `kind_W` head) and `adult` (the joint's raw adult marginal). `adult = adult_p >= 0.375`. No
  `.ftz`/fastText path exists. `clf_ver` == `adult_clf_ver` == 15.
- **Movie-kind v13** (`movie_kind_model_dir` = `models/v13`) is llama.cpp + a JEVH head, run inline
  at ingest for movie + `adult = 0` rows only, stamping `movie_kind_clf_ver` 13.
- Generation is **manifest-derived** (`"version": "v13"` → 13); a bump needs no code change.

Four properties are contracts, not tuning knobs — details and the measurements behind them are in
[`plan/measured-evidence.md`](plan/measured-evidence.md):

1. **`E` is a cost.** `P = softmax(−E)`, answer = argmin, `adult_p` high = adult.
2. **The 48-state masked argmin is the category model.** The bare category unary is ~9 pp worse and
   must never ship.
3. **The 50-file cap is a serving contract.** Training features are first-50-file features;
   `classifier_classify()` caps `n_files` at 50 before `tp_parse`. The full list still goes to the
   DB and to movie_kind.
4. **The serving path strips `__adult_*` tokens** (`cram_strip_adult_tokens`) because the frozen
   model was trained without them. Permanent shipped property.

Degraded is **all-or-nothing**: a missing/mismatched manifest or container fails `cram_load()`
non-fatally, increments `classifier.model_load_failures` + `cram.load_failures`, and the classifier
returns `clf_ver` 0 with parser-hint values. There is no one-field fallback.

**Crawler flavours.** Plain `make` in the vendored repo compiles both engines **inert** (no
`CLF_ENABLED` / `MK_ENABLED`). `make clf` is CRAM only, `make llama` builds the pinned llama.cpp
into `build/llama-install`, `make full` is both, and `make models` runs `tools/fetch-models.sh` to
fetch and sha-verify the weights into `./models/<gen>` — the compiled-in default dirs, so no config
change is needed. The Android build sets those defines itself; `-PdhtCrawlerSrc` only changes which
source tree is copied.

## Tests

- Location: `app/src/test/java/com/torrent/app/` mirroring main packages. **103 files, ~1,358
  `@Test` methods.** Run with `./gradlew :app:testDebugUnitTest` (same JDK/env as above). There is
  **no `androidTest`**.
- JUnit 4.13.2; Robolectric 4.14.1 for `Context`/prefs-dependent tests; Mockito 5.12.0.
- House style: extract logic into pure classes with **no Android deps** (plain JUnit4), or use
  constructor injection (gateways/RPC/executors as `() -> T` lambdas, injectable clock/sleeper).
  Robolectric runs with `isReturnDefaultValues = true` (prod code calls `android.util.Log`).
- The crawler has its own hermetic `make tests` (12 targets in the aggregate, 15 `tests-*` targets
  with the ASan variants) — see the vendored repo's `AGENTS.md`.

## Invariants — respect these when changing code

1. **Submodules first:** run `git submodule update --init --recursive` inside
   `app/src/main/cpp/dht_crawler/` before building — `prepareDhtCrawlerSrc` hard-fails without it.
2. **Never edit vendored sources** (`dht_crawler/lib/`, tarball-extracted transmission/cloudflared):
   changes are wiped on rebuild. Crawler fixes are normal commits **in the `dht_crawler` repository
   itself** (it is a fork, `wbpxre150/dht_crawler`); fixes to its `lib/` submodules go in
   `dht_crawler/patches/` and are applied by `prepareDhtCrawlerSrc` — exactly three such patches
   exist today, and a new submodule fix goes **LAST, as the 4th slot**; transmission changes go in
   `transmission-fork/apply_fork.py` (idempotent, marker-gated) or its fork files.
3. **Exec binaries in place** from `nativeLibraryDir` — do not extract to a writable work dir
   (W^X / SELinux `apk_data_file`); both `ProcessSupervisor` and `OneShotRunner` rely on this.
4. **`jniLibs/arm64-v8a/*.so` are gitignored build outputs** (only `.gitkeep` is tracked);
   `mergeDebugJniLibFolders` depends on the four build tasks, so every build regenerates them —
   after native source changes, re-run the Gradle build tasks.
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
    `app/src/main/assets/dht_crawler/config.ini.default` (loopback bind, backups disabled). It is
    also untracked in the crawler repo.
12. **New servers/executors are wired in `DaemonService`** — it is the single composition point.
13. **FAT-illegal filenames are aliases, not collisions.** The USB volume is exFAT behind
    `ExternalStorageProvider`, which rewrites `" * / : < > ? \ |` and control chars to `_` on
    create. `SafNames.sanitize` mirrors that rewrite; `ensureDocument` accepts a created document
    whose name equals the sanitized leaf and caches it under the *requested* path, and both resolve
    walks match the sanitized leaf so the alias survives cache loss. Only a provider `" (1)"` dedup
    suffix is a real collision (deleted + loud failure). Rejecting transliteration makes torrents
    with such files unsaveable (torrent pauses with `I/O error (5)` forever).
14. **A new `torrent_class` column means FIVE C places plus the Kotlin mirror:** the live CREATE,
    `database_ensure_column`, `batch_writer.c`'s backup DDL, `backup_ensure_column` and
    `tools/schema-v2.sql`, plus `backup/CrawlerDbSchema.kt` and its exact-string test. Miss one and
    backup/restore or `rebuild-db-v2.sh --check-schema` breaks — this is how `adult_clf_ver` landed.

## Gotchas

- `classifier_enabled` / `movie_kind_enabled` are parsed with `atoi`, so `= true` means **0 (off)**.
  Use `= 1`.
- `log_level = DEBUG` on the crawler writes ~1.9 GB/h — INFO for every device gate.
- **config beats manifest** for the adult threshold. Move `config.ini`, `config.ini.default`,
  `AppConfig.adultHideThreshold` and the manifest together or the device keeps a stale operating
  point (`ClassifierModelVersionMigration` protects the device, not the host).
- `.assets_version` is `<versionCode>|keywords` — it names the keyword payload only. Nothing under
  `models/` is ever deleted by the app; `--fetch-models` owns that directory.
- **Redact before publishing any screenshot.** `MainActivity` prints the dashboard URL with the
  control token, and the FTP card prints the FTP URL with its password.

## tools/ scripts

- `tools/ftp-host-test.sh` — host smoke test: builds `ftp-server/host` (posix + SAF), runs the daemon
  on `127.0.0.1:22121`, asserts ~14+ curl cases (LIST/REST/ABOR/…), then a SAF-backend pass against
  a python3 mock of the `SafIpcServer` wire protocol.
- `tools/usb-io/rpc_probe.sh USER PASS [SECONDS]` — transmission RPC latency probe over
  `adb forward tcp:9091` (p50/p99/max).
- `tools/usb-io/sample_threads.sh [SECONDS]` — samples `/proc/<pid>/task/*` for the daemon's
  `tr-*` threads via `run-as com.torrent.app` (D-state, pread/pwrite stalls).
- `tools/crawler/gp_probe.py` — reads the get_peers / metadata / peer-fail / BEP51 `/stats` blocks
  into a window table (legacy fallback for `metadata_success_rate_percent`).
- `tools/rebuild-db-v2.sh` — see below.
- `tools/push-torrents-db.sh [SOURCE_DB]` — streams a crawler database into the phone's
  `files/crawler/data/torrents.db` in resumable, size-verified 1 GiB chunks (aborts if the crawler
  is running; `ANDROID_SERIAL` picks a device).

## Rebuilding the search database

Schema v2 drops the v1 51.2 GB trigram `file_search*` index and keeps the base + `torrent_docs` FTS
tables. Measured sizes and timings are in
[`plan/measured-evidence.md`](plan/measured-evidence.md#search-database-rebuild-schema-v2).

```bash
./gradlew :app:prepareDhtCrawlerSrc && make -j"$(nproc)" -C app/build/native/src/dht_crawler
tools/rebuild-db-v2.sh --check-schema
tools/rebuild-db-v2.sh --source ~/torrents-phone.db --target ~/torrents-v2.db
tools/rebuild-db-v2.sh --verify-only --source ~/torrents-phone.db --target ~/torrents-v2.db
```

The first line is optional — the script builds a missing/stale crawler binary itself (it needs the
JDK 21 env above). It reads the source `mode=ro`, builds the target schema from the crawler's own
`--print-schema` output (never from the committed baseline; `--check-schema` only diffs the two),
and refuses an existing target or a non-empty source `-wal`. Pre-existing `torrent_files` orphans
are copied verbatim, so the FK check compares the target's violation set against the source's
instead of demanding zero.

**Upload is manual, never unattended, and only after the PC-side checks pass.** Keep the app
force-stopped through the push: the script assembles `torrents.db.incoming` and swaps it in only
after the full byte count verifies.

```bash
adb -s <serial> shell am force-stop com.torrent.app
adb -s <serial> exec-out run-as com.torrent.app sh -c 'cd files/crawler/data && rm -f torrents.db torrents.db-wal torrents.db-shm torrents.db.incoming torrents.db.incoming.meta'
ANDROID_SERIAL=<serial> tools/push-torrents-db.sh /home/adam/torrents-v2.db
adb -s <serial> shell am start -n com.torrent.app/.MainActivity
```

Verify on the phone (there is no `sqlite3` binary on the device):

```bash
adb -s <serial> exec-out run-as com.torrent.app du -h files/crawler/data/torrents.db   # ≤ 40 GiB
adb forward tcp:8081 tcp:8081 && curl -s http://127.0.0.1:8081/stats                  # torrents_indexed
adb -s <serial> exec-out run-as com.torrent.app grep -cE 'SQLITE_CORRUPT|database disk image is malformed' files/logs/dht_crawler.log   # 0
```

Rollback is the same procedure with `~/torrents-phone.db`; keep that snapshot until the phone-side
criteria pass. The swap leaves the replaced file as `torrents.db.bak` — delete it once the new
database is confirmed good.

## Measured evidence

Every figure that used to live in this file is in [`plan/`](plan/), dated and labelled with the
device it came from:

| Document | Contents |
|---|---|
| [`measured-evidence.md`](plan/measured-evidence.md) | Gate results G0–G8, Gate P, container geometry, accepted costs, APK diet, schema-v2 rebuild sizes, dated device snapshots |
| [`cram-v15-integration.md`](plan/cram-v15-integration.md) | CRAM v15 single-engine design and rollout |
| [`movie-kind-v13-integration.md`](plan/movie-kind-v13-integration.md) | Movie-kind v13 backbone/head integration |
| [`movie-kind-v10-streaming-integration.md`](plan/movie-kind-v10-streaming-integration.md) | Last-token pooling + inline ingest classification |
| [`adult-v11-integration.md`](plan/adult-v11-integration.md) | Token-gated adult backfill |
| [`getpeers-metadata-results.md`](plan/getpeers-metadata-results.md) | The seven 30-minute get_peers/metadata windows |
| [`getpeers-metadata-next-steps.md`](plan/getpeers-metadata-next-steps.md) | Open questions from those windows |
| [`pps-wifi-thermal-envelope.md`](plan/pps-wifi-thermal-envelope.md) | Packets-per-second and thermal envelope |

Two stale-figure traps when reading them: a "three daemons" count predates cloudflared, and any
`versionCode` 4 / `0.4.0` snapshot predates both the APK diet and the current install.

The crawler features that used to ship as `.patch` files are now ordinary commits in
`dht_crawler`'s own history, one per former patch, subjects prefixed `crawler:` (99 such commits
as of this writing) — so `git log --grep='^crawler:'` recovers the provenance of any crawler
behaviour this file only summarises. Only the three `lib/` submodule fixes remain as patches.

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
