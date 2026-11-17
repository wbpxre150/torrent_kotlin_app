# get_peers / metadata — window record (patches 20–23)

Status: **complete — all seven windows measured, every verdict in.** This is the run's authoritative
measurement record: W0/W1 (`tree_parallel_peers` A/B), W2 (superseded history), W2b/W3 (peer-fail map TTL),
W4/W5 (BEP51 funnel), then the final seven-window table, the exit-criteria roll-up and the dead-ends table at
the end. Every number in it is a rate **delta over one 30-minute window** on the device — never a lifetime
counter, never an estimate — and every experiment is paired with a contemporaneous same-hour, same-build
control that differs in exactly one variable.

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

## Later stages

<!-- Sections below, in execution order: the Stage-6 corrective (map resize + W2b/W3), Stage 4 (patch 23, the
     BEP51 funnel + W4/W5), then the final seven-window table, the exit-criteria roll-up, the dead-ends table
     and the final device config. Keep the RECORD SET columns above so every window stays comparable, and
     remember the Stage-1 correction: use the queue drain (or total lookups minus Δ`gp.from_retry_total`) for
     the worker-capacity comparator. -->

---

## Stage 6 (corrective) — the peer-fail map was capacity-churn-bound, not TTL-bound

Patch 22 (`patches/meta-peer-fail-capacity.patch`, 22nd slot; patches 20/21 are not amended). One constant
plus comments: `META_PEER_FAIL_CAPACITY` 8192 → 131,072. No key change (`tree_metadata_peer_blacklist_ttl_sec`
keeps its compiled default `0`), no `CrawlerKeys`/template change, no `gp_probe.py` change, no `/stats` schema
change (`metadata_peer_fail.capacity` echoes the constant), no change to the eight `metadata_failures`
counters or the count-once discipline.

### The blocker (parent-measured, `reports/w3-sizing-blocker.md`)

On the patch-21 build at `tree_metadata_peer_blacklist_ttl_sec = 600`, the 8192-slot map admitted
~110–133 **NEW distinct** connect-failure addresses per second. The load-factor clear fires at 3/4 load
(6,144), so the whole table was wiped every ~50–70 s: `clears_total` went 1 → 2 → 3 inside 90 s, `held`
sawtoothed 200 → 4,190 → 2,031 → 5,577, and `expired_total` stayed **0 at every sample**. Entries were dying
by capacity clear, never by TTL, so a W3 window on that build would have measured table churn and not the
Stage-3 hypothesis. The map's own counters proved it, which is exactly what patch 21's instrumentation was
for.

### The sizing decision and its arithmetic (option (a), capacity 131,072)

The rule, stated so the next reader can re-derive it: **the 3/4-load clear threshold must hold the whole TTL
working set without a clear.**

| quantity | value |
|---|---|
| measured NEW distinct fail addresses/s | ~110–133 |
| TTL under test | 600 s |
| worst-case TTL working set | 66,000–79,800 entries |
| chosen capacity | 131,072 slots (power of two) |
| 3/4-load clear threshold | **98,304** (> worst case, ~23 % margin) |
| footprint | 131,072 × 32 B = **4 MiB** ≈ 0.2 % of the measured 2.04 GB crawler RSS |

Rejected alternatives: 65,536 slots (clear at 49,152) and the old 16,384 fallback (12,288) both still clear
below the measured working set, so TTL could never dominate eviction; option (c) — shortening the TTL to fit
8192 slots — needs ≤ 45 s, below the key's 60 s clamp floor and shorter than the ~95 s discovery-pause
episodes, so it would not test "remember a dead peer for ten minutes". No new thread, one mutex unchanged,
the slot-size static assertion still holds at 32 B.

Residual bound: if the sustained new-address rate exceeds ~164/s (98,304 / 600 s), clears recur and W3 is
INVALID → recorded NOT-IMPROVED (churn) per exit criterion 3, with **no further capacity escalation** in this
run.

### What W3 now tests

With capacity churn removed, W3 measures the actual Stage-3 hypothesis: a stable, TTL-aged working set of
~66–80 k dead addresses consulted at window selection, with entries dying by 600 s expiry (and retried once
after expiry) instead of by table wipes. Expected observable signature: `held` ramps for ~10 min then
plateaus (no sawtooth below ~1,000), `clears_total` stays 0 (≤2 tolerated), `expired_total` becomes > 0 after
the first TTL horizon, and skips/s rises well above the amnesiac map's 63–99/s.

### Install-time validation (patch-22 build, not a measurement window)

1. **ttl = 0 (shipped default), after install:** `metadata_peer_fail` = `{enabled:false, capacity:0,
   held:0, ttl_sec:0, clears_total:0, expired_total:0, metadata_peers_skipped_total:0,
   metadata_connect_fail_addresses_total:0}` — fail-open unchanged.
2. **ttl = 600, 6 minutes of `/stats`:** `capacity` echoed **131072**; `held` 707 → 2,151 → 3,618 → 4,933 →
   **6,413** → 7,976 → 9,560 → 11,198 → 12,933 → 14,798 → 16,581 → 18,333 → 20,197 → 21,917 → 25,010 →
   35,823 → **44,296**, i.e. it crossed the old 6,144 clear threshold and kept climbing straight past the old
   capacity; `clears_total` **0** at every sample; `metadata_peers_skipped_total` 109 → 65,398 (skips/s > 0
   throughout); `[ERROR]` lines in `dht_crawler.log` **0**. Skip-guard check: `first_strike_failures` per
   1,000 `metadata_attempts_total` measured **483.2** at ttl=600 vs **480.8** at the ttl=0 control
   (+0.5 % — no spike, so the skip-to-empty guard is intact and no infohash was starved).
3. **ttl reverted to 0 and applied:** `enabled:false`, `capacity:0`, every counter 0 — the device is clean for
   W2b. Final device config: `log_level=INFO`, `num_trees=64`, `tree_parallel_peers=2`,
   `tree_bep51_workers=3`, `tree_get_peers_workers=18`, `tree_metadata_workers=10`,
   `udp_rate_limit_pps=3000`, `tree_metadata_timeout_ms=60000`, patch-19 knobs all 0,
   `tree_metadata_peer_blacklist_ttl_sec=0`.

### W2b / W3 — table and verdict

Both windows ran on the **resized** (131,072-slot) build, same hour, one variable
(`tree_metadata_peer_blacklist_ttl_sec` 0 → 600, an advanced-override key), with `tree_parallel_peers` 2,
64 trees, pps cap 3,000 and `log_level=INFO`. **W2b** 12:31:23 → 13:01:23, **W3** 13:07:28 → 13:37:28;
6 min 5 s seam = Apply restart + 5 min settle. 360 usable / 0 skipped samples each, span 1795.1 s.

