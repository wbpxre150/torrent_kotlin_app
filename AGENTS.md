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
| `libdhtcrawler.so` | vendored `src/main/cpp/dht_crawler/` | `buildDhtCrawler` → `prepareDhtCrawlerSrc` (copy + 3 submodule patches) → CMake | DHT keyspace crawler; SQLite DB; civetweb HTTP API; bloom-filter dedup |
| `libftpserver.so` | `src/main/cpp/ftp-server/` (C11) | `buildFtpServer` → CMake/NDK | FTP server for media clients; posix or SAF backend (SAF talks to `SafIpcServer`) |

**Task graph:** `buildMbedtls` (mbedTLS **3.6.6**) → `buildCurl` (curl **8.20.0**) → `buildTransmission`;
`buildLibuv` (**1.48.0**) + `fetchSqlite` (**3.49.1** amalgamation) + `prepareDhtCrawlerSrc` →
`buildDhtCrawler`; `fetchLlamaCpp` (llama.cpp pin `18ab62a8ec5144ae5d2f65e78374ba69652d75dd`, tarball
sha256 `34bd70e229bf0439575b7865e986209d23584bd35274d95c256f9ccf398ab46a`) → `buildLlama` →
`buildDhtCrawler`;
`buildFtpServer` (leaf). `copyDhtCrawlerAssets` copies the crawler's keyword files (~1 MB) into
`build/generated/assets/dht_crawler/` (wired into merge assets) — the classifier weights are **not**
assets any more (see *Movie-kind classifier (v13)*). `afterEvaluate` makes
`mergeDebugJniLibFolders` depend on all three build tasks. `-PdhtCrawlerSrc=<path>` overrides the
crawler source root. Native build inputs/outputs land under `app/build/native/`.

**Movie-kind classifier (v13).** The crawler links a CPU-only llama.cpp (`buildLlama`) and the vendored
`dht_crawler-android/utf8proc/` (**2.12.0**); the NDK wrapper defines `MK_ENABLED` and
`MK_NFC_ENABLED` only when `LLAMA_INSTALL` is set, so a plain host configure stays plain C99. Two
model generations ship side by side: `models/v15` (CRAM — see *CRAM classifier (v15)* below) and
`models/v13` (same family as v10 — decoder backbone, Q4_0 501,452,416 B / 478.2 MiB, sha256
`28270fa01100687969b541282814371062ab9e1a560bb7ea755243c822cf0d4c`, plus the JEVH head 65,712 B,
1024×16, sha256 `2f994cbe792441be182717cc6faa46f3ff245db6d17af30d72cfc47bbefb7ae3`, hidden 1024, 16
labels, prompt `Torrent: {name}\nFilm genres:`, max 64 tokens, staged beside `manifest.json` 17,772 B
sha256 `e0474643…fb57` — pinned by `movie_kind_model_dir`). `CrawlerConfig.MOVIE_MODEL_SUBDIR =
"models/v13"` drives the pin. **The weights are no longer shipped in the APK.** Both generations used to
be staged out of `app/src/main/cpp/dht_crawler-android/models/` (that directory is gone) and are now
downloaded by the crawler itself at first run: `dht_crawler --fetch-models` pulls
`https://unjumble.au/bep51/models/{v15,v13}/` into `./models/{v15,v13}` (cwd = `files/crawler`), verifies
every file against that generation's own sha256-pinned `manifest.json`, skips files that are already
present and correct, exits 0/1 and is safe to call on every start. `DaemonService.maybeFetchCrawlerModels`
runs it through a **dedicated** `OneShotRunner` + single-thread executor (never the maintenance runner,
which holds one child/pid) **before** the crawler starts, and blocks the crawler start — not the service,
which keeps FTP/transmission/the dashboard live — until it exits, bounded by
`CrawlerModels.FETCH_TIMEOUT_MS` (12 min) as a hang guard; a non-zero exit, a launch failure or the bound
all start the crawler anyway (both engines inert) and retry after
`CrawlerModels.FAILURE_RETRY_INTERVAL_MS` (1 h). The wait is deliberate: the crawler loads its models only
at process start, so starting it first would leave it inert for the whole session and stamp `clf_ver` 0 on
every ingested row, which nothing repairs automatically (`classify_backfill_mode` is pinned `off`). The
downloaded generations total 512,972,090 B (~489.2 MiB — `models/v15` 11,436,190 B + `models/v13`
501,535,900 B), which is the payload the APK no longer carries: the 0.4.0 debug APK measured 540,330,690 B
with them staged, and the post-diet APK size is **pending the parent's build measurement** (expect ~489 MB
smaller, less the few MB of keyword assets that remain). `.assets_version` is now
`<versionCode>|keywords` (`CrawlerConfig.stampValue`) — it names the keyword payload only, so a classifier
bump can no longer force a restage; it is a crawler-level file (`files/crawler/.assets_version`, **not**
under `models/`). `ensureAssets` stages the keyword files only and the `v<digits>` sweep is **gone**:
nothing under `models/` is ever deleted by the app, so an upgrade from the old `4|models/v15|models/v13`
stamp keeps a user's downloaded generations (`CrawlerConfigTest.ensureAssets_upgradeFromTheModelDirStampKeepsDownloadedModels`
pins exactly that).
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
(state/rows_processed/rows_total/failures/thermal_pauses) beside the `classifier` (CRAM since v15) and
`classifier_backfill` blocks. The `movie-kind-bench` maintenance action runs
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
`files/crawler/models/` now holds only `v15` and `v13` (`v11` was swept at the v15 install).

