# Stage 09 — dht_crawler & rclone cross-compile, supervision & data import

> **DEFERRED — post-PoC.** Do not start until the transmission-to-USB PoC (Stages 01–05) is
> complete. dht_crawler and rclone are not needed to prove transmission writes to USB.

**Builds on:** Stage 02 (native binary packaging & supervision), Stage 03 (USB SAF gateway),
Stage 06 (FTP server).
**De-risks:** cross-compiling dht_crawler (C) and rclone (Go) for arm64 Android, integrating
dht_crawler into the Kotlin process supervisor, and bootstrapping the DB from an existing
`data/` import to avoid cold-start failures.

## Goal

Cross-compile `dht_crawler` and `rclone` for arm64 Android, integrate dht_crawler into the
Kotlin supervisor (reusing the Stage 02 machinery), optionally seed its SQLite DB from a USB
`data/` import, and confirm the crawler's HTTP search UI is reachable at `:8080`.

## What gets built

### 1. dht_crawler cross-compile

- **Native deps:** `libuv`, `libsqlite3`, `libssl`/`libcrypto` (OpenSSL), `libpthread`,
  `libm`, `libdl`. Bundled submodules in `lib/`: `bencode-c`, `cJSON`, `civetweb`,
  `libbloom`, `uthash`. Replicate the upstream `Makefile` with an NDK toolchain + cross-built
  deps (crib flags from `termux-packages`).
- **Apply the PT_TLS p_align ELF patch** in the Gradle task's `doLast` block after
  `libdhtcrawler.so` is produced (copy the `buildProbe` `doLast` from
  `app/build.gradle.kts`). ARM64 Bionic rejects static NDK binaries with `PT_TLS p_align
  < 64`; the crawler will not start without this patch. Apply the same patch to any
  separately-shipped static C helpers (`libsqlite3.so`, `libzstd.so`).
- Ship as `libdhtcrawler.so` in `jniLibs/arm64-v8a/` (Stage 02 pattern).
- **HTTP port 8080 is effectively hardcoded** in `http_api.c`/`http_api.h`; present it
  read-only in the control UI unless the crawler is patched to read it from config.
- **Working directory matters:** `config.ini` paths are relative; set `directory()` to the
  dir holding `data/`, `config.ini`, and keyword files (`filesDir/crawler/`).
- The crawler has its own **internal supervisor** (`supervisor.c`); the Kotlin supervisor only
  needs to keep the outer process alive and restart on full exit.
- **Resource tuning for mobile:** the defaults (~32 trees, ~4,500 threads, ~500 MB RAM) are
  too heavy. Seed a `config.ini` with reduced values: `num_trees=16`, lower worker counts.
  Surface these keys in the Stage 09 settings UI.
- **Cold-start bootstrap abort:** if the BEP51 cache is empty and bootstrap fails, the
  process exits. The Kotlin supervisor should retry with backoff; importing an existing
  `data/` (see §4 below) avoids this.

### 2. rclone cross-compile

```sh
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -o librclone.so ./...
```

Ship as `librclone.so` in `jniLibs/arm64-v8a/`. rclone is **not exec'd directly here** — it
is built and packaging-proven in this stage and used by Stage 08 (backup scheduling).

### 3. Supervisor integration

Register `libdhtcrawler.so` with the Kotlin supervisor (Stage 02 machinery):

- Working dir = `filesDir/crawler/` (holds `data/`, `config.ini`, keyword files).
- Env: `LD_LIBRARY_PATH=nativeLibraryDir`.
- Exponential backoff restart on exit; surface state and last 200 log lines in the control
  UI (extends the Stage 02 control page pattern).
- The control page gains a **dht_crawler panel**: state, PID, uptime, log tail,
  start/stop/restart buttons.

### 4. Data directory import (optional bootstrap)

UI action in the control server: copy an existing `data/` directory (containing `torrents.db`
plus bloom/auxiliary files) into the crawler's internal working dir, with progress reporting
and integrity validation. Two sources are supported:

**4a. USB import (via SAF gateway)**
- User selects the `data/` directory on their USB device via the SAF directory picker.
- Kotlin reads each file through the SAF gateway (Stage 03) and streams it to a staging
  area at `filesDir/crawler/data.import/`.
