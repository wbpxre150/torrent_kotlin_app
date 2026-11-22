# cram-v15-integration — CRAM for category, tv_kind and adult; fastText retired

Status: **proposed, rev 2** (written 2026-11-22, revised same day). Source of truth for the
model: `~/apps/cram/PLAN_cram_replacement.md` (shipped + tv_kind v2 correction + the
**adult-coverage measurement, commit `718f9f4`**) and `~/apps/cram/tvkind/scripts/cram_repl.py`.
This plan is the *integration* of that result into the dht_crawler and the app — it does not
re-open any modelling decision.

**Rev 2 supersedes rev 1's adult decision.** Rev 1 kept fastText v14 for adult; the operator
then pointed at commit `718f9f4` ("Measure adult coverage: CRAM covers it, and beats v14's
signal"), which measured the CRAM adult head for the first time: **AUC 0.9858 vs v14
fastText's 0.8411** on the same heldout rows. fastText is therefore **not required at all** —
no `.ftz` ships, no ftc model is loaded, and one engine scores all three fields.

**Operator decisions recorded up front (2026-11-22):**

| decision | value | consequence |
|---|---|---|
| category + tv_kind | **CRAM heads** (`heads_per_field_v2.npz`) | both fastText students retired from the shipped set |
| adult | **CRAM adult marginal** (`cat_Wa`/`cat_ba`, same forward pass) | fastText retired entirely; `adult`/`adult_p` come from the masked joint's adult marginal |
| adult operating point | **threshold 0.375** — the measured recall-0.90 point (precision 0.848, 566 FP / 352 missed on 19,941 heldout rows) | operator choice among the commit's measured points; rides `adult_hide_threshold` exactly like every prior generation (tag boundary = drop boundary) |
| backfill | **none — not built, not run, not planned** | no one-shot, no reset, no maintenance action, no watermark pass. `classify_backfill_mode` stays pinned `off`; the compiled backfill paths stay untouched and unused |
| validation | **new device / clean database** | fresh install, empty crawler DB, live DHT ingest only. No `push-torrents-db.sh`, no production-DB gates. Every gate below is stated on a clean DB |

Scope: `category`, `tv_kind` and `adult` become CRAM decisions at ingest. `movie_kind`
(v13), the parser's season/episode/resolution fields, the SAF/FTP/transmission layers, and
the DB schema are **out of scope and unchanged**.

---

## 1. What ships (measured upstream — do not re-derive)

Artefact: `~/apps/cram/tvkind/data/clean_v3/heads_per_field_v2.npz`, **4,909,615 B**,
sha256 `1cad6d9f9ddfd4afd72338288d12dda6855dceda4cb531d5417b9ee22406fff2`.

- **Category head** — bit-identical to the heldout-validated v1 (`heads_per_field_sparse.npz`,
  sha256 `d89014444ad22e4c…`). Heldout, scored once: **0.9131** acc / **1,733** errors vs
  fastText B1 0.9042 / 1,911 and teacher v14 0.9084 / 1,826. Val (pruned operating point,
  keep 0.02): **1,846** errors / 0.9073.
- **tv_kind head v2** — hard targets, tv/anime population only, 800 updates, keep 0.05
  (418,603 nz; pruning costs nothing). **Val-validated only** (heldout is spent):
  `val_all` **177**/3,999, `undecided__b1_decides` **57**/817, `decided_agree` 15/2,688,
  `decided_conflict` 48/169, `gate_blocked_undecided` 57/325.
- **Adult head** (commit `718f9f4`, heldout 19,941 rows, base rate 17.65 % / 3,519 positive):

  | signal | AUC |
  |---|---:|
  | **CRAM adult marginal (shipped arrays)** | **0.9858** |
  | CRAM adult unary alone | 0.9858 |
  | `category == movie` (oracle category) | 0.8074 |
  | v14 fastText `adult_prob`, sign-corrected | 0.8411 |

  Accuracy at 0.5: **0.9533** (tp 2,970 / fp 382 / fn 549 / tn 16,040) vs 0.8235 always-no.
  Adult is genuinely modelled, not riding on category. **The unary and the marginal are
  identical for adult** — the mask that gives category its 9.35 pp does nothing here; adult
  is a separable head whose only interaction with the joint is sharing the feature matrix.
  Measured filter operating points:

  | target recall | threshold | precision | FP | missed |
  |---|---:|---:|---:|---:|
  | **0.90 (shipped)** | **0.375** | **0.848** | **566** | **352** |
  | 0.95 | 0.198 | 0.764 | 1,033 | 176 |
  | 0.99 | 0.034 | 0.527 | 3,123 | 36 |
  | clean flag ≥ 0.9 | 0.9 | 0.975 | — | recall 0.53 |

- Arrays (all float32): `cat_Wc` (1,195,982×12, 289,443 nz), `cat_Wk` (×7, 97,257 nz),
  `cat_Wa` (×2, 115,612 nz), `cat_bc/bk/ba`, `kind_W` (×7, 418,603 nz), `kind_b`,
  `cat_classes`, `kind_classes`, `states` (48), `scol/skol/asol`.
- Temperature for the **category confidence** marginal: **T = 0.7629844484210975**
  (`runs/cram_repl/stage7_heldout_probe_v100.json`, fitted on train). **The adult marginal
  is raw — no temperature.** T was fitted for category confidence only; the adult
  operating points above are on the raw marginal scale, and applying T to adult would
  silently move the threshold's meaning.

**Known regressions / accepted costs (not waived, must survive into AGENTS.md):**

1. tv_kind v1 heldout drift +2.73 pp on the 817-analogue population; v2 fixes the val
   numbers but is **val-validated only** — extrapolated heldout ≈ 83/857 is an
   extrapolation, never a measurement.
2. Val ECE 0.0222 > 0.02 bar (heldout 0.0178 passes; the criterion never named a split).
3. `decided_conflict` rows (parser hint contradicts teacher) are ~90 % error by
   construction and were never a ship target.
4. The no-gate tv_kind architecture pays **15 errors on the 2,688 `decided_agree` val
   rows** where the old parser-first rule was near-perfect, and buys back 94 conflict-row
   + 28 undecided-row errors. Accepted trade of the findings' re-plan.
5. **The shipped adult point misses ~10 % of adult rows** (352/3,519 heldout) and flags
   566 clean rows (2.8 %). In `adult_mode=drop` those false positives are *deleted*; in
   `tag` (the default) they are hidden. This is the operator-chosen recall-0.90 point,
   recorded with its measured cost — not a defect to tune away inside this plan.
6. Upstream data-quality finding (commit `718f9f4`): `v14_adult_prob` in the research
   stage-0 dump is **inverted** (P(adult=0) or swapped columns). The shipped CRAM model
   is unaffected (it trained on hard `teacher_adult` labels) and nothing in the app
   consumes that dump — recorded so no later work reads the wrong class from it.

**Do not retry (measured negatives upstream):** context/sibling features (no transfer on
the wide space), the 84-weight pairwise block (F4), distillation at inference (teacher
absent), energy-margin abstain, proximal-L1 sparsity, Adam on this space.

## 2. The decision path, before and after

Today (`src/classifier.c`, post-patch tree):

```
tparse(name, files) → ft
category = fastText category.ftz(ft)          (fallback: parser hint)
adult    = fastText adult.ftz(ft) ≥ threshold
tv_kind  = parser's concrete kind, ELSE fastText tv_kind.ftz(ft) on tv/anime rows
```

After (this plan) — **one engine, one forward pass, no fastText**:

```
tparse(name, first-50-files) → ft                       (§3 file cap)
Ec[12], Ek[7], Ea[2] = CRAM sparse dots (double accum)  (§4)
E_state = Ec[cat] + Ek[kind] + Ea[adult]  over the 48 reachable states
state*   = argmin E_state                                (first minimum wins)
category = cat(state*)                                   (masked min-marginal)
confidence = Σ states with cat(state*) of softmax(−E_state / T)
tv_kind  = argmin of the standalone kind head (7 energies) IFF category ∈ {tv, anime}
           else TP_TV_NONE                               (NO parser gate)
P_state  = softmax(−E_state)                             (raw, no T)
adult_p  = Σ states with adult=1 of P_state              (the adult marginal)
adult    = adult_p ≥ 0.375                               (tag boundary = drop boundary)
season/episode/resolution = tparse fields                (unchanged)
```

Three architecture facts from the findings are load-bearing:

- **The masked decision IS the category model.** The bare 12-column unary argmin scores
  0.8150; the same weights under the masked 3-unary argmin score 0.9085 (val). Under the
  mask `kind=none` is the only state open to non-tv/anime categories, so `u_kind` carries
  part of the category signal. Shipping the bare unary silently discards ~9 pp. The C
  engine must implement the full 48-state masked argmin, and a host test must pin the
  gap (bare-unary vs masked on the fixture set).
- **Adult is the marginal of the same joint, and equals the unary softmax.** Because
  adult is orthogonal in the mask (both adult values reachable for every (cat,kind)
  pair), `Σ P_state over adult=1` = `softmax(−Ea)[1]` exactly. Compute it as the marginal
  in the same pass (that is what the commit measured); a host test pins the
  marginal-equals-unary identity so a future mask change cannot break it silently.
- **No parser gate on tv_kind.** The parser-first composite is structurally pinned at 169
  errors on the 169 conflict rows; the head decides every tv/anime row. tparse's
  `tv_kind` hint survives only as evidence inside `ft` (`__ftv_*` tokens) and in the
  season/episode fields.

Energy convention (two independent sign bugs upstream make this a named trap):
**E is a cost; P = softmax(−E); the answer is argmin(E)**. Every C loop, test fixture and
review check uses that direction. The adult verdict is the *probability* side of the same
convention: `adult_p = P(adult=1)`, high means adult — the exact opposite of the energy
direction, and the place a sign error would silently invert the filter (the upstream
`v14_adult_prob` inversion is the cautionary example).

## 3. Feature pipeline — the exact training-time contract

The model consumes `ft`, the tparse feature text. Training-time provenance (verified
2026-11-22, not assumed):

- `ft` comes from `~/apps/classifier-model/data/feedback_category/corpus/features.jsonl`,
  produced by `cm features` (the tparse CLI) over `cm sample` records.
- **The corpus file list is capped at the first 50 files** (`sample.py FILE_LIMIT = 50`,
  `ORDER BY tf.file_index LIMIT 50`). Measured witness: the `__nfiles` token distribution
  over all 200,000 corpus rows ends at bucket 50 with a 16,377-row spike (the cap bucket)
  and no row above it. The cram workspace's own 2,000-file records fed *labels/evidence*,
  not the features CRAM was fitted on.
- The app's vendored tparse (`dht_crawler-android/tparse/`) is byte-identical to
  classifier-model's `native/tparse/` today (diff: only `cli.c`, build files and tests
  absent). Whether `features.jsonl`'s vintage matches that source is **not** assumed —
  gate G1 measures it.