**CRAM classifier (v15).** `classifier_model_dir` pins `models/v15` (`CrawlerConfig.MODELS_SUBDIR`);
`models/v11` — the three fastText `.ftz` students — is retired and survives only as rollback history
(reinstalling the 0.3.0 APK restages it and `ClassifierModelVersionMigration` resets the persisted
threshold to 0.545726; rows already stamped `clf_ver`/`adult_clf_ver` 15 stay readable, because they are
plain integers in unchanged columns and the v11 code does not interpret the generation). **One engine
scores all three fields**: a single CRAM forward pass over the wide design decides `category` (masked
48-state argmin), `tv_kind` (the standalone `kind_W` head) and `adult` (the joint's adult marginal) at
ingest. **No `.ftz` file ships, no ftc model is loaded, and there is no fastText fallback**; the
`dht_crawler-android` wrapper still links ftc/fastText, and removing that build wiring is optional
cleanup. Fetched files and shas (`files/crawler/models/v15/`, downloaded by `--fetch-models`):

| file | bytes | sha256 |
|---|---:|---|
| `cram.bin` | 9,388,835 | `834159b642d67da728bbdc3411badf83b639742743c95d414fc62ddd81e45bec` |
| `cram_vocab.bin` | 2,046,524 | `6e24c4b27e3bd546a7dad0cb9a929dc7f354e664139604f0bb75f3ca1cf21cee` |
| `manifest.json` | 831 | `59e39d3f85db21e015d94ffd7f5b055d0b593f69c238ac18f39c30bf50d53e55` |

All three come from `~/apps/cram/tvkind/data/clean_v3/heads_per_field_v2.npz` (4,909,615 B, sha256
`1cad6d9f9ddfd4afd72338288d12dda6855dceda4cb531d5417b9ee22406fff2`) via `scripts/cram_export.py`
(cram workspace commit `e6ce723`); the export converts and proves bit-identity, it never refits.

**Container geometry.** `cram.bin` is a little-endian CSR with no padding: header (magic `CRAM`, format
version, `ncols` **1,195,982**, 28 output columns, rowptr), feature-row-major entries of
`(uint8 col, float32 weight)` — 5 B each — for the **920,915** nonzeros, 28 biases, the 48-state
`(cat_idx, kind_idx, adult_idx)` table and T = **0.7629844484210975**. The columns are **147,406** named
(structs 1,315 / words 48,757 / bigrams 97,334) plus a **1,048,576**-bucket char tail, which is the
1,195,982 total; the design doc's "920,922" nnz is 7 high and is corrected here. `cram_vocab.bin` is the
same three sorted string lists as an offset table + UTF-8 blob (UTF-8 byte order == Python codepoint
order, so C `strcmp` bsearch reproduces dict membership). G0 proved both: dense reconstruction
bit-identical to **all 11** npz arrays (0 mismatches), val reload category **1,846**/19,913 (delta 0),
`val_all` kind **177**/3,999 and **57**/817 (delta 0), val ECE at T **0.022218**; vocab witnesses train
nnz **26,628,710** / val nnz **3,289,873** against the container's 920,915. Heldout was never read.

**The 48-state masked argmin is the category model.** `cram_predict()` accumulates energies in `double`
and takes `category` from the masked argmin over the 48 `(cat, kind, adult)` states, **first minimum
wins**; `confidence` is that state's category marginal of `softmax(−E_state/T)`. The bare category unary
(argmin over `cat_Wc` alone) is **~9 pp worse** (0.8150 vs 0.9085 upstream) and must never ship —
`tests-cram` pins the gap on the fixture rows where the two disagree. `E` is a **cost**: `P =
softmax(−E)`, answer = argmin, `adult_p` high = adult.

**Adult is the raw marginal at 0.375.** `adult_p` is the adult marginal of `softmax(−E_state)` with **no
temperature** — T was fitted for the category confidence only, and applying it to adult would silently
move the threshold's meaning. `adult = adult_p >= adult_hide_threshold`, and **tag boundary == drop
boundary**: the v15 manifest omits `adult_drop_threshold`, `classifier.c` stops reading it, and `/stats`
keeps the key mirroring the effective `adult_hide_threshold` for shape stability (no Kotlin consumer;
`CrawlerConfig.VESTIGIAL_KEYS` keeps purging it). 0.375 is the measured **recall-0.90** point of the CRAM
adult marginal on 19,941 heldout rows (base rate 17.65 %, 3,519 positive: precision 0.848, 566 false
positives, 352 missed) and no fastText-calibrated value (0.545726, 0.898355, 0.917039) means anything on
this scale. It rides four places that must agree — `config.ini` (config beats manifest),
`models/v15/manifest.json`, `config.ini.default` and `AppConfig.adultHideThreshold` — with the C
compiled-in default 0.917039 left as the unreachable last resort.

**The 50-file cap is a serving contract, not an optimisation.** Training features are first-50-file
features (the `__nfiles_50` spike is the witness), so `classifier_classify()` caps `n_files` at 50
**before** the `tp_file_t` malloc and before `tp_parse`; the full list still goes to the DB and to
movie_kind — the cap is local to the classify call. Serving the full list silently shifts the
file-evidence tokens. `/stats cram.file_cap` publishes 50, and `classifier.file_cap_hits` /
`classifier.cram_feature_cap_hits` count the capped calls.

**The serving path strips `__adult_*` — a permanent shipped property.** The vendored parser
(`dht_crawler-android/tparse/`, resynced from classifier-model) emits `__adult_jav`, `__adult_site` and
`__adult_set` from `tparse/adult_terms.h` **unconditionally**, while the frozen model was trained on
`features.jsonl` (sha `e9eec4486e5cf7edaa8c94907340e0f4776035417086ac510c5568ac3084a04d`, rewritten
2026-11-21) that carries those tokens on only **20 of 270,390** rows. `cram_strip_adult_tokens(char *ft)`
(`src/cram.c`, pure C99, in-place, no allocation, idempotent) removes every whitespace-delimited token
beginning `__adult_` together with exactly one adjacent separator; `src/classifier.c`'s shared CRAM
helper calls it **after `tp_feature_text` and before `cram_features`**, on the path shared by
`classifier_classify()` and `classifier_classify_adult()`. It is deliberately **not** in
`tparse/adult_terms.h` (a generated header — `uv run cm gen-header`, never hand-edited — and a resync
drift surface) and **not** in `src/adult_backfill.c`: that file's own `ab_gate_text()` renders and scans
the *raw* feature text, so a parser-level strip would gate out every row and silently no-op the
`--adult-backfill` one-shot. Cost: one extra O(n) pass over the ≤8,192-byte feature buffer per classify.

**G1 recorded a bar revision, not a silent pass.** The original bar was 10,000/10,000 identical `ft`
between the app's tparse CLI (50-file cap applied) and `features.jsonl`, on a deterministic seed-15
stratified sample (1,111 tv/anime ≥ 500; 708 rows `files_total > 50` ≥ 200). It was **MISSED at
9,710/10,000**: every one of the 290 mismatches was adult-token-only (`__adult_jav` 160, `__adult_site`
129, `__adult_set` 3; **0 missing tokens, 0 numeric-field differences**), full corpus 116,700/120,000 =
2.75 %. Measured verdict impact on those 290 rows: category **0/290**, tv_kind **0/290**, adult
**1/290**, max |Δadult_p| **0.0098** — the engine was right (G2 passed 100 % on the same rows) and the
drift was corpus vintage. The operator authorised the serving-side strip (a recorded decision, not a
waived bar). Post-strip: **10,000/10,000 exact**, sample ∩ the 20-id corpus set = ∅, and full corpus
**120,000/120,000 with mismatch id set `[]`** — the design doc's "119,980/120,000" was a planner
inference that does not hold, because those 20 ids appear in none of the local record files
(`corpus_adult_ids_in_join` = 0), so the assertion is set equality and is strictly stronger. The
opposite direction is witnessed separately: on the 20 rows the corpus *does* carry tokens for, 0/20
category, 0/20 tv_kind, 0/20 adult, max |Δadult_p| **0.00982432**.

**The degraded contract is all-or-nothing.** A missing manifest, or a `cram.bin`/`cram_vocab.bin` that is
missing, sha-mismatched or malformed, fails `cram_load()` non-fatally: one event increments both
`classifier.model_load_failures` and `cram.load_failures`, and there is **no partial state** — no
one-field fallback. `classifier_classify()` then returns 0 with `category` = the parser hint, `tv_kind`
= the parser kind, `adult` 0 / `adult_p` 0.0, `clf_ver` 0, `parser_only_fallback++`.
`classifier_models_loaded()` means "CRAM loaded and usable"; `classifier_current_version()` returns 15.
`tests-cram` covers the engine and the loader failure modes; the `CLF_ENABLED` half of `classifier.c` is
**not** host-covered (no such test flavour exists, and adding a seam was out of scope) — it is verified
by the arm64 cross-build, the G2 device leg and G3's zero `parser_only_fallback`.

**Single generation: `clf_ver` == `adult_clf_ver` == 15.** Both columns carry the same CRAM generation;
`clf_ver` is no longer "category/tv_kind generation only", because adult really is the same engine now.
`classifier_write_meta()` writes `clf_ver` 15, **`adult_clf_ver` 15** (a new `clf_meta_put` call it did
not make in the fastText era) and both container shas, gated on `manifest_ok`, so a degraded device
records no meta row rather than claiming a generation it did not run. `classifier_current_version()`
returning 15 makes both backfills auto-retarget, and on a v15 DB the `tc.adult_clf_ver < ?` predicate
makes a manual `--adult-backfill --write` a no-op on already-15 rows.

`/stats classifier` keeps `enabled`, `adult_mode`, `adult_hide_threshold`, `clf_ver`,
`adult_drop_threshold`, `total_classified`, `mean_us_per_item`, `adult_tagged`, `adult_dropped`,
`hard_block_dropped`, `parser_only_fallback`, `model_load_failures`, `tv_kind_model_used` (same key, new
meaning: "the kind head was consulted"), `file_cap_hits`, `by_category` and the `adult_backfill` object
(`state`, `target_adult_clf_ver`, `rows_total`, `rows_gated_out`, `rows_scored`, `rows_updated`,
`rows_failed`, `last_id`, `started_at`, `finished_at`, `last_error`); it **replaces**
`model_sha256_{adult,category,tv_kind}` with `cram_sha256` + `cram_vocab_sha256` and adds
`cram_adult_tagged_predictions`, `cram_feature_cap_hits` and a `cram` sub-object `{enabled, generation,
temperature, rows_predicted, load_failures, kind_head_used, file_cap}`. `generation` is the model version
the rows carry, deliberately **not** the manifest's container generation (2).

**Dashboard.** The crawler card gained a CRAM line under the "N torrents, N active trees, X metadata/s"
line — `CRAM v15 (sha 834159b642d6): N predicted, 0 load failures` — rendered from
`classifier.cram_sha256` + the `cram` object by `ControlServer.cramStatsLine()`, which returns null when
the body does not parse, carries no `classifier.cram` object, or reports `enabled` false (the card then
keeps its previous line, the `crawlerBackfillLine` contract). It is cached in `refreshCrawlerStats` and
pushed over the WS frame key `cramStatsLine`; the pinned-string test lives in `ControlServerHelpersTest`
(39 tests green). Measured on the phone: server-rendered row
`<span class="k">Classifier</span>… data-field="cramStatsLine"`, updating live.

`classify_backfill_mode` stays pinned **`off`** (`CrawlerConfig` write + `CrawlerKeys` allow-list;
operator decision), so the crawler's own backfill daemon never starts a pass at launch and publishes
state **`disabled`**, not `idle`. The C default stays `all` and the daemon's `adult` branch (auto-run the
gated pass) is implemented and host-tested but unpinned — flipping the pin is a one-line config change.
The token-gated adult one-shot (crawler patch 16, `--adult-backfill <db> [--write]
[--adult-backfill-limit <n>]`, dashboard action `POST /crawler/maintenance/adult-backfill`,
`MaintenanceAction.ADULT_BACKFILL`, space-gated, stops the supervised crawler and restarts it) is
**CRAM-backed now**: `classifier_classify_adult()` was rewired onto the same capped-50 → tparse → strip →
`cram_features` → `cram_predict` path, so a manual `--write` labels gated rows with the CRAM adult
marginal and stamps `adult_clf_ver` 15 instead of relabelling them `adult=0` (the defect a
fastText-shaped one-shot would have on a model-free device). `adult_backfill.c` and `classify_backfill*.c`
are otherwise byte-identical, and the `__adult_*` gate still scans the raw feature text.

