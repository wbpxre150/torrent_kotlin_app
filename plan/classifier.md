# Torrent classifier for the DHT crawler: investigation

Status: **investigation only, nothing implemented.**
Sources: four web-researcher runs (2026-10-14) plus a read of the current crawler code.
The **[EST]** tag marks unmeasured estimates. Measure these before committing to a design.

> **Update 2026-10-14:** the implementation is split into per-stage packets in **`plan/classifier-stages/`** (index:
> `plan/classifier-stages/README.md`). Measured facts there replace estimates here. Most importantly, the real DB has **34.4M torrents / 1.41B file rows (84 GB)**, not 1–10M. The teacher is the existing Ornith-1.5-35B-A3B on llama.cpp (`FASTx1`, ~3.5 items/s). Training code lives in `~/apps/classifier-model`.

---

## 1. TL;DR

1. **sqlite-jev cannot meet the "runs locally" requirement.**
   - `mgaitan/sqlite-jev` is a thin SQLite client for **TypeSafe Jev**, a *hosted, closed-weight* model at `api.typesafe.ai`.
   - There are no published weights and no local binary.
   - Every row we classify would send DHT torrent names and file lists to a third party.
   - TypeSafe publishes no adult-content policy, which matters because porn detection is the main use case.
   - Its result cache is per-connection, unbounded and linear-scan, and nothing is persisted.
   - **Verdict:** don't ship it. Copy its *SQL design* instead (see §3).
2. **Recommended architecture:** three tiers, all local.
   - **Tier 0, deterministic C parser.** A release-name and file-list parser, in the style of Sonarr or guessit, decides TV granularity and the obvious content types. Season pack, single episode and similar labels are mostly a parsing problem, not an ML problem.
   - **Tier 1, a small on-phone model.** A **fastText** supervised model (char n-grams, quantized `.ftz`, a few MB) runs in the crawler process. It covers adult yes/no, content type, and the ambiguous TV cases. It is microseconds per item, so it is fast enough both for live ingest and for re-classifying the entire existing DB **on the phone**.
   - **Tier 2, a V100 teacher, offline only.** A larger LLM on llama.cpp (`json_schema`-constrained output) labels a few hundred thousand torrents. That set is the training data for the fastText student. The teacher can optionally re-label low-confidence leftovers. It is **not** on the ingest path, so the phone works when the PC is off.
