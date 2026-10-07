# Stage 1: disk I/O worker + async post-download piece verification

Part of `TRANSMISSION_USB_SAF.md`. Requires stage 0 (`TRANSMISSION_USB_SAF_0.md`, which also
has the **Conventions** section: apply_fork style, registering new files, re-applying patches,
build commands. Read it first).

**Goal**: add one dedicated disk worker thread, and move the hash check that runs when a
downloaded piece completes (`tr_torrent::on_block_received` → `check_piece` →
`tr_ioTestPiece`) onto it.

**Exit criterion**: during a download, `[DISKSLOW] thread=tr-session op=testpiece` never appears.
`thread=tr-diskio op=testpiece` lines may appear. Pieces still complete, fail and re-download
correctly.

Writes, upload reads and flushes stay on the session thread in this stage.

---

## Fixed design (don't redesign)

- **Generic queue** `tr_disk_queue` (header-only, std-only, unit-tested on the host): one
  worker thread, two FIFO queues, `High` and `Low`. The worker always takes from `High` first.
  Jobs are `std::function<void()>`. It supports `stop_and_discard()` (finish the current job,
  drop the rest, join) and `stop_and_drain(deadline)` (run queued jobs until empty or deadline,
  then drop the rest, join). Ordering guarantee: jobs **within one queue** run in enqueue order.
- **Disk I/O service** `tr_disk_io` (owns a `tr_disk_queue` and its **own** `tr_open_files`
  pool, touched only on the worker thread). Completion callbacks are posted to the session
  thread with `session.run_in_session_thread(...)`.
- **The worker never touches `tr_torrent` or `tr_session` state.** On the session thread, the
  caller converts a byte range into a list of self-contained `tr_disk_segment`s (file paths,
  offsets, sizes, all copied strings) **without doing any filesystem calls**. The worker
  resolves, opens and creates files itself. That also moves the stat and open SAF round trips
  off the session thread.
- **Cached-block overlay**: in this stage writes still go through the session-thread `Cache`,
  so a just-completed piece may be partly in memory only. The session thread copies those
  cached bytes into the job (`overlay`). The worker uses the overlay for those ranges and reads
  the rest from disk.
- Post-download checks go on the **Low** queue. (Stage 2 puts the piece's writes on Low ahead of
  the check, so ordering matters there.)
- A new per-torrent **pending-check set**. While a piece is pending: it isn't in the bitfield we
  send to peers, we don't send HAVE-ALL, upload requests for it are rejected, its blocks are
  left out of the resume file, and `recheck_completeness()` is deferred.
- Result codes are the same as the existing fork tri-state: `1` good, `0` hash mismatch, `-1`
  read error.
- **Read error (`-1`) after download**: un-have the piece so it re-downloads, **without**
  counting corrupt bytes or blaming peers. This also fixes an existing bug: today
  `if (check_piece(piece))` treats `-1` as success.
- Completion lookups always go through the torrent **id**
  (`session->torrents().get(tor_id)`), never a captured `tr_torrent*`. Ids are never reused
  within a session.

---

## Steps

### 1.1 `disk-queue.h` (new fork file, header-only)

```cpp
// ANDROID fork: TRANSMISSION_USB_SAF disk worker queue. std-only so it can be host-tested.
#pragma once
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>

class tr_disk_queue
{
public:
    enum class Prio { High, Low };
    using Job = std::function<void()>;

    explicit tr_disk_queue(std::function<void()> on_thread_start = {});
    ~tr_disk_queue(); // calls stop_and_discard() if still running

    void push(Prio prio, Job job);           // thread-safe; ignored after stop
    void stop_and_discard();                 // finish current job, drop queued, join
    void stop_and_drain(std::chrono::steady_clock::time_point deadline);
    [[nodiscard]] size_t size() const;       // queued (not running) jobs, both queues
private:
    void run();
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<Job> high_, low_;
    bool stopping_ = false;
    bool draining_ = false;
    std::chrono::steady_clock::time_point deadline_{};
    std::thread thread_; // started in the constructor, last member
};
```

