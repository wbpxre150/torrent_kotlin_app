# Stage 1: get_peers instrumentation, baseline, and the worker-scaling probe

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`**, submodule `app/src/main/cpp/dht_crawler`.
> Index/design: `plan/crawler-getpeers-throughput/README.md`.
> No dependencies. **This stage changes no crawling behaviour.**

## Goal

Make the input stage measurable, then use that measurement to find which ceiling we are actually
hitting before rewriting anything.

Two deliverables:

1. **Counters.** `/stats` currently exposes nothing about `get_peers` — no lookups, no packets, no
   duration, no yield — and the zero-peer drop at `thread_tree.c:776` is silent. That is why a 4×
   throughput defect went unnoticed.
2. **A scaling probe that decides stage 2's target.** `tree_get_peers_workers` is a config key read at
   startup, so 18 → 36 → 54 costs no rebuild. If the drain scales linearly and pps stays under the cap,
   the stage is worker-limited and the fan-out rewrite wins big. If it plateaus (dispatcher, routing-table
   lock, UDP bucket), stage 2's packet reduction is the primary lever, not the fan-out.

## Context (measured — do not re-derive)

Baseline on the phone, 32 trees, `tree_get_peers_workers=18`, `tree_get_peers_timeout_ms=1500`:

| Quantity | Value |
|---|---|
| drain (pops) | 1.66 /s per tree → 53 /s total |
| worker time per pop | ≈10.9 s (18 ÷ 1.66) |
| packets per pop | ≈25 (1,346 pps ÷ 53 pops/s) |
| yield | 22 % of pops produce a peer entry |
| UDP | 1,346 pps of a 2,500 pps cap; 962 throttled of 7.35 M |
| get_peers pool | 576 threads, saturated |
| metadata pool | 384 threads, 20 % busy |

Code facts to build on:

- `tree_lookup_peers()` (`thread_tree.c:644-722`) is the single place every get_peers packet is sent
  from — two call sites: the get_peers worker (`thread_tree.c:773`) and the metadata worker's
  fresh-peer retry (`tree_metadata.c:1486`).
- `tree_dispatcher_t` already carries `total_responses`, `routed_responses`, `dropped_responses`,
  `queue_full_drops` (`include/tree_dispatcher.h:57-60`) — not exposed anywhere.
- `tree_infohash_queue_get_stats()` already exists (`tree_infohash_queue.c:259`) — not called from
  `/stats`.
- With a fixed `dht_port` (this install: 6881) **one shared dispatcher thread serves all 32 trees**
  (`supervisor.c` `shared_dispatcher`), and `tree_socket_send()` serialises every packet through one
  process-wide `send_lock` (`tree_socket.c:160`). Both are candidate ceilings for the probe to find.

## Scope

### 1. Per-tree counters (`include/thread_tree.h`, `src/thread_tree.c`)

Add next to `metadata_piece_reasks` (line ~202):

```c
atomic_uint_fast64_t gp_lookups;             /* tree_lookup_peers() calls */
atomic_uint_fast64_t gp_lookups_with_peers;  /* of those, peer_count > 0 */
atomic_uint_fast64_t gp_lookups_from_retry;  /* of those, called by the metadata retry path */
atomic_uint_fast64_t gp_packets_sent;        /* get_peers packets attempted */
atomic_uint_fast64_t gp_answers;             /* responses matched to one of our TIDs */
atomic_uint_fast64_t gp_lookup_us_total;     /* sum of lookup wall time, microseconds */
```

Initialise to 0 in `thread_tree_create()` next to the other `atomic_store`s (line ~1059).

### 2. Instrument `tree_lookup_peers()`

Wrap the existing body, **without changing behaviour**:

- `clock_gettime(CLOCK_MONOTONIC)` at entry and at every return; add the delta to
  `gp_lookup_us_total`. Use a single exit path (or a small `goto done`) so no return is missed.
- `gp_lookups` +1 at entry; `gp_lookups_with_peers` +1 iff the returned `out->peer_count > 0`.
- `gp_packets_sent` +1 immediately before each `tree_send_get_peers()` (count attempts, not successes —
  a failed build/send is still a lost opportunity).
- `gp_answers` +1 on each `pop_result == 0` (i.e. a response we actually consumed).

### 3. Mark retry calls

In `tree_metadata.c`, immediately before the `tree_lookup_peers()` call at line 1486 (the fresh-peer
relookup), `atomic_fetch_add(&tree->gp_lookups_from_retry, 1);`. Do **not** change the function
signature.

### 4. Supervisor cumulative counters + accessor

- `include/supervisor.h`: add `cumulative_gp_lookups`, `cumulative_gp_lookups_with_peers`,
  `cumulative_gp_lookups_from_retry`, `cumulative_gp_packets_sent`, `cumulative_gp_answers`,
  `cumulative_gp_lookup_us_total` beside the other `cumulative_*` fields (line ~222).
- Accumulate them in the respawn path exactly like `cumulative_metadata_piece_reasks`
  (`supervisor.c:1170`).
- **Do not extend `supervisor_stats()`** — it already takes 7 out-params and every call site would
  churn. Add a separate accessor:

  ```c
  void supervisor_getpeers_stats(supervisor_t *sup,
                                 uint64_t *out_lookups, uint64_t *out_with_peers,
                                 uint64_t *out_from_retry, uint64_t *out_packets,
                                 uint64_t *out_answers, uint64_t *out_lookup_us);
  ```

  Same shape as `supervisor_stats()`: cumulative + sum over live trees, under `trees_lock`.

### 5. Expose in `/stats` (`src/http_api.c`, `stats_handler`)

Per tree, alongside the existing `queues` object:

```json
"get_peers": {
  "lookups_total": 0, "lookups_with_peers": 0, "yield_percent": 0.0,
  "packets_total": 0, "packets_per_lookup": 0.0,
  "mean_lookup_ms": 0.0, "answers_total": 0, "from_retry_total": 0
}
```

Under `supervisor`, add the same object with the `supervisor_getpeers_stats()` totals, plus:

```json
"dispatcher": { "total_responses": 0, "routed_responses": 0,
                "dropped_responses": 0, "queue_full_drops": 0 }
