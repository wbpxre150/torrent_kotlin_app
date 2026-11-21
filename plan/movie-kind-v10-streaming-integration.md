# PLAN_movie_kind_v10 — streaming integration of `models/v10` (Qwen3.5-0.8B decoder JEV, nemotron arm, Q4_0) into dht_crawler + the app

Status: **stage 0 complete (2026-11-11); stage 1 is next.** The upstream model plan
(`~/apps/classifier-model/plan/movie-kind-qwen35.md`, v3) is complete through its stage 7:
both arms cleared every measurable gate, and the stage-7 one-variable check picked
**`models/v10-nemotron-q4`** as the artefact to stage — the nemotron fine-tune with Q4_0
pinned (a 0.95 s `select` re-run of stage 5's Q4_K_M selection):

| v3 gate | value | bar |
|---|---:|---|
| G1 held-out micro-F1 | 0.5521 | ≥ 0.50 |
| G2 held-out macro-F1 | 0.5462 | ≥ 0.35 |
| G3 `none` P / R | 0.9567 / 0.9171 | ≥ 0.85 each |
| G7 runtime parity (f16 + C head) | 1 500 / 1 500 = 100 % | ≥ 98 % |
| G8 paired vs v9-on-nemotron | +0.0866 (0.5521 vs 0.4656) | ≥ +0.03 |
| G4 wiki150 agreement | 36/46 = 0.7826 | ≥ 0.287 |
| wikieval2 external micro-F1 | 0.4536 (beats Q4_K_M 0.4514 and the v2-source arm 0.4450) | reported |
| **G6 phone, real loop** | **pending — this plan's stage 3 measures it** | median ≤ 300 ms, p95 ≤ 600 ms, RSS ≤ 1.2 GB, file ≤ 600 MiB |

Everything upstream (strip, export, D5 re-fit, parity, eval) is done and sha-pinned; this plan
only has to get the artefact into the APK and the crawler classifying **at ingest**, because the
product was re-scoped (U3): **no backfill, streaming only**, and the operator will run the app on
a **fresh phone with a new crawl**, so every stored row is classified by v10 inline.

---

## 0. Decisions (operator, taken 2026-11-11)

| # | decision |
|---|---|
| P1 | Artefact = **`models/v10-nemotron-q4`** (classifier-model repo): nemotron fine-tune, **Q4_0**, backbone 478.2 MiB, JEVH head 1024×16. Manifest version `"v10-nemotron-q4"` parses to generation **10** (`mk_parse_generation` takes the digits), so `movie_kind_clf_ver` becomes 10 with no code change. |
| P2 | Stage dir = **`app/src/main/cpp/dht_crawler-android/models/v10/`** — the generation-name convention (`v<digits>`), so the `ensureAssets` sweep auto-cleans it on any future bump. The name intentionally differs from classifier-model's `models/v10-nemotron-q4` (and from classifier-model's `models/v10`, which is the *v2-source arm* — never sync from there); the manifest's sha256 pins are what certify the binaries, and they verify. |
| P3 | **Bundle in the APK** exactly like v6/v9 (`copyDhtCrawlerAssets`). No download-on-first-run machinery. Cost: ~+478 MB of git blob (precedent: v9's 160 MB GGUF is committed) and an APK that carries ~505 MB of models (v6 27 MB + v10 478 MB) — under the model plan's ~750 MB R6 flag. |
| P4 | **Streaming only.** movie_kind classification moves into the ingest path (`tree_metadata_worker_func`); the `movie_backfill_daemon` is **no longer started** in `main.c`. The backfill engine/CLI/tests stay compiled (the `--movie-kind-bench` maintenance action and `--movie-kind-backfill` one-shots keep working; nothing re-runs them in production). |
| P5 | **Fresh device, fresh DB.** The operator installs on a different phone that has never run the app, completes the first-run wizard personally, and the crawler starts a new crawl from an empty database. No DB-reset action is built; the 28.6 GiB `torrents-v2` install on the current phone is untouched. |
| P6 | Crawler-side changes ship as **one new patch** in `dht_crawler/patches/` appended to the fixed-order patch list in `build.gradle.kts` (AGENTS invariant 2: never edit vendored sources in place; `prepareDhtCrawlerSrc` re-applies). |

## 1. The artefact (stage 0 — DONE, 2026-11-11)

Fetched over rsync from `adam@192.168.0.24:~/classifier-model/models/v10-nemotron-q4/` into
`app/src/main/cpp/dht_crawler-android/models/v10/` and sha256-verified against the manifest:

| file | bytes | sha256 |
|---|---:|---|
| `movie_kind_backbone_Q4_0.gguf` | 501 452 416 (478.2 MiB) | `02555750a2994a6f4d6e95703c0cb854b7f0015790e81ed134bf4044cadeb05d` |
| `movie_kind_head.bin` | 65 712 (JEVH, 1024×16) | `077aaf116e1194677360eac24f8da7ac1965e6369eb4a6304c1bc29925a09bc5` |
| `manifest.json` | 16 531 | `7a9cc7c16243dfd802956e72ff2d2d0478b2ee8af2c8a70973b4531f22640100` (byte-identical to the committed classifier-model manifest) |

Properties this plan stands on (all from the manifest / v3 stage 5-6):

- **hidden 1024** (the crawler already validates `llama_model_n_embd == manifest.hidden` at
  `mk_model_ensure_locked` — no hardcode exists; only the *host test fixtures* are 768 today).
- **Pooling `last`**: the GGUF itself carries `qwen35.pooling_type = 3`, `add_bos_token=false`,
  `add_eos_token=false`, `eos_token_id 248044`, written at convert time. The crawler's *context*
  parameter, however, is built with `LLAMA_POOLING_TYPE_CLS` (`movie_kind.c:546`) — **this is the
  one P0-class code delta** (see stage 1a).
- **Tokenizer**: Qwen BPE, no specials; stage 6 verified `llama_tokenize` parity 10 000/10 000 on
  real names, 59/59 round-trips over the 64-token window. The crawler's
  `llama_tokenize(..., add_special=true, ...)` is inert for this vocab (no BOS/EOS configured) —
  unchanged.
- **NFC is load-bearing**: llama.cpp's qwen35 path ignores the GGUF's NFC normaliser exactly as
  `modern-bert` did; `movie_kind_render.c` (utf8proc, `MK_NFC_ENABLED` in the NDK build) already
  normalises before tokenizing. Reused verbatim — the stage-11 P0 fix stays paid for.
- **Quantisation cost 84.40 %** identical label sets vs the f16 reference (234/1500 rows differ,
  201 in one genre), with the D5 head re-fitted on the quant's own states: val micro-F1 0.5961,
  −0.64 pt vs the fp32 T arm. Expected, not a defect; the on-device spot check compares against
  the **same shipped GGUF** reference, so this cost is invisible there.
- **Taxonomy**: 16 genres in manifest order — action, comedy, drama, horror, thriller, scifi,
  fantasy, animation, documentary, romance, crime, adventure, family, mystery, war, western —
  bit *j* of the mask is `manifest.genres[j]`, `none` = 0. Stage 1 asserts this order against
  `taxonomy.h` and against v9's manifest (same taxonomy sha `313dafd4…`), because the DB rows
  written by generation 9 and 10 must stay bit-compatible.
- **llama.cpp toolchain**: the app already pins `18ab62a8ec5144ae5d2f65e78374ba69652d75dd`
  (`fetchLlamaCpp`/`buildLlama`) — the *same* commit the entire v1/v2/v3 export + parity chain
  used. The GGUF is guaranteed loadable; **no build-graph change at all** (MK_ENABLED is already
  wired via `LLAMA_INSTALL`).

## 2. Code deltas in detail (all facts read from the current tree)

### 2.1 Crawler (one patch, `movie-kind-v10-streaming.patch`)

**a. Pooling accessor — the P0-class change.** `src/movie_kind.c:546`:
`context_params.pooling_type = LLAMA_POOLING_TYPE_CLS` → `LLAMA_POOLING_TYPE_LAST`. The read
stays `llama_get_embeddings_seq(mk.context, 0)` — with pooling LAST that accessor returns the
sequence's final-position state, which for the single unpadded sequence is exactly the
last-token state the head was fitted on (R11: the Android loop is single-sequence, where the
left/right-pad conventions coincide). Update the comments in `movie_kind.c` (the "pooled CLS
vector" doc at :568-575, the wrong-accessor P0 history) and the `movie_kind.h` header doc
("unnormalised CLS embedding", "768") to say last-token / 1024. The stage-9 P0 was exactly this
accessor class: `llama_get_embeddings()` returned zeros under CLS pooling and every movie got one
constant mask (20744 on all 500 names) — invisible to review, host tests and `/stats`, caught
only by the on-device mask spot check. That check is therefore **mandatory** (stage 2, gate P).

**b. Streaming classification at ingest — new.** Today *only* the backfill daemon classifies;
the ingest path never calls `movie_kind_classify`. Hook site: `tree_metadata.c ::
tree_metadata_worker_func`, right after the survivor classification block (~:1577) and before
`batch_writer_add` (~:1651):

- Predicate: `db_meta.has_classification && db_meta.classification.category == TP_CAT_MOVIE &&
  db_meta.classification.adult == 0 && movie_kind_enabled()` — the same membership rule as the
  backfill engine (`include/movie_backfill.h:17`), minus the generation test (a fresh row has
  no generation yet; it is labelled now or left NULL on failure).
- Carry `movie_kind_mask` + a `has_movie_kind` flag on the database metadata struct; extend the
  `torrent_class` INSERT in `batch_writer.c`/`database.c` to write `movie_kind_mask` and
  `movie_kind_clf_ver = movie_kind_generation()` inline in the same batch transaction (the
  columns exist since v9; today they are written NULL at insert and only the backfill UPDATEs
  them). A classify failure stores the row unlabelled — the `classifier_classify()` contract,
  non-fatal.
- Threading: **inline first** (recommended). `movie_kind_classify` is mutex-serialised behind
  one model+context, ~0.4 s/name predicted — a movie row holds its *own* metadata worker for
  that span, but the other workers keep fetching; the streaming regime is a few names/s, so the
  tree pool has slack. The first call also pays the lazy model load (mmap of 478 MiB, seconds —
  logged at INFO, already implemented). **Pre-registered fallback** if tree throughput visibly
  degrades (metadata queue depth, per-tree `last_metadata_time` stalls): a shallow
  classify-queue worker thread with the same predicate, updating rows post-insert with the
  backfill's UPDATE statement — no new decision, just plumbing.
- Thermal: no gating inline (the `movie_kind_thermal_backoff_c` reader stays for the one-shot
  CLI paths). Live ingest is burst-light; the G6 measurement records CPU temp so a thermal
  problem is visible in stage 3 rather than assumed away.

**c. Stop starting the backfill daemon.** `src/main.c:1162`
`movie_backfill_daemon_start(...)` — remove the start (and the matching
`movie_backfill_request_shutdown` at :1189; `movie_backfill_daemon_destroy` can stay as a
no-op-safe teardown or go with it). The engine, `--movie-kind-backfill` CLI, `--reset`,
`movie-kind-bench`, tests and `/stats` wiring all stay: a fresh DB has no candidates, nothing in
production ever calls them, and the maintenance actions keep working for diagnostics. `/stats`
`movie_kind_backfill` will permanently read `idle`/`disabled` — leave the block (and the
dashboard panel) as-is; it is honest ("no backfill configured") and avoids dashboard churn.

**d. `config.c` default** `movie_kind_model_dir` `"models/v9"` → `"models/v10"`
(consistency only — the app always pins the key via `PINNED_KEYS`).

**e. Host tests.** `tests/test_movie_kind.c` fixtures move from hidden 768 → 1024 (synthetic
provider vectors, synthetic manifest); add a streaming-write test (ingest path with the seam
provider → `torrent_class` row carries mask + clf_ver 10; failure path stores NULL); assert the
genre order against `taxonomy.h`. The pooling change itself cannot be host-tested (MK_ENABLED is
NDK-only) — that is what the device spot check is for. Host suite: `make -C
app/build/native/src/dht_crawler` after `./gradlew :app:prepareDhtCrawlerSrc`.

**Patch mechanics**: develop in the vendored repo (own `.git`, never edit `lib/`), commit,
export with `git format-patch`, drop into `dht_crawler/patches/`, append the filename to the
fixed-order list in `build.gradle.kts` (after `maintenance-progress.patch`). Re-run the Gradle
build — invariant 4: `mergeDebugJniLibFolders` regenerates `jniLibs/arm64-v8a/libdhtcrawler.so`.

### 2.2 App (Kotlin)

- `CrawlerConfig.kt`: `MOVIE_MODEL_SUBDIR = "models/v10"`; `MOVIE_MODEL_FILES =
  ["manifest.json", "movie_kind_backbone_Q4_0.gguf", "movie_kind_head.bin"]` (the file name
  changes with the quant); KDoc updated (Q4_0, hidden 1024). The stamp
  `<versionCode>|models/v6|models/v10` restages at an unchanged versionCode; sweep deletes any
  stale `v<digits>` dir (v9 on an old device — moot on the fresh phone). Bump `versionCode` as
  usual for a release.
- `build.gradle.kts` `copyDhtCrawlerAssets`: `include("v6/**", "v9/**")` → `include("v6/**",
  "v10/**")` (the task already deletes its destination wholesale, so no stale-v9-in-APK risk).
- `CrawlerKeys` / `PINNED_KEYS`: no change — `movie_kind_model_dir` already pins to
  `MOVIE_MODEL_SUBDIR`.
- Kotlin tests: `CrawlerConfigTest` (and any test asserting the v9 file list/stamp) updated.
- Dashboard: nothing required — `/stats movie_kind` already publishes generation, hidden,
  model_dir, shas, counters and `by_genre`; the panel will read generation 10 / hidden 1024
  without changes.
- AGENTS invariant 12 (new collaborators in `DaemonService`): **no new server/executor** — the
  classifier runs inside the crawler process; nothing to wire.

## 3. Stages

### Stage 0 — artefact staging (COMPLETE, 2026-11-11)

§1 above. rsync + sha verification done; the three files sit in
`app/src/main/cpp/dht_crawler-android/models/v10/`. Commit together with stage 1.

### Stage 1 — crawler patch + app wiring (workstation)

- Write the patch per §2.1 (pooling LAST, ingest hook, daemon not started, config default,
  fixtures 1024, streaming test), apply to the patch list, make the Kotlin changes per §2.2.
- Assert at review time: manifest `genres[]` order == `taxonomy.h` order == v9's manifest order
  (bit compatibility across generations 9 and 10); manifest `taxonomy.sha256` matches
  `313dafd4…`.
- **Exit:** host crawler tests green (plain-C99 half: manifest/refusal/seam/render), Kotlin unit
  tests green (`./gradlew :app:testDebugUnitTest`), `./gradlew installDebug` produces an APK
  whose assets contain `dht_crawler/models/v10/{manifest.json, movie_kind_backbone_Q4_0.gguf,
  movie_kind_head.bin}` and **not** `models/v9`. Record the APK size (expect ~+318 MB net over
  the v9 build). **Commit:** patch file, `build.gradle.kts`, `CrawlerConfig.kt`, tests (the AGENTS.md
  pass belongs to stage 3 wrap-up).

### Stage 2 — device bring-up on the fresh phone (the G6 stage)

Preconditions: the new phone, never having run the app, adb-connected; **the operator completes
the first-run wizard personally** (P5). Then:

1. **Module health:** `/stats` → `movie_kind`: `enabled=1`, `model_ready=1` after the first movie
   row (lazy load — expect a seconds-long first classify, `model_load_failures=0`),
   `generation=10`, `hidden=1024`, `model_dir=models/v10`, backbone sha `02555750…`, head sha
   `077aaf11…`.
2. **Gate P — mask parity spot check, 500/500, mandatory before trusting any production write**
   (criterion-7 discipline; it is the only check that catches the pooling-accessor P0 class):
   sample 500 stored movie rows, compute reference label sets on the workstation with the
   *shipped* GGUF + head (`llama-embedding --pooling last --embd-normalize -1` + the JEVH
   arithmetic, pinned `18ab62a8`), compare against the DB masks. Assert **mask diversity**
   (the P0 signature was one constant mask) and include at least one decomposed-Unicode name
   (NFC path). 500/500 or stop and fix.
3. **Gate G6 — the real loop, finally measured** (U7 bars): steady state, 4 threads, crawler
   live, no backfill co-running by construction. Numbers: live median/p95 via
   `movie-kind-bench` on the fresh DB's rows (maintenance action, crawler stopped for the bench
   run) *and* `/stats movie_kind.mean_us_per_item` over a sustained live window; peak RSS via
   `/proc/<dhtcrawler-pid>/status` `VmHWM`; CPU temp beside every number. Bars: **median ≤ 300 ms,
   p95 ≤ 600 ms, RSS ≤ 1.2 GB (GiB reading), file 478.2 MiB** — pass. The honest prediction is
   ≈420 ms median (CLI 156.7 ms × v9's measured 2.7 loop-vs-CLI multiplier), so a median miss is
   *expected arithmetic, not a surprise*: the pre-registered levers are the shallow
   classify-queue with batch K ≥ 4 (~1.8× per v9's batching measurement) and — operator-only,
   before ship, per U7 — a re-scoped bar. RSS prediction: single-sequence 64-token context ≈
   file + runtime ≈ 700-900 MiB; the 1 206.7 MiB stage-0 row was the 8-sequence CLI allocation
   and is an upper bound.
4. **Acceptance:** the crawl grows (`torrents_indexed` up), movie rows carry
   `movie_kind_clf_ver = 10`, `by_genre` populated, `movie_kind_backfill` state never leaves
   idle, `grep -cE 'SQLITE_CORRUPT|malformed' files/logs/dht_crawler.log` = 0.
5. **Exit:** gate P 500/500, G6 verdict recorded with both bar readings, acceptance list green.
   If G6 fails on file cap or parity fails: fix or stop — v9 remains shipped on the old phone
   either way; nothing here is ship-on-fail (U6).

### Stage 3 — wrap-up

- AGENTS.md: rewrite the "Movie-kind classifier (v9)" section for v10 streaming (models/v10,
  Q4_0 nemotron arm, last-token pooling hidden 1024, inline ingest classification, backfill
  daemon not started, `movie_kind_clf_ver` 10, G6 numbers); note the fresh-phone install.
- Project memory/skill: extend or supersede `classifier-model-version-bump` with the v10
  streaming facts if the flow generalises.
- Record every measured number in this plan's Status header, v3-style.

## 4. Budget

| stage | resource | estimate |
|---|---|---|
| 0 | workstation + LAN | done (~5 min; 501 MB rsync, sha-verified) |
| 1 | workstation | ~0.5-1 day (patch + tests + APK check) |
| 2 | new phone + workstation | ~1-2 h hands-on + a soak window for the live-mean number |
| 3 | — | ~1 h docs |

## 5. Risks

| risk | sign | fallback |
|---|---|---|
| **R1 (main): pooling accessor P0 recurs** — wrong pooling gives zeros or a constant mask, every movie one verdict | stage 2 gate P: 500/500 mismatch or zero mask diversity | fix before trusting any write; the failure mode is loud under the spot check precisely because it was invisible everywhere else in v9 |
| **R2 G6 median miss** (≈420 ms predicted vs 300 ms bar) | stage 2 bench/live median | shallow classify-queue + batch K ≥ 4 (~1.8×); operator re-scope of the U7 bar before ship only |
| **R3 ingest stall** — inline classify holds a metadata worker ~0.4 s/movie row; first call pays the 478 MiB model load | tree metadata throughput visibly degrades | move to the queue worker (§2.1b), same predicate, post-insert UPDATE; the lever is plumbing, not a redesign |
| **R4 RSS over 1.2 GB on the real loop** | `VmHWM` | single-sequence context is ~an order below the stage-0 CLI row; if it still misses, threads/model context params are the only knobs — operator decision |
| **R5 repo/APK weight** — +478 MB git blob, APK ~505 MB of models | APK > ~750 MB R6 flag | accepted under P3 (bundle); the committed-blob precedent is v9's 160 MB; if it ever hurts, the download-on-first-run option (v3 R6) is the escape hatch, not a re-export |
| **R6 taxonomy bit-drift between generations 9 and 10** | stage 1 review assert | manifest order == `taxonomy.h` == v9 manifest, taxonomy sha `313dafd4…`; the assert is cheap and the failure (silently permuted genre bits) is not |
| **R7 new-phone unknowns** (SoC thermals, storage speed for the 478 MiB mmap) | G6 steady-state drift, first-load latency | measure at steady state (v9: ≤14 % drift on encoders, v1 saw ±28 % on decoders); report CPU temp with every number; the fresh device makes all G6 rows first measurements, not comparables |

## 6. Sources

- Upstream plan (complete through stage 7): `~/apps/classifier-model/plan/movie-kind-qwen35.md`
  — stage 7's one-variable pick (`models/v10-nemotron-q4`, external 0.4536, G8 +0.0866),
  G6/U7 bars, the 2.7× loop-vs-CLI multiplier, the quantisation-cost and NFC findings.
- Artefact manifests: `models/v10-nemotron-q4/manifest.json` (this repo's staged copy is
  byte-identical, sha `7a9cc7c1…`).
- In-repo wiring read on 2026-11-11: `app/build.gradle.kts` (`copyDhtCrawlerAssets`,
  `fetchLlamaCpp`/`buildLlama` pin `18ab62a8`, patch list), `CrawlerConfig.kt` (staging, stamp,
  sweep, `PINNED_KEYS`), `dht_crawler/src/movie_kind.c` (CLS pooling at :546,
  `llama_get_embeddings_seq` read at :626, hidden-from-manifest check), `movie_kind.h`,
  `tree_metadata.c` ingest block (:1577-1651), `movie_backfill.h` (movie predicate :17),
  `main.c` (daemon start :1162), `http_api.c` (`/stats movie_kind` :734),
  `CrawlerMaintenance.kt` (`MOVIE_KIND_BENCH`).
