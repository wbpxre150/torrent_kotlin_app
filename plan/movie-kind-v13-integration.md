# PLAN_movie_kind_v13 — integrate the retrained movie-kind head (v5-adult0 decoder T, Q4_0) as `models/v13`

Status: **executed — device stages 0-6 complete on b078fce (2026-11-14); docs commit landed.**
The upstream arm is complete and closed
(`~/apps/classifier-model/plan/movie-kind-head-retrain.md`, executed 2026-11-13/14): the
genre-stratified adult0-only retrain measured out, the encoder/`v12` variant washed out, and the
head retrained on the **decoder** backbone shipped as **`models/v13`** in the classifier-model
repo (provenance: `data-provenance-movie-kind-v12.md`, `data-provenance-movie-kind-v13.md`).
This plan covers only the device-side integration — upstream's stage 7.

### Measured outcome (b078fce / PFEM10 — recorded, not re-derived)

- **Artefact + build:** v13 staged and sha-verified (backbone 501,452,416 B `28270fa0…cf0d4c`, head
  65,712 B `2f994cbe…7ae3`, manifest 17,772 B `e0474643…fb57`); the 17-patch chain applies, the host C
  suite and `:app:testDebugUnitTest` are green, and the APK measures **542,045,550 B** at versionCode 3
  / "0.3.0" — ~6.8 MB *smaller* than the 548,821,733 B v10 build because the v13 weights deflate
  differently, despite identical uncompressed asset sizes. Staged models total 528,148,052 B
  (~503.7 MiB: v11 26,612,152 + v13 501,535,900).
- **Bring-up:** stamp `3|models/v11|models/v13` (crawler-level `files/crawler/.assets_version`, not
  under `models/`), `files/crawler/models/` = {v11, v13} (v10 and v6 swept),
  `movie_kind_model_dir = models/v13`, `/stats .movie_kind` generation 13 / hidden 1024 / backbone
  `28270fa0…` / head `2f994cbe…` / `model_load_failures` 0. Inline classification at generation 13 was
  demonstrated over a bounded crawl window (810 inline 13 rows — criterion 4 passed far above its ≥1
  bar; R4 resolved).
- **Gate P (mandatory pre-write): PASSED under the operator-revised cross-architecture bar** (operator
  decision 2026-11-14, in writing; the original 500/500 empty-diff letter is recorded as **FAILED** in
  commit 3e592d7 — recorded, not silently re-lettered): ≥98 % identical label sets, measured
  **490/500 = 98.00 %** and **986/1000 = 98.6 %** (≤64-token subset 985/995 = 99.0 %); every mismatch
  within Hamming 4 (N=1000: 11 rows at 1, 2 at 2, 1 at 4) with flips in both directions; 122/165
  distinct masks (no constant-mask P0 signature); the decomposed-Unicode row (row 879) agrees; zero
  classify failures; three independent phone bench runs bit-identical (determinism). Residual =
  arm64-vs-x86 float32 accumulation at the head thresholds. Criterion 8 re-proved the same bar on the
  backfilled rows, and the backfill's 500-row dump was byte-identical to the pre-write gate-P dump.
- **D1 reset + full backfill:** the reset cleared **9,144** rows (it clears every
  `movie_kind_clf_ver >= 9`, hence exactly once, app force-stopped, before the pass); the pass then
  relabelled all 9,144 with `updated=9144 skipped=0 failed=0 thermal_pauses=0` in ~29 min.
  Single-generation DB afterwards: **21,404 torrents, 9,144 adult0-movie rows all at
  `movie_kind_clf_ver = 13`, zero at 10, zero NULL**; `classifier_meta` `movie_kind_clf_ver = 13` and
  `movie_backfill_last_id = ''`; log corruption count 0.
