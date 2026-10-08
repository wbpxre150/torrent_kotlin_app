# Plan: move transmission disk I/O off the session thread (USB/SAF)

**Status**: Outline, not started. Split into stage plans `TRANSMISSION_USB_SAF_0.md` … `_5.md` (stage N = phase N below).
**Goal**: RPC (port 9091) stays responsive no matter how slow USB storage is.

---

## Problem

transmission-daemon does all piece I/O synchronously on its session thread (the libevent
loop that also serves RPC, peers, trackers and timers). Any slow `pread`/`pwrite` therefore
stalls RPC for its full duration.

On this device the USB drive is **exFAT on kernel 5.10, accessed through Android's FUSE
layer** (`/storage/FABE-BF49`, ~8–10 MB/s). That combination makes slow I/O routine:

- **No sparse files.** A write past EOF makes the kernel zero-fill from the old EOF to the
  write offset *inside the pwrite call*. With `"preallocation": 0` (set in
  `TransmissionConfig.kt` by commit `a3e9bd9`) and rarest-first piece selection, the first
  piece landing near the end of a fresh 1.8 GB file blocks for ~3 minutes.
- **Single-queue device.** Seeding reads, download writes and piece-verify reads all queue
  behind each other, so even in-place I/O takes 1–30 s under load.

### Evidence (2026-10-07, live device)

- Session thread in `D` state, syscall 68 (`pwrite64`), 344 KB at offset 1.845 GB into a
  0-byte file (`Manifest … S04E18`). The file jumped 0 → 1,847,054,411 bytes when the call
  returned. One RPC call made during that window took **188 s**.
- Manifest torrent at 0.8% complete already occupied ~62 GB on disk (zero-filled files).
- Session thread `iowait_sum` 214 s of ~265 s total runtime.
- The SAF bridge itself (Kotlin `SafIpcServer`, gateway locks) was **not** involved: the
  thread was blocked in a direct `pwrite` on a FUSE fd, not in `recvmsg` on
  `saf-bridge.sock`.
- Rejected alternative (prototyped, then reverted): a Kotlin preallocator that stops a
  torrent, extends its files with `ftruncate`, then restarts it. That removed the
  multi-minute stalls, but seeding reads still showed 1–35 s RPC spikes while USB was busy,
  and torrents stayed stopped for hours during extension. Making transmission's own
  preallocation async does not work either: it runs right before the first write to a new
  file (`open-files.cc:201`), so that write still blocks.

---

## Session-thread disk I/O inventory (transmission 4.1.2)

Line numbers refer to the patched tree in `app/build/native/src/transmission-4.1.2/libtransmission/`.

| # | Path | Where | Cost on USB |
|---|---|---|---|
| 1 | **Block writes**: `tr_peerMsgsImpl::client_got_block` → `Cache::write_block` → (cache full) `Cache::write_contiguous` → `tr_ioWrite` | `peer-msgs.cc:1757`, `cache.cc:65-141`; also `webseed.cc:393` | Seconds; minutes if past EOF (zero-fill) |
| 2 | **Piece hash check after download**: `tr_torrent::on_block_received` → `check_piece` → `tr_ioTestPiece` (reads the whole piece) | `torrent.cc:2220-2250`, `torrent.cc:1993` | Reads 1 piece (16 MB for Manifest) ≈ 2 s per completed piece |
| 3 | **Upload reads**: peer request → `ensure_piece_is_checked` (may hash a whole piece) → `Cache::read_block` → `tr_ioRead` | `peer-msgs.cc:2025-2060` | 16 KB random reads, 0.1–1 s each under load, issued serially |
| 4 | **Flushes**: `flush_torrent` / `flush_file` on stop, remove, sequential mode, file completion | `session.cc:2151-2164`, `torrent.cc:2189` | Writes the whole cache |
| 5 | **File open/stat/create via SAF IPC**: `tr_open_files::get` → `tr_sys_path_get_info` + `tr_sys_file_open` → `file-saf.cc` round trip to Kotlin | `open-files.cc:179-232`, `file-saf.cc` | SAF `createDocument`/query, 100–500 ms |
| 6 | **Preallocation** (if re-enabled) | `open-files.cc:201-232` | Full zero-fill of the file |

Already off-thread (use as the model): **full verify** runs on `tr_verify_worker`'s own
thread (`verify.cc:132`) and reports back through a mediator.

