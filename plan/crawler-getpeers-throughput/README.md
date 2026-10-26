# Crawler get_peers throughput — stage index

Per-stage implementation packets live here, one file per stage, implemented **one at a time**
via `/dev <stage file>` (dev-workflow). This document is the parent's design + on-phone exit criteria.

> **Target repo: `~/apps/torrent_kotlin_app`**, submodule `app/src/main/cpp/dht_crawler`.
> A phone connected over ADB is required for every measurement (this plan is measurement-led).

## Problem

The crawler's input stage (`get_peers`) is saturated while its output stage (metadata fetch) is idle.
That is the whole reason metadata yield sits at 2.6 torrents/s on a device with 384 idle metadata
threads. Nothing in the pipeline is starved of *hashes* — the infohash queue never empties — the
stage is starved of **time**: one infohash costs ~11 s of a get_peers worker, and ~78 % of those
seconds produce nothing.

## Measured baseline

Live on the phone, 32 trees, 91 min uptime, config `tree_get_peers_workers=18`,
`tree_metadata_workers=12`, `tree_bep51_workers=3`, `tree_find_node_workers=3`,
queues 500/500, `udp_rate_limit_pps=2500`, `tree_get_peers_timeout_ms=1500`, `tree_parallel_peers=4`.

Probe: 70 `/stats` samples over 72 s, per-tree linear fits (`plan/…/stage-01` builds the repeatable
version of this).

| Quantity | Value | Source |
|---|---|---|
| infohash-queue drain (get_peers pops) | **1.66 /s per tree → 53 /s total** | fit on `discovery_paused` stretches |
| BEP51 fill (sustained, accepted hashes) | ≈**4.1 /s per tree** | net rise + drain |
| fill : drain | **2.4 : 1** | — |
| queue sawtooth | 165 ↔ 450, ~118 s up / ~171 s down | resume/pause thresholds 33 %/90 % |
| `discovery_paused` fraction | ~59 % of wall time | — |
| peer yield | **22 %** of drained hashes → 11.7 peer entries/s | (metadata + first-strike)/s ÷ drain |
| **worker time per pop** | **≈10.9 s** | 18 workers ÷ 1.66 pops/s |
| **UDP packets per pop** | **≈25** | 1,346 pps ÷ 53 pops/s |
| UDP | 1,346 pps of a 2,500 pps cap; 962 throttled of 7.35 M sends | `/stats` |
| get_peers pool | 576 threads, **saturated** (queue never empties) | queue range 163–458 |
| metadata pool | 384 threads, 9.6 of 48 possible conns/tree = **20 % busy** | `active_connections` |
| metadata | 2.62 /s, 15.8 % success, 4,920 stored/h | `/stats` |

## Root causes

All four are inside `tree_lookup_peers()` (`src/thread_tree.c:644-722`).

**1. Every wait is sequential.** For each node: `tree_send_get_peers()` then
`tree_response_queue_pop(…, tree_get_peers_timeout_ms)` — 1,500 ms per node, one at a time. Iteration 0
alone is 8 × up to 1.5 s before anything else can happen.

**2. Two of the three rounds re-query the same eight nodes.** Line 717 refills `closest` *from the
routing table*, and the closer nodes learned from responses are only inserted into the table
(line 703-705, whose comment reads `/* Could track these for next iteration, but keeping it simple */`).
For a fixed target the XOR-closest 8 of a 1,500-node table do not change, so rounds 1 and 2 re-ask the
nodes that just timed out. **~2/3 of the packet budget is duplicate traffic.** Worst case per dead
infohash: 8 × 3 × 1.5 s = **36 s**.

**3. No per-infohash deadline.** There is only a per-node timeout, so the cost of a dead hash is
unbounded from the caller's point of view (only `peer_count > 0` breaks out early).

**4. The stage is invisible.** `/stats` exposes no get_peers counters at all, and the zero-peer drop at
line 776 is silent. That is why a 4× throughput defect went unnoticed: the only visible symptom is
"metadata is slow", and the only visible remedy was to add threads.

## Design decisions

**Locked:**

