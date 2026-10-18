# Stage 14: v2 backfill of the PC classified copy (optional, not phone-blocking)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/classifier-model`** (host `tclassify-db`; no phone, no LLM — normal
> subagent rules apply).

## Goal

Bring the PC-side audit consumer up to v2: re-classify `~/torrents-classified.db` (the working copy
of the 34M DB, stage 9) at `clf_ver=2`, so the next feedback round / `cm audit` / `/random-sample`
comparisons run against v2 labels instead of stale v1 rows.

**Status: optional.** Not required for the phone rollout (stages 12–13 are complete without it).
Recommended before the next teacher feedback round, since sampling v1-labeled rows against v2
students would produce misleading mismatches.

## Depends on

Stage 12 step 0 (fold-all v2 promoted to `models/v2/` with its `cm export` manifest); stage 9
(`tclassify-db`, proven resumable keyset backfill).

## Facts you must respect

- `~/torrents-classified.db`: 79 GB working copy, ~7.77M live torrents, `torrent_class` at
  `clf_ver=1`; `~/torrents.db` original must remain untouched (verify size + mtime after every run).
- Measured (stage 9): ~1,300 rows/s single-process → full remaining sweep ≈ 1.5–2 h; kill -9 +
  resume proven; concurrent double-run proven idempotent.
- `tclassify-db` resolves the students from the models dir recorded in the manifest — point it at
  `models/v2` (verify the loaded sha256s against `models/v2/manifest.json` in its startup output
  before walking away from the run).

## Scope

1. `tclassify-db` (host build) against the copy with the v2 students, `clf_ver=2`, resumable;
   the same label semantics as the phone path (parity with stage 8/13 is the point).
2. Coverage + spot-check verification, then record the run here.

## Exit criteria

1. 100% of live torrent ids in `~/torrents-classified.db` have exactly one `torrent_class` row at
   `clf_ver=2` (SQL: count == distinct, zero `clf_ver<2`, zero missing).
2. ≥ 200-row spot-check: labels bit-identical to a re-run of the same ids (determinism check), and
   the run's model sha256s match `models/v2/manifest.json`.
3. Original `~/torrents.db` unchanged (size + mtime re-verified).
4. Wall clock + rows/s recorded in Results.

## Commit

`classifier-model: stage 14 — v2 backfill of the classified copy`

## Results

(record after the stage run)
