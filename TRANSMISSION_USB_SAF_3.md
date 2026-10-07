# Stage 3: async upload reads + async lazy verify

Part of `TRANSMISSION_USB_SAF.md`. Requires stages 0–2. Read the **Conventions** in
`TRANSMISSION_USB_SAF_0.md`, and `TRANSMISSION_USB_SAF_1.md` / `_2.md` for `tr_disk_io`, the
segment/overlay helpers and the in-flight cache.

**Goal**: serving a peer's block request never reads the disk on the session thread. Cache hits
are still served immediately. Misses become `Read` jobs on the worker's **High** queue, and the
`Piece` message is sent from the completion. Lazy verification of unchecked pieces
(`ensure_piece_is_checked` in the upload path) becomes a `test_piece` job too.

**Exit criterion**: while seeding to many peers (scenario 2), `sample_threads.sh` never shows
syscall 67 on `tr-session`, and the log has no `thread=tr-session op=pread` or
`op=testpiece` (except the one-time `is_new_torrent_a_seed` check when a torrent is added).
Peers still receive correct data (upload counters rise, no "corrupt" errors).

---

## Fixed design (don't redesign)

- **Fast path**: if every byte of the request is in the cache, send it synchronously, as today.
- **Slow path**: enqueue `tr_disk_io::read` on **High**. The completion re-finds the peer
  through `weak_from_this()` (`tr_peerMsgsImpl` already derives from
  `std::enable_shared_from_this`). If the peer is gone, or the request was cancelled or cleared
  meanwhile, drop the data.
- **Caps**: at most **4** outstanding disk reads per peer and **64** globally. When a cap is
  hit, put the request back at the **front** of `peer_requested_` and stop filling. The next
  `pulse()` / `fill_output_buffer` retries.
