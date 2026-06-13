# Stage 03 — USB storage via SAF (gateway + fd bridge)

**Builds on:** Stage 01 (control UI).
**De-risks:** the project's central bet — that native code can do random-access I/O to USB
through a SAF file descriptor. **Prove this here, before the transmission fork (Stage 05).**

> **PoC scope:** this stage builds only the gateway and fd bridge needed for transmission.
> The dht_crawler `data/` import is **deferred to post-PoC** — it is not needed to prove
> transmission writes to USB.

## Goal

A single Kotlin **SAF storage gateway** that owns all USB access, plus a **native-facing fd
bridge** (the API the transmission fork will call via JNI). The SAF fd primitive must be
validated for seekability and large-file I/O before the fork is attempted in Stage 05.

## What gets built

1. **USB volume selection (first-run setup):**
   - `ACTION_OPEN_DOCUMENT_TREE` to let the user grant the USB drive's root.
   - `takePersistableUriPermission` so access survives reboots.
   - Persist the granted tree URI in app settings; surface "USB: connected / not granted"
     in the control UI.
2. **SAF storage gateway (Kotlin)** — the *only* component that touches SAF. High-level API:
   - `list(relPath)`, `exists(relPath)`, `size(relPath)`, `mkdirs(relPath)`,
     `delete(relPath)`, `rename(relPath, newName)`, `freeSpace()`.
   - Maintains a **relative-path ⇄ document-URI map** (DocumentFile tree is slow to walk;
     cache lookups). The "download root" the daemons see is virtualized over the granted
     tree.
   - Backed by `DocumentsContract` / `DocumentFile` / `ContentResolver`.
3. **Native-facing fd bridge** — the API the transmission fork (Stage 05) will reach via
   JNI:
   - `openFd(relPath, mode): Int` — ensures parent dirs exist, creates the document if
     needed, calls `ContentResolver.openFileDescriptor(uri, mode)`, returns
     `pfd.detachFd()` (a raw int the native side owns and must `close()`).
   - `truncate(relPath, len)`, `statSize(relPath)`, `renameDoc(relPath, newName)`,
     `removeDoc(relPath)`, `mkdirs(relPath)`, `availableBytes()`.
   - Defined as a clean Kotlin interface now; the JNI surface is added in Stage 05. Keep it
     synchronous and thread-safe (transmission's disk thread will call it).
4. **Primitive-validation harness (CRITICAL):** a debug screen / test that, from **native
   code** (a tiny JNI test fn) using an fd from `openFd`:
   - `pwrite` a pattern at a **>4 GB offset**, `pread` it back, `lseek`, `ftruncate` to grow
     and shrink, `fsync`. Confirms the platform returns a **seekable** fd for the target
     drive and that large/random-access I/O works over the FUSE layer.
   - Measure throughput. Record results in the stage's notes.
5. **Drive guidance:** detect/report the USB filesystem; warn if **FAT32** (4 GB file cap) —
   recommend **exFAT**.

*(Deferred — post-PoC) **Import existing dht_crawler `data/` from USB:** UI action that
copies `data/torrents.db` (+ bloom/aux files) from a user-picked USB folder into the
crawler's internal `data/` via the gateway, with progress and integrity check
(`--check-database`). Not needed until Stage 02b adds dht_crawler supervision.*

## Key decisions & gotchas

- **Seekable-fd assumption must be verified on the real device + drive (step 4).** If the
  provider returns a non-seekable/pipe fd, Stage 05 is not viable as designed — find out
  now.
- **exFAT** strongly recommended; Android 11+ mounts it natively. FAT32 breaks large media.
- DocumentFile tree walks are O(n) and slow — **cache the path→URI map**; never re-walk per
  I/O.
- `availableBytes()`/free space: try the SAF root's `COLUMN_AVAILABLE_BYTES`; if absent,
  report a large sentinel and rely on write errors (transmission free-space checks handled
  in Stage 05).
- The fd returned by `detachFd()` is owned by native code; define close ownership clearly to
  avoid leaks (transmission `tr_sys_file_close` → JNI → nothing, since native already holds
  the fd and closes it directly).

## Out of scope (later stages)

- The transmission JNI wiring & file-backend fork (Stage 05).
- FTP reads through the gateway (Stage 06).

## Acceptance test

- User grants the USB drive; grant persists across an app restart.
- The native validation harness writes/reads a >4 GB random-access pattern to USB via a SAF
  fd and passes, with a recorded throughput number.

*(Deferred acceptance criterion — post-PoC) Importing a real `data/torrents.db` from USB
populates the crawler's internal DB and `--check-database` passes.*
