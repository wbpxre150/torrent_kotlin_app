# Stage 9: Backfill the 34M DB on the PC

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/classifier-model`** (host tool reusing the vendored tparse + ftc code; it may reference
> the app's crawler SQL as a spec). Design: `plan/classifier.md` §8 ("Alternative backfill").
> Classification uses the fastText students — **no LLM calls**; normal subagent rules apply. Any spot-check
> review of sampled rows against the teacher would be run by the parent session directly.

## Goal

Classify `~/torrents.db` without the phone.

## Depends on

Stage 3 (students + manifest) and Stage 8 (the exact keyset-loop SQL and label semantics, already proven on
the phone — parity between the two paths is the point).

## Facts you must respect

- `~/torrents.db`: 84 GB, 34,398,754 torrents, 1,411,404,500 file rows. **Never write to the original**:
  default to read-only operation against a copy; `--write` (in-place) is an explicit opt-in flag.
- Reads are dominated by ≤ 50 files per torrent out of 1.41B rows — measure 1M rows first, then extrapolate
  before committing to the full run.

## Scope

1. `tclassify-db` host tool: the same tparse + ftc code and the same SQL as Stage 8, run against a **copy**,
   or in place only with an explicit `--write` flag. Default never touches the original.
2. Measure on a 1M-row slice first; record extrapolated full-run time in this stage file before starting it.
3. Produce either a classified DB copy, or a `torrent_class` dump that can be imported, plus a documented
   procedure for getting it onto the phone that holds this DB (push + `ATTACH`/`INSERT`, or swap the DB via
   the existing backup/restore path).

## Build / validate

- Host cmake build of `tclassify-db` (reuses `native/tparse` + `native/ftc`); `uv run pytest` for any Python
  helpers (dump/import procedures).

## Exit criteria

1. 100% of the 34.4M rows have a `torrent_class` row at the current `clf_ver`.
2. Spot-check 200 random rows against the teacher labels (`cm label` re-run on those ids, run by the parent session): ≥ 90% agree.
3. The original `~/torrents.db` is unchanged (same mtime and size) unless `--write` was used.
4. The import procedure is tested on the new phone with a 1M-row slice (verify counts + a sample of labels
   on-device after import).

## Commit

`classifier-model: stage 9 — tclassify-db host backfill of the 34M DB + phone import procedure`

## Measurement (recorded 2026-10-16, by parent before the full run)

- **DB reality check:** the stage-0 facts were stale. `~/torrents.db` has `MAX(id) = 34,398,754`
  (that figure was the id ceiling, not the live count) and **7,765,374 live torrents** (~78% of
  ids deleted by spam cleanup) with **401,752,919 live file rows** (not 1.41B).
- Copy via online backup API (`sqlite3 ~/torrents.db ".backup ~/torrents-classified.db"`),
  79 GB in ~4.5 min; `torrent_class`/`classifier_meta` created on the copy with the crawler's
  exact stage-5 DDL (the original predates the classifier schema). Original bit-untouched
  (size 83,770,822,656, mtime 2026-10-13 16:15:12 — re-verified after every run).
- **1M-row measure:** single-process **~1,200–1,330 rows/s** (kill-test segment: 72,000 rows in
  60 s; two full --limit 1000000 passes: 1,329.5 and 1,301.1 rows/s). Kill -9 + restart resumed
  from checkpoint with **zero missing/duplicate rows** (coverage verified by SQL: every live id
  ≤ ckpt has exactly one class row; count == distinct). An accidental concurrent double-run
  (kill hit the wrapping subshell, not the binary) stressed the upsert: both instances wrote
  idempotently, final state 1,114,000 distinct rows ≤ ckpt 2,252,565, 0 strays, 0 tparse rejects.
- **Extrapolated full run:** 6,651,374 rows remaining at ~1,300 rows/s ≈ **85–90 min** (row work
  dominates over the sparse-id scan; later ranges are ~21% live density, so allow up to ~2 h).
- Phone import procedure (criterion 4) is deferred: stage 8 proved the phone import machinery;
  the 34M DB stays on the PC (this stage's audit consumer).