Implement it inline in the header. Rules:
- `run()`: call `on_thread_start` once (used to name the thread). Then loop: wait until a queue
  is non-empty or stopping. If `stopping_ && !draining_`, exit. If draining and (both empty or
  `now >= deadline_`), exit. Otherwise pop from `high_` if non-empty, else from `low_`, unlock,
  run the job, relock.
- Jobs must not throw. Wrap the job call in `try { job(); } catch (...) {}` anyway.
- `stop_*` are idempotent and safe to call from any thread **except** the worker itself.

### 1.2 Host unit test for the queue

Create `app/src/main/cpp/transmission-fork/tests/disk_queue_test.cc` (plain `main()` with
`assert`s, no framework) and `tests/run.sh`, which runs
`g++ -std=c++17 -O1 -pthread -I.. disk_queue_test.cc -o /tmp/disk_queue_test && /tmp/disk_queue_test`.
Test:
1. FIFO order within Low (push 100 jobs appending ints, check order).
2. High before Low: block the worker with a first job that waits on a latch, push L1, L2, H1,
   release, and expect H1, L1, L2.
3. `stop_and_discard()` drops queued jobs: a blocked first job, 10 queued, release + discard,
   and at most the blocked job ran.
4. `stop_and_drain(far deadline)` runs all queued jobs. `stop_and_drain(past deadline)` runs
   none of the queued ones.

The test is not part of the Gradle build. Run it manually; it must pass before committing.

### 1.3 `disk-io.h` / `disk-io.cc` (new fork files, add to CMake + Gradle)

```cpp
// ANDROID fork: TRANSMISSION_USB_SAF disk I/O worker.
#pragma once
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "libtransmission/transmission.h"
#include "libtransmission/block-info.h"
#include "libtransmission/crypto-utils.h" // tr_sha1_digest_t
#include "libtransmission/disk-queue.h"
#include "libtransmission/open-files.h"

struct tr_torrent;
struct tr_session;

// Everything the worker needs to find/open/create one file. Built on the session thread.
struct tr_disk_file_ref
{
    tr_torrent_id_t tor_id = {};
    tr_file_index_t file_index = {};
    uint64_t file_size = {};
    std::string subpath;                    // tor.file_subpath(i)
    std::vector<std::string> search_dirs;   // non-empty download_dir(), then incomplete_dir()
    std::string create_path;                // current_dir()/subpath[+PartialFileSuffix]; used only for writes
    tr_open_files::Preallocation prealloc = tr_open_files::Preallocation::None; // writes only
};

// One contiguous byte range inside one file. A job's segments are in torrent byte order and
// together cover the job's byte range exactly.
struct tr_disk_segment
{
    tr_disk_file_ref file;
    uint64_t file_offset = {};
    uint64_t length = {};
};

// Bytes already in memory (from the Cache), relative to the start of the job's byte range.
struct tr_disk_overlay_chunk
{
    uint64_t offset = {};
    std::vector<uint8_t> data;
};

// Session thread only. No filesystem calls.
[[nodiscard]] std::vector<tr_disk_segment> tr_disk_make_segments(
    tr_torrent const& tor, tr_block_info::Location loc, uint64_t len, bool writable);

// Session thread only. Copies the parts of cached blocks that fall inside piece `piece`.
[[nodiscard]] std::vector<tr_disk_overlay_chunk> tr_disk_make_piece_overlay(tr_torrent const& tor, tr_piece_index_t piece);

class tr_disk_io
{
public:
    explicit tr_disk_io(tr_session& session);
    ~tr_disk_io(); // stop_and_discard

    // Result: 1 good, 0 mismatch, -1 read error. `done` runs on the session thread.
    void test_piece(
        tr_disk_queue::Prio prio,
        std::vector<tr_disk_segment> segments,
        std::vector<tr_disk_overlay_chunk> overlay,
        tr_sha1_digest_t expected,
        std::function<void(int result)> done);

    // Fire-and-forget: close the worker's fds (Low queue, so after earlier Low jobs).
    void close_torrent(tr_torrent_id_t tor_id);
    void close_file(tr_torrent_id_t tor_id, tr_file_index_t file);

    void shutdown(); // stop_and_discard; called from tr_session::closeImplPart1

private:
    tr_session& session_;
    tr_open_files files_; // worker thread only
    tr_disk_queue queue_; // last member: its thread starts in the constructor
};
```

