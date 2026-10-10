# Stage 10 — dht_crawler web control panel

**Builds on:** Stage 09 (crawler built, seeded, supervised, `http_port`/`http_bind` keys exist),
Stage 07 (control server, config ownership, apply/rollback), Stage 06 (`WifiGateMonitor`).
**Replaces:** the old Stage 10 (crawler + rclone panels). No backup or rclone UI. The
`settings/rclone` stub and the "Backups (soon)" link are removed.

## Goal

Replace the `/settings/crawler` stub in `ControlServer` with a full panel. It configures,
controls and monitors the crawler from the same authenticated web surface as transmission and
FTP. It also puts the crawler's own search UI behind the control server's auth, and migrates
the existing database out of Termux's home into the app's internal storage.

## What gets built

### 1. Crawler settings panel (`GET/POST /settings/crawler`)

Kotlin is the single writer of `config.ini` (Stage 09 `CrawlerConfig`). Extend
`CrawlerSettings` in `AppConfig`/`ConfigStore`/`ConfigValidator` and render server-side, in the
same style as the transmission/FTP panels.

- **General:** enabled toggle (start/stop + persist), wifi-only toggle, `log_level`,
  `dht_port`, `nice_level`, "skip startup integrity check" (`--dont-check-database` arg).
- **Resources** (shown prominently): `num_trees`, `tree_find_node_workers`,
  `tree_bep51_workers`, `tree_get_peers_workers`, `tree_metadata_workers`, queue capacities.
  Show a live **estimated thread count** (`num_trees × Σworkers` + overhead) and warn above a
  threshold (e.g. > 3,000, compared with ~2,200 for the Termux-proven 16-tree config).
- **Filters:** `porn_filter_enabled`, `language_filter_non_latin_threshold`.
- **Bloom:** `bloom_capacity`, `failure_bloom_capacity`, `bloom_error_rate`. Changing these
  invalidates the persisted filters, so the UI offers to run `--rebuild-bloom-filter` after apply.
- **Advanced overrides:** a `key=value` textarea checked against an allow-list of known
  `config.c` keys. **Excluded:** all path keys (`db_path`, `bloom_path`, `*_cache_path`,
  `*_bookmark_path`, keyword files) and **all backup keys** (`backup_*`, `ssh_*`, `rclone_*`).
- **Not shown anywhere:** backup settings. The writer still forces `backup_enabled`,
  `ssh_backup_enabled` and `rclone_backup_enabled` to `0`.
- **Apply flow** (Stage 07 rule): validate → stop crawler (long grace) → atomic write → start.
  If the crawler exits within N seconds of a restart, roll back to the last-known-good
  `config.ini` (reuse `RollbackPolicy`) and show the failure with the log tail.

### 2. Status & stats

- The crawler gets a dashboard card: state, PID, uptime, restart count, log tail (existing
  `ProcessStatus`), DB file size, and internal-storage free space (warn when low).
- `GET /api/crawler/stats`: the control server fetches `http://127.0.0.1:<http_port>/stats`
  with a short timeout and passes the JSON through (torrent count, metadata rate, active trees).
  The card polls it. If the crawler isn't running, return a clean "not running" response
  instead of an error.

### 3. Authenticated access to the search UI

The crawler UI has no auth and uses absolute paths (`/search`, `/torrent`, `/refresh`,
`/random-*`), so it can't be mounted under a sub-path of `:8090` without rewriting HTML.

- Switch the seeded/managed config to **`http_bind = 127.0.0.1`** so the crawler is no longer
  reachable on the LAN without auth.
- Add a small **Ktor reverse proxy on its own LAN port** (default `8080`, so existing bookmarks
  keep working; configurable). It forwards everything to `127.0.0.1:<crawler http_port>` and
  needs a valid control token. Browsers send cookies regardless of port, so the existing
  `control_token` cookie from `:8090` authenticates here too. Support `?token=` and Basic auth
  as fallbacks, as `ControlServer` already does.
- The internal crawler port must differ from the proxy port when both are on 8080 (e.g.
  crawler `http_port = 8081` on loopback, proxy listens on `0.0.0.0:8080`). Write this into
  the managed config.
- The proxy must stream responses (search pages can be large) and use a timeout of at least
  the crawler's `/refresh` duration.
- The dashboard "Crawler search" link (stubbed in Stage 07) points at the proxy.
- *(Optional)* "LAN direct (no auth)" toggle for Termux parity. It sets `http_bind=0.0.0.0` and
  disables the proxy. Off by default.

### 4. Maintenance actions

Buttons for `--check-database`, `--compact`, `--recover-database`, `--rebuild-bloom-filter`,
`--porn-filter-update`.

- Each one runs `libdhtcrawler.so <flag>` as a **one-shot** process in `filesDir/crawler/`.
  The supervised crawler is stopped first (UI confirms) and restarted afterwards if it was
  enabled.
