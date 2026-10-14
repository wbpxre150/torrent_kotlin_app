# Stage 0: Data foundations

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/classifier-model`** (not the torrent app). Design context: `~/apps/torrent_kotlin_app/plan/classifier.md` §2, §6.1.

## Goal

Reproducible, read-only samples of `~/torrents.db`, plus a baseline score for the current keyword filter.

## Depends on

Nothing. This is the first stage. The repo scaffold (`cm` CLI entry point, `src/classifier_model/`, `tests/`,
`uv` project) already exists.

## Facts you must respect

- Source DB: `~/torrents.db` — 84 GB, 34,398,754 torrents, 1,411,404,500 file rows, WAL. **Never write to it.**
  Open with `file:...?mode=ro` (or `sqlite3 -readonly`). Never full-scan `torrent_files`; look files up by
  `torrent_id` through the `idx_files_torrent` index. Do not use `ORDER BY random()` on 34M rows.
- Files per torrent: fetch ≤ 50 through `idx_files_torrent` + `path_prefixes`.
- House rules (repo AGENTS.md): labels/enums/feature tokens defined once in `config/taxonomy.json`;
  `data/gold/` is hand-reviewed and frozen; tests via `uv run pytest`.

## Scope

1. `cm sample`:
   - Pick random ids by rowid range; fetch ≤ 50 files per torrent (see above).
   - Write `data/samples/s<N>_<seed>.jsonl`.
   - Produce three outputs: an initial 120k (for teacher labels), a separate 3k (gold candidates), and
     stratified extras (CJK, Cyrillic, ≥ 20 video files, names containing `S\d\d`, `complete`, `season`)
     so rare TV kinds are covered.
2. `cm stats`: name-script mix, file-count histogram, extension mix.
3. `baseline.py`: a faithful port of `porn_filter.c` semantics (substring `[standalone]` list + `xxx`
   co-occurrence + spam marker `manko.fun.url`), run on the samples. Baseline = the current crawler filter's
   hit-rate, to be beaten in Stage 3.

## Build / validate

- `uv run pytest` must pass, including a test asserting the DB is opened `mode=ro` (a write attempt fails).
- Sample determinism: same seed → identical sample output (test this).

## Exit criteria

1. Samples exist and are byte-deterministic for a given seed (verified by re-running one sample with the
   same seed and diffing).
2. The DB is opened `mode=ro`; a test asserts that writing fails.
3. A 120k sample takes < 30 min (record the measured wall time in the commit/report).
4. Baseline hit-rate is reported (number, plus how many adult rows the keyword filter would flag).
5. `uv run pytest` passes.

## Commit

`classifier-model: stage 0 — read-only sampling (cm sample/stats) + keyword-filter baseline`
