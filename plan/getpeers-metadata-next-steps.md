# PLAN_getpeers_metadata_next — raise discovery fill and metadata peer liveness (patch 19 follow-up)

Status: **EXECUTED (2026-11-17).** All stages ran. The authoritative record — seven 30-minute windows, the
per-stage verdicts, the exit-criteria roll-up and the dead-ends table — is
**`plan/getpeers-metadata-results.md`**. This file is kept as the *pre-registration of record*: read it for what
was planned and why, and the results file for what was measured. Where the two disagree the results file wins,
and every premise this plan got wrong is corrected in place below (marked **CORRECTED**).

Outcome, one line per stage: **Stage 1 PASS** (windowed rates shipped as patch 20); **Stage 2 PASS** (pp=2 kept
on a same-hour A/B, 6.834 vs 6.408 metadata/s); **Stage 3 PASS** (`ttl=600`, R 4.665 → 2.525, metadata/s
+9.4 %, after a capacity correction from 8192 to 131,072 slots); **Stage 4 NOT-IMPROVED on rate** (BEP51
workers 3 → 12 raised offers 48 % and left lookups/s flat — the ceiling is the deadline-dominated drain, not
discovery fill); **Stage 5** = the documentation pass that produced this header.

Derived from the `/dev` run that shipped crawler **patch 19**
(`getpeers-metadata-tuning.patch`, instrumentation only, all behaviour knobs at legacy defaults).
That run's measurements are the baseline for this plan and are reproduced below so this document is
self-contained; the full record (six 30-minute windows, per-window tables, attribution) is
`/home/adam/.local/state/pi-dev/r/7ky10o/reports/measurements.md`.

## Why this plan exists

Patch 19 was supposed to raise get_peers and metadata throughput. It did not: five 30-minute on-device
windows showed the tuning was **neutral-to-negative**, and the run stood every behaviour knob back down
to legacy while keeping the instrumentation that produced the findings. What patch 19 *did* deliver is
the ability to see where the throughput actually goes — and that changes the target. The next work
must aim at the two real ceilings, not at fetch latency or thread counts.

## Measured starting point (PFEM10, 64 trees / 18 workers / cap 3000; 30-min windows, rate deltas)

| window | lookups/s | yield % | pkt/lookup | metadata/s | pps | throttled Δ |
|---|---:|---:|---:|---:|---:|---:|
| BASELINE (pre-patch-19) | 239.1 | 36.9 | 9.23 | 7.16 | 2,454 | 0 |
| TUNED (all knobs on) | 233.0 | 29.7 | 8.99 | 5.86 | 2,458 | 34,111 |
| LEGACY (same build, knobs off) | 236.6 | 33.1 | 8.46 | 6.75 | 2,415 | 47,439 |
| SHIPPED (legacy + `parallel_peers=2`) | 221.2 | 25.4 | 7.93 | 5.12 | 2,092 | 5,167 |

Two facts dominate everything below:

1. **CORRECTED — get_peers is latency-bound, not discovery-bound.** This plan's original reading (lookups/s
   194–239 and per-tree drain 2.5–3.0/s against a BEP51 fill of 3.0–3.5/s with `discovery_paused` 11–14 %)
   was queue pressure, not a discovery cap. The seven windows in `plan/getpeers-metadata-results.md` measure the
   drain at 98.9–99.6 % of `1000 × tree_get_peers_workers / mean_lookup_ms` in *every* window with
   `idle_exits_total` 0, and doubling fill (3.73 → 7.14 /s/tree, W5) left the drain at 3.13 → 3.14. The
   patch-19 numbers fit the same model — 1000 × 18 / 7,044 ms = 2.56 /s sits inside the observed band — so
   finishing lookups sooner is the only lever that could raise lookups/s, and the safe latency levers were all
   measured negative (dead ends below). The 50,000/50,000 cache was a red herring; see Stage 4.
2. **Metadata is peer-liveness-bound.** The failure attribution is immovable across all six windows:
   `connect` **82.8–84.6 %**, `handshake` 14.3–16.1 %, `stall` 0.8 %, `deadline` 0.1–0.2 %,
   `hash` 0.1 %, `ut_reject`/`size`/`other` ≈0. The fetch machinery is not the wall; the peers get_peers
   returns are largely unreachable on TCP.

### Dead ends — do not retry these

