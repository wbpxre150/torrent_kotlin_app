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
2. Spot-check 200 random rows against a gold-style review: ≥ 90% agree.
3. The original `~/torrents.db` is unchanged (same mtime and size) unless `--write` was used.
4. The import procedure is tested on the new phone with a 1M-row slice (verify counts + a sample of labels
   on-device after import).

## Commit

`classifier-model: stage 9 — tclassify-db host backfill of the 34M DB + phone import procedure`
