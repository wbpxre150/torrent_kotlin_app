# Stage 00 — Overview & Architecture

This document is the map for the whole project. Read it before any individual stage.
It records *why* the design is shaped the way it is, so the build doesn't drift into a
dead end. The stages each build on the previous and are independently testable. Stages 01–05
are the PoC; the remainder are post-PoC extensions and deferred features.

---

## 1. Goal (restated)

A single Android (Kotlin) app that runs and supervises several daemons on an **arm64,
non-rooted** phone, replacing a Termux-based setup. It must:

- Download torrents (**forked transmission-daemon**, C) controlled by the user's existing
  Transmission Android remote, writing **directly to USB OTG storage** with no per-torrent
  size limit. **← PoC focus; everything else is deferred until this works.**
- Serve that storage over a **Kotlin FTP server** to the local **wifi** network (never
  mobile data) so **Kodi** can stream.
- Run 24/7 under a wakelock, restart crashed services, and be configured entirely from
  one built-in **web settings page**.

**Deferred to post-PoC:**
- Crawl the BitTorrent DHT for torrent metadata (**dht_crawler**, C) and expose its search
  web UI.
- Back up the crawler database off-device with **rclone** (Go).
- Import an existing dht_crawler `data/` directory from USB so the DB doesn't start empty.

---

## 2. The decisive constraint: USB storage on non-rooted Android

This single fact dictates the architecture. **It is the reason the app is structured the
way it is, and the reason Termux failed.**

> On non-rooted Android, a native process cannot **open a path** on a USB OTG drive. USB
> drives are not given a POSIX filesystem path to apps; they are reachable **only** through
> the **Storage Access Framework (SAF)** as `content://` document handles. No permission —
> including `MANAGE_EXTERNAL_STORAGE` — changes this. `mount()` needs `CAP_SYS_ADMIN`
> (root). libaums gives a JVM object API with no fd. `openProxyFileDescriptor` is one file.

**Why Termux can't see USB:** Termux runs stock native binaries that only know `open(path)`,
and there is no USB path. It never had a bridge.

### The bridge that makes this project possible: SAF file descriptors

There is exactly one mechanism by which **native code** can do real random-access I/O to a
USB drive without root:

> **`ContentResolver.openFileDescriptor(documentUri, "rw")`** (and the underlying
> `DocumentsContract`/`StorageManager` plumbing) returns a **`ParcelFileDescriptor` wrapping
> a real, seekable POSIX file descriptor**, backed by the platform's FUSE daemon for the
> external/USB volume. Pass that raw `int` fd down to native code via JNI and it supports
> `pread`/`pwrite` at arbitrary offsets, `lseek`, `ftruncate`/grow, `fsync` — everything a
> torrent client needs.

So the project's central technical act is: **fork transmission-daemon and replace its
file backend so that, for files under the USB download tree, it obtains fds from a Kotlin
SAF bridge instead of calling `open()` on a path.** transmission then downloads *directly*
to USB. This is the same class of technique that SAF-aware Android torrent apps
(LibreTorrent et al.) use with libtorrent.

### Component → USB access map

| Component                     | Lang   | USB I/O?            | How                                                                 | PoC? |
|-------------------------------|--------|---------------------|---------------------------------------------------------------------|------|
| **transmission-daemon (fork)**| C      | **Yes (read+write)**| File backend obtains **SAF fds via JNI**; downloads straight to USB. | **Yes** |
| Kotlin SAF gateway            | Kotlin | **Yes**             | Single choke point: opens fds, creates/renames/deletes docs, lists. | **Yes** |
| Kotlin FTP server             | Kotlin | **Yes (read)**      | Streams library from USB via SAF gateway to Kodi over wifi.          | Post-PoC |
| Kotlin control server         | Kotlin | Read (import)       | Settings UI; copies imported `data/` USB → internal via SAF.        | Post-PoC |
| dht_crawler                   | C      | No                  | SQLite DB on **internal** storage (wanted, for speed; ≤100 GB).      | **Deferred** |
| rclone / sqlite3 / zstd       | native | No                  | Backup helpers the **crawler shells out to** (`system()`); upload DB to a network remote. | **Deferred** |

### What the SAF-fd fork does and doesn't remove