**Measured on the clean install (device b93dac74, empty DB, live DHT ingest only, versionCode 4).** G2
device leg — 600 rows pulled from the live DB: **100 % / 100 % / 100 %** category/tv_kind/adult against
the host reference, max |Δadult_p| **1.221e-15**; the three-way check (host x86 C harness vs arm64 DB
verdicts) is **600/600 with |Δadult_p| = 0.000e+00** — bit-identical float64; the ungated raw kind head
would match only **225/600 = 37.5 %**, so the caller's "concrete kind ⇒ tv/anime" gate is demonstrably
live; 10 rows sit in [0.375, 0.5) so the operating point is exercised; diversity 11/12 categories, 5
concrete kinds, adult 126/474 = 21.0 % (DB-wide 21.8 %). G2 host: 10,000/10,000 on each field (bar
≥ 99.9 %), max |Δadult_p| 1.443e-15, max |Δconfidence| 1.554e-15, **max |ΔE| over all 48 states = 0.0**,
0 mismatches, diversity 12 categories / 6 kinds / both adult values. G3 — **6 h 08 m**, 37 samples,
**192,599** stored rows (38× the 5,000 bar): `clf_ver` **15 on all 192,599**, `adult_clf_ver` **15 on all
192,599**, top category movie **39.66 %** (bar < 60 %), 12 categories present, **0 rows** with a concrete
`tv_kind` outside tv/anime, and `classifier.enabled` true, `cram.enabled` true, `cram.load_failures` 0,
`parser_only_fallback` 0, `model_load_failures` 0 in **every** sample; `PRAGMA quick_check` ok on the
1,642,405,888 B checkpointed DB. *Caveat:* the crawler log is truncated on relaunch, so the
`0 SQLITE_CORRUPT` grep covers only the post-restart period — whole-window corruption evidence rests on
`quick_check` plus the zero error counters.