- **Unchecked pieces** (`!tor_.is_piece_checked(index)`): don't read. Start one lazy
  `test_piece` per piece (High queue, with the cache overlay) and **reject** the request
  (fext reject, else silently drop, the same as today's failure branch). The peer will
  re-request. The lazy result applies exactly what today's `ensure_piece_is_checked` does.
- Upload-side counters (`blocks_sent_to_peer`) update only when the `Piece` message is actually
  sent.
- `is_new_torrent_a_seed()` keeps its synchronous `ensure_piece_is_checked(0)`. That's one
  piece, once, when a torrent is added. Leave it and the synchronous `check_piece` /
  `tr_ioTestPiece` / `tr_ioRead` code for it.

---

## Steps

### 3.1 `tr_disk_io::read` (`disk-io.h` / `disk-io.cc`)

```cpp
struct tr_disk_read_result { int err = 0; std::vector<uint8_t> data; };

// High queue. Reads exactly the bytes covered by `segments`. `done` runs on the session thread.
void read(std::vector<tr_disk_segment> segments, std::function<void(tr_disk_read_result)> done);

// Global read cap bookkeeping, session thread only.
[[nodiscard]] bool can_start_read() const noexcept { return reads_in_flight_ < 64U; }
size_t reads_in_flight_ = 0; // incremented by callers on enqueue, decremented by read()'s wrapper
```

Implementation: the job allocates `data` with the total length and uses stage 1's
`read_range(segments, 0, total, data.data(), error)`. Any error → `err = error.code()` (use
`EIO` if the code is 0) and empty `data`. Make `reads_in_flight_` private and do the
bookkeeping **inside** `read()`: increment when called, and in the session-thread completion
wrapper decrement **before** calling the user's `done`, so the count is right even when the
peer is gone. Expose only `can_start_read()`.

### 3.2 `Cache::read_block_if_cached` (`cache.h` / `cache.cc`)

```cpp
// ANDROID fork: true and fills `setme` only if the block is in the cache; never reads disk.
[[nodiscard]] bool read_block_if_cached(tr_torrent const& tor, tr_block_info::Location const& loc, size_t len, uint8_t* setme);
```
Find the entry the same way `get_block` does. Return `true` only if the block is cached **and**
`loc.block_offset + len <= std::size(*iter->buf)`. Then copy
`std::copy_n(std::begin(*iter->buf) + loc.block_offset, len, setme)`. Otherwise return `false`
and the slow path handles it. (A request with an unaligned offset can span two cache blocks.
That case just takes the slow path.) Leave the existing `read_block` unchanged.

### 3.3 Lazy check on the torrent (`torrent.h` / `torrent.cc`)

Add:
- private member `std::set<tr_piece_index_t> lazy_checks_;`
- public `void request_lazy_check(tr_piece_index_t piece);`
- private `void on_lazy_check_done(tr_piece_index_t piece, int result);`

```cpp
void tr_torrent::request_lazy_check(tr_piece_index_t const piece)
{
    if (is_piece_checked(piece) || !lazy_checks_.insert(piece).second)
    {
        return;
    }
    session->disk_io().test_piece(
        tr_disk_queue::Prio::High,
        tr_disk_make_segments(*this, piece_loc(piece), piece_size(piece), false),
        tr_disk_make_piece_overlay(*this, piece),
        piece_hash(piece),
        [session = session, tor_id = id(), piece](int result)
        {
            if (auto* const tor = session->torrents().get(tor_id); tor != nullptr)
            {
                tor->on_lazy_check_done(piece, result);
            }
        });
}
```

`on_lazy_check_done`: if `lazy_checks_.erase(piece) == 0`, return. Then do what the patched
`ensure_piece_is_checked` does after its `check_piece` call: `mark_changed(); set_dirty();`.
If `result == -1`, return (leave it unchecked, it is retried on the next request).
Otherwise `checked_pieces_.set(piece, result == 1)`, and if `result == 0`:
`error().set_local_error(fmt::format("Please Verify Local Data! Piece #{:d} is corrupt.", piece));`
(the message currently in `peer-msgs.cc`).

In `tr_torrentVerify`'s session-thread lambda, next to the stage 1 `pending_checks_` clear, also
clear `lazy_checks_`.

### 3.4 Peer upload path (`peer-msgs.cc`)

New members in `tr_peerMsgsImpl` (next to `peer_requested_`):
```cpp
    // ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads.
    std::vector<peer_request> reads_pending_;
    static constexpr size_t MaxReadsPerPeer = 4U;
```

Rewrite `add_next_block` (replace the whole patched body. Keep the early return for an empty
queue / no write-buffer space):

```text
req = peer_requested_.front(); peer_requested_.pop_front();
ok = is_valid_request(req) && tor_.has_piece(req.index) && !tor_.is_piece_check_pending(req.index)
if ok && !tor_.is_piece_checked(req.index):
    tor_.request_lazy_check(req.index); ok = false        // reject below
if ok:
    loc = tor_.piece_loc(req.index, req.offset)
    if session->cache->read_block_if_cached(tor_, loc, req.length, buf):
        blocks_sent_to_peer.add(now_sec, 1)
        return protocol_send_message(BtPeerMsgs::Piece, req.index, req.offset, piece_data)  // as today
    if size(reads_pending_) >= MaxReadsPerPeer || !session->disk_io().can_start_read():
        peer_requested_.push_front(req); return 0          // retry later; 0 stops the fill loop
    reads_pending_.push_back(req)
    session->disk_io().read(tr_disk_make_segments(tor_, loc, req.length, false),
        [weak = weak_from_this(), req](tr_disk_read_result r) {
            if (auto self = weak.lock()) self->on_block_read(req, std::move(r));
        })
    return 0
if io_->supports_fext(): return protocol_send_reject(req)
return 0
```

Important: returning `0` with requests still queued makes `fill_output_buffer_impl`'s loop stop
(it loops while bytes were added). That is the intended "try again later" behavior.

New member `void on_block_read(peer_request const& req, tr_disk_read_result r)`:
- find `req` in `reads_pending_`. If it's absent, return (cancelled or cleared). Erase it.
- if `r.err != 0 || std::size(r.data) != req.length`: if `io_->supports_fext()`,
  `protocol_send_reject(req)`. Return. (Don't set a torrent error: read errors are transient.
  This matches the tri-state fix.)
- otherwise `blocks_sent_to_peer.add(tr_time(), 1);` and
  `protocol_send_message(BtPeerMsgs::Piece, req.index, req.offset, std::string_view{ reinterpret_cast<char const*>(r.data.data()), req.length });`

Keep `reads_pending_` in sync with every place that drops queued requests:
- `reject_all_requests()`: for each request in `reads_pending_`, send a reject if fext, then
  `reads_pending_.clear()` (in addition to the existing `peer_requested_` handling).
- the `BtPeerMsgs::Cancel` handler: if the cancelled request isn't found in `peer_requested_`,
  look in `reads_pending_`. If found, erase it and send a reject if fext (BEP 6: reply with a
  reject or the piece).
- Run `grep -n "peer_requested_" peer-msgs.cc` and check every other place that clears or
  erases (for example on choke). Mirror it for `reads_pending_`.

`peer_request` already has `operator==` (the Cancel handler uses `std::find` on it).

### 3.5 Build and verify

1. Build and install.
2. Scenario 2 (seed a complete torrent from USB to several peers while another torrent
   downloads) with both scripts running: no syscall 67 on `tr-session`, and RPC p99 is better
   than stage 2.
3. Restart the daemon with a seeding torrent so pieces are unchecked (or edit nothing: pieces
   whose file mtime changed are unchecked by `load_checked_pieces`). Confirm uploads resume
   after the first lazy checks, with no "corrupt" errors.
4. Upload correctness: let another client download a torrent fully from this device only, and
   verify it there.
5. Append stage 3 rows to `## Measurements`.

---

## Known and accepted in this stage

- Requests for unchecked pieces are rejected once while the lazy check runs.
- Stop/remove/relocate still use stage 2's temporary blocking wait (stage 4).