Each of these was measured and rejected in the patch-19 run; re-proposing one costs a rebuild. The rows
marked *(this run)* were added by the run recorded in `plan/getpeers-metadata-results.md`.

| lever | measured outcome |
|---|---|
| more get_peers workers / trees | flat-to-worse; the bucket saturates and drain is upstream-bound |
| round-idle early exit (`round_idle_ms`) | inert — 264 idle exits vs 257,062 deadline exits (0.1 %); ~5 answers/lookup keep resetting the timer |
| per-slot budget + finish-on-peers | −3.4 pp yield, −13 % metadata/s for −1.5 % lookups/s (same-hour A/B) |
| miss penalties on unanswered slots | 1.86 M penalties / 31 min churned routing tables; −10.8 pp yield |
| metadata piece-idle eviction | addresses ≤1 % of the failure mass; no measurable gain |
| shortening `metadata_timeout_ms` | `deadline_total` is 0.1–0.2 % of failures — the deadline is not the problem |
| **BEP51 worker scaling** (`tree_bep51_workers` 3 → 12) *(this run)* | offered/s/tree +48.4 %, pushed −1.7 %, lookups/s −1.1 %, metadata/s +0.6 % — accepted samples are novelty-bound (bloom 62–66 %, in-flight 27–33 % of offered) |
| **“The BEP51 cache is full”** *(this run, code-ruled-out)* | one supervisor-global 50,000-node seed cache, FIFO evict-and-insert when full, read only at warm start — it cannot throttle a query; no `tree_bep51_cache_capacity` key exists |
| **The 8192-slot peer-fail map** *(this run)* | capacity-churn-bound — `expired_total` 0 and a table clear every ~50–70 s at ~120 new fail addresses/s — so it was resized to 131,072 slots before any verdict was read |
| **The UDP bucket as the drain cap** *(this run)* | throttle ratio 0.000–0.531 % of sends in every window; the bucket binds only on bursts |
| **The discovery-fill premise** *(this run)* | fill +91.5 % left the drain +0.3 % and lookups/s −1.1 % — discovery fill does not bound lookups/s |
| **`tree_parallel_peers = 4`** *(this run)* | same-hour A/B: 6.408 vs 6.834 metadata/s at pp=2; the patch-19 reading that favoured 4 was drift |

### Measurement protocol (mandatory — the patch-19 run learned this the hard way)

The 00:46 baseline and the 04:05 "identical behaviour" run differ by **3.8 pp of yield** with no code
difference: the DHT thinned overnight. Therefore:

- every experiment needs a **contemporaneous control** — same hour, same build, only the variable under
  test changed; a baseline measured hours earlier is not a control;
- 30-minute windows, 5-second interval, fixed tree count, **rate deltas only** (counters reset when the
  crawler restarts);
- state the confound whenever more than one knob moves.

## Stage 1 — Windowed rates in `/stats` (instrumentation gap)

**Scope.** Patch-20 change to `src/http_api.c` (prepared tree, exported as
`app/src/main/cpp/dht_crawler/patches/<new>.patch`, appended last in `app/build.gradle.kts`).
**Change.** `aggregate.metadata_fetch_success_percent` and `aggregate.get_peers_yield_percent` are
cumulative-since-crawler-start, so they climb after every restart and cannot be read as a window rate
(the tester flagged this: shipped sample 0 read 11.26 %, later 13.75 %). Add
`aggregate.metadata_attempts_last_interval` / `metadata_fetched_last_interval` and the same for
get_peers, sampled on a fixed interval (mirror the existing `metadata_rate` EMA machinery rather than
inventing a new timer), and publish `*_percent_last_interval` beside the lifetime values. Keep the
lifetime keys unchanged.
**Build.** `./gradlew :app:prepareDhtCrawlerSrc && make -j"$(nproc)" -C app/build/native/src/dht_crawler`,
then `./gradlew :app:assembleDebug` (JDK 21 env).
**Validation.** Clean-apply from the pristine submodule with the new patch last; unit tests green; on
device the new keys move independently of uptime and match a hand-computed Δ from two snapshots.
**Why first.** Every later stage's verdict depends on reading rates honestly; this is small and
low-risk, and it removes the one misleading number the run left behind.

## Stage 2 — `parallel_peers` A/B: 2 vs 4 (settings only, no rebuild)

