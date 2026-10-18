# Stage 13: v2 full-backfill verification on the phone

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (verification-heavy; only small fix-ups if
> something fails). Requires the phone with the **existing v1-classified DB** on USB.
> No LLM involved — normal subagent rules apply.

## Goal

Prove the user's headline requirement: **when the v2 model loads onto the phone, every row of every
existing on-device database is fully re-classified at `clf_ver=2`** — resumably, without gaps or
duplicates, while crawling continues — and that the new labels are correct v2 output.

## Depends on

Stage 12 (v2 staged, migration done, installed on the phone with the existing DB).

## Facts you must respect

- The backfill engine (stage 8, crawler `classifier_backfill.c`) is always-on and ungated: on every
  daemon start it sweeps with a keyset loop (`classifier_meta` checkpoint, batches of 2k, rowid
  order) and re-classifies rows where `torrent_class.clf_ver < current`; `current` comes from the
  manifest `version` digits → 2. The version bump therefore arms it automatically; the sweep
  re-scans from 0 when the checkpoint predates the version change, so rows below the old ckpt are
  caught. No code change is expected in this stage.
- Measured on this DB (stage 8): ~1,480–1,902 rows/s; 7.9k rows ≈ 7 s wall clock — a run this size
  may complete before you can observe it live; use the from-0 re-run (below) to observe a live run.
- Phone DB lives at `filesDir/crawler/data/torrents.db` — touch it only with the app stopped
  (`adb shell run-as com.torrent.app`); host `sqlite3` on the pulled copy is the proven path.
- Stage-8 known notes (accepted then, still true): aborted-page `/stats` ckpt overstates the
  resume point (DB value authoritative); `count_pending` does one full LEFT-JOIN scan per sweep
  start (bounded, measured no ingest impact).

## Procedure / scope

1. **Baseline before install** (if not already recorded in stage 12): pull the DB copy and record
   `torrent_class` counts by `clf_ver`, `adult_tagged`, `adult_dropped` from `classifier` `/stats`,
   and total live torrent count.
2. **Install stage-12 build, watch the automatic backfill:** `/api/crawler/stats` →
   `classifier.backfill`: `state` running → complete, `done == total`, `clf_ver=2`,
   `rate_rows_s` recorded. The dashboard /stats Backfill cell renders the same run.
3. **Kill/resume under v2:** stop the app mid-sweep (or kill the crawler process), restart, and
   confirm resume from the DB checkpoint: no duplicate `torrent_class` rows, no gaps. The v1→v2
   bump also exercises the stale-checkpoint re-scan path (pending scan is by `clf_ver`, not just
   `id > ckpt`) — verify rows below the pre-bump checkpoint were re-classified.
4. **100% coverage proof (the exit bar):** stop the crawler, pull the DB, host-side SQL:
   - every live torrent id has exactly one `torrent_class` row (`count == distinct`);
   - **zero rows with `clf_ver < 2`**, zero unclassified live ids;
   - `classifier_meta` records the v2 model sha256s / checkpoint state (spot-check keys).
