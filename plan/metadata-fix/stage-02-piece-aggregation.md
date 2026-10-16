# Stage 2: Piece aggregation — one shared metadata buffer, window peers fetch different pieces

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repos: `~/apps/torrent_kotlin_app`** (gitlink only) **and the vendored crawler repo at
> `app/src/main/cpp/dht_crawler/`** (its own `.git`, HEAD = whatever Stage 1 left).
> Requires the test phone on USB. Index: `plan/metadata-fix/README.md`.

## Goal

Convert the ≤ 4-conn window from a **whole-metadata race** into a **piece-level aggregate fetch** —
the libtorrent/Transmission/aria2 shape (see `README.md` research table):

- **One shared** `metadata_buffer` + `pieces_bitmap` + `pieces_received` per fetch (per popped
  peers-queue entry), written by all window conns. No more 4× redundant full downloads.
- Conns request **missing pieces only**, **≤ 2 outstanding requests per conn**
  (`METADATA_OUTSTANDING_PER_PEER`, libtorrent parity), **never all at once** — kill the blast.
- Requests **spread across conns** (round-robin over missing pieces; Transmission rotates, too).
- On full assembly: SHA-1 vs infohash → success; on mismatch: **reset the bitfield once** and
  re-request everything (Transmission re-downloads all on mismatch; cap at one reset so the entry
  can still fall through to the fresh-peer rescue + normal strike path).
- Global deadline = Stage 1's `tree_metadata_timeout_ms` (default 60 s). Window refill, per-conn
  connect timeout, `active_connections` accounting, and the fresh-peer rescue path are unchanged.

## Depends on

- Stage 1 (`stage-01-metadata-timeout.md`): the `tree->metadata_timeout_ms` field and its 60 s
  default — the new deadline this stage's scheduler runs under.