---

## Design outline

### 1. A disk I/O worker (`disk-io.cc/.h`, new fork file)

- One dedicated worker thread (a USB stick is a single queue, so parallelism buys nothing),
  with **two queues: reads take priority over writes**, so seeding and verify reads don't
  sit behind bulk writes or zero-fill.
- Jobs: `Write{tor_id, loc, buffer}`, `Read{tor_id, loc, len, cb}`,
  `TestPiece{tor_id, piece, cb}`, `Flush{tor_id, file?, cb}`, `Barrier{tor_id, cb}`.
- Completions are posted back to the session thread (`session->run_in_session_thread` or
  an equivalent event queue). The worker **never touches torrent or session state
  directly**. It receives copies of everything it needs (file paths, piece offsets, hashes)
  at enqueue time.
- The worker owns its own `tr_open_files` instance (the current one is not thread-safe).
  The session thread stops opening data files itself.
- Add it the same way as `file-saf.cc`: new files plus patch hunks in
  `app/src/main/cpp/transmission-fork/apply_fork.py`.

### 2. Async writes (inventory #1, #4)

- `Cache` keeps its role as the in-memory block store. When it would flush, it hands the
  contiguous run to the worker instead of calling `tr_ioWrite`, and marks those blocks
  **in-flight**. Blocks stay readable from the cache until the write completes.
- **Backpressure, never blocking**: track queued write bytes. Above a high-water mark
  (e.g. 64–128 MB), stop issuing new block requests to peers (gate in the wishlist/request
  path in `peer-mgr.cc`) until it drains below a low-water mark.
- Write errors arrive asynchronously. Route them to the existing local-error handling
  (`tor->error().set_local_error`) on the session thread.
- `flush_torrent` / `flush_file` become async (a `Flush` job plus a callback). Callers that
  currently need a synchronous flush (torrent stop/remove/relocate, file completion) are
  covered in step 6.

### 3. Async piece verification after download (inventory #2)

- `on_block_received`: when a piece's last block arrives, enqueue `TestPiece` instead of
  calling `check_piece` inline. The worker must see the piece's blocks: either queue it
  behind that piece's pending writes (same queue, ordered), or hash from the cache plus disk.
- Add a per-torrent "pending check" set. Until the result arrives, the piece is neither
  advertised (no HAVE, not in bitfield) nor counted complete.
- On completion, run `on_piece_completed` / `on_piece_failed` on the session thread,
  unchanged (they already carry the ANDROID-fork `checked_pieces_` fix).
- Resume/`have` state must only include pieces that are **written and verified**.

### 4. Async upload reads (inventory #3)

- `fill_output_buffer` / the piece-request path: if the block is in the cache, serve it
  synchronously as now. Otherwise enqueue `Read` and send the `Piece` message from the
  completion callback (re-check the peer is still connected and the request wasn't
  cancelled).
- Cap outstanding reads per peer and globally, so a fast peer can't flood the queue.
- `ensure_piece_is_checked` (lazy verify of unchecked pieces) becomes a `TestPiece` job.
  The request waits for it, or is rejected (with fast extension) if it's still pending.

### 5. SAF IPC concurrency (inventory #5)

- `file-saf.cc` uses one global socket guarded by `g_mutex`. Once the worker does opens,
  session-thread SAF calls (stat/rename/mkdir/remove) would queue behind it. Switch to one
  connection per native thread (thread-local `g_sock`). `SafIpcServer` already handles one
  coroutine per connection.
- Audit the remaining session-thread SAF calls (`tr_sys_path_get_info` in
  `load_checked_pieces` at startup, move/rename/remove) and decide which also move to the
  worker.

### 6. Lifecycle and synchronization points

- **Torrent stop/remove/relocate/set-location**: today these flush and close files
  synchronously. Make them two-phase: mark the torrent stopping, enqueue `Barrier`, and
  finish (close files, update state, fire callbacks) in the completion. Jobs for a removed
  torrent must be cancellable or harmless (look up the torrent by id on completion and
  ignore missing ones).
- **Session shutdown**: drain the worker with a timeout before `tr_sessionClose` finishes,
  so cached writes aren't lost.
- **Bandwidth accounting**: `bytes_downloaded_` / corrupt counters update on completion,
  not enqueue.

### 7. Preallocation

