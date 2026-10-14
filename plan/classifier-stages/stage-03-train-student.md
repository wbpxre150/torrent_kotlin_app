# Stage 3: Train, calibrate and export the student

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/classifier-model`**. Design: `plan/classifier.md` §5, §6.5–6.7.
> Note: this stage uses fastText on the workstation CPU — **no LLM calls** (labels already exist from Stage 2),
> so normal subagent rules apply.

## Goal

Small fastText models that beat the keyword baseline.

## Depends on

Stage 2 (teacher labels + frozen gold set) and Stage 1 (tparse feature text).

## Scope

1. `scripts/fetch_fasttext.sh` fetches fastText into `native/third_party/`; pin the commit it checks out.
2. `cm train`:
   - Feature text from tparse; labels from the teacher, filtered (drop rows where the teacher and tparse
     strongly conflict on `tv_kind`).
   - Two models, `adult` and `category`. `tv_kind` stays a parser decision; train a third model only if
     tparse leaves > 10% of tv rows undecided (measure and report).
   - Autotune on a 10% validation split, then quantize (`.ftz`).
3. `cm calibrate`: choose `adult_hide_threshold` (precision-oriented) and `adult_drop_threshold` (stricter)
   on gold.
4. `cm export`: `models/v1/{adult,category}.ftz` plus `manifest.json` (sha256, thresholds, taxonomy version,
   tparse version, fastText rev).

## Build / validate

- `uv run pytest`.
- Reproducibility check: clean re-run from the committed code produces models with the manifest's sha256s
  (record seed/version pins).

## Exit criteria (on the frozen gold set)

1. Student adult F1 ≥ keyword baseline (Stage 0) + 10 points, and adult precision ≥ 0.97 at the hide threshold.
2. Category accuracy ≥ 0.90.
3. Each `.ftz` is ≤ 10 MB.
4. Training is reproducible from a clean checkout in < 1 h.
5. `uv run pytest` passes.

## Commit

`classifier-model: stage 3 — fastText student (train/calibrate/export) beating keyword baseline`