- **G6 (the first real on-device movie-kind measurement; v10's was never run):** bench
  `--movie-kind-bench data/torrents.db 500` → median **188.843 ms**, p95 **284.400 ms**, `vmhwm_kib`
  **1,149,368** (= 1,122.4 MiB), max CPU 76 °C, threads 4, 3000 samples over 627 s, failures 0,
  generation 13. Bars: median ≤ 300 ms **PASS**, p95 ≤ 600 ms **PASS**, peak RSS ≤ 1.2 GiB **PASS on
  both bases** (1,228,800 KiB reported-KiB and 1,171,866 KiB = 1,144.4 MiB strict-GB), file 478.2 MiB
  < 600 MiB **PASS**. Sustained live window (35.5 min, 8 samples, crawler ingesting 22,036 → 29,103
  torrents): `mean_us_per_item` settled ≈ **254 ms** (window mean 302.7 ms, steady-state mean 261.2 ms
  after warm-up), `thermal_pauses` 0 and failures 0 at every sample, 59.0-67.3 °C. **A separate
  quantity, not the classifier's pre-registered bench figure:** the whole crawler process's live
  `VmHWM` reached **2,044,464 KiB ≈ 1.95 GiB** (model + crawl state + SQLite page cache; already
  1.70 GiB before the measurement).
- **Restart persistence (criterion 10):** two force-stop/relaunch cycles — stamp unchanged, the
  `models/v13/` mtimes and inodes unchanged (no restage), generation 13 with the same backbone/head
  shas, `movie_kind_backfill` idle with `rows_processed` 0 and `started_at` 0 (no re-run).
  `.movie_kind.total_classified` is a **per-session** counter (it resets on relaunch: 3,350 → 44 → 26
  across this check), not a durable total.
- **Carried forward unchanged:** the accepted G3 `none`-recall miss (0.7368 vs the 0.85 bar — recorded
  upstream, not waived; R6), `classify_backfill_mode = off` with the backfill daemon un-started, and
  the movie-kind backfill reachable only as a force-stopped one-shot. `models/v10` survives only in git
  as reinstall-rollback history (reinstalling the 0.2.0 APK restages it, and masks are bit-compatible,
  so 13-labelled rows stay readable).

## Upstream gates (recorded, not re-derived here)

| gate | value | bar | verdict |
|---|---:|---|---|
| G1 held-out micro-F1 (new 3 123-row adult0 heldout) | 0.6199 | ≥ 0.50 | pass |
| G2 macro-F1 (genres n ≥ 30) | 0.5538 | ≥ 0.35 | pass |
| G3 `none` precision / recall | **0.8785 / 0.7368** | ≥ 0.85 each | **precision pass, recall MISS — accepted in writing** (no head over the 16 probabilities reaches 0.85/0.85 on the adult0 population; the bar, not the head, moved — R1 in the upstream plan) |
| G4 `wikieval2` external yardstick (re-excluded against the v5 draw: 3 375 paired rows) | any-hit 0.6966 vs shipped 0.6684 (+0.0282), micro-F1 0.4612 vs 0.4531, film-clustered delta +0.0326, CI [+0.0080, +0.0571], sign test p = 0.0016 | ≥ v10 head's measured result | pass |
| G7 runtime parity (f16 reference) | pass (machinery reused; head file changes, backbone family does not) | ≥ 98 % identical | pass |
| G8 paired vs the shipped v10 head on the shared 2 025-row adult0 heldout | +0.0835 micro / +0.0518 macro / +0.2470 `none`-precision (12/14 genres) | ≥ re-derived baseline + 0.03 | pass |
| G6 phone latency/RSS/file | file 478.2 MiB (bar 600); batched-CLI stage-0: median 156.7 ms / p95 236.2 ms / RSS 1 206.7 MiB | median ≤ 300 ms, p95 ≤ 600 ms, RSS ≤ 1.2 GiB (= 1 144.4 MiB strict-GB), file ≤ 600 MiB | **this plan's stage 5 measures the real single-sequence loop** — the 1 206.7 MiB row is the 8-sequence batched CLI allocation, an upper bound, not the app's loop; v10's on-device bench was never run |

The ship decision is recorded in the v13 manifest (`selection.gate_miss.accepted: true`,
"recorded, not waived"). This plan does not re-litigate it; it carries the known G3 recall
miss forward (risk R6).

## 0. Operator decisions (taken 2026-11-14, before any device work)

| # | decision |
|---|---|
| D1 | **Destructive reset + full backfill.** Existing device rows do not keep their generation-10 labels: after install and gate P, run `--movie-kind-backfill <db> --reset` (clears every `movie_kind_clf_ver >= 9` row, tombstones the resume key, deletes the completion key), then a full `--movie-kind-backfill <db>` pass with the v13 manifest as target. End state: a single-generation DB — every adult0 movie row is 13 or NULL (failed classify), no 10s remain. |
| D2 | **Tiny 17th crawler patch, `movie-kind-v13-default.patch`**: `config.c`'s compiled-in `movie_kind_model_dir` default `models/v10` → `models/v13` (+ the `config.h` doc comment). No functional C change anywhere else — v13 is the same decoder/Q4_0/hidden-1024/pooling-last/genre-order family as v10; only the weights differ. |
| D3 | **versionCode 3 / versionName "0.3.0".** The `.assets_version` stamp becomes `3|models/v11|models/v13` — first launch restages and sweeps the retired `models/v10` dir out of `filesDir`. |
| — (inherited) | Streaming-only production regime (P4 of the v10 plan): the backfill daemon stays un-started; all relabelling happens via the force-stopped one-shots above. Bundle-in-APK (P3). Gate P mask parity is **mandatory before any production write is trusted**. |

## 1. The artefact

Source of truth: `~/apps/classifier-model/models/v13/` (local; the v13 arm was built and
verified on this workstation, `export.py --mode verify --models-dir models/v13` reproduces).
Stage into `app/src/main/cpp/dht_crawler-android/models/v13/` and sha-verify every file:

| file | bytes | sha256 |
|---|---:|---|
| `movie_kind_backbone_Q4_0.gguf` | 501 452 416 (478.2 MiB) | `28270fa01100687969b541282814371062ab9e1a560bb7ea755243c822cf0d4c` |
| `movie_kind_head.bin` | 65 712 (JEVH, 1024×16) | `2f994cbe792441be182717cc6faa46f3ff245db6d17af30d72cfc47bbefb7ae3` |
| `manifest.json` | 17 772 | `e047464314e40bb70e7fc16205297878981d4252349d96ef3830f0260757fb57` (commit byte-identical) |

Properties this plan stands on (all verified against the manifest and the staged v10 copy on
2026-11-14):

- **No functional code delta.** `family: decoder`, `quant: Q4_0`, `hidden: 1024`,
  `n_labels: 16`, `pooling: last`, `embd_normalize: none`, prompt template
  `Torrent: {name}\nFilm genres:`, `max_input_tokens: 64` — every one identical to the shipped
  v10 pair. Same llama.cpp pin `18ab62a8ec5144ae5d2f65e78374ba69652d75dd`. File names and byte
  sizes are identical, so `MOVIE_MODEL_FILES` and every size assertion carry over unchanged.
- **Generation is manifest-derived:** `version: "v13"` → `mk_parse_generation` takes the digits
  → `movie_kind_clf_ver` becomes 13, `/stats` generation 13. No code change.
- **Bit compatibility:** `genres[]` order is byte-identical to the staged v10 manifest
  (action, comedy, drama, horror, thriller, scifi, fantasy, animation, documentary, romance,
  crime, adventure, family, mystery, war, western — bit *j* is `genres[j]`, `none` = 0), and
  `taxonomy.sha256` is unchanged (`313dafd4a5fd27e35e15530cee5279c409a92d2cb818a3d4878cfc914f7148b4`,
  same as v9/v10/v11). Assert both diffs empty at stage 0 — the failure mode (silently permuted
  genre bits across a DB that already carries 10s) is why.
- `models_dir: "models/v13"` matches the stage dir name directly this time (v10's named
  `models/v10-nemotron-q4` — harmless either way; the crawler resolves via the pinned key).
- `tokenizer.json` is pinned in the manifest but staged in neither v10 nor v13 — unchanged and
  harmless (the GGUF carries the vocab).
- The head is D5-fitted on this quant's own last-token embeddings; thresholds live in the JEVH
  file (round-tripped by upstream `export.py --mode verify`); the manifest's `thresholds` array
  is informational.

## 2. Code deltas in detail (all facts read from the current tree)

### 2.1 Crawler — one tiny patch, `movie-kind-v13-default.patch` (17th slot)

- `src/config.c :: config_init_defaults` (:130): `strncpy(config->movie_kind_model_dir,
  "models/v10", …)` → `"models/v13"`. Consistency only — the app always pins the key — but a
  hand-run host CLI with a bare config would otherwise look for the retired dir.
- `include/config.h` (:102): update the doc comment's `(default: "models/v10")`.
- **Patch mechanics** (AGENTS invariant 2 + "new patches go LAST"): the hunk's context is the
  *post-patch-16* tree (patch 16 wrote the `v10` literal), so build the diff from a scratch copy
  with patches 1–16 applied (`./gradlew :app:prepareDhtCrawlerSrc` output tree works), make the
  one-line change, `diff -u` against that state, drop the result into
  `app/src/main/cpp/dht_crawler/patches/`, and append the filename **after**
  `adult-v11-backfill.patch` in the fixed-order list in `app/build.gradle.kts`. The vendored
  tree stays pristine.
- Nothing else in C: pooling stays `LLAMA_POOLING_TYPE_LAST`, the read stays
  `llama_get_embeddings_seq(ctx, 0)`, hidden comes from the manifest, host fixtures stay 1024,
  the ingest hook and the 14-column INSERT are untouched. Invariant 14 is **not** triggered (no
  schema/column change).

### 2.2 App (Kotlin / Gradle / assets)

- `CrawlerConfig.kt`: `MOVIE_MODEL_SUBDIR = "models/v10"` (:245) → `"models/v13"`; KDoc at
  :39-40, :54 and :303-305 (v10 → v13, same Q4_0/hidden-1024 facts). `MOVIE_MODEL_FILES`
  unchanged (names identical).
- `app/build.gradle.kts`: `copyDhtCrawlerAssets` `include("v11/**", "v10/**")` →
  `include("v11/**", "v13/**")` (+ the path comment above it); patch list append (§2.1);
  `versionCode = 3`, `versionName = "0.3.0"` (:18-19).
- `app/src/main/assets/dht_crawler/config.ini.default`: `movie_kind_model_dir = models/v10` →
  `models/v13`, plus the two comment blocks naming models/v10 (:83-89).
- `config/CrawlerTaxonomy.kt` (:32): doc path `dht_crawler-android/models/v10/manifest.json` →
  `models/v13`.
- Tests (mirror of main, no androidTest): `CrawlerConfigTest`
  `realConfigIniDefault_isAndroidTunedAndHasNoDesktopPaths` (:264-266: comment +
  `assertEquals("models/v10", …)` → `"models/v13"`) and `EnsureLayoutTest`
  `MOVIE_MODEL_SRC_DIR = File("src/main/cpp/dht_crawler-android/models/v10")` (:562) → v13.
  Retarget any other test asserting the v10 stamp/dir; the streaming-sha/chunked-comparator
  pattern from the v10 bump carries over as-is (the 478 MiB GGUF must never be slurped — see
  the v10 pitfalls). `CrawlerKeysTest` (rejects `movie_kind_model_dir=models/v4` as a path key),
  `CrawlerDbSchemaTest` and the backup layer are untouched.
- Dashboard: nothing — `/stats movie_kind` already publishes generation, hidden, model_dir,
  shas, counters and `by_genre`; the panel reads 13/1024/`models/v13` without changes. No new
  `DaemonService` wiring (invariant 12: no new collaborator).

## 3. Stages

### Stage 0 — artefact staging (workstation, ~10 min)

- Copy `~/apps/classifier-model/models/v13/{manifest.json, movie_kind_backbone_Q4_0.gguf,
  movie_kind_head.bin}` into `app/src/main/cpp/dht_crawler-android/models/v13/`; verify all
  three sha256s against §1 (streaming sha for the GGUF).
- Review asserts (fail loudly here): `diff <(jq -c '.genres[]' v13/manifest.json) <(jq -c
  '.genres[]' v10/manifest.json)` empty; `.taxonomy.sha256` == `313dafd4…`; `.hidden` 1024;
  `.pooling == "last"`; `.embd_normalize == "none"`; `.version == "v13"`.
- **Exit:** three files staged, shas recorded, asserts green. **Commit:** its own data commit
  (~478 MB blob; second after v10's — repo-weight precedent accepted under P3, risk R5).

### Stage 1 — wiring (workstation, ~0.5 day)

- Patch 17 + Kotlin/Gradle/config-template changes per §2; retarget tests per §2.2.
- Build with JDK 21 (AGENTS): `./gradlew :app:prepareDhtCrawlerSrc` (proves the 17-patch chain
  applies — a "previously applied" error means the vendored tree was left dirty);
  `make -C app/build/native/src/dht_crawler tests` (host suite green — no fixture change
  expected); `./gradlew :app:testDebugUnitTest`; `./gradlew assembleDebug`.
- APK check: `unzip -l` shows `dht_crawler/models/v13/{manifest.json,
  movie_kind_backbone_Q4_0.gguf, movie_kind_head.bin}` and **zero** `models/v10` entries;
  record the APK size (expected ≈ the v10 build's 548.8 MB — the artefacts are the same sizes,
  so the delta is code-only).
- **Exit:** all builds green, APK contents verified. **Commit:** patch + wiring + tests
  (separate from the stage-0 data commit).

### Stage 2 — device bring-up

- `adb install -r` the 0.3.0 APK; launch; let the wizard/config apply pin
  `movie_kind_model_dir = models/v13` and `ensureAssets` stage v13 + sweep the v10 dir out of
  `filesDir` (stamp `3|models/v11|models/v13`).
- `/stats` (after `adb forward tcp:8081 tcp:8081`): `movie_kind` → `enabled 1`,
  `model_ready 1` after the first movie row (lazy load, seconds — logged), **generation 13**,
  `hidden 1024`, `model_dir models/v13`, backbone sha `28270fa0…`, head sha `2f994cbe…`,
  `model_load_failures 0`; `movie_kind_backfill` block reads idle/disabled (daemon not
  started).
- Let the crawl produce **≥ 500 inline generation-13 movie rows** (category=movie ∧ adult=0 ∧
  `movie_kind_clf_ver = 13`).
- **Exit:** `/stats` healthy; 500+ gen-13 rows stored.

### Stage 3 — Gate P: mask parity, 500/500, mandatory before trusting any production write

- Extract 500 (name, `movie_kind_mask`) gen-13 rows, recompute reference masks on the
  workstation with the **shipped** GGUF + head (host llama.cpp at `18ab62a8`, pooling last,
  unnormalised reads, the D1 serving string). Require **500/500** identical label sets,
  **mask diversity** (the v9 P0 signature was one constant mask on every name), and ≥ 1
  decomposed-Unicode name (NFC path).
- This is the only check that catches the pooling-accessor P0 class, and it is cheap
  insurance even though nothing in the serving path changed. **Exit:** 500/500 or stop and fix.

### Stage 4 — destructive reset + full backfill (D1)

Runbook (all with the app force-stopped; the one-shots run as the app user via
`run-as com.torrent.app`, resolving `libdhtcrawler.so` under `nativeLibraryDir` and the
work-dir-relative DB/config exactly as the `MOVIE_KIND_BENCH`/adult-backfill maintenance
actions do — `CrawlerMaintenance.kt` is the invocation precedent):

1. **Count first:** run the backfill with `dry_run` (write nothing) to get `rows_total` for the
   v13 target — budget = rows × ~0.157 s (phone stage-0 median) + commit overhead; expect an
   hours-scale pass over the adult0 movie rows of the 965 k-row DB. Resume-safe
   (`classifier_meta.movie_backfill_last_id` = `13:<id>`), so it can be run in chunks.
2. `adb shell am force-stop com.torrent.app` — the reset is **destructive if run after a good
   pass** (`movie_backfill.h`: it wipes every `clf_ver >= 9` row — including 13s) — so it runs
   exactly once, now, while stopped, **before** the pass.
3. `--movie-kind-backfill files/crawler/data/torrents.db --reset` → clears all 10s (and the
   inline 13s), tombstones the resume key, deletes the completion key. Record `rows_reset`.
4. `--movie-kind-backfill files/crawler/data/torrents.db` (write mode, unlimited) → the full
   pass; target = the loaded manifest's generation (13). Monitor the progress counters.
5. Relaunch the app. Verify:
   - `/stats movie_kind_backfill` idle; `classifier_meta.movie_kind_clf_ver` = 13
     (`adb shell run-as com.torrent.app sqlite3` is unavailable on-device — read it via the
     crawl/`/stats` fields or the maintenance status instead);
   - DB distribution via a pulled sample or `/stats by_genre`: `movie_kind_clf_ver = 13` on
     relabelled rows, **zero** rows at 10, NULL only where classify failed or the row is not
     an adult0 movie;
   - `grep -cE 'SQLITE_CORRUPT|database disk image is malformed' files/logs/dht_crawler.log`
     = 0; WAL growth from the pass has checkpointed (free space was checked before the pass —
     the 8.2 GB DB's WAL grows with the UPDATEs).
6. **Re-run the gate P reference comparison on 500 backfilled rows** (same harness, same bar) —
   the backfill drives the identical `movie_kind_classify` path, this re-proves it on the
   labels that now matter.
- **Exit:** single-generation DB (13s only), 500/500 backfilled parity, log clean.

### Stage 5 — Gate G6: the real on-device measurement (first ever for movie-kind)

- Run the `movie-kind-bench` maintenance action (dashboard, crawler stopped for the bench run)
  **and** record `/stats movie_kind.mean_us_per_item` over a sustained live window; peak RSS
  via `/proc/<dhtcrawler-pid>/status VmHWM`; CPU temp beside every number.
- Bars: **median ≤ 300 ms, p95 ≤ 600 ms, RSS ≤ 1.2 GiB (1 144.4 MiB strict-GB), file 478.2
  MiB.** The honest prior: the manifest's 156.7/236.2 ms rows are the phone's batched CLI;
  the app's single-sequence 64-token loop is the real measurement, expected under the batched
  RSS row (1 206.7 MiB, itself a strict-GB miss by 5.5 % on the 8-sequence allocation — if the
  single-sequence loop also misses, threads/context params are the only knobs and it is an
  operator decision, as it was for v10's R4).
- **Exit:** G6 verdict recorded with both RSS readings and the bar basis named.

### Stage 6 — restart persistence + wrap-up

- Force-stop + relaunch: model-dir mtimes and `.assets_version` (`3|models/v11|models/v13`)
  identical, no restage, `/stats` generation 13, no re-run of anything.
- AGENTS.md: rewrite the movie-kind classifier section for v13 (artefact shas, generation 13,
  single-generation DB after the reset+backfill, the recorded G3 recall miss, the G6 numbers,
  the updated `.assets_version`/sweep set `{v11, v13}`); bump the quick-reference
  staged-sizes/APK numbers; note `models/v10` survives only as reinstall-rollback history.
- Update this plan's Status header with every measured number (v10-plan discipline); extend the
  `movie-kind-model-version-bump` skill with the reset+backfill relabel pattern if it
  generalises.

## 4. Budget

| stage | resource | estimate |
|---|---|---|
| 0 | workstation | ~10 min (local copy + shas) |
| 1 | workstation | ~0.5 day (patch + wiring + builds + APK check) |
| 2-3 | phone + workstation | ~1 h hands-on + a crawl window for 500 rows + the parity compute |
| 4 | phone (force-stopped) | hours-scale backfill (measure with the dry run; resumable) |
| 5 | phone | bench + a live soak window |
| 6 | — | ~1 h docs |

## 5. Risks

| # | risk | fallback |
|---|---|---|
| R1 | The pooling-accessor P0 class recurs silently (wrong read path → one constant mask) — invisible to host tests, review and `/stats` | stage 3 gate P, 500/500 + diversity, mandatory before the reset/backfill touches 965 k rows; re-run on backfilled rows in stage 4 |
| R2 | G6 median or RSS misses on the real loop (never measured for v10 either) | pre-registered levers only: re-scope the bar (operator, before ship) or thread/context tuning; a file-cap miss is impossible (478.2 MiB < 600) |
| R3 | **The reset is destructive if mis-ordered** — run after the backfill it wipes good 13 labels | runbook order is fixed: reset exactly once, app stopped, before the pass; the completion key + resume tombstone make re-runs of the pass safe |
| R4 | Backfill duration/thermal/space — hours over an 8.2 GB DB, WAL growth during UPDATEs | dry-run count first; pass is resumable per batch; thermal gate compiled in; verify free space (~115 GB was free) before starting |
| R5 | Repo weight: a second ~478 MB git blob (v10's stays in history) | accepted under the bundle precedent (P3/v10 R5); download-on-first-run remains the escape hatch, not a re-export |
| R6 | The accepted G3 `none`-recall miss (0.7368 vs the v10 head's 0.9169 on the arm's own val): relabelled rows will carry more false genre bits on empty-set movies than v10 did | recorded upstream, not waived; the paired heldout still favours v13 overall (+0.0835 micro). Search facets that filter on `none` will see the shift — if it bites, the revert is: reinstall the 0.2.0 APK (restages v10; masks are bit-compatible so 13-labelled rows stay readable) and re-run reset+backfill with the v10 manifest as target |
| R7 | Genre-bit drift between generations (would corrupt a DB carrying both) | stage-0 asserts: `genres[]` diff empty, taxonomy sha `313dafd4…` — verified equal today; the assert is cheap, the failure is not |
| R8 | A backfill classify failure leaves a row NULL forever (INSERT OR IGNORE swallows re-ingest) | the row is a re-candidate on any future pass (`clf_ver < target` predicate); failures are counted in the pass progress, non-fatal |

## 6. Sources

- Upstream plan (closed): `~/apps/classifier-model/plan/movie-kind-head-retrain.md` — H1-H4
  decisions, gate table, the v12 wash, the v13 decoder retrain; provenance
  `data-provenance-movie-kind-v12.md` §5.1, `data-provenance-movie-kind-v13.md` §1-§7.
- Artefact: `~/apps/classifier-model/models/v13/manifest.json` (sha `e0474643…`) — gates,
  thresholds, runtime budget, tokenizer, taxonomy; `export.py --mode verify` reproduced
  2026-11-14 (`build/jev/verify_v13/verify.json`).
- In-repo wiring read 2026-11-14: `app/build.gradle.kts` (patch list :569-574,
  `copyDhtCrawlerAssets` includes, versionCode :18-19), `CrawlerConfig.kt`
  (:39-40, :54-80, :244-245, :303-333), `config.ini.default` (:83-89),
  `config/CrawlerTaxonomy.kt` (:32), `CrawlerConfigTest.kt` (:264-266, :562),
  `dht_crawler/src/movie_kind.c` (`mk_parse_generation`, manifest verification, pooling),
  `include/movie_backfill.h` (reset contract, `RESET_MIN_GENERATION 9`, resume/completion keys,
  bench), `include/config.h` (:102), `src/config.c` (:130).
- Precedent plans: `plan/movie-kind-v10-streaming-integration.md` (the template this follows),
  `plan/adult-v11-integration.md` (restage/sweep/stamp discipline, maintenance-action shape).