**Validity gate first (exit criterion 3):** Δ`expired_total` **4,071 > 0** and Δ`clears_total` **1 ≤ 2** — the
map aged entries out by TTL instead of by a capacity clear, so the window is VALID and the verdict below is
admissible. This is the gate the 8192-slot attempt failed (`reports/w3-sizing-blocker.md`).

| metric | **W2b (ttl=0, control)** | **W3 (ttl=600)** | W3 vs W2b | source |
|---|---:|---:|---:|---|
| `metadata_peer_fail.enabled` | false | true | — | `/stats` echo |
| `capacity` | 0 | 131,072 | — | patch 22 constant |
| `held` (end of window) | 0 | 42,469 | — | map occupancy |
| Δ`clears_total` | 0 | **1** | — | validity gate ≤ 2 |
| Δ`expired_total` | 0 | **4,071** | — | validity gate > 0 |
| Δ`metadata_peers_skipped_total` | 0 | **230,086** | — | skipped SLOTS |
| skips per attempt | 0 | **2.44** | — | 230,086 / 94,423 |
| Δ`metadata_connect_fail_addresses_total` | 0 | 153,465 | — | NEW distinct addresses |
| attempts (window Δ) | 82,439 | 94,423 | +14.5 % | Δ`metadata_attempts_total` |
| **R = Δconnect/Δattempts** | **4.665** | **2.525** | **−45.9 %** | 384,573 / 82,439 → 238,374 / 94,423 |
| **metadata/s** | **6.128** | **6.706** | **+9.4 %** | Δ`total_metadata_fetched`/span |
| attempts/s | 45.93 | 52.60 | +14.5 % | Δ/span |
| window success % | 13.34 | 12.75 | −0.59 pp | metadata/s ÷ attempts/s |
| peer yield % (window) | 31.94 | **36.19** | **+4.25 pp** | Δ`lookups_with_peers`/Δ`lookups_total` |
| lookups/s (all callers) | 236.7 | 243.9 | +3.0 % | Δ`supervisor.get_peers.lookups_total`/span |
| per-tree drain /s | 3.051 | 3.075 | +0.8 % | queue slope while paused |
| per-tree fill /s | 3.825 | 3.740 | −2.2 % | net rise while unpaused + drain |
| `discovery_paused` fraction | 16.0 % | 13.4 % | −2.6 pp | share of (tree, sample) pairs paused |
| **active conns / tree** | **10.03** | **7.13** | **−28.9 %** | mean `active_connections` |
| peers queue mean | 68.1 | 23.0 | — | across trees |
| failure mass (window Δ) | 462,119 | **329,453** | **−28.7 %** | sum of the eight counters |
| failed attempts (window Δ) | 71,439 | 82,385 | +15.3 % | attempts that failed |
| connect share | **83.22 %** | **72.35 %** | **−10.87 pp** | 384,573 / 238,374 events |
| handshake share | **15.68 %** | **25.84 %** | **+10.16 pp** | 72,447 / 85,147 events |
| stall share | 0.76 % | 1.32 % | +0.56 pp | 3,499 / 4,340 |
| deadline share | 0.17 % | 0.15 % | −0.02 pp | 807 / 481 |
| hash share | 0.135 % | 0.258 % | +0.12 pp | 625 / 849 |
| ut_reject / other / size | 0.021 / 0.011 / 0.004 % | 0.051 / 0.027 / 0.002 % | — | small counters |
| mean lookup ms | 5,838 | 5,807 | −0.5 % | run-local |
| pps | 2,407 | 2,487 | +3.3 % | cap 3,000 |
| throttle share of sends | 0.531 % | 0.514 % | −0.02 pp | 22,920 / 4,320,104 → 22,942 / 4,463,561 |
| threads (min–max) | 2,380–2,383 | 2,380–2,383 | 0 | `ls /proc/<pid>/task \| wc -l` |
| RSS (end of window) | 2,163,032 KiB (2,112 MB) | 2,205,664 KiB (2,154 MB) | +42 MB | `/proc/<pid>` VmRSS |
| **temperature** | *not sampled in-window* | *not sampled in-window* | — | probe samples threads/RSS only; last reading 38.9 °C at triage |
| `torrents_indexed` | 515,818 → 521,817 | 522,819 → 529,223 | seam +1,002 rows | DB growth during the window |

**Verdict: PASS.** Criterion 3 requires that connect events per attempt fall measurably against a same-hour
ttl-0 control and that metadata/s rise — or that the stage be recorded NOT-IMPROVED with the counter
evidence. Every condition holds:

- **R fell 4.665 → 2.525 (−45.9 %)**: 2.44 dead addresses were stepped over before any TCP connect was opened,
  so the connect-event mass dropped 384,573 → 238,374.
- **metadata/s rose 6.128 → 6.706 (+9.4 %)** on the same build in the same hour, with the drain flat
  (3.051 → 3.075 /s/tree) — the gain is on the metadata side, where the map acts.
- **Δskips 230,086 > 0** proves the map was consulted at selection (a zero skip count would have meant a
  selection bug, not a negative result).
- **The distribution SHIFTED, not merely shrank**: connect 83.2 % → 72.4 % while handshake rose 15.7 % →
  25.8 % and stall 0.76 % → 1.32 %. The attempts now reach further up the TCP/Torrent handshake before
  failing, which is what removing unreachable addresses should do. A distribution that stayed ~83 % connect
  with skips > 0 would have meant skipping does not help liveness.

The device stays at `tree_metadata_peer_blacklist_ttl_sec = 600`. The **template still ships `0`** (the
fail-open guardrail of plan Assumption 4), so the winning value lives on the device only — an install on a
fresh device starts with the map off.

Side effects, all in the same direction, none of them a target: total failure mass 462,119 → 329,453
(−28.7 %) while failed *attempts* rose 71,439 → 82,385 (+15.3 %) — fewer failure events per attempt, more
attempts getting further; yield 31.94 % → 36.19 % (+4.25 pp); `active_connections` per tree 10.03 → 7.13
(fewer wasted sockets, −28.9 %); peers queue mean 68.1 → 23.0. Window success % fell slightly (13.34 →
12.75) because attempts rose faster than fetches — the same 11–12 % fetch ceiling the whole run measured.

**Sizing correction (recorded, not hidden).** The first attempt at this A/B ran on the 8192-slot map and was
**capacity-churn-bound**: at ~110–133 NEW distinct fail addresses/s the 3/4-load clear fired every ~50–70 s
(`clears_total` 1 → 2 → 3 inside 90 s, `held` sawtoothing 200 → 4,190 → 2,031 → 5,577) and `expired_total`
stayed **0**, so the TTL mechanism never ran and no verdict was possible. Patch 22 resized the map to 131,072
slots and **W2b replaced the stale W2 as the control**. Why that mattered, in one line: W2 (8192 build,
ttl=0) measured **6.63** metadata/s against W2b's **6.13** on identical settings, so reusing W2 as the control
would have turned the +9.4 % gain into **+1.2 %**. W2 stays in this file as history, not as a control.