```

`dispatcher` comes from `sup->shared_dispatcher` when non-NULL; otherwise sum the live trees'
`tree->dispatcher`. Also add the existing `tree_infohash_queue_get_stats()` values to each tree's
`queues` object as `infohash_push_attempts` / `infohash_duplicates_rejected`.

Divide-by-zero guard on every ratio: report `0.0`, never `nan` (cJSON emits invalid JSON for NaN).

### 6. Repeatable probe — `tools/crawler/gp_probe.py`

New file, python3 stdlib only, mirroring `tools/usb-io/rpc_probe.sh`'s role. Behaviour:

```
python3 tools/crawler/gp_probe.py [--minutes N] [--interval S] [--port 8081] [--json OUT]
```

- `adb forward tcp:<port> tcp:8081`, poll `/stats` every `--interval` (default 2 s), remove the forward
  on exit (also on Ctrl-C).
- Compute and print a markdown table:

  | metric | how |
  |---|---|
  | drain /s per tree | linear fit of `infohash_queue_size` over runs of ≥8 consecutive samples with `discovery_paused == true`, negated |
  | fill /s per tree | net rise over runs of ≥8 samples with `discovery_paused == false`, **plus** drain |
  | paused fraction | share of (tree, sample) pairs with `discovery_paused` |
  | yield % | Δ`lookups_with_peers` / Δ`lookups_total` |
  | packets per lookup | Δ`packets_total` / Δ`lookups_total` |
  | mean lookup ms | Δ`lookup_us_total` / Δ`lookups_total` / 1000 |
  | pps | Δ`udp_sends_total` / Δt, plus `udp_throttled_sends_total` delta |
  | metadata /s, first strike /s | deltas of `supervisor.total_metadata_fetched` and `first_strike_failures` |
  | dispatcher drops /s | deltas of `dropped_responses`, `queue_full_drops` |
  | peers queue | mean and max `peers_queue_size` across trees |
  | active conns / tree | mean `active_connections` |

- `--json` writes the raw samples plus the derived table for before/after diffing.
- Print the tree count, uptime and the config values the probe depends on (`tree_get_peers_workers`,
  `tree_get_peers_timeout_ms`, `udp_rate_limit_pps`) so two runs are provably comparable.

### 7. The scaling probe (the point of this stage)

Using the dashboard/config path (no rebuild — `tree_get_peers_workers` is already allow-listed in
`CrawlerKeys.kt:48`):

1. Baseline: workers = 18, restart the daemon, `gp_probe.py --minutes 10 --json /tmp/gp-18.json`.
2. workers = 36 → probe 10 min → `/tmp/gp-36.json`.
3. workers = 54 → probe 10 min → `/tmp/gp-54.json`.
4. **Revert to 18** and restart.
5. While each run is live, sample process health once a minute:
   `adb shell dumpsys meminfo com.torrent.app | head -20` and
   `adb shell run-as com.torrent.app ls /proc/self/task | wc -l` (thread count via the daemon pid).

Write the three rows plus a one-paragraph verdict into
`plan/crawler-getpeers-throughput/measurements.md`:

- **If drain scales ~linearly and pps stays <2,200** → the stage is worker-limited; stage 2's fan-out is
  the right lever and its targets stand.
- **If drain plateaus while pps approaches 2,500** → packets are the binding constraint; stage 2 must
  land the duplicate-round removal *and* keep packets/pop ≤10 before expecting any gain.
- **If drain plateaus with pps well under the cap and `dropped_responses`/`queue_full_drops` rising** →
  the shared dispatcher is the ceiling. Stop and report: stage 2 would need a dispatcher change, which
  is not in this plan.

## Constraints

- **No behavioural change.** Same packets, same timeouts, same early-exit conditions, same routing-table
  updates. This stage must be verifiable as a pure instrumentation diff.
- Do not edit `dht_crawler/lib/` or the three patches in `dht_crawler/patches/`.
- `thread_tree.h` is included widely; keep the new fields plain `atomic_uint_fast64_t` (no new headers).
- `/stats` is polled by the Kotlin dashboard and the probe: keep the existing keys and shapes byte-stable,
  only add.
- The probe must not need `sudo`, `jq`, or any non-stdlib Python module.

## Pitfalls

1. **Every early return must be timed.** `tree_lookup_peers()` has three returns today (`n == 0`,
   `n == 0` inside the loop, and the final one). A missed return skews `mean_lookup_ms` low and will be
   read as a stage-2 win that did not happen.
2. **`gp_lookups_from_retry` is bumped by the caller, not the callee.** If you bump it inside
   `tree_lookup_peers()` you cannot tell retries from fresh lookups; the whole point is to subtract the
   retry traffic (87 k/h) when reading the yield.
3. **The shared dispatcher may be NULL.** With `dht_port=0` (ephemeral) each tree owns its dispatcher.
   Handle both, and do not dereference `sup->shared_dispatcher` without the NULL check.
4. **`peers_queue_size` is per-tree and 0 most of the time.** Report mean *and* max, otherwise the
   table looks like the queue is always empty and the reader concludes the wrong thing.
5. **Thread count will be huge (576 today).** When you bump workers to 54 expect ~2,900 threads; record
   RSS and thread count at each step or the "worker-limited" verdict is unfalsifiable.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
(cd app/src/main/cpp/dht_crawler && make -j"$(nproc)")
./gradlew installDebug
./gradlew :app:testDebugUnitTest
```