G4 — threshold **0.375 in all four places** (`/stats`, device `config.ini`, `models/v15/manifest.json`,
`config.ini.default`); `adult_p` min 6.23e-08, max 0.99999999999999867; boundary consistency
`adult=1 ⟺ adult_p ≥ 0.375` with **0 violations**; live tag rate **21.89 %** (42,161/192,599) reported
beside the heldout anchor (base 17.65 %, recall 0.90 / precision 0.848 at 0.375). **Two recorded
deviations:** (a) **21 rows carry `adult_p` marginally above 1** — 1.0000000000000002 to
1.0000000000000007, 1–3 ULP, all `adult=1`, verdict unaffected and reference-consistent (the marginal is
summed unclamped); (b) the **spot check MISSED** — of the 120-name corpus fixture only **3** were present
in the live DB at 76,695 rows (1 known-adult tagged p=0.8383, 2 known-clean untagged p=0.0008 / 0.0001,
**all 3 correct**), because ≥20-per-class is unreachable by live DHT ingest of a fixture built from an
older corpus (extrapolation ≈1.5 M rows). Recorded as a miss with numbers; no rows were injected and the
operator declined a criterion revision.

G5 — window mean **2.853 ms/item** (Δ540,079,636 µs / 189,281 items; every 10-min interval 2.05–3.52 ms)
against the 10 ms bar, lifetime mean 2.848 ms at window end; peak crawler `VmHWM` **1,822,308 kB = 1.7379
GiB** against the 2.3 GiB bar, **flat from uptime 0:58 for the remaining 4 h 50 m** while the DB grew to
192 k torrents — **−0.21 to −0.30 GiB lower** than the v13-era 1.95–2.04 GiB whole-process figure;
0/0/0 error counters across all 37 samples; `movie_kind.thermal_pauses` 0 and
`movie_kind_backfill.thermal_pauses` 0. **Open item, recorded honestly:** after the deliberate restart the
fresh process read **2,006 → 2,181 MB (≈2.13 GiB) in 12 minutes** before the phone's battery died; its
plateau was **never observed**, and the operator instructed that the re-measurement be skipped. So the G5
PASS covers the cold-start / grow-with-the-DB path — a warm restart over an already-1.6 GB DB sits closer
to the bar and is **unmeasured**.