**R denominator note.** R here is Δ`connect_total` / Δ`metadata_attempts_total`, both taken as window deltas
(last sample − first sample) — the same convention the Stage-2 table uses (W0: 405,152 / 84,789 = 4.778).
Dividing by the *last lifetime* `metadata_attempts_total` instead (101,207 / 105,495) yields 3.80 → 2.26: the
same direction and the same verdict, but it mixes a window numerator with a since-process-start denominator,
so it is not used anywhere in this record.

---

## Stage 4 — patch 23: BEP51 query + dedup-funnel instrumentation, and the ceiling investigation

Patch 23 (`patches/bep51-funnel-stats.patch`, 23rd and last slot; patches 20/21/22 are not amended). **No
behaviour change, no config key, no existing `/stats` key changes meaning** — eight per-tree atomics, their
supervisor fold, two new `/stats` objects and the probe support. Citations below are the **prepared** tree
(`app/build/native/src/dht_crawler/`, i.e. after patch 23), re-anchored by symbol; the pristine submodule has
no patch content and different line numbers.

### What shipped

| counter (`trees[].bep51.*`, summed in `supervisor.bep51.*`) | bumped at |
|---|---|
| `queries_sent_total` | the `sample_infohashes` send — `thread_tree.c:504-509` |
| `responses_received_total` | the `rc == 0` branch of the 1000 ms wait — `thread_tree.c:525-528` |
| `timeouts_total` | the `else` branch of the same wait — `thread_tree.c:533-535` |
| `samples_offered_total` | every infohash examined in a response — `thread_tree.c:568-569` |
| `bloom_rejected_total` | shared-bloom hit — `thread_tree.c:571-574` |
| `inflight_refused_total` | `inflight_set_claim()` refusal — `thread_tree.c:585-588` |
| `queue_full_total` | `tree_infohash_queue_try_push()` refused (claim released) — `thread_tree.c:601-606` |
| `samples_pushed_total` | push accepted — `thread_tree.c:591-592` |

The four gates are mutually exclusive (every examined sample leaves the loop through exactly one of them), so
per window `offered = bloom + inflight + queue_full + pushed`. Zero-init in `thread_tree_create`
(`thread_tree.c:1650-1658`), cumulative fold beside the existing `gp_*` fold in the respawn path
(`supervisor.c:1347-1354` loads, `:1385-1392` adds), summed accessor `supervisor_bep51_stats()`
(`supervisor.c:1737`), per-tree object beside `trees[].get_peers` (`http_api.c:1424-1445`), supervisor object
beside `supervisor.get_peers` (`http_api.c:1086-1105`). The three worker locals (`queries_sent`,
`responses_received`, `timeouts`) are kept unchanged as per-worker **log-throttle cursors**, so the DEBUG line
sequence is byte-identical to before; only the published counters are new.

`tools/crawler/gp_probe.py`: `BEP51_KEYS`, `_bep51_norm()`, both blocks parsed (`supervisor.bep51`,
`trees[].bep51`), and three new table rows — `BEP51 queries /s per tree` (with responses/query and timeouts),
`BEP51 samples offered /s per tree`, `BEP51 funnel (window)` (accepted %, the bloom / in-flight / queue-full
shares of offered, and the identity gap). A synthetic-body check of the derivation prints an identity gap of
**0.00 %** and the expected shares, so a blank column in a window report is now impossible.

**Host-test decision (explicit).** No host test for this stage, per plan Assumption 2 and the stage text:
`thread_tree.c` / `http_api.c` have no test harness, and the change is additive atomic increments plus cJSON
emission. Factoring the funnel into a pure module was considered and rejected — the gates *are* calls into
`bloom_filter_check()`, `inflight_set_claim()` and `tree_infohash_queue_try_push()`, so a pure accounting
module would have to re-implement the gate decision (or the worker loop would have to hand its control flow
to it), which is exactly the behaviour change this patch forbids. The identity is therefore validated on
device (below), not faked in a test. The existing 13 host test targets still pass unchanged.

### (i) RULED OUT BY CODE — no window spent

**Lead: "the BEP51 cache is full (50,000/50,000), so submission is throttled."** False, by construction.

- The cache is **one supervisor-global** object (`supervisor.c`, `sup->bep51_cache`), not one per tree: 50,000
  nodes shared across 64 trees ≈ 781/tree. `/stats` already publishes it as the top-level `bep51_cache` block,
  beside `capacity` 50,000 and `submit_percent` 17.
- It is **written on the response path, admission-sampled**: `if (rand() % 100 < sup->bep51_cache_submit_percent)`
  then `bep51_cache_add_node(...)` — `thread_tree.c:556-565`. `submit_percent` is a **sampling rate for
  admission**, not a throttle: it decides how many responders get remembered for the *next cold start*.
- It is **read only at warm-start**: `bep51_cache_get_random()` appears exactly twice in the tree —
  `thread_tree.c:1467` (bootstrap) and `refresh_thread.c:349` (refresh warm start). There is **no read of the
  cache anywhere between node-pick and `sample_infohashes` send** (`thread_tree.c:459-509`).
- **Full ⇒ FIFO evict-and-insert, never a reject**: `if (cache->count >= cache->capacity) { … HASH_DEL … }`
  then insert (`bep51_cache.c:188-203`); `count` never exceeds `capacity` and the caller ignores the return
  value. A full cache cannot drop a query, a response or a sample.
- The prompt's `tree_bep51_cache_capacity` key **does not exist**. The real keys are
  `bep51_cache_capacity` = 50000 and `bep51_cache_submit_percent` = 17 (device `config.ini`, verified).

**Lead: "64 partitions re-query the same nodes / the trees over-query."** The overlap is real; the
over-querying is not.

- Partitioning sets **only the tree's own node ID**: `keyspace_generate_node_id()`
  (`keyspace.c:28`, centre-bit assignment at `70-109`) writes a deterministic ID at the partition centre.
- Routing tables are **global**: `find_node` workers query `generate_random_target()` across the whole 160-bit
  space (`thread_tree.c:94-265`), and every responder plus up to 256 returned nodes is added
  (`tree_protocol.c:697-711`). The 64 trees therefore sample the same node population.
