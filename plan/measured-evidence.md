# Measured evidence

Dated measurements moved out of `AGENTS.md` so that file can stay a short architecture and
invariants reference. Nothing here is a rule; it is the evidence behind the rules.

Everything below was measured on a physical device. **Device serials and versions matter** — a
figure from `b93dac74` (empty DB, versionCode 4) is not a figure for `b078fce` (1.6 M-row DB,
versionCode 5). Snapshots are labelled.

The crawler's own long-form measurement records live in
[`getpeers-metadata-results.md`](getpeers-metadata-results.md) and
[`getpeers-metadata-next-steps.md`](getpeers-metadata-next-steps.md).

---

## Classifier v15 (CRAM)

Source of truth for the integration: [`cram-v15-integration.md`](cram-v15-integration.md).

### Container geometry

`cram.bin` is a little-endian CSR with no padding: header (magic `CRAM`, format version, `ncols`
**1,195,982**, 28 output columns, rowptr), feature-row-major entries of `(uint8 col, float32
weight)` — 5 B each — for the **920,915** nonzeros, 28 biases, the 48-state
`(cat_idx, kind_idx, adult_idx)` table and T = **0.7629844484210975**. The columns are **147,406**
named (structs 1,315 / words 48,757 / bigrams 97,334) plus a **1,048,576**-bucket char tail, which
is the 1,195,982 total; the design doc's "920,922" nnz is 7 high and is corrected here.
`cram_vocab.bin` is the same three sorted string lists as an offset table + UTF-8 blob (UTF-8 byte
order == Python codepoint order, so C `strcmp` bsearch reproduces dict membership).

Fetched files and shas (`files/crawler/models/v15/`, downloaded by `--fetch-models`):

| file | bytes | sha256 |
|---|---:|---|
| `cram.bin` | 9,388,835 | `834159b642d67da728bbdc3411badf83b639742743c95d414fc62ddd81e45bec` |
| `cram_vocab.bin` | 2,046,524 | `6e24c4b27e3bd546a7dad0cb9a929dc7f354e664139604f0bb75f3ca1cf21cee` |
| `manifest.json` | 831 | `59e39d3f85db21e015d94ffd7f5b055d0b593f69c238ac18f39c30bf50d53e55` |

All three come from `~/apps/cram/tvkind/data/clean_v3/heads_per_field_v2.npz` (4,909,615 B, sha256
`1cad6d9f9ddfd4afd72338288d12dda6855dceda4cb531d5417b9ee22406fff2`) via `scripts/cram_export.py`
(cram workspace commit `e6ce723`); the export converts and proves bit-identity, it never refits.

### G0 — export fidelity

Dense reconstruction bit-identical to **all 11** npz arrays (0 mismatches), val reload category
**1,846**/19,913 (delta 0), `val_all` kind **177**/3,999 and **57**/817 (delta 0), val ECE at T
**0.022218**; vocab witnesses train nnz **26,628,710** / val nnz **3,289,873** against the
container's 920,915. Heldout was never read.

### G1 — feature-text parity (bar revised, recorded, not waived)

The original bar was 10,000/10,000 identical `ft` between the app's tparse CLI (50-file cap
applied) and `features.jsonl`, on a deterministic seed-15 stratified sample (1,111 tv/anime ≥ 500;
708 rows `files_total > 50` ≥ 200). It was **MISSED at 9,710/10,000**: every one of the 290
mismatches was adult-token-only (`__adult_jav` 160, `__adult_site` 129, `__adult_set` 3; **0
missing tokens, 0 numeric-field differences**), full corpus 116,700/120,000 = 2.75 %. Measured
verdict impact on those 290 rows: category **0/290**, tv_kind **0/290**, adult **1/290**, max
|Δadult_p| **0.0098** — the engine was right (G2 passed 100 % on the same rows) and the drift was
corpus vintage. The operator authorised the serving-side strip (a recorded decision, not a waived
bar).

