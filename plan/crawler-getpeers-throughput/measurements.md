# get_peers throughput — measurements

Append-only log. Every stage's exit criteria quote a row from here; do not edit past rows, add new ones.
Produced by `tools/crawler/gp_probe.py` (stage 1) plus the raw `/stats` fields named in each row's notes.

## Baseline (pre-stage, measured 2026-10-26, 32 trees, 91 min uptime)

Config: `tree_get_peers_workers=18`, `tree_metadata_workers=12`, `tree_bep51_workers=3`,
`tree_find_node_workers=3`, `tree_infohash_queue_capacity=500`, `tree_peers_queue_capacity=500`,
`tree_get_peers_timeout_ms=1500`, `tree_parallel_peers=4`, `udp_rate_limit_pps=2500`, `num_trees=32`,
`use_keyspace_partitioning=1`.

Probe: 70 `/stats` samples over 72 s; drain/fill from per-tree linear fits over runs of ≥8 samples with a
constant `discovery_paused` flag.

| Metric | Baseline | Notes |
|---|---|---|
| drain (pops) per tree | **1.66 /s** | 53 /s across 32 trees |
| fill (BEP51, accepted) per tree | **≈4.1 /s** | net rise +2.40/s while running, plus drain |
| fill : drain | **2.4 : 1** | queue never empties |
| queue range | 163 – 458 | resume 165 / pause 450 |
| `discovery_paused` fraction | **59 %** | sawtooth ~118 s up / ~171 s down |
| peer yield | **22 %** | 11.7 peer entries/s |
| worker time per pop | **≈10.9 s** | 18 workers ÷ 1.66 pops/s |
| packets per pop | **≈25** | 1,346 pps ÷ 53 pops/s |
| UDP | **1,346 pps** of 2,500; 962 throttled of 7.35 M | `udp_sends_total`, `udp_throttled_sends_total` |
| get_peers threads | 576, saturated | queue never empty |
| metadata threads | 384, **20 % busy** | 9.6 of 48 possible conns/tree |
| metadata | **2.62 /s**, 15.8 % success | `total_metadata_fetched`, `metadata_success_rate_percent` |
| stored | **4,920 /h** | `torrents_last_hour` |
| bloom persistence | 2 × 53.9 MB per flush, ~10 flushes/h → **~1.08 GB/h** | file sizes + mtimes |

## Stage 1 — worker-scaling probe

`tree_get_peers_workers` changed from the dashboard (no rebuild), 10 min per step, daemon restarted
between steps.

| workers | drain /s per tree | pps | mean lookup ms | yield % | `dropped_responses` /s | `queue_full_drops` /s | RSS | threads | verdict |
|---|---|---|---|---|---|---|---|---|---|
| 18 | 1.57 | 1,349 | 10,155 | 48.5 | 38.96 | 0.00 | 623 MB | 1,259 | reference |
| 36 | 3.53 | 2,477 | 9,452 | 50.4 | 59.38 | 0.00 | 698 MB | 1,835 | packet-limited |
| 54 | 3.21 | 2,162 | 12,653 | 52.4 | 50.26 | 0.00 | 719 MB | 2,411 | packet-limited |

Probe settings, identical for all three runs: 10 min, 2 s interval, 300 usable `/stats` samples, 10 health
samples, 32/32 trees, `tree_get_peers_timeout_ms=1500`, `udp_rate_limit_pps=2500`. Health samples
(threads, VmRSS start → end): 18 workers — 1,259 threads, 498 → 623 MB; 36 — 1,835 threads, 530 → 698 MB;
54 — 2,411 threads, 563 → 719 MB. Each run started from a cold daemon restart (config apply) and was
warmed to mean `routing_table_nodes` ≥ 800 before the probe began, so the three runs are comparable to
each other but **not** to the 91-min-uptime baseline section above.

**Verdict: packet-limited.** The `yield %` column is the new counter definition,
`Δlookups_with_peers / Δlookups_total` (48.5 % at 18 workers); it is **not** comparable to the baseline
section's 22 % peer yield, which is the metadata-outcome definition
`(metadata/s + first_strike/s) ÷ drain_total/s` — that quantity measured **27.4 %** in the same 18-worker
run ((3.15 + 10.59) / 50.1). Both numbers are quoted so neither can be read as the other.

Mechanical rules (stage 5), applied in precedence order, where `D` = drain /s per tree, `P` = mean pps,
`T` = throttled sends /s, `DR` = `dropped_responses` /s, `QF` = `queue_full_drops` /s:

1. **Packet-limited if `P54 ≥ 2,200` OR `T54 ≥ 10/s`.** `P54 = 2,162` is below 2,200, but
   `T54 = 1,013,435 ÷ 598.1 s = 1,694.6/s ≥ 10/s` → **this branch fires.**