- **Fix the consumer, not the buffer.** `tree_infohash_queue_capacity` and the pause thresholds are
  throughput-neutral here: fill:drain is 2.4:1, so the queue is a buffer whose only job is to stop
  BEP51 from unbounded growth. Raising it lengthens FIFO latency and nothing else.
- **Fan out the round, then drain against one deadline.** Send the first round's queries
  back-to-back with distinct TIDs (the dispatcher already routes per TID — `tree_dispatcher.c`,
  `tree_protocol_extract_tid()` already exists), then drain responses until all slots are answered or
  the deadline expires. This removes cause 1 and cause 3 together.
- **Cut packets per infohash, do not just compress time.** The UDP token bucket (2,500 pps, global,
  `udp_rate_limit.c`) becomes the binding constraint at ~275 pops/s. Target **≤10 packets per
  infohash** (today ~25). Duplicate rounds are the cheapest packets to delete.
- **Keep `tree_lookup_peers()`'s signature and semantics** — returns peer count, fills `peer_entry_t`,
  pushes nothing, changes no phase. Three call sites depend on it: the get_peers worker
  (`thread_tree.c:773`), the metadata worker's fresh-peer retry (`tree_metadata.c:1486`, 87 k calls/h),
  and the future failure-retry lane.
- **One deadline per infohash, hard-capped below 10 s.** `tree_dispatcher.c` runs
  `tree_dispatcher_cleanup_stale_tids(…, 10)` every 30 s and deletes registrations older than 10 s; a
  longer deadline would race the cleanup and lose responses.
- **Response-queue capacity ≥ fanout + 4 in every caller.** `tree_response_queue_try_push()` drops
  silently when full, so an under-sized queue turns into phantom timeouts. Both the worker queue
  (`thread_tree.c:807`, capacity 10) and the metadata retry queue (`tree_metadata.c:1482`, capacity 10,
  allocated per failure) must be resized in the same stage as the fan-out.
- **Do not raise `udp_rate_limit_pps`.** It is a network-politeness decision, not an engineering one,
  and it stays the operator's knob. This plan gets its throughput inside the current budget; if the
  stages land and the budget is what still binds, that is a separate, explicit decision.

**Rejected after measurement — do not implement:**

- **"Just add get_peers workers."** 4× the threads costs 4× the packets (each pop still spends ~25),
  i.e. ~5,400 pps against a 2,500 cap, plus 2,304 threads. Kept instead as a *diagnostic probe* in
  stage 1 to locate which ceiling we actually hit.
- **Bigger infohash queue.** Measured fill:drain 2.4:1 — the queue never empties, so capacity is not
  the constraint. Every entry still costs a full lookup.
- **Lowering the pause threshold.** BEP51 already sits paused ~59 % of the time; this changes its duty
  cycle, not the drain rate.
- **Caching peer addresses for hashes that failed.** That is the failure-store / retry-lane work (a
  separate plan). It cannot raise the input stage's rate, and routing retries through
  `tree_infohash_queue` would *steal* from the saturated stage.

## Stage index

| Stage | File | Fixes | Expected effect (on-phone) |
|---|---|---|---|
| 1 | `stage-01-instrumentation-and-baseline.md` | cause 4 | get_peers counters + repeatable probe + the worker-scaling experiment that decides stage 2's target |
| 2 | `stage-02-parallel-fanout.md` | causes 1, 3 | lookup 10.9 s → ≤2.5 s, packets/pop ~25 → ≤10, drain 1.66 → ≥4 /s per tree |
| 3 | `stage-03-config-plumbing.md` | — | deadline / fanout / query-cap become config, allow-listed, clamped |
| 4 | `stage-04-iterative-round.md` | cause 2 (properly) | yield 22 % → ≥25 %; chase only closer nodes, inside the packet budget |
| 5 | `stage-05-bloom-persist-throttle.md` | regression guard | bloom rewrite ~1 GB/h → ≤0.3 GB/h after the flush rate rises |

Stages 1–2 are the throughput work, in dependency order. Stage 3 generalises stage 2's hardcoded
constants. Stage 4 is the incremental yield refinement. Stage 5 ships **only if** the measured flush
rate rises as predicted — it is a consequence guard, not a feature.

