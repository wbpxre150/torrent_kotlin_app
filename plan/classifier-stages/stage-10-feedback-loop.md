# Stage 10: Feedback loop (optional)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repos: both** — `~/apps/torrent_kotlin_app` (feedback table + dashboard action) and
> `~/apps/classifier-model` (`cm import-feedback` + retrain wiring). Design: `plan/classifier.md` §6.8, §8.
>
> ⚠ HARD CONSTRAINT: this stage includes periodic **retraining**, which re-runs teacher labelling
> (Stage 2 machinery) on low-confidence rows plus feedback. All teacher-LLM traffic
> (`http://192.168.0.24:8081/v1`) is executed **directly by the parent session itself** — implementer/explorer
> subagents write the tooling but never run the labelling loop against the real endpoint.

## Goal

Fix mistakes and improve the model over time.

## Depends on

Stages 5–8 shipped (ingest, config, search, phone backfill) and Stage 2 tooling (`cm label`).

## Scope

1. App side:
   - Dashboard "wrong label" action → stored in a `class_feedback` table (crawler DB), including the old
     label, the corrected label, and a timestamp.
2. classifier-model side:
   - `cm import-feedback` pulls the `class_feedback` table over adb into `data/feedback/`.
   - Retrain monthly: Stage 2–3 re-run on low-confidence rows plus feedback → bump `clf_ver`, ship the new
     `.ftz` + manifest (the parent session runs any teacher labelling pass directly).
   - Then Stage 8's re-classify loop (`clf_ver < current`) relabels the phone on the next model bump.

## Exit criteria

A full round trip works: mark a row wrong in the dashboard → `cm import-feedback` pulls it → retrain bumps
`clf_ver` (with teacher passes run by the parent, or skipped if the feedback alone suffices for the round) →
install the new `.ftz` → the phone's re-classify loop relabels the row (verify the specific row's new label
on-device).

## Commit (per repo)

- app: `crawler: stage 10 — class_feedback table + dashboard "wrong label" action`
- classifier-model: `classifier-model: stage 10 — cm import-feedback + retrain wiring`
