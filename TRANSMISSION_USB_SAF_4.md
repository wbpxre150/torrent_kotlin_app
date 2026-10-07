# Stage 4: per-thread SAF sockets + two-phase stop/remove/relocate

Part of `TRANSMISSION_USB_SAF.md`. Requires stages 0–3. Read the **Conventions** in
`TRANSMISSION_USB_SAF_0.md`, and `_1.md`–`_3.md` for `tr_disk_io` (`barrier`, `wait_idle`,
`close_torrent`) and the in-flight cache.

**Goal**: (a) worker and session-thread SAF calls no longer serialize on one socket and mutex;
(b) stopping, removing (with data) and relocating a torrent never block the session thread. This
stage deletes stage 2's temporary `wait_idle` call.

**Exit criterion**: scenario 3 (stop, remove with data, relocate a torrent mid-download while
USB is busy) keeps RPC p99 < 500 ms, files are deleted or moved correctly and never recreated,
and `grep -n "wait_idle" ` in the patched tree finds no caller.

---

## Fixed design (don't redesign)

- **SAF**: one Unix-socket connection **per native thread** (`thread_local`), closed
  automatically when the thread exits. Remove `g_mutex`. `SafIpcServer` already runs one
  coroutine per accepted connection (`handleConnection` loops over requests until EOF, on
  `Dispatchers.IO`), so the protocol needs no change. Two connections can now call
  `handler.handle(...)` concurrently. Read the handler class that `SafIpcServer` is constructed
  with (see `DaemonService.kt`) and anything it calls. If it has shared mutable state that isn't
  thread-safe (plain `HashMap`s, caches, lazy fields), guard that state with `synchronized` on a
  private lock object. Don't serialize whole requests.
- **Stop** doesn't need to wait at all: it enqueues the flush plus the worker close, and the
  queued writes finish later. No new writes for the torrent are created once it is stopped
  (peers are disconnected by the `stopped_` signal).
