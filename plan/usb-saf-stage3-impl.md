# Stage 3 implementation plan (async upload reads + async lazy verify)

Spec: `TRANSMISSION_USB_SAF_3.md` (authoritative; don't redesign). Conventions:
`TRANSMISSION_USB_SAF_0.md` § Conventions. Last existing `apply_fork.py` step is **23**.

Patched source to read (anchors must match it exactly):
`app/build/native/src/transmission-4.1.2/libtransmission/`. Never edit that tree by hand.
Fork files (`disk-io.h`, `disk-io.cc`) in `app/src/main/cpp/transmission-fork/` are edited directly.

After changing `apply_fork.py`: `rm -rf app/build/native/src/transmission-4.1.2` (keep the
`.tar.xz`) and rebuild. Build env:
```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME="$HOME/Android/Sdk" ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
./gradlew :app:buildTransmission     # compile check
./gradlew installDebug               # device (wireless adb already connected)
```

## Stage A — disk-io read, cache fast path, torrent lazy check

A1. `disk-io.h` / `disk-io.cc` (fork files, edit directly):
- `struct tr_disk_read_result { int err = 0; std::vector<uint8_t> data; };`
- `void read(std::vector<tr_disk_segment> segments, std::function<void(tr_disk_read_result)> done);`
  pushes on `Prio::High`. Worker: total = sum of seg lengths, `data.resize(total)`,
  `tr_disk_timer{"pread", first subpath, 0, total}`, `read_range(files_, segments, 0, total, data.data(), error)`.
  On failure: `err = error.code() != 0 ? error.code() : EIO`, `data.clear()`.
- `[[nodiscard]] bool can_start_read() const noexcept { return *reads_in_flight_ < 64U; }`
- Counter bookkeeping inside `read()`: increment on call (session thread); the session-thread
  completion wrapper decrements **before** calling `done`. The session part must not capture
  `this` (existing rule) — so hold the counter as
  `std::shared_ptr<size_t> reads_in_flight_ = std::make_shared<size_t>(0);` (private, session
  thread only) and capture the shared_ptr in the lambdas. Declare it before `queue_` (queue_ must
  stay the last member).

A2. New apply_fork step 24 — `cache.h` / `cache.cc`: add
`[[nodiscard]] bool read_block_if_cached(tr_torrent const& tor, tr_block_info::Location const& loc, size_t len, uint8_t* setme);`
(comment `// ANDROID fork: stage 3 read_block_if_cached ...`) next to `read_block`. Body: find via
`get_block(tor, loc)`; return true + `std::copy_n(std::begin(*iter->buf) + loc.block_offset, len, setme)`
only if found and `loc.block_offset + len <= std::size(*iter->buf)`; else false. `read_block` unchanged.

A3. New apply_fork step 25 — `torrent.h` / `torrent.cc` (marker `ANDROID fork: stage 3 lazy check`):
- private `std::set<tr_piece_index_t> lazy_checks_;` (next to `pending_checks_`),
  public `void request_lazy_check(tr_piece_index_t piece);`,
  private `void on_lazy_check_done(tr_piece_index_t piece, int result);`
- Bodies exactly as spec §3.3 (High prio, `tr_disk_make_segments(*this, piece_loc(piece), piece_size(piece), false)`,
  `tr_disk_make_piece_overlay(*this, piece)`, `piece_hash(piece)`, lambda captures session, tor id, piece
  and re-finds the torrent via `session->torrents().get(tor_id)`).
- `on_lazy_check_done`: erase==0 → return; `mark_changed(); set_dirty();`; result -1 → return;
  `checked_pieces_.set(piece, result == 1)`; result 0 → `error().set_local_error(fmt::format("Please Verify Local Data! Piece #{:d} is corrupt.", piece));`
- In `tr_torrentVerify` session-thread lambda, after `tor->clear_pending_checks();` also clear
  `lazy_checks_` (add a public `clear_lazy_checks()` or extend `clear_pending_checks()`; prefer a
  new line calling a new `clear_lazy_checks()` helper).

Exit A: `./gradlew :app:buildTransmission` succeeds after rm -rf of the extracted tree. Nothing
calls the new APIs yet (that's fine).

## Stage B — peer upload path (`peer-msgs.cc`), new apply_fork step 26

Marker `ANDROID fork: TRANSMISSION_USB_SAF stage 3 async reads`.
- Members next to `peer_requested_`: `std::vector<peer_request> reads_pending_;`
  `static constexpr size_t MaxReadsPerPeer = 4U;` and declare
  `void on_block_read(peer_request const& req, tr_disk_read_result r);`.
- Replace the whole patched `add_next_block` body with spec §3.4 pseudo-code (keep the early
  return; remove the synchronous `ensure_piece_is_checked` + `cache->read_block` path).
  Unchecked piece → `tor_.request_lazy_check(req.index)` and reject (fext) / drop.
  Cache hit → `blocks_sent_to_peer.add(now_sec, 1)` + send Piece. Caps hit → `push_front`, return 0.
  Otherwise push to `reads_pending_`, `session->disk_io().read(tr_disk_make_segments(tor_, loc, req.length, false), [weak = weak_from_this(), req](tr_disk_read_result r){ if (auto self = weak.lock()) self->on_block_read(req, std::move(r)); });` return 0.
- `on_block_read`: find/erase in `reads_pending_` (absent → return). Error or size mismatch →
  reject if fext, return (no torrent error). Else `blocks_sent_to_peer.add(tr_time(), 1)`, send
  Piece with `std::string_view{reinterpret_cast<char const*>(r.data.data()), req.length}`.
  Then call `fill_output_buffer(tr_time(), tr_time_msec())` so the next queued requests get
  served without waiting for the next pulse (otherwise per-peer throughput is capped at
  4 blocks per pulse). Do this on both success and error paths after the erase.
- `reject_all_requests()`: also send reject (if fext) for each `reads_pending_` entry, then clear.
- Cancel handler: if not found in `peer_requested_`, look in `reads_pending_`; if found erase +
  reject if fext.
- `peer_requested_metadata_pieces_` is a different container — don't touch it.
- `disk-io.h` arrives via `session.h`; add an explicit include only if needed.

Exit B: clean rebuild (`rm -rf` extracted tree) + `./gradlew installDebug` succeeds; daemon starts
on the phone and RPC answers (user `torrent`, pass `testtest`, `tools/usb-io/rpc_probe.sh`).

## Stage C — device verification (spec §3.5)
Scenario 2 with `tools/usb-io/sample_threads.sh` and `rpc_probe.sh torrent testtest`; check no
syscall 67 on tr-session, no `thread=tr-session op=pread|testpiece` DISKSLOW lines (except
is_new_torrent_a_seed), uploads rise, no "corrupt" errors; restart daemon while seeding to exercise
lazy checks. Append stage 3 rows to `## Measurements` in `TRANSMISSION_USB_SAF.md`. Commit
`feat(transmission): stage 3 async upload reads + async lazy verify`.
