# cram-v15-integration — CRAM category/tv_kind heads, fastText v14 adult

Status: **proposed** (written 2026-11-22). Source of truth for the model: `~/apps/cram/PLAN_cram_replacement.md`
(shipped + post-ship tv_kind v2 correction) and `~/apps/cram/tvkind/scripts/cram_repl.py`.
This plan is the *integration* of that result into the dht_crawler and the app — it does not
re-open any modelling decision.

**Operator decisions recorded up front (2026-11-22):**

| decision | value | consequence |
|---|---|---|
| adult filter | **fastText, v14 student** (`models/v14/adult.ftz`) | adult stays an ftc model; only the weights and the operating point move (v11 → v14). CRAM's internal `u_adult` unary is *not* the product adult verdict |
| category + tv_kind | **CRAM heads** (`heads_per_field_v2.npz`) | both fastText students (`category.ftz`, `tv_kind.ftz`) are retired from the shipped set |
| backfill | **none — not built, not run, not planned** | no one-shot, no reset, no maintenance action, no watermark pass. `classify_backfill_mode` stays pinned `off`; the compiled adult-backfill/movie-kind backfill paths stay untouched and unused |
| validation | **new device / clean database** | fresh install, empty crawler DB, live DHT ingest only. No `push-torrents-db.sh`, no production-DB gates. Every gate below is stated on a clean DB |

Scope: `category` and `tv_kind` become CRAM decisions at ingest; `adult` becomes the v14
fastText decision at the v14 calibrated threshold. `movie_kind` (v13), the parser's
season/episode/resolution fields, the SAF/FTP/transmission layers, and the DB schema are
**out of scope and unchanged**.

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
- Arrays (all float32): `cat_Wc` (1,195,982×12, 289,443 nz), `cat_Wk` (×7, 97,257 nz),
  `cat_Wa` (×2, 115,612 nz), `cat_bc/bk/ba`, `kind_W` (×7, 418,603 nz), `kind_b`,
  `cat_classes`, `kind_classes`, `states` (48), `scol/skol/asol`.
- Temperature for the category confidence marginal: **T = 0.7629844484210975**
  (`runs/cram_repl/stage7_heldout_probe_v100.json`, fitted on train).