**Scope.** Device settings only.
**Change.** Two 30-minute windows with everything else legacy: `tree_parallel_peers = 2`, then `= 4`.
The shipped config reverted to 2 without an A/B, yet LEGACY (pp=4) measured 6.75 metadata/s against
SHIPPED (pp=2) 5.12 — confounded by drift, which is exactly what a same-hour A/B resolves.
**Validation.** Same-hour comparison of metadata/s, attempts/s, `stall_total`/`deadline_total` share,
and `active_connections` per tree; report the winner with the confound stated.
**Why second.** It is free (no build), it can recover metadata throughput immediately, and it settles a
default that currently rests on an untested guess.

## Stage 3 — Metadata peer-liveness: per-address connect-failure memory

**Scope.** New crawler patch; `src/tree_metadata.c` (connect sites), a new fail-open key, counters.
**Change.** `connect` is ~83 % of all metadata failures — the same unreachable peers are re-tried across
infohashes because strikes are keyed on **infohash**, never on peer. Add a bounded, per-tree (or
supervisor-shared) address→failure-count map: on a TCP connect failure, record the address; when
selecting the fetch window, deprioritise addresses whose recent connect-failure count crosses a
threshold; age entries out on a TTL so a recovered peer is retried. Fail-open:
`tree_metadata_peer_blacklist_ttl_sec = 0` restores exact legacy selection. New counters
`metadata_peers_skipped_total` and `metadata_connect_fail_addresses_total` in `supervisor.metadata`.
**Validation.** Same-hour A/B (TTL 0 vs TTL N) over 30-minute windows: `connect_total` per attempt must
fall, metadata/s must rise, and the failure distribution must shift rather than merely shrink (a
distribution that stays 83 % connect means the map is not being consulted).
**Constraint.** No new threads; bounded memory (cap the map, evict LRU); no change to the eight existing
`metadata_failures` semantics — the count-once discipline must survive.

## Stage 4 — Raise BEP51 discovery fill (the actual get_peers ceiling)

**Scope.** Investigation first, then a new crawler patch; `src/thread_tree.c` (BEP51 workers),
`src/bep51_cache.c`, `src/config.c` keys, `src/keyspace.c` partitioning.
**Change.** ~~Discovery is the ceiling and it is currently capped by something in the BEP51 path, not by
get_peers.~~ **CORRECTED: discovery is not the ceiling — the drain is** (premise 1 above). The stage was
executed as an investigation plus one config-only window pair, with these leads:

1. **The BEP51 node cache is full (50,000 / 50,000)** — ~~establish whether a full cache throttles
   `sample_infohashes` submission, and whether raising `tree_bep51_cache_capacity` (or the submit policy)
   raises fill.~~ **Ruled out by code, no window spent:** the cache is ONE supervisor-global 50,000-node
   warm-start seed (≈780 per tree at 64 trees), written admission-sampled on the response path
   (`if (rand() % 100 < bep51_cache_submit_percent)`), read **only** at warm start, and FIFO
   evict-and-insert when full — it never rejects a query and is never read between node-pick and send.
   **There is no `tree_bep51_cache_capacity` key**; the real keys are `bep51_cache_capacity = 50000` and
   `bep51_cache_submit_percent = 17` (an admission sampling rate, not a throttle), and neither is per-tree.
2. **BEP51 worker count and query interval** — ~~`tree_bep51_workers` 10, `tree_bep51_query_interval_ms`
   10~~ **CORRECTED: the device runs `tree_bep51_workers = 3` and `tree_bep51_query_interval_ms = 5`**
   (verified in `config.ini` and in the template). Workers 3 vs 12 was the one window pair spent (W4/W5):
   queries/s/tree +43.6 %, offered/s/tree +48.4 %, pushed −1.7 %, lookups/s −1.1 %, metadata/s +0.6 %. The
   interval is arithmetically inert (5 ms against an RTT-dominated ~0.8 s cycle, responses/query 0.945), so
   no window was spent on it.
3. **Keyspace coverage** — ~~64 partitions at 50,000 cached nodes each may be re-querying the same nodes~~
   **ruled out by code:** partitioning sets only the tree's own node ID, routing tables are global, and the
   capable-only reservoir plus the 3 s cooldown gives ≈1 re-query per node per ~130 s at the shipped rate.
   `trees[].bep51_nodes_cached` counts cache admissions, not distinct nodes queried.