- Node selection is a **capable-only reservoir plus a cooldown**: `tree_routing_get_random_bep51_nodes()`
  (`tree_routing.c:615-680`) requires `BEP51_CAPABLE` and `now - last_queried >= bep51_node_cooldown_sec`
  (shipped 3 s), then stamps `last_queried`. At the shipped rate that is ≈1 re-query per node per ~130 s —
  far above the cooldown, so per-node over-querying is not occurring.
- `trees[].bep51_nodes_cached` counts **cache admissions**, not distinct nodes queried; it must not be read as
  "50,000 nodes per tree".

**Lead: "the 5 ms query interval is throttling fill."** Arithmetically inert, no window spent: the cycle is
send → (RTT or the 1000 ms wait) → `usleep(5 ms)` (`thread_tree.c:518-520`, `thread_tree.c:609-612`), so the
interval is ~1 % of a cycle dominated by the response wait. The device measures **responses/query 0.952**
(below), i.e. most cycles are RTT-bound, which makes the interval an even smaller share.

### (ii) CORRECTED PREMISE

The task prompt's figures (10 BEP51 workers, 10 ms interval) are **not** what the device runs. Live
`config.ini`: **`tree_bep51_workers = 3`**, **`tree_bep51_query_interval_ms = 5`**, `num_trees = 64`,
`tree_get_peers_workers = 18`, `tree_metadata_workers = 10`, `tree_parallel_peers = 2`,
`udp_rate_limit_pps = 3000`, `log_level = INFO`, `tree_metadata_peer_blacklist_ttl_sec = 600`.

And `discovery_paused` is **backpressure, not starvation**: set at 90 % of `tree_infohash_queue_capacity`,
cleared at 33 % (`thread_tree.c:287-309`), sampled every 2 s. Under that sawtooth the paused duty cycle is
`paused ≈ (fill − drain) / fill` — independent of queue capacity. **A fill-only rise RAISES the paused
fraction**, so the prompt's criterion "fill must rise AND `discovery_paused` must fall" is self-contradictory
and is replaced by exit criterion 4 (the discriminator, below). The three same-build windows already show the
predicted monotonicity:

| window | fill /s/tree | paused (measured) | paused = (fill−drain)/fill |
|---|---|---|---|
| W0 | 3.587 | 8.2 % | 13.4 % |
| W3 | 3.740 | 13.4 % | 17.8 % |
| W2b | 3.825 | 16.0 % | 20.2 % |

Measured sits below the model in every window (a 2 s duty-cycle sample and least-squares fits on a sawtooth
are coarse), but the ordering is monotone in fill — which is the falsifiable part.

### (iii) THE DISCRIMINATOR — measured on W0 (same build, same hour as W1)

Per-tree get_peers drain versus the worker-capacity model `1000 × workers / mean_lookup_ms`:

```
W0: measured drain              3.107 lookups/s/tree   (queue slope while paused, 98 runs / 53 trees)
    worker-side lookups         3.117 /s/tree          (Δlookups 431,928 − Δfrom_retry 73,781 = 358,147,
                                                         / 64 trees / 1795.1 s)
    model 1000×18/5722 ms       3.146 /s/tree
    → 99.1 % of the model, 100.3 % of the drain fit    ⇒ EQUALITY ⇒ latency-bound drain
```

Corroboration, same window: **idle exits 0 vs deadline exits 268,355** (idle share 0.0 %), `round_idle_ms` /
`slot_budget_ms` / `finish_on_peers` all 0, `mean_lookup_ms` 5722 **> the 6000 ms deadline**, and the UDP
bucket is not the cause — throttled acquires 9,177 against 2,512 pps × 1795.1 s = 4.51 M sends, i.e. a
**0.20 % throttle ratio**, so the bucket binds only on bursts (burst = 128 tokens). The bucket is ruled out as
the ceiling; `udp_rate_limit_pps` is forbidden anyway (exit criterion 6).