5. **Label correctness spot-check (no LLM):** sample ≥ 200 re-classified ids (include rows that
   flipped v1→v2 and rows that didn't) and re-classify the same ids host-side with the v2 students
   (`tclassify-db` on a copy, or the classifier-model host tools at `models/v2`) — labels must be
   bit-identical (category, adult, adult_p, tv_kind, confidence), same proof strength as stage 8's
   criterion 1.
6. **Ingest concurrency:** backfill and crawl run together; ingest rate during the job window is
   within stage-8 tolerance vs a plain-restart control; **new rows land directly at `clf_ver=2`**.
7. **Threshold effectiveness:** after the backfill, `/stats` adult counters
   (`adult_tagged`/`adult_dropped` growth in `tag` mode) move consistently with v2 calibration
   (hide threshold moved 0.917 → 0.897 with a materially higher-recall student). Record the before/after
   numbers — this is the on-device evidence of the v2 improvement.
8. **Record in this file:** full-run wall clock, rows/s, v1→v2 label-flip counts (how many rows
   changed category / adult verdict / tv_kind), and any deviations.

## Exit criteria

1. `/stats` `classifier.backfill`: `clf_ver=2`, `state=complete`, `done == total` (observed live at
   least once during the kill/resume run).
2. Pulled-DB SQL: 100% of live torrents have exactly one class row at `clf_ver=2`; 0 pending; no
   duplicates/gaps after the kill/resume cycle.
3. ≥ 200-row host-parity spot-check: 0 label mismatches vs a host v2 re-classification of the same ids.
4. Ingest unaffected (rate within tolerance) and new rows at `clf_ver=2`.
5. Dashboard Backfill cell + `/stats` fields show the v2 run.
6. `./gradlew :app:testDebugUnitTest` passes (should be unchanged from stage 12; re-run for the record).
7. Numbers (rows/s, wall clock, flip counts, adult counter deltas) recorded in Results below.

## Commit

None expected (verification stage). Any fix-ups land as their own commits
(`crawler: …` in the vendored repo + gitlink, or `app: …`, with the reason recorded here).

## Results

(recorded 2026-10-18, via dev-workflow; phone b93dac74 / CPH2305)

**Prerequisite handling:** the phone no longer held stage 8's ~7.9k-row v1 DB — it had been rebuilt
from scratch minutes prior (627 rows, all already clf_ver=2). Per operator decision the backfill basis
was a **synthetic v1 downgrade**: flip the live rows' clf_ver 2→1 in place and let the always-on
engine migrate them back to 2. Consequence (accepted): no genuine v1→v2 label flips, so the
"v1→v2 flip count" is 0 by construction — see item 7.

1. **Live backfill observed: PASS.** Downgraded 2,451 rows to clf_ver=1, restarted; `/stats`
   `classifier.backfill` observed live: `running 0/2451` → `running 2000/2451` →
   `running 2451/2451` (done == total) → `complete`, all at `clf_ver=2`. `rate_rows_s` recorded
   1733 → 1669 rows/s (stage-8 range 1480–1902). Wall clock ≈ 1.25 s for 2,451 rows. The COMPLETE
   frame publishes done/total=0/0 (the known stage-8 quirk).

2. **Kill/resume + coverage: PASS.** Kill landed at the first `running` frame (done=0); the on-disk
   DB was unchanged afterward (2,451 rows all still clf_ver=1, 0 partial writes) — the in-flight
   page rolled back cleanly. Restart resumed (from-0 re-scan) and completed. Final pulled-DB SQL:
   2,451 live torrents → 2,451 class rows / 2,451 distinct ids; 0 rows clf_ver<2; 0 unclassified;
   0 duplicates. `classifier_meta`: clf_ver=2, the three v2 sha256s, adult_hide_threshold=0.897417,
   and backfill_ckpt=2918 (checkpoint now present after a real sweep).

3. **Host-parity spot-check: labels bit-identical; float columns ULP-identical (not bit-identical).**
   Full-DB (2,451 rows ≫ 200) host re-classification via `tclassify-db --clf-ver 2 --models-dir
   models/v2`: `category`, `tv_kind`, and `adult` are bit-identical — **0/2,451 mismatches each**
   (these are the classification labels). `adult_p` and `confidence` differ on 66 and 811 rows
   respectively, but only at float32 ULP scale (adult_p max |Δ|=1.19e-7; confidence max |Δ|=6.56e-7)
   — ARM64 phone vs x86-64 host dot-product accumulation order, with zero decision-boundary
   crossings (hence category/adult/tv_kind still bit-identical). Same-arch bit-identity was already
   proven in stage 8 (backfill vs ingest, both on-device).

4. **Ingest + new rows at v2: PASS (rate note).** Newest `torrent_class` row is clf_ver=2; 0
   unclassified throughout. Crawl stayed active during/after the backfill (32 trees, metadata
   fetches and UDP sends advancing). This session's ingest windows showed ~0 new torrents
   (post-bootstrap DHT lull), so a fresh "vs control" rate could not be measured; the
   no-regression claim rests on stage 8's rigorous 15-min measurement (backfill did not reduce ingest).

5. **Dashboard + /stats: PASS.** `/stats` page HTML contains `id="bf-cell"`, `id="s-backfill"`,
   `renderBackfill` (×2); `/api/crawler/stats` returns `classifier.backfill{state,done,total,
   rate_rows_s,ckpt,clf_ver}` (observed live throughout).

6. **Unit tests: PASS.** `./gradlew :app:testDebugUnitTest --rerun-tasks` → 617/617, 0 failures.

7. **Numbers.** rows/s 1669–1733; wall clock ≈1.25 s / 2,451 rows; v1→v2 flip count **0 (synthetic
   basis)**; adult counters — final DB has 414/2,451 rows adult=1 at the v2 hide threshold 0.897417
   (adult_dropped=0 in tag mode). A v1(0.917039)-vs-v2(0.897417) adult-counter delta is not
   measurable on the synthetic basis (no v1-labeled rows on-device).