## Exit criteria (implementer) — all must pass

1. Host `make`, `./gradlew installDebug` and `:app:testDebugUnitTest` all clean.
2. `/stats` shows `trees[].get_peers`, `supervisor.get_peers`, `supervisor.dispatcher` and the two new
   `queues` fields; every value moves under load; no `nan`/`null` in the JSON.
3. `gp_probe.py --minutes 10` reproduces the baseline within **±20 %** on: drain (1.66/s per tree),
   packets per lookup (~25), mean lookup ms (~10.9 s), yield (22 %).
4. Behaviour unchanged: `torrents_last_hour` and `supervisor.total_metadata_fetched` rate within noise
   of the pre-stage probe.
5. `measurements.md` contains the 18/36/54 rows, the health samples, and the verdict paragraph naming
   which ceiling binds (worker / packets / dispatcher).
6. No counter overflow (all `uint64`), and `dropped_responses` does not grow in the baseline run.

## Commits

1. Inside the submodule:
   `git -C app/src/main/cpp/dht_crawler commit -am "crawler: get_peers lookup/packet/yield counters + /stats exposure"`
2. Parent, with the probe script and the measurement note:
   `git add app/src/main/cpp/dht_crawler tools/crawler plan/crawler-getpeers-throughput && git commit -m "app: crawler get_peers throughput stage 1 — instrumentation + baseline"`
