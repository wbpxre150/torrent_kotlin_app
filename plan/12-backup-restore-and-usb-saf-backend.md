# Stage 12 — Backup restore, SSH data import, and USB SAF external backup backend

> **DEFERRED — post-PoC.** Depends on Stage 11 (rclone backups running, incremental SQL
> archive in place) and Stage 09 (data import UI scaffolding). Do not start until both are
> complete.

**Builds on:** Stage 09 (USB/SSH import UI, rclone sftp), Stage 10 (crawler UI panel),
Stage 11 (rclone incremental backups, SQL restore flows).
**Adds:** SSH data-directory download flow, USB SAF external backup backend (Android-only
build-flag, applied as a patch), and any remaining restore-UI polish.

## Goals

1. **SSH data-directory download** — let a user pull their entire `data/` directory from a
   remote SSH server into the phone's internal storage as a one-shot bootstrap, using rclone
   sftp so no standalone SSH binary is needed.
2. **USB SAF external backup backend** — let the crawler write incremental `.sql.zst` backup
   files to a USB device via the Android SAF gateway. This backend is Android-only, compiled
   in only when the `ANDROID_SAF_BACKUP=1` Makefile flag is set, and applied as a patch file
   to the dht_crawler source (not upstream).

---

## Part 1 — SSH data-directory download

This extends the Stage 09 §4b SSH import with a full rclone sftp download flow.

### What it does

- User provides SSH connection details (host, port, user, key path or password, remote `data/`
  path) in the import/restore UI.
- Kotlin constructs a one-shot rclone sftp remote config (written to a temp file; not
  persisted as a named remote) and exec's `librclone.so copy sftp:<remote_path> <local_staging>`.
- Files are downloaded to `filesDir/crawler/data.import/`.
- The same validation pipeline from Stage 09 §4 applies: allow-list of expected filenames,
  table-existence check on `torrents.db`, size cap (20 GB), rejection of unexpected files or
  executables.
- On validation pass: stop the crawler (if running), atomically rename `data.import/` →
  `data/`, run `dht_crawler --check-database`, roll back on failure.
- Progress: rclone's `--progress` / stats JSON output is parsed and surfaced as a per-file
  progress bar in the UI.

### UI placement

- "Download data directory from SSH server" action in the crawler control panel, under the
  same "Import / Bootstrap" section as the USB import (Stage 09 §4a).
- Requires the same crawler-stopped guard as USB import.

### Notes

- Use rclone's sftp backend exclusively — no standalone `ssh` or `scp` binary needed.
- Connection details for this one-off import are **not** persisted as a backup target; they
  are separate from the SSH incremental backup settings (Stage 11).
- Key-based auth: write the key to a temp file in `filesDir`; delete after import. Password
  auth via rclone's `--sftp-pass` (obfuscated with `rclone obscure`).

---

## Part 2 — USB SAF external backup backend

The crawler's existing backup paths (`backup_path`, `ssh_backup_enabled`, `rclone_backup_enabled`)
all write to paths accessible to the native process. On Android, USB is only accessible via
the SAF gateway (Kotlin side). This part adds a new backup backend to dht_crawler that writes
to a Kotlin-provided file descriptor rather than a path, bridging the SAF boundary.

### Design constraints

- **Android-only.** Desktop builds must not be affected. Controlled by a compile-time flag
  `ANDROID_SAF_BACKUP` (`-DANDROID_SAF_BACKUP=1` in CFLAGS).
- **Applied as a patch file** to the dht_crawler source, not committed upstream. The patch
  lives in `app/patches/dht_crawler/` alongside the existing ELF and config patches.
  The Android Makefile integration applies it during the NDK build step.
- The patch must not break the existing `make` build on non-Android (no flag = no change).

### IPC: Kotlin ↔ native bridge for SAF writes

The crawler runs as a child process; it cannot call Android APIs directly. The bridge is a
**Unix domain socket** or **named pipe** in `filesDir`:

**Chosen mechanism: Unix domain socket (SOCK_STREAM)**

- Kotlin `DaemonService` opens a `ServerSocket` bound to `filesDir/crawler/saf_backup.sock`.
- When the crawler completes an incremental export and `ANDROID_SAF_BACKUP=1`, instead of
  writing to a local path or calling `rclone rcat`, it connects to `saf_backup.sock`, sends
  a header (filename, expected size), and streams the compressed `.sql.zst` bytes.
- Kotlin's socket handler receives the stream, opens an SAF `OutputStream` for the target
  file on the USB document tree, and copies bytes through. It sends an ACK/NACK after the
  write completes.
- The crawler checks the ACK; on NACK it logs the error and will retry on the next backup
  trigger.

**Protocol (simple, length-prefixed):**
```
Client → Server:
  [4 bytes: filename length] [filename bytes] [8 bytes: payload length] [payload bytes]

Server → Client:
  [1 byte: 0x00=OK, 0x01=ERROR] [4 bytes: error message length] [error bytes (0 if OK)]
```

All integers are little-endian. The connection is closed after each file transfer.

### dht_crawler patch: `saf_backup.c` / `saf_backup.h`

New files added by the patch (compiled only when `ANDROID_SAF_BACKUP=1`):

```c
// saf_backup.h
#ifdef ANDROID_SAF_BACKUP
int saf_backup_send(const char *sock_path, const char *filename,
                    const uint8_t *data, size_t len);
#endif
```

`batch_writer.c` changes (inside `#ifdef ANDROID_SAF_BACKUP` guards):
- In `do_rclone_backup()`: after building the compressed SQL blob (or instead of `system()`
  with `rclone rcat`), call `saf_backup_send(saf_sock_path, filename, buf, len)`.
