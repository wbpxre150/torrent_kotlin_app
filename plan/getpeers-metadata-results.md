# get_peers / metadata — running window record (patches 20–22)

Status: **in progress.** This is the run's authoritative measurement record. Stage 2 creates it; Stages 3–5
append below the marker at the end. Every number in it is a rate **delta over one 30-minute window** on the
device — never a lifetime counter, never an estimate — and every experiment is paired with a
contemporaneous same-hour, same-build control that differs in exactly one variable.

## Run header

| field | value |
|---|---|
| device | PFEM10, `com.torrent.app`, adb transport `adb-b078fce-dgRfE0._adb-tls-connect._tcp` (plain `b078fce` fails) |
| build | HEAD **d271699** "crawler patch 20: last-interval windowed rates in /stats aggregate (10s supervisor sampler, pure stats_window module + host test)" — one build for both windows, no rebuild between them |
| date | 2026-11-17 (+0800) |
| windows | **W0** 09:29:23 → 09:59:23 (`tree_parallel_peers=2`) · **W1** 10:06:44 → 10:36:45 (`tree_parallel_peers=4`); 7 min 21 s between W0's end and W1's start = Apply restart + 5 min settle |
| who measured | the **parent** ran every window, every device config change and every probe (Assumption 8). No implementer/tester ran a window |
| window shape | 30.0 min, 5 s interval, span **1795.1 s**, **360 usable / 0 skipped** samples, 28 health samples, `interrupted: false`, `missing_features: []` |
| tree count | **64 / 64** in both windows, `total_trees_spawned` 64 → 64, `draining_trees` 0 throughout (no respawn churn, so per-tree denominators are stable) |
| crawler uptime at window start | W0 `0:04`, W1 `0:05` — both windows ran on the process the settings Apply had just restarted, so lifetime counters ≈ window counters modulo the settle |
| fixed config (both windows) | `tree_get_peers_workers=18`, `tree_get_peers_timeout_ms=1500`, `tree_metadata_workers=10`, `tree_metadata_timeout_ms=60000`, `tree_tcp_connect_timeout_ms=2000`, `tree_bep51_workers=3`, `num_trees=64`, `udp_rate_limit_pps=3000`, `piece_idle_ms=0`, `fresh_peer_retries=1`, all patch-19 behaviour knobs `0` (`round_idle_ms`/`penalize_misses`/`slot_budget_ms`/`finish_on_peers`), `log_level=INFO` |
| raw inputs | `reports/w0.log` + `reports/w0.json` (21,930,767 B), `reports/w1.log` + `reports/w1.json` (21,934,416 B), under `/home/adam/.local/state/pi-dev/r/znbdh8/` |

### `log_level` DEBUG → INFO (applied before W0, held for both windows)

The triage baseline ran `log_level = DEBUG` and had written **4.6 GB** of logs (2,408,327,129 B live
`dht_crawler.log` + 2,214,139,183 B rotated `dht_crawler.log.1`), still growing at roughly **2.5 GB/h**; by
the time the change was applied the live file was ~3.4 GB (~5.6 GB total, parent reading). That is a disk-I/O
confound sitting inside every measurement window on a device whose crawler already carries a 480 k-row SQLite
DB with a large page cache, so the parent switched logging to **INFO before W0** (exit criterion 7) and kept
it there for both windows. Consequence for reading this record: the two arms are comparable to each other, and
**not** directly comparable to the patch-19 run's windows, which were measured at DEBUG.

### Measurement protocol (binding, from the plan's Assumption 8)

Six 30-minute windows, 5 s interval, fixed 64 trees, rate deltas only; every experiment paired with a
contemporaneous same-hour, same-build control differing in exactly one variable (W0/W1, W2/W3, W4/W5); 5-min
settle after every apply; the RECORD SET captured for every window; confounds stated whenever anything else
moved. The patch-19 run established why: a baseline and an "identical behaviour" run four hours apart differed
by 3.8 pp of yield with no code difference — **the DHT thins, so a baseline measured hours earlier is not a
control.**

### How each rate in this document is derived

`gp_probe.py` differences the crawler's **cumulative** counters host-side, so:

- `lookups/s` = Δ`supervisor.get_peers.lookups_total` / 1795.1 s (this counter is incremented once per
  `tree_lookup_peers()` call, i.e. it includes lookups started by the metadata fresh-peer retry path)
- `drain/s per tree` = least-squares slope of `infohash_queue_size` over runs where `discovery_paused` is true,
  negated — i.e. what the 18 get_peers workers actually consume
- `metadata/s` = Δ`aggregate.total_metadata_fetched` / span; `attempts/s` = Δ`aggregate.metadata_attempts_total` / span
- `R` = Δ`supervisor.metadata_failures.connect_total` / Δ`aggregate.metadata_attempts_total` (connect events are
  **per connection**, so R > 1 is normal)
- `mean lookup ms` = Δ(`mean_lookup_ms` × `lookups_total`) / Δ`lookups_total` — run-local, not lifetime

These are Δ-derived and are **not** reads of the new patch-20 last-interval keys; the two mechanisms are
independent, which is what lets criterion 1 and this record cross-check each other.

Per Assumption 2, the patch-20 glue (`supervisor.c` sampler + `http_api.c` emission) has no host harness — the
`stats_window` module is host-tested, the glue is device-validated only, and these windows are that validation
context.

---

## Stage 1 — the (A)/(B) discriminator: the get_peers drain is **latency-bound**

W0 doubles as the discriminator the plan required (exit criterion 4b), and it settles the question the task
prompt had answered the other way.

| quantity | W0 measurement | arithmetic |
|---|---:|---|
| per-tree drain (queue consumption while paused) | **3.107 /s** | least-squares over 98 paused runs / 53 trees (1,866 samples, ≥8 each); **198.9 /s** across 64 trees |
| worker-capacity model `1000 × tree_get_peers_workers / mean_lookup_ms` | **3.146 /s** | 1000 × 18 / 5722 = 3.146; **201.4 /s** across 64 trees |
| measured ÷ model | **98.8 %** | 3.107 / 3.146 — **equality** (1.2 % short, i.e. the workers are busy essentially 100 % of the time) |
| idle exits vs deadline exits (window Δ) | **0 / 268,355** | 100 % of lookups end at the 6 s deadline; idle share of exits 0.0 % (`round_idle_ms`=0, `penalize_misses`=0) |
| unanswered slots (window Δ) | 984,586 | 2.28 per lookup — the slots that never got an answer |
| Δ`udp_throttled_sends_total` / Δ`udp_sends_total` | **9,177 / 4,509,710 = 0.203 %** | the UDP token bucket is not the cap (pps 2,512 of a 3,000 cap) |

**Verdict: latency-bound, not discovery-bound.** Equality between the drain and `workers / mean_lookup` means
each of the 18 per-tree get_peers workers is busy for the full duration of every lookup and the queue is not
starving them — so finishing lookups sooner cannot create more lookups, and more workers would raise the drain
only linearly while pushing pps into the cap. The corroboration is independent: `idle_exits_total` is **0** in
both arms (nothing ever exits early), every lookup burns the whole deadline, and throttling is 0.2 % of sends.

This **reverses the task prompt's "discovery-bound, not latency-bound" claim**, and it disposes of the
233-vs-164 lookups/s puzzle in the same stroke (see the next section).

### Why 233 and 164 were both "right" — and why the plan's comparator needs one correction

The plan's RECORD SET defines per-tree lookups/s as Δ per-tree `get_peers.lookups_total` / 1795 s. Measured that
way W0 is **3.76 /s per tree**, which *exceeds* the 3.146 /s worker-capacity bound — impossible if those 18
workers were the only callers. They are not: `tree_lookup_peers()` increments `tree->gp_lookups` for **every**
caller, and it has two (`get_peers_worker_func` and `tree_metadata_worker_func`, the fresh-peer retry path).
The window closes to within 0.3 % when the two populations are separated:

| W0 component | lookups/s | × 1795.1 s |
|---|---:|---:|
| get_peers workers (drain 3.107/s × 64 trees) | 198.9 | 356,997 |
| metadata fresh-peer retry path (Δ`gp.from_retry_total` 10,407 → 84,188) | 41.1 | 73,781 |
| **sum** | **240.0** | **430,778** |
| measured Δ`lookups_total` | **240.6** | **431,928** |
| slack | 0.6 | 1,150 (**0.27 %**) |