- Once writes are off-thread, the exFAT zero-fill only delays the worker (and, through
  backpressure, download speed), never RPC. Then decide whether to restore transmission's
  default `"preallocation": 1` in `TransmissionConfig.buildSettingsJson` (zero-fill once,
  up front, on the worker) or keep `0` (zero-fill on demand). Measure both.

---

## Phasing

| Phase | Scope | Exit criterion |
|---|---|---|
| 0 | Instrumentation: log any session-thread disk call over 100 ms (op, path, offset, ms) | Baseline numbers for the scenarios below |
| 1 | Worker thread + async piece verification (#3) | No `tr_ioTestPiece` on the session thread |
| 2 | Async writes + backpressure + async flush (#2, part of #6) | No `pwrite` on the session thread during download |
| 3 | Async upload reads + lazy verify (#4) | No `pread` on the session thread while seeding |
| 4 | Per-thread SAF sockets, two-phase stop/remove (#5, #6) | Stop/remove during heavy I/O doesn't stall RPC |
| 5 | Preallocation decision (#7), remove instrumentation noise | Settings committed with measurements |

Each phase is a separate commit and a separate `apply_fork.py` step, and must pass the
existing seeding-corruption regressions (Mechanisms C/D, Fix B/E in `apply_fork.py`).

---

## Verification

- **RPC latency probe**: loop `curl -u torrent:… http://<device>:9091/transmission/rpc`
  every second. Count only real responses (HTTP 409/200). Connection-refused returns
  instantly and looks "fast", so exclude it. Target: p99 < 500 ms during all scenarios.
- **Session-thread sampling**: `adb shell run-as com.torrent.app cat /proc/<pid>/task/<tid>/syscall`
  in a loop. The session thread should never show 67 (`pread64`) or 68 (`pwrite64`).
- **Scenarios**:
  1. Add a new multi-GB, multi-file torrent with preallocation off (the original repro).
  2. Heavy seeding (many peers, random reads) while downloading.
  3. Stop, remove (with data), and relocate a torrent mid-download under load.
  4. Kill the daemon mid-download; on restart, no piece is marked "have" without its data
     (run a full verify to confirm).
  5. USB removed mid-write: errors surface as torrent local errors, no crash or hang.
- Unit-level: the worker queue (ordering, read priority, barrier semantics, cancellation)
  should be testable without a session, using a fake I/O backend.

---

## Risks

- **Fork maintenance**: these patches touch core files (`cache.cc`, `peer-msgs.cc`,
  `torrent.cc`, `peer-mgr.cc`, `open-files.cc`) and will conflict with upstream updates.
  Keep the hunks small and isolated behind the worker interface.
- **Consistency**: async writes plus async verification add states (in-flight,
  pending-check) that resume, completeness and RPC `bytesCompleted` must respect.
- **Memory**: the backpressure limits must hold on the phone. The cache is currently set to
  4 MB (`cache-size-mb`).
- **Upstream** (checked 2026-10-07): not moved. The latest release, 4.1.3, still does
  synchronous I/O. On `main`, the async series (#8742) deleted the write cache and added an
  unused `tr::LocalData`, then stalled in March 2026. Decision: write our own worker on 4.1.2,
  with an API loosely shaped like `LocalData`.

---

## Measurements

RPC = `tools/usb-io/rpc_probe.sh` (`session-get` every 1 s, HTTP 200/409 only, no failures in
any run). Samples = `tools/usb-io/sample_threads.sh` on `tr-session` (200 ms interval), as
D / 67 (`pread64`) / 68 (`pwrite64`). Device: phone over wireless adb, USB drive via SAF,
`cache-size-mb` 4, preallocation 0.

Stage 0/1 rows were never recorded. Reference baseline from the 2026-10-07 evidence above:
one RPC call took **188 s** while `tr-session` sat in `pwrite64`.

| stage | scenario | RPC p50 | RPC p99 | RPC max | session-thread D/67/68 samples | worst DISKSLOW op |
|---|---|---|---|---|---|---|
| 2 | 1: new 4.4 GB 8-file torrent, first 5 min (1–2.4 MiB/s, backlog paused once) | 15.2 ms | 47.5 ms | 52.6 ms | 1/1/0 of 837 | `tr-diskio` pwrite 8574 ms (no `tr-session` DISKSLOW) |
| 2 | 1 + 3 + 4: stop/start, kill -9 + full verify, download to 100%, full verify, remove with data (≈23 min) | 15.0 ms | 300.3 ms | 924.9 ms | 1 pread event, 0 pwrite (summary lost) | `tr-diskio` pwrite 6696 ms; `tr-session` only read opens (≤346 ms) |
| 2 | seeding + full verify (final build) | 13.9 ms | 51.0 ms | 82.5 ms | 0/0/0 of 336 | none |

Stage 2 notes:
- `kill -9` at 22.9%, restart, full verify: 241 pieces "have" before, 251 after, 0 lost.
- Remove with "delete data" of a completed 4.4 GB torrent: RPC returned in 1.3 s; files gone
  and not recreated.
- A re-download of the same torrent at up to ~10 MiB/s: 0 failed post-download checks,
  `corruptEver` 0, full verify 100%. The first download (after the `kill -9` restart) logged
  `corruptEver` 557 MB (~133 pieces). The data verified 100% afterwards and no cause in the write
  path was found (a diagnostic build that re-checked failed pieces saw no failures), so it is
  attributed to bad peers, unconfirmed.
- Remaining `tr-session` disk work is upload-side: read opens/`pread` in `tr_ioRead` and
  `ensure_piece_is_checked` (`testpiece`) for unchecked pieces. Stage 3 scope.

| stage | scenario | RPC p50 | RPC p99 | RPC max | session-thread D/67/68 samples | worst DISKSLOW op |
|---|---|---|---|---|---|---|
| 3 | 2: 8 local libtorrent peers leeching from the phone only (~31 MiB/s up, 9.4 GB in 300 s) while the torrent downloads ~1.4 MiB/s; RPC via `rpc_probe.sh` over wireless adb | 463.7 ms | 809.8 ms | 1215.2 ms | 0/0/0 of 380 (`tr-diskio` 106/91/16) | `tr-diskio` testpiece 352 ms; no `tr-session` pread/testpiece |
| 3 | 2, same load (8 peers, ~31 MiB/s), RPC measured **on the device** (`curl` to 127.0.0.1) | 3.4 ms | 527.6 ms | 854.1 ms | n/a | 2 of 130 samples > 50 ms |
| 3 | 2, 12 peers (~21 MiB/s), RPC on device + 50 ms device-side `tr-session` syscall sampler | 3.6 ms | 14.5 ms | 31.9 ms | sampler: never outside epoll/ppoll | none on `tr-session` |
| 3 | no upload load (download only), RPC on device | 3.5 ms | 8.1 ms | 8.8 ms | n/a | — |

Stage 3 notes:
- Load generator: `libtorrent` 2.1 (python) with N sessions, IP-filtered so they only talk to
  the phone, plaintext, TCP only. Each peer verifies every piece: ~21,000 pieces received over
  all runs, **0 hash failures**. `error` stayed 0 and there were no "corrupt" local errors.
  (`corruptEver` rose 0 → 6 MiB during the session from internet peers on the download side,
  which stage 3 doesn't touch.)
- The `rpc_probe.sh` numbers under upload load measure the saturated WiFi link that wireless
  adb shares with the peer traffic, not the session thread. On the device itself p50 stays
  ~3.5 ms. The two 0.5–0.85 s on-device outliers in the 31 MiB/s run didn't reproduce in
  later runs with the device-side sampler, which never caught `tr-session` outside its
  poll wait.
- `tr-session` was at 17% CPU at ~30 MiB/s up. Once the page cache is cold, seeding throughput
  is bound by the single `tr-diskio` thread doing random 16 KiB reads (`D`/`pread64` ~60% of
  samples, ~3–8 MiB/s total). A possible later improvement is piece-sized read-ahead.
- Restart (SIGKILL via force-stop) left ~6 pieces unchecked. Uploads resumed right away and
  lazy checks ran on `tr-diskio` (`testpiece`). The only `tr-session` DISKSLOW after restart
  is one `saf-stat` (128 ms) at torrent load, outside stage 3's scope.
- The per-call-site log rate limit ("Too many messages like this") mutes `[DISKSLOW]` after
  ~30 lines per daemon session, so the thread sampler is the main evidence.
- Beyond the spec: `on_block_read` calls `fill_output_buffer` after each completion, so queued
  requests keep flowing. Otherwise a peer would get only 4 blocks per pulse.