2. Else **dispatcher-limited** if (`DR54 ≥ 2×DR18` AND `DR54 ≥ 1/s`) OR (`QF54 > 0` while `QF18 == 0`):
   `DR54/DR18 = 50.26/38.96 = 1.29` (< 2×) and `QF54 = QF18 = 0` → does not fire.
3. Else **worker-limited** if `D54 ≥ 2×D18` AND `P54 < 2,200`: `D54/D18 = 3.2053/1.5669 = 2.05` (≥ 2×)
   and `P54 = 2,162 < 2,200` → would fire, but rule 1 has precedence.
4. Else **unexplained plateau** — not reached.

Shape of the curve: 18 → 36 **scaled 2.25×** (1.57 → 3.53 /s per tree) because the UDP budget still had
headroom at 1,349 pps (54 % of the 2,500 cap); 36 → 54 **plateaued and regressed** (3.53 → 3.21 /s per
tree) with 54 running at 2,162 pps (86 % of cap) and throttling at 1,888/s (36) and 1,695/s (54). Mean
lookup time rose 9,452 → 12,653 ms from 36 to 54, so the saturated-pool identity
`mean_lookup_s × drain_per_tree_per_s` fell to **40.6** against 54 workers (33.3 at 36, 15.9 at 18): the
extra workers were blocked in the UDP token bucket, not starved of work. The dispatcher is exonerated —
`queue_full_drops` was 0.00/s in all three runs and the `dropped_responses` ratio is only 1.29×
(38.96 → 50.26 /s, i.e. late responses arriving after TID unregistration, not a routing bottleneck).

Implication for stage 2: packets are the binding constraint, so land the duplicate-round removal and get
packets per pop ≤ 10 **before** expecting any gain from fan-out alone — the lookup rate cannot grow past
the UDP budget until each pop costs fewer packets.

Reverted to 18 afterwards: ☑ (device `files/crawler/config.ini` reads `tree_get_peers_workers = 18`).

## Stage 2 — parallel fan-out

| Metric | Baseline | Stage 2 | Target | Pass |
|---|---|---|---|---|
| mean lookup ms | 10,900 | | ≤2,500 | ☐ |
| packets per lookup | 25 | | ≤10 | ☐ |
| drain /s per tree | 1.66 | | ≥4.0 | ☐ |
| yield % | 22 | | ≥20 | ☐ |
| pps | 1,346 | | <2,200 | ☐ |
| `queue_full_drops` delta | 0 | | 0 | ☐ |
| peers queue max | 17 | | >100 | ☐ |
| `get_peers_paused` observed | never | | yes | ☐ |
| `torrents_last_hour` | 4,920 | | ≥9,840 | ☐ |
| metadata success % | 15.8 | | 15.8 ±3 pp | ☐ |

## Stage 3 — config plumbing

| Check | Result |
|---|---|
| `deadline_ms = 5000` round-trips to `/stats` + on-device `config.ini` | ☐ |
| `fanout = 4`, `max_queries = 24` round-trip | ☐ |
| `deadline_ms = 99999` clamps to 8000 with a WARN | ☐ |
| `fanout = 1` clamps to 2 | ☐ |
| `max_queries = 2` raised to the fan-out value | ☐ |
| defaults (3000/8/16) within ±10 % of stage 2 | ☐ |
| one queue create/destroy in `tree_metadata_worker_func()` | ☐ |
| 30 min at 12/8000: drain not lower, RSS flat | ☐ |

## Stage 4 — iterative round

| Metric | Stage 3 | Stage 4 | Target | Pass |
|---|---|---|---|---|
| yield % | | | ≥25 | ☐ |
| packets per lookup | | | 10 – 14 | ☐ |
| mean rounds per lookup | 1.0 | | 1.05 – 1.6 | ☐ |
| drain /s per tree | | | ≥ stage 3 | ☐ |
| mean lookup ms | | | ≤3,000 | ☐ |
| pps | | | <2,400 | ☐ |
| `torrents_last_hour` | | | ≥ stage 3 | ☐ |

**Verdict (fill in):** ship or revert. A revert is a valid result and closes root cause 2.

## Stage 5 — bloom persist throttle

| Metric | Before | After | Target | Pass |
|---|---|---|---|---|
| flushes/h (measured trigger) | | — | recorded | ☐ |
| `bloom_saves_total` / 30 min | | | ≤7 | ☐ |
| `bloom_bytes_written_total` / h | ~1.08 GB | | ≤0.3 GB | ☐ |
| dedup unchanged (`torrents_indexed` growth == `torrents_last_hour`) | | | yes | ☐ |
| shutdown still persists both filters (mtime updates) | | | yes | ☐ |