- **Remove with data**: phase 1 (immediate) stops the torrent and enqueues its flush and close.
  Then the deletion itself runs as a **Low** worker job (FIFO, so after every queued write for
  it), and phase 2 (session thread, in the job's completion) frees the torrent. Between the
  phases the torrent stays in the list, flagged so it can't be started or removed again.
- **Relocate with move**: phase 1 stops the torrent if it is running (remembering that), and
  enqueues the flush, the close and a **Low** `move` job. Phase 2 (completion) applies the new
  location and restarts the torrent if it was running. Stopping prevents new writes from being
  queued with the old paths.
- Remove/relocate **without** moving data, and session shutdown, stay synchronous apart from
  the flush. Shutdown already drains the worker (stage 2).
- The remaining session-thread SAF calls (startup `load_checked_pieces` stats, `find_file` in
  RPC/verify-mediator paths, `.part` renames, `set_local_error_if_files_disappeared`) stay where
  they are. They are rare and now don't queue behind the worker's socket. Note any that show up
  in `[DISKSLOW] thread=tr-session` during scenario 3 in the measurements, but don't move them.

---

## Steps

### 4.1 `file-saf.cc` (fork file, edit directly)

- Replace `int g_sock = -1; std::mutex g_mutex;` with:
  ```cpp
  // ANDROID fork: one connection per native thread (TRANSMISSION_USB_SAF stage 4).
  struct SafSock
  {
      int fd = -1;
      ~SafSock()
      {
          if (fd >= 0)
          {
              ::close(fd);
          }
      }
  };
  thread_local SafSock t_sock;
  ```
- Replace every use of `g_sock` with `t_sock.fd` (`ensure_sock`, `drop_sock`, `recv_response`,
  `recv_response_with_fd`, and so on).
- Delete every `std::lock_guard<std::mutex> lk(g_mutex);`. Keep the stage 0 `tr_disk_timer`s.
- Remove `#include <mutex>` if nothing else needs it.
- Thread lifetimes: the session thread and `tr-diskio` live for the whole session. The verify
  thread is recreated per verify batch, and its socket is closed by `~SafSock` when it exits.

### 4.2 Worker jobs for remove and move (`disk-io.h` / `disk-io.cc`)

```cpp
// Low queue. Runs `fn` on the worker thread (it must not touch torrent/session state), then
// `done(err, message)` on the session thread.
void run_low(std::function<std::pair<int, std::string>()> fn, std::function<void(int, std::string)> done);
```

That one generic method is enough. The callers build the closures.

### 4.3 Stop no longer waits (`session.cc`)

In `tr_session::close_torrent_files`, delete the stage 2 `wait_idle` block. What remains:
`cache->flush_torrent(tor_id); if (disk_io_) disk_io_->close_torrent(tor_id); openFiles().close_torrent(tor_id);`
Then delete `tr_disk_io::wait_idle` from `disk-io.h/.cc`.

### 4.4 Two-phase remove (`torrent.cc`, `torrent.h`)

Add a private `bool remove_pending_ = false; // ANDROID fork` to `tr_torrent`, with a public
getter `is_remove_pending()`.

Rewrite `tr_torrentRemoveInSessionThread(tor, delete_flag, delete_func, delete_user_data)`:

```text
lock
if tor->remove_pending_: return                  // second remove request
if !(delete_flag && tor->has_metainfo()):
    tr_torrentFreeInSessionThread(tor); return   // unchanged path
tor->remove_pending_ = true
tor->session->verify_remove(tor)
if tor->is_running(): tor->stop_now()            // enqueues flush + worker close (4.3)
else: tor->session->close_torrent_files(tor->id())
if delete_func == nullptr: delete_func = start_stop_helpers::removeTorrentFile   // as today
// copies for the worker:
files = tor->files()            // tr_torrent_files, copyable value (check: it holds a vector)
dir = std::string{ tor->current_dir() }; name = std::string{ tor->name() }
session = tor->session; tor_id = tor->id()
session->disk_io().run_low(
    [files, dir, name, delete_func, delete_user_data]() -> std::pair<int, std::string> {
        auto error = tr_error{};
        files.remove(dir, name, [&](char const* f){ delete_func(f, delete_user_data, nullptr); }, &error);
        return { error.code(), std::string{ error.message() } };
    },
    [session, tor_id, name](int err, std::string msg) {
        if (err != 0) tr_logAddWarn(fmt::format("Couldn't remove all torrent files for '{}': {} ({})", name, msg, err));
        if (auto* tor = session->torrents().get(tor_id); tor != nullptr) tr_torrentFreeInSessionThread(tor);
    });
```

Notes:
- The daemon's RPC calls `tr_torrentRemove(tor, delete_flag, nullptr, nullptr)`
  (`rpcimpl.cc:413`), so `delete_func` is always the default `removeTorrentFile`, which only
  calls `tr_sys_path_remove`. That is safe off the session thread.
- `tr_torrentFreeInSessionThread` calls `stop_now()` again. `stop_now` must cope with an
  already-stopped torrent (it does today: it just re-flushes and re-saves). Since
  `is_deleting_` is true, the resume file isn't saved.
- If the session closes before the job finishes, `closeImplPart1` frees the torrent itself, and
  the completion finds no torrent and does nothing. The shutdown drain (stage 2) may still run
  the remove job. That's fine.

Guards for the window between the phases:
- `tr_torrentStart` / `start_in_session_thread` (`torrent.cc` around lines 660–667): return
  early if `is_remove_pending()`.
- `tr_torrentVerify` lambda: it already returns if `tor->is_deleting_`. `tr_torrentRemove` sets
  `is_deleting_` before queueing, so nothing more is needed there.
- `tr_torrentSetLocation` / `set_location`: return early if `is_remove_pending()`.

### 4.5 Two-phase relocate (`torrent.cc`)

In `tr_torrent::set_location_in_session_thread(path, move_from_old_path, setme_state)`, the
`move_from_old_path == true` branch currently calls `close_torrent_files`, `verify_remove`, and
the synchronous `files().move(current_dir(), path, name(), &error)`, then handles the result.
Read the whole function in the patched tree first. Split it:

```text
if move_from_old_path:
    if setme_state: *setme_state = TR_LOC_MOVING
    was_running = is_running()
    if was_running: stop_now()          // enqueues flush + close
    else: session->close_torrent_files(id())
    session->verify_remove(this)
    files = files(); old_dir = std::string{ current_dir() }; new_dir = std::string{ path }; nm = std::string{ name() }
    session->disk_io().run_low(
        [files, old_dir, new_dir, nm]() -> std::pair<int, std::string> {
            auto error = tr_error{};
            auto const ok = files.move(old_dir, new_dir, nm, &error);
            return { ok ? 0 : (error.code() != 0 ? error.code() : EIO), std::string{ error.message() } };
        },
        [session = session, tor_id = id(), new_dir, was_running, setme_state](int err, std::string msg) {
            auto* tor = session->torrents().get(tor_id);
            if (tor == nullptr) return;
            tor->finish_set_location(new_dir, err, msg, setme_state);   // the rest of the old function
            if (was_running && err == 0) tr_torrentStart(tor);
        });
    return
else:
    (unchanged non-move path)
```

Create a private `void finish_set_location(std::string_view path, int err, std::string const& msg, int volatile* setme_state);`
containing **everything the old function did after `files().move(...)`**, unchanged: error
message formatting (use `msg`/`err` in place of `error.message()`/`error.code()`),
`set_download_dir` / `set_incomplete_dir` logic, `*setme_state = ok ? TR_LOC_DONE : TR_LOC_ERROR`.
Keep the old function's non-move branch calling the same tail with `err = 0`, so behavior there
is identical.

On error, if the torrent was running, restart it anyway? **No**: leave it stopped with the local
error set, so the user sees it.

### 4.6 Build and verify

1. Build and install.
2. Scenario 3 under load (one torrent downloading fast to USB, another seeding), with
   `rpc_probe.sh` running:
   - stop/start the downloading torrent repeatedly;
   - remove a torrent with "delete local data" mid-download: RPC stays responsive, the folder
     disappears after the queue drains and doesn't come back, and the torrent disappears from
     the list when the job completes;
   - relocate (move) a torrent mid-download: it stops, moves, resumes at the new location, and
     no files reappear at the old location.
3. Re-run scenarios 1 and 2 to check nothing regressed.
4. Run `sample_threads.sh` during all of the above. The session thread should not be in state
   `D` for more than one sample in a row.
5. Append stage 4 rows to `## Measurements`.

---

## Known and accepted in this stage

- A removed torrent stays visible in RPC until its queued writes and the delete finish.
- Session shutdown still waits up to 20 s for queued writes.
