# Stage 2 implementation plan (async writes, backpressure, async flush)

Spec: `TRANSMISSION_USB_SAF_2.md` (fixed design, don't redesign). Conventions: `TRANSMISSION_USB_SAF_0.md`.
Patched tree for reading: `app/build/native/src/transmission-4.1.2/libtransmission/` (regenerated
from apply_fork.py at stage-1 HEAD). Never edit it; changes go into
`app/src/main/cpp/transmission-fork/` (fork files edited directly; upstream files via new
`apply_fork.py` steps 20+, anchors must match the already-patched text).

To re-check patches: `rm -rf app/build/native/src/transmission-4.1.2 && tar -xJf ... && python3 apply_fork.py <dir>`
(or `./gradlew :app:buildTransmission` after deleting the tree).

## Stage A — disk-io worker additions (fork files only)  [2.1]
- `disk-io.h`: include `libtransmission/cache.h`; `tr_disk_write_result`; `write`, `barrier`,
  `wait_idle`, `writes_backlogged`, `shutdown_drain`; members `std::atomic<uint64_t>
  queued_write_bytes_`, `bool backlogged_` (declared before `queue_`, which must stay last).
- `disk-io.cc`: `write_entire_buf` copy; `worker_get_fd` gets an optional `bool* created` out-param
  (set when the create branch returns an fd); write job (1 MiB scratch, per-segment pwrite with
  `tr_disk_timer "pwrite"`, stop at first error, subtract bytes always, post completion that calls
  `session->add_file_created()` N times then `done(result)`); barrier; wait_idle (shared promise set
  on worker); hysteresis 64/32 MiB with Info logs; shutdown_drain. Keep `shutdown()` for now
  (removed from session in stage C).
- Gate: compiles (via stage B build, or a quick buildTransmission since nothing calls it yet).

## Stage B — Cache write path rewrite  [2.2, 2.3]
- New apply_fork.py step 20 (marker `ANDROID fork: stage 2 async cache writes`) on cache.h/cache.cc.
  Replace whole functions/blocks (find_span_end, find_biggest_span, write_contiguous→enqueue_write,
  write_block, read_block untouched, flush_span, flush_file, flush_torrent, flush_biggest,
  cache_trim, add flush_piece, on_write_done).
- Notes: `CIter` is a const_iterator; to mark in-flight either make `in_flight` `mutable` or
  convert to mutable iterators via index. enqueue_write must not insert/erase (iterators stay
  valid during the span walk). On error, call `tr_torrentStop` **after** the erase loop.
  cache_trim terminates because size-inflight > max implies a non-in-flight span exists.
- Gate: `grep tr_ioWrite libtransmission/*.cc` shows only inout.cc definition; build compiles.

## Stage C — torrent/session/peer gates  [2.4–2.8]
- step 21 torrent.cc: enqueue_piece_check flush_piece + `{}` overlay; on_file_completed barrier.
- step 22 peer-msgs.cc + webseed.cc backpressure gates.
- step 23 session.cc: wrappers per 2.7 (keep stage-0 timers), closeImplPart1 per 2.8
  (remove stage-1 shutdown line, add shutdown_drain after `torrents.clear();` before `cache.reset()`).
- Gate: `tests/run.sh`, `./gradlew installDebug`.

## Stage D — device verification + measurements  [2.9]
- Scenario 1 with rpc_probe.sh (torrent/testtest) + sample_threads.sh; log at
  `/data/user/0/com.torrent.app/files/logs/transmission.log` / `adb logcat -s transmission`.
- Stop/start, remove with data, kill -9 + full verify.
- Create `## Measurements` in TRANSMISSION_USB_SAF.md (missing) and add stage 2 rows.
- Commit `feat(transmission): stage 2 ...`.