- Adult v14: `~/apps/classifier-model/models/v14/adult.ftz`, **8,864,493 B**, sha256
  `bf7bb8997b2be150256970e596f6f525f884297fa7961aea7a237c4a00eb173b`; manifest thresholds
  `adult_hide_threshold` **0.898355** (hide precision 0.9703 / recall 0.6254 on its heldout),
  `adult_drop_threshold` 0.9909 (unused — the app's drop boundary is the hide boundary).

**Known regressions carried from the findings (not waived, must survive into AGENTS.md):**

1. tv_kind v1 heldout drift +2.73 pp on the 817-analogue population; v2 fixes the val
   numbers but is **val-validated only** — extrapolated heldout ≈ 83/857 is an
   extrapolation, never a measurement.
2. Val ECE 0.0222 > 0.02 bar (heldout 0.0178 passes; the criterion never named a split).
3. `decided_conflict` rows (parser hint contradicts teacher) are ~90 % error by
   construction and were never a ship target.
4. The no-gate architecture pays **15 errors on the 2,688 `decided_agree` val rows** where
   the old parser-first rule was near-perfect, and buys back 94 conflict-row + 28
   undecided-row errors (net −114 vs the v1 distilled head, −180 vs the best rule-first
   arm). This is the accepted trade of the findings' re-plan, not a defect to fix here.

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

After (this plan):

```
tparse(name, first-50-files) → ft                       (§3 file cap)
Ec[12], Ek[7], Ea[2] = CRAM sparse dots (double accum)  (§4)
state*   = argmin over the 48 reachable states of Ec[cat] + Ek[kind] + Ea[adult]
category = cat(state*)                                   (masked min-marginal)
confidence = Σ states with cat(state*) of softmax(−E_state / T)
tv_kind  = argmin of the standalone kind head (7 energies) IFF category ∈ {tv, anime}
           else TP_TV_NONE                               (NO parser gate)
adult    = fastText v14 adult.ftz(ft) ≥ 0.898355         (unchanged mechanism)
season/episode/resolution = tparse fields                (unchanged)
```

Three architecture facts from the findings are load-bearing:

- **The masked decision IS the category model.** The bare 12-column unary argmin scores
  0.8150; the same weights under the masked 3-unary argmin score 0.9085 (val). Under the
  mask `kind=none` is the only state open to non-tv/anime categories, so `u_kind` carries
  part of the category signal. Shipping the bare unary silently discards ~9 pp. The C
  engine must implement the full 48-state masked argmin, and a host test must pin the
  gap (bare-unary vs masked on the fixture set).
- **CRAM's `u_adult` is internal to the category decision only.** The product `adult` /
  `adult_p` columns come from the v14 fastText student, per the operator decision. The two
  adult notions may disagree on a row; that is by design and is not a bug to reconcile.
- **No parser gate on tv_kind.** The parser-first composite is structurally pinned at 169
  errors on the 169 conflict rows; the head decides every tv/anime row. tparse's
  `tv_kind` hint survives only as evidence inside `ft` (`__ftv_*` tokens) and in the
  season/episode fields.

Energy convention (two independent sign bugs upstream make this a named trap):
**E is a cost; P = softmax(−E); the answer is argmin(E)**. Every C loop, test fixture and
review check uses that direction.

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
first casualty. The existing fastText path has the same skew today; this plan removes it
for CRAM and for the v14 adult student alike.

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
const. Any failure is **non-fatal**: the engine reports unavailable, the classifier
falls back parser-only for category/kind (existing degraded path), adult keeps working,
and `model_load_failures` / a new `cram_load_failures` counter move. No partial state.

**Feature builder.** `cram_features(ft, out_row)`:
whitespace split (ASCII space/tabs; ft is tparse-normalised — G1/G2 witness any Unicode
whitespace drift); struct/word/bigram bsearch; per-name-token char grams via a local
CRC32 (no zlib dependency; test vectors against Python `zlib.crc32`); dedup + ascending
sort of column indices with counts; values `(float)log1p((double)count)`. Per-call
scratch, no global state, thread-safe like `tp_feature_text`. A bounded per-word-type
gram cache is a G5 optimisation only if measurement asks for it — not built up front.

**Inference.** `cram_predict(row, out)`:
- energies in **double**, accumulated per output column in **ascending feature-index
  order** (the nonzero subsequence of the dense dot's order); weights and values widened
  from float32 exactly as the stage-7 float32-reload path does.
- category: masked argmin over the 48 states, **first minimum wins** (numpy `argmin`
  tie-break); confidence = category marginal of `softmax(−E_state / T)`.
- kind: argmin over the 7 standalone-head energies, first minimum wins; the caller
  applies it only on CRAM-decided tv/anime.
- degenerate inputs are legal: empty ft → bias-only energies → still a verdict; OOV-only
  ft → char tail only. Neither is an error.

Cross-architecture note: x86 host and arm64 device both do IEEE double on identical
float32 inputs, so disagreement can only come from summation-order/BLAS differences in
the *reference*, i.e. near-ties. G2 therefore bars on agreement with a tie analysis, not
on bit-identity (the Gate-P lesson: a constant-output P0 is invisible to shape checks, so
the fixture set must contain rows with *distinct* verdicts and the gate asserts verdict
diversity, not just agreement).

## 5. Crawler integration (one patch, 25th slot)

All C changes ride **one new patch, last in the list** (`cram-category-tvkind.patch`),
authored per the `dht-crawler-patch-authoring` skill against the prepared tree.

1. **`src/cram.c` + `include/cram.h`** — §4, plus a `cram_stats_t` (rows_predicted,
   load_failures, kind_head_used, feature-cap hits).
2. **`src/classifier.c`**:
   - init: load CRAM beside the adult model; `category.ftz` / `tv_kind.ftz` are **not
     loaded and not shipped** — their manifest entries, sha fields and lazy-load
     `pthread_once` go away. `clf_load_manifest()` reads the new manifest shape (§7):
     top-level `version` "v15" → `clf_ver` 15; `models.adult.version` "v14" → a new
     `adult_clf_ver` field; `models.cram` / `models.cram_vocab` shas; thresholds block
     unchanged in shape.
   - classify: cap files at 50 (§3) → tparse → ft → CRAM category/kind (§2) → fastText
     adult on the same ft. `classification_t` gains `long long adult_clf_ver`;
     `out->clf_ver` stays the manifest generation (15).
   - stats: `tv_kind_model_used` becomes "kind head consulted" (same counter name keeps
     the /stats key stable; its meaning is documented), add `cram` counters.
3. **`src/database.c`**: the ingest bind at slot 15 (`adult_clf_ver`, today `c->clf_ver`)
   binds `c->adult_clf_ver` instead. **No DDL change anywhere** — the column already
   exists in all five C places and the Kotlin mirror, so invariant 14 is *not* triggered;
   `rebuild-db-v2.sh --check-schema` must stay green as a guard, not as a migration.
4. **`src/http_api.c`**: the `classifier` /stats object keeps every existing key;
   `model_sha256_category` / `model_sha256_tv_kind` are replaced by `cram_sha256` /
   `cram_vocab_sha256`, and a `cram` sub-object publishes
   `{enabled, generation, temperature, rows_predicted, load_failures, kind_head_used,
   file_cap: 50}`. `classifier_meta` writes the same shas + `clf_ver` 15 +
   `adult_clf_ver` 14 + the effective threshold.
5. **`Makefile`**: `tests-cram` (+ `tests-asan-cram`) added to the `tests:` aggregate —
   fixtures committed under `tests/` (exported from the Python reference, §6): crc32
   vectors; ft→(indices,values) rows incl. OOV/struct/bigram/collision cases; container
   round-trip + sha-rejection; masked-vs-bare-unary gap on the fixture set; first-minimum
   tie-break; constraint invariant (every concrete kind verdict ⇒ category tv/anime);
   empty/OOV ft; malformed container → non-fatal. All 14 existing test targets unchanged
   and green.

**Not touched:** `classify_backfill*.c`, `movie_backfill*.c`, `movie_kind*.c`, the
`--adult-backfill` / `--movie-kind-backfill` one-shots, `classify_backfill_mode` (pinned
`off`), the config key surface (no new key — CRAM is the v15 generation's engine, gated
by the existing `classifier_enabled`; fail-open is the loader contract, not a knob).

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
   discipline). Val ECE at T recomputed = 0.0222 ± 0.0005. Heldout is **not touched**.
3. Emit `cram.bin`, `cram_vocab.bin`, and fixture JSONs for the C tests (a stratified
   ~2,000-row sample: ft, expected indices/values, expected category/kind/confidence,
   plus the crc32 vectors), with shas recorded in a report under `runs/cram_repl/`.
4. Stage into `app/src/main/cpp/dht_crawler-android/models/v15/`:
   `adult.ftz` (byte-copy of classifier-model `models/v14/adult.ftz`), `cram.bin`,
   `cram_vocab.bin`, `manifest.json` (§7). The export report's shas are the manifest's.

## 7. The v15 manifest and version semantics

`models/v15/manifest.json` (shape-compatible with the existing loader where possible):

```json
{
  "manifest_version": 1,
  "version": "v15",
  "engine": "cram",
  "models": {
    "adult":      { "file": "adult.ftz", "version": "v14",
                    "bytes": 8864493, "sha256": "bf7bb899…" },
    "cram":       { "file": "cram.bin", "bytes": …, "sha256": … },
    "cram_vocab": { "file": "cram_vocab.bin", "bytes": …, "sha256": … }
  },
  "thresholds": { "adult_hide_threshold": 0.898355 },
  "cram": { "generation": 2, "temperature": 0.7629844484210975,
            "columns": 1195982, "states": 48, "file_cap": 50,
            "source_npz_sha256": "1cad6d9f9ddfd4af…" },
  "taxonomy": { "sha256": "313dafd4a5fd27e3…" }
}
```

Row stamping on the clean DB (every row is an ingest row; there are no old rows):

| column | value | meaning |
|---|---|---|
| `clf_ver` | **15** | category/tv_kind generation (manifest `version`) — the CRAM engine |
| `adult_clf_ver` | **14** | the adult student that produced `adult`/`adult_p` (manifest `models.adult.version`) |
| `movie_kind_clf_ver` | 13 | unchanged |

`adult_clf_ver` keeps its exact v11 semantics ("which adult student scored this row"),
which is why the ingest bind moves from `c->clf_ver` to the new per-model field instead
of stamping 15 over a v14 verdict. `classifier_meta`: `clf_ver` 15, `adult_clf_ver` 14,
`model_sha256_adult`, `cram_sha256`, `cram_vocab_sha256`, `adult_hide_threshold`
0.898355. The taxonomy sha is unchanged (`313dafd4…`) — CRAM's class order is the same
12 categories / 7 kinds, verified against the npz `cat_classes`/`kind_classes`.

## 8. App-side changes (Kotlin)

| file | change |
|---|---|
| `CrawlerConfig.kt` | `MODELS_SUBDIR = "models/v15"`; `MODEL_FILES = listOf("adult.ftz", "cram.bin", "cram_vocab.bin", "manifest.json")`; KDoc v11→v15. The `.assets_version` stamp (`<versionCode>\|models/v15\|models/v13`) changes by itself → restage with no versionCode bump required; `deleteStaleModelDirs` sweeps `v11` automatically (active set {v15, v13}) |
| `app/build.gradle.kts` | `copyDhtCrawlerAssets` include list `v11/**` → `v15/**` (v13 stays). The `dht_crawler-android/models/` staging dir keeps its retired dirs; only the include list ships |
| `assets/dht_crawler/config.ini.default` | `classifier_model_dir=models/v15`, `adult_hide_threshold=0.898355`, comment updates. **The template value is authoritative over the manifest** (config > manifest trap) — it must move with the generation |
| `config/AppConfig.kt` (+ `ConfigDefaults`) | `CrawlerSettings.adultHideThreshold` default **0.545726 → 0.898355** |
| `config/ClassifierModelVersionMigration` | unchanged code; the v11→v15 sentinel change fires it and resets any persisted threshold to the new default (the existing generation-bump guard). On the clean test device it is a no-op by construction |
| `DaemonService.kt` | no wiring change (no new server/executor); `migrateClassifierVersion` log line picks up the new dir |
| `ControlServer.kt` | classifier panel: category/tv_kind sha lines → CRAM generation/sha line + `cram` counters line; the settings form's threshold default renders 0.898355. Helper + exact-string tests updated |
| `config/CrawlerKeys` | **unchanged** — no new config key |
| `backup/CrawlerDbSchema.kt` | **unchanged** — no DDL change; its exact-string test stays green as the guard |

Unit tests: `CrawlerConfigTest` (file list, stamp, sweep v11→v15, binary staging of the
two new files), `ClassifierModelVersionMigrationTest` (rides `MODELS_SUBDIR`, no edit
expected), `ControlServerHelpersTest` (new classifier line), any default-value assertions
on 0.545726. Run `./gradlew :app:testDebugUnitTest` (JDK 21 env).

**APK / staging budget:** staged `models/v15` ≈ 8.9 MB (adult.ftz) + ~9.5 MB (cram.bin)
+ ~1.5 MB (vocab) + manifest ≈ **20 MB** — *smaller* than v11's 26.6 MB three-student set.
APK delta ≈ +5 MB compressed (the CSR container deflates poorly compared with the
mostly-zero npz, but two 8.8 MB ftz files leave). Crawler RSS: one resident ftc model
(~19 MB) + CRAM ~12 MB replaces up to three ftc models — expect a **decrease**, bar in G5.

## 9. Stages and exit criteria

| stage | work | exit criterion |
|---|---|---|
| **S0** | `cram_export.py` + G0; stage `models/v15/` | G0 passes (bit-identity + val reload 1,846 / 177 / 57, delta 0); shas recorded |
| **S1** | C engine + `tests-cram` fixtures (patch 25, part 1) | `make tests-cram` + asan green; all 14 existing targets green; `prepareDhtCrawlerSrc` applies the patch last, exit 0 |
| **S2** | classifier.c / database.c / http_api.c wiring (same patch) | host build clean; `/stats` shape verified against the fixture manifest; degraded path (missing/corrupt cram.bin) exercised in a host test |
| **S3** | Kotlin §8 + unit tests | `:app:testDebugUnitTest` green; `installDebug` builds; staged assets carry v15 only |
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
| **G0 export parity** (host) | dense reconstruction bit-identical to every npz array; val reload **1,846 / 177 / 57**, delta 0; ECE 0.0222 ± 0.0005 | §6; heldout never read |
| **G1 feature-text parity** (host) | **10,000/10,000** identical `ft` between the app's tparse build (50-file cap applied) and `features.jsonl`, sampled stratified across categories incl. ≥500 tv/anime and ≥200 rows with `files_total > 50` | build the app's tparse CLI from `dht_crawler-android/tparse/` + classifier-model `cli.c`; feed corpus records (name + first-50 files); string-compare `ft`. **Any mismatch = STOP**: the corpus vintage drifted from the vendored parser; report, do not retrain inside this plan |
| **G2 C-vs-reference verdict parity** (host x86, then arm64) | host C harness vs Python reference on the G1 rows: **≥ 99.9 %** category and kind agreement, every mismatch a near-tie (|ΔE| between top-2 < 1e-6 relative, dumped and reviewed); verdict diversity asserted (≥ 10 distinct categories, ≥ 5 kinds — the constant-mask P0 witness). On-device: **≥ 98 %** agreement (Gate-P-style cross-arch floor) between DB verdicts and the Python reference on ≥ 500 ingested rows (name + first-50 files pulled from the clean DB), mismatches Hamming-bounded and dumped | the C harness reads the same fixtures the export emitted; the device leg re-derives ft on the host from the pulled rows, so parser and model are compared end-to-end |
| **G3 clean ingest smoke** | ≥ 6 h live crawl, ≥ 5,000 stored rows; `/stats classifier.enabled` true, `cram.enabled` true, `load_failures` 0, `parser_only_fallback` 0; every sampled row `clf_ver = 15`, `adult_clf_ver = 14`; category distribution non-degenerate (no single category > 60 %); constraint invariant holds in SQL: **0 rows** with concrete `tv_kind` outside tv/anime; 0 `SQLITE_CORRUPT` in the log | `/stats` + pulled-DB SQL; `log_level=INFO` (DEBUG is 1.9 GB/h) |
| **G4 adult v14 operating point** | effective threshold **0.898355** reported by `/stats` (config == manifest == template); adult tag rate within the v14 calibration envelope (heldout positive-rate 11.4 % at hide — expect single-digit % on a live crawl, report the number); spot check: ≥ 20 known-adult names tag, ≥ 20 known-clean names don't (fixture list from the corpus' adult labels, searched via the dashboard) | `/stats classifier.adult_hide_threshold`; search API spot checks |
| **G5 cost** | mean classify ≤ **10 ms/item** (`total_us / total_classified`; CRAM's budget is "a few ms/row" on top of tparse+adult); crawler `VmHWM` ≤ **2.3 GiB** during ingest with movie_kind active (the v13-era whole-process figure was ~1.95–2.04 GiB; the engine swap should *lower* it — report the delta); no thermal pause attributable to classification | `/stats` sampled every 10 min; `run-as cat /proc/<pid>/status` |
| **G6 schema/backup guard** | `tools/rebuild-db-v2.sh --check-schema` green (no DDL drift); a backup/restore round-trip of the clean DB preserves `clf_ver`/`adult_clf_ver`/movie-kind columns exactly | host script + the app's backup path on the small DB |
| **G7 restart persistence** | force-stop + relaunch: `.assets_version` unchanged (`<vc>\|models/v15\|models/v13`), model mtimes unchanged (no restage), `models/` holds exactly {v15, v13}, `/stats` shas identical, `classifier_meta` unchanged, ingest resumes with no reclassification of stored rows | `run-as` stat + `/stats` before/after |
| **G8 movie-kind untouched** | generation 13, `model_ready` true, backbone/head shas unchanged, inline classification still stamps `movie_kind_clf_ver` 13 on movie rows | `/stats movie_kind` + SQL |

**Decision rule:** G1 or G2-host failing is a STOP (the artefact/parser contract is
broken; nothing ships). G2-device below 98 % is a STOP with the mismatch dump. G3–G8
failures are fix-forward inside this plan's scope. Every gate result is appended to this
file with its numbers, the way the v11/v13 plans record theirs — including misses.

## 11. Rollback

Reinstall the previous APK (0.3.0, v11 generation): its stamp restages `models/v11`,
the sweep deletes `v15`, and `ClassifierModelVersionMigration` resets the persisted
threshold to the v11 default. Rows already stamped `clf_ver` 15 / `adult_clf_ver` 14
stay readable — they are plain integers in unchanged columns, and the v11 code reads
category/tv_kind/adult without interpreting the generation. No mask-format coupling
exists (unlike movie_kind's bit-compatible generations). The clean test DB is disposable;
rollback needs no data surgery. Keep the 0.3.0 APK until G3–G8 pass.

Optional but recommended: bump `versionCode` 3 → 4 / `versionName` "0.4.0" so the two
APKs are distinguishable in rollback history (the stamp alone would suffice for
restaging — this is for the operator, not the mechanism).

## 12. Traps (each already cost time upstream or in this repo)

1. **Energy sign.** P = softmax(−E), answer = argmin. Two independent sign bugs upstream
   (distillation gradient, torch port) and the `prune_frontier` argmax bug. Every C loop
   and fixture asserts the direction.
2. **Bare unary ≠ category model.** 0.8150 vs 0.9085 — the masked 48-state decision is
   mandatory (§2), with a host test pinning the gap.
3. **The 50-file cap.** Training features are first-50-file features (the `__nfiles_50`
   spike is the witness). Serving the full list silently shifts the file-evidence tokens.
4. **crc32, not hash(); `<token>` markers included; UTF-8 bytes.** Python's salted
   `hash()` would differ per process; the C CRC32 must match zlib's polynomial.
5. **Train-only vocab, frozen lists.** The export ships the sorted df≥2 lists; rebuilding
   them from any other population is a vocab leak and a different model.
6. **Never refit.** `heads`/`kindfit` are unseeded; a refit invalidates stage 7. The
   export only converts and proves bit-identity (`pack`'s discipline).
7. **config > manifest for the threshold.** The template and the Kotlin default must move
   to 0.898355 together with the manifest, or the device keeps the v11 operating point on
   v14 weights. The C compiled-in default (0.917039, `src/config.c`) stays as the
   unreachable last resort — recorded, not shipped.
8. **`adult_clf_ver` semantics.** Stamping 15 over a v14 adult verdict would mislabel the
   generation exactly the way the v11 plan's `clf_ver`/`adult_clf_ver` split exists to
   prevent — hence the per-model manifest version and the bind change.
9. **The npz is not the container.** 4.69 MB compressed ≠ 133.9 MB dense ≠ 3.51 MB
   nonzeros. The shipped CSR is ~9.5 MB; all three figures are distinct quantities.
10. **Heldout is spent.** No gate in this plan reads it. Val reloads are the only
    permitted re-scores, and they are parity checks, not selection.
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
# G1: ft parity over 10,000 corpus records; G2: C-vs-Python verdicts

# S5 — clean device
adb -s "$ANDROID_SERIAL" uninstall com.torrent.app        # wipes DB + models + prefs
adb -s "$ANDROID_SERIAL" install app/build/outputs/apk/debug/app-debug.apk
# wizard → live crawl ≥ 6 h → G3–G8 per §10
```

## 14. What this plan deliberately does not do

- **No backfill of any kind** (operator decision): no reclassify pass, no reset, no new
  maintenance action, no watermark. The existing backfill code stays compiled, pinned
  off, and unused; on a clean DB there is nothing to backfill.
- **No retrain, no refit, no feature change.** The model is the frozen v2 artefact; the
  only new upstream code is the export/parity script.
- **No parser-first gate, no abstain, no context features, no pairwise block** — all
  measured and settled in the findings (§1 do-not-retry list).
- **No CRAM adult verdict.** `u_adult` participates in the category mask only; the
  product adult field is fastText v14 (operator decision).
- **No schema change, no new config key, no movie_kind change.**
- **No production-DB validation.** Every device gate runs on the clean install; the
  6.1 M-row production DB and its rollback copies are not touched by this plan.
