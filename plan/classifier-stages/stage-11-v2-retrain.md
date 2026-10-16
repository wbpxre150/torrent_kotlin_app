# Stage 11: v2 student retrain — fold audited failures back into the training corpus

> **Target repo: `~/apps/classifier-model`** (training + audit tooling; no crawler/app changes
> beyond the deferred gitlink bump).
> ⚠ HARD CONSTRAINT: all teacher LLM traffic (`http://192.168.0.24:8081/v1`) is executed
> **only by the parent session** — never by explorer/implementer subagents.
> ⚠ HARD CONSTRAINT: `~/torrents.db` stays untouched; failure-row harvest reads the
> classified copy `~/torrents-classified.db` read-only (`?mode=ro`).

## Context (stage 10 outcome, 2026-10-17)

- The 300-item audit against teacher **FASTx1** measured adult 88.3% / category 78.7% /
  tv_kind 76.3% — statistically conclusive failure vs the ≥90% gate; the full 33k run
  (~10–15 h of teacher time) was **not** executed (user decision; early-stop is documented
  as a protocol deviation).
- **FASTx2 adjudication of 30 mismatch rows** (2×15-item calls, 34 s, 0 rejects): the
  failures are **majority genuine student errors** — adult 9/11, tv_kind 8/10, category
  10/18 (plus 3 FASTx1-teacher noise, 6 all-differ/ambiguous). The student's adult misses
  are almost all in the safety-critical direction (student 0, teacher 1 = under-flagging).
- Gate-design flaw found: the gold set was labeled by the **FASTx2 bulk + SLOWx2 rescue
  cascade** (`config/labeler.toml`), but the stage-10 audit teacher was pinned to
  **FASTx1** — a different serving config of the same weights with a 13% invalid-rows
  reject rate (FASTx2: 0%). The v2 gate must use **FASTx2** as the audit teacher.

## Answer to "finetune or add to the dataset?"

fastText has **no incremental fine-tuning**: the only mechanism is retraining from scratch
on the full corpus. So the plan is the second option the user proposed — append the
adjudicated failure rows to the existing 53,648-row teacher-labeled corpus
(`data/labels/s120000_42.label_v1.jsonl` + matching `features_s120000_42.jsonl` rows) and
retrain the three students as **models/v2**. Training is cheap (minutes on CPU at 53k rows).

## Design

1. **Harvest failures** (parent session, teacher traffic):
   - Re-run the audit with `--model FASTx2` (fixes the teacher mismatch; also use as the
     definitive v1 baseline). Two options, decided at execution time:
     - **A. Full 33k** (~10 h at ~0.9 items/s, resumable, run overnight): definitive
       v1-vs-FASTx2 baseline + the largest failure corpus (~4k mismatch rows at 79%).
     - **B. Bounded 5k** (~1.5 h): quick harvest; the v2 gate re-run supplies the
       definitive number later.
   - `--state-dir data/audit/v1-fastx2` so results are the durable record.
2. **Adjudicate every mismatch row** (parent session):
   - Re-label each mismatch id through FASTx2; rows FASTx2 rejects (or where the student
     and FASTx2 still disagree on adult) go to **SLOWx2** rescue — same consensus spirit
     as `cm gold`. Rows where student, FASTx2 and SLOWx2 all differ → discard as
     ambiguous (never add a coin-flip label).
   - Output: `data/labels/audit_failures_v1.jsonl` (rows like the v1 label file:
     `{"id", "adult", "category", "tv_kind", "model", "prompt"}`) plus
     `data/samples/features_audit_failures_v1.jsonl` (tparse feature rows; replicate the
     endpoint's file selection `ORDER BY file_index LIMIT 20` from the classified copy).
3. **Corpus + retrain** (implementer, no teacher traffic):
   - `data/labels/s120000_42.label_v2.jsonl` = v1 labels ∪ adjudicated failures
     (dedup by id; failure labels win where ids collide — there are none: audit ids come
     from the full DB, training ids from the seed-42 120k sample of the *original* DB).
   - Oversample adult under-flag failures ×2 (repeat those lines in the train file —
     fastText treats duplicates as weight); keep all other hyperparameters verbatim from
     `config/train.toml` (no re-autotune: avoid tuning on the audit set).
   - `cm train --seed 43` replay → v2 models; holdouts: original val split metrics must
     be ≥ v1 (category acc, adult recall/precision at the 0.97 operating point).
   - `cm calibrate` on v2 → new `adult_hide_threshold` (under-flagging is partly
     threshold-addressable); `cm export` → `models/v2/manifest.json` (new sha256s,
     `clf_ver=2`).
4. **Ship v2 + re-gate** (parent execution):
   - `tclassify-db --clf-ver 2 --write` backfill on the classified copy (resumable;
     coverage 100% as in stage 9).
   - Fresh 33k audit vs FASTx2 (new `--state-dir`; the audit samples the full 7.7M-row
     pool so training-contamination from the ~4k harvested failure ids is ~0.05% of draws
     — acceptable, but record the ids in the plan results).
   - If ≥90% per field: bump the app's vendored-crawler gitlink + phone import
     (deferred stage-9 item) in a follow-up. If not: honest failure, iterate corpus
     (bigger harvest) — never tune-to-green on the gate sample.

## Acceptance criteria

1. Adjudicated failure corpus committed (labels + features rows; FASTx2/SLOWx2 provenance
   on every row; ambiguous rows discarded with a count).
2. v2 trained; held-out metrics ≥ v1 on the original val split; adult precision at the
   operating point ≥ 0.97.
3. `models/v2/manifest.json` exported: three sha256s + recalibrated hide threshold,
   `clf_ver=2`.
4. Classified copy backfilled at `clf_ver=2`, 100% coverage verified by SQL.
5. Fresh 33k audit vs FASTx2: **≥90% per field** (adult, category, tv_kind) — or an honest
   FAIL recorded with numbers.

## Commit

`classifier-model: stage 11 — v2 students retrained on gold + adjudicated audit failures`