Implementation notes for `disk-io.cc`:

- Constructor: `queue_{ [] { prctl(PR_SET_NAME, "tr-diskio", 0, 0, 0); } }` (include
  `<sys/prctl.h>`).
- **Posting completions**: capture `tr_session* session = &session_` and the user `done` in the
  job lambda. Never capture `this` in the lambda that runs on the session thread. Post with
  `session->run_in_session_thread([done = std::move(done), result]() { done(result); });`.
- **`tr_disk_make_segments`**: mirror `read_or_write_piece` in `inout.cc`: start from
  `auto [file_index, file_offset] = tor.file_offset(loc);` and loop while `len > 0`, taking
  `std::min(len, tor.file_size(file_index) - file_offset)` per file. **Skip zero-size files**
  (`inout.cc` returns early for them) but still advance `file_index`. Fill each
  `tr_disk_file_ref`:
  - `subpath = tor.file_subpath(i)`;
  - `search_dirs`: `tor.download_dir()` then `tor.incomplete_dir()`, each only if non-empty
    (the same as `buildSearchPathArray` in `torrent.cc`);
  - `create_path`: `tr_pathbuf{ tor.current_dir(), '/', subpath, suffix }`, where
    `suffix = session->isIncompleteFileNamingEnabled() ? tr_torrent_files::PartialFileSuffix : ""sv`
    (the same as `get_fd` in `inout.cc`);
  - `prealloc`: `writable && tor.file_is_wanted(i) ? session->preallocationMode() : None`.
  Add `TR_ASSERT(tor.session->am_in_session_thread());`.
- **Worker-side fd lookup** (static helper, worker thread only), the same logic as `get_fd`:
  1. `files_.get(tor_id, idx, writable)`: if it hits, use it.
  2. For each `dir` in `search_dirs`, try `dir/subpath`, then `dir/subpath + PartialFileSuffix`.
     The first one where `tr_sys_path_get_info` succeeds →
     `files_.get(tor_id, idx, writable, path, prealloc, file_size)`.
  3. If none was found and `writable`: `files_.get(..., create_path, prealloc, file_size)`.
  4. Otherwise fail with `ENOENT`.
- **Worker-side range read** `read_range(segments, rel_offset, len, uint8_t* out, tr_error&)`:
  walk the segments, skip the first `rel_offset` bytes, and read with a `tr_sys_file_read_at`
  loop (copy `read_entire_buf` from `inout.cc`. The fork step 10 EOF→EIO behavior comes from
  `tr_sys_file_read_at` itself). Wrap each read in
  `tr_disk_timer t{ "pread", seg.file.subpath, off, n };`.
- **`test_piece` job body** (worker): `tr_sha1` over the job's whole range. Keep a cursor
  `pos = 0` and an iterator into `overlay` (sorted by offset, non-overlapping). While
  `pos < total`: if the next overlay chunk starts at `pos`, `sha.add` the chunk and advance;
  otherwise read from disk `[pos, min(total, next_chunk_offset, pos + 256 KiB))` into a reused
  256 KiB buffer and hash it. Any read error → result `-1`. At the end, compare
  `sha.finish()` with `expected` → `1` or `0`. Wrap the whole job in
  `tr_disk_timer t{ "testpiece", segments.front().file.subpath, 0, total };`.
- **`tr_disk_make_piece_overlay`**: for each block in `tor.block_span_for_piece(piece)`, ask
  the cache for a copy (new `Cache` method, below). Intersect the block's byte range
  `[block_loc.byte, +block_size)` with the piece's range
  `tor.block_info().byte_span_for_piece(piece)` and store the intersection with
  `offset = intersection_begin - piece_begin`.
