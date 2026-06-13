# Stage 06 — Kotlin FTP server (wifi-only) serving USB to Kodi

**Builds on:** Stage 03 (SAF gateway — read path), Stage 05 (torrents now live on USB).
**De-risks:** streaming media off a SAF-backed drive to a real client (Kodi) over wifi,
including seek/resume.

## Goal

A native-Kotlin FTP server that serves the USB library to the local **wifi** network only
(never mobile data), supporting the random-access reads Kodi needs to stream and seek.

## What gets built

1. **FTP server (Kotlin/JVM)** bound to `:2121`:
   - Apache FtpServer (MINA) embedded, or a small purpose-built server — choice driven by
     how cleanly its file-system SPI can be backed by the **SAF gateway** rather than
     `java.io.File`. Apache FtpServer has a pluggable `FileSystemFactory`/`FtpFile`
     abstraction that maps well to the gateway.
   - Read-only by default (serving, not uploading).
   - Auth: configurable user/password or anonymous-read (user choice in Stage 08).
2. **SAF-backed FTP filesystem:** implement the server's file abstraction over the Stage 03
   gateway — `list`, `size`, `exists`, and crucially an **`InputStream` that supports
   range/offset** via `ContentResolver.openInputStream` / `openFileDescriptor("r")` +
   `lseek`, so FTP `REST` (resume offset) works for streaming/seeking in Kodi.
3. **Wifi-only gating:**
   - Use `ConnectivityManager` + `NetworkCapabilities.TRANSPORT_WIFI` to bind the listener
     only to the wifi interface address, and **stop/refuse** serving when the active
     transport is cellular.
   - Register a network callback: on wifi-lost → stop the listener; on wifi-gained →
     (re)bind. Surface state in the control UI.
4. **Library exposure:** serve the USB download tree (the same root transmission writes to),
   so completed torrents appear automatically. Optionally hide `.part`/incomplete files.

## Key decisions & gotchas

- **Range support is mandatory** for Kodi seeking — verify FTP `REST`/`SIZE` work and that
  the SAF read fd seeks correctly (it does if Stage 03's seekable-fd test passed).
- **Never bind `0.0.0.0` unconditionally** — bind the wifi address so the server isn't
  reachable over a cellular/tether interface. Re-evaluate the bind on every network change.
- Throughput: SAF reads add FUSE overhead; confirm a high-bitrate file streams without
  stalls. Use a sensible read buffer.
- Don't serve transmission's internal config; only the USB media tree.
- Coexists with transmission reading/writing the same USB files — reads are fine; avoid
  locking semantics that block transmission's writer.

## Out of scope (later stages)

- FTP settings UI (Stage 08).
- HTTPS/FTPS (note as a possible later hardening; LAN-only mitigates).

## Acceptance test

- On wifi, Kodi on another device connects to the FTP server, browses the USB library, and
  **streams a large video with seeking** (jump forward/back) without re-downloading.
- Switch the phone to mobile data (wifi off): the FTP server stops accepting connections;
  back on wifi, it resumes. Verified in the control UI state.
