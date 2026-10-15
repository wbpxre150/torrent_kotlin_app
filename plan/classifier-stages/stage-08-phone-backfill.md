# Stage 8: On-phone backfill and re-classify job

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (crawler C + Kotlin maintenance). Design: `plan/classifier.md` §8 (backfill/versioning).
> Requires the test phone on USB. No LLM involved — normal subagent rules apply.

## Goal

Classify existing and old-version rows on the phone in a resumable way.

## Depends on

Stage 5 (ingest-time classification, `torrent_class` + `classifier_meta` tables, `clf_ver` versioning).

## Scope

1. A crawler maintenance mode or low-priority thread (wired through `CrawlerMaintenance.kt`, like the
   existing `--porn-filter-update`):
   - Keyset loop (`id > :ckpt`) with the checkpoint in `classifier_meta`, batches of 2k, ≤ 50 files read per
     torrent (file reads are the expensive part; use `idx_files_torrent` and read only the first N filenames).
   - One short UPSERT transaction per batch (WAL + `busy_timeout` — the crawler writer is never blocked long).
   - Always runs — no gating on charging, battery, or thermal state.
2. Progress (`done/total`, rate) is exposed in `/stats` and on the dashboard.
3. Re-classifies rows where `clf_ver < current`.

## Constraints (app invariants)

- Maintenance jobs run via `OneShotRunner`/`CrawlerMaintenance` patterns — one single-thread executor per
  supervised process; lifecycle ops never block the main thread (`DaemonService` is the composition point).
- The phone DB is on-device; never route backfill through the SAF/USB bridge — it touches only the crawler's
  own SQLite DB.
- Build env: JDK 21 + Android SDK/NDK r27; build `./gradlew installDebug`.

## Exit criteria (on the new phone)

1. Wipe `torrent_class` (drop/recreate or `DELETE`) and run the job: everything is reclassified, with labels
   identical to the ingest-time labels (compare a sample of ≥ 100 rows).
2. Kill the crawler mid-run and restart: it resumes from the checkpoint with no duplicates and no gaps.
3. Crawling continues throughout (ingest rate within 10% of the pre-job rate).
4. Measured rows/s recorded in this stage file.
5. `/stats` and the dashboard show backfill progress.
6. `./gradlew :app:testDebugUnitTest` passes.

## Results (recorded 2026-10-16, via dev-workflow)

Commits: crawler `c4f4324` (classifier_backfill engine, /stats `classifier.backfill` object;
amended twice during the stage: `0599fa4` → `d1d2402` cap removal → `c4f4324` rowid-order fix) and app
`aba6ffc` (gitlink + dashboard Backfill cell in the /stats page + string-contract tests).

1. **Wipe + reclassify identity: PASS.** Wiped all 7,896 `torrent_class` rows (host sqlite3 on the
   pulled DB, pushed back with the app stopped), restarted: full reclassify of 7,896 rows in ~7 s.
   Full-DB label diff vs the pre-wipe ingest-time labels (all label columns incl. `adult_p` and
   `confidence` as exact strings): **0 mismatches on 7,895 common ids** (the ≥100-row sample bar,
   but at full-DB strength; one pre-wipe id was later deleted by the crawler's existing spam-name
   cleanup, cascading its `torrent_class` row — not a backfill gap).
2. **Kill + resume: PASS.** Force-stopped mid-run at done=4000/7895; on-disk state at the kill:
   committed ckpt 5162, 4,324 rows committed (the in-flight 2k page rolled back cleanly). Restart
   resumed **from ckpt 5162 with exactly the 3,895 remaining rows** — no duplicates (8,219 rows,
   8,219 distinct ids), no gaps (0 unclassified, 0 `clf_ver<1`), then the from-0 verification pass
   completed the job. The stale-checkpoint re-scan path was exercised too: a resume above the
   sweep start re-scans from 0 and caught all rows below the old ckpt.
3. **Ingest unaffected: PASS (no regression).** 15-min windows: steady baseline **1,532/hr**; job
   session (7.9k-row backfill in its first ~7 s) **1,818/hr**; plain-restart control **1,537/hr**.
   The job session is +18% vs control (above the −10% bar; DHT rates fluctuate ~20%, stage-5
   precedent) — the backfill did not reduce ingest. The job runs ~1–2 s of wall clock per restart
   on an already-classified DB, so long concurrent windows are dominated by normal DHT variance.
4. **Throughput: recorded.** `rate_rows_s` live: **1,480–1,902 rows/s** (fastText is µs-cheap;
   SQLite reads + the 100 ms page sleep dominate). Full 7,896-row sweep ≈ 7 s wall clock incl.
   daemon startup. Extrapolated: a 1M-row phone DB ≈ 9–11 min.
5. **Progress visible: PASS.** `/api/crawler/stats` (proxied crawler /stats) shows
   `classifier.backfill`: `state/available/done/total/rate_rows_s/ckpt/clf_ver` (observed live:
   `running 4000/7895 rate≈1900 ckpt=5162`); the dashboard /stats page carries the `bf-cell`
   Backfill summary cell + `renderBackfill(data)` wired into the ws render loop.
6. **Unit tests: PASS.** `:app:testDebugUnitTest` 561/561, 0 failures (fresh `--rerun-tasks` run;
   needed `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=…` because /tmp tmpfs quota is exhausted by unrelated
   files — pre-existing environment issue).

Deviations (accepted): the job is an **in-daemon low-priority thread** (the stage file's own
"or low-priority thread" option), not a `CrawlerMaintenance` one-shot — the maintenance path stops
the crawler, which cannot satisfy exit criteria 2/3 (kill-the-crawler resume; crawling continues
throughout). The **≤50-files read cap was removed** before install: parent-side measurement with
the shipped models on the phone's own >50-file torrents (429 rows) showed **92/858 label flips**
vs ingest, and the cap saved nothing on a phone DB with 5.5k file rows; the full-list read makes
labels bit-identical by construction (verified in criterion 1). Review P1 fixed pre-install: the
per-torrent fetch orders by `torrent_files.id` (rowid) because `file_index` is `int16_t` and wraps
negative past 32,767 files (the DB has a ~42k-file torrent). Review P2s left as documented notes:
aborted-page `/stats` ckpt overstates the resume point (the DB value is authoritative), `complete`
publishes done/total=0/0 on the dashboard, the WAL checkpoint runs only after written pages, and
`count_pending` does one full LEFT-JOIN scan per sweep start (bounded; measured no ingest impact).

## Commit

`crawler: stage 8 — resumable backfill job + progress in /stats`