Post-strip: **10,000/10,000 exact**, sample ∩ the 20-id corpus set = ∅, and full corpus
**120,000/120,000 with mismatch id set `[]`** — the design doc's "119,980/120,000" was a planner
inference that does not hold, because those 20 ids appear in none of the local record files
(`corpus_adult_ids_in_join` = 0), so the assertion is set equality and is strictly stronger. The
opposite direction is witnessed separately: on the 20 rows the corpus *does* carry tokens for,
0/20 category, 0/20 tv_kind, 0/20 adult, max |Δadult_p| **0.00982432**.

### G2 — verdict parity

Device leg (b93dac74, 600 rows pulled from the live DB): **100 % / 100 % / 100 %**
category/tv_kind/adult against the host reference, max |Δadult_p| **1.221e-15**; the three-way
check (host x86 C harness vs arm64 DB verdicts) is **600/600 with |Δadult_p| = 0.000e+00** —
bit-identical float64; the ungated raw kind head would match only **225/600 = 37.5 %**, so the
caller's "concrete kind ⇒ tv/anime" gate is demonstrably live; 10 rows sit in [0.375, 0.5) so the
operating point is exercised; diversity 11/12 categories, 5 concrete kinds, adult 126/474 = 21.0 %
(DB-wide 21.8 %).

Host leg: 10,000/10,000 on each field (bar ≥ 99.9 %), max |Δadult_p| 1.443e-15, max
|Δconfidence| 1.554e-15, **max |ΔE| over all 48 states = 0.0**, 0 mismatches, diversity 12
categories / 6 kinds / both adult values.

### G3 — sustained ingest

**6 h 08 m**, 37 samples, **192,599** stored rows (38× the 5,000 bar): `clf_ver` **15 on all
192,599**, `adult_clf_ver` **15 on all 192,599**, top category movie **39.66 %** (bar < 60 %), 12
categories present, **0 rows** with a concrete `tv_kind` outside tv/anime, and
`classifier.enabled` true, `cram.enabled` true, `cram.load_failures` 0, `parser_only_fallback` 0,
`model_load_failures` 0 in **every** sample; `PRAGMA quick_check` ok on the 1,642,405,888 B
checkpointed DB.

*Caveat:* the crawler log is truncated on relaunch, so the `0 SQLITE_CORRUPT` grep covers only the
post-restart period — whole-window corruption evidence rests on `quick_check` plus the zero error
counters.

### G4 — operating point

Threshold **0.375 in all four places** (`/stats`, device `config.ini`, `models/v15/manifest.json`,
`config.ini.default`); `adult_p` min 6.23e-08, max 0.99999999999999867; boundary consistency
`adult=1 ⟺ adult_p ≥ 0.375` with **0 violations**; live tag rate **21.89 %** (42,161/192,599)
reported beside the heldout anchor (base 17.65 %, recall 0.90 / precision 0.848 at 0.375).

**Two recorded deviations:**

1. **21 rows carry `adult_p` marginally above 1** — 1.0000000000000002 to 1.0000000000000007,
   1–3 ULP, all `adult=1`, verdict unaffected and reference-consistent (the marginal is summed
   unclamped).
2. The **spot check MISSED** — of the 120-name corpus fixture only **3** were present in the live
   DB at 76,695 rows (1 known-adult tagged p=0.8383, 2 known-clean untagged p=0.0008 / 0.0001,
   **all 3 correct**), because ≥20-per-class is unreachable by live DHT ingest of a fixture built
   from an older corpus (extrapolation ≈1.5 M rows). Recorded as a miss with numbers; no rows were
   injected and the operator declined a criterion revision.

### G5 — throughput and RSS

Window mean **2.853 ms/item** (Δ540,079,636 µs / 189,281 items; every 10-min interval 2.05–3.52
ms) against the 10 ms bar, lifetime mean 2.848 ms at window end; peak crawler `VmHWM`
**1,822,308 kB = 1.7379 GiB** against the 2.3 GiB bar, **flat from uptime 0:58 for the remaining
4 h 50 m** while the DB grew to 192 k torrents — **−0.21 to −0.30 GiB lower** than the v13-era
1.95–2.04 GiB whole-process figure; 0/0/0 error counters across all 37 samples;
`movie_kind.thermal_pauses` 0 and `movie_kind_backfill.thermal_pauses` 0.

