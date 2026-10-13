# Plan 2: Database Backup to S3 — Compressed Incremental SQL via Pure-Kotlin SigV4

Status: outline, not yet reviewed
Depends on: Plan 1 Stage 1 (shared SigV4/S3 client; this plan needs PUT, GET, **LIST** for restore-from-bucket,
and **DELETE** for app-managed retention)
- Real infrastructure (operator-provided, see "Real infrastructure" section): DB backups go to the private R2
  bucket **`dht_backup`**. The BEP51 cache (Plan 1) lives in the separate bucket **`unjumble`**, which also
  backs the public `https://unjumble.au/files/bep51_cache.dat` bootstrap endpoint.

## Goal

Back up the crawler's SQLite database (`filesDir/crawler/data/torrents.db`, internal storage — no SAF bridge)
to an S3 bucket as **compressed incremental SQL text**, mirroring the crawler's original Arch-Linux backup
design: `VACUUM INTO` snapshots are too slow to push over the internet, whereas incremental `INSERT` dumps are
small, fast to upload, and restore is "inject into a fresh DB" — still thousands of times faster than
re-crawling the network. Pure-Kotlin SigV4 (no rclone) to keep APK size unchanged.

## Background / established facts (from exploration)

- The crawler's own backup pipeline (`batch_writer.c`) — the design we are porting — works like this:
  - Tables dumped, in order: `path_prefixes(id, prefix)` → `torrents(id, info_hash, name, size_bytes,
    total_peers, added_timestamp)` → `torrent_files(id, torrent_id, prefix_id, filename, size_bytes, file_index)`.
    Base tables only, **no FTS5, no schema** in the dump.
  - Rows are gated by a bookmark cursor file with exactly two lines:
    `last_torrent_id=<int64>\nlast_prefix_id=<int64>\n`, written atomically (tmp+rename). First run → both -1
    → dump everything (the first dump is effectively a full dump).
  - Emitted statements come from `sqlite3 .mode insert` + `sed 's/^INSERT INTO /INSERT OR IGNORE INTO /'`
    → idempotent replay, dedup on primary keys. Blobs (`info_hash`) become `x'...'` hex literals.
  - Filename convention: `incremental_YYYYMMDD_START-END.sql.zst` where START = last_torrent_id+1,
    END = max_torrent_id dumped.
  - Dump source is the **local backup DB** when configured (to avoid holding WAL locks on the live DB).
- **No restore code exists anywhere**: the crawler never replays its own dumps. The only DB-rebuild path is
  `database_recover(src, dst)` behind `--recover-database` — recreates schema (`CREATE_TABLES_SQL` +
  `CREATE_FTS_SQL` + `CREATE_TRIGGERS_SQL`) and row-copies with original IDs, FTS rebuilt by the AFTER-INSERT
  triggers (`torrents_ai`, `files_ai`).
- **FTS5 constraint**: Android's bundled framework SQLite gained FTS5 only at API 28. A Kotlin-side restore
  that creates the full schema (with FTS5 virtual tables/triggers) fails on API 26/27. The crawler bundles its
  own SQLite 3.49.1 with FTS5, so a restore executed **through the crawler binary** is immune to this.
- zstd is **not linked** anywhere — it was only an external CLI in the crawler's `system()` pipeline. Kotlin must
  use `java.util.zip` (gzip/deflate, zero deps) or add a zstd JVM dependency (see questions).
- The DB is **append-only** in practice (crawler inserts; delete-triggers deliberately omitted from backup DB),
  so an incremental cursor model never misses rows.
- DB runs in WAL mode with manual checkpoints after flushes; a **read transaction** gives a consistent snapshot
  without blocking the crawler (readers never block the writer in WAL).
- Existing integration seams (shared with Plan 1): `DaemonService` composition point, `CrawlerMaintenance`
  stop-run-restart one-shot pattern, `busy()` mutual exclusion, `ControlServer` maintenance/status route pattern,
  app-side settings only (crawler `config.ini` backup keys stay pinned off).

## Architecture overview

- Reuse Plan 1's S3 client, extended with GET, object listing (restore reads the newest chain straight from the
  bucket) and DELETE (app-managed retention).