**Targets.** Per stage, on the phone: stage 2 takes the mean lookup from **10.9 s to ≤2.5 s**, packets
per pop from **~25 to ≤10**, and the drain from **1.66/s to ≥4/s per tree** (2.4×) at **≤2,200 pps**.
Stage 4 lifts yield to **≥25 %** and drain to **≥5/s**. End state: the peers queue fills, `get_peers_paused`
engages on its own, and the metadata stage becomes the limiter — which is where the limiter belongs,
because that stage is 80 % idle today.

**The one number not to extrapolate:** yield at the new drain rate. A faster drain reaches deader
hashes, so 22 % may fall. The stage-2 exit criterion is `yield ≥ 20 %`; if it collapses, raise the
deadline (`tree_get_peers_deadline_ms`, stage 3) before adding anything else. Do not quote a projected
"5× torrents" — measure `torrents_last_hour` before and after.

## Cross-stage constraints

- The crawler is a git **submodule**. Commit inside it, then bump the parent gitlink.
- Edit `dht_crawler/src/` and `dht_crawler/include/` freely; **never** `dht_crawler/lib/` (vendored).
  Do not touch the three build patches in `dht_crawler/patches/` (`bencode-cap-fix.patch`,
  `bencode-init-fix.patch`, `civetweb-stop-flag-atomic.patch`) — they patch the vendored libs only.
- The crawler must still build with its own host `make` (no `CLF_ENABLED`).
- New config keys go in **three** places or they are dead: `include/config.h`, `src/config.c`
  (defaults + parse + clamp + `config_print`), and the app side — `CrawlerKeys.kt` allow-list
  (AGENTS.md invariant 7) plus `app/src/main/assets/dht_crawler/config.ini.default`.
- Keep `tree_lookup_peers()`'s signature: `thread_tree.h:283` documents it as the shared helper.
- Do not touch `refresh_thread.c` / `refresh_query.c`. The `/refresh` endpoint has its own lookup with
  different semantics (bootstrap sample, own iteration count); it is out of scope.
- All dispatcher TID registrations must be balanced on **every** exit path, including shutdown and
  deadline expiry, or the shared dispatcher keeps a pointer to a dead queue.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
# 1) host compile of the crawler (fast):
(cd app/src/main/cpp/dht_crawler && make -j"$(nproc)")
# 2) full Android cross-compile + install:
./gradlew installDebug
# 3) unit tests still green:
./gradlew :app:testDebugUnitTest
```

## On-phone verification (operator, after each stage)

```bash
# A. the repeatable probe (stage 1) — before/after tables must be comparable
python3 tools/crawler/gp_probe.py --minutes 10
# B. raw counters
adb forward tcp:8081 tcp:8081
curl -s http://127.0.0.1:8081/stats | jq '.supervisor, .trees[0].get_peers'
# C. throughput end-to-end
curl -s http://127.0.0.1:8081/stats | jq '{torrents_last_hour, aggregate}'
```

Pass condition after stage 2: drain **≥4 /s per tree** sustained over 10 min, mean lookup **≤2.5 s**,
packets per pop **≤10**, `udp_sends_total` delta **<2,200 pps**, `queue_full_drops` flat,
`torrents_last_hour` **≥2×** baseline, and the crawler's `/search` + `/torrent/info` latencies
unchanged (this plan must not touch the DB/HTTP path that `plan/crawler-search-performance/` fixed).

## Follow-ups (not in this plan)

- **BEP51 fan-out.** The BEP51 worker has the same send-then-wait-1000 ms shape
  (`thread_tree.c:512-514`) with 3 workers/tree. Once the drain outruns the fill it becomes the next
  lever. Do not do it here — it is not the current bottleneck and it shares the same UDP budget.
- **Failure store + opportunistic retry lane** (the research this plan came out of): replace
  `failure_bloom.dat` with a bounded `fetch_failures` table and let idle metadata workers retry
  partition-affine candidates. It becomes relevant *after* this plan, when the metadata stage is the
  limiter and its idle capacity is the resource worth spending.