**Serving rule: `classifier_classify()` caps the file evidence at the first 50 files**
(in `meta->files` order, which is torrent file order) before `tp_parse` /
`tp_feature_text`. The full list still goes to the DB and to movie_kind — the cap is
local to the classification call. Without it, large packs get `__nfiles`/`__ext`/`__ftv`
tokens the model never saw, and the tv_kind head (which lives on file evidence) is the
first casualty. The existing fastText path has the same skew today; this plan removes it.

Design matrix (`cram_repl.py ngram_vocab/ngram_design`, wide space, 1,195,982 columns):

| block | columns | rule |
|---|---:|---|
| struct markers | 1,315 | `__`-prefixed ft tokens, train df ≥ 2, sorted |
| words | 48,757 | non-`__` ft tokens, train df ≥ 2, sorted |
| word bigrams | 97,334 | adjacent **name** tokens joined by one space, train df ≥ 2, sorted |
| char n-grams | 1,048,576 | widths **2,3,4,5,6** of `<token>` (fastText markers), `zlib.crc32(utf8 bytes) & (2^20−1)`, offset after the named blocks |

Per row: whitespace-split `ft`; every struct token hits its exact column (unknown struct
→ dropped); every name token hits its word column if in vocab **and** contributes its
char-gram buckets regardless; bigrams from the name-token sequence only. Column indices
are deduped and counted within the row; value = `(float32)log1p(count)`. Column order is
structs | words | bigrams | char tail — the vocab export must preserve it exactly.