G6 — `tools/rebuild-db-v2.sh --check-schema` exit 0 ("16 statements match"); `CrawlerDbSchemaTest` 13
tests green; `PRAGMA table_info(torrent_class)` **identical** to the `backup/CrawlerDbSchema.kt` replica
(15 columns, same names/order/types); the backup/restore round-trip was **not exercised**. **Premise
corrected:** a destination *is* configured on this device (`backup_db_enabled=true`, bucket `dht-backup`,
prefix `db`, `backup_last_success_date` 2026-11-22) — the plan's "clean device, no backup destination"
does not hold; the operator confirmed it is a separate bucket and instructed no change. Unexplained
observation recorded verbatim: `files/backup/db_bookmark.txt` mtime 18:26 with `last_torrent_id=4503 /
last_prefix_id=10110`.

G7 — **PARTIAL.** Every persistence leg PASSes: `.assets_version` `4|models/v15|models/v13` identical
before the deliberate restart, after it, **and after an unplanned cold boot**; `models/` exactly
`{v13, v15}` in all three; v15 model mtimes identical across all three (**no restage**, including across
power loss); `/stats` cram/vocab shas and `clf_ver` identical before vs after; `classifier_meta` =
`clf_ver` 15, `adult_clf_ver` 15, both cram shas, `adult_hide_threshold` 0.375000; `clf_ver` /
`adult_clf_ver` 100 % at 15 over 192,599 rows. **The ingest-resumption leg was SKIPPED** at operator
instruction (the phone was at 2 % battery, charging, crawler stopped) — skipped/unmeasured, not passed.

G8 — `movie_kind` generation **13**, `model_ready` true; backbone
`28270fa01100687969b541282814371062ab9e1a560bb7ea755243c822cf0d4c` and head
`2f994cbe792441be182717cc6faa46f3ff245db6d17af30d72cfc47bbefb7ae3` char-identical across `/stats`, the
v13 manifest and on-device `sha256sum`; **1,947/1,947** adult0-movie rows at `movie_kind_clf_ver` 13
(**0 NULL, 0 other**); masks non-NULL on all 1,947 with real genre bitmasks; v13 files byte-unchanged.

