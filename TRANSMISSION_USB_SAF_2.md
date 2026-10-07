# Stage 2: async block writes, backpressure, async flush

Part of `TRANSMISSION_USB_SAF.md`. Requires stages 0–1. Read the **Conventions** in
`TRANSMISSION_USB_SAF_0.md` and the design in `TRANSMISSION_USB_SAF_1.md` (`tr_disk_queue`,
`tr_disk_io`, `tr_disk_make_segments`, pending-check set) first.

**Goal**: the session thread never calls `pwrite`. `Cache` hands contiguous block runs to the
worker. Blocks stay readable from the cache until the worker has written them. Downloads pause
(no new block requests) while too many bytes are queued for writing.

**Exit criterion**: during scenario 1 (new multi-GB torrent, preallocation 0), the log has no
`thread=tr-session op=pwrite` and no `thread=tr-session op=open` for writes, and
`tools/usb-io/sample_threads.sh` never shows syscall 68 on `tr-session` while downloading.
Torrents complete and pass a full verify.

---

## Fixed design (don't redesign)

- **In-flight blocks**: `Cache::CacheBlock` gets `bool in_flight`. When the cache would write,
  it marks the run in-flight and enqueues a `Write` job on the **Low** queue. In-flight blocks
  stay in `blocks_` (so `read_block` and the overlay still find them) and are erased by the
  write completion.
- **Shared buffers, no copies**: `CacheBlock::buf` becomes
  `std::shared_ptr<BlockData const>`. The `Write` job holds `shared_ptr` copies of the run's
  buffers. On the completion, a cache entry is erased **only if** its `buf.get()` is still the
  pointer that was written. A block re-downloaded meanwhile was replaced with a new buffer and
  must stay.
- The cache limit (`max_blocks_`) counts **only non-in-flight** blocks. In-flight bytes are
  bounded by backpressure instead.
- **Backpressure**: `tr_disk_io` tracks queued write bytes (`std::atomic<uint64_t>`,
  incremented on enqueue on the session thread, decremented by the worker after each write).
  Hysteresis is evaluated on the session thread: **high water 64 MiB, low water 32 MiB**. While
  backlogged, peers and webseeds request no new blocks. Already-requested blocks still arrive and
  are accepted.
- **Post-download check ordering**: before enqueuing the piece's `test_piece`, flush that
  piece's cached blocks (they become Write jobs on Low), then enqueue `test_piece` on **Low**
  with an **empty overlay**. FIFO order means the check reads exactly what was written, so a
  piece becomes "have" in the resume file only when it is **written and verified**.