**Validation.** ~~Fill/s (probe `fill /s per tree`) must rise and `discovery_paused` fraction must fall;
drain must follow fill upward, and lookups/s must rise as a consequence.~~ **REPLACED (see the plan's exit
criterion 4):** under the 90 %/33 % hysteresis `discovery_paused ≈ (fill − drain)/fill`, so a fill-only rise
RAISES the paused fraction — the original clause was self-contradictory. The valid test is the funnel plus the
discriminator: per-tree lookups/s against `1000 × tree_get_peers_workers / mean_lookup_ms` (equality ⇒
latency-bound), `deadline_exits_total` per lookup, the throttle ratio, and the funnel's accepted / bloom /
in-flight shares.

**Constraint.** UDP sends must stay ≤ cap 3000. Measured: the throttle ratio was 0.146 % (W4) and 0.000 %
(W5) of sends, so the bucket never bound; `udp_rate_limit_pps` was not raised in either arm.

## Stage 5 — Record and document

**Scope.** `AGENTS.md`, submodule `CONFIG_INI.md` / `CLAUDE.md`, and
`plan/getpeers-metadata-next-steps.md` (this file) updated to *executed* with measured results.
**Change.** Record every window in the same six-row format, mark each stage PASS/FAIL/NOT-IMPROVED
honestly, and update the "dead ends" table with anything newly ruled out. If a stage cannot move its
metric, say so and stop rather than layering another knob.
**Validation.** Documented key names and defaults grep-match the shipped patch; no claim of improvement
that is not backed by a contemporaneous-control window.
**Result (executed).** `AGENTS.md` carries the patch-20/21/22/23 paragraphs, the seven-window measurement
summary, the corrected latency-bound statement and the dead-ends additions; the submodule `CLAUDE.md` and
`CONFIG_INI.md` carry the patch-20/23 sections and the corrected `tree_parallel_peers` rationale; this file is
marked executed with its wrong figures corrected. Grep checks: `tree_metadata_peer_blacklist_ttl_sec` agrees
across patch 21, `CrawlerKeys.kt`, `config.ini.default`, `CONFIG_INI.md` and the Kotlin tests (default `0`
everywhere), and the seven last-interval key names match between patch 20 and `AGENTS.md`. **One mismatch found
and left alone deliberately:** `gp_probe.py` does not read the last-interval keys at all (Stage 1 decided
against a probe change), so criterion 1's evidence is a direct `/stats` read rather than a probe column.

## Exit criteria

1. `aggregate` publishes windowed (last-interval) yield and fetch-success percentages, and they are
   shown to differ from the lifetime values across a crawler restart.
2. `tree_parallel_peers` ships at the value that wins a same-hour A/B, with the numbers recorded.
3. `connect_total` per metadata attempt falls measurably against a same-hour TTL-0 control, and
   metadata/s rises; or the stage is recorded NOT-IMPROVED with the counter evidence.
4. BEP51 fill/s rises and `discovery_paused` falls against a same-hour control, and lookups/s follows;
   or the stage names the new ceiling with evidence.
5. No invariant regression: patch chain applies cleanly from the pristine submodule with the new patch
   last, `:app:testDebugUnitTest` green, every pre-existing `/stats` block intact, no vendored
   `src/`/`include/`/`lib/` edit, no credentials in any file.
6. No concurrency escalation: tree count, worker counts and `udp_rate_limit_pps` unchanged unless a
   criterion explicitly justifies otherwise; threads and RSS reported per window.

**Result.** All six were assessed against the seven windows. Note that this list predates the plan's revision:
the run was judged against the plan's seven-criterion version (criterion 4 replaced by the funnel + ceiling
discriminator, criterion 5's patch count fixed at 23, criterion 6's seven windows, criterion 7 added for the
`log_level` confound). The authoritative per-criterion roll-up is in `plan/getpeers-metadata-results.md`:
**1–7 all PASS**, with criterion 3 PASS on a valid window and criterion 4 recorded as the documented negative
(funnel visible, ceiling named).

## Guardrails

- Crawler C changes are **patches only** (patch 20 onward, appended last); never edit the vendored
  submodule sources or the live `config.ini`.
- Every new key is **fail-open** (`0` = exact legacy) and lands in `CrawlerKeys.KNOWN_KEYS`, the
  sanitized `config.ini.default`, and the docs in the same stage.
- Keep the eight `metadata_failures` counters, the exit counters and the stats split intact — they are
  what made this plan possible.
- Measure-and-report: no pre-registered numeric target, no invented threshold, and a documented
  negative result is a successful outcome.