- New pure-Kotlin `SqlDumpWriter`: reads the DB inside a single read transaction, emits incremental SQL text
  using the crawler-compatible format (bookmark cursors, `INSERT OR IGNORE`, `x'hex'` blobs, table order).
- New `DbBackupManager` in `DaemonService`: schedules dump+compress+upload, persists the bookmark, exposes
  status/manual trigger via `ControlServer`.
- Restore = crawler patch adding a `--restore-database <dump.sql[.zst|.gz]>` CLI maintenance mode that replays
  the dump into a fresh DB using the existing recover-style flow (schema + triggers + `INSERT OR IGNORE`),
  executed as a one-shot via `OneShotRunner` while the crawler is stopped — reuses the entire
  `CrawlerMaintenance` stop/restart machinery.

## Real infrastructure (operator-provided)

- **R2 bucket `dht_backup`** — private; this plan's DB dumps (`db/incremental_YYYYMMDD_START-END.sql.gz`) go here.
- **R2 bucket `unjumble`** — separate bucket holding Plan 1's cache object (`bep51/bep51_cache.dat.gz`); it also
  backs the public `https://unjumble.au/files/bep51_cache.dat` bootstrap endpoint (out-of-band sync). Never a
  DB-backup target.
- Credentials: R2 access key ID + secret (rclone-style `~/r2_creds.txt`, `[r2]` section) — configure on the
  device via the dashboard/wizard; never commit them to the repo or tests.

## Stages

### Stage 1 — SqlDumpWriter (pure Kotlin, no Android deps)
- Open `torrents.db` read-only inside one transaction: read `last_torrent_id`/`last_prefix_id` from the bookmark,
  read `MAX(id)` for both tables, then stream `SELECT *` rows with `id > cursor` (and `torrent_files` gated on
  `torrent_id > last_torrent_id`) — all within the same snapshot so the cursor and the rows can never disagree.
- Emit SQL: keep the crawler-compatible statement shape (column-order `INSERT OR IGNORE INTO <table> VALUES(...)`,
  `x'hex'` blobs, NULL literal, bare integers; table order `path_prefixes → torrents → torrent_files`), with
  **standard SQL single-quote escaping** (`''`). **Decided: not byte-compatible** with `sqlite3 .mode insert`
  output — we control both writer and reader; old Arch-era `.sql.zst` dumps cannot be restored through this path
  without a transcoding step.
- Exit criteria: round-trip unit tests against a fixture DB (host JVM, plain JUnit4); dump of a fresh DB is a
  full dump; second run with unchanged DB produces zero statements; third run only new rows.

### Stage 2 — Compression + object naming
- **Decided: gzip** via `java.util.zip` (zero new dependencies; no zstd parity with the old archives).
- Object key convention mirroring the crawler: `db/incremental_YYYYMMDD_START-END.sql.gz` under the same bucket
  as Plan 1 (different prefix). START/END from the torrent-id range dumped.
- Exit criteria: compressed object decompresses to byte-identical SQL; name round-trips the id range.

### Stage 3 — Bookmark + upload orchestration
- App-side bookmark file (e.g. `filesDir/backup/db_bookmark.txt`, same two-line format, atomic tmp+rename write).
  Update order: upload succeeds → bookmark advances (never the reverse, so a failed upload is naturally retried).
- `DbBackupManager` in `DaemonService.onCreate`: single-thread executor + scheduler; run only when not `busy()`
  (apply/maintenance) — the crawler itself can keep running during a dump (WAL snapshot read), but
  coordinate if we prefer quiet periods.
- Reuse Plan 1's settings surface: add DB-backup enable flag, key prefix, cadence to the same settings group.
- `ControlServer`: `post("crawler/backup/db")` + status endpoint, following the maintenance-route pattern.
- Exit criteria: two consecutive scheduled runs produce one full + one (possibly empty) incremental object; killing
  the app mid-upload leaves the bookmark unchanged and the next run re-uploads the same range (idempotent thanks
  to `INSERT OR IGNORE`).