- Only one action at a time (mutex). Output streams into a log view on the panel. Exit code
  shows success or failure.
- Pre-checks: `--compact`/`--recover-database` need roughly the DB size in free internal
  space. Refuse with a clear message if there isn't enough.
- Reuse `ProcessSupervisor` launch/drain code (factor out a one-shot runner) rather than a
  second exec path.

### 5. Wifi-only gating

When `wifiOnly` is set (default **on**: the crawler is bandwidth heavy), drive crawler
start/stop from the existing `WifiGateMonitor`/`WifiGateReducer` state, the same way FTP is
gated. The state shows on the card ("paused: no wifi"). Start/stop calls run off the main
thread (see the FTP restore plan §2.6).

### 6. Migrate the existing data from Termux (phone storage → phone storage, no USB)

Copies the running Termux crawler's database
`/data/data/com.termux/files/home/dht_crawler/data/` (and its `config.ini`) into the app's
internal storage at `filesDir/crawler/`. The DB stays on internal storage. **USB is not used at
any point**, and the USB `SafStorageGateway` is not involved.

**How we reach Termux's home without root.** Termux ships a SAF `DocumentsProvider`
(`com.termux.filepicker.TermuxDocumentsProvider`, authority `com.termux.documents`). It
exposes `$HOME` with `FLAG_SUPPORTS_IS_CHILD`. Material Files uses the same provider to
browse Termux's home ("Add storage → External storage" → Termux). Facts from its source:
- Document IDs are **absolute paths**, e.g. `/data/data/com.termux/files/home/dht_crawler`.
- `openDocument()` returns `ParcelFileDescriptor.open(file, mode)`: a **real, seekable file
  fd** passed over binder. Reads are straight disk reads in our process, with no per-chunk IPC,
  so multi-GB copies run at internal-flash speed.
- `queryChildDocuments` lists via `File.listFiles()` and reports `COLUMN_SIZE` and
  `COLUMN_LAST_MODIFIED`.
- Android starts the provider on demand. Termux doesn't need to be open, only installed.

**Flow**

1. **Prepare Termux (user, shown as instructions in the panel):** stop the Termux crawler
   cleanly (Ctrl-C, then wait for the final flush). Optionally run
   `sqlite3 data/torrents.db 'PRAGMA wal_checkpoint(TRUNCATE);'` to fold the WAL into the DB.
   Leave Termux's copy in place.
2. **Grant access (on the phone; the web page can't open a picker):** a
   "Choose Termux dht_crawler folder" button in `MainActivity` launches
   `ActivityResultContracts.OpenDocumentTree` with `EXTRA_INITIAL_URI` =
   `DocumentsContract.buildDocumentUri("com.termux.documents",
   "/data/data/com.termux/files/home/dht_crawler")`, so the picker opens in the right place.
   The user selects the **`dht_crawler`** folder (the parent of `data/`, so `config.ini` comes
   along). Take the persistable read grant so a long copy survives an activity restart. Release
   it when the migration finishes.
   - Check the authority is `com.termux.documents` and show it in the panel. Any other SAF tree
     is accepted with a warning, as a generic fallback; the USB tree is refused.
   - If Termux isn't in the picker, the panel says so (Termux not installed or too old for the
     provider).
3. **Scan (web panel shows the source):** list `data/` through `DocumentsContract` child
   queries. Show each file's size and mtime, the total, and internal free space
   (`StatFs(filesDir)`). Require total + 10% headroom free. Copying also needs the target slot
   free: if `filesDir/crawler/data/` already exists, the old data is kept until the swap.
4. **Copy (background job, crawler stopped and enforced server-side):**
   - Allow-list from `data/`: `torrents.db` (required), `torrents.db-wal` (if present),
     `bloom.dat`, `failure_bloom.dat`, `bep51_cache.dat`. **Skip** `torrents.db-shm` (SQLite
     rebuilds it) and leftover backup bookmarks (`last_backup_date.txt`,
     `*_backup_ids.txt`). List any other unknown files as skipped. Reject symlinks,
     directories and anything outside the picked tree.
   - Copy into `filesDir/crawler/data.import/` with
     `FileInputStream(pfd.fileDescriptor).channel.transferTo(...)` → `FileChannel`, then `fsync`.
     Show per-file and total progress plus MB/s on the panel (polled status endpoint). The job
     can be cancelled; cancelling deletes `data.import/`.
   - **Detect a live source:** re-query each file's size and mtime after copying it. If either
     changed, abort with "the Termux crawler appears to be running — stop it and retry". The
     copied size must equal the source size.
5. **Verify:** the crawler reads `config.ini` from its cwd and takes `db_path` from it. Run
   `libdhtcrawler.so --check-database` as a one-shot (§4 runner) with cwd
   `filesDir/crawler/verify/`, containing a generated `config.ini` with
   `db_path=../data.import/torrents.db` (backups 0). Then show the torrent count
   (`SELECT COUNT(*) FROM torrents` via Android SQLite, read-only) so the user can compare it
   with Termux (`sqlite3 data/torrents.db 'SELECT COUNT(*) FROM torrents'`).
