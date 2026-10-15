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

## Commit

`crawler: stage 8 — resumable backfill job + progress in /stats`