- Add `saf_sock_path` to the `batch_writer` struct and `batch_writer_set_saf_backup()` setter.
- Add `saf_backup_enabled` and `saf_sock_path` config keys parsed in `config.c`.

`config.h` / `config.c` additions:
```c
#ifdef ANDROID_SAF_BACKUP
  int  saf_backup_enabled;
  char saf_sock_path[1024];   /* default: "data/saf_backup.sock" */
#endif
```

The seeded `config.ini` on Android sets:
```ini
saf_backup_enabled=0        ; enabled by wizard if USB backup is chosen
saf_sock_path=data/saf_backup.sock
```

### Kotlin side: `SafBackupSocketServer`

New class `SafBackupSocketServer` started by `DaemonService` when the crawler is running
with `saf_backup_enabled=1`:

- Listens on `filesDir/crawler/saf_backup.sock`.
- On connection: reads header, validates filename (must match `incremental_YYYYMMDD_*.sql.zst`
  pattern), calls `contentResolver.openOutputStream(safDocumentUri)` for the target document
  on the USB tree, streams bytes, sends ACK.
- USB tree URI is stored in `SharedPreferences` from the SAF directory grant (Stage 03).
- If the USB tree is unavailable (device unplugged), sends NACK; the crawler logs the error
  and retries next trigger.

### Makefile integration

In the Android NDK Makefile (or the Gradle `externalNativeBuild` CMakeLists.txt):

```makefile
ifdef ANDROID_SAF_BACKUP
  CFLAGS += -DANDROID_SAF_BACKUP=1
  SRCS   += src/saf_backup.c
endif
```

The patch is applied before the NDK build step in Gradle's `preBuild` task:

```kotlin
tasks.named("preBuild") {
    doFirst {
        exec {
            commandLine("patch", "-p1", "-d", dhtCrawlerSrcDir,
                        "--input", "app/patches/dht_crawler/saf-backup.patch",
                        "--forward", "--reject-file=-")
        }
    }
}
```

The patch file is generated once from the modified source with:
```sh
git diff HEAD > app/patches/dht_crawler/saf-backup.patch
```

### UI for USB SAF backup

- In the backup settings UI (Stage 10), a "USB backup" toggle is hidden on non-Android builds
  (build config check).
- When enabled: SAF directory picker shown; user grants persistent write access to the USB
  folder. The URI is stored in `SharedPreferences`.
- The same "Browse backup archive" list view (Stage 11) works for the USB target: Kotlin reads
  the SAF document tree and lists `.sql.zst` files with dates and sizes.
- The SQL restore flow (Stage 11 flows A and B) also supports the USB source: download files
  from the SAF document tree instead of the rclone remote.

---

## Key decisions & gotchas

- **Patch, not fork.** The USB SAF backend must not be compiled into the upstream dht_crawler.
  The patch approach mirrors the existing civetweb/bencode patches in the dht_crawler repo and
  the transmission SAF fork (Stage 05).
- **`ANDROID_SAF_BACKUP=1` build flag is the gate.** All new code is inside `#ifdef` guards;
  a plain `make` on Linux produces an identical binary to the unpatched source.
- **Socket in `filesDir`, not `/tmp`.** Android's SELinux policy does not allow Unix domain
  sockets in arbitrary locations; `filesDir` (app-private internal storage) is safe.
- **One file per connection.** Keep the protocol simple; do not multiplex multiple files over
  one socket connection. The crawler's backup frequency (daily) makes connection overhead
  irrelevant.
- **Crawler generates the SQL blob in memory before sending** (same as the current rclone path
  which pipes through `system()`). For very large incremental dumps this could be hundreds of
  MB; if memory pressure is a concern, switch to a streaming pipe instead of an in-memory
  buffer. Profile at implementation time.
- **SAF write speed to USB is slower than internal storage.** The backup socket write will
  block the crawler's backup thread until the SAF write completes. This is acceptable for a
  daily backup but must not block the main crawler loop. Ensure the socket send runs on the
  existing backup background thread (`ssh_backup_thread_func` pattern).
- **USB device unplugged mid-backup:** NACK is sent; the crawler retries next trigger. The
  partial file on USB is left and overwritten on retry (same filename pattern).

## Out of scope

- Backing up torrent content (`.torrent` files) on USB — content is re-downloadable.
- Windows/Linux SAF equivalent — this backend is Android-only by design.
- Real-time continuous backup — daily incremental is sufficient.

## Acceptance test

**SSH download:**
- User enters valid SSH details; "Download from SSH" copies `data/` to internal storage,
  validates the DB, and the crawler starts on the imported data with `--check-database`
  passing.
- Attempting to import a directory missing `torrents.db` shows a validation error and does
  not modify internal storage.

**USB SAF backup:**
- With `ANDROID_SAF_BACKUP=1` build flag, a USB device granted write access, and the crawler
  running for one backup trigger, a `.sql.zst` file appears in the USB directory.
- Unplugging the USB device before the backup completes logs a NACK in the crawler log; the
  next trigger retries successfully when the device is re-plugged.
- The "Browse backup archive" UI lists the `.sql.zst` files from the USB source.
- Restoring from USB backup (Flow A): applying all `.sql.zst` files from USB to a blank DB
  produces a database that passes `--check-database`.
- A non-Android build (`ANDROID_SAF_BACKUP` unset) compiles and runs identically to the
  unpatched dht_crawler source.