- **Write errors**: on the session thread, the same as today's `tr_ioWrite` failure: if the
  torrent still exists and its error type isn't already `TR_STAT_LOCAL_ERROR`, call
  `tor->error().set_local_error(msg)` and `tr_torrentStop(tor)`. Erase the failed blocks from
  the cache anyway. (The piece's check then fails with -1 or 0 and the piece re-downloads.)
- **Flushes are non-blocking** (`flush_torrent`/`flush_file` just enqueue). The only
  **temporary** blocking wait is in `tr_session::close_torrent_files()` (stop, remove,
  relocate, completion), which waits on a barrier so files are never deleted or moved while
  writes for them are still queued. Stage 4 removes this wait.
- File completion (`on_file_completed`) must not block: the `.part` rename
  (`update_file_path`) runs in a barrier completion instead.
- Shutdown drains the worker (bounded by the session close deadline) **after** the torrents
  are freed, so cached writes are not lost.

---

## Steps

### 2.1 `tr_disk_io` additions (`disk-io.h` / `disk-io.cc`)

```cpp
struct tr_disk_write_result { int err = 0; std::string message; };

// Write `buffers` (concatenated, in order) at the byte range described by `segments`.
// Low queue. `done` runs on the session thread.
void write(
    std::vector<tr_disk_segment> segments,
    std::vector<std::shared_ptr<Cache::BlockData const>> buffers,
    std::function<void(tr_disk_write_result)> done);

// Low queue job that does nothing. `done` runs on the session thread once every Low job
// enqueued before it has finished.
void barrier(std::function<void()> done);

// Session thread. Blocks until a barrier passes or `timeout` elapses. Returns false on timeout.
// TEMPORARY (removed in stage 4). Implement with std::promise<void> + future.wait_for. The
// barrier job sets the promise directly on the worker (don't post to the session thread, or it
// would deadlock).
bool wait_idle(std::chrono::milliseconds timeout);

// Session thread. Backpressure with hysteresis (64 MiB / 32 MiB).
[[nodiscard]] bool writes_backlogged();

// Drain the Low queue until empty or `deadline`, then stop.
void shutdown_drain(std::chrono::steady_clock::time_point deadline);
```

- `disk-io.h` needs `#include "libtransmission/cache.h"` for `Cache::BlockData` (check that
  this doesn't make an include cycle. `cache.h` doesn't include `disk-io.h`).
- Write job body (worker): walk the segments, filling a reused 1 MiB scratch buffer from the
  concatenated `buffers` (track a buffer index plus an offset into it), and for each segment
  `pwrite` the matching bytes with a `tr_sys_file_write_at` loop (copy `write_entire_buf` from
  `inout.cc`). Use the writable fd lookup from stage 1 (it may create the file; count creations
  and post `session->add_file_created()` that many times in the completion). Wrap each write
  in `tr_disk_timer t{ "pwrite", seg.file.subpath, off, n };`. Stop at the first error. Put
  `err = error.code()` and
  `message = fmt::format(_("Couldn't save '{path}': {error} ({error_code})"), ...)` in the result.
  After the job, subtract the job's total bytes from the atomic counter, **whether it succeeded or
  failed**.
- `writes_backlogged()`: `auto const q = queued_write_bytes_.load();` If `!backlogged_ && q >= 64 MiB`,
  set `backlogged_ = true`. If `backlogged_ && q <= 32 MiB`, set `backlogged_ = false`. Return
  `backlogged_` (a plain `bool`, session thread only). Log once at Info on each transition
  (`"Disk write backlog: pausing block requests"` / `"...resuming"`).
- `shutdown_drain`: `queue_.stop_and_drain(deadline)`.

### 2.2 `Cache` rewrite of the write path (`cache.h` / `cache.cc`)

Keep the public API names. Changes:

1. `CacheBlock { Key key; std::shared_ptr<BlockData const> buf; bool in_flight = false; };`
   `write_block` still takes `std::unique_ptr<BlockData>` (callers unchanged) and assigns it to
   the `shared_ptr`. If the existing entry was in-flight, replacing its buffer must also set
   `in_flight = false` (the new data hasn't been handed to the worker).
2. Add the member `size_t in_flight_count_ = 0;`. Keep it exact everywhere `in_flight` changes
   or an in-flight entry is erased.
3. `cache_trim()`: loop `while (std::size(blocks_) - in_flight_count_ > max_blocks_)`.
4. `find_span_end`: two neighbours are adjacent only if the current rule holds **and** both are
   `!in_flight`. `find_biggest_span`: skip spans whose first block is in-flight. Then a span is
   either a single in-flight block (skipped) or a run of non-in-flight blocks.
5. Replace `write_contiguous(begin, end)` with `enqueue_write(begin, end)`, which:
   - looks up `tor = torrents_.get(torrent_id)` and returns `EINVAL` if it's null (as today);
   - computes `len` = the sum of the buffer sizes and `loc = tor->block_loc(first block)`;
   - builds `segments = tr_disk_make_segments(*tor, loc, len, true)`;
   - collects `buffers` (shared_ptr copies) and a `std::vector<std::pair<Key, BlockData const*>>`
     `written` list;
   - marks those entries `in_flight = true` (and updates `in_flight_count_`);
   - calls `tor->session->disk_io().write(segments, buffers, done)`, where `done` captures
     `session` (`tr_session*`), `tor_id` and `written`. On the session thread: if
     `session->cache` is null, return (shutdown). Otherwise call the new private
     `Cache::on_write_done(written, result)` through a public trampoline (simplest: make
     `on_write_done` public and mark it `// ANDROID fork: internal`);
   - updates `disk_writes_` and `disk_write_bytes_` in `on_write_done`, not here.

   `Cache` needs a session pointer for `disk_io()`. `tor->session` is available through the
   torrent, so use that (no constructor change).
6. `on_write_done(written, result)`: for each `(key, ptr)`, find the entry with `equal_range`.
   If it exists and `entry.buf.get() == ptr` and `entry.in_flight`, erase it (decrement
   `in_flight_count_`). Erase in reverse order or re-search each time, because erasing
   invalidates iterators. If `result.err != 0`: `auto* tor = torrents_.get(key.first)` (the
   first entry's torrent) and, if non-null and `tor->error().error_type() != TR_STAT_LOCAL_ERROR`,
   call `tor->error().set_local_error(result.message); tr_torrentStop(tor);`. Need
   `cache.h`'s `torrents_` to be non-const for that, or use `tor->session->torrents()`. Pick
   whichever compiles without `const_cast`.
7. `flush_span(begin, end)` and `flush_biggest()`: same span walk as today, but call
   `enqueue_write` per span and **don't** `blocks_.erase` (the completion erases). Spans are
   non-in-flight only, per rule 4.
8. **Remove the `max_blocks_ == 0` bypass** in `write_block`. Always insert, then `cache_trim()`.
   With limit 0, every non-in-flight block is flushed at once, but it stays readable while in
   flight.
9. Add `int flush_piece(tr_torrent const& tor, tr_piece_index_t piece);` the same way as
   `flush_file`, using `tor.block_span_for_piece(piece)`.
10. `read_block` is unchanged (it finds in-flight entries too). `copy_cached_block` (stage 1)
    is unchanged.

### 2.3 `tr_ioWrite` no longer called on the session thread

After 2.2, `grep -n "tr_ioWrite" libtransmission/*.cc` in the patched tree must show only its
definition in `inout.cc`. If any caller remains, route it through the cache.

### 2.4 Post-download check after the piece's writes (`torrent.cc`)

In `tr_torrent::enqueue_piece_check` (stage 1), before `test_piece`:
```cpp
    session->cache->flush_piece(*this, piece); // ANDROID fork stage 2: writes queued ahead of the check
```
and pass `{}` instead of `tr_disk_make_piece_overlay(*this, piece)`. Keep
`tr_disk_make_piece_overlay` (stage 3 uses it).

### 2.5 Backpressure gates

- `peer-msgs.cc`, `tr_peerMsgsImpl::maybe_send_block_requests()`: after the
  `if (!tor_.client_can_download())` early return, add:
  ```cpp
      // ANDROID fork: TRANSMISSION_USB_SAF stage 2 write backpressure.
      if (session->disk_io().writes_backlogged())
      {
          return;
      }
  ```
- `webseed.cc`: in the function that calls `tr_peerMgrGetNextRequests(&tor, this, max_blocks)`
  (around line 305), add the same early return before `max_available_reqs()` is used. Use
  `tor.session->disk_io()` or the webseed's `session_` member, whichever is in scope.

### 2.6 Non-blocking file completion (`torrent.cc`)

`tr_torrent::on_file_completed(file)` today does `close_torrent_file`, sets `file_mtimes_`,
then `update_file_path(file, true)`. Change it to:
```cpp
    session->close_torrent_file(*this, file); // now: enqueue flush_file + worker close_file
    file_mtimes_[file] = tr_time();
    // ANDROID fork: rename after queued writes for this file are on disk.
    session->disk_io().barrier(
        [session = session, tor_id = id(), file]()
        {
            if (auto* const tor = session->torrents().get(tor_id); tor != nullptr)
            {
                tor->update_file_path(file, true);
            }
        });
```
(If `update_file_path` is private, a lambda created inside a member function can still call
it.)

### 2.7 Session flush/close wrappers (`session.cc`, around line 2151)

```cpp
void tr_session::flush_torrent_files(tr_torrent_id_t const tor_id) const noexcept
{
    this->cache->flush_torrent(tor_id); // enqueue only
}

void tr_session::close_torrent_files(tr_torrent_id_t const tor_id) noexcept
{
    this->cache->flush_torrent(tor_id);
    if (disk_io_)
    {
        disk_io_->close_torrent(tor_id);
        // ANDROID fork: TEMPORARY synchronous wait so remove/relocate never race queued
        // writes. Removed in TRANSMISSION_USB_SAF stage 4.
        auto const timeout = isClosing() ? std::chrono::milliseconds{ 20000 } : std::chrono::milliseconds{ 3600000 };
        if (!disk_io_->wait_idle(timeout))
        {
            tr_logAddWarn("Timed out waiting for queued disk writes");
        }
    }
    openFiles().close_torrent(tor_id);
}

void tr_session::close_torrent_file(tr_torrent const& tor, tr_file_index_t file_num) noexcept
{
    this->cache->flush_file(tor, file_num);
    if (disk_io_)
    {
        disk_io_->close_file(tor.id(), file_num);
    }
    openFiles().close_file(tor.id(), file_num);
}
```
Keep the stage 0 `tr_disk_timer`s in these functions.

`flush_torrent_files` is `const`, but `cache->flush_torrent` is non-const. That already compiles
today because `cache` is a `unique_ptr`. Leave it.

### 2.8 Shutdown order (`session.cc`, `closeImplPart1`)

- **Remove** the stage 1 `disk_io_->shutdown(); disk_io_.reset();` after `verifier_.reset();`.
- Insert **after** the torrent-freeing loop (`torrents.clear();`) and **before**
  `this->cache.reset();`:
  ```cpp
      // ANDROID fork: flush queued writes before the cache goes away.
      if (disk_io_)
      {
          disk_io_->shutdown_drain(std::min(deadline, std::chrono::steady_clock::now() + std::chrono::seconds{ 20 }));
          disk_io_.reset();
      }
  ```
  `closeImplPart1` receives `deadline` as a parameter. Torrents freed by the loop already
  enqueued their flushes through `stop_now` → `close_torrent_files`.

### 2.9 Build and verify

1. `tests/run.sh` still passes.
2. Build and install.
3. Scenario 1 (new multi-GB multi-file torrent to USB, preallocation 0) with
   `rpc_probe.sh` + `sample_threads.sh` running: the session thread shows no 68, and RPC p99
   drops versus the stage 0/1 baseline. Watch for the backlog Info lines.
4. Stop and start the torrent mid-download. Remove one with "delete data" (it may block for a
   while: that's the temporary wait, acceptable until stage 4). Check the files are gone and
   not recreated.
5. Kill the daemon mid-download (`adb shell run-as com.torrent.app kill -9 <pid>`), restart,
   then run a full verify: no piece reported "have" before the verify may turn out missing
   afterwards. (Blocks of incomplete pieces may be lost. That's fine.)
6. Append stage 2 rows to `## Measurements`.

---

## Known and accepted in this stage

- Stop/remove/relocate block the session thread until queued writes finish (temporary).
- Upload reads (`add_next_block` → `read_block` → `tr_ioRead`) are still synchronous on cache
  misses. Stage 3 handles them.
- One worker thread: while it is inside a long zero-fill `pwrite`, reads queued on High wait
  too. This hurts seeding latency, not RPC. Stage 5 measures preallocation.