W1 closes the same way (195.5 + 43.3 = 238.8 vs 239.2 measured, 0.16 % slack). So the 233 figure was a
**total-lookups** number and the 164 figure was a **worker-drain** number derived from a longer `mean_lookup`
(7,044 ms) — two different quantities that were never comparable, and neither matches this build's
contemporaneous pair (240.6 total / 198.9 worker-drain at 5,722 ms). **For Stages 3–5 the discriminator must use
the queue drain, or total lookups minus Δ`gp.from_retry_total`** — the raw `lookups_total` delta over-states the
worker population by ~20 % on this device.

---

## Stage 2 — `tree_parallel_peers` 2 vs 4, same hour, same build, one variable

Both windows echo the knob they ran (`supervisor.metadata.parallel_peers` **2** in W0, **4** in W1) and are
otherwise identical in every echoed knob. Mechanism: `pp` bounds concurrent TCP connects per metadata attempt
(the window fill and refill loops in `tree_metadata.c`), clamp 1..4, so per-tree in-flight TCP =
`metadata_workers × pp` = 10 × {2, 4} = {20, 40}.

### RECORD SET — full, both arms

| metric | **W0 (pp=2)** | **W1 (pp=4)** | W1 vs W0 | source |
|---|---:|---:|---:|---|
| lookups/s (all callers) | **240.6** | **239.2** | −0.6 % | Δ`supervisor.get_peers.lookups_total`/span |
| per-tree drain /s (get_peers workers) | **3.107** | **3.054** | −1.7 % | queue slope while paused |
| per-tree fill /s | 3.587 | 3.797 | +5.9 % | net rise while unpaused + drain |
| fill : drain | 1.15 : 1 | 1.24 : 1 | — | buffer pressure |
| `discovery_paused` fraction | **8.2 %** | **15.3 %** | +7.1 pp | share of (tree, sample) pairs paused |
| peer yield % (window) | **33.31** | **33.79** | +0.48 pp | Δ`lookups_with_peers`/Δ`lookups_total` |
| peer yield % (lifetime echo) | 33.81 | 33.32 | — | `aggregate.get_peers_yield_percent` |
| peer entries/s | 80.16 | 80.81 | +0.8 % | Δ peer entries |
| **metadata/s** | **6.834** | **6.408** | **−6.2 %** | Δ`total_metadata_fetched`/span |
| metadata fetched (window Δ) | 12,268 | 11,502 | −6.2 % | `aggregate.total_metadata_fetched` |
| attempts/s | **47.23** | **49.06** | +3.9 % | Δ`metadata_attempts_total`/span |
| window success % | **14.47** | **13.06** | −1.4 pp | fetched/attempts in window |
| first strike /s | 24.70 | 24.06 | −2.6 % | first-strike failures/s |
| packets per lookup | 8.83 | 8.50 | −3.7 % | Δ`packets_total`/Δ`lookups_total` |
| mean rounds per lookup | 1.164 | 1.132 | — | 1 + Δ`rounds_total`/Δ`lookups_total` |
| mean lookup ms | **5,722** | **5,857** | +2.4 % | run-local, not lifetime |
| pps | **2,512** | **2,412** | −4.0 % | Δ`udp_sends_total`/span, cap 3,000 |
| Δ`udp_throttled_sends_total` | 9,177 | 17,141 | +87 % | window delta |
| throttle share of sends | 0.203 % | 0.396 % | +0.19 pp | throttled Δ / sends Δ |
| dispatcher drops /s | 75.95 | 87.74 | +15.5 % | Δ`dispatcher.dropped_total`/span; `queue_full_drops` 0.00/s in both |
| peers queue mean / max | 114.5 / 319 | 15.6 / 180 | — | across trees |
| **active conns / tree** | **10.60** | **11.28** | +6.4 % | mean `active_connections` |
| infohash pushes /s per tree | 3.176 | 3.080 | −3.0 % | duplicates rejected 0.0 % |
| idle exits / deadline exits | 0 / 268,355 | 0 / 270,578 | — | window deltas |
| unanswered slots | 984,586 | 1,128,026 | +14.6 % | window delta |
| **stall + deadline share** | **0.91 %** | **0.80 %** | −0.11 pp | of the failure mass |
| connect share | 83.55 % | 83.30 % | −0.25 pp | 405,152 / 427,348 events |
| handshake share | 15.37 % | 15.73 % | +0.36 pp | 74,548 / 80,692 events |
| failure mass (window Δ) | 484,945 | 513,051 | +5.8 % | sum of the eight counters |
| failed attempts (window Δ) | 72,521 | 76,561 | +5.6 % | attempts that failed |
| **R = Δconnect/Δattempts** | **4.778** | **4.853** | +0.08 | connect events per attempt |
| **RSS** (start → end, sampled peak) | 1,716,240 → **2,144,096** KiB (2,094 MB), peak 2,094 MB | 1,761,732 → **2,195,980** KiB (2,144 MB), peak 2,202,180 KiB | +50 MB at end | `/proc/<pid>` VmRSS, 28 samples |
| **threads** (min–max over window) | 2,380 – 2,383 | 2,380 – 2,383 | 0 | `ls /proc/<pid>/task \| wc -l` |
| **temperature** | *not sampled in-window* | *not sampled in-window* | — | probe samples threads/RSS only; last measured 38.9 °C at triage (charging, level 100) |
| `torrents_indexed` (start → end) | 480,751 → 487,255 (**+6,504, +1.35 %**) | 488,826 → 494,929 (**+6,103, +1.25 %**) | seam +1,571 rows | DB growth during the window |
| `files_indexed` (window Δ) | +224,745 | +162,411 | — | 15.33 M → 15.55 M / 15.59 M → 15.75 M |