- Expected files: `torrents.db`, `torrents.db-wal` (optional), `bloom_filter.bin`,
  `failure_bloom.bin`, `rclone_backup_ids.txt` (optional), `ssh_backup_ids.txt` (optional).
  Reject the import if `torrents.db` is missing or if unexpected top-level entries are found.
- Validate: open `torrents.db` with the Android SQLite API (read-only) and check that the
  `torrents`, `torrent_files`, and `path_prefixes` tables exist and are non-empty. Reject
  blobs, executables, or files > 20 GB.
- On validation pass, atomically rename `data.import/` → `data/`.

**4b. SSH import**
- User provides SSH host, user, port, key path (or password), and remote `data/` path via the
  setup/import UI.
- Kotlin exec's `librclone.so` (already available after Stage 09 §2) with the `sftp` backend
  to list the remote directory and copy files into `filesDir/crawler/data.import/`.
  Alternatively, exec `libssh.so` / use JSch if rclone sftp is sufficient — prefer rclone to
  avoid a second SSH binary.
- Apply the same validation (table existence, file allow-list, size cap) before atomic rename.
- SSH import is **Android-only disabled by default** and enabled via the import UI explicitly;
  it does not require SSH backup to be configured.

**Common rules for both sources:**
- Block import if the crawler is currently running (never copy into a live WAL DB).
- Run `dht_crawler --check-database` after the atomic rename to confirm integrity; roll back
  (delete `data/`) and surface the error if it fails.
- Show per-file progress (file name, size, bytes copied) during transfer.
- The import UI lives in the crawler control panel (Stage 10), reachable even before first
  crawler start, so a cold-start user can seed the DB immediately.

## Key decisions & gotchas

- **Static linking preferred.** dht_crawler's deps should be statically linked into
  `libdhtcrawler.so` or co-shipped as `lib*.so` siblings with `LD_LIBRARY_PATH`. Dynamic deps
  against the wrong libc fail at exec.
- **PT_TLS p_align ELF patch required** for every static NDK C binary produced in this
  stage (`libdhtcrawler.so`, `libsqlite3.so`, `libzstd.so`). See Stage 02 gotchas and the
  `buildProbe` `doLast` reference implementation. rclone (`CGO_ENABLED=0`, pure Go) does
  not go through the NDK linker and does not need this patch.
- **HTTP port 8080 is compile-time.** Unless the crawler is patched, 8080 cannot be changed
  via config — document this in the settings UI (Stage 09) as a read-only field.
- **Resource tuning is mandatory on mobile.** A default-config dht_crawler will OOM the
  device. The seeded `config.ini` with reduced workers/trees must be in place before first
  launch.
- **Never copy a live WAL DB.** The import action must enforce that the crawler is stopped
  before copying `data/torrents.db`, or use SQLite online backup.
- **dht_crawler has no USB access** (it runs on internal storage only; see Stage 00). The
  `backup_path` daily-copy option cannot target USB — point it at internal storage.
- **SSH import uses rclone sftp**, not a raw `ssh` binary, to avoid an extra dependency and to
  reuse the `librclone.so` already shipped. Configure sftp connection from the import UI; no
  persistent rclone remote is needed for a one-off import.
- **Import validation is mandatory.** Never blindly copy whatever directory the user provides.
  Reject imports that fail the table-existence check or contain unexpected files.

## Out of scope (later stages)

- rclone configuration, backup scheduling, and the binary-resolution patch for the crawler's
  own `system()` backup calls (Stage 11).
- USB SAF external backup backend (Stage 12 — build-flag gated, applied as a patch).
- SSH backup download and restore (Stage 12).
- Backup and crawler settings in the full web UI (Stage 10).

## Acceptance test

- `libdhtcrawler.so` and `librclone.so` build cleanly for `arm64-v8a` under the NDK.
- The supervisor starts `libdhtcrawler.so`; the control page shows it running with PID,
  uptime, and log output.
- The crawler's HTTP search UI is reachable at `http://<device-ip>:8080` on the LAN.
- `kill` the crawler process → the supervisor restarts it within the backoff window; the
  restart event is visible in the control page log.
- *(If USB data import is exercised)* Importing a real `data/torrents.db` from USB populates
  the crawler's internal DB and `--check-database` passes.
- *(If SSH data import is exercised)* Importing via rclone sftp completes, validates, and the
  crawler starts on the imported DB with `--check-database` passing.
