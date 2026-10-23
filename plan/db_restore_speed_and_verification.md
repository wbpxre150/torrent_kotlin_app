# DB restore next steps — speed optimization and the remaining exit criteria

Status: OPEN. Written 2026-10-22 at the end of the `/dev` run that fixed the
crawler-DB-restore crash. The crash itself is fixed and verified on device; what is
left is (a) the ~20 h full-chain restore that three exit criteria still need, and
(b) the speed work that would make that restore practical rather than overnight.

Plan owner: the `/dev` parent session. Implementers: cold-start — this file plus the
committed code is your context. Do not re-derive the diagnosis below; it is measured.

Repo: `torrent_kotlin_app` (single module `:app`, package `com.torrent.app`).
Device: CPH2305 (ColorOS, non-rooted), adb serial `b93dac74`, package `com.torrent.app`.

## What landed (context — do not re-derive)

The reported bug was: the restore downloaded all 17357 chunks, concatenated them into
one ~36.7 GB temp `.sql`, ran `libdhtcrawler.so --restore-database`, and the app process
was **killed** (kernel-OOM signature: no dropbox crash entry, no tombstone, dmesg
unreadable) during the replay. Re-running started over.

The restore is now a Kotlin-side, one-chunk-at-a-time importer:

- streams each chunk's gunzip through a quote-aware splitter into a temp DB
  (`files/crawler/data/torrents.db.restore.tmp`), one committed transaction per chunk,
  never materialising a whole chunk or the whole chain;
- drops embedded `U+0000` (the dump contains NUL bytes inside text literals; `execSQL`
  passes a C string, so a NUL truncates the statement and SQLite reports
  `unrecognized token` — this broke chunk 1 deterministically);
- persists a per-chunk resume marker (`files/backup/db_restore_progress.txt`) and
  rewinds 3 chunks on resume for WAL-durability safety;
- creates the base tables during the bulk phase and the six `idx_*` **after** all
  chunks are in (`finishImport`), which removed the per-row secondary-index churn;
- runs the **new native** `--rebuild-fts` one-shot on the temp DB (bundled SQLite
  3.49.1, `patches/rebuild-fts-database.patch`) so both `torrent_search` and
  `file_search` are rebuilt — the user rejected silent FTS degradation;
- swaps the finished temp DB over the live DB atomically (`renameTo` straight over the
  target), resets the bookmark, and starts the crawler;
- reaps stale `restore-*.sql` dumps, guards free space, and bounds the WAL with a
  periodic `wal_checkpoint(TRUNCATE)` at ≥ 1 GiB;
