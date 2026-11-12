# PLAN_adult_v11 — integrate `models/v11` (fastText adult/category/tv_kind) + a token-gated adult backfill into dht_crawler and the app

Status: **shipped on the reference device; code and docs committed in the app repo.** Upstream
(`~/apps/classifier-model/plan/adult-kind.md`, provenance `~/apps/classifier-model/data-provenance-adult.md`)
exported `models/v11`; the integration half landed as `8138dc9` (stage `models/v11`, resync tparse with
the adult token stage), `f26fd1c` (crawler patch 16: token-gated adult backfill, `adult_clf_ver`,
`classify_backfill_mode`), `38eab5c` (wire `models/v11`, pin `classify_backfill_mode=off`, adult-backfill
maintenance action) and `051b450` (dry run walks the token gate, terminal counters surfaced). Device
build: APK 548,821,733 B, `versionCode` 2 / `versionName` "0.2.0", packaged `models/v11` + `models/v10`
(no `v6`). Unit tests 1,180 / 93 suites green; host crawler `make tests` green including
`tests-adult-backfill`.

**Measured gates.** Every figure below is from this run; the two misses are stated as misses.

| gate | bar | measured | verdict |
|---|---|---|---|
| **G1 parser parity** | 10,000/10,000 identical, ≥ 200 rows per `__adult_*` family | 10,000/10,000 identical; quotas jav 259 / site 243 / set 200 / none 9,306; negative control (pre-Stage-1 parser) 694 of 10,000 rows differ, `__adult_*` tokens only, host side only | **PASS** |
| **G2 model parity** | ≥ 99 % labels, `max abs Δadult_p` ≤ 1e-3, 500 rows | 500/500 label agreement, max abs Δadult_p 0.000000, 0 rows over tolerance | **PASS** |
| **G3 candidate set** | pre-registered 2,886–24,049 (0.3–2.5 %) | dry run gated in 24,435 of 961,969 = **2.5401 % — MISSED by 386 rows (1.6 %)**; band re-derived to 1.0–8.0 % under operator authorisation; actual write 24,528 = 2.5411 %, inside the re-derived band | **MISSED, then re-derived** |
| **G4 no collateral damage** | 0 differing sampled rows | 200 untouched rows: 0 differing on category/tv_kind/clf_ver/adult/adult_p; 24,435 backfilled rows: 0 changed on category/tv_kind/clf_ver; write reconciles exactly (`clf_ver` 6 AND `adult_clf_ver` 11 = 24,528 = the run's `updated`; `adult_clf_ver IS NULL` = 940,702 = its `gated_out`) | **PASS** |
| **G5 backfill cost** | ≤ 30 min, VmHWM ≤ 300 MiB, no restart | ~2.5 min for 965,230 scanned / 24,528 scored; **peak RSS VmHWM 1,832,772 kB ≈ 1.75 GiB — MISS**; no crawler restart, `/stats` 200 | **PARTIAL — RSS bar MISSED** |
| **G6 = D1** | catch ≥ 60 %, ≤ 2 flips of 500 | catch 99.00 % (396/400), 0 flips; paired v6 recall 98.50 %, v11 catches 6 rows v6 missed, loses 0 | **PASS** |
| **G7 no regression** | movie_kind generation 10, same shas | generation 10, hidden 1024, backbone `02555750…`, head `077aaf11…`, `model_ready` true, `movie_kind_backfill` idle with `rows_processed` 0 | **PASS** |
| **G8 rollback** | previous APK restores `models/v6` | reinstall 0.1.0 / `versionCode` 1. This device does not exercise the backup path, so the `SELECT *` backup-copy caveat is a non-issue here; keep the revert-build note for devices that do back up | **available, not exercised** |
| **criterion 11 restart persistence** | no restage, no re-run | after force-stop + relaunch: model-dir mtimes and `.assets_version` (versionCode 2, `models/v11`, `models/v10`) identical, `clf_ver` 11, `classifier_backfill` `disabled`, `adult_backfill` idle with no re-run | **PASS** |

**G3 miss — cause established, not assumed.** Corpus composition, not token over-fire: the identical
parser and lists fire **0.7352 %** on `~/torrents.db` (6,015,586 rows) versus **2.5328 %** on the device
corpus (950,993 rows), driven by `__adult_site` at 9.3×; 25/25 sampled `__adult_site` matches are genuine
curated adult studios. Operator rejected R3 re-curation on that evidence. The re-derived band keeps the
original 0.4×–3.2× margins re-anchored to the measured 2.5401 % census, so a 3× explosion still trips the
ceiling.

**G5 RSS miss, plainly.** The 300 MiB bar was set for a model-free host measurement. The likely cause is
the on-device movie_kind backbone (~478 MiB Q4_0) plus the 8.2 GB DB page cache; that cause is **not
proven** and the bar is not re-derived here.

**Coverage bound on D1 — the most important qualifier on the ship decision.** D1's sample is ~80 %
backfilled rows, so a 99 % catch measures the *fidelity of the write*, not the reach of the backfill. An
unbiased 20,000-row random sample scores **19.14 %** reference-adult (3,828/20,000), i.e. ~184,745 adult
rows in the 965,230-row DB; the token gate selected 24,528 of them — **≈ 13.3 % coverage**. The remaining
~87 % of adult rows keep their v6-era labels (`clf_ver` 6, `adult_clf_ver` NULL). The full `clf_ver` 11
reclassify that would cover them is deliberately **not** run in this release.

Shipped configuration: `classify_backfill_mode` pinned **`off`** by operator decision (the daemon publishes
state `disabled`, never polls; its `adult` branch stays implemented and unpinned), so the write is driven
only by `--adult-backfill <db> [--write] [--adult-backfill-limit <n>]` through the dashboard maintenance
action. Docs: `AGENTS.md` → *fastText adult classifier (v11)*.

Upstream is a **partial win**, and that shapes everything below:

| upstream gate | v6 | v11 | bar | verdict |
|---|---:|---:|---|---|
| corpus held-out `P@hide` | 0.9701 | 0.9700 | ≥ 0.970 | pass |
| corpus held-out `R@hide` | 0.5880 | **0.7127** | ≥ 0.700 | pass |
| pooled acceptance `P@hide` | 0.955 | 0.946 | ≥ 0.940 | pass |
| pooled acceptance `R@hide` | 0.670 | **0.715** | ≥ 0.750 | **miss** |
| jav_code probe `R@hide` | 0.949 | 0.756 | ≥ 0.900 | **miss** |
| jav_code probe, best `R` at `P≥0.94` | 0.852 | **0.972** | — | gain |
| pooled acceptance FP (of 6,967) | 17 | 22 | ≤ 35 | pass |
| category / tv_kind accuracy | 0.9023 / 0.9398 | 0.9080 / 0.9385 | within noise | pass |

The model knows curated JAV codes much better (0.972 vs 0.852 at matched precision), but the
calibrated threshold (0.545726, down from 0.891912) lands where code rows are still below it.
Precision is intact and nothing regressed. **D1 is therefore the ship-or-hold decision**, and
stage 3 measures the product-side number that decides it on real device rows rather than on
the acceptance samples.

---

## 0. Decisions

| # | decision | status |
|---|---|---|
| **D1** | **Ship gate.** Stage and integrate v11, but flip the app's default only after stage 3's device measurement: on a device sample of 500 rows the v11 adult verdict must catch **≥ 60 %** of the rows a workstation reference calls adult (v6 catches ~67 % on the acceptance samples), with **≤ 0.5 %** of the sample flipped adult=1 by mistake. A miss keeps v6 staged and the plan records the numbers. | operator |
| **D2** | **One staged fastText dir.** `models/v11/` replaces `models/v6/` as `classifier_model_dir`; v6 is not kept side by side (the sweep already retires `v<digits>` dirs). Rollback is the previous APK. | proposed |
| **D3** | **Adult backfill = token-gated, on device** (§2.2). Only rows whose name/file evidence fires a new `__adult_*` token are re-scored — ~47 k of 6.1 M rows (0.78 %), minutes not hours. **The ordinary `clf_ver 11` full reclassify is explicitly out of scope for this plan** and is gated behind a new config key so the app does not silently start a multi-hour pass on the phone. | proposed |
| **D4** | **Additive schema**: `torrent_class.adult_clf_ver INTEGER`. `clf_ver` keeps meaning "category/tv_kind generation" and stays 6 for every row this plan does not touch; `adult_clf_ver` records which adult student produced `adult`/`adult_p`. No column is dropped or rewritten. | proposed |
| **D5** | **Parser parity is a gate, not a hope.** The `dht_crawler-android/tparse/` copy is resynced from classifier-model (including the generated `adult_terms.h`), and stage 2 proves byte-identical feature text on 10,000 names. | proposed |
| **D6** | **`movie_kind` (v10) is untouched.** No change to `movie_kind_model_dir`, the streaming ingest hook or the JEVH artefact. | proposed |
| **D7** | **Ingest needs no code change.** The `__adult_*` tokens come from the parser, so once the staged parser and model are v11, new rows are classified by v11 automatically. | proposed |

---

## 1. The artefact (stage 0)

`~/apps/classifier-model/models/v11/` → `app/src/main/cpp/dht_crawler-android/models/v11/`:

| file | bytes | sha256 |
|---|---:|---|
| `adult.ftz` | 8,863,323 | `7b8a3272642133dbf56b0a44d78dedfdc28a1a63b2800c96ab3cc8923d0f9412` |
| `category.ftz` | 8,857,514 | `2d4dcd7c6c4cc50dd078667872985fe1ce58d2a56dd9103828b700e4b6dd9271` |
| `tv_kind.ftz` | 8,883,074 | `35c0ac36f50ef4e2950bc8189990faf9759f3c1ed1e28278bc07cce1cfa13440` |
| `manifest.json` | 8,241 | the classifier-model manifest, byte-identical (tracked there as `models/v11/manifest.json`) |

Properties this plan stands on:

- **Thresholds ride the manifest.** `src/classifier.c:229` reads
  `thresholds.adult_hide_threshold` from the staged manifest and only falls back to
  `adult_hide_threshold` in `config.ini` (then `CLF_ADULT_HIDE_DEFAULT`). v11 ships
  **`adult_hide_threshold` 0.545726** and **`adult_drop_threshold` 0.949021**; no code change
  and no `CrawlerKeys` change are needed to serve the new operating point.
- **`clf_ver` parses from the manifest version.** `classifier_current_version()` returns
  `clf.clf_ver`, parsed from `"v11"` → 11. `classifier_meta` (key/value) is rewritten at init
  with `clf_ver`, the three `.ftz` sha256s and the effective adult threshold, so the shipped
  state is auditable from the DB.
- **Size is a non-issue.** 26.6 MB of `.ftz` for the three students, unchanged in kind from
  v6 (26.6 MB) — the APK's weight is still dominated by the v10 backbone (478.2 MiB).
- **New deterministic evidence.** `tparse` now emits up to three extra whole-token features
  after the extension tokens: `__adult_jav` (curated studio prefix + 2–5 digits),
  `__adult_site` (curated site/studio term as one token or two adjacent tokens joined by one
  separator), `__adult_set` (a nudity term **and** an AI/checkpoint marker anywhere in the
  name or file paths). Lists live in classifier-model `config/adult_terms.json` and are
  generated into `adult_terms.h` by `cm gen-header`. Whole-token only: `private`, `babes`,
  `art`, `model-3` and `x265-10bit` never fire (measured on the teacher-labelled acceptance
  rows: jav 0.952, site 0.947, set 1.000 precision).
- **Feature text changes for ~0.8 % of rows.** On the 144,248 re-renderable corpus rows the
  diff was exactly 1,200 rows, all differing by `__adult_*` tokens only, 0 by anything else.

---

## 2. Code deltas (facts read from the current tree on 2026-11-12)

### 2.1 Parser sync (no patch — this tree is ours)

`app/src/main/cpp/dht_crawler-android/tparse/` is a **copy** of classifier-model's
`native/tparse/` (`CMakeLists.txt:45-50` builds `tparse.c` + `name.c` from it). Copy
`tparse.c` (the `put_adult_tokens` addition), `adult_terms.h` (new, generated) and — if the
`name.c`/`tparse.h`/`tparse_internal.h` diff is non-empty — those too. `taxonomy.h` is
unchanged (taxonomy v1). Keep the C11/libc-only contract: no allocation, one `strcmp` per
candidate, two fixed 33-byte token buffers.

The generated header must be regenerated, never hand-edited:
`cd ~/apps/classifier-model && uv run cm gen-header` (writes `native/tparse/adult_terms.h`),
then copy. `tests/test_gen_header.py` in classifier-model already asserts the committed header
matches a fresh generation.

### 2.2 Crawler patch (`adult-v11-backfill.patch`, appended last)

**a. Token-gated adult backfill — new.** Add `--adult-backfill <db> [--write]
[--adult-backfill-limit <n>]` beside the existing `--classify-backfill`
(`src/main.c:241-300`), reusing the engine's record builder and batch transaction:

- Candidate scan: `SELECT t.id, t.name, t.size_bytes FROM torrents t JOIN torrent_class tc ON
  tc.torrent_id = t.id WHERE t.id > ? AND t.id <= ? AND (tc.adult_clf_ver IS NULL OR
  tc.adult_clf_ver < ?)` — the `classify_backfill.c:576` pattern with the new column.
- **Gate per row, before any inference:** compute `tp_feature_text(name, files, …)` and skip
  unless the text contains `__adult_jav`, `__adult_site` or `__adult_set`. File lists are only
  read for rows that already passed a name-only token test, so the pass touches ~47 k rows'
  file tables instead of 286 M file rows.
- Score with the **adult student only** (`classifier_classify` already returns the full
  `classification_t`; use `out.adult`/`out.adult_p`), then
  `UPDATE torrent_class SET adult = ?, adult_p = ?, adult_clf_ver = ? WHERE torrent_id = ?`
  in the engine's per-batch `BEGIN … COMMIT`.
- Respect `adult_mode`: in `drop` mode the same rows would have been discarded at ingest, so
  the backfill **tags** them (`adult=1`) and reports the count; it never deletes. Deleting is
  a separate, operator-only decision.
- Idempotent and resumable: `adult_clf_ver` is the watermark, `--adult-backfill-limit` chunks
  it, and a re-run picks up where it stopped.

**b. Schema — additive column.** `src/database.c:72`-area DDL and the `torrent_class` CREATE:
add `adult_clf_ver INTEGER` (NULL default). Add the `ALTER TABLE … ADD COLUMN` migration for
existing DBs (guarded by a `PRAGMA table_info(torrent_class)` check, the pattern the repo
already uses for additive columns), update `tools/schema-v2.sql`, and extend the 14-column
INSERT in `batch_writer.c`/`database.c` so **ingest stamps `adult_clf_ver =
classifier_current_version()`** in the same transaction (a fresh row is classified by v11 by
construction, D7).

**c. Config — do not let the bump start a multi-hour pass (D3).**
`classify_backfill_daemon_start(&g_app_ctx, &g_database)` at `src/main.c:1158` targets
`classifier_current_version()`, so staging v11 alone makes it reclassify **all 6.1 M rows** in
`CB_BATCH_ROWS` batches. Add one allow-listed key, `classify_backfill_mode` (`all` | `adult` |
`off`, default `all` so nothing changes for an operator who wants it), read in
`classify_backfill_daemon.c`: `adult` makes the daemon run the §2.2a pass instead of the
full one; `off` makes it idle. The app pins `adult` for this release. `CrawlerKeys` gets the
key in the allow-list (invariant 7) with the same validation as `adult_mode`.

**d. `/stats`.** Extend the `classifier` object with `adult_clf_ver_target`,
`adult_rows_at_prev_ver` and the effective `adult_drop_threshold`, and add an
`adult_backfill` object mirroring `classifier_backfill`
(`target_clf_ver`/`rows_total`/`rows_processed`/`rows_left`/`rows_gated_out`). The existing
`adult_tagged`/`adult_dropped`/`mean_us_per_item` counters already give the throughput
number stage 3 needs.

**e. Host tests.** Plain-C99 half: token-gate fixtures (a `__adult_jav` row is selected, a
`Top 100` row is gated out), the `adult_clf_ver` watermark (a re-run processes 0 rows), the
`drop`-mode tag-not-delete rule, and the migration on a v6-shaped DB. The inference itself is
not host-testable (the host build stays plain C99); stage 2's parity harness is what covers
the model.

**Patch mechanics** (invariant 2): develop in the vendored repo (own `.git`, never `lib/`),
commit, `git format-patch`, drop into `dht_crawler/patches/`, append the filename **last** in
the fixed-order list in `app/build.gradle.kts` (after `movie-kind-v10-streaming.patch`), then
re-run the Gradle build so `mergeDebugJniLibFolders` regenerates `libdhtcrawler.so`
(invariant 4).

### 2.3 App (Kotlin)

- `CrawlerConfig.kt`: `MODELS_SUBDIR = "models/v11"`, the file list stays
  `["adult.ftz", "category.ftz", "tv_kind.ftz", "manifest.json"]`, and the stamp becomes
  `<versionCode>|models/v11|models/v10` (a stamp change is what restages — no `versionCode`
  bump strictly required, but a release bump is normal).
- `build.gradle.kts` `copyDhtCrawlerAssets`: `include("v6/**", "v10/**")` →
  `include("v11/**", "v10/**")`.
- `CrawlerKeys`: allow-list `classify_backfill_mode` (invariant 7); `PINNED_KEYS` pins it to
  `adult` for this release.
- `CrawlerMaintenance.kt`: new `MaintenanceAction.ADULT_BACKFILL` running
  `--adult-backfill data/torrents.db --write` (every maintenance action already stops the
  supervised crawler first). Expose it on the dashboard beside the existing movie-kind bench.
- Dashboard: render the new `adult_backfill` block; no new server/executor (invariant 12).
- Kotlin tests: `CrawlerConfigTest` (subdir, file list, stamp), `CrawlerKeysTest` (the new
  key is accepted, unknown keys still rejected), `CrawlerMaintenanceTest` (argv shape).

### 2.4 Parity harness (stage 2 exit)

Two checks, both cheap and both mandatory:

1. **Feature text**: 10,000 names sampled read-only from `~/torrents.db`, rendered by
   classifier-model's `build/tparse/tparse` and by the NDK-built crawler parser
   (`--adult-backfill <db> --adult-backfill-limit 10000` without `--write` prints the gated
   subset; add a `--dump-features` debug flag in the same patch, or compare through
   `classify_backfill`'s existing dry-run output). Require **10,000/10,000 identical**,
   including at least 200 names that fire each family and 200 negatives.
2. **Adult verdicts**: 500 rows from the device DB scored by classifier-model's
   `tclassify-db`/fastText reference on the workstation and by the crawler; require
   **≥ 99 % identical `adult` labels** and `|Δ adult_p| ≤ 1e-3`.

---

## 3. Stages

### Stage 0 — artefact + parser staging (workstation, ~15 min)

rsync `models/v11/{adult,category,tv_kind}.ftz,manifest.json` into
`dht_crawler-android/models/v11/` and sha256-verify against §1; regenerate and copy
`adult_terms.h`, `tparse.c`. **Exit:** the four files verify; `git status` shows the new model
dir staged for the stage-1 commit (the repo already commits model bytes, precedent: v9/v10).

### Stage 1 — crawler patch + app wiring (workstation, ~1 day)

Write §2.2 as one patch, wire §2.3, apply the patch list, build.
**Exit:** host crawler tests green (`make -C app/build/native/src/dht_crawler` after
`./gradlew :app:prepareDhtCrawlerSrc`), `./gradlew :app:testDebugUnitTest` green,
`./gradlew installDebug` produces an APK whose assets contain
`dht_crawler/models/v11/{adult,category,tv_kind}.ftz,manifest.json` and **not** `models/v6`.
Record APK size and the `ensureAssets` staged total (~+0.0 MB net: 26.6 MB replaces 26.6 MB).

### Stage 2 — parity (workstation, ~1 h)

§2.4. **Exit:** 10,000/10,000 feature texts identical; ≥ 99 % identical adult labels on 500
rows. A miss stops the plan — the failure mode (a parser copy that drifted) is silent in
production.

### Stage 3 — device: targeted adult backfill + D1 measurement (~2-3 h hands-on)

1. Install, let `ensureAssets` restage, confirm `/stats classifier` reports the v11 shas,
   `adult_hide_threshold` 0.545726 and `mean_us_per_item` on live rows.
2. **Measure the candidate set before writing:** run `--adult-backfill --limit 0` (report
   only) and record `rows_total` / `rows_gated_out`. Prediction from the workstation census:
   ~47 k rows total (jav_code 34,258 + adult_site 10,607 + photo_set 2,034 + ai_image 509 of
   6,098,645). A total above ~150 k means the token lists over-fire on this DB — stop and
   re-measure rather than writing.
3. **Write**, chunked (`--adult-backfill-limit 20000`), watching `/stats adult_backfill` and
   the crawler log. Expect minutes, not hours; record rows/s and CPU temp.
4. **D1 measurement:** sample 500 backfilled rows + 500 untouched `adult=0` rows, score them
   on the workstation with the shipped v11 `.ftz` (reference), and compare to the DB's
   `adult`/`adult_p`. Also re-score the same 1,000 rows with v6 and report the paired
   confusion (v6 missed → v11 catches; v6 caught → v11 loses). This is the number D1 turns on.
5. **Acceptance:** `classify_backfill_mode=adult` means the full pass never starts
   (`/stats classifier_backfill` stays `idle`); `grep -cE 'SQLITE_CORRUPT|malformed'` on the
   crawler log is 0; the adult filter in the UI now hides the backfilled rows; no row's
   `category`/`tv_kind` changed (spot-check 200 rows against a pre-backfill export).
6. **Exit:** D1 verdict recorded with both bars; if it fails, restage v6 (previous APK) and
   write up the partial result — v6 remains shipped, nothing is ship-on-fail.

### Stage 4 — wrap-up (~1 h)

- `AGENTS.md`: rewrite the fastText paragraph — `models/v11`, the `__adult_*` tokens, the new
  thresholds, `adult_clf_ver`, the token-gated backfill, `classify_backfill_mode`, and the
  D1 numbers. Note that the full `clf_ver 11` reclassify is deliberately not run.
- `README`/plan Status header: the measured device numbers.
- Cross-reference classifier-model's `data-provenance-adult.md` (frozen hashes, teacher pass,
  gate table) from this plan.

---

## 4. Gates (pre-registered, frozen before stage 1)

| gate | bar |
|---|---|
| **G1 parser parity** | 10,000/10,000 feature texts identical between the workstation `tparse` and the NDK crawler parser; ≥ 200 positives and 200 negatives in the sample |
| **G2 model parity** | ≥ 99 % identical `adult` labels, `\|Δ adult_p\| ≤ 1e-3` on 500 device rows |
| **G3 candidate set is targeted** | `rows_total` between 20 k and 150 k on a 6.1 M-row DB (i.e. 0.3–2.5 %) |
| **G4 no collateral damage** | 200 sampled untouched rows keep `category`/`tv_kind`/`clf_ver` byte-identical; `classifier_meta.clf_ver` = 11 only for rows the pass wrote |
| **G5 backfill cost** | ≤ 30 min for the full candidate set at `--adult-backfill-limit 20000`, peak RSS ≤ 300 MiB, no crawler restart |
| **G6 D1 product gate** | on the 500-row device sample: v11 catches ≥ 60 % of the workstation-reference adult rows and flips ≤ 0.5 % of the reference non-adult rows |
| **G7 no regression** | `/stats classifier.adult_dropped` unchanged for live ingest; the movie-kind v10 block untouched (generation 10, hidden 1024, same shas) |
| **G8 rollback is one APK** | reinstalling the previous build restores `models/v6` via the stamp change and leaves the DB readable (the additive column is ignored by the older crawler) |

---

## 5. Budget

| stage | resource | estimate |
|---|---|---|
| 0 | workstation + LAN | 15 min (27 MB rsync + header regen) |
| 1 | workstation | ~1 day (patch + tests + APK check) |
| 2 | workstation | ~1 h (two parity runs) |
| 3 | phone + workstation | ~2-3 h hands-on; the backfill itself is minutes |
| 4 | — | ~1 h docs |
| **wall clock** | | **~1.5 working days**, dominated by the patch + device session |

---

## 6. Risks

| risk | sign | fallback |
|---|---|---|
| **R1 parser copy drifts from classifier-model** — the device emits different feature text, every verdict is quietly wrong | stage 2 G1 | the header is generated and the parity harness is a gate, not a spot check; a miss stops the plan |
| **R2 the bump starts a multi-hour full reclassify** on a phone | `/stats classifier_backfill` leaves `idle` | D3's `classify_backfill_mode=adult` pinned by the app; `off` is the belt-and-braces setting |
| **R3 token lists over-fire on the real DB** (mojibake names, non-JAV releases) | stage 3 step 2 `rows_total` above 150 k | the lists are curated and whole-token; drop the family that over-fires from `config/adult_terms.json`, regenerate, re-parity. Never loosen the gate to hit a schedule |
| **R4 D1 fails** (v11 catches fewer than 60 % on device rows) | stage 3 step 4 | v6 stays shipped; the plan records the measured cells. The upstream partial win (G2/G4 missed) makes this a live possibility, not a formality |
| **R5 `adult_mode=drop` semantics** — a backfilled row would have been discarded at ingest | rows tagged instead of dropped | tag is the safe direction (hides the row, keeps it); deletion stays an operator-only action with its own plan |
| **R6 additive column breaks an older reader** | rollback APK reads the DB | SQLite readers ignore unknown columns; G8 asserts it on the real DB before the plan closes |
| **R7 the v11 category/tv_kind students are never applied** (only adult is backfilled) | mixed generations in one DB | `clf_ver` stays 6 and `adult_clf_ver` = 11, so the state is explicit rather than mislabelled; the full pass is a follow-up decision with its own plan |

---

## 7. Sources

- Upstream plan + provenance: `~/apps/classifier-model/plan/adult-kind.md`,
  `~/apps/classifier-model/data-provenance-adult.md` (frozen corpus shas, the 30 k teacher
  pass, the stratum yield table, the gate table).
- Artefact: `~/apps/classifier-model/models/v11/manifest.json` (tracked there), thresholds
  `adult_hide_threshold` 0.545726 / `adult_drop_threshold` 0.949021.
- Parser: `~/apps/classifier-model/native/tparse/{tparse.c,adult_terms.h}`,
  `config/adult_terms.json`; the device copy is `dht_crawler-android/tparse/`
  (`CMakeLists.txt:45-50`).
- In-repo wiring read on 2026-11-12: `src/classifier.c` (`adult_hide_threshold` from the
  manifest at :229, `classifier_current_version()` at :521), `src/classify_backfill.c`
  (candidate query :576, meta keys :412), `src/classify_backfill_daemon.c` (poll loop,
  `CB_BATCH_ROWS`), `src/main.c` (:241 `--classify-backfill`, :1158 daemon start),
  `src/database.c` (:72 `classifier_meta` DDL), `src/tree_metadata.c` (:1582 `adult_mode=drop`,
  :1644 stored-adult note), `src/config.c` (:122 `adult_mode` default `tag`),
  `src/http_api.c` (:674 `classifier` stats), `CrawlerConfig.kt` (`MODELS_SUBDIR`,
  `.assets_version` stamp), `app/build.gradle.kts` (`copyDhtCrawlerAssets`, patch list).
