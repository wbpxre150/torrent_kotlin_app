# Plan 1: BEP51 Cache — S3 Backup (pure-Kotlin SigV4) + Wizard Bootstrap Download

Status: outline, not yet reviewed
Depends on: nothing (this plan builds the shared SigV4 module that Plan 2 reuses)

## Goal

1. Periodically upload the DHT crawler's bootstrap-node cache (`filesDir/crawler/data/bep51_cache.dat`,
   ~2 MB at capacity 50,000 nodes) to an S3 bucket using a **pure-Kotlin AWS SigV4 client** — no rclone,
   no new native binary (saves ~26 MB of APK size).
2. Add a setup-wizard setting: an HTTPS URL from which a fresh install **downloads the cache file**
   before the crawler first starts, so new clients warm-start with known-good BEP51-capable nodes
   instead of cold-bootstrapping.

## Background / established facts (from exploration)

- `bep51_cache.dat` is a **bootstrap peer cache** (20-byte node_id + family + IPv4/IPv6 + port), NOT an infohash index.
  Format: 12-byte header (magic `"BEP1"`, big-endian version, big-endian node_count) + v2 records of 39 bytes each
  + 32-byte SHA-256 trailer over header+records (`bep51_cache.c:15-21, 208-465, 503-591`). Atomic save (tmp + rename).
- Load-time validation is strict: bad magic/version/size/checksum → load fails and the crawler proceeds with a
  **cold bootstrap** (missing file is a debug-level "first run?", not an error). v2 without a checksum trailer is
  accepted with a warning. Over-capacity node counts are accepted (FIFO eviction).
- The cache is written to disk **only at shutdown and on bootstrap failure** (`supervisor.c:819, 1015`) — there is
  **no on-demand or timer-driven save** and no CLI flag for it (`main.c` flags: `--rebuild-bloom-filter`,
  `--porn-filter-update`, `--compact`, `--check-database`, `--recover-database`). A crawler patch is required
  for a periodically-fresh cache.
- Wizard: single synchronous screen in `MainActivity.kt` (`WizardScreen`, lines 316-397); gated by the
  `wizard_completed` pref; no skip path. `DaemonService` is already bound/running while the wizard shows, but the
  crawler only starts after `completeWizard()` → `startConfiguredDaemons()`. New field plumbing:
  `WizardSubmit.Form` → `AppConfig` → `ConfigStore` → `ConfigValidator` (no URL validator exists yet).
- `CrawlerConfig.ensureLayout()` is idempotent and never deletes `data/` — a pre-placed cache file needs **no
  special sequencing** before first crawler start.
- Network security config permits **HTTPS only** for non-loopback hosts — the bootstrap URL must be HTTPS.
- No HTTP client library exists (no ktor-client/okhttp); raw `HttpURLConnection` is the established pattern
  (`CrawlerProxyServer`, `TransmissionRpcClient`). SigV4 signing is pure string/HMAC work on top of this.
- No scheduler exists anywhere in the app (only a 3 s UI refresh loop). `DaemonService` is the composition point
  (AGENTS invariant 12).
- The crawler settings form allow-list (`CrawlerKeys`) and `PINNED_KEYS` deliberately reject/pin all `backup_*`,
  `ssh_*`, `rclone_*` keys — all new settings for this feature stay **app-side** (SharedPreferences), never in
  crawler `config.ini`.

## Architecture overview

- New pure-Kotlin module `s3` (or package `com.torrent.app.s3`): SigV4 signer + minimal S3 object client
  (PUT only for this plan) over `HttpURLConnection`. No Android dependencies → plain-JUnit testable (house style).
- New app-side settings group "Bootstrap backup" in `ConfigStore`/`AppConfig`, surfaced in the wizard and the
  dashboard crawler panel: R2 endpoint (`https://<accountid>.r2.cloudflarestorage.com`), R2 access key ID +
  secret, bucket, object key, daily schedule, and the bootstrap download URL — a **separate HTTPS endpoint you
  control**; the app performs a plain anonymous GET and never needs S3 read access for bootstrapping.
- New `Bep51BackupManager` wired in `DaemonService.onCreate`: schedules uploads, runs them on its own
  single-thread executor, consults the existing `busy()` gate, exposes status to `ControlServer`.
- Small **crawler patch** (in `dht_crawler/patches/`, never vendored edits): periodic cache save so the file on
  disk stays fresh while the crawler runs for days.
- Wizard step: on "Finish setup", if a bootstrap URL is configured and no cache file exists, download + validate +
  place the file into `filesDir/crawler/data/` before daemons start.

## Stages

### Stage 1 — SigV4 signer + S3 PUT client (pure Kotlin)
- Implement canonical-request/string-to-sign construction, HMAC-SHA256 signing chain, `Authorization` header,
  `x-amz-date`, `x-amz-content-sha256`, for a single `PUT /{bucket}/{key}` object upload.
  **Decided: Cloudflare R2** — path-style addressing, region string `auto` (no meaningful region).
- Retry with exponential backoff on 5xx/network errors; distinguish 4xx (no retry).
- Exit criteria: unit tests of the signer against known AWS SigV4 test vectors; PUT verified against a real or
  localstack-style S3 endpoint from host JVM tests.

### Stage 2 — Settings surface
- New `BackupSettings` (or extend `CrawlerSettings`) data class: `r2Endpoint`, `r2Bucket`,
  `r2ObjectKey` (`bep51/bep51_cache.dat.gz`), `r2AccessKeyId`, `r2SecretAccessKey`, upload cadence
  (**daily, calendar-day trigger**), `bootstrapUrl` (download side), `bootstrapEnabled`.
- Plumb through `ConfigStore` (new pref keys + load/save), `ConfigValidator` (new URL validator: https scheme,
  non-empty host; reject http), wizard form + dashboard panel form.