**The `gp_lookups` double-counting correction (Risk 1, now closed).** `supervisor.get_peers.lookups_total`
counts **two producers**: the 18 get_peers workers *and* the metadata fresh-peer retry path, which calls
`tree_lookup_peers()` again (`from_retry_total`, 17.1 % of W0's lookups). Against the raw total, W0 reads
3.760 lookups/s/tree — 19.5 % above the 3.146 model, which is the historical "233 vs 164" puzzle. Separating
the retry producer closes the arithmetic to **within 1 %** (99.1 % of the model; 0.3 % against the drain fit —
the same closure the task prompt quotes as 0.27 %). The reconciliation was done here, before naming a ceiling,
as Risk 1 requires: the retry lookups are real work but they are not worker capacity, and any future
worker-count argument must use `Δlookups − Δfrom_retry_total` (or the drain fit). The same separation on W2b
gives 3.066 vs 3.083 (99.5 %) and on W3 3.081 vs 3.100 (99.4 %), so the equality is not a W0 artefact.

### Install-time validation (patch-23 build, not a measurement window)

1. **Clean apply:** 23 patches, zero `.rej`, no "Hunk FAILED"; the applied tree matches the authored files
   byte-for-byte.
2. **Blocks present:** `trees[].bep51` on **64 / 64** trees and `supervisor.bep51` summed, in the existing cJSON
   style; every pre-existing key still present with unchanged meaning (`supervisor.get_peers`,
   `metadata_peer_fail`, `aggregate`, `inflight` untouched).
3. **2-minute curl pair** (`/tmp/s1.json` → `/tmp/s2.json`, span 126 s, 64 trees):

   | quantity | value |
   |---|---|
   | Δ`queries_sent_total` | 30,244 → **3.75 queries/s/tree** (3 workers ⇒ 0.80 s cycle) |
   | Δ`responses_received_total` / Δ`timeouts_total` | 28,787 / 1,449 ⇒ responses/query **0.952**, timeouts 4.8 % |
   | Δ`samples_offered_total` | 374,400 → **46.4 offered/s/tree** |
   | Δ`bloom_rejected_total` | 236,259 (63.10 % of offered) |
   | Δ`inflight_refused_total` | 109,145 (29.15 %) |
   | Δ`queue_full_total` | 0 (0.00 % — the queue never refused in this window) |
   | Δ`samples_pushed_total` | 28,996 (7.74 % accepted) → 3.60 pushed/s/tree |
   | **identity** | offered 374,400 vs gates 374,400 → **gap 0, +0.0000 %** (bar ±1 %) |
   | behaviour drift | lookups/s 241.6, yield 39.58 %, packets/lookup 8.76, metadata/s 7.08, pps 2,582 / cap 3,000, `mean_lookup_ms` 5,621 — inside W3's drift band (242-ish lookups/s, 36.2 %, 8.57, 6.71, 2,487, 5,807) |

   The funnel is now measurable and it says what the arithmetic predicted: **~92 % of the offered samples never
   reach the queue, and the two dedup gates (bloom 63 %, in-flight 29 %) own essentially all of it** — queue-full
   backpressure is 0 % in a normal window. `queries_sent/s/tree` 3.75 is inside the expected 3 workers ×
   ~1–2/s band (3 × 1.25/s at the measured 0.80 s cycle).
4. **Stage-3 state undisturbed:** `metadata_peer_fail` = `{enabled:true, capacity:131072, ttl_sec:600,
   clears_total:0, held:17,570}` and `config.ini` still `tree_metadata_peer_blacklist_ttl_sec = 600`. The device
   is left exactly as Stage 3 passed it.
5. **Submodule hygiene:** `git -C app/src/main/cpp/dht_crawler status --porcelain` shows only `?? build/` plus
   this commit's `patches/bep51-funnel-stats.patch`.

### (iv) W4 / W5 — table, ceiling statement and verdict

Both windows ran on the patch-23 build, same hour, one variable (`tree_bep51_workers` 3 → 12, a
**FORM-managed** key), with `tree_parallel_peers` 2 and `tree_metadata_peer_blacklist_ttl_sec` 600 at their
stage winners, 64 trees, pps cap 3,000 and `log_level=INFO`. **W4** 13:56:00 → 14:26:00, **W5** 14:32:03 →
15:02:04; 5 min 57 s seam = Apply restart + 5 min settle. 360 usable / 0 skipped samples each, span 1795.1 s,
`missing_features: []` — the probe saw both `bep51` blocks, so the funnel is measurable in both arms.

| metric | **W4 (BEP51 workers 3, control)** | **W5 (workers 12)** | W5 vs W4 | source |
|---|---:|---:|---:|---|
| `tree_bep51_workers` | 3 | 12 | — | form field, `config.ini` + log |
| queries sent (window Δ) | 402,714 | 578,465 | +43.6 % | Δ`supervisor.bep51.queries_sent_total` |
| **queries / s / tree** | **3.505** | **5.035** | **+43.6 %** | Δ / 64 trees / 1795.1 s |
| responses / query | 0.945 | 0.946 | +0.1 % | Δresponses / Δqueries |
| timeouts (window Δ) | 22,232 | 31,122 | +40.0 % | Δ`timeouts_total` |
| samples offered (window Δ) | 5,093,898 | 7,557,343 | +48.4 % | Δ`samples_offered_total` |
| **offered / s / tree** | **44.34** | **65.78** | **+48.4 %** | Δ / 64 / 1795.1 |
| **samples pushed (window Δ)** | **362,156** | **356,005** | **−1.7 %** | Δ`samples_pushed_total` |
| accepted share of offered | **7.11 %** | **4.71 %** | **−2.40 pp** | pushed / offered |
| bloom rejected | 3,355,070 (**65.86 %**) | 4,696,384 (**62.14 %**) | −3.72 pp | Δ`bloom_rejected_total` |
| in-flight refused | 1,376,672 (**27.03 %**) | 2,504,901 (**33.15 %**) | +6.12 pp | Δ`inflight_refused_total` |
| queue full | 0 (0.00 %) | 53 (0.0007 %) | — | Δ`queue_full_total` |
| **identity gap** | **0.00 %** | **0.00 %** | — | offered vs bloom+inflight+queue+pushed |
| per-tree fill /s | 3.728 | **7.137** | **+91.5 %** | queue fill fit |
| per-tree drain /s | 3.128 | **3.135** | **+0.2 %** | queue slope while paused |
| `discovery_paused` fraction | **10.9 %** | **68.1 %** | **+57.2 pp** | share of (tree, sample) pairs paused |
| paused, sawtooth model (fill−drain)/fill | 16.1 % | **56.1 %** | — | predicts a fill-only rise RAISES paused |
| lookups/s (all callers) | **249.1** | **246.4** | **−1.1 %** | Δ`lookups_total`/span |
| worker lookups /s / tree | 3.134 | 3.122 | −0.4 % | (Δlookups − Δ`from_retry`)/64/span |
| model `1000×18/mean_lookup_ms` | 3.170 | 3.154 | — | worker-capacity bound |
| measured ÷ model | **98.9 %** | **99.0 %** | — | equality ⇒ latency-bound drain |
| mean lookup ms | 5,678 | 5,707 | +0.5 % | run-local |
| idle exits / deadline exits | 0 / 257,421 | 0 / 260,945 | — | window deltas |
| **metadata/s** | **7.241** | **7.286** | **+0.6 %** | Δ`total_metadata_fetched`/span |
| attempts (window Δ) | 98,539 | 95,135 | −3.5 % | Δ`metadata_attempts_total` |
| attempts/s | 54.89 | 53.00 | −3.4 % | Δ/span |
| window success % | 13.19 | 13.75 | +0.56 pp | metadata/s ÷ attempts/s |
| R = Δconnect/Δattempts | 2.544 | 2.697 | +6.0 % | 250,692 / 256,590 events |
| peer yield % (window) | 38.11 | 37.36 | −0.75 pp | Δ`lookups_with_peers`/Δ`lookups_total` |
| failure mass (window Δ) | 349,776 | 357,188 | +2.1 % | sum of the eight counters |
| connect / handshake share | 71.7 % / 26.5 % | 71.8 % / 26.4 % | — | map still on (ttl 600) in both arms |
| active conns / tree | 7.617 | 7.679 | +0.8 % | mean `active_connections` |
| pps | 2,577 | 2,640 | +2.4 % | cap 3,000 |
| Δ`udp_throttled_sends_total` / Δsends | **6,762 / 4,625,123 = 0.146 %** | **0 / 4,738,710 = 0.000 %** | — | bucket not the cap |
| **threads** (min–max) | 2,380–2,383 | **2,956–2,959** | **+576** | 64 × 9 extra BEP51 workers, as predicted |
| RSS (end of window) | 2,119,252 KiB (2,070 MB) | 2,241,568 KiB (2,189 MB) | +122 MB | `/proc/<pid>` VmRSS |
| **temperature** | *not sampled in-window* | *not sampled in-window* | — | probe samples threads/RSS only; last reading 38.9 °C at triage |
| `torrents_indexed` | 532,903 → 539,862 | 540,902 → 547,739 | seam +1,040 rows | DB growth during the window |

**The ceiling, named.** Raising the BEP51 offer rate does **not** raise lookups/s. +43.6 % queries/s/tree and
+48.4 % offered/s/tree bought **−1.7 % pushed samples** and a flat rate (lookups/s −1.1 %, metadata/s +0.6 %,
both inside this run's drift band), because the accepted share fell from 7.11 % to 4.71 %: the bloom gate
refuses 62–66 % of offered samples and the in-flight gate 27–33 %, and more offers mostly multiply duplicates.
The extra fill raised `discovery_paused` from 10.9 % to 68.1 % — the 90 %/33 % sawtooth's prediction,
(fill − drain)/fill = (7.137 − 3.135) / 7.137 = **56.1 % predicted vs 68.1 % measured** — while the drain did
not budge (3.128 → 3.135 /s/tree, +0.2 %).

The binding term is the **deadline-dominated drain**, and four independent counters say so in the same
window: the per-tree worker drain equals `1000 × 18 / mean_lookup_ms` to within 1.1 % (98.9 % / 99.0 %),
`mean_lookup_ms` 5,707 ms sits against the 6,000 ms deadline, `idle_exits_total` is 0 against ~260 k
deadline exits per window, and the UDP bucket is not the cap (0.146 % / 0.000 % throttle ratio, pps 2,577–
2,640 of 3,000). Accepted samples are **novelty-bound** (bloom + in-flight gates), not offer-bound — which is
exactly what the funnel counters were built to measure. Every safe latency lever is already in the dead-ends
table below (round-idle inert, slot-budget / finish-on-peers −13 % metadata/s, miss-penalties −10.8 pp
yield) and more get_peers workers stays forbidden by exit criterion 6.

**Verdict: Stage 4 NOT-IMPROVED on rate; ceiling = deadline-dominated drain; discovery fill is not binding;
the funnel counters ship as the durable win.** The pre-registered ceiling statement below (kept verbatim)
predicted precisely this
("offered rises while lookups/s and metadata/s stay flat"), so the falsifiable prediction held and the stage
is a success under the measure-and-report rule, not a plan failure. The alternative branch — "offered flat at
4× the workers" — did **not** occur: offered rose 48 %, so the binding term is the drain, not novelty per
query.

**Revert confirmed:** `tree_bep51_workers` is back at **3** in `config.ini` (re-read after the revert and
again during Stage 5), and `metadata_peer_fail` is untouched by this stage (`enabled:true`,
`capacity:131072`, `ttl_sec:600`).

> **Ceiling statement (pre-registered before W4/W5; kept verbatim as the pre-registration of record, and
> CONFIRMED by the W4/W5 table above).** On the code and the three
> windows already measured, the binding term is **drain latency at the 6000 ms deadline**, not discovery fill:
> the drain equals the worker-capacity model to within 1 % (W0 99.1 %, W2b 99.5 %, W3 99.4 %), idle exits are
> 0 in every window while deadline exits are ~265–274 k, `mean_lookup_ms` exceeds the deadline in every window,
> and the throttle ratio is ≤ 0.53 %. If W5 raises `offered/s/tree` (the funnel's `samples_offered_total`) while
> lookups/s and metadata/s stay flat — the expected result — then the honest record is **Stage 4 NOT-IMPROVED
> on rate; ceiling = deadline-dominated drain; discovery fill is not binding; the funnel counters ship as the
> durable win**, which is a success under the measure-and-report rule, not a plan failure. Every safe latency
> lever is already in the prompt's binding Dead-ends table (round-idle inert, slot-budget / finish-on-peers
> −13 % metadata/s, miss-penalties −10.8 pp yield) and more get_peers workers is forbidden by exit criterion 6.
> If instead W5 shows `offered/s/tree` flat at 4× the workers, the binding term is novelty per query and the
> same NOT-IMPROVED record stands with a different named ceiling. No W4/W5 number is asserted here.

---

## Final record — all seven windows, one table

Every row is a 30.0-minute window (360 usable / 0 skipped samples, 5 s interval, span 1795.1 s, 64/64 trees,
`log_level=INFO`). `R` = Δ`connect_total` / Δ`metadata_attempts_total` (window deltas). `model` =
`1000 × tree_get_peers_workers / mean_lookup_ms`, the worker-capacity bound the drain is compared against.
Threads and RSS are the probe's own `health` samples (`ls /proc/<pid>/task`, `/proc/<pid>` VmRSS).

| # | window | start → end (+0800) | variable under test | build (patch chain) | lookups/s | yield % | metadata/s | R | drain /s/tree | model | paused % | threads | RSS end (KiB) | pps |
|---|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|
| W0 | control | 09:29:23 → 09:59:23 | `tree_parallel_peers=2` | d271699 (20) | 240.6 | 33.31 | **6.834** | 4.778 | 3.107 | 3.146 | 8.2 | 2,380–2,383 | 2,144,096 | 2,512 |
| W1 | A/B arm | 10:06:44 → 10:36:45 | `tree_parallel_peers=4` | d271699 (20) | 239.2 | 33.79 | **6.408** | 4.853 | 3.054 | 3.074 | 15.3 | 2,380–2,383 | 2,195,980 | 2,412 |
| W2 | **superseded history** | 11:23:39 → 11:53:39 | ttl=0 on the **8192-slot** build | c4d89a6 (21) | 241.6 | 33.85 | 6.628 | 4.671 | 3.110 | 3.151 | 10.0 | 2,380–2,383 | 2,213,320 | 2,510 |
| W2b | Stage-3 control | 12:31:23 → 13:01:23 | ttl=0 on the **131,072-slot** build | 9d1101a (22) | 236.7 | 31.94 | **6.128** | 4.665 | 3.051 | 3.083 | 16.0 | 2,380–2,383 | 2,163,032 | 2,407 |
| W3 | Stage-3 arm | 13:07:28 → 13:37:28 | `ttl=600` | 9d1101a (22) | 243.9 | 36.19 | **6.706** | **2.525** | 3.075 | 3.100 | 13.4 | 2,380–2,383 | 2,205,664 | 2,487 |
| W4 | Stage-4 control | 13:56:00 → 14:26:00 | `tree_bep51_workers=3` | 5fe15a1 (23) | 249.1 | 38.11 | **7.241** | 2.544 | 3.128 | 3.170 | 10.9 | 2,380–2,383 | 2,119,252 | 2,577 |
| W5 | Stage-4 arm | 14:32:03 → 15:02:04 | `tree_bep51_workers=12` | 5fe15a1 (23) | 246.4 | 37.36 | **7.286** | 2.697 | 3.135 | 3.154 | 68.1 | **2,956–2,959** | 2,241,568 | 2,640 |

Read it as three same-hour, same-build pairs plus one historical window: **W0/W1** (pp), **W2b/W3** (TTL),
**W4/W5** (BEP51 workers). **W2 is history, not a control** — it ran on the 8192-slot build, whose map was
capacity-churn-bound, and it is 0.50 metadata/s above W2b on identical settings (see the sizing correction in
the Stage-6 section). Seams between arms: W0→W1 7 min 21 s, W2b→W3 6 min 5 s, W4→W5 5 min 57 s (Apply restart
+ 5 min settle each). Build lineage in order: **d271699** patch 20 → **c4d89a6** patch 21 (submodule 66dc2a8)
→ **9d1101a** patch 22, capacity (submodule 3afb0a0) → **5fe15a1** patch 23, funnel (submodule 381a90d) →
this commit (documentation only).

Per-window deltas that the table cannot carry: `torrents_indexed` W0 480,751 → 487,255, W1 488,826 →
494,929, W2 503,266 → 509,703, W2b 515,818 → 521,817, W3 522,819 → 529,223, W4 532,903 → 539,862, W5 540,902
→ 547,739 (the DB grows ~1.2–1.4 % per window in every arm; the pairs are adjacent so the asymmetry is small
and is stated as a confound rather than corrected). **Temperature is not sampled in-window** — the probe reads
threads and RSS only; the last direct reading is the triage baseline's 38.9 °C (charging, level 100). That is
the one RECORD-SET field this run could not fill, and it is the same gap in every window.

## Exit criteria — final roll-up

**1. PASS — `aggregate` publishes windowed (last-interval) yield and fetch-success percentages, shown to
differ from the lifetime values across a restart.** All seven keys are present and non-zero on an ingesting
crawler: `metadata_attempts_last_interval`, `metadata_fetched_last_interval`,
`metadata_fetch_success_percent_last_interval`, `get_peers_lookups_last_interval`,
`get_peers_lookups_with_peers_last_interval`, `get_peers_yield_percent_last_interval` and
`stats_interval_sec`. **Internal consistency is exact** in a Stage-5 re-read: 59 / 470 =
**12.5532 %** and 688 / 2,358 = **29.1773 %**, matching the published percentages to every printed digit, with
`stats_interval_sec` 10. **They differ from the lifetime values in the same body after the restart**:
11.6296 % / 31.5926 % lifetime against 12.5532 % / 29.1773 % windowed (the parent's earlier sample showed the
same property at 12.86 % / 35.12 % windowed vs 13.44 % / 36.10 % lifetime). The mechanism is cross-checked by
this document's Δ-derived rates, which never read these keys. *Note:* `gp_probe.py` does **not** consume the
last-interval keys — Stage 1 deliberately made no probe change (the probe differences cumulative counters
host-side), so this criterion's evidence is a direct `/stats` read, not a probe column.

**2. PASS — `tree_parallel_peers` ships at the same-hour A/B winner, numbers recorded.** W0 6.834 vs W1
6.408 metadata/s, a 6.24 % relative gap on the higher arm's base (6.65 % on the lower), outside the <5 % tie
band, so **pp = 2** ships. No repo change was needed: `app/src/main/assets/dht_crawler/config.ini.default`
already carries `tree_parallel_peers = 2` (verified) and no Kotlin test asserts a different value. The device
was returned to 2 and re-read in Stage 5 (`config.ini` + `supervisor.metadata.parallel_peers`).

**3. PASS on a VALID window — connect events per attempt fall measurably against a same-hour ttl-0 control and
metadata/s rises.** Validity gate: Δ`expired_total` **4,071 > 0**, Δ`clears_total` **1 ≤ 2**. Then R 4.665 →
**2.525** (−45.9 %), metadata/s 6.128 → **6.706** (+9.4 %), Δskips **230,086** slots (2.44 per attempt), and
the failure distribution **shifted**: connect 83.2 % → 72.4 % with handshake rising 15.7 % → 25.8 %. Side
effects: failure mass 462,119 → 329,453 (−28.7 %), yield 31.9 % → 36.2 %, active conns/tree 10.03 → 7.13,
drain flat 3.05 → 3.07. **Sizing history recorded:** the first attempt (8192 slots) was churn-dominated
(clears 1→2→3 in 90 s, `expired_total` 0) and produced no verdict; patch 22 resized the map to 131,072 and
W2b replaced the stale W2 as the control — without which the +9.4 % would have been recorded as +1.2 %.

**4. PASS — the BEP51 funnel is visible and the ceiling is named with evidence.** Funnel: W4 queries/s/tree
3.51, offered/s/tree 44.34, accepted 7.1 % (bloom 65.9 %, in-flight 27.0 %, queue-full 0.0 %), identity gap
**0.00 %**. W5 (workers 12): queries/s/tree 5.04 (+43.6 %), offered/s/tree 65.78 (+48.4 %), accepted 4.7 %,
in-flight share 27.0 % → 33.1 %, pushed 362,156 → 356,005 (−1.7 %), fill 3.73 → 7.14 (+91.5 %), drain
3.13 → 3.14 (flat), `discovery_paused` 10.9 % → 68.1 %, lookups/s 249.1 → 246.4 and metadata/s 7.24 → 7.29
(flat). **Raising the BEP51 offer rate does not raise lookups/s** — accepted samples are novelty-bound (bloom +
in-flight gates) and more offers multiply duplicates. The binding term is the **deadline-dominated drain**
(mean lookup 5,707 ms ≈ the 6,000 ms deadline; idle exits 0; throttle ratio 0.146 % / 0.000 %), and every safe
latency lever is already in the dead-ends table. The stage is recorded **NOT-IMPROVED on rate** — the
documented negative the measure-and-report clause allows — with the funnel counters as the durable win.

**5. PASS — no invariant regression.** Clean apply from the pristine submodule:
`rm -rf app/build/native/src/dht_crawler && ./gradlew :app:prepareDhtCrawlerSrc` → BUILD SUCCESSFUL with **23
patches, zero `.rej`** (`find … -name '*.rej'` = 0) and the prepared tree carrying every stage's artifacts
(`src/stats_window.c`, `src/meta_peer_fail.c` + `include/meta_peer_fail.h`, `META_PEER_FAIL_CAPACITY 131072`,
the `bep51` funnel emitters). Host tests: `make -C app/build/native/src/dht_crawler tests` → exit 0, **14 test
binaries green** (13 `tests:` targets), including the two new targets `tests-stats-window` and
`tests-meta-peer-fail`. Kotlin:
`:app:testDebugUnitTest` on a forced re-run → **1,249 tests, 0 failures, 0 errors, 0 skipped** (98 suites).
Every pre-existing `/stats` block still present (aggregate, the eight `metadata_failures` counters, the
get_peers exit counters, `inflight`, `bep51_cache`, `config`, partitions). `git -C app/src/main/cpp/dht_crawler
status --porcelain` shows only ` M CLAUDE.md`, ` M CONFIG_INI.md` (this stage's doc edits) and `?? build/` — **no
vendored `src/`, `include/` or `lib/` edit**. No credential or host value in any committed diff.

**6. PASS — no concurrency escalation in the final state.** Stage-5 device re-read (`run-as … cat
files/crawler/config.ini`, then `/stats`): `num_trees 64`, `tree_get_peers_workers 18`, `tree_bep51_workers 3`,
`tree_metadata_workers 10`, `tree_parallel_peers 2`, `udp_rate_limit_pps 3000`, `log_level INFO`,
`tree_metadata_peer_blacklist_ttl_sec 600`, `tree_bep51_query_interval_ms 5`, patch-19 knobs all `0`. The W5
`tree_bep51_workers = 12` excursion is reverted: a Stage-5 re-read of the live crawler process shows
**2,383 threads** and `VmRSS` 1,982,372 KiB — back in the 2,380–2,383 baseline family, so the +576-thread
excursion is gone. Threads and RSS are recorded per window in the table above.

**7. PASS — the logging confound was removed before the first window and held.** `log_level = INFO` was applied
before W0 and every one of the seven windows ran at INFO (device re-read in Stage 5 still shows `INFO`); the
rationale — DEBUG had written ~4.6 GB at ~2.5 GB/h, a disk-I/O confound inside every window — is recorded in
the header section of this file. Consequence for comparison: these windows are **not** directly comparable to
the patch-19 run's, which ran at DEBUG.

## Dead-ends — updated with what this run ruled out

Carried from the patch-19 run, still valid: more get_peers workers/trees, round-idle early exit, per-slot
budget + finish-on-peers, miss penalties, metadata piece-idle eviction, shortening `metadata_timeout_ms`.
Added by this run:

| lever | measured outcome |
|---|---|
| **BEP51 worker scaling as a lookups/s lever** (`tree_bep51_workers` 3 → 12) | queries/s/tree +43.6 %, offered/s/tree +48.4 %, **pushed −1.7 %**, lookups/s −1.1 %, metadata/s +0.6 %. The bloom (62–66 %) and in-flight (27–33 %) gates refuse the extra samples; accepted samples are novelty-bound, not offer-bound. |
| **The BEP51 cache being "full" throttling submission** | Ruled out **by code**, no window spent: one supervisor-global 50,000-node seed cache, admission-sampled on the response path, read only at warm start, FIFO evict-and-insert when full (`bep51_cache.c`), never a reject and never on the query path. |
| **`tree_bep51_cache_capacity`** (the task prompt's key) | Does not exist. The real keys are `bep51_cache_capacity = 50000` and `bep51_cache_submit_percent = 17` (an admission sampling rate, not a throttle), and the cache is **one supervisor-global** object, not per tree. |
| **The 8192-slot peer-fail map** | Capacity-churn-bound: at ~110–133 NEW distinct fail addresses/s the 3/4-load clear wiped the table every ~50–70 s, `expired_total` stayed 0, so the TTL mechanism never ran and no verdict was possible. Capacity must be sized from the TTL working set (131,072 slots → 98,304 clear threshold vs a ~66–80 k working set). |
| **The UDP token bucket as the drain cap** | Throttle ratio 0.000–0.531 % of sends in every window (W5: 0 of 4,738,710) while pps ran 2,407–2,640 against the 3,000 cap. The bucket binds only on bursts. |
| **The discovery-fill premise itself** | Doubling fill (3.73 → 7.14 /s/tree, +91.5 %) moved the drain by 0.3 % (3.13 → 3.14) and lookups/s by −1.1 %. Discovery fill is not what bounds lookups/s. |
| **`tree_parallel_peers = 4`** | Same-hour, same-build A/B: 6.408 vs 6.834 metadata/s at pp=2 (−6.2 %), with more concurrent connections and more connect events and no more fetches. The patch-19 reading that favoured 4 was drift. |
| **The 5 ms BEP51 query interval as a fill throttle** | Arithmetically inert, no window spent: the cycle is send → RTT (or the 1000 ms wait) → `usleep(5 ms)`, and responses/query is 0.945–0.946, i.e. most cycles are RTT-bound. |
| **64 partitions re-querying the same nodes** | Ruled out by code: partitioning sets only the tree's own node ID, routing tables are global, and the capable-only reservoir plus the 3 s cooldown gives ≈1 re-query per node per ~130 s at the shipped rate. |

## Final device config vs the triage baseline

| key | triage baseline | final (Stage-5 re-read) | why it moved |
|---|---|---|---|
| `log_level` | `DEBUG` | `INFO` | criterion 7 (confound removal) |
| `num_trees` | 64 | 64 | unchanged |
| `tree_get_peers_workers` | 18 | 18 | unchanged |
| `tree_bep51_workers` | 3 | 3 | W5 excursion reverted |
| `tree_metadata_workers` | 10 | 10 | unchanged |
| `tree_parallel_peers` | 2 | 2 | A/B'd (W0/W1) and kept |
| `udp_rate_limit_pps` | 3000 | 3000 | unchanged (never raised) |
| `tree_metadata_peer_blacklist_ttl_sec` | absent (0) | **600** | criterion 3 PASS |
| patch-19 behaviour knobs | 0 | 0 | unchanged |
| `tree_bep51_query_interval_ms` | 5 | 5 | unchanged (the task prompt's "10" was wrong) |
| `bep51_cache_capacity` / `submit_percent` | 50000 / 17 | 50000 / 17 | unchanged |

`torrents_indexed` grew 480,751 → 548,410 across the run (7 × 30-minute windows), i.e. the crawler kept
ingesting throughout — no window was measured on a stalled crawler.

## What shipped

| commit | patch | content |
|---|---|---|
| d271699 | 20 `stats-last-interval.patch` | windowed rates in `/stats aggregate` (10 s supervisor sampler, pure `stats_window` module + `tests-stats-window`) |
| fed3cb9 | — | the pp A/B record (W0/W1), ship 2 |
| c4d89a6 (submodule 66dc2a8) | 21 `metadata-peer-fail-map.patch` | fail-open per-address connect-failure memory (TTL map, skip-to-empty guard, `/stats`, `tests-meta-peer-fail`) |
| 9d1101a (submodule 3afb0a0) | 22 `meta-peer-fail-capacity.patch` | capacity 8192 → 131,072 sized from the TTL working set; W2b/W3 verdict |
| 5fe15a1 (submodule 381a90d) | 23 `bep51-funnel-stats.patch` | BEP51 query + dedup-funnel counters (per tree + supervisor); W4/W5 and the ceiling investigation |
| this commit | — | documentation: this record, `AGENTS.md`, the submodule `CLAUDE.md`/`CONFIG_INI.md`, and the next-steps plan marked executed |

One behaviour change reached the device (`tree_metadata_peer_blacklist_ttl_sec = 600`); the template ships it
at `0`, so a fresh install starts fail-open and must opt in. Everything else this run added is instrumentation
or documentation.