**Open item, recorded honestly:** after the deliberate restart the fresh process read **2,006 →
2,181 MB (≈2.13 GiB) in 12 minutes** before the phone's battery died; its plateau was **never
observed**, and the operator instructed that the re-measurement be skipped. So the G5 PASS covers
the cold-start / grow-with-the-DB path — a warm restart over an already-1.6 GB DB sits closer to
the bar and is **unmeasured**.

### G6 — schema

`tools/rebuild-db-v2.sh --check-schema` exit 0 ("16 statements match"); `CrawlerDbSchemaTest` 13
tests green; `PRAGMA table_info(torrent_class)` **identical** to the `backup/CrawlerDbSchema.kt`
replica (15 columns, same names/order/types); the backup/restore round-trip was **not exercised**.

**Premise corrected:** a destination *is* configured on this device (`backup_db_enabled=true`,
bucket `dht-backup`, prefix `db`, `backup_last_success_date` 2026-11-22) — the plan's "clean
device, no backup destination" does not hold; the operator confirmed it is a separate bucket and
instructed no change. Unexplained observation recorded verbatim:
`files/backup/db_bookmark.txt` mtime 18:26 with `last_torrent_id=4503 / last_prefix_id=10110`.

### G7 — persistence — PARTIAL

Every persistence leg PASSes: `.assets_version` identical before the deliberate restart, after it,
**and after an unplanned cold boot**; `models/` exactly `{v13, v15}` in all three; v15 model mtimes
identical across all three (**no restage**, including across power loss); `/stats` cram/vocab shas
and `clf_ver` identical before vs after; `classifier_meta` = `clf_ver` 15, `adult_clf_ver` 15, both
cram shas, `adult_hide_threshold` 0.375000; `clf_ver` / `adult_clf_ver` 100 % at 15 over 192,599
rows.

**The ingest-resumption leg was SKIPPED** at operator instruction (the phone was at 2 % battery,
charging, crawler stopped) — skipped/unmeasured, not passed.

### G8 — movie-kind coexistence

`movie_kind` generation **13**, `model_ready` true; backbone
`28270fa01100687969b541282814371062ab9e1a560bb7ea755243c822cf0d4c` and head
`2f994cbe792441be182717cc6faa46f3ff245db6d17af30d72cfc47bbefb7ae3` char-identical across `/stats`,
the v13 manifest and on-device `sha256sum`; **1,947/1,947** adult0-movie rows at
`movie_kind_clf_ver` 13 (**0 NULL, 0 other**); masks non-NULL on all 1,947 with real genre
bitmasks; v13 files byte-unchanged.

### Accepted costs (design §1 — carried, not waived)

- tv_kind v1 heldout drift **+2.73 pp**, and v2 is **val-validated only** (its heldout analogue is
  an extrapolation, never a measurement).
- val ECE **0.0222 > the 0.02 bar** (heldout 0.0178 passes; the criterion never named a split).
- `decided_conflict` rows (parser hint contradicts teacher) are **~90 % error by construction** and
  were never a ship target.
- The no-gate tv_kind architecture **pays 15 errors on the 2,688 `decided_agree` val rows** and
  buys back 94 conflict-row + 28 undecided-row errors.
- The shipped adult point **misses ~10 % of adult rows** (352/3,519 heldout) and **flags 566 clean
  rows (2.8 %)** — in `adult_mode=drop` those are deleted, in `tag` (the default) they are hidden.
- The research stage-0 dump's `v14_adult_prob` column is **inverted** (P(adult=0) or swapped
  columns) — the shipped model is unaffected (it trained on hard `teacher_adult` labels) and
  nothing in the app consumes that dump.

### Do not retry (measured negatives upstream)

Context/sibling features (no transfer on the wide space), the 84-weight pairwise block,
distillation at inference (teacher absent), energy-margin abstain, proximal-L1 sparsity, Adam on
this space.

---

## Movie-kind v13

Source of truth: [`movie-kind-v13-integration.md`](movie-kind-v13-integration.md) (and
[`movie-kind-v10-streaming-integration.md`](movie-kind-v10-streaming-integration.md) for the
pooling change).

### G6 bench — the first real on-device movie-kind measurement (v10's was never run)

