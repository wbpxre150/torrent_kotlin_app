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
| `libdhtcrawler.so` | vendored `src/main/cpp/dht_crawler/` | `buildDhtCrawler` → `prepareDhtCrawlerSrc` (copy + 23 patches) → CMake | DHT keyspace crawler; SQLite DB; civetweb HTTP API; bloom-filter dedup |
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

**Movie-kind classifier (v13).** The crawler links a CPU-only llama.cpp (`buildLlama`) and the vendored
`dht_crawler-android/utf8proc/` (**2.12.0**); the NDK wrapper defines `MK_ENABLED` and
`MK_NFC_ENABLED` only when `LLAMA_INSTALL` is set, so a plain host configure stays plain C99. Two
model generations ship side by side: `models/v11` (fastText — see *fastText adult classifier (v11)*
below) and `models/v13` (same family as v10 — decoder backbone, Q4_0 501,452,416 B / 478.2 MiB, sha256
`28270fa01100687969b541282814371062ab9e1a560bb7ea755243c822cf0d4c`, plus the JEVH head 65,712 B,
1024×16, sha256 `2f994cbe792441be182717cc6faa46f3ff245db6d17af30d72cfc47bbefb7ae3`, hidden 1024, 16
labels, prompt `Torrent: {name}\nFilm genres:`, max 64 tokens, staged beside `manifest.json` 17,772 B
sha256 `e0474643…fb57` — pinned by `movie_kind_model_dir`). `CrawlerConfig.MOVIE_MODEL_SUBDIR =
"models/v13"` drives the staging source/dest, the stamp and that pin. `ensureAssets` stages both, the
sweep keeps the active *set* `{v11, v13}` and removes retired dirs, and `.assets_version` is
`<versionCode>|models/v11|models/v13` — a stamp change is what triggers restaging, no versionCode bump
required; the stamp is a crawler-level file (`files/crawler/.assets_version`, **not** under `models/`)
and currently reads `3|models/v11|models/v13`. The staged models total 528,148,052 B (~503.7 MiB —
`models/v11` 26,612,152 B + `models/v13` 501,535,900 B) and the debug APK measures 542,045,550 B at
`versionCode` 3 / `versionName` "0.3.0" — ~6.8 MB *smaller* than the 548,821,733 B v10 build because the
v13 weights deflate differently, despite identical uncompressed asset sizes.
Generation is manifest-derived: the manifest's top-level `"version": "v13"` (`mk_parse_generation`)
yields `movie_kind_clf_ver` 13 and `/stats movie_kind.generation` 13 with **no code change**; `genres[]`
order is byte-identical to v10 and `taxonomy.sha256` is unchanged at
`313dafd4a5fd27e35e15530cee5279c409a92d2cb818a3d4878cfc914f7148b4`.
Pooling is last-token: `src/movie_kind.c` sets `context_params.pooling_type = LLAMA_POOLING_TYPE_LAST`,
the read stays `llama_get_embeddings_seq(ctx, 0)`, and `embd_normalize` is not set — at the llama.cpp
pin the pooled vector comes back unnormalised, which is what the v13 head was fitted on, as v10 was. v9
shipped the
CLS/`llama_get_embeddings()` pairing, whose P0 (one constant mask on every name) was invisible to host
tests, review and `/stats`, so the on-device mask-parity spot check is mandatory before any production
write is trusted.
Classification happens inline at ingest: `movie_kind_classify()` is called in
`tree_metadata_worker_func` between the survivor classification block and the adult drop — outside
`db->mutex` and outside `batch_writer_add` — for movie category + `adult = 0` + `movie_kind_enabled()`
rows only. The verdict rides `torrent_metadata_t` (`movie_kind_mask`, `has_movie_kind`,
`movie_kind_clf_ver`) into the 14-column `torrent_class` INSERT, stamped `movie_kind_clf_ver` = 13; a
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
median ≤ 300 ms, p95 ≤ 600 ms, peak RSS ≤ 1.2 GiB and the 478.2 MiB backbone file.
**G6 measured on b078fce — the first real on-device movie-kind measurement (v10's was never run).** The
bench `--movie-kind-bench data/torrents.db 500` reports median **188.843 ms**, p95 **284.400 ms**,
`vmhwm_kib` **1,149,368** (= 1,122.4 MiB), max CPU 76 °C, threads 4, 3000 samples over 627 s, failures
0, generation 13: PASS on median, p95 and peak RSS on **both** bar bases (1,228,800 KiB reported-KiB and
1,171,866 KiB = 1,144.4 MiB strict-GB), and the 478.2 MiB file passes the 600 MiB bar. A sustained live
window (35.5 min, 8 samples, the crawler ingesting 22,036 → 29,103 torrents) settled at
`mean_us_per_item` ≈ **254 ms** (window mean 302.7 ms, steady-state mean 261.2 ms after warm-up) with
`thermal_pauses` 0 and failures 0 at every sample, 59.0–67.3 °C. A distinct quantity, never conflated
with the classifier's pre-registered bench figure: the **whole crawler process** live `VmHWM` reached
2,044,464 KiB ≈ 1.95 GiB (model + crawl state + SQLite page cache; already 1.70 GiB before the
measurement).
**Gate P (mask parity) is mandatory before any production write** — the v9 P0 class (one constant mask
on every name) is invisible to host tests, review and `/stats`. It ran and PASSED under an
operator-revised cross-architecture bar: ≥98 % identical label sets, measured **490/500 = 98.00 %** and
**986/1000 = 98.6 %** (≤64-token subset 985/995 = 99.0 %), every mismatch within Hamming 4 (N=1000: 11
rows at 1, 2 at 2, 1 at 4) with flips in **both** directions, 122/165 distinct masks (no constant-mask
P0 signature), the decomposed-Unicode row agrees, zero classify failures, and three independent phone
bench runs are bit-identical (determinism). The original 500/500 empty-diff letter is recorded as
**FAILED** (commit 3e592d7); the bar revision is a recorded operator decision, not a silent pass. The
residual is arm64-vs-x86 float32 accumulation at the head thresholds.
**Device state after the D1 reset + full backfill (b078fce, single generation):** 21,404 torrents,
**9,144** adult0-movie rows all at `movie_kind_clf_ver` = 13, zero at 10, zero NULL; `classifier_meta`
`movie_kind_clf_ver` = 13 and `movie_backfill_last_id` = `''`. The reset cleared 9,144 rows (it clears
every `movie_kind_clf_ver >= 9`, which is why it runs **exactly once**, app force-stopped, before the
pass) and the pass then relabelled all 9,144 with `updated=9144 skipped=0 failed=0 thermal_pauses=0` in
~29 min; log corruption count 0. The backfill's 500-row `--dump-masks` dump was byte-identical to the
pre-write gate-P dump.
Carried forward, unchanged: the accepted G3 `none`-recall miss (**0.7368** vs the 0.85 bar — recorded
upstream, not waived), `classify_backfill_mode` pinned `off`, the backfill daemon not started in
production, and the movie-kind backfill reachable only as a force-stopped one-shot.
**Rollback history:** `models/v10` survives only in git as reinstall-rollback history — reinstalling the
0.2.0 APK restages v10, and masks are bit-compatible so 13-labelled rows stay readable. The device's
`files/crawler/models/` now holds only `v11` and `v13`.

**fastText adult classifier (v11).** `classifier_model_dir` pins `models/v11` (`CrawlerConfig.MODELS_SUBDIR`);
`models/v6` is retired and survives only as rollback history (reinstalling the 0.1.0 APK restages it — G8
in `plan/adult-v11-integration.md`). Staged files and shas:

| file | bytes | sha256 |
|---|---:|---|
| `adult.ftz` | 8,863,323 | `7b8a3272642133dbf56b0a44d78dedfdc28a1a63b2800c96ab3cc8923d0f9412` |
| `category.ftz` | 8,857,514 | `2d4dcd7c6c4cc50dd078667872985fe1ce58d2a56dd9103828b700e4b6dd9271` |
| `tv_kind.ftz` | 8,883,074 | `35c0ac36f50ef4e2950bc8189990faf9759f3c1ed1e28278bc07cce1cfa13440` |
| `manifest.json` | 8,241 | `481e737905f2cf7eafe353229329d67ced60c4ffa946eb1ea34440f06d3092aa` |

`adult_hide_threshold` **0.545726** and `adult_drop_threshold` **0.949021** ride the manifest:
`src/classifier.c` prefers `thresholds.*` from `models/v11/manifest.json` over `config.ini`, which is why
the manifest is staged with the `.ftz` files. `AppConfig.adultHideThreshold` carries the same 0.545726
default, and the Stage-12 `ClassifierModelVersionMigration` resets the persisted value on a generation
change, so a model bump cannot leave a stale operating point behind.

The parser copy (`dht_crawler-android/tparse/`, resynced from classifier-model) emits the `__adult_jav`,
`__adult_site` and `__adult_set` feature tokens from `tparse/adult_terms.h`. That header is **generated**
(`uv run cm gen-header` in the model repo, guarded there by a committed-parity test) — never hand-edit it.

`torrent_class.adult_clf_ver` records which adult student produced `adult`/`adult_p`: **NULL = never
adult-backfilled** (a v6-era verdict), **11 = scored by v11**. `clf_ver` keeps its old meaning
(category/tv_kind generation) and stays 6 on backfilled rows, so the mixed generations are explicit rather
than mislabelled. The full `clf_ver` 11 reclassify is deliberately **not** run this release.

The token-gated pass is crawler patch 16: `--adult-backfill <db> [--write] [--adult-backfill-limit <n>]`.
It re-scores only rows whose name fires an `__adult_*` token, re-checks the file list for the rows that
pass, and (with `--write`) sets `adult`/`adult_p`/`adult_clf_ver` = 11; without `--write` it walks the same
gate and writes nothing. On the phone the only way to run it is the dashboard maintenance action
`POST /crawler/maintenance/adult-backfill` (`MaintenanceAction.ADULT_BACKFILL`, space-gated), which stops
the supervised crawler, runs the one-shot and restarts it; the terminal `[MPROG]` counters
(`total/gated_out/scored/updated/failed`) are folded into the maintenance status `message`.

`classify_backfill_mode` is pinned **`off`** (`CrawlerConfig` write + `CrawlerKeys` allow-list; operator
decision), so the crawler's own backfill daemon never starts a pass at launch. The C default stays `all`
and the daemon's `adult` branch (auto-run the gated pass) is implemented and host-tested but unpinned —
flipping the pin is a one-line config change. With `off` the daemon publishes state **`disabled`**, not
`idle`, and never polls.

`/stats` gains `classifier.clf_ver`, `classifier.adult_drop_threshold` and
`classifier.model_sha256_{adult,category,tv_kind}` beside the existing threshold/counter fields, plus an
`adult_backfill` object: `state`, `target_adult_clf_ver`, `rows_total`, `rows_gated_out`, `rows_scored`,
`rows_updated`, `rows_failed`, `last_id`, `started_at`, `finished_at`, `last_error`.

**Measured on the reference device (v11 rollout, 965,230-row corpus, threshold 0.545726).** G1 parser
parity 10,000/10,000 identical feature texts (quotas jav 259 / site 243 / set 200 / none 9,306 against a
200 bar; the negative control — the pre-Stage-1 parser — differs on 694 of 10,000 rows, `__adult_*` tokens
only, host side only, so the harness does detect the drift). G2 500/500 label agreement, max |Δadult_p|
0.000000. G3 **the pre-registered 2,886–24,049 (0.3–2.5 %) band was MISSED**: the pre-write dry run gated
in 24,435 of 961,969 = 2.5401 %, 386 rows (1.6 %) over the ceiling, and the band was re-derived to
1.0–8.0 % under operator authorisation once the cause was established as corpus composition, not token
over-fire (identical parser and lists fire 0.7352 % on `~/torrents.db`'s 6,015,586 rows vs 2.5328 % on the
950,993-row device corpus, driven by `__adult_site` at 9.3×; 25/25 sampled `__adult_site` matches are
genuine curated adult studios; R3 re-curation was rejected). The actual write measured 24,528 = 2.5411 %,
inside the re-derived band. G4 0 of 200 untouched rows differing, 0 of 24,435 backfilled rows changing
`category`/`tv_kind`/`clf_ver`, `clf_ver` = 6 AND `adult_clf_ver` = 11 = 24,528 = the run's `updated`,
`adult_clf_ver IS NULL` = 940,702 = its `gated_out`. G5 duration ~2.5 min for 965,230 scanned / 24,528
scored (bar ≤ 30 min) but peak RSS **VmHWM 1,832,772 kB ≈ 1.75 GiB against a 300 MiB bar — MISS** (likely
the on-device movie_kind backbone plus the 8.2 GB DB page cache, i.e. the bar was set for a model-free
host measurement — not proven); no crawler restart, `/stats` stayed 200. G6/D1 PASS: catch 99.00 %
(396/400) against ≥ 60 %, 0 flips against ≤ 2, paired v6 recall 98.50 % with 6 rows caught that v6 missed
and 0 lost. G7 movie_kind untouched: generation 10, hidden 1024, backbone `02555750…`, head `077aaf11…`,
`model_ready` true, `movie_kind_backfill` idle with `rows_processed` 0. Restart persistence clean: model-dir
mtimes and `.assets_version` (`2|models/v11|models/v10`) identical after force-stop + relaunch (no
restage), `clf_ver` 11, `classifier_backfill` `disabled`, `adult_backfill` idle with no re-run.

**D1's coverage bound.** The 500-row D1 sample was ~80 % backfilled rows, so 99 % catch measures the
*fidelity of the write*, not the reach of the backfill. An unbiased 20,000-row random sample scores 19.14 %
reference-adult (3,828/20,000 — ~184,745 adult rows in the 965,230-row DB) and the token gate selected
24,528 of them, i.e. **≈ 13.3 % coverage**; the remaining ~87 % of adult rows keep their v6-era labels.
That is the bound on what this pass achieves, and the full `clf_ver` 11 reclassify that would cover them is
deliberately not run in this release.

**Classifier traps (each cost time on the v11 rollout).**
- The host CLF binary's `config.ini` default `adult_hide_threshold` (**0.917039**, `src/config.c`) beats
  the manifest (0.545726) — config > manifest. Any host scoring run must set it explicitly; the device is
  protected by `ClassifierModelVersionMigration`, the host is not.
- `classifier_enabled` / `movie_kind_enabled` are parsed with `atoi`, so `= true` means 0 (off). Use `= 1`.
- `make clf-backfill` takes `-I` from the app's tparse copy but `TPARSE_LIB` from the model repo's prebuilt
  archive — override `TPARSE_LIB` or you measure the wrong parser.
- Adding a `torrent_class` column means five places in the C tree plus the Kotlin mirror — see invariant 14.

**dht_crawler patches (order is fixed, applied by `prepareDhtCrawlerSrc`):** the 23-entry list in
`app/build.gradle.kts` opens with the three upstream fixes — `bencode-cap-fix.patch` →
`bencode-init-fix.patch` → `civetweb-stop-flag-atomic.patch` — and closes with
`movie-kind-v10-streaming.patch` (last-token pooling + inline ingest classification),
`adult-v11-backfill.patch` (the token-gated adult backfill, `adult_clf_ver`, `classify_backfill_mode`),
`movie-kind-v13-default.patch` (flips only the compiled-in model-dir default — three literals in
`src/config.c`, `include/movie_kind.h` `MOVIE_KIND_MODEL_DIR_DEFAULT` and the `include/config.h`
comment — so the vendored tree stays pristine), `inflight-dedup.patch` (18th) and
`getpeers-metadata-tuning.patch` (19th: the `/stats` success-metric split + per-outcome metadata
failure attribution + get_peers round-idle/slot-budget/finish-on-peers/miss-penalty mechanisms + metadata
piece-idle eviction, all as **opt-in knobs shipped at legacy defaults** — five 30-minute on-device windows
measured the tuning net-negative, so patch 19 ships the instrumentation and the keys, not the behaviour),
`stats-last-interval.patch` (20th: last-interval "windowed" rates in `/stats aggregate` — the
pure `stats_window` module plus a sampler on the supervisor monitor thread; additive only, every lifetime
key keeps its exact meaning), `metadata-peer-fail-map.patch` (21st: the fail-open per-address TCP
connect-failure memory — `src/meta_peer_fail.c` + `include/meta_peer_fail.h`, host-tested by
`make tests-meta-peer-fail`), `meta-peer-fail-capacity.patch` (22nd: the capacity corrective, 8192 →
131,072 slots — constant + comments only, no behaviour change) and `bep51-funnel-stats.patch` (23rd and
last: the BEP51 query + dedup-funnel counters — per-tree `trees[].bep51` and summed `supervisor.bep51`,
counters only, no behaviour change and no config key); new patches go LAST, as the 24th slot. Upstream
fixes belong in `patches/`.
See the vendored repo's `CLAUDE.md` for its architecture (thread trees, bloom filters, schema) and its
`CONFIG_INI.md` for the patch-19/21 config keys and the patch-20/22 `/stats` blocks.

**`/stats` after patch 19 (measured — the instrumentation is the win, the tuning is not).** `aggregate` publishes both
denominators beside their percentages: `metadata_attempts_total` with `metadata_fetch_success_percent`
(`total_metadata_fetched * 100 / metadata_attempts_total`, 0.0 when the denominator is 0) and
`get_peers_lookups_total` / `get_peers_lookups_with_peers` with `get_peers_yield_percent`; **`metadata_success_rate_percent`
is removed** from the JSON — it survives only as a `gp_probe.py` legacy fallback and in source comments.
`supervisor.metadata_failures` splits the failure mass into `connect_total`, `handshake_total`,
`stall_total`, `ut_reject_total`, `size_total`, `hash_total`, `deadline_total` and `other_total`: each
failed connection is counted once, at the site that first detects it, **except `deadline_total`, which is
one event per attempt** at the global-timeout break (every live slot is abandoned together), so
Σ counters ≥ failed attempts by design. `supervisor.metadata` echoes the effective metadata knobs
(`parallel_peers`, `metadata_timeout_ms`, `metadata_workers`, `tcp_connect_timeout_ms`,
`fresh_peer_retries`, `piece_idle_ms`); `supervisor.get_peers` and every `trees[].get_peers` gain
`idle_exits_total`, `deadline_exits_total`, `unanswered_slots_total` and `miss_penalties_total` (the
per-tree block also echoes `round_idle_ms` and `penalize_misses`), and each tree gains `metadata_attempts`
beside `metadata_count`. Patch 19 also adds the config keys `tree_get_peers_round_idle_ms` (default 0 = off,
opt-in clamp 250..6000), `tree_get_peers_penalize_misses` (default 0, 0/1), `tree_get_peers_slot_budget_ms`
(default 0 = off, opt-in clamp 500..6000), `tree_get_peers_finish_on_peers` (default 0, 0/1) and
`tree_metadata_piece_idle_ms` (default 0 = off, opt-in clamp 3000..60000), and narrows the
`tree_parallel_peers` clamp from 1..10 to 1..4 to match `MAX_PARALLEL_PEERS` — a correctness fix, so the
accepted value is the effective one — while `tree_parallel_peers` and `tree_metadata_timeout_ms` keep their
legacy defaults **2** / **60000**. Every knob stays present, parseable, echoed and documented; all fail-open
to the pre-patch behaviour at 0/old values.
`tools/crawler/gp_probe.py` reads the new keys with a fallback to `metadata_success_rate_percent`, so one
probe version measures pre- and post-patch-19 builds.

**Measured over five 30-minute windows (1795 s each, 64 trees / 18 workers / cap 3000) — the tuning is
net-negative, so it ships off.** The only valid contemporaneous comparison is TUNED vs LEGACY on the same
build in the same hour: lookups/s 233.0 vs 236.6 (−1.5 %), yield 29.7 % vs 33.1 % (−3.4 pp), packets/lookup
8.99 vs 8.46 (+6 %), metadata/s 5.86 vs 6.75 (−13 %). The 00:46 baseline (239.1 / 36.9 % / 9.23 / 7.16) is
**not** a valid control — LEGACY on the new build measured 33.1 % yield against the baseline's 36.9 %, i.e.
the DHT thinned overnight. get_peers is **latency-bound, not discovery-bound** — corrected by this run's seven windows (below): the
per-tree worker drain equals `1000 × 18 / mean_lookup_ms` to within 1.1 % in every window (98.9–99.6 %),
`idle_exits_total` is 0, deadline exits are ~62 % of all-caller lookups and `mean_lookup` sits at 94.6–97.6 %
of the 6 s deadline, and raising BEP51 fill 3.73 → 7.14 /s/tree (W5)
left the drain at 3.13 → 3.14. The patch-19 numbers were already consistent with that model — 1000 × 18 /
7,044 ms = 2.56 /s sits inside the observed 2.5–3.0 drain band — so "discovery-bound" was a queue-pressure
observation (fill 3.0–3.5 vs drain 2.5–3.0, `discovery_paused` 11–14 %), not a discovery cap; metadata is
**peer-liveness-bound** (connect 82.8–84.1 %, handshake 14.8–16.1 %, stall 0.8 %,
deadline 0.1–0.2 % across every window), so piece-idle eviction and the deadline knob address ≤1 % of the
failure mass. The round-idle premise was **falsified**: 245 idle exits vs 246,380 deadline exits, because
answers trickle in faster than 1.5 s. Criteria 4 (get_peers rate) and 5 (metadata rate/success) are recorded
**NOT-IMPROVED** — the documented negative result the measure-and-report clause allows, not a pass. What
patch 19 does deliver is the instrumentation (the split with published denominators, the eight
`metadata_failures` counters, the exit/slot counters), which produced every finding above. `Δudp_throttled_sends_total`
is non-zero in all new-build windows (17,782–47,439) including LEGACY vs 0 in the pre-change baseline — a
build-level observation, cause unisolated, **not** attributed to the behaviour knobs.

**`/stats` last-interval rates (patch 20).** The lifetime `aggregate` counters are cumulative since process
start, so a single snapshot cannot say how fast anything is happening *now*. Patch 20 adds seven additive
siblings — `metadata_attempts_last_interval`, `metadata_fetched_last_interval`,
`metadata_fetch_success_percent_last_interval`, `get_peers_lookups_last_interval`,
`get_peers_lookups_with_peers_last_interval`, `get_peers_yield_percent_last_interval` and
`stats_interval_sec` — sampled by the supervisor monitor thread on its existing hard-coded 10 s tick (no new
config key: `stats_interval_sec` is the *measured* elapsed time, so rate = value / `stats_interval_sec`).
The sampler (`src/stats_window.c`, pure C99, host-tested by `make tests-stats-window`) folds the same
totals `stats_handler` publishes (cumulative respawn buckets + live trees) and stores the delta since its
previous tick; the first tick only records the baseline, so a freshly started crawler publishes zeros with
`stats_interval_sec` 0 rather than a spike, and a counter that moves backwards saturates at 0 instead of
wrapping. Lifetime keys, their percentages, `supervisor.get_peers.*` and the eight `metadata_failures`
counters are untouched.

**Per-address TCP connect-failure memory (patch 21).** `~83 %` of the metadata failure mass is a TCP
connect that never completes (patch-19 measurement), and the peer list handed to a fetch is
arrival-ordered with **no address dedup**, so the same unreachable peer is re-tried across infohashes and
across trees. Patch 21 adds `tree_metadata_peer_blacklist_ttl_sec` (compiled default **0 = off**, opt-in
clamp 60..3600, warn-and-clamp) and one supervisor-shared map (`src/meta_peer_fail.c`: 131,072 slots × 32 B =
4 MiB, open addressing + FNV-1a, one `pthread_mutex_t`, lazy TTL ageing on the lookup/insert path only —
no new thread; the load-factor clear at 3/4 is fail-open). **Capacity is sized by the TTL working set**
(patch 22, `meta-peer-fail-capacity.patch`): 131,072 × 3/4 = 98,304 clear threshold > the ~66,000–79,800
entries a 600 s TTL accumulates at the measured ~110–133 NEW distinct fail addresses/s (~23 % margin), and
4 MiB ≈ 0.2 % of the measured 2.04 GB crawler RSS. The original 8192 slots (clear at 6,144) were wiped every
~50–70 s at that rate, so `expired_total` stayed 0 and entries died by capacity clear, never by TTL. Fail-open is absolute: `ttl_sec = 0` never
creates the map, so every lookup answers "do not skip" and selection is byte-identical to legacy; a
creation failure is non-fatal and lands in the same state. Recording is **additive only** — every site that
counts `metadata_failures.connect_total` (the seven direct `meta_count_fail(tree, META_FAIL_CONNECT)` sites
plus `meta_count_conn_fail()` when it classifies CONNECT) also records the address, while the eight
counters, the exit counters and the count-once discipline are untouched (`parallel_conn_t` gained `peer`
because the poll-loop sites have no other handle on the address). **The skip-to-empty guard is mandatory:**
the selection cursor never passes the last candidate of an attempt and an attempt whose whole window was
skipped refills once with the map ignored (the cursor is NOT reset — re-trying an address already failed
would inflate `connect_total` and make `R = connect_total/attempts` unreadable); without it a
zero-connection attempt would feed the irreversible infohash strike bloom. `/stats` publishes
`metadata_peer_fail` beside `inflight`: `enabled`, `capacity`, `held`, `ttl_sec`, `clears_total`,
`expired_total`, `metadata_peers_skipped_total` (skipped **SLOTS** — peers are not deduplicated) and
`metadata_connect_fail_addresses_total` (NEW distinct addresses admitted, not events). The template ships
the key at 0 even if a later A/B wins: the device carries the winning value, the template ships off.
`gp_probe.py` reads the block (`MPF_KEYS`, a `peer-fail map` row, a missing-feature check) so a window
report can never show a blank column.

**BEP51 query + dedup-funnel counters (patch 23).** `bep51-funnel-stats.patch` is the 23rd and last patch and
changes **no behaviour and no config key**: eight per-tree atomics — `queries_sent_total`,
`responses_received_total`, `timeouts_total`, `samples_offered_total`, `bloom_rejected_total`,
`inflight_refused_total`, `queue_full_total`, `samples_pushed_total` — are published per tree as
`trees[].bep51.*` and summed as `supervisor.bep51.*`, cumulative-folded at respawn like the other `gp_*`
counters. The four gates are mutually exclusive, so per window
`offered = bloom_rejected + inflight_refused + queue_full + pushed` (±1 % slack for in-flight boundary
effects); `tools/crawler/gp_probe.py` reads both blocks (`BEP51_KEYS`, `_bep51_norm()`, three table rows) and
prints the identity gap, so a blank funnel column is impossible. No host test: `thread_tree.c` /
`http_api.c` have no harness and the change is additive atomic increments plus cJSON emission — it is
validated on device by key presence and the accounting identity, and the 13 `tests:` targets (14 binaries)
all pass with no existing target changed.

**Measured on this run — seven 30-minute windows (2026-11-17, 64 trees / get_peers 18 / BEP51 3 /
metadata 10 workers, pp 2, pps cap 3000, `log_level=INFO`; full record `plan/getpeers-metadata-results.md`).**
Three same-hour, same-build pairs, one variable each:

- **`tree_parallel_peers` 2 vs 4 (W0/W1) — 2 ships.** metadata/s 6.834 vs 6.408 (−6.2 %, outside the <5 % tie
  band), connect events 405,152 vs 427,348 with attempts 84,789 vs 88,063 and window success 14.47 % vs
  13.06 %: pp=4 bought more concurrent connections (10.60 → 11.28 per tree) and more attempts, and no more
  fetches. The template already carried 2, so nothing changed in the repo. This reverses the patch-19 run's
  confounded LEGACY 6.75 vs SHIPPED 5.12 comparison.
- **`tree_metadata_peer_blacklist_ttl_sec` 0 vs 600 on the resized map (W2b/W3) — PASS, the device ships
  600.** Validity gate met (Δ`expired_total` 4,071 > 0, Δ`clears_total` 1 ≤ 2), then R 4.665 → 2.525,
  metadata/s 6.128 → 6.706 (+9.4 %), 230,086 skipped slots (2.44 per attempt), connect share 83.2 % →
  72.4 % with handshake rising 15.7 % → 25.8 % — the distribution **shifted**, it did not merely shrink.
  Side effects: failure mass 462,119 → 329,453 (−28.7 %), yield 31.9 % → 36.2 %, active conns per tree
  10.03 → 7.13, drain flat 3.05 → 3.07. The first attempt at 8192 slots was capacity-churn-bound (clears
  1→2→3 in 90 s, `expired_total` 0) and produced no verdict; patch 22 resized the map and W2b replaced the
  stale W2 as the control. That mattered: W2 (8192 build, ttl=0) read 6.63 against W2b's 6.13 on identical
  settings, so reusing W2 would have turned +9.4 % into +1.2 %.
- **BEP51 workers 3 vs 12 (W4/W5) — NOT-IMPROVED on rate; the funnel is the win.** queries/s/tree 3.51 →
  5.04 (+44 %), offered/s/tree 44.34 → 65.78 (+48 %), but pushed fell 362,156 → 356,005 (−1.7 %) and
  lookups/s 249.1 → 246.4 with metadata/s 7.24 → 7.29 flat; the accepted share fell 7.1 % → 4.7 % because
  bloom (65.9 → 62.1 %) and in-flight (27.0 → 33.1 %) refuse the extra duplicates. Fill 3.73 → 7.14 /s/tree
  (+91 %) did not move the drain (3.13 → 3.14), and `discovery_paused` rose 10.9 % → 68.1 % exactly as the
  90 %/33 % sawtooth predicts ((fill−drain)/fill ≈ 56 % predicted vs 68.1 % measured). **The binding term is
  the deadline-dominated drain**: `mean_lookup_ms` 5,707 ≈ the 6,000 ms deadline, idle exits 0, throttle
  ratio 0.15 % of sends (W5: 0 throttled of 4.74 M sends). Raising BEP51 offer rate does not raise lookups/s.
  Threads 2,383 → 2,959 (+576 = 64 × 9, as predicted), RSS 2,119,252 → 2,241,568 KiB, pps 2,577 → 2,640 of
  the 3,000 cap.

**Ruled out by this run — do not retry without a new discriminator.** (i) **BEP51 worker scaling as a
lookups/s lever**: measured above — accepted samples are novelty-bound (bloom + in-flight gates) and more
offers multiply duplicates. (ii) **The 8192-slot peer-fail map**: at ~110–133 NEW distinct fail addresses/s
the 3/4-load clear wiped it every ~50–70 s and `expired_total` stayed 0, so the TTL mechanism never ran —
capacity must be sized from the TTL working set, not guessed. (iii) **The UDP token bucket as the drain
cap**: the throttle ratio is 0.15–0.53 % of sends in every window (W5 0.000 %) while pps sat at 2,407–2,640
of the 3,000 cap. (iv) **The discovery-fill premise itself**: doubling fill moved the drain by 0.3 %, so
discovery is not what bounds lookups/s. Every safe latency lever is already in the patch-19 dead-ends list
(round-idle inert, slot-budget / finish-on-peers −13 % metadata/s, miss-penalties −10.8 pp yield), and more
get_peers workers stays forbidden by the concurrency budget.

**Measured on b078fce (patch 18, 320,697-row DB, 64 trees).** `/stats` carries an `inflight` object
(`enabled/capacity/held/claimed/refused/released/expired/tree_released/clears`). Over an 18-minute run
`refused` 788,419 vs `claimed` 188,715 — **80.7 %** of BEP51 discovery attempts were duplicates that never
reached a fetch; `held` plateaued at ~21–22 k (bounded by the per-tree queue capacities, not a leak) and
`expired`/`clears` stayed 0. `Cached counts initialized: 320697 torrents, 10711461 files` matched the
pre-restart `/stats` exactly across two restarts (319,600 → 319,600 and 320,697 → 320,697), and the
15-minute re-seed logged `320697 torrents (was 320697)` — zero drift. That re-seed costs ≤ 1 s here
(`COUNT(*)` over 320 k torrents + 10.7 M files), so expect ~10–25 s on the 6.1 M/286.7 M-row production DB.
**Trap:** `log_level = DEBUG` on this crawler writes ~575 MB in 18 minutes (1.9 GB/h) — enable it only for a
short window, and remember `crawler_log_level` lives in `app_config.xml` while `config.ini` is only
rewritten on a settings apply, so a hand-edited `config.ini` survives a restart.

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
14. **A new `torrent_class` column means FIVE C places plus the Kotlin mirror:** the live CREATE,
    `database_ensure_column`, `batch_writer.c`'s backup DDL, `backup_ensure_column` and
    `tools/schema-v2.sql`, plus `backup/CrawlerDbSchema.kt` and its exact-string test. Miss one and
    backup/restore or `rebuild-db-v2.sh --check-schema` breaks — this is how `adult_clf_ver` landed.

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