- catches `Throwable` (not just `Exception`) so an OOM lands as FAILED instead of
  wedging `status.running = true` forever (which used to block every crawler job and
  the wizard's finish gate);
- the wizard's restore card is service-anchored, so an activity recreation no longer
  loses the status or locks "Finish setup".

Commits (oldest first): `f7af45f`, `89d1046`, `6397795`, `50ad8a4`, `8bfb0aa` +
submodule `bb8a2ad`, `4566064`, `8aa18c3`, `2e96936`, `658ad16`, `3f06bc5`, `35f4994`,
`bc4bab6`. `patches/restore-database.patch` and all vendored C are byte-identical.

## Verified exit criteria (on device)

| # | Criterion | Status |
|---|---|---|
| 1 | No giant concatenated temp `.sql`; stale dumps reaped | PASS — 73 GB reaped ~8 s after trigger, `files/tmp` stays 4 KB |
| 2 | Bounded app memory during import | PASS — max TOTAL PSS 113 MB vs the 350 MB limit, flat |
| 3 | Chunk-by-chunk progress visible and counting up | PASS — `logcat -s DbRestore` shows `chunk i/17357 imported` 1→25+, wizard counter monotonic, wording "importing" |
| 4 | Mid-import interruption resumes per chunk | PASS — force-stop at chunk 55/56 → `resuming at chunk 54 (marker 56, rewound 3)`, DB kept growing, never restarts at 1 |
| 5 | Crawler not running during import; restarted after | **first half PASS** (no `libdhtcrawler` process, corroborated 3 ways); second half PENDING SUCCESS |
| 6 | Completed restore + working FTS name and filename search | **PENDING SUCCESS** |
| 7 | Status/copy surfaces describe the new flow | wizard label PASS; dashboard JSON + settings-page copy PENDING SUCCESS (dashboard is gated off during the first-run wizard) |
| 8 | Native `--restore-database` untouched and still built | PASS — submodule diff is only the new patch file; patch list has one appended line |
| 9 | Unit tests for the new logic | PASS — 959 tests, incl. Robolectric tests against a real `SQLiteDatabase` |

## Measured performance (the reason for this document)

- Cost is proportional to **statements**, at ~**0.22 ms/statement** (chunk 1:
  192,705 statements in 44 s; chunk 2: 200,550 statements, same 44 s).
- Chain shape: early chunks are ~2.8 MB gz / ~20 MB SQL / ~200 k statements; later
  chunks are 1.5–3.6 MB SQL / 13–32 k statements. ~36.7 GB of SQL in total.
- Throughput ≈ 455 KB/s of SQL ⇒ **full chain ≈ 20–22 h**.
- Download is NOT the bottleneck: a whole object arrives in one ~2 s burst, then the
  restore thread (`crawler-control`) burns ~1 core for the rest of the chunk.
- The splitter is NOT the bottleneck (host JVM: 20 MB in 1.3 s ≈ 15 MB/s).
- Disk: guard reports `need ~64 GB, have ~208-211 GB free`; final DB estimate 20–40 GB.
- `PRAGMA page_size=32768` is a **no-op** on Android (`openDatabase(CREATE_IF_NECESSARY)`
  initialises the header first) — the fresh DB is 4096-byte pages.
- On a **resumed** run the temp DB reports `journal_mode=truncate` (not WAL), so the
  periodic-WAL-checkpoint path is dormant there and a bounded ~70–137 MB rollback
  journal is used instead. Harmless, but see S5.

## Pending verification (do this first — the run is already going)

The phone is restoring right now (started 13:21, resumed 14:15 at chunk 54/17357).
Expected completion ≈ 20 h later. Monitor with:

```bash
adb -s b93dac74 logcat -d -s DbRestore | grep -E "chunk .* imported|created index|FTS|swapped" | tail -5
adb -s b93dac74 shell run-as com.torrent.app cat files/backup/db_restore_progress.txt | head -1
adb -s b93dac74 shell run-as com.torrent.app ls -l files/crawler/data
```

At SUCCESS the log should show, in order: `created index idx_* in <ms>ms (deferred past
the bulk import)` ×6, the `--rebuild-fts` one-shot output (its lines also land in the
restore status `lines`), `swapped restored db into …`, and the bookmark reset.

Then verify the three pending items:

1. **Criterion 5, second half** — crawler restarted after SUCCESS:
   `adb -s b93dac74 shell ps -A -o UID,PID,NAME | grep -w 10338` shows a
   `libdhtcrawler.so` child; and `GET /api/status` on the dashboard reports the crawler
   running. The dashboard only comes up once the wizard completes (it is gated on
   wizard completion), which SUCCESS unlocks.
2. **Criterion 6** — completed DB with working FTS:
   - wizard Finish control enabled (`WizardRestoreGate` passes only on SUCCESS);
   - `GET /api/crawler/restore` → `state=SUCCESS`, non-zero `objectsDone`, and status
     `lines` containing the FTS rebuild output for BOTH `torrent_search` and
     `file_search` (no "file_search skipped" warning — the native one-shot uses the
     bundled SQLite 3.49.1, so the trigram tokenizer is always available);
   - `GET /api/crawler/stats` → non-zero torrent count;
   - a torrent-name search through the dashboard crawler search surface returns ≥ 1
     match (proves `torrent_search`), and a filename search returns ≥ 1 match (proves
     `file_search`).
   - Control token: `/data/data/com.torrent.app/shared_prefs/control_auth.xml`, key
     `auth_token`, read via `adb shell run-as com.torrent.app cat …` — never print it.
3. **Criterion 7, dashboard half** — `GET /api/crawler/restore` while running shows
   importing wording (no `--restore-database`), and the crawler settings page copy
   describes the streamed import + `--rebuild-fts` + swap, not the native replay.

## Speed optimization stages (if the ~20 h is not acceptable)

Ordered by expected win per unit of risk. Each is Kotlin-only, keeps the resume
semantics, and must re-run criteria 1–4 afterwards.

### S1 — Batch multi-row INSERTs (biggest, lowest risk)

Today each statement is a separate `execSQL`, so SQLite re-parses and re-prepares
~200 bytes of SQL per row (~0.22 ms each). Accumulate consecutive statements that
target the same table into one multi-row `INSERT OR IGNORE INTO <table> VALUES (…),(…),…`
(50–200 rows per statement, bounded by bytes, e.g. 64 KB). Parsing cost amortises,
`OR IGNORE` semantics are unchanged, and the chunk transaction still bounds the work.
Expect a large multiple (measure; the parse/prepare share of the 0.22 ms is the target).
Keep the per-chunk transaction and marker cadence; a chunk still commits atomically.

### S2 — Drop the UNIQUE auto-indexes during the bulk phase (biggest remaining churn)

`torrents.info_hash UNIQUE` and `path_prefixes.prefix UNIQUE` are table constraints, so
they cannot be deferred with the `idx_*` set — every insert does two random B-tree
lookups. Idempotency currently relies on them. Alternative: create the base tables
**without** `UNIQUE`, and on resume delete the rows of the chunks being replayed
(the object key already carries the id range: `incremental_<date>_<start>-<end>`),
then replay them. At `finishImport` create the UNIQUE indexes with `CREATE UNIQUE INDEX`
(which fails loudly on any duplicate — a useful integrity check). This removes the
random churn from the hot path and turns dedup into a single sorted build at the end.
Needs care: `torrent_files` has no id cursor of its own, so define its range by
`torrent_id` from the same chunk range.

### S3 — Prepared statements with bound arguments

Instead of SQL text, compile one `SQLiteStatement` per (table, column-shape) and bind
parsed values. Fastest per row, but requires a value parser for the dump's literal
forms (`NULL`, integer, `'…'` with `''`, `x'hex'`) and a fallback to `execSQL` for
anything unexpected. Only worth it if S1+S2 do not get the restore under ~4 h.

### S4 — Prefetch the next chunk while the current one imports

The download is only ~2 s of a ~50 s chunk today, so this buys little now — but after
S1/S2 the import may drop below the download time, at which point a one-chunk-ahead
prefetch (bounded to a single ~3 MB body) becomes the limiting factor. Do it last.

### S5 — Force `journal_mode=WAL` on the resume path

Resumed runs report `journal_mode=truncate`, so the periodic WAL checkpoint never
fires and a rollback journal is used instead. Correctness is fine (the journal is
bounded), but the two paths then have different I/O behaviour and the checkpoint code
is untested on device. Either set WAL on resume too, or document that resume runs are
rollback-journal by design.

### S6 — Real 32 KB pages (only if measurements justify it)

`PRAGMA page_size` is a no-op on Android after `openDatabase`. A real change needs
`PRAGMA page_size=32768; VACUUM;` on the freshly created empty DB (cheap while empty)
— verify on device that the header actually changes before relying on it. Bigger pages
reduce B-tree depth and WAL frame count.

## Risks / notes

- **Never** change `patches/restore-database.patch` or vendored C (AGENTS.md invariant);
  crawler changes go in a new patch file appended last in the fixed list.
- The dump contains **NUL bytes** inside text literals (2 of ~192 k statements in chunk
  1; 18 of the first 50 chunks logged drops). Any future importer must strip `U+0000`
  before handing SQL to `execSQL`.
- `torrent_class` / `classifier_meta` are **not** in the dump, so classifications are
  not restored (pre-existing behaviour, out of scope here).
- The disk guard's 7× gzip ratio is empirical; re-check it if the chain shape changes.
- Restore is single-flight (`inFlight`), which is what makes `reapStaleSqlDumps`
  (deletes every `restore-*.sql` in `files/tmp`) safe.
- Open P2s from review, deliberately not fixed:
  - a 1 Hz no-op poll loop if the binder goes null while the snapshot is RUNNING;
  - `WizardRestoreViewModel.invalidate()/adopt()` untested;
  - `dropLegacyIndexes` runs on every resume (a `--rebuild-fts` retry rebuilds six
    indexes needlessly);
  - **on a `truncate`-mode resumed run there is no `-wal` file, so `maybeCheckpoint()`
    returns null every chunk and the *periodic* disk re-check never runs** — the guard
    fires pre-flight only (a mid-run `SQLITE_FULL` still fails cleanly, keeping the temp
    DB + marker). Fixing S5 (force WAL on resume) also fixes this;
  - `RestoreDiskGuard.gigabytes()` renders GiB with a `GB` suffix (~7 % understated) and
    the 2 GiB base over-reserves WAL headroom for truncate-mode runs — cosmetic/safe.

## Appendix — device probe cheatsheet

```bash
# progress / markers
adb -s b93dac74 logcat -d -s DbRestore | grep "chunk .* imported" | tail -3
adb -s b93dac74 shell run-as com.torrent.app cat files/backup/db_restore_progress.txt | head -1
adb -s b93dac74 shell run-as com.torrent.app ls -l files/crawler/data files/tmp
# memory / disk
adb -s b93dac74 shell dumpsys meminfo com.torrent.app | grep "TOTAL PSS"
adb -s b93dac74 shell df -h /data | tail -1
# crawler process (ColorOS hides names; use the uid)
adb -s b93dac74 shell ps -A -o UID,PID,NAME | grep -w 10338
# trigger from the wizard (dashboard is gated off during first-run)
adb -s b93dac74 shell input swipe 540 2000 540 400 200   # scroll to section 6
adb -s b93dac74 shell input tap 150 1872                  # switch ON
adb -s b93dac74 shell input tap 266 2088                  # "Restore now"
# dashboard (after the wizard completes)
adb -s b93dac74 shell run-as com.torrent.app cat /data/data/com.torrent.app/shared_prefs/control_auth.xml
#   → GET http://<phone-ip>:8090/api/crawler/restore   (cookie control_token=<value>)
```

---

# Category-filtered DB import — landed plan and next steps

Supersedes the note above that "`torrent_class` / `classifier_meta` are **not** in the dump":
the dump still carries no class rows, but the dumper now writes a **sidecar class index**
alongside each object and the restore filters with it. Chains dumped before this change
(everything in `db/` up to 2026-10-22) carry no categories and import unfiltered.

## Why this exists (premise correction, measured)

The request was "the categories are already tagged in the SQL files being downloaded".
Verified against the real bucket `dht-backup/db/` (17,357 objects, 7 objects sampled from
first to newest):

| sampled object | `path_prefixes` | `torrents` | `torrent_files` | `torrent_class` |
|---|---|---|---|---|
| first `…_10000701` | 190,000 | 56 | 2,649 | **0** |
| `…_10002701` (middle) | — | 505 | 9,022 | **0** |
| `…_1358701`, `…_5770701`, `…_20742201`, `…_29691851` | — | 996 / 117 / 626 / 162 | 30,111 / 3,295 / 145,046 / 11,131 | **0** |
| newest `…_34516997` | — | 1,489 | 42,481 | **0** |

`torrents` has no category column and `CrawlerDbDumper` read only three tables, so **every
restore to date imported an unclassified corpus** (the crawler only classifies at ingest,
and `torrent_class` is `ON DELETE CASCADE` from `torrents`). The native backup-DB path
(`batch_writer.c:766-770`) does copy `torrent_class`, but its flags are pinned off on
Android (`CrawlerConfig.kt:259-266`), so it was never an option here.

## Design that landed

- **Producer (Kotlin only, no native change):** next to every `incremental_<date>_<start>-<end>.sql.gz`
  the dumper writes `incremental_<date>_<start>-<end>.idx.gz` — gzip of one
  `<torrent_id> <category>\n` line per classified torrent in that object's window
  `(bookmark.lastTorrentId, chunkMax]`. The `.idx.gz` suffix deliberately fails
  `DbObjectKey.parse`, so chain ordering, the gap/overlap rules and the resume marker are
  untouched. An **empty** index is still written: it asserts "this range is fully covered,
  nothing is classified", which is different from "no index → unfiltered".
- **Consumer:** the restore streams each object's index, builds `RestoreCategoryFilter`, and
  skips statements of unselected categories at the existing statement hook
  (`DbRestoreManager.kt` `SqlStatementSplitter.split { sql -> … }`). Skipping does not touch
  chunk accounting (`endChunk` only checks `chunkFailed`), so one-transaction-per-chunk and
  the `INSERT OR IGNORE` rewind replay stay intact.
- **Drop depth:** whole torrent — `torrents` + `torrent_files` + `torrent_class` (a class-only
  drop would leave rows the crawler never re-classifies, so the classifier would never
  "fix" them; and the bulk phase runs `foreign_keys=OFF`, so a post-import DELETE would need
  explicit child deletes over ~34.5 M rows).
- **Selection source:** persisted `crawler_save_categories` on every entry point. The wizard's
  "Restore now" now persists the chip state first (store-only write: no `config.ini` rewrite,
  no crawler restart), so display, store and filter cannot disagree.
- **Empty selection = import everything**, byte-for-byte the old behaviour: no sidecar GETs,
  no statement parsing. Missing sidecar on a listed object → that object imports unfiltered
  plus one info line; a sidecar that *is* listed but fails to download/parse → FAILED with the
  temp DB + marker kept (resumable). No new `DbRestoreState`.
- Category codes are `CrawlerTaxonomy.CATEGORIES` index 1:1 (no offset) — `taxonomy.h:13-25`,
  `classifier.c:18-21`, `category_filter_cleanup.c:23-26`. A torrent with no index entry is
  unknown(0), matching the crawler's own ingest rule. Unparseable statements are always KEPT
  (over-include, never silently drop).

## Stages as implemented

| # | Stage | Commit |
|---|---|---|
| 1 | Producer: per-range class-index sidecar (dumper, writer, upload before the bookmark write) | `70fdedc` |
| 2 | Pure `parseClassIndex` + `RestoreCategoryFilter.keep(sql)` (quote-aware value scan) | `9d94fe8` |
| 3 | Restore integration: selection read once per run, sidecar GET before `beginChunk()`, skip in the statement callback, info/summary lines | `fc6a286` |
| 4 | Wizard: persist the chip selection before "Restore now" | `5139942` |
| 5 | `tools/r2-synth-chain.sh` — reproducible synthetic chain builder | `ac68f92` |
| 6 | Corrective: targeted `saveBackup` so a dashboard save cannot clobber the crawler section | `f64d18d` |

997 unit tests, 0 failures. Fresh reviewer: APPROVE on all five feature commits (P2 notes
only) and APPROVE on the corrective commit.

## Verified on device (`b93dac74`) — all PASS

| # | Criterion | Evidence |
|---|---|---|
| 1 | Restore-now persists the chips before the restore | `save_categories={movie,music}` at click time, before Finish setup |
| 2 | Filtered import (movie+music) | 5 torrents (ids 1,2,3,8,9), 10 file rows, 5 class rows, `1|3`+`4|2`, `skipped 26 statement(s)` |
| 3 | Empty selection imports everything | 12 / 24 / 10, no filter line in the log |
| 4 | Chain without a sidecar | 12 / 24 / 10 + `class index: missing for 2 of 2 object(s); imported unfiltered`, SUCCESS |
| 5 | Dashboard entry point honours the persisted selection | `movie` → 3 / 6 / 3, `1|3`; stayed `movie` across two consecutive saves after Stage 6 |
| 6 | Kill-and-resume consistency | killed at `nextIndex=2`, `resuming at chunk 1/300 (marker 2, rewound 2)`, final 5 / 10 / 5 |

## Next steps

1. **Sibling race at `DaemonService.kt:555`** (`applyTransmissionSettings` → full-section
   `configStore.save(cfg)`, snapshot taken in `ControlServer` at request time). Same defect
   class as the one Stage 6 fixed: a transmission save can revert a just-queued crawler
   `saveCategories`. Fix with the same targeted-writer pattern — write `saveTransmission(s)`
   plus the `dataSubpath` key instead of the whole config. Leave `DaemonService.kt:1238`
   (crash-loop rollback) alone: that full write is deliberate.
2. **Regression test does not pin the fixed call site.** `ConfigStoreTest.saveBackup_isSectionTargeted`
   exercises `ConfigStore.saveBackup` (unchanged by the fix) and would pass against pre-fix
   code. Either soften its KDoc to "contract test for the writer the fix relies on" or add a
   service-level test that interleaves `saveCrawler` with `saveBackupSettings`.
3. **Smaller P2s, unfixed by choice:** backup status message double-reports index bytes
   (`totalBytes` includes them and the suffix re-states them); `filterActive` derives from
   names while the code set derives from known names, so a store containing only unknown
   names would filter everything out (not reachable through the UI); no test for a *listed*
   sidecar that fails to **download** (the parse-failure path is covered).
4. **The next real chain must carry sidecars** or restores import unfiltered by design. The
   operator is re-uploading `dht-backup/db/` from a device running this build; each
   `incremental_*.sql.gz` needs its `incremental_*.idx.gz` sibling (lines `<torrent_id> <category>`).
   Quick shape check after the upload:
   `rclone lsf r2:dht-backup/db/ | grep -c '\.idx\.gz'` should equal the `.sql.gz` count.
5. **Testing recipe** for future changes to this path: `tools/r2-synth-chain.sh --prefix dbtest-<ts>`
   (add `--no-index` for the unfiltered case, `--repeat N` for a long kill/resume run), point
   `backup_r2_db_object_prefix` at the test prefix, run the restore from the dashboard, pull the
   DB (`adb exec-out run-as com.torrent.app cat files/crawler/data/torrents.db > /tmp/x.db`) and
   query it with host `sqlite3`. The device has no `sqlite3`. Purge the prefix afterwards
   (`rclone purge r2:dht-backup/dbtest-<ts>`) — never test in `db/`.
6. **Device state left after this work:** prefix back to `db`, `crawler_save_categories` empty
   (= import everything — tick categories before the real restore), and the live
   `torrents.db` still holds the 3 synthetic test torrents until the next restore swaps it out.