**Device state after the v15 rollout:** 192,599 torrents / 14,704,427 files, DB 1,642,405,888 B,
`versionCode` 4 / `versionName` "0.4.0", installed APK 540,330,690 B, `files/crawler/models/` = {v15,
v13}, `.assets_version` = `4|models/v15|models/v13` — that stamp format predates the APK diet; it is now
`<versionCode>|keywords`, and `files/crawler/models/` is populated by the crawler's `--fetch-models`
instead of by asset staging.

**Accepted costs (design §1 — carried, not waived).** tv_kind v1 heldout drift **+2.73 pp**, and v2 is
**val-validated only** (its heldout analogue is an extrapolation, never a measurement); val ECE
**0.0222 > the 0.02 bar** (heldout 0.0178 passes; the criterion never named a split);
`decided_conflict` rows (parser hint contradicts teacher) are **~90 % error by construction** and were
never a ship target; the no-gate tv_kind architecture **pays 15 errors on the 2,688 `decided_agree` val
rows** and buys back 94 conflict-row + 28 undecided-row errors; the shipped adult point **misses ~10 % of
adult rows** (352/3,519 heldout) and **flags 566 clean rows (2.8 %)** — in `adult_mode=drop` those are
deleted, in `tag` (the default) they are hidden; and the research stage-0 dump's `v14_adult_prob` column
is **inverted** (P(adult=0) or swapped columns) — the shipped model is unaffected (it trained on hard
`teacher_adult` labels) and nothing in the app consumes that dump.

**Do not retry (measured negatives upstream):** context/sibling features (no transfer on the wide space),
the 84-weight pairwise block, distillation at inference (teacher absent), energy-margin abstain,
proximal-L1 sparsity, Adam on this space.

**CRAM traps (each cost time on the v15 rollout).**
- **Energy sign.** `P = softmax(−E)`, answer = argmin; two independent sign bugs upstream. `adult_p` high
  = adult — an inverted adult read is exactly the `v14_adult_prob` defect, and G2's both-adult-values
  diversity check is its witness.
- **config > manifest for the threshold.** The template and the Kotlin default must move to 0.375 together
  with the manifest, or the device keeps a stale operating point (`ClassifierModelVersionMigration`
  protects the device on a generation change; the host is not protected).
- **The npz is not the container.** 4.69 MB compressed ≠ 133.9 MB dense ≠ 3.51 MB nonzeros; the shipped
  CSR is ~9.4 MB. All four are distinct quantities.
- **`kind_W`/`kind_b` is the shipped tv_kind head; `cat_Wk` is the kind unary inside the joint energy.**
  Swapping them silently degrades both fields.
- **crc32 (polynomial 0xEDB88320), `<token>` markers included, UTF-8 bytes** — Python's salted `hash()`
  would differ per process.
- **Train-only vocab, frozen lists.** Rebuilding the sorted df≥2 lists from any other population is a
  vocab leak and a different model.
- **Empty feature rows are legal in C** (bias-only energies); the Python `Csr` empty-row hard-fail is a
  reference-side asymmetry, not a serving rule.
- **Never refit.** `heads`/`kindfit` are unseeded and heldout is spent; the export only converts and
  proves bit-identity.
- `classifier_enabled` / `movie_kind_enabled` are parsed with `atoi`, so `= true` means 0 (off). Use `= 1`.
- `log_level = DEBUG` writes ~1.9 GB/h — INFO for every device gate.
- `make clf-backfill` takes `-I` from the app's tparse copy but `TPARSE_LIB` from the model repo's
  prebuilt archive — override `TPARSE_LIB` or you measure the wrong parser.
- Adding a `torrent_class` column means five places in the C tree plus the Kotlin mirror — see invariant 14.

**dht_crawler history — merged in-tree.** The crawler's features used to ship as 25 `.patch` files applied
by `prepareDhtCrawlerSrc`. Twenty-two of them are now **committed directly in the `dht_crawler`
repository** (one commit per former patch, subjects prefixed `crawler:`), and their patch files are
deleted, so a plain clone of `dht_crawler` builds standalone with its own `make` — no Android pipeline
required. `app/build.gradle.kts` and `app/src/main/cpp/dht_crawler/patches/` now carry **only the three
fixes that patch third-party submodules** — `bencode-cap-fix.patch` → `bencode-init-fix.patch` →
`civetweb-stop-flag-atomic.patch` — because `lib/bencode-c` and `lib/civetweb` are upstream submodules
(their own `make` applies the same three, guarded and idempotent; the Gradle list exists only because the
Android build compiles the copied tree with CMake/NDK and never runs that Makefile). New submodule fixes
go LAST, as the 4th slot; every other crawler change is a normal commit in the crawler repo.
The crawler's own `make tests` (16 binaries) is hermetic — no Android checkout needed: the CRAM v15
container is vendored at `tests/fixtures/cram/models/v15` (override `CRAM_MODEL_DIR`) and utf8proc
2.12.0 at `vendor/utf8proc`. `CLF_ANDROID_DIR` is gone; `tparse/` (80 KB) is vendored so the classifier
flavours build standalone.