3. **Porn handling becomes configurable.** The default is store + tag + hide at query time. There is an option to drop at ingest (today's behaviour). A small, separately reviewed **hard-block** list (e.g. CSAM terms) always drops at ingest regardless of the setting.
4. **New search:** a `torrent_class` table with partial and covering indexes. FTS5 `MATCH` runs first, then a rowid join filters by category, TV kind and adult. This adds new `/search` parameters and lets the dashboard offer "TV → complete seasons only" and similar.
5. **Backfill:** a resumable keyset (`id > last`) loop with a checkpoint table. On the phone, with fastText, all 10M rows take roughly **hours, dominated by SQLite I/O [EST]**, run as an always-on background job.

---

## 2. Current state (from the code)

| Aspect | Today |
|---|---|
| Filter | `src/porn_filter.c`: case-insensitive **substring** match against `porn_filter_keywords.txt` (`[standalone]`), plus an `[xxx_cooccurrence]` list that fires only when the whole word `xxx` is present, plus one spam-filename marker (`manko.fun.url`). |
| Where | `tree_metadata.c:1338`. If `porn_filter_enabled`, the torrent is **dropped before insert**. `--porn-filter-update` (`porn_filter_cleanup.c`) re-runs the filter over the DB and deletes matches. |
| Why it's unreliable | Substring matching hits the Scunthorpe problem: `sex` matches `Sussex` / `sextant`, and `anal` matches `analysis`. Obfuscated titles (`P0rn`, `S.e.x`), JAV codes (`ABCD-123`) and Japanese, Chinese or Russian titles slip through. File lists are ignored. The result is a single boolean with no confidence and no recovery, because dropped rows are gone. |
| Schema | `torrents(id, info_hash, name, size_bytes, total_peers, added_timestamp)`, `torrent_files(torrent_id, prefix_id, filename, size_bytes, file_index)`, `path_prefixes`, FTS5 `torrent_search` (porter unicode61, external content) and `file_search` (trigram). |
| Existing "type" logic | `/random-tv`, `/random-movies`, `/random-music` in `http_api.c` infer type at query time using `LIKE` on file extensions. Nothing is stored. |

**Training-data consequence:** if the filter has been on, the existing DB contains *few adult rows*. Adult training examples will have to come from (a) running ingest in tag mode for a while, and (b) the false negatives already in the DB.

---

## 3. sqlite-jev / TypeSafe Jev in detail

- **Jev:** TypeSafe's "System One" decision model. `POST /v1/systemone` takes `{state, model, questions:{id:{type, instructions, criteria}}}` and returns typed answers. Primitives:
  - **Noul:** yes/no probability.
  - **Choice:** up to 255 keys, with probabilities and confidence.
  - **Score:** 2–10 ordered levels.

  Model `jev-1.13.0`, 64k context, $0.042 per million input tokens, output free, limits of 100k tok/s and 80 req/s.
- **Cost if we used it anyway [EST]:**
  - About $10 for 10M rows using names only, or about $90 with file lists.
  - About an hour at the rate limits.

  Cheap, but it fails the privacy and locality requirements.
- **"Local" variants:**
  - `mattn/sqlite3-jev` documents a `base_url` pointing at `mattn/tensai serve`, a Go ML framework with an OpenAI-compatible server. That is *not* Jev, just a small GGUF model behind a Jev-shaped route, and the route itself is unverified in tensai's source.
  - A llama.cpp shim that maps `/v1/systemone` → `/v1/chat/completions` (JSON schema output, logprobs for probabilities) is buildable. It is still an LLM-per-row design, though, which is too slow on the phone (§5).
- **Static Android build:** possible but awkward.
  - Compile with `-DSQLITE_CORE`.
  - Register through `sqlite3_auto_extension()` instead of `.load`.
  - Remove its `dlopen("libcurl.so.4")` and link the static curl and mbedTLS we already build.
  - Supply a CA bundle.
- **Worth copying:**
  1. A **table-valued / SQL-function interface**, so classification is a set-based join on rowid. We can register our own `tclass(name, files_json)` scalar function in the crawler with `sqlite3_create_function_v2`, which makes ad-hoc SQL experiments trivial.
  2. **Batching** many rows into one request. This is useful for the V100 teacher (§6).
  3. **Typed questions** (bool / choice / ordinal) with thresholds kept in SQL, not in code.
  4. **Spend/row guards.**
  5. And, unlike sqlite-jev, **persist the results** with a model version.

---

## 4. Label taxonomy (proposal)

| Field | Values | Primary source |
|---|---|---|
| `adult` | 0 / 1, plus `adult_p` probability | fastText, with rule features |
| `category` | movie, tv, anime, music, audiobook, ebook, comic, software, game, image, other, unknown | parser rules → fastText |
| `tv_kind` | episode, multi_episode, season_pack, multi_season, complete_series, daily, NULL | **parser** (name + file list); fastText only when the parser can't decide |
| `season_lo/hi`, `episode_lo/hi` | integers | parser |
| `resolution`, `source` (e.g. 1080p, WEB-DL) | small enums | parser (cheap; good search facets) |
| `confidence`, `clf_ver`, `rule_hit` | — | for auditing and re-classification |

**TV granularity decision (parser, mostly from the file list):**
1. Count video files using an extension list; bitmagnet's `extensions.video` is a good start. Ignore `sample`, extras, `.nfo`, `.srt` and small files.
2. Parse each video filename for `SxxEyy`, `NxNN`, `Season N`, anime `[Group] Title - NN` and daily `YYYY.MM.DD` patterns, building a set of (season, episode) pairs.
3. Decide:
   - 1 pair → `episode`.
   - 2 or more episodes from one season → `season_pack`.
   - Several seasons → `multi_season`.
   - Several seasons plus a name token such as `Complete` or `S01-S05`, with no gaps → `complete_series`.
   - Gaps inside a season range → flag `incomplete`.
4. If the file list gives no pairs, fall back to name tokens (`S02`, `Season 2`, `Complete`, `S01-S03`).

Prior art to port (C has none ready-made):
- Sonarr `Parser.cs` regexes: the most battle-tested patterns, C#, port the regexes.
- guessit: Python, a property model with `season`/`episode` lists, `season_count`, `episode_count`.
- **anitomy-c**: a C ABI over the header-only C++ Anitomy, for anime. Needs C++23. Check the license and NDK r27 support.

None of these publish accuracy figures, so we need our own labelled test set either way.

---

## 5. On-phone model options (Snapdragon 8-series CPU)

| Option | Size | Per-item latency | 10M backfill on phone | NDK effort | Multilingual / obfuscation | Verdict |
|---|---|---|---|---|---|---|
| **fastText supervised**, char n-grams, `.ftz` | a few MB [EST] | µs-range [EST] | **≈0.1–0.5 h of compute; SQLite I/O dominates [EST]** | Low: C++ lib, MIT, small `extern "C"` shim | Good with `minn/maxn` char n-grams; works on CJK bytes and leetspeak | **Recommended** |
| multilingual-e5-small (118M, 384-d) + logistic head | 113 MB int8 ONNX; GGUF Q8 ≈ same | tens of ms [EST] | ≈10–30 h [EST] | High: ONNX Runtime Mobile or llama.cpp embedding | Strong (100 langs) | Fallback if fastText fails on non-English titles |
| Small LLM (Qwen3-0.6B, Gemma3-1B) via llama.cpp + GBNF | 0.4–0.8 GB | ~1–2 s per item (15–30 tok/s generation) | **months** | Medium | OK | Not viable on the phone; teacher only |
| GBDT on handcrafted features | KB | µs | trivial | Low | Poor | Use those features as *inputs* to fastText instead |
| sqlite-vec + embeddings (kNN) | 10M × 384: f32 15.4 GB / int8 3.8 GB / 1-bit 0.48 GB | — | — | Medium | — | Not worth it on the phone. Only consider it later, for semantic "more like this" on a subset. |

fastText trick: feed it a *text* that includes rule-derived tokens, for example
`__ext_mkv __nvid_10 __sxxeyy __jav_code __script_cjk <normalised name tokens> <top file basenames>`,
so one linear model sees both lexical and structural signals. Train separate models per label: adult (binary) and category (multi-class).

---

## 6. Training pipeline (V100 32GB + Ryzen 5 + 32GB RAM)

1. **Export a sample** from the phone DB (`adb exec-out run-as … sqlite3` or the existing backup path). Take about 300–500k rows, stratified by name script (Latin, CJK, Cyrillic), file count and size. For adult examples, run ingest in **tag mode** for a few days first (§2).
2. **Teacher labelling** with upstream `llama-server` on the V100:
   - Model: Qwen2.5-14B-Instruct Q4–Q8 (fits in 32 GB), or a 7B abliterated/uncensored variant if the instruct model refuses adult labels. V100 has no bf16, so use GGUF/fp16.
   - `json_schema` / `response_format` for `{adult, category, tv_kind, notes}`.
   - Use `-np 8..16` parallel slots (continuous batching is on by default). Put a shared system prompt first so it is prefix-cached. Pack 10–20 torrents per request, as sqlite-jev does with 40.
   - **Throughput [EST]:** roughly 5–30 torrents/s, so 300k takes hours to about a day. Measure on 1k rows first.
3. **Gold set — dropped (2026-10-15):** no separate hand-labelled gold set. The user hand-checked the teacher's `adult` labels and accepted them, so measure the teacher, the current keyword filter and the student on a held-out split of the teacher labels instead.
4. **Quality filtering:** keep teacher labels that pass the schema, and that either agree with the parser or are confirmed by a second prompt or model.
5. **Train the student:** fastText `supervised -minn 2 -maxn 5 -wordNgrams 2`, autotune on validation, then `quantize -qnorm -retrain -cutoff`. Training takes minutes on the Ryzen.
6. **Calibrate** per-label thresholds on a held-out split of the teacher labels. Adult needs high precision for the "hide" mode, and recall matters for "drop" mode, so allow separate thresholds per mode. Report precision and recall against the old keyword filter as the acceptance bar.
7. **Ship** `adult.ftz` and `category.ftz` as crawler assets, the same way the keyword files are copied by `copyDhtCrawlerAssets`, along with a `model_version`/sha256.
8. **Iterate:** periodically export low-confidence rows plus user corrections, re-label them with the teacher, retrain, bump `clf_ver`, and re-classify (§8).

---

## 7. Schema and search

```sql
CREATE TABLE torrent_class (
  torrent_id   INTEGER PRIMARY KEY REFERENCES torrents(id) ON DELETE CASCADE,
  category     INTEGER NOT NULL,          -- small enum
  tv_kind      INTEGER,                   -- NULL unless tv/anime
  adult        INTEGER NOT NULL DEFAULT 0,
  adult_p      REAL,
  season_lo INTEGER, season_hi INTEGER, episode_lo INTEGER, episode_hi INTEGER,
  resolution   INTEGER,
  confidence   REAL,
  clf_ver      INTEGER NOT NULL
);
CREATE INDEX idx_class_cat ON torrent_class(category, tv_kind, torrent_id) WHERE adult = 0;
CREATE INDEX idx_class_ver ON torrent_class(clf_ver);
CREATE TABLE classifier_meta (key TEXT PRIMARY KEY, value TEXT);   -- model hashes, backfill checkpoint
```

- Use a **separate table**, not new columns on `torrents`. That avoids rewriting a 10M-row table, lets the table be dropped and rebuilt, and leaves `torrents` and the FTS triggers untouched.
- **Search:** run `torrent_search MATCH ?` (or `file_search`) first, then `JOIN torrent_class USING rowid` with category, tv_kind and adult filters. The cost scales with the match set, which is usually small. A browse with no text query ("complete seasons, newest first") uses the partial index directly.
- Don't rely on FTS5 `UNINDEXED` columns for filtering: they can't be matched, and they aren't indexed for `WHERE`.
- **API:** extend `/search` with `category=`, `tv_kind=`, `adult=hide|show|only`, `min_conf=`. Replace the `/random-tv` and `/random-movies` extension-`LIKE` heuristics with `torrent_class` lookups. The Kotlin `CrawlerProxyServer` passes query strings through, so the main Kotlin work is dashboard UI plus allow-listing any new config keys in `CrawlerKeys`.

---

## 8. Ingest hook, backfill and versioning

**Ingest:**
- Don't run the model inside the batch writer's transaction, because it would hold the single write lock.
- Instead, the parser and fastText run in `tree_metadata.c`, where `porn_filter_check` runs today. Both are µs-cheap, so no extra thread is needed.
- The resulting `classification_t` travels with `torrent_metadata_t` into `batch_writer_add`, which inserts `torrent_class` in the same batch transaction.
- Hard-block and drop-mode decisions happen at that same point.
- If a heavier model is ever used (e5), switch to a bounded queue drained by a classifier thread that posts results back to the single writer.

**Backfill / re-classify (on the phone):**
- Run it as a crawler maintenance mode, like the existing `--porn-filter-update`, wired through `CrawlerMaintenance.kt`, or as a low-priority background thread.
- Loop:

  ```
  SELECT t.id, t.name, <file summary> FROM torrents t
  LEFT JOIN torrent_class c ON c.torrent_id = t.id
  WHERE t.id > :ckpt AND (c.torrent_id IS NULL OR c.clf_ver < :ver)
  ORDER BY t.id LIMIT 2000;
  ```

  Classify the batch, then UPSERT the results and the checkpoint in one short transaction (WAL plus `busy_timeout`, so the crawler writer is never blocked for long).
- Expose progress in `/stats`.
- File lists are the expensive part of the read, since `torrent_files` is much larger than `torrents`. Read only the first N filenames, count by extension in SQL, and use `idx_files_torrent`.

**Alternative backfill:** export to the PC, classify there, and import with `INSERT OR REPLACE`. This is only worth it if a model too heavy for the phone becomes the student (e5/LLM). With fastText it isn't needed.

**Versioning:** `clf_ver` per row, plus model sha256 values in `classifier_meta`. A new model bumps the version, and the same loop re-classifies. Old labels stay visible until they are overwritten.

---

## 9. Configurable porn handling

| `config.ini` key (proposal) | Values | Behaviour |
|---|---|---|
| `classifier_enabled` | 0/1 | The parser always runs; this toggles the fastText models. |
| `adult_mode` | `tag` (default) / `drop` | `tag`: store, set `adult=1`, hide in search unless asked. `drop`: don't store when `adult_p >= adult_drop_threshold`. |
| `adult_hide_threshold` / `adult_drop_threshold` | 0–1 | Calibrated defaults from §6. The drop threshold should be stricter (higher). |
| `hard_block_file` | path | Always dropped. Separate, reviewed list, never trained around. |

Keep the existing keyword filter as a fallback when model files are missing. Its hits can also be features for the model. Add the new keys to `CrawlerKeys` (the crawler settings form is allow-listed).

---

## 10. Phased roadmap (for when we implement)

1. **Measure:** export a 500k sample. Score the current keyword filter on the teacher labels to get a baseline.
2. **Parser:** C release-name and file-list parser with unit tests (host build, like `ftp-server/host`). Add the `torrent_class` table. Classify at ingest using parser output only.
3. **Teacher run** on the V100 → fastText student → calibrate → beat the baseline on a held-out split of the teacher labels.
4. **fastText in the crawler:** NDK CMake, assets, `adult_mode` config, `/search` filters, dashboard UI.
5. **Backfill** on the phone (maintenance mode), plus progress in stats.
6. **Feedback loop:** "mark as wrong" in the dashboard, then periodic retraining.

---

## 11. Risks and open questions

- **No measured numbers** for fastText or e5 on the Snapdragon, or for teacher throughput on the V100. Step 1 must benchmark them.
- **Teacher refusal or bias on adult content:** checked 2026-10-15 — the user hand-reviewed the teacher's `adult` labels and accepted them (abliterated models remain unvalidated; no gold set involved).
- **Training-data skew:** adult rows were dropped historically (§2), so a tag-mode collection period is needed.
- **anitomy-c** requires C++23 and its license is unverified. A small hand-written anime regex set may be simpler.
- **JAV-code pattern** (2–5 letters, hyphen, 3–5 digits) has no authoritative source. It is a feature, not a rule, because it collides with part numbers and software versions.
- **DB growth:** `torrent_class` is about 40–60 B/row plus indexes, so roughly 0.5–1 GB for 10M rows [EST].
- Which phone? "Snapdragon 8-series" covers Gen 1 to Elite; this matters only if e5 becomes necessary.

---

## Sources

- sqlite-jev — https://github.com/mgaitan/sqlite-jev · TypeSafe docs — https://docs.typesafe.ai/api.md, https://docs.typesafe.ai/models.md · mattn/sqlite3-jev — https://github.com/mattn/sqlite3-jev · tensai — https://github.com/mattn/tensai
- fastText — https://arxiv.org/abs/1607.01759, https://fasttext.cc/docs/en/python-module.html · quantization — https://flavioclesio.com/post-training-quantization-in-fasttext-or-how-to-shrink-your-fasttext-model-in-90
- multilingual-e5-small — https://huggingface.co/intfloat/multilingual-e5-small, ONNX int8 https://huggingface.co/Teradata/multilingual-e5-small, GGUF https://huggingface.co/cstr/multilingual-e5-small-GGUF
- llama.cpp grammars/server — https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md, https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md · Android llama.cpp perf (device unstated) — https://github.com/Siddhesh2377/llama.cpp-android/blob/re-write/docs/PERFORMANCE.md
- Teacher candidates — https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF, https://huggingface.co/QuantFactory/Qwen2.5-7B-Instruct-abliterated-v2-GGUF
- bitmagnet classifier — https://bitmagnet.io/guides/classifier.html, https://github.com/bitmagnet-io/bitmagnet/blob/main/internal/classifier/classifier.core.yml
- guessit — https://guessit.readthedocs.io/en/latest/properties.html · Sonarr Parser.cs — https://github.com/Sonarr/Sonarr/blob/develop/src/NzbDrone.Core/Parser/Parser.cs · Anitomy — https://github.com/erengy/anitomy, https://github.com/Xtansia/anitomy-c
- Scunthorpe problem — https://en.wikipedia.org/wiki/Scunthorpe_problem · Jackett categories — https://github.com/Jackett/Jackett/wiki/Jackett-Categories
- SQLite FTS5 — https://www.sqlite.org/fts5.html · sqlite-vec vec0 — https://alexgarcia.xyz/sqlite-vec/features/vec0.html · sqlite-lembed — https://github.com/asg017/sqlite-lembed