The bench `--movie-kind-bench data/torrents.db 500` reports median **188.843 ms**, p95 **284.400
ms**, `vmhwm_kib` **1,149,368** (= 1,122.4 MiB), max CPU 76 °C, threads 4, 3000 samples over 627
s, failures 0, generation 13: PASS on median, p95 and peak RSS on **both** bar bases (1,228,800
KiB reported-KiB and 1,171,866 KiB = 1,144.4 MiB strict-GB), and the 478.2 MiB file passes the
600 MiB bar.

A sustained live window (35.5 min, 8 samples, the crawler ingesting 22,036 → 29,103 torrents)
settled at `mean_us_per_item` ≈ **254 ms** (window mean 302.7 ms, steady-state mean 261.2 ms after
warm-up) with `thermal_pauses` 0 and failures 0 at every sample, 59.0–67.3 °C.

A distinct quantity, never conflated with the classifier's pre-registered bench figure: the **whole
crawler process** live `VmHWM` reached 2,044,464 KiB ≈ 1.95 GiB (model + crawl state + SQLite page
cache; already 1.70 GiB before the measurement).

### Gate P — mask parity (mandatory before any production write)

The v9 P0 class (one constant mask on every name) is invisible to host tests, review and `/stats`.

It ran and PASSED under an operator-revised cross-architecture bar: ≥98 % identical label sets,
measured **490/500 = 98.00 %** and **986/1000 = 98.6 %** (≤64-token subset 985/995 = 99.0 %), every
mismatch within Hamming 4 (N=1000: 11 rows at 1, 2 at 2, 1 at 4) with flips in **both** directions,
122/165 distinct masks (no constant-mask P0 signature), the decomposed-Unicode row agrees, zero
classify failures, and three independent phone bench runs are bit-identical (determinism).

The original 500/500 empty-diff letter is recorded as **FAILED** (commit 3e592d7); the bar revision
is a recorded operator decision, not a silent pass. The residual is arm64-vs-x86 float32
accumulation at the head thresholds.

### D1 reset + full backfill (b078fce, single generation)

21,404 torrents, **9,144** adult0-movie rows all at `movie_kind_clf_ver` = 13, zero at 10, zero
NULL; `classifier_meta` `movie_kind_clf_ver` = 13 and `movie_backfill_last_id` = `''`. The reset
cleared 9,144 rows (it clears every `movie_kind_clf_ver >= 9`, which is why it runs **exactly
once**, app force-stopped, before the pass) and the pass then relabelled all 9,144 with
`updated=9144 skipped=0 failed=0 thermal_pauses=0` in ~29 min; log corruption count 0. The
backfill's 500-row `--dump-masks` dump was byte-identical to the pre-write gate-P dump.

Carried forward, unchanged: the accepted G3 `none`-recall miss (**0.7368** vs the 0.85 bar —
recorded upstream, not waived).

### Rollback history

`models/v10` survives only in git as reinstall-rollback history — reinstalling the 0.2.0 APK
restages v10, and masks are bit-compatible so 13-labelled rows stay readable. `models/v11` — the
three fastText `.ftz` students — is retired the same way (reinstalling the 0.3.0 APK restages it and
`ClassifierModelVersionMigration` resets the persisted threshold to 0.545726; rows already stamped
`clf_ver`/`adult_clf_ver` 15 stay readable, because they are plain integers in unchanged columns).

---

## Crawler `/stats` patches 19–23

### Patch 19 — the instrumentation is the win, the tuning is not

Measured over five 30-minute windows (1795 s each, 64 trees / 18 workers / cap 3000). The only
valid contemporaneous comparison is TUNED vs LEGACY on the same build in the same hour: lookups/s
233.0 vs 236.6 (−1.5 %), yield 29.7 % vs 33.1 % (−3.4 pp), packets/lookup 8.99 vs 8.46 (+6 %),
metadata/s 5.86 vs 6.75 (−13 %).

The 00:46 baseline (239.1 / 36.9 % / 9.23 / 7.16) is **not** a valid control — LEGACY on the new
build measured 33.1 % yield against the baseline's 36.9 %, i.e. the DHT thinned overnight.