Failure distribution, all eight counters, both arms (`supervisor.metadata_failures`, window deltas):

| counter | W0 (pp=2) | share | W1 (pp=4) | share |
|---|---:|---:|---:|---:|
| `connect_total` | 405,152 | 83.55 % | 427,348 | 83.30 % |
| `handshake_total` | 74,548 | 15.37 % | 80,692 | 15.73 % |
| `stall_total` | 3,456 | 0.71 % | 3,682 | 0.72 % |
| `hash_total` | 590 | 0.12 % | 715 | 0.14 % |
| `deadline_total` | 964 | 0.20 % | 409 | 0.08 % |
| `ut_reject_total` | 145 | 0.03 % | 141 | 0.03 % |
| `other_total` | 76 | 0.02 % | 64 | 0.01 % |
| `size_total` | 14 | 0.00 % | 0 | 0.00 % |
| **sum** | **484,945** | 100 % | **513,051** | 100 % |

### Winner rule (plan step 2, applied to these two windows)

> winner = higher `metadata/s`; if `metadata/s` differs by <5 % (relative), treat as a tie and keep 2.

| step | arithmetic |
|---|---|
| W0 (pp=2) `metadata/s` | **6.834** |
| W1 (pp=4) `metadata/s` | **6.408** |
| relative difference, base = higher arm | (6.834 − 6.408) / 6.834 = **6.24 %** |
| relative difference, base = lower arm | (6.834 − 6.408) / 6.408 = **6.65 %** |
| tie band | <5 % relative — **exceeded on either denominator** |
| **winner** | **`tree_parallel_peers = 2`** |

The device was returned to `tree_parallel_peers = 2` (parent, plan step 3) and `/stats` echoes
`supervisor.metadata.parallel_peers = 2`. Because the winner is the already-shipped legacy value, **no template
change and no `CrawlerConfigTest` change is needed** — `app/src/main/assets/dht_crawler/config.ini.default`
stays at `tree_parallel_peers = 2` (the value it already carries) and no Kotlin test asserts a new number. This
stage is docs-only in the repo.

**Robustness of the verdict.** The 6.2 % gap is a single-pair difference and would be thin on its own; it is
corroborated by the mechanism the two windows expose, which points the same way:

- pp=4 raised `active_connections`/tree 10.60 → 11.28 (+6.4 %) and attempts/s 47.23 → 49.06 (+3.9 %) —
  more concurrent connections and more attempts were bought;
- those attempts hit the same wall: connect events 405,152 → 427,348 (+5.5 %), R 4.778 → 4.853, window success
  14.47 % → 13.06 % — **more connects per attempt, no more fetches**;
