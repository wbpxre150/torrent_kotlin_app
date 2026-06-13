# Stage 07 — Backups (crawler DB → network remote)

> **DEFERRED — post-PoC.** This stage depends on dht_crawler being cross-compiled and
> supervised (Stage 02b). Do not start until the transmission-to-USB PoC (Stages 01–05) is
> complete and dht_crawler has been integrated.

**Builds on:** Stage 02b (dht_crawler cross-compile; rclone built & exec-proven; crawler
running).
**De-risks:** off-device durability of the expensive-to-rebuild asset — the crawler's
SQLite database — using the crawler's **existing** backup machinery where possible.

## What the crawler already does (verified from source)

dht_crawler has **built-in backups** (`batch_writer.c`, `config.c`), already configured in
the user's `config.ini` (`rclone_backup_enabled=1`, remote `r2`, i.e. Cloudflare R2):

1. **Daily file copy** — `backup_enabled` + `backup_path` → copies `torrents.db` to a path.
2. **SSH incremental** — `ssh_backup_enabled` → daily new rows exported as
   `incremental_*.sql.zst` and streamed to a remote over `ssh`.
3. **rclone incremental** — `rclone_backup_enabled` → same SQL export streamed via
   `rclone rcat` to `rclone_remote:rclone_dest_path`.

**Crucial implementation detail:** the incremental backups run via **`system()`** with a
shell pipeline of **bare command names** — effectively
`sqlite3 'db' .dump… | zstd | rclone rcat r2:…` (and the `ssh` variant). On bionic,
`system()` uses `/system/bin/sh`, which exists — but `sqlite3`, `zstd`, `rclone`, `ssh`
must be **found on `PATH` under those exact names**. In a standalone APK the only
executable location is `nativeLibraryDir`, where files are named `lib*.so`. So the bare
names won't resolve. **This is the one thing that must be solved for the crawler's own
backup to work.**

## Goal

Get a consistent crawler-DB backup to the user's network remote, reusing the crawler's
backup feature, with the binary-resolution problem solved.

## Approach — pick one (recommended: A)

### Option A (recommended): patch the crawler to use absolute, configurable binary paths
- Add config keys (`sqlite3_bin`, `zstd_bin`, `rclone_bin`, `ssh_bin`) parsed in `config.c`;
  use them in the `batch_writer.c` command strings instead of bare names (default to bare
  names so desktop builds are unchanged).
- Ship `sqlite3`, `zstd`, `rclone` (and optionally `ssh`) as `lib*.so` in `jniLibs`
  (Stage 02 pattern); set the config keys to their `nativeLibraryDir` absolute paths.
- Provide rclone its config: set `RCLONE_CONFIG`/`HOME` in the crawler child's environment
  (or pass `--config`) so the `r2` remote resolves. Manage `rclone.conf` from the control
  UI (Stage 08).
- Keeps the crawler's efficient **incremental** export; smallest behavioural change.

### Option B (fallback): drive backup from Kotlin
- Disable the crawler's `system()` backups; instead a Kotlin scheduled job invokes
  `librclone.so` (absolute path — no PATH problem) to upload a **consistent full-DB
  snapshot**.
- Snapshot safely: stop the crawler briefly, or use SQLite online backup / `VACUUM INTO`
  (the crawler runs **WAL** mode — never copy the live file naively). The crawler's
  `--compact` / maintenance flags can help, but a read-only snapshot copy is cleanest.
- Simpler binary story, loses incremental efficiency (full DB each time).

## Storage notes

- **`backup_path` (daily file copy) cannot target USB** — the crawler is native and can't
  write USB (Stage 00). Point it at **internal** storage as a local safety copy, or disable
  it in favour of the rclone/ssh remote backup.
- rclone's **sftp** backend covers the user's OpenSSH/SSH requirement without bundling a
  separate `openssh` — only revisit a standalone `ssh` binary if the crawler's *ssh
  incremental* path (not rclone) is specifically wanted.
- Backing up **torrent content on USB** is out of scope (native rclone can't read USB; and
  content is re-downloadable — the DB is the irreplaceable asset).

## Key decisions & gotchas

- **Solve binary resolution (Option A patch or Option B Kotlin) — this is the crux.** The
  symlink-named-`rclone`-in-a-PATH-dir trick is fragile under SELinux `execute_no_trans`;
  prefer absolute paths via patch.
- **Consistent snapshot, not a live WAL file.**
- rclone config + secrets live in internal storage; obscure with rclone's own mechanism;
  manage via the control UI.
- Schedule from within the foreground service (coroutine timer), not WorkManager (which
  fights the always-on model); avoid colliding with heavy download I/O.

## Out of scope (later stages)

- Remote-setup / schedule UI (Stage 08).
- USB-content backup (deliberately excluded).

## Acceptance test

- With a remote configured, the crawler's incremental rclone backup (Option A) or the
  Kotlin job (Option B) produces a restorable backup on the remote, verified by listing it.
- A restore pulls the DB back into internal storage; the crawler starts on it and
  `--check-database` passes.
- The scheduled job runs on its interval and reports success/size/time in the control UI.