get_peers is **latency-bound, not discovery-bound** — corrected by the seven windows below: the
per-tree worker drain equals `1000 × 18 / mean_lookup_ms` to within 1.1 % in every window
(98.9–99.6 %), `idle_exits_total` is 0, deadline exits are ~62 % of all-caller lookups and
`mean_lookup` sits at 94.6–97.6 % of the 6 s deadline, and raising BEP51 fill 3.73 → 7.14 /s/tree
(W5) left the drain at 3.13 → 3.14. The patch-19 numbers were already consistent with that model —
1000 × 18 / 7,044 ms = 2.56 /s sits inside the observed 2.5–3.0 drain band — so "discovery-bound"
was a queue-pressure observation (fill 3.0–3.5 vs drain 2.5–3.0, `discovery_paused` 11–14 %), not a
discovery cap.

Metadata is **peer-liveness-bound** (connect 82.8–84.1 %, handshake 14.8–16.1 %, stall 0.8 %,
deadline 0.1–0.2 % across every window), so piece-idle eviction and the deadline knob address ≤1 %
of the failure mass. The round-idle premise was **falsified**: 245 idle exits vs 246,380 deadline
exits, because answers trickle in faster than 1.5 s.

Criteria 4 (get_peers rate) and 5 (metadata rate/success) are recorded **NOT-IMPROVED** — the
documented negative result the measure-and-report clause allows, not a pass.

`Δudp_throttled_sends_total` is non-zero in all new-build windows (17,782–47,439) including LEGACY
vs 0 in the pre-change baseline — a build-level observation, cause unisolated, **not** attributed
to the behaviour knobs.

### Seven 30-minute windows (2026-11-17)

64 trees / get_peers 18 / BEP51 3 / metadata 10 workers, pp 2, pps cap 3000, `log_level=INFO`. Full
record: [`getpeers-metadata-results.md`](getpeers-metadata-results.md). Three same-hour,
same-build pairs, one variable each:

- **`tree_parallel_peers` 2 vs 4 (W0/W1) — 2 ships.** metadata/s 6.834 vs 6.408 (−6.2 %, outside
  the <5 % tie band), connect events 405,152 vs 427,348 with attempts 84,789 vs 88,063 and window
  success 14.47 % vs 13.06 %: pp=4 bought more concurrent connections (10.60 → 11.28 per tree) and
  more attempts, and no more fetches. The template already carried 2, so nothing changed in the
  repo. This reverses the patch-19 run's confounded LEGACY 6.75 vs SHIPPED 5.12 comparison.
- **`tree_metadata_peer_blacklist_ttl_sec` 0 vs 600 (W2b/W3) — 600 ships, and so does the shipped
  default.** Validity gate met: metadata/s 6.128 → 6.706 (+9.4 %), 230,086 skipped slots (2.44 per
  attempt), connect share 83.2 % → 72.4 % with handshake rising 15.7 % → 25.8 % — the distribution
  **shifted**, it did not merely shrink. Side effects: failure mass 462,119 → 329,453 (−28.7 %),
  yield 31.9 % → 36.2 %, active conns per tree 10.03 → 7.13, drain flat 3.05 → 3.07. The first
  attempt at 8192 slots was capacity-churn-bound (clears 1→2→3 in 90 s, `expired_total` 0) and
  produced no verdict; patch 22 resized the map and W2b replaced the stale W2 as the control. That
  mattered: W2 (8192 build, ttl=0) read 6.63 against W2b's 6.13 on identical settings, so reusing
  W2 would have turned +9.4 % into +1.2 %.