### Stage 4 — Restore path (crawler patch + one-shot exec)
- Patch `dht_crawler` (new file in `patches/`, appended to the fixed patch order): a `--restore-database <path>`
  maintenance mode in `main.c` alongside `--recover-database`: create fresh DB with `CREATE_TABLES_SQL` +
  `CREATE_FTS_SQL` + `CREATE_TRIGGERS_SQL`, decompress (if compressed with a codec the C side supports —
  simplest: Kotlin decompresses before invoking; keep the crawler dumb), replay statements with
  `INSERT OR IGNORE` in one transaction, integrity-check, atomic swap over the old DB, `rename()` like
  `database_recover` does.
- Kotlin flow: stop crawler supervisor (same as maintenance) → **list objects in the bucket** (decided:
  restore reads from the bucket), order oldest → newest (the first object is the full baseline) → download +
  decompress each → run `OneShotRunner` with `--restore-database` per dump (or a combined temp file) → restart
  crawler. Reuse `CrawlerMaintenance`'s status/ring-buffer/restart pattern.
- Expose as a maintenance-style action on the dashboard (explicit user action only in this plan — no automatic
  restore).
- Exit criteria: on a fresh install, restoring a full + N incrementals yields a DB that passes
  `--check-database`, FTS search works (verify via `/search`), and the bookmark is reset so the next backup
  starts a new range.

### Stage 5 — Scheduling + settings polish
- **Decided: single ever-growing incremental chain** — no periodic full re-anchor dumps. Consequence: restores
  replay the whole chain (each object is small, so this stays cheap), and **nothing in the chain can ever be
  safely deleted** until a compaction story exists.
- **Retention (decided: app-managed, needs DELETE in the S3 client)**: with a single chain and fixed keys, the
  only deletable objects today are orphaned/aborted uploads (e.g. a failed multipart or an upload interrupted
  before the bookmark advanced). Chain compaction — re-baselining and deleting the superseded prefix — is future
  work; until then the bucket grows one small object per day.
- **Cadence: daily** (calendar-day trigger), **Wi-Fi-only** — consistent with Plan 1.
- Exit criteria: settings round-trip; schedule survives service restarts (persist next-run time, not just in memory).

### Stage 6 — Tests
- Plain JUnit4: dump-writer snapshot/cursor/escaping/blob tests against fixture DBs; bookmark atomicity and
  upload-failure ordering; object-name round-trip; restore-orchestration state machine (with fake one-shot runner).
- Host-level verification of the crawler patch: build `libdhtcrawler.so`, run `--restore-database` against a
  generated dump on-device or via the host test build, `--check-database` green.
- Exit criteria: `./gradlew :app:testDebugUnitTest` green; manual restore verified once on a real device.

## Explicitly out of scope
- rclone and the crawler's built-in `rclone_*`/`ssh_*` backup keys (stay rejected/pinned; the `system()` pipeline
  with `sqlite3`/`zstd`/`ssh` binaries cannot work on Android anyway).
- Automatic/cloud-initiated restore (restore is an explicit user action from the dashboard).
- `VACUUM INTO`-based snapshots over the network (rejected by design: too slow to upload).

## Decisions (operator-confirmed)

1. **Compression: gzip** via `java.util.zip` — zero APK cost; no zstd parity with old `.sql.zst` archives.
2. **Dump format: standard SQL escaping** (single quotes, `''`) — not byte-compatible with `sqlite3 .mode insert`;
   old Arch-era dumps would need transcoding to be restorable via this path.
3. **Restore executor: crawler-side `--restore-database` flag** — FTS5-safe via the bundled SQLite 3.49.1,
   immune to the API 26/27 framework FTS5 gap.
4. **Chain model: single ever-growing incremental chain** — no periodic full re-anchor; restore replays the
   whole chain.
5. **Restore source: from the bucket** — app lists + downloads objects (S3 client needs LIST and GET).
6. **Cadence: daily**, **Wi-Fi-only** (same as Plan 1).
7. **Encryption: provider-side only** (R2 encrypts at rest) — nothing app-side.
8. **Retention: app-managed** (S3 client needs DELETE) — for now this only covers orphaned/aborted objects;
   chain compaction is future work (see Stage 5).
