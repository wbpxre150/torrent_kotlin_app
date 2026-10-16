# Stage 3: Per-piece 3 s re-ask across peers + reask counters

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repos: `~/apps/torrent_kotlin_app`** (gitlink only) **and the vendored crawler repo at
> `app/src/main/cpp/dht_crawler/`** (its own `.git`, HEAD = whatever Stage 2 left).
> Requires the test phone on USB. Index: `plan/metadata-fix/README.md`.

## Goal

Close the reliability gap Stage 2 leaves: a piece whose request went unanswered on one conn stays
outstanding there until the 10 s conn-silence close or the 60 s global deadline. Production
clients re-ask much sooner and from **other** peers:

- **libtorrent**: 3 s per-piece cooldown, then any other peer may take it
  (`src/ut_metadata.cpp`, per-piece `last_request`).
- **Transmission**: `MinRepeatIntervalSecs = 3`, re-ask from **any** peer
  (`torrent-magnet.cc` `get_next_metadata_request`).

This stage adds a per-piece **3 s re-ask rule** to Stage 2's scheduler: a piece outstanding on one
conn for > 3 s without a reply becomes requestable by the other conns (the original request is
**not** cancelled — duplicate replies are already deduped by the shared bitmap, Transmission drops
duplicates the same way), plus per-tree **reask counters** in `/stats` so the effect is measurable.
Defaults stay hardcoded (no new config keys — deferred per `README.md`).

## Depends on

- Stage 2 (`stage-02-piece-aggregation.md`): the shared fetch state, per-piece `outstanding[]`
  flags, per-conn outstanding cap, and the scheduler helper. **Baseline before starting**: ≥ 30 min
  crawl, save `/stats` `metadata_success_rate_percent` (Stage 2's post-stage number).

## Scope — vendored crawler repo (`app/src/main/cpp/dht_crawler/`)

All in `src/tree_metadata.c` unless noted.

1. **Monotonic ms clock helper**: `static int64_t now_ms(void)` via
   `clock_gettime(CLOCK_MONOTONIC, …)` (µs→ms). The rest of the file uses `time(NULL)` seconds;
   scheduler math needs ms. One small helper, no global state.
2. **Per-piece bookkeeping** in Stage 2's shared fetch state: `int64_t last_request_ms[]` next to
   `outstanding[]`, stamped when a request for that piece is sent.
3. **Scheduler rule change** (Stage 2's helper): a piece is requestable by a conn if
   `missing && (!outstanding || now_ms − last_request_ms > 3000)`. On the re-ask path (the piece was
   already outstanding elsewhere), bump the new per-tree atomic
   `atomic_fetch_add(&tree->metadata_piece_reasks, 1)`.
   - Cap unchanged: ≤ `METADATA_OUTSTANDING_PER_PEER` (2) outstanding per conn — a re-ask occupies
     the *new* conn's slot, the old one still holds its own.
   - Keep the 10 s conn-silence close as the backstop for dead conns; closing a conn clears its
     outstanding flags (Stage 2) so its pieces re-arm immediately.
4. **Counters + `/stats`**:
   - `include/thread_tree.h` `thread_tree_t` (~`:193-199`, next to `fresh_peer_relookups`):
     `atomic_uint_fast64_t metadata_piece_reasks;` (init to 0 where the tree is created).
   - `src/http_api.c`: per-tree `metadata_piece_reasks` in the tree JSON (next to `metadata_count`,
     `:1125`) and a supervisor-aggregated `metadata_piece_reasks_total` next to the existing
     fresh-peer aggregates.
5. No changes to: config plumbing (no new keys), the worker failure/strike/fresh-peer path, the
   window/refill logic, or the single-peer dead code.

## Scope — parent repo (`~/apps/torrent_kotlin_app`)

- None (no new config keys). Parent commit records the crawler gitlink.

## Constraints

- Global constraints from `plan/metadata-fix/README.md` apply.
- Re-ask must not spam: 3 s is a floor per piece (libtorrent/Transmission parity), and the
  per-conn ≤ 2 cap bounds total request rate per fetch. No config knobs this stage (deferred).
- Do not cancel the original request on re-ask (libtorrent keeps both; duplicates dedup by bitmap).
- `/stats` additions must follow the existing aggregate pattern (`metadata_success_rate_percent`
  block, `http_api.c:1218-1231`) — no new handler, extend the existing stats JSON.

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
2. `/stats` exposes `metadata_piece_reasks_total` (and per-tree `metadata_piece_reasks`) — verify
   with `adb forward tcp:18081 tcp:8081` + `curl http://127.0.0.1:18081/stats`.
3. Over a ≥ 1 h crawl: `metadata_piece_reasks_total > 0` (the rule actually fires on real hangs)
   and `metadata_success_rate_percent` is **not lower** than the Stage-2 baseline (expected: higher
   — record both numbers for the final report).
4. `metadata_attempts` per hour within −25 % of the Stage-2 baseline (the re-ask must not stall the
   window); observed UDP pps unaffected (TCP only).
5. No crashes/tombstones in logcat over the window; force-stop exits promptly.
6. **Final**: across the three stages, `metadata_success_rate_percent` is materially above the
   pre-Stage-1 ~15 % baseline — the number that justified this plan.

## Commits

Vendored crawler repo:

```
crawler: metadata-fix stage 3 — 3s per-piece re-ask across peers + metadata_piece_reasks counters
```

Parent repo (gitlink only):

```
app: metadata-fix stage 3 — crawler gitlink (per-piece re-ask)
```