- **BEP51 workers 3 vs 12 (W4/W5) — NOT-IMPROVED on rate; the funnel is the win.** queries/s/tree
  3.51 → 5.04 (+44 %), offered/s/tree 44.34 → 65.78 (+48 %), but pushed fell 362,156 → 356,005
  (−1.7 %) and lookups/s 249.1 → 246.4 with metadata/s 7.24 → 7.29 flat; the accepted share fell
  7.1 % → 4.7 % because bloom (65.9 → 62.1 %) and in-flight (27.0 → 33.1 %) refuse the extra
  duplicates. Fill 3.73 → 7.14 /s/tree (+91 %) did not move the drain (3.13 → 3.14), and
  `discovery_paused` rose 10.9 % → 68.1 % exactly as the 90 %/33 % sawtooth predicts
  ((fill−drain)/fill ≈ 56 % predicted vs 68.1 % measured). **The binding term is the
  deadline-dominated drain**: `mean_lookup_ms` 5,707 ≈ the 6,000 ms deadline, idle exits 0, throttle
  ratio 0.15 % of sends (W5: 0 throttled of 4.74 M sends). Raising BEP51 offer rate does not raise
  lookups/s. Threads 2,383 → 2,959 (+576 = 64 × 9, as predicted), RSS 2,119,252 → 2,241,568 KiB,
  pps 2,577 → 2,640 of the 3,000 cap.

### Ruled out by that run — do not retry without a new discriminator

1. **BEP51 worker scaling as a lookups/s lever** — accepted samples are novelty-bound (bloom +
   in-flight gates) and more offers multiply duplicates.
2. **The 8192-slot peer-fail map** — at ~110–133 NEW distinct fail addresses/s the 3/4-load clear
   wiped it every ~50–70 s and `expired_total` stayed 0, so the TTL mechanism never ran; capacity
   must be sized from the TTL working set, not guessed.
3. **The UDP token bucket as the drain cap** — the throttle ratio is 0.15–0.53 % of sends in every
   window (W5 0.000 %) while pps sat at 2,407–2,640 of the 3,000 cap.
4. **The discovery-fill premise itself** — doubling fill moved the drain by 0.3 %, so discovery is
   not what bounds lookups/s.

Every safe latency lever is already in the patch-19 dead-ends list (round-idle inert, slot-budget /
finish-on-peers −13 % metadata/s, miss-penalties −10.8 pp yield), and more get_peers workers stays
forbidden by the concurrency budget.

### Patch 21/22 — peer-fail map sizing

131,072 × 3/4 = 98,304 clear threshold > the ~66,000–79,800 entries a 600 s TTL accumulates at the
measured ~110–133 NEW distinct fail addresses/s (~23 % margin), and 4 MiB ≈ 0.2 % of the measured
2.04 GB crawler RSS. The original 8192 slots (clear at 6,144) were wiped every ~50–70 s at that
rate, so `expired_total` stayed 0 and entries died by capacity clear, never by TTL.

### Patch 18 — inflight dedup (b078fce, 320,697-row DB, 64 trees)

`/stats` carries an `inflight` object
(`enabled/capacity/held/claimed/refused/released/expired/tree_released/clears`). Over an 18-minute
run `refused` 788,419 vs `claimed` 188,715 — **80.7 %** of BEP51 discovery attempts were duplicates
that never reached a fetch; `held` plateaued at ~21–22 k (bounded by the per-tree queue capacities,
not a leak) and `expired`/`clears` stayed 0.

`Cached counts initialized: 320697 torrents, 10711461 files` matched the pre-restart `/stats`
exactly across two restarts (319,600 → 319,600 and 320,697 → 320,697), and the 15-minute re-seed
logged `320697 torrents (was 320697)` — zero drift. That re-seed costs ≤ 1 s here (`COUNT(*)` over
320 k torrents + 10.7 M files), so expect ~10–25 s on the 6.1 M/286.7 M-row production DB.

---

## APK diet

The two model generations total 512,972,090 B (~489.2 MiB — `models/v15` 11,436,190 B +
`models/v13` 501,535,900 B), which is the payload the APK no longer carries.

- 0.4.0 debug APK with the weights staged: **540,330,690 B**.
- Post-diet APK: **51,313,609 B** (49 MiB) — 489,017,081 B smaller, 90.5 %.
- Installed on b078fce today (0.4.1, versionCode 5): **55,048,593 B**.

**Packaging caveat:** AGP writes the new, smaller APK over the old one and leaves the freed region
as dead bytes inside the zip (measured: 482,603,215 B of gap before
`assets/dht_crawler/torrent_search_keywords.txt`, so the first post-diet build still weighed
537,114,957 B). Deleting `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/intermediates/{apk,packaged_assets,compressed_assets}` before rebuilding gives the true
size; a `clean` build is not needed and would rebuild every native dependency.