Hashing traps, both measured upstream: Python `hash()` is salted per process — the bucket
map is **crc32**, polynomial 0xEDB88320 (zlib-compatible), over the UTF-8 bytes of the
gram *including* the `<` `>` markers. And the vocab is **train-only** (df counted on the
160,146 train rows, never val/heldout) — the export ships the frozen lists, it does not
rebuild them from anything else.

## 4. The C engine (`src/cram.c`, `include/cram.h`)

New pure-C99 module in the crawler, no Android deps, host-testable (the `meta_peer_fail`
pattern). Compiled only under `CLF_ENABLED` like the rest of the real classifier; the
host `make` builds it for the test harness directly.

**Container.** The npz is a research artefact (dense float32, 133,959,964 B uncompressed;
the 4.69 MB file is deflate over mostly zeros). It is **never shipped and never re-fitted**
(`pack`'s rule: a refit is a different model and invalidates stage 7). An export script
in the cram workspace (§6) converts it to two C-friendly files:

- `cram.bin` — feature-row-major CSR: header (magic `CRAM`, format version, ncols
  1,195,982, 28 output columns, rowptr), then per-feature-row entries `(uint8 col,
  float32 weight)` for the 920,922 nonzeros, then the 28 biases, the 48-state table
  `(cat_idx, kind_idx, adult_idx)`, and T. ≈ 9.5 MB on disk and in RAM. Values are the
  **exact float32 nonzeros** of the npz arrays — the export proves bit-identity by
  reconstructing the dense arrays and comparing, then re-scoring val (§6 G0).
- `cram_vocab.bin` — the three sorted string lists (structs, words, bigrams) as an
  offset table + UTF-8 blob, plus the block offsets. UTF-8 byte order equals Python's
  codepoint sort order, so `strcmp` bsearch reproduces `dict` lookup membership exactly.

Dense-mmap was the alternative (simpler loader, ~128 MiB file-backed RSS); CSR wins on
RAM (~10 MB vs ~128 MiB touched) and per-row work (~166 features × ≤28 entries ≈ 130
multiply-adds per head-set, vs a 1.2 M-column gather).

**Loader.** `cram_load(dir, manifest)` verifies `cram.bin` and `cram_vocab.bin` against
the manifest sha256s with the existing `clf_sha256_file()` (unverified bytes are never
loaded — the classifier's standing contract), parses bounds, and freezes everything
const. Any failure is **non-fatal**: the engine reports unavailable and the classifier
falls back parser-only for **all three fields** (category hint, parser tv_kind, adult 0 /
adult_p 0.0 — the existing degraded contract, now single-engine). `model_load_failures`
and a new `cram_load_failures` counter move. No partial state.

**Feature builder.** `cram_features(ft, out_row)`:
whitespace split (ASCII space/tabs; ft is tparse-normalised — G1/G2 witness any Unicode
whitespace drift); struct/word/bigram bsearch; per-name-token char grams via a local
CRC32 (no zlib dependency; test vectors against Python `zlib.crc32`); dedup + ascending
sort of column indices with counts; values `(float)log1p((double)count)`. Per-call
scratch, no global state, thread-safe like `tp_feature_text`. A bounded per-word-type
gram cache is a G5 optimisation only if measurement asks for it — not built up front.

**Inference.** `cram_predict(row, out)` — one pass fills category, kind, confidence,
adult and adult_p:
- energies in **double**, accumulated per output column in **ascending feature-index
  order** (the nonzero subsequence of the dense dot's order); weights and values widened
  from float32 exactly as the stage-7 float32-reload path does.
- category: masked argmin over the 48 states, **first minimum wins** (numpy `argmin`
  tie-break); confidence = category marginal of `softmax(−E_state / T)`.
- kind: argmin over the 7 standalone-head energies, first minimum wins; the caller
  applies it only on CRAM-decided tv/anime.
- adult: `adult_p` = adult marginal of the **raw** `softmax(−E_state)` (no T); the
  caller compares `adult_p ≥ threshold`. The marginal-equals-unary identity is a test.
- degenerate inputs are legal: empty ft → bias-only energies → still a verdict; OOV-only
  ft → char tail only. Neither is an error.

Cross-architecture note: x86 host and arm64 device both do IEEE double on identical
float32 inputs, so disagreement can only come from summation-order/BLAS differences in
the *reference*, i.e. near-ties. G2 therefore bars on agreement with a tie analysis, not
on bit-identity (the Gate-P lesson: a constant-output P0 is invisible to shape checks, so
the fixture set must contain rows with *distinct* verdicts — including both adult values
— and the gate asserts verdict diversity, not just agreement).

## 5. Crawler integration (one patch, 25th slot)

All C changes ride **one new patch, last in the list** (`cram-category-tvkind.patch`),
authored per the `dht-crawler-patch-authoring` skill against the prepared tree.

1. **`src/cram.c` + `include/cram.h`** — §4, plus a `cram_stats_t` (rows_predicted,
   load_failures, kind_head_used, adult_tagged_predictions, feature-cap hits).
2. **`src/classifier.c`**:
   - init: load CRAM; **no ftc model is loaded at all** — `adult_model`, `category_model`,
     `tv_kind_model`, their sha fields, the lazy-load `pthread_once` and the `ftc.h`
     include go away. `clf_load_manifest()` reads the new manifest shape (§7): top-level
     `version` "v15" → `clf_ver` 15; `models.cram` / `models.cram_vocab` shas; thresholds
     block unchanged in shape (`adult_hide_threshold` 0.375).
   - classify: cap files at 50 (§3) → tparse → ft → one `cram_predict` pass fills
     category, tv_kind, confidence, adult, adult_p (§2). `classification_t` is
     **unchanged** (it already carries `adult`, `adult_p`, `clf_ver`).
   - `classifier_models_loaded()` now means "CRAM loaded and usable" — its contract
     ("the classifier owns adult tagging") and its callers are unchanged.
   - stats: `tv_kind_model_used` becomes "kind head consulted" (same counter name keeps
     the /stats key stable; its meaning is documented), add `cram` counters.
3. **`src/database.c`: no change.** The ingest bind at slot 15 already stamps
   `adult_clf_ver = c->clf_ver`, which is now *correct*: one engine (generation 15)
   produced category, tv_kind **and** adult. **No DDL change anywhere** — invariant 14
   is not triggered; `rebuild-db-v2.sh --check-schema` stays green as a guard.
4. **`src/http_api.c`**: the `classifier` /stats object keeps its threshold/counter keys;
   the three `model_sha256_{adult,category,tv_kind}` keys are replaced by `cram_sha256` /
   `cram_vocab_sha256`, and a `cram` sub-object publishes
   `{enabled, generation, temperature, rows_predicted, load_failures, kind_head_used,
   file_cap: 50}`. `classifier_write_meta()` writes `clf_ver` 15, **`adult_clf_ver` 15**
   (new key, additive), the two cram shas and the effective threshold.
5. **`Makefile`**: `tests-cram` (+ `tests-asan-cram`) added to the `tests:` aggregate —
   fixtures committed under `tests/` (exported from the Python reference, §6): crc32
   vectors; ft→(indices,values) rows incl. OOV/struct/bigram/collision cases; container
   round-trip + sha-rejection; masked-vs-bare-unary gap on the fixture set; first-minimum
   tie-break; constraint invariant (every concrete kind verdict ⇒ category tv/anime);
   **adult marginal-equals-unary identity; adult_p sign direction (a known-adult fixture
   row must score adult_p > 0.5, a known-clean row < 0.5); threshold boundary (`adult_p
   == thr` tags, `≥` semantics)**; empty/OOV ft; malformed container → non-fatal. All 14
   existing test targets unchanged and green.

**Not touched:** `classify_backfill*.c`, `movie_backfill*.c`, `movie_kind*.c`, the
`--adult-backfill` / `--movie-kind-backfill` one-shots, `classify_backfill_mode` (pinned
`off`), the config key surface (no new key — CRAM is the v15 generation's engine, gated
by the existing `classifier_enabled`; fail-open is the loader contract, not a knob).
The `dht_crawler-android` wrapper still builds/links ftc + fastText; nothing loads them.
Removing them from the wrapper build is optional cleanup, **out of scope**.

## 6. Artefact pipeline (cram workspace, read-only on classifier-model)

New `tvkind/scripts/cram_export.py` (the only new code upstream; nothing outside
`tvkind/` is written except the staged copy into the app repo):

1. Load `heads_per_field_v2.npz`; **never refit**. Rebuild the wide design from the
   frozen splits exactly as `kindfit` does (train-only vocab, `--char-ngrams 2,3,4,5,6
   --buckets 1048576 --bigrams`); assert 1,195,982 columns.
2. **G0 (export parity, host):** reconstruct dense arrays from the CSR container and
   assert bit-identity with every npz array; then re-score **val** from the exported
   container: category masked argmin must reproduce **1,846** errors, kind head
   **177** `val_all` / **57** on the 817 population (delta 0, the stage-7 reload
   discipline). Val ECE at T recomputed = 0.0222 ± 0.0005. Record the val adult
   accuracy@0.5 and AUC as witnesses (bit-identity already guarantees they match the
   npz; no pre-registered val adult bar exists — the commit's adult figures are heldout
   and are not re-scored here). Heldout is **not touched**.
3. Emit `cram.bin`, `cram_vocab.bin`, and fixture JSONs for the C tests (a stratified
   ~2,000-row sample: ft, expected indices/values, expected category/kind/confidence/
   adult/adult_p, plus the crc32 vectors), with shas recorded in a report under
   `runs/cram_repl/`.
4. Stage into `app/src/main/cpp/dht_crawler-android/models/v15/`:
   `cram.bin`, `cram_vocab.bin`, `manifest.json` (§7). **No `.ftz` file.** The export
   report's shas are the manifest's.

## 7. The v15 manifest and version semantics

`models/v15/manifest.json` (shape-compatible with the existing loader where possible):

```json
{
  "manifest_version": 1,
  "version": "v15",
  "engine": "cram",
  "models": {
    "cram":       { "file": "cram.bin", "bytes": …, "sha256": … },
    "cram_vocab": { "file": "cram_vocab.bin", "bytes": …, "sha256": … }
  },
  "thresholds": { "adult_hide_threshold": 0.375 },
  "cram": { "generation": 2, "temperature": 0.7629844484210975,
            "columns": 1195982, "states": 48, "file_cap": 50,
            "adult_threshold_recall": 0.90,
            "source_npz_sha256": "1cad6d9f9ddfd4af…" },
  "taxonomy": { "sha256": "313dafd4a5fd27e3…" }
}
```

Row stamping on the clean DB (every row is an ingest row; there are no old rows):

| column | value | meaning |
|---|---|---|
| `clf_ver` | **15** | category/tv_kind generation (manifest `version`) — the CRAM engine |
| `adult_clf_ver` | **15** | the engine that produced `adult`/`adult_p` — the same CRAM generation |
| `movie_kind_clf_ver` | 13 | unchanged |

The v11-era `clf_ver`/`adult_clf_ver` split existed to make *mixed* generations explicit
(fastText v6 category + v11 adult). v15 is single-engine, so the two columns coincide by
construction and the existing ingest bind (`c->clf_ver`) is already right — no
`classification_t` field, no bind change, no per-model manifest version. `classifier_meta`:
`clf_ver` 15, `adult_clf_ver` 15, `cram_sha256`, `cram_vocab_sha256`,
`adult_hide_threshold` 0.375. The taxonomy sha is unchanged (`313dafd4…`) — CRAM's class
order is the same 12 categories / 7 kinds / 2 adult values, verified against the npz
`cat_classes`/`kind_classes` and the states table.

## 8. App-side changes (Kotlin)

| file | change |
|---|---|
| `CrawlerConfig.kt` | `MODELS_SUBDIR = "models/v15"`; `MODEL_FILES = listOf("cram.bin", "cram_vocab.bin", "manifest.json")`; KDoc v11→v15 (no `.ftz` in the set). The `.assets_version` stamp (`<versionCode>\|models/v15\|models/v13`) changes by itself → restage with no versionCode bump required; `deleteStaleModelDirs` sweeps `v11` automatically (active set {v15, v13}) |
| `app/build.gradle.kts` | `copyDhtCrawlerAssets` include list `v11/**` → `v15/**` (v13 stays). The `dht_crawler-android/models/` staging dir keeps its retired dirs; only the include list ships |
| `assets/dht_crawler/config.ini.default` | `classifier_model_dir=models/v15`, `adult_hide_threshold=0.375`, comment updates (CRAM adult marginal, recall-0.90 point). **The template value is authoritative over the manifest** (config > manifest trap) — it must move with the generation |
| `config/AppConfig.kt` (+ `ConfigDefaults`) | `CrawlerSettings.adultHideThreshold` default **0.545726 → 0.375** |
| `config/ClassifierModelVersionMigration` | unchanged code; the v11→v15 sentinel change fires it and resets any persisted threshold to the new default (the existing generation-bump guard). On the clean test device it is a no-op by construction |
| `DaemonService.kt` | no wiring change (no new server/executor); `migrateClassifierVersion` log line picks up the new dir |
| `ControlServer.kt` | classifier panel: the three ftz sha lines → CRAM generation/sha line + `cram` counters line; the settings form's threshold default renders 0.375. Helper + exact-string tests updated |
| `config/CrawlerKeys` | **unchanged** — no new config key; `adult_hide_threshold` keeps its name and fraction validation |
| `backup/CrawlerDbSchema.kt` | **unchanged** — no DDL change; its exact-string test stays green as the guard |

Unit tests: `CrawlerConfigTest` (file list, stamp, sweep v11→v15, binary staging of the
two new files), `ClassifierModelVersionMigrationTest` (rides `MODELS_SUBDIR`, no edit
expected), `ControlServerHelpersTest` (new classifier line), any default-value assertions
on 0.545726. Run `./gradlew :app:testDebugUnitTest` (JDK 21 env).

**APK / staging budget:** staged `models/v15` ≈ 9.5 MB (cram.bin) + ~1.5 MB (vocab) +
manifest ≈ **11 MB** — vs v11's 26.6 MB three-student set. APK delta ≈ −15 MB uncompressed
assets (the CSR container deflates poorly compared with the mostly-zero npz, but all
three 8.8 MB ftz files leave). Crawler RSS: CRAM ~12 MB replaces up to three resident ftc
models (~19 MB each) — expect a **decrease**, bar in G5.

## 9. Stages and exit criteria

| stage | work | exit criterion |
|---|---|---|
| **S0** | `cram_export.py` + G0; stage `models/v15/` | G0 passes (bit-identity + val reload 1,846 / 177 / 57, delta 0); shas recorded |
| **S1** | C engine + `tests-cram` fixtures (patch 25, part 1) | `make tests-cram` + asan green; all 14 existing targets green; `prepareDhtCrawlerSrc` applies the patch last, exit 0 |
| **S2** | classifier.c / http_api.c wiring (same patch) | host build clean; `/stats` shape verified against the fixture manifest; degraded path (missing/corrupt cram.bin) exercised in a host test |
| **S3** | Kotlin §8 + unit tests | `:app:testDebugUnitTest` green; `installDebug` builds; staged assets carry v15 only, no `.ftz` |
| **S4** | host parity gates G1 + G2 | bars below met on the frozen corpus sample; any drift STOPs and is reported (no retrain in this plan) |
| **S5** | clean-device install + G3–G8 | bars below met; results appended to this file |
| **S6** | record | AGENTS.md classifier section rewritten for v15 (measured numbers only), patch list 24→25, plan status → shipped |

## 10. Gates (all on the clean device / host; no backfill anywhere)

Test device: a **fresh install** — uninstall `com.torrent.app` first (wipes `filesDir`,
i.e. the crawler DB, models and prefs) or a different physical device; `ANDROID_SERIAL`
selects. Wizard completes with defaults; the crawler ingests live DHT. **No DB push, no
`torrents.db` from the host, ever.** Row-level inspection pulls the (small, early-run)
DB copy to the host via `run-as … cat` and reads it with host `sqlite3`; live counters
come from `/stats` through `adb forward tcp:8081`.

| gate | bar | protocol |
|---|---|---|
| **G0 export parity** (host) | dense reconstruction bit-identical to every npz array; val reload **1,846 / 177 / 57**, delta 0; ECE 0.0222 ± 0.0005; val adult witnesses recorded | §6; heldout never read |
| **G1 feature-text parity** (host) | **10,000/10,000** identical `ft` between the app's tparse build (50-file cap applied) and `features.jsonl`, sampled stratified across categories incl. ≥500 tv/anime and ≥200 rows with `files_total > 50` | build the app's tparse CLI from `dht_crawler-android/tparse/` + classifier-model `cli.c`; feed corpus records (name + first-50 files); string-compare `ft`. **Any mismatch = STOP**: the corpus vintage drifted from the vendored parser; report, do not retrain inside this plan |
| **G2 C-vs-reference verdict parity** (host x86, then arm64) | host C harness vs Python reference on the G1 rows: **≥ 99.9 %** category, kind **and adult** agreement; `adult_p` abs diff ≤ 1e-6; every label mismatch a near-tie (|ΔE| between top-2 < 1e-6 relative, dumped and reviewed); verdict diversity asserted (≥ 10 distinct categories, ≥ 5 kinds, **both adult values** — the constant-mask / inverted-filter P0 witness). On-device: **≥ 98 %** agreement (Gate-P-style cross-arch floor) between DB verdicts and the Python reference on ≥ 500 ingested rows (name + first-50 files pulled from the clean DB), mismatches Hamming-bounded and dumped | the C harness reads the same fixtures the export emitted; the device leg re-derives ft on the host from the pulled rows, so parser and model are compared end-to-end |
| **G3 clean ingest smoke** | ≥ 6 h live crawl, ≥ 5,000 stored rows; `/stats classifier.enabled` true, `cram.enabled` true, `load_failures` 0, `parser_only_fallback` 0; every sampled row `clf_ver = 15`, `adult_clf_ver = 15`; category distribution non-degenerate (no single category > 60 %); constraint invariant holds in SQL: **0 rows** with concrete `tv_kind` outside tv/anime; 0 `SQLITE_CORRUPT` in the log | `/stats` + pulled-DB SQL; `log_level=INFO` (DEBUG is 1.9 GB/h) |
| **G4 CRAM adult operating point** | effective threshold **0.375** reported by `/stats` (config == manifest == template); `adult_p` ∈ [0,1] on every sampled row and non-degenerate (both sides of the threshold present); live adult tag rate reported with the heldout anchor (base rate 17.65 %, recall 0.90 / precision 0.848 at the shipped point — the live base rate differs, so report, don't bar); spot check: ≥ 20 known-adult corpus names tag, ≥ 20 known-clean names don't (fixture list from the corpus adult labels, searched via the dashboard) | `/stats classifier.adult_hide_threshold`; pulled-DB `adult_p` distribution; search API spot checks |
| **G5 cost** | mean classify ≤ **10 ms/item** (`total_us / total_classified`; the whole pass is now tparse + one CRAM forward pass); crawler `VmHWM` ≤ **2.3 GiB** during ingest with movie_kind active (the v13-era whole-process figure was ~1.95–2.04 GiB; retiring the ftc models should *lower* it — report the delta); no thermal pause attributable to classification | `/stats` sampled every 10 min; `run-as cat /proc/<pid>/status` |
| **G6 schema/backup guard** | `tools/rebuild-db-v2.sh --check-schema` green (no DDL drift); a backup/restore round-trip of the clean DB preserves `clf_ver`/`adult_clf_ver`/movie-kind columns exactly | host script + the app's backup path on the small DB |
| **G7 restart persistence** | force-stop + relaunch: `.assets_version` unchanged (`<vc>\|models/v15\|models/v13`), model mtimes unchanged (no restage), `models/` holds exactly {v15, v13}, `/stats` shas identical, `classifier_meta` unchanged, ingest resumes with no reclassification of stored rows | `run-as` stat + `/stats` before/after |
| **G8 movie-kind untouched** | generation 13, `model_ready` true, backbone/head shas unchanged, inline classification still stamps `movie_kind_clf_ver` 13 on movie rows | `/stats movie_kind` + SQL |

**Decision rule:** G1 or G2-host failing is a STOP (the artefact/parser contract is
broken; nothing ships). G2-device below 98 % is a STOP with the mismatch dump. G3–G8
failures are fix-forward inside this plan's scope. Every gate result is appended to this
file with its numbers, the way the v11/v13 plans record theirs — including misses.

## 11. Rollback

Reinstall the previous APK (0.3.0, v11 generation): its stamp restages `models/v11`
(the three ftz students), the sweep deletes `v15`, and `ClassifierModelVersionMigration`
resets the persisted threshold to the v11 default (0.545726). Rows already stamped
`clf_ver` 15 / `adult_clf_ver` 15 stay readable — they are plain integers in unchanged
columns, and the v11 code reads category/tv_kind/adult without interpreting the
generation. No mask-format coupling exists (unlike movie_kind's bit-compatible
generations). The clean test DB is disposable; rollback needs no data surgery. Keep the
0.3.0 APK until G3–G8 pass.

Optional but recommended: bump `versionCode` 3 → 4 / `versionName` "0.4.0" so the two
APKs are distinguishable in rollback history (the stamp alone would suffice for
restaging — this is for the operator, not the mechanism).

## 12. Traps (each already cost time upstream or in this repo)

1. **Energy sign.** P = softmax(−E), answer = argmin. Two independent sign bugs upstream
   (distillation gradient, torch port) and the `prune_frontier` argmax bug. Every C loop
   and fixture asserts the direction. **Adult is the probability side**: `adult_p` high
   = adult; an inverted adult read is exactly the upstream `v14_adult_prob` defect, and
   the G2 diversity check (both adult values present) is its witness.
2. **Bare unary ≠ category model.** 0.8150 vs 0.9085 — the masked 48-state decision is
   mandatory (§2), with a host test pinning the gap. (For adult the mask does nothing —
   marginal == unary — but compute the marginal anyway: it is what the commit measured.)
3. **The 50-file cap.** Training features are first-50-file features (the `__nfiles_50`
   spike is the witness). Serving the full list silently shifts the file-evidence tokens.
4. **crc32, not hash(); `<token>` markers included; UTF-8 bytes.** Python's salted
   `hash()` would differ per process; the C CRC32 must match zlib's polynomial.
5. **Train-only vocab, frozen lists.** The export ships the sorted df≥2 lists; rebuilding
   them from any other population is a vocab leak and a different model.
6. **Never refit.** `heads`/`kindfit` are unseeded; a refit invalidates stage 7. The
   export only converts and proves bit-identity (`pack`'s discipline).
7. **config > manifest for the threshold.** The template and the Kotlin default must move
   to 0.375 together with the manifest, or the device keeps a stale operating point. The
   C compiled-in default (0.917039, `src/config.c`) stays as the unreachable last resort
   — recorded, not shipped.
8. **The threshold is on CRAM's probability scale, not fastText's.** 0.375 is the
   measured recall-0.90 point of the CRAM adult marginal; no fastText-calibrated value
   (0.545726, 0.898355, 0.917039) means anything on it. And the adult marginal is **raw**
   — the category temperature T must not be applied to it.
9. **The npz is not the container.** 4.69 MB compressed ≠ 133.9 MB dense ≠ 3.51 MB
   nonzeros. The shipped CSR is ~9.5 MB; all three figures are distinct quantities.
10. **Heldout is spent.** No gate in this plan reads it. Val reloads are the only
    permitted re-scores, and they are parity checks, not selection. The commit's adult
    figures are cited as measured upstream, never re-derived here.
11. **Vendored tree stays pristine.** All C changes through patch 25 against the prepared
    tree (skill: `dht-crawler-patch-authoring`); `prepareDhtCrawlerSrc` wipes direct edits.
12. **`log_level=DEBUG` writes ~1.9 GB/h.** INFO for every device gate.

## 13. Reproduce (planned)

```sh
# S0 — export + G0 (cram workspace; classifier-model read-only)
cd ~/apps/cram/tvkind
~/apps/classifier-model/.venv/bin/python scripts/cram_export.py          # writes runs/cram_repl/export.json
# stages ~/apps/torrent_kotlin_app/app/src/main/cpp/dht_crawler-android/models/v15/

# S1/S2 — patch 25 (app repo; JDK 21 env per AGENTS.md)
cd ~/apps/torrent_kotlin_app
./gradlew :app:prepareDhtCrawlerSrc
make -j"$(nproc)" -C app/build/native/src/dht_crawler
make -C app/build/native/src/dht_crawler tests tests-cram tests-asan-cram

# S3 — Kotlin
./gradlew :app:testDebugUnitTest installDebug

# S4 — host parity (harness built from the app's tparse + cram fixtures)
# G1: ft parity over 10,000 corpus records; G2: C-vs-Python verdicts incl. adult_p

# S5 — clean device
adb -s "$ANDROID_SERIAL" uninstall com.torrent.app        # wipes DB + models + prefs
adb -s "$ANDROID_SERIAL" install app/build/outputs/apk/debug/app-debug.apk
# wizard → live crawl ≥ 6 h → G3–G8 per §10
```

## 14. What this plan deliberately does not do

- **No fastText anywhere in the shipped product** (operator decision, rev 2): no `.ftz`
  asset, no ftc model load, no fastText fallback. The wrapper still links ftc/fastText;
  removing that build wiring is optional cleanup, out of scope.
- **No backfill of any kind** (operator decision): no reclassify pass, no reset, no new
  maintenance action, no watermark. The existing backfill code stays compiled, pinned
  off, and unused; on a clean DB there is nothing to backfill.
- **No retrain, no refit, no feature change, no threshold tuning.** The model is the
  frozen v2 artefact and 0.375 is the commit's measured recall-0.90 point; the only new
  upstream code is the export/parity script. Moving the operating point later is a
  config change (the key already exists), not a model change.
- **No parser-first gate, no abstain, no context features, no pairwise block** — all
  measured and settled in the findings (§1 do-not-retry list).
- **No schema change, no new config key, no movie_kind change.**
- **No production-DB validation.** Every device gate runs on the clean install; the
  6.1 M-row production DB and its rollback copies are not touched by this plan.