- `close_torrent` / `close_file`: push a Low job calling `files_.close_torrent(id)` /
  `files_.close_file(id, file)`.

### 1.4 `Cache` helper (patch `cache.h` / `cache.cc`)

Add a public method that never touches the disk:

```cpp
// ANDROID fork: copy a cached block without falling back to disk. Empty if not cached.
[[nodiscard]] std::optional<std::vector<uint8_t>> copy_cached_block(tr_torrent_id_t tor_id, tr_block_index_t block) const;
```

Implement it with `std::equal_range` on `blocks_` using `Key{ tor_id, block }` and
`CompareCacheBlockByKey` (as `get_block` does). Add `#include <optional>`.

### 1.5 Session wiring (patch `session.h` / `session.cc`)

- `session.h`: `#include "libtransmission/disk-io.h"`. Add the member
  `std::unique_ptr<tr_disk_io> disk_io_;` **directly after** the
  `std::unique_ptr<Cache> cache = ...;` line, and a public accessor next to `openFiles()`:
  `[[nodiscard]] tr_disk_io& disk_io() noexcept { return *disk_io_; }`.
- Create it in `tr_session::initImpl` (on the session thread), before torrents are loaded:
  `disk_io_ = std::make_unique<tr_disk_io>(*this);`. Find the start of `initImpl` in
  `session.cc` and insert it there.
- `closeImplPart1`: right after `verifier_.reset();` add
  `disk_io_->shutdown(); disk_io_.reset();`. Queued checks are dropped. That is safe because
  their pieces were never "have" in the resume file (see 1.7).
- `tr_session::close_torrent_files(tor_id)`: append `if (disk_io_) disk_io_->close_torrent(tor_id);`.
  `tr_session::close_torrent_file(tor, file)`: append `if (disk_io_) disk_io_->close_file(tor.id(), file_num);`.
  (After `disk_io_.reset()` during shutdown, torrents are still freed, hence the null check.)

### 1.6 Torrent changes (patch `torrent.h` / `torrent.cc`)

`torrent.h`, inside `struct tr_torrent`:
- private member: `std::set<tr_piece_index_t> pending_checks_; // ANDROID fork` (add
  `#include <set>`);
- public:
  ```cpp
  [[nodiscard]] bool is_piece_check_pending(tr_piece_index_t piece) const { return pending_checks_.count(piece) != 0U; }
  [[nodiscard]] bool has_pending_checks() const noexcept { return !std::empty(pending_checks_); }
  ```
- private: `void enqueue_piece_check(tr_piece_index_t piece);`,
  `void on_piece_check_done(tr_piece_index_t piece, int result);`,
  `void on_piece_read_error(tr_piece_index_t piece);`
- `create_piece_bitfield()` (inline in `torrent.h`, about line 368): after getting
  `auto bits = completion_.create_piece_bitfield();`, clear the bit for every pending piece:
  `bits[p / 8] &= ~(0x80 >> (p % 8));`. Return `bits`.

`torrent.cc`:
- `on_block_received`: inside the `for (auto piece = first_piece; ...)` loop, replace the
  `if (check_piece(piece)) { on_piece_completed(piece); } else { on_piece_failed(piece); }` block
  with:
  ```cpp
        // ANDROID fork: verify on the disk worker (TRANSMISSION_USB_SAF stage 1).
        if (!is_piece_check_pending(piece))
        {
            enqueue_piece_check(piece);
        }
  ```
- `enqueue_piece_check`:
  ```cpp
  void tr_torrent::enqueue_piece_check(tr_piece_index_t const piece)
  {
      pending_checks_.insert(piece);
      session->disk_io().test_piece(
          tr_disk_queue::Prio::Low,
          tr_disk_make_segments(*this, piece_loc(piece), piece_size(piece), false),
          tr_disk_make_piece_overlay(*this, piece),
          piece_hash(piece),
          [session = session, tor_id = id(), piece](int result)
          {
              if (auto* const tor = session->torrents().get(tor_id); tor != nullptr)
              {
                  tor->on_piece_check_done(piece, result);
              }
          });
  }
  ```