- Secret handling: **decided** — plain SharedPreferences, same storage class as the existing control token.
- Exit criteria: wizard and dashboard can persist/reload the settings; invalid URLs are rejected with form errors;
  `CrawlerKeys`/crawler `config.ini` untouched.

### Stage 3 — Crawler patch: periodic cache persistence
- Patch (new file in `dht_crawler/patches/`, wired into `prepareDhtCrawlerSrc`'s fixed patch order) adding a
  timer-driven `bep51_cache_save_to_file()` call in the supervisor (reusing the existing atomic save path and the
  cache's internal lock), so the on-disk file stays fresh while the crawler runs continuously.
- **Decided: supervisor timer** (not an HTTP save-now route). Pick a save interval that bounds staleness without
  excessive disk churn (e.g. hourly — the upload runs daily).
- (A one-shot CLI flag was rejected: the cache lives in the running process's memory, so an external flag cannot
  save it.)
- Exit criteria: `libdhtcrawler.so` rebuilt via the Gradle task, `jniLibs` updated (AGENTS invariant 4); with the
  patch, the file's mtime advances while the crawler runs; crawler still loads the file fine on restart.

### Stage 4 — Upload manager (DaemonService wiring)
- New collaborator constructed in `DaemonService.onCreate` (invariant 12): single-thread executor, scheduler
  (`ScheduledExecutorService` is sufficient — the FGS outlives it; WorkManager only if restart-resilience is wanted).
- Upload flow: check `busy()` (don't race apply/maintenance) → verify cache file passes the same
  validation the crawler does (magic/version/size/SHA-256) → **gzip-compress** → PUT to `r2Bucket/r2ObjectKey`
  with `Content-Type: application/octet-stream` (or `application/gzip`) → record status.
- **Decided: fixed object key, overwrite** — no timestamped history copies (R2 keeps object versioning if you
  ever want history on the bucket side).
- `ControlServer`: `post("crawler/backup/upload")` manual trigger + `get("api/crawler/backup")` status polling,
  copying the maintenance-route pattern; auth via the existing token interceptor.
- **Decided: Wi-Fi-only gate** — reuse the `CrawlerGate`-style check used for crawler ops; cadence is **daily**.
- Exit criteria: manual trigger from dashboard uploads the file and the object is fetchable via plain HTTPS GET;
  status visible on the dashboard; upload skipped while an apply is in flight.

### Stage 5 — Wizard bootstrap download
- On "Finish setup" (after validation, before `completeWizard()`/`startConfiguredDaemons()`):
  if `bootstrapEnabled` && `bootstrapUrl` set && `filesDir/crawler/data/bep51_cache.dat` does not exist:
  download to a temp file in `filesDir`, **validate in Kotlin** (magic `BEP1`, version 2, exact size
  `12 + n*39 [+ 32]`, SHA-256 trailer match when present), then atomically rename into place.
- Because the crawler starts only after wizard completion, there is no race with crawler writes; `ensureLayout()`
  has already created `data/` idempotently.
- Failure behavior: any failure (network, validation) → log a note, continue wizard normally; the crawler
  cold-bootstraps exactly as it does today (graceful degradation — a bad URL must never block setup).
- UI: synchronous is unacceptable for a multi-MB download; add a small async download state to the wizard
  (progress or simple spinner) using `Dispatchers.IO` + `MutableStateFlow`, per existing patterns. Disable the
  "Finish setup" button while the download runs, or run it after daemon-start with a note — pick during review.
- **Decided: also re-download on later app starts** when the cache file is missing (e.g. after clear-data) and a
  bootstrap URL is configured — same download+validate+place flow, run by `DaemonService` (not the wizard UI).
- Exit criteria: fresh install with a bootstrap URL starts the crawler with a warm cache (verify via `/stats`
  `bep51_cache.cached_nodes` > 0 shortly after start); fresh install without a URL behaves as today; corrupt URL
  payload is rejected and does not end up on disk; a missing cache on a later start gets re-downloaded.

### Stage 6 — Tests
- Plain JUnit4 for: SigV4 signer vectors; cache-file validator (good v2, v2-without-trailer, corrupt checksum,
  truncated, bad magic); settings round-trip; URL validator; busy-gate interactions.
- Robolectric where Context/prefs needed. No androidTest (house rule).
- Exit criteria: `./gradlew :app:testDebugUnitTest` green.

## Explicitly out of scope
- rclone (rejected to save ~26 MB APK size; pure-Kotlin SigV4 chosen).
- Any crawler `config.ini` backup keys (stay pinned off; all settings app-side).
- DB backups (Plan 2) — but Stage 1's S3 module is shared and must be designed for reuse
  (GET + LIST for restore-from-bucket, DELETE for app-managed retention).

## Decisions (operator-confirmed)

1. **S3 provider: Cloudflare R2** — path-style addressing, region `auto`, no virtual-hosted addressing needed.
2. **Bootstrap URL: separate HTTPS endpoint** you control (CDN/website) — wizard does a plain anonymous GET;
   the app needs no S3 read access for bootstrapping. The R2 object is synced out-of-band by you.
3. **Crawler patch: supervisor timer** — periodic in-process cache save (not an HTTP save-now route).
4. **Compression: gzip** — both the upload and the wizard download side must gunzip before validation/placement.
5. **Cadence: daily** (calendar-day trigger), same as the crawler's original backup semantics.
6. **Network gate: Wi-Fi only**, consistent with crawler gating.
7. **Post-wizard bootstrap: also re-download when missing** on later app starts.
8. **Secrets: plain SharedPreferences**, same storage class as the existing control token.
9. **Object keys: fixed, overwritten** — no timestamped history copies.