- metadata/s fell 6.2 % and dispatcher drops rose 75.95 → 87.74/s while lookups/s, drain and yield were flat
  (−0.6 %, −1.7 %, +0.48 pp);
- nothing in the get_peers path moved (idle exits 0 in both, deadline exits 268k → 271k, pps 2,512 → 2,412),
  which is what the Stage-1 discriminator predicts: pp is a metadata-side knob and cannot raise the drain;
- the metadata **peer backlog** did empty faster (mean peers queue 114.5 → 15.6, max 319 → 180), which is
  exactly what doubling concurrent connects should do — and it bought no fetches, i.e. the backlog was never
  the constraint. Peer liveness was.

Under a tie the shipped value would also be 2, so the decision does not depend on which denominator the 5 %
band is read against.

### Confounds (stated, not waved away)

1. **Temperature is unmeasured in-window.** The probe samples threads and RSS, not `dumpsys battery`; the last
   reading is the triage baseline's 38.9 °C (charging, level 100). Both windows are adjacent 30-minute blocks
   on the same build, so a thermal drift between them is possible and cannot be excluded from the record.
2. **`torrents_indexed` drifted continuously across both windows** — W0 480,751 → 487,255, W1 488,826 →
   494,929 (+1.35 % / +1.25 % inside each window, +1,571 rows across the 7-minute seam). The DB was strictly
   larger in W1, and the same rows were not re-crawled; both arms sit in a ~480–495 k-row band, so this is a
   small but real asymmetry whose direction on `metadata/s` is not known and is not corrected for.
3. **`discovery_paused` rose 8.2 % → 15.3 %.** This is *expected*, not a regression: fill rose 5.9 % while the
   drain fell 1.7 %, and under the 90 %/33 % hysteresis `paused ≈ (fill − drain) / fill`. Paused is
   backpressure on a queue that drains slower than it fills — consistent with the latency-bound drain above.
4. **RSS rose ~50 MB in W1** (2,094 → 2,144 MB at window end, peak 2,202,180 KiB). Expected: pp=4 doubles the
   in-flight TCP set per tree (10 × 4 = 40 vs 20). Thread count is unchanged (2,380–2,383) — pp spawns no threads.
5. **The two arms are not comparable to the patch-19 windows** because both ran at `log_level=INFO` while
   patch-19's ran at DEBUG (4.6 GB / 4 h of log I/O).

### What this reverses

- **The Stage-2 rationale itself.** The task prompt justified the A/B with LEGACY **6.75** metadata/s vs
  SHIPPED (pp=2) **5.12** metadata/s — a 31.8 % advantage for pp=4 — taken from two different windows of the
  patch-19 run, which the plan already called confounded by ~65 min of DHT drift. This same-hour, same-build
  pair measures the **opposite direction** (6.834 at pp=2 vs 6.408 at pp=4). `tree_parallel_peers = 2` is
  therefore confirmed **on measurement**, not on the assumption that the shipped value must have been right.
- **The task prompt's "discovery-bound, not latency-bound" premise** — see the Stage-1 discriminator: the drain
  equals `1000 × 18 / mean_lookup_ms` to within 1.2 %, idle exits are 0, and the UDP bucket is 0.2 % throttled.
  Discovery fill (3.59/s per tree) is *above* the drain (3.11/s), which is queue pressure, not a discovery cap.
- **No improvement is claimed anywhere in this stage.** W0 is a control window, not a change: it was measured on
  an unmodified build with the knob at its shipped value. Its `metadata/s` (6.83) is level with patch-19's
  LEGACY 6.75 and above its SHIPPED 5.12, but those are different windows on different builds and different
  log levels — the honest statement is that this pair bounds the *difference between two settings*, not the
  effect of anything shipped.

---

## Later stages — append below

<!-- Stages 3–5 append their sections below this marker: W2/W3 (metadata peer blacklist TTL 0 vs 600),
     W4/W5 (BEP51 workers 3 vs 12), the failure-map counters, the BEP51 funnel accounting and the final
     exit-criteria roll-up. Keep the RECORD SET columns above so every window stays comparable, and remember
     the Stage-1 correction: use the queue drain (or total lookups minus Δ`gp.from_retry_total`) for the
     worker-capacity comparator. -->
