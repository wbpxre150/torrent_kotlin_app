# Stage 11 — Backups (crawler DB → network remote)

> **DEFERRED — post-PoC.** This stage depends on dht_crawler being cross-compiled and
> supervised (Stage 09). Do not start until the transmission-to-USB PoC (Stages 01–05) is
> complete and dht_crawler has been integrated.

**Builds on:** Stage 09 (dht_crawler cross-compile; rclone built & exec-proven; crawler
running) and Stage 10 (crawler UI panel wired up).
**De-risks:** off-device durability of the expensive-to-rebuild crawler SQLite database using
the crawler's existing backup machinery, plus a UI-driven SQL restore path for both fresh
installs and corrupted databases.

## What the crawler already does (verified from source)

dht_crawler has **built-in backups** (`batch_writer.c`, `config.c`):

1. **Daily file copy** — `backup_enabled` + `backup_path` → copies `torrents.db` to a local
   path.  **Disabled on Android** — the crawler cannot write to USB (Stage 00), and a second
   local copy on internal storage wastes space without adding durability. Force
   `backup_enabled=0` in the seeded `config.ini` and hide it from the Android UI.
2. **SSH incremental** — `ssh_backup_enabled` → daily new rows exported as
   `incremental_*.sql.zst` and streamed to a remote over `ssh`.  **Allowed on Android, but
   disabled by default.** Enable explicitly from the settings UI; see binary-resolution note
   below.
3. **rclone incremental** — `rclone_backup_enabled` → same SQL export streamed via
   `rclone rcat` to `rclone_remote:rclone_dest_path`.  **Primary backup path on Android.**

**Binary-resolution problem:** both the SSH and rclone incremental paths run via `system()`
with bare command names (`sqlite3`, `zstd`, `rclone`, `ssh`). On Bionic, `/system/bin/sh`
exists but these commands are not on `PATH`. They are shipped as `lib*.so` in
`nativeLibraryDir` — bare names won't resolve. **This must be solved before either backup
path works.**

## Goal

Get reliable, incremental crawler-DB backups to the user's S3/rclone remote, enabled
automatically after the setup wizard validates write access, with a UI-driven SQL restore
path so corrupted or fresh databases can be rebuilt from the incremental archive.

## Approach

### Option A (recommended): patch the crawler to use absolute, configurable binary paths

Add config keys `sqlite3_bin`, `zstd_bin`, `rclone_bin`, `ssh_bin` to `config.h` / `config.c`
(parsed from `config.ini`; default to bare names so desktop builds are unchanged).
Replace the bare command tokens in the `batch_writer.c` `system()` format strings with these
config keys. Ship `libsqlite3.so`, `libzstd.so`, `librclone.so` (Stage 09) in `jniLibs`;
set the four config keys to their `nativeLibraryDir` absolute paths in the seeded
`config.ini`.

- `libsqlite3.so` and `libzstd.so` are static NDK C binaries — apply the **PT_TLS p_align
  ELF patch** in their Gradle `doLast` blocks (copy pattern from `buildProbe`).
- `librclone.so` is `CGO_ENABLED=0` pure Go — no ELF patch needed.
- SSH variant: set `ssh_bin` to the absolute path of `libssh.so` if a standalone SSH binary
  is shipped; otherwise disable the SSH `system()` path and drive SSH via rclone's `sftp`
  backend only (see §SSH below).

**Changes required to the dht_crawler source** (tracked separately — see §Crawler changes):
- Add the four bin-path config keys.
- Substitute them in the two `system()` command format strings in `batch_writer.c`.
- Add `rclone_backup_enabled` default-on toggle that the Kotlin setup wizard writes into
  `config.ini` after the S3 write test passes.

### Option B (fallback): drive backup from Kotlin

Disable the crawler's `system()` backups; a Kotlin scheduled job invokes `librclone.so`
(absolute path) to upload a consistent full-DB snapshot. Snapshot via SQLite online backup /
`VACUUM INTO` (never copy the live WAL file). Simpler binary story but loses incremental
efficiency (full DB each time, grows to tens of GB).

Use Option B only if the Option A patch is blocked.

## Setup wizard: automatic rclone enable