- `on_piece_check_done`: if `pending_checks_.erase(piece) == 0`, return (stale). Then `1` →
  `on_piece_completed(piece)`, `0` → `on_piece_failed(piece)`, `-1` →
  `on_piece_read_error(piece)`. Finally `set_dirty(); set_needs_completeness_check();`.
- `on_piece_read_error`: `tr_logAddWarnTor(this, fmt::format("Piece {} could not be read back for verification; re-downloading", piece));`
  then `bytes_downloaded_.reduce(piece_size(piece)); set_has_piece(piece, false);`. **Don't**
  touch `bytes_corrupt_` and **don't** emit `got_bad_piece_`.
- `recheck_completeness()`: right after `auto const lock = unique_lock();` insert
  `if (has_pending_checks()) { return; } // ANDROID fork: re-run when checks finish`.
  (`needs_completeness_check_` stays true, and `on_piece_check_done` sets it again.)
- `tr_torrentVerify`: in the session-thread lambda, after `session->verify_remove(tor);`, add
  `tor->pending_checks_.clear();` (a full verify recomputes every piece, so late results get
  ignored). If `pending_checks_` is private and the lambda isn't a member, add a public
  `void clear_pending_checks() noexcept` and call that.

### 1.7 Resume excludes pending pieces

`tr_torrent::ResumeHelper::blocks()` (`torrent.h` around line 83, `torrent.cc` around line
2632) returns `tr_bitfield const&`. Change it to return `tr_bitfield` **by value**: copy
`tor_.completion_.blocks()`, then for each pending piece call
`copy.unset_span(begin, end)` with `auto [begin, end] = tor_.block_span_for_piece(p);`. Run
`grep -n "blocks()" resume.cc torrent.cc` to confirm the only caller is `save_progress` in
`resume.cc` (it takes the result by value-compatible const&, so no other change).

### 1.8 Peer-facing masking (patch `peer-msgs.cc`)

- `protocol_send_bitfield()`: change `if (fext && tor_.has_all())` to
  `if (fext && tor_.has_all() && !tor_.has_pending_checks())`. The `else if` bitfield branch
  then sends the masked bitfield.
- `add_next_block`: change
  `auto ok = is_valid_request(req) && tor_.has_piece(req.index);` to
  `auto ok = is_valid_request(req) && tor_.has_piece(req.index) && !tor_.is_piece_check_pending(req.index);`.
  The existing code then rejects (fext) or drops. (Use the anchor text exactly as patched by
  step 9j.)

### 1.9 Build, test, measure

1. `tests/run.sh` passes.
2. `./gradlew :app:buildTransmission`, then `./gradlew installDebug`.
3. On the device, download a torrent to USB. Check:
   - the log has no `thread=tr-session op=testpiece`;
   - the torrent reaches 100%, and a full verify afterwards (RPC `torrent-verify`) reports 100%;
   - RPC `torrent-get` with `pieceCount`/`haveValid` progresses normally.
4. Append stage 1 rows to `## Measurements` in `TRANSMISSION_USB_SAF.md` (scenarios 1 and 2).

---

## Known and accepted in this stage

- RPC `haveValid`/`percentDone` briefly count a piece that is pending a check. That's cosmetic.
- `is_new_torrent_a_seed()` still calls `ensure_piece_is_checked(0)` synchronously (one piece,
  only when a torrent is added). Leave it.
- Lazy verify of unchecked pieces for upload (`ensure_piece_is_checked` in `add_next_block`)
  is still synchronous. Stage 3 handles it.
- Worker opens/stats go through `file-saf.cc`'s global mutex and can wait behind session-thread
  SAF calls. Stage 4 fixes that.