6. **Swap:** `data/` → `data.old/`, `data.import/` → `data/` (same filesystem, so these are
   atomic renames). If bloom files were missing, run `--rebuild-bloom-filter`.
7. **Config:** parse Termux's `config.ini` and offer to adopt its **tuning keys only**
   (`num_trees`, `tree_*`, bloom sizes, filters, rate and respawn settings), showing a
   diff. Paths, `http_*`, `nice_level` and **all backup keys are never imported**: backups stay
   `0`. The result goes through the normal apply/rollback flow (§1).
8. **Start & confirm:** start the crawler. The panel shows a "Migration" status box (torrent
   count, crawler running, `/stats` reachable) until the user presses **"Confirm migration"**.
9. **Cleanup after confirmation:**
   - App side: delete `data.old/` and release the SAF grant.
   - Termux side: show the exact command to run in Termux,
     `rm -rf ~/dht_crawler/data` (or the whole `~/dht_crawler`). *(Optional button)* "Delete
     source files via SAF": calls `DocumentsContract.deleteDocument` per allow-listed file
     (the Termux provider deletes files and empty dirs only). It needs a separate explicit
     confirmation and is never automatic.
   - Remind the user not to start the Termux crawler again (port 8080/6881 clash, and two
     diverging DBs).

**Rollback** at any point before confirmation: "Revert migration" stops the crawler, moves
`data/` → `data.failed/` and `data.old/` → `data/` (or deletes it if there was no previous
data), and restores the previous `config.ini`. The Termux copy is untouched until step 9, so
the user can always go back to running it in Termux.

### 7. Cleanup

- Remove the `settings/rclone` route, the "Backups (soon)" dashboard link, and the rclone
  mentions in the status JSON/dashboard.
- Optional (not required): add an "Enable DHT crawler" checkbox to the first-run wizard.
  Default off; enabling it just sets `crawler.enabled`.

## Key decisions & gotchas

- **No backups, anywhere in the UI.** Backup keys are neither rendered nor accepted from the
  advanced textarea, and they are pinned to `0` on write.
- **Auth on every LAN port** (Stage 00 rule): the search UI moves to loopback behind the proxy.
- **Never act on a live DB:** migration and maintenance actions require the crawler to be
  stopped. Enforce this server-side, not only in the page. The Termux side can't be enforced,
  so use the size/mtime re-check plus `--check-database`.
- **Crawler data never touches USB.** Migration reads Termux's home through the Termux
  DocumentsProvider and writes to `filesDir`; the USB gateway is not used.
- **Termux source is read-only until the user confirms.** Nothing is deleted in Termux
  automatically.
- **Long stop times:** apply, maintenance and import all go through the crawler's longer
  SIGTERM grace. The UI shows "stopping…" rather than blocking the request thread. Run these
  actions as background jobs with a status endpoint that the page polls.
- Keep the panel dependency-light (server-rendered HTML + a few lines of JS), consistent with
  existing panels.

## Out of scope

- Backups, restore, rclone, SSH (dropped).
- Patching the crawler's own HTML/search UI.
- Any import from or export to USB.

## Acceptance test

- `/settings/crawler` shows live status. Changing `num_trees` applies, survives a service
  restart, and shows in the crawler log. A deliberately bad value is rejected or rolled back
  without a crash loop.
- Backup keys don't appear in the panel. Submitting them in the advanced textarea is rejected,
  and `config.ini` still has all three at `0`.
- From another LAN device: `:8080` without a token → 401; after logging in on `:8090`,
  `:8080` serves the search UI, and search, torrent pages, `/refresh` and random pages all
  work. Port 8081 is not reachable from the LAN.
- The dashboard card shows the torrent count and metadata rate from `/api/crawler/stats`.
- Each maintenance action runs against a stopped crawler, streams output, reports the exit
  code, and the crawler comes back afterwards.
- With wifi-only on: disabling wifi stops the crawler (card shows paused); re-enabling restarts it.
- **Termux migration:** with the Termux crawler stopped, picking `~/dht_crawler` in the picker
  (opens at that folder) copies `data/` into `filesDir/crawler/data/`. Progress is visible,
  `--check-database` passes, the torrent count matches Termux, and the crawler starts on it.
  Nothing is written to USB.
- Starting the copy while the Termux crawler is still running is detected (size/mtime change)
  and aborted, leaving `data/` untouched. A source without `torrents.db` is rejected.
- Termux tuning keys are offered as a diff; backup keys stay `0` after adopting them.
- "Revert migration" before confirming restores the previous state. "Confirm migration"
  deletes `data.old/`. The Termux files are only removed by the user (or the separately
  confirmed SAF delete button).