- **Baseline before starting**: ≥ 30 min crawl, save `/stats` `metadata_success_rate_percent`
  (Stage 1's post-stage number).

## Scope — vendored crawler repo (`app/src/main/cpp/dht_crawler/`), all in `src/tree_metadata.c`

1. **Shared fetch state** — new struct (file-local) owned by `tree_fetch_metadata_parallel` on the
   worker's stack: `metadata_size`, `total_pieces`, `metadata_buffer`, `pieces_bitmap`,
   `pieces_received`, `reset_count`, plus a per-piece `outstanding[]` flag array and a round-robin
   cursor. Populated from the **first** conn whose extended handshake advertises `ut_metadata` +
   `metadata_size` (≤ `max_metadata`, else close that conn).
   - `metadata_size` consistency: a conn advertising a **different** size than the shared one is
     closed and its slot refilled (peers disagree → at most one of them can be right; the SHA-1
     check at the end arbitrates).
   - No locking needed: the shared state is driven only by the worker thread's single `poll()`
     loop — document this in a comment.
2. **Slim `parallel_conn_t`** (`:758-770`): keep `fd`, `state`, `recv_buf`/`recv_offset`,
   `ut_metadata_id`, `connect_start`, `last_activity`, and its outstanding piece indices (≤ 2);
   drop the per-conn `metadata_size`/`total_pieces`/`metadata_buffer`/`pieces_bitmap`/
   `pieces_received`. `parallel_conn_close` frees only per-conn data; the shared buffer is freed
   once in `tree_fetch_metadata_parallel`'s cleanup.
3. **Request scheduler** (replaces the blast at `:911-913`): a helper that, for a given conn,
   while `outstanding < 2` and a **missing && not-outstanding** piece exists (round-robin from the
   cursor), sends `send_metadata_request` and marks it outstanding. Call it:
   - right after the extended handshake is parsed (instead of the all-pieces loop),
   - after each accepted piece (keep the pipe full),
   - once per poll-loop tick for every live conn (the 500 ms `poll` return already loops).
4. **Receiving** (`parallel_conn_process`): on `UT_METADATA_DATA` verify piece index in range and
   length `== expected` (`min(16384, metadata_size − index·16384)`, same math as `:934-938`), copy
   into the **shared** buffer, clear the outstanding flag, `pieces_received++` only if newly
   marked. Duplicates (two conns raced the same piece) are dropped by the bitmap — same as today.
   On `pieces_received == total_pieces`: SHA-1 (`SHA1(metadata_buffer, metadata_size)`) vs
   infohash → complete: `parse_info_dict`, set `result`, close all conns. On mismatch:
   if `reset_count == 0`, reset bitmap/outstanding/cursor and let the scheduler re-request
   everything; else `CONN_STATE_ERROR` on the entry (fall through to the existing failure path).
5. **Reject & silence handling** (window keeps sliding):
   - `UT_METADATA_REJECT` (`:926-929`): close the conn + refill its slot from the remaining peers
     (already today's behavior — keep; reject means "peer lacks that piece", aria2 drops the conn).
   - **Conn-silence watchdog**: track `last_activity` per conn (any readable data updates it); on a
     poll tick, if `now − last_activity > CONN_SILENCE_CLOSE_MS` (**10 s**, hardcoded), close + refill.
     Without this a silently-ignoring peer would park 2 pieces until the global deadline.
     On conn close, its outstanding flags are cleared so other conns can take those pieces.
6. **Window refill** (`:1118-1129`), **per-conn connect timeout** (`:1063-1067`), **global deadline**
   (`:1022`), `MAX_PARALLEL_PEERS` cap (`:755`), `tree->active_connections` accounting: unchanged.
7. Do **not** touch: the single-peer `tree_fetch_metadata_from_peer` (dead in `src/`, keep as is),
   the single-path blast `:679-681` (same function), the worker failure/strike/fresh-peer logic
   (`:1285-1312`), `config.c`/`main.c`/`supervisor.c`/`thread_tree.c` (no new keys this stage).

## Scope — parent repo (`~/apps/torrent_kotlin_app`)

- None (no new config keys, no Kotlin/assets changes). Parent commit records the crawler gitlink.

## Constraints

- Global constraints from `plan/metadata-fix/README.md` apply.
- Keep the public signatures (`tree_fetch_metadata_parallel`, `parallel_conn_*` are file-local)
   and the `tree_metadata_config_t` shape — Stage 3 only adds scheduler fields.
- `parse_extended_handshake` / `parse_metadata_piece` / `parse_info_dict` stay shared helpers —
  stage 2 moves their *call sites*, not their logic.
- Redundancy removal must not regress success rate: with ≤ 2 outstanding per peer and silence
  closes, a fetch now needs *fewer* total bytes but *more* round trips; the 60 s Stage-1 deadline
  absorbs that. If measurements show a regression, the fallback is raising
  `METADATA_OUTSTANDING_PER_PEER` (still ≪ all-at-once) — an implementer decision, record it.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
git submodule update --init --recursive   # inside app/src/main/cpp/dht_crawler/
./gradlew :app:testDebugUnitTest
./gradlew installDebug
```

## Exit criteria (on the phone over USB)

1. `./gradlew :app:testDebugUnitTest` passes; `./gradlew installDebug` succeeds.
2. Over a ≥ 1 h crawl: `metadata_success_rate_percent` is **strictly above** the Stage-1 baseline
   (the point of the stage — record both numbers).
3. Attempts throughput does not collapse: `metadata_attempts` per hour within −25 % of the Stage-1
   baseline (aggregation trades fewer bytes for more round trips; a collapse means the scheduler
   is stalling, not trading).
4. `fresh_peer_rescues_total` still moves (failure path intact); `metadata_count` still grows.
5. No crashes/tombstones in logcat over the window; force-stop exits promptly; observed UDP pps
   unaffected (metadata is TCP only).

## Commits

Vendored crawler repo:

```
crawler: metadata-fix stage 2 — piece aggregation across window peers (shared buffer, ≤2 outstanding/peer, no blast)
```

Parent repo (gitlink only):

```
app: metadata-fix stage 2 — crawler gitlink (metadata piece aggregation)
```