**The crawler now ships and feeds itself.** `make` alone compiles both engines **inert** (no
`CLF_ENABLED` / `MK_ENABLED`); the flavours are `make clf` (CRAM only, `build/clf/dht_crawler`),
`make llama` (pinned llama.cpp `18ab62a8`, tarball sha `34bd70e2…`, static CPU-only into
`build/llama-install`) and `make full` (both engines, `build/full/dht_crawler`). Weights come from
`make models` → `tools/fetch-models.sh`, which downloads **`https://unjumble.au/bep51/models/<gen>/`**
(v15 `cram.bin`/`cram_vocab.bin`/`manifest.json`, v13 `movie_kind_backbone_Q4_0.gguf`/`movie_kind_head.bin`/
`manifest.json`, ~490 MB) and verifies each file against the sha256 pinned in the script before installing
it into `./models/<gen>` — the compiled-in default dirs, so no config change is needed. The bucket is the
R2 `unjumble` bucket (`bep51/` prefix) behind the `unjumble.au` custom domain; the app no longer stages its own
copy into APK assets — it runs the same `--fetch-models` one-shot at crawler start (see *Movie-kind
classifier (v13)*).

**The BEP51 cache is fetched by the crawler too** (it used to be the Kotlin app's job only).
`bep51_cache_url` (default `https://unjumble.au/bep51/bep51_cache.dat`, ~1.9 MB / 50,000 nodes; empty
disables), `bep51_cache_fetch_timeout_sec` (30) and `bep51_cache_ca_file`/`_dir` (empty = system trust
store) drive a cold-start fetch in `supervisor_start()` via the new `src/http_fetch.c` (OpenSSL, chain +
hostname/IP-SAN verification, redirects, chunked, byte cap, deadline). The download is validated with the
loader's own magic+sha256 checks against a scratch cache and renamed into place only on success, so a
failure falls through to the router bootstrap **unchanged** — which matters because that path aborts
startup unless it finds `global_bootstrap_target` nodes. `--fetch-bep51-cache` runs the same fetch as a
one-shot. Measured on a clean tree: cold start → fetch 50,000 nodes → `/stats` in **2 s** (previously
100 s+ or an abort).

`config.ini` is **no longer tracked** in the crawler repo (invariant 11: it carries the operator's
ssh/rclone backup host, user and paths). It stays on disk; the sanitized template is
`app/src/main/assets/dht_crawler/config.ini.default`, which now carries the four `bep51_cache_*` keys.

The merged series, in its original order (the numbers are the old series slots, kept because older plans
and measurement reports cite them): `movie-kind-v10-streaming` (last-token pooling + inline ingest
classification), `adult-v11-backfill` (the token-gated adult backfill, `adult_clf_ver`,
`classify_backfill_mode`), `movie-kind-v13-default` (flips only the compiled-in model-dir default — three
literals in `src/config.c`, `include/movie_kind.h` `MOVIE_KIND_MODEL_DIR_DEFAULT` and the
`include/config.h` comment), `inflight-dedup` (18th) and `getpeers-metadata-tuning` (19th: the `/stats`
success-metric split + per-outcome metadata failure attribution + get_peers
round-idle/slot-budget/finish-on-peers/miss-penalty mechanisms + metadata piece-idle eviction, all as
**opt-in knobs shipped at legacy defaults** — five 30-minute on-device windows measured the tuning
net-negative, so the commit ships the instrumentation and the keys, not the behaviour),
`stats-last-interval` (20th: last-interval "windowed" rates in `/stats aggregate` — the pure
`stats_window` module plus a sampler on the supervisor monitor thread; additive only, every lifetime key
keeps its exact meaning), `metadata-peer-fail-map` (21st: the fail-open per-address TCP connect-failure
memory — `src/meta_peer_fail.c` + `include/meta_peer_fail.h`, host-tested by
`make tests-meta-peer-fail`), `meta-peer-fail-capacity` (22nd: the capacity corrective, 8192 → 131,072
slots — constant + comments only, no behaviour change), `bep51-funnel-stats` (23rd: the BEP51 query +
dedup-funnel counters — per-tree `trees[].bep51` and summed `supervisor.bep51`, counters only, no
behaviour change and no config key), `shutdown-abort-metadata-fetch` (24th:
`tree_fetch_metadata_parallel()` breaks when `shutdown_reason` is `SHUTDOWN_REASON_SUPERVISOR` — the
metadata workers only re-check the stop *between* fetches and `thread_tree_destroy()` joins them
unbounded, so one in-flight 60 s attempt held every crawler stop (settings apply, app exit, maintenance
one-shot) for the whole metadata deadline; the same hunk makes that deadline monotonic-ms instead of
`time(NULL)` seconds, whose `difftime > timeout/1000` test could overrun to 61 s and cross the app's
SIGTERM grace. `SHUTDOWN_REASON_RATE_BASED` respawns are excluded by the reason gate, and no counter, key
or `/stats` field changes) and `cram-category-tvkind` (25th and last: the whole CRAM v15 single-engine
integration — new `src/cram.c` + `include/cram.h` (container loader with sha verification, the
feature/CSR build, `cram_predict`'s masked 48-state argmin + raw adult marginal,
`cram_strip_adult_tokens`), new `tests/test_cram.c` + the `tests-cram` / `tests-asan-cram` Makefile
targets, the `src/classifier.c` rewire (fastText/ftc removed, 50-file cap, adult rewire onto CRAM,
all-or-nothing degraded path, `adult_clf_ver` 15 in `classifier_write_meta()`), the `classifier_stats_t`
+ `src/http_api.c` `/stats` shape (`cram_sha256`/`cram_vocab_sha256` + the `cram` sub-object), and the
compiled-in `classifier_model_dir` default in `src/config.c`).

See the vendored repo's `CLAUDE.md` for its architecture (thread trees, bloom filters, schema) and its
`CONFIG_INI.md` for the get_peers/metadata config keys and the last-interval / peer-fail / BEP51 `/stats`
blocks.

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
(patch 22, in-tree as `META_PEER_FAIL_CAPACITY` in `include/meta_peer_fail.h`): 131,072 × 3/4 = 98,304 clear threshold > the ~66,000–79,800
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
`metadata_connect_fail_addresses_total` (NEW distinct addresses admitted, not events). **The template now
ships the key at the measured A/B winner, `600`** (W3, +9.4 % metadata/s) — the C default stays `0`, so
the fail-open rule still holds for a config that omits the key; only the shipped Android config turns it on.
`gp_probe.py` reads the block (`MPF_KEYS`, a `peer-fail map` row, a missing-feature check) so a window
report can never show a blank column.

**BEP51 query + dedup-funnel counters (patch 23).** The 23rd and last commit of the merged series changes **no behaviour and no config key**: eight per-tree atomics — `queries_sent_total`,
changes **no behaviour and no config key**: eight per-tree atomics — `queries_sent_total`,
`responses_received_total`, `timeouts_total`, `samples_offered_total`, `bloom_rejected_total`,
`inflight_refused_total`, `queue_full_total`, `samples_pushed_total` — are published per tree as
`trees[].bep51.*` and summed as `supervisor.bep51.*`, cumulative-folded at respawn like the other `gp_*`
counters. The four gates are mutually exclusive, so per window
`offered = bloom_rejected + inflight_refused + queue_full + pushed` (±1 % slack for in-flight boundary
effects); `tools/crawler/gp_probe.py` reads both blocks (`BEP51_KEYS`, `_bep51_norm()`, three table rows) and
prints the identity gap, so a blank funnel column is impossible. No host test: `thread_tree.c` /
`http_api.c` have no harness and the change is additive atomic increments plus cJSON emission — it is
validated on device by key presence and the accounting identity, and the 14 `tests:` targets (15 binaries)
all pass with no existing target changed — `tests-cram` and `tests-asan-cram` (patch 25's CRAM engine and
loader failure modes) are the additions; the `tests:` aggregate now lists 14 targets and the `.PHONY` line
carries the two new names.

**Measured on this run — seven 30-minute windows (2026-11-17, 64 trees / get_peers 18 / BEP51 3 /
metadata 10 workers, pp 2, pps cap 3000, `log_level=INFO`; full record `plan/getpeers-metadata-results.md`).**
Three same-hour, same-build pairs, one variable each:

- **`tree_parallel_peers` 2 vs 4 (W0/W1) — 2 ships.** metadata/s 6.834 vs 6.408 (−6.2 %, outside the <5 % tie
  band), connect events 405,152 vs 427,348 with attempts 84,789 vs 88,063 and window success 14.47 % vs
  13.06 %: pp=4 bought more concurrent connections (10.60 → 11.28 per tree) and more attempts, and no more
  fetches. The template already carried 2, so nothing changed in the repo. This reverses the patch-19 run's
  confounded LEGACY 6.75 vs SHIPPED 5.12 comparison.
  600, and so does the shipped default.** Validity gate met
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
   wiped on rebuild. Crawler fixes are normal commits **in the `dht_crawler` repository itself** (it is a
   fork, `wbpxre150/dht_crawler`); fixes to its `lib/` submodules go in `dht_crawler/patches/` and are
   applied by `prepareDhtCrawlerSrc`; transmission changes go in
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