During the app setup wizard (before the crawler's first start):

1. User enters S3 (or compatible) credentials: endpoint, bucket, access key, secret key,
   optional path prefix. These are written to `filesDir/crawler/rclone.conf`.
2. Wizard runs a write probe: `librclone.so rcat <remote>:<bucket>/<prefix>/.write_test`
   with a 1-byte payload, then deletes it. Probe timeout: 15 s.
3. **On probe success:** set `rclone_backup_enabled=1` in `config.ini` automatically. Show a
   green "Backup enabled" banner. The user may proceed.
4. **On probe failure:** show an error with the rclone output. The user may fix credentials
   and retry, or explicitly tap "Skip backup (not recommended)" with a warning:
   *"Without backups, crawler data cannot be recovered if the device is lost or the database
   is corrupted."* Skipping is allowed but requires an explicit tap.
5. The wizard **does not proceed past the backup step** until either the probe succeeds or the
   user explicitly skips.

## SSH incremental backup

- Allowed on Android; **disabled by default** (`ssh_backup_enabled=0` in seeded `config.ini`).
- The settings UI exposes host, user, port, key path (or password-over-sftp via rclone),
  and remote destination path.
- Binary resolution: if a standalone `libssh.so` is shipped, set `ssh_bin` in `config.ini`.
  Preferred alternative: do **not** ship a raw `ssh` binary; instead disable the crawler's
  own SSH `system()` path and implement SSH incremental upload in Kotlin using rclone's `sftp`
  backend (same `librclone.so`, no extra binary). Decide at implementation time based on
  rclone sftp's ability to replicate the incremental `.sql.zst` upload pattern.
- SSH backup is a later-stage concern (Stage 12 covers the download/restore side).

## SQL restore UI

Users need a way to rebuild a database from the incremental `.sql.zst` archive — both for
fresh installs and corrupted databases.

### Restore flows

**Flow A — Fresh install (no local DB):**
1. User has never run the crawler, or the local DB is deleted/corrupted.
2. In the crawler control panel, "Restore from backup" action connects to the configured
   rclone remote, lists `incremental_*.sql.zst` files, and downloads them all (or a
   user-specified date range) to `filesDir/crawler/restore/`.
3. Kotlin creates a blank SQLite DB (`filesDir/crawler/data/torrents.db.restore`) with the
   correct schema (run `dht_crawler --init-db` or apply the DDL directly).
4. Kotlin decompresses each `.sql.zst` file in chronological order (by filename date) and
   feeds the SQL to the blank DB via the SQLite API (`sqlite3_exec`). Use batched
   transactions for performance.
5. On completion, atomically rename `torrents.db.restore` → `torrents.db`. Run
   `dht_crawler --check-database`. If it fails, roll back and surface the error.

**Flow B — Existing external DB backup + incremental topping-up:**
1. User has a full `torrents.db` from a previous device or external backup (imported via
   Stage 09 §4 USB/SSH import).
2. In the restore UI, user selects "Apply incremental SQL files to imported DB".
3. Kotlin lists files on the remote newer than the DB's `MAX(id)` in each table, downloads
   them, and feeds them in order (same pipeline as Flow A step 4).
4. SQL files use `INSERT OR IGNORE` (already the case from the crawler's dump format), so
   re-applying overlapping files is safe.

### UI details

- The restore panel shows: remote file list with dates and sizes, estimated total download
  size, progress bar (files remaining, rows inserted), estimated time.
- The crawler must be stopped before restore starts; the UI enforces this.
- After restore, the user is prompted to start the crawler.
- Accessing the SQL files directly (for manual inspection or export) is also exposed: a
  "Browse backup archive" list view in the UI with per-file download links.

## Storage notes

- `backup_enabled` / `backup_path` (daily local file copy): **disabled on Android.** Hidden
  in the Android settings UI. Force `backup_enabled=0` in the seeded `config.ini`.
- rclone remote config (`rclone.conf`) lives in `filesDir/crawler/`; set `RCLONE_CONFIG` in
  the crawler child's environment. Manage via the control UI (Stage 10).
- USB-content backup (torrent files) is out of scope; content is re-downloadable.

## Key decisions & gotchas

- **Option A patch is required** — the `system()` bare-name problem must be solved. The
  symlink-named-`rclone`-in-a-PATH-dir trick is fragile under SELinux `execute_no_trans`.
- **Consistent snapshot, not a live WAL file.** Any full-DB copy must use SQLite online
  backup or `VACUUM INTO`, never `cp torrents.db`.
- **Wizard probe is blocking.** The user cannot skip without an explicit acknowledgement.
  This prevents silent data loss when the user assumes backups are running.
- **`INSERT OR IGNORE` in SQL dumps.** The crawler already emits this form; re-applying
  overlapping files during restore is safe.
- **Schedule from within the foreground service** (coroutine timer), not WorkManager.

## Crawler changes (separate plan documents)

The following modifications to the dht_crawler source are needed and must be planned and
implemented in separate documents:

1. Add `sqlite3_bin`, `zstd_bin`, `rclone_bin`, `ssh_bin` config keys to `config.h`/
   `config.c`; substitute in `batch_writer.c` `system()` strings.
2. Add `rclone_backup_enabled` as a runtime-writable toggle (so the Kotlin wizard can flip it
   after the write probe without manually editing `config.ini`). Alternatively, Kotlin writes
   `config.ini` directly — simpler, acceptable if the crawler re-reads on SIGHUP or restart.
3. Patch the rclone incremental path to skip backup gracefully when `rclone_bin` is not
   configured, rather than silently failing the `system()` call.

These patches will be applied as patch files (same pattern as `patches/` in the dht_crawler
repo) and compiled in via the Android Makefile integration, keeping the upstream dht_crawler
source unmodified.

## Out of scope

- USB SAF external backup backend (Stage 12).
- SSH data-directory download (Stage 12).
- Remote-setup and schedule UI details beyond what's described here (Stage 10).

## Acceptance test

- Setup wizard: entering valid S3 credentials and passing the write probe results in
  `rclone_backup_enabled=1` in `config.ini`; skipping shows the warning banner.
- With a remote configured and Option A patch applied, the crawler's incremental rclone backup
  produces `.sql.zst` files on the remote each day, verified by listing via `librclone.so`.
- **Flow A restore:** starting from no local DB, the restore UI downloads all incremental
  files, creates a blank DB, applies the SQL, and `--check-database` passes.
- **Flow B restore:** starting from an imported DB, the restore UI applies only newer
  incremental files; the combined DB has the correct row counts and `--check-database` passes.
- `backup_enabled` is absent / `0` in the Android seeded `config.ini`; the setting is hidden
  in the Android UI.
- SSH backup settings are present in the UI but default to disabled.