### Post-diet device verification (b078fce, 2026-11-23)

The diet APK installs at 51,313,609 B and on first launch rewrote `.assets_version` from
`4|models/v15|models/v13` to **`4|keywords`** while leaving `files/crawler/models/` untouched (v13
479 MB + v15 11 MB) — the sweep no longer reaches it.

The mbedTLS TLS path, previously only proven on the host's OpenSSL, ran on-device:
`libdhtcrawler.so --fetch-models --models-dir ./mftest` downloaded and verified **512,953,487 B in
50 s** (4 files + 2 manifests, exit 0), and a second run was a no-op (0 downloaded, 2.6 s of
hashing).

`/stats` publishes `bep51_cache_fetch` = `{"last_result":"not_attempted","enabled":true,...}` on a
warm cache, with `classifier.clf_ver` 15, `cram.enabled` true, `adult_hide_threshold` **0.375** and
`movie_kind` generation 13 / `model_ready` true — the crawler started on the pre-existing models, so
`maybeFetchCrawlerModels` correctly skipped the download.

---

## Search database rebuild (schema v2)

The v1 database carried a 51.2 GB trigram `file_search*` index. Schema v2 drops it and keeps the
base and `torrent_docs` FTS tables.

The phone snapshot `~/torrents-phone.db` (82,977,325,056 B, ~83 GB, WAL checkpointed) rebuilds to
**28.57 GiB** — 30,675,599,360 B on disk, 30,670,880,768 B by `dbstat`, with the `torrent_docs*`
shadows at 4,325,539,840 B (4.03 GiB; the design README's 6.4 GB was a sample-based estimate).

Preserved: `torrents` 6,098,645 · `torrent_files` 286,750,070 · `path_prefixes` 11,210,522 ·
`torrent_class` 6,098,645 · `classifier_meta` 6, `torrent_docs` = the torrents count, `file_search%`
= 0, `sqlite_sequence` high-water marks (34,776,141 / 1,424,727,769 / 11,210,522).

A four-term `torrent_docs` match-set INSERT is **16–19 ms** (627 rows) where the v1 trigram+GLOB
candidate scan took **16.5–21.7 s**.

Runtime: ~44 min full (26 copy + ~20 `torrent_docs` chunks + 5.5 VACUUM plus ~10 integrity check); a
`--verify-only` re-check is ~17 min.

72,866 pre-existing `torrent_files` orphans (1,212 missing parents) are copied verbatim, so the FK
check compares the target's violation set against the source's instead of demanding zero.

---

## Dated device snapshots

### After the v15 rollout (2026-11-22, b078fce)

192,599 torrents / 14,704,427 files, DB 1,642,405,888 B, `versionCode` 4 / `versionName` "0.4.0",
installed APK 540,330,690 B, `files/crawler/models/` = {v15, v13}, `.assets_version` =
`4|models/v15|models/v13` — that stamp format predates the APK diet; it is now
`<versionCode>|keywords`, and `files/crawler/models/` is populated by the crawler's `--fetch-models`
instead of by asset staging.

### 2026-11-26 (b078fce) — production database

`versionCode` 5 / `versionName` "0.4.1", installed APK **55,048,593 B**, `files/crawler/models/` =
{v15, v13}. Live `/stats` via the dashboard: **1,640,540 torrents**, 64 active trees, 4.2 metadata/s,
DB 11.6 GB, internal free 170.6 GB, `CRAM v15 (sha 834159b642d6)` with 1,651 predicted and 0 load
failures, language filter `non-Latin >=33%: 2242 checked, 591 dropped (26.4%)`, USB storage granted
with 170.6 GB free.

Four daemons running: DHT Crawler, Transmission, cloudflared, FTP Server.

Observed on that dashboard render, unexplained at the time: the DHT Crawler log tail is saturated
with repeated llama.cpp lines `init: embeddings required but some input tokens were not marked as
outputs -> overriding`. Written up separately in
[`llama-embeddings-warning.md`](llama-embeddings-warning.md).