- **Removed:** the internal "staging + copy to USB" mover. transmission writes to USB
  directly, so a single torrent can be as large as the drive allows. This is the whole
  reason for the fork (some torrents won't fit internal storage).
- **Still internal:** transmission's own small files — `settings.json`, `.resume` /
  resume state, `stats.json`, logs — stay on internal storage (simpler, faster, and they
  don't need to survive on the drive). Only the **torrent payload files** go through the
  SAF-fd backend to USB.

### Accepted trade-offs of the non-root SAF-fd model

- I/O to USB goes through the platform FUSE layer; expect lower-than-native throughput
  (tens of MB/s) and some syscall quirks (preallocation/`fallocate`, free-space queries —
  see Stage 05).
- Provider must return a **seekable** fd. The platform `ExternalStorageProvider` (what
  backs USB OTG) does; this must be validated early (Stage 03) with a >4 GB random-access
  test.
- The drive should be **exFAT** (no 4 GB file cap; Android 11+ has native exFAT support).
  FAT32's 4 GB limit is disqualifying for large media.

> If the device is ever rooted later, the fork can be compiled to fall back to direct
> `open()` on `/mnt/media_rw/<UUID>` and the JNI bridge becomes a no-op. The seam is
> isolated in the transmission file backend (Stage 05) and the SAF gateway (Stage 03).

---

## 3. Process & runtime model

Everything runs inside **one Android app process**, kept alive by a single
**foreground service** holding a `PARTIAL_WAKE_LOCK` (+ a `WifiLock`).

```
┌─────────────────────────────────────────────────────────────────────────┐
│ App process (foreground service: wakelock + wifilock)                     │
│                                                                           │
│  Kotlin supervisor ── spawns & restarts ──┐                               │
│                                            ▼                              │
│   native children (ProcessBuilder, exec'd from nativeLibraryDir):         │
│     • libtransmissiond.so → RPC :9091; payload I/O via SAF-fd JNI → USB   │
│     • libdhtcrawler.so    → HTTP UI :8080, SQLite on internal  [deferred] │
│         └─ shells out (system()) to librclone.so/sqlite3/zstd  [deferred] │
│                                            ▲                              │
│  SAF fd bridge (JNI)  ◀────fd requests─────┘                              │
│       │ opens content:// fds, mkdir/rename/delete/stat/free-space         │
│       ▼                                                                   │
│  Kotlin SAF storage gateway  ──────▶  USB OTG drive (exFAT)               │
│       ▲                                                                   │
│  Kotlin services (in-JVM):                                                │
│     • Control/settings web server (Ktor)      :8090                       │
│     • FTP server (wifi-only)                  :2121  [post-PoC]            │
└───────────────────────────────────────────────────────────────────────────┘
```

### Localhost port map (all bind `127.0.0.1`, all require auth — see §5)

| Port | Service                         | Exposure                                  | PoC? |
|------|---------------------------------|-------------------------------------------|------|
| 9091 | transmission-daemon RPC         | LAN (existing Android remote)             | **Yes** |
| 8090 | Kotlin control/settings web UI  | LAN (single config surface)               | Stub in PoC |
| 8080 | dht_crawler HTTP search UI      | LAN (user searches & copies magnets)      | Deferred |
| 2121 | Kotlin FTP server               | **wifi only**, gated on TRANSPORT_WIFI    | Post-PoC |

The control server can reverse-proxy 8080/9091 so the user has one LAN entry point.

---

## 4. Data flow (the happy path)

**PoC happy path (Stages 01–05):**
1. User pastes a magnet into their **Transmission Android remote** (→ `:9091`).
2. The **forked** transmission-daemon downloads, writing payload files **directly to USB**
   via SAF fds (no internal staging).
3. Files are readable on the USB drive.

**Additional steps unlocked post-PoC:**
4. *(Stage 06)* **Kodi** connects to the **FTP server** (`:2121`) over wifi and streams from USB.
5. *(Stage 07)* User opens dht_crawler UI (`:8080`), searches, copies a **magnet link** instead of sourcing it manually.
6. *(Stage 08)* On schedule, **rclone** uploads the SQLite DB to the configured remote.

---

## 5. Cross-cutting rules (apply to every stage)

- **Native binaries ship as `lib*.so` in `jniLibs/<abi>/`** and are exec'd from
  `applicationInfo.nativeLibraryDir`. **`packaging { jniLibs { useLegacyPackaging = true } }`**
  is mandatory or they won't be extracted to disk and exec fails. (`chmod +x` in `filesDir`
  never works on Android 10+.) See Stage 02.
- **Statically link** the C binaries (or co-ship and `LD_LIBRARY_PATH` their `.so` deps).
- **Always drain** child `stdout`/`stderr`, or the child blocks. Logs → rotating files +
  control UI.
- **Auth on every localhost port.** Loopback is not UID-isolated on Android. Set
  Transmission RPC user/pass, rclone `--rc-user/--rc-pass`, FTP creds, control-server token.
- **All USB access goes through the single SAF storage gateway** (Stage 03) — including the
  transmission fork's fd requests (via the JNI bridge). No component calls SAF elsewhere.
  This keeps the root-vs-non-root seam in one place.
- **Config is owned by the control server.** Single writer of each daemon's config; stop →
  rewrite atomically → restart (or push live via RPC where supported).

---

## 6. Stage index

### PoC — transmission-daemon writing to USB (build these first)

| Stage | File                                          | Delivers                                                  |
|-------|-----------------------------------------------|-----------------------------------------------------------|
| 01    | `01-foundation-service-and-control-shell.md`  | App shell, foreground service + wakelock, control web stub|
| 02    | `02-native-binary-packaging-and-supervision.md`| Ship/exec/supervise native binaries (proven w/ minimal test binary; dht_crawler deferred)|
| 03    | `03-usb-storage-via-saf.md`                   | SAF USB gateway **+ fd-provisioning bridge** (data-dir import deferred)|
| 04    | `04-transmission-build-baseline.md`           | Cross-compile transmission under NDK, running to internal, RPC |
| 05    | `05-transmission-saf-storage-fork.md`         | **Fork file backend → SAF fds → downloads direct to USB** ← **PoC done**|

### Post-PoC extensions (build after Stage 05 acceptance test passes)

| Stage | File                                          | Delivers                                                  |
|-------|-----------------------------------------------|-----------------------------------------------------------|
| 06    | `06-ftp-server.md`                            | Wifi-only FTP serving USB to Kodi                         |
| 07    | `07-dht-crawler.md`                           | Cross-compile dht_crawler + rclone; supervise crawler; USB data import |
| 08    | `08-rclone-backups.md`                        | Scheduled DB backup to a network remote (depends on Stage 07) |
| 09    | `09-unified-settings-and-control.md`          | Full settings/control web UI, first-run wizard            |
| 10    | `10-resilience-and-startup.md`                | Boot start, 24/7 survival, crash recovery, OEM/Doze       |

Build the PoC stages in order first. Stage 05 is the highest-risk, highest-value stage —
Stages 03 and 04 exist to de-risk it (prove the SAF fd works; prove the daemon builds).
Stage 07 (dht_crawler) and Stage 08 (rclone backups) are not needed to prove transmission
writes to USB and should be built after Stage 06.

---

## 7. Open risks to watch (tracked across stages)

### PoC risks (Stages 01–05 — address in order)

- **The SAF-fd fork (Stage 05) is the core technical bet.** Validate the primitive in
  Stage 03 *before* building Stage 05: open a USB doc `"rw"`, `pwrite` at a 5 GB offset,
  `pread` it back, `ftruncate`, `fsync` — from native code via JNI. If the platform returns
  a non-seekable fd on the target device, the whole approach needs rethinking; find out
  early.
- **Preallocation / free-space / rename over SAF** — transmission expects `fallocate`,
  free-space checks, and atomic `.part`→final rename. Each needs a SAF-aware path (Stage 05).
- **Cross-compiling transmission + deps** (curl/openssl/libevent) for arm64 — Stage 04;
  crib flags from `termux-packages`.
- **exFAT required on the drive** (FAT32 4 GB cap). Validate in Stage 03.

### Post-PoC risks (address after Stage 05 acceptance test passes)

- **Android 15 `dataSync` FGS ~6h/24h cap** — breaks always-on. Mitigation in Stage 09
  (likely `specialUse` FGS for sideload). Decide before Stage 09.
- **OEM background killers** — no API fix; user whitelists (Stage 09).
- **Cross-compiling dht_crawler + deps** (libuv/sqlite/openssl) for arm64 — Stage 07.
- **Distribution is sideload-only** assumed (Play policy would fight the FGS/battery needs).
