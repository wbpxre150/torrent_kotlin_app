# Stage 10: Teacher-audited accuracy gate (replaces the feedback loop)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repos: `~/apps/classifier-model`** (`cm audit` tooling) **and `~/apps/dht_crawler`**
> (the vendored crawler, PC clone — one new read-only endpoint). **No phone work** and no changes to
> `~/apps/torrent_kotlin_app` in this stage. This supersedes the old feedback-loop design
> (`plan/classifier.md` §6.8); the `class_feedback` table, dashboard "wrong label" action,
> `cm import-feedback`, and monthly retrain are **dropped from the plan entirely**.
>
> ⚠ HARD CONSTRAINT: the audit sends torrent data to the teacher LLM at `http://192.168.0.24:8081/v1`
> (alias `FASTx1`, Ornith-1.5-35B-A3B Q6_K). All audit/teacher traffic is executed **directly by the
> parent session itself** — implementer subagents write the tooling (`cm audit`, prompts, comparison
> logic, resume logic) but never run it against the real endpoint. Explorers never call the endpoint.

## Goal

Prove the shipped student model is working: sample random torrents from the crawler's HTTP endpoint
(running on the PC against the classified 34M DB), have the local-llama teacher classify the same
torrents, and require **per-model agreement ≥ 90% (adult, category, tv_kind) after a cumulative
sample size of 33,000 torrents**. If the bar is met, the model is considered working and the
classifier plan is done.

## Depends on

- Stage 9: a classified copy of `~/torrents.db` on the PC at the current `clf_ver` (the audit
  compares the DB's student labels, so rows must be classified by the shipped model).
- Stage 2 tooling: the `cm label` teacher prompt machinery (name + filenames, `json_schema`
  output, thinking off) is reused verbatim.
- Stage 5 schema: `torrent_class` / `classifier_meta` exist in the PC crawler clone (vendored-repo
  commits from stage 5 are already in `~/apps/dht_crawler`).

## Facts you must respect

- `~/torrents.db`: 84 GB, 34.4M torrents, 1.41B file rows. **Never write to the original** — point
  the crawler at the classified copy (Stage 9's read-only workflow).
- Teacher throughput: ~3.5 items/s single slot → 33,000 items ≈ 2.6 h. Batched prompts may improve
  this. The run is resumable, so it may be split across parent sessions.
- `ORDER BY RANDOM()` over 34M rows is too slow — sample via random-rowid probing (retry on misses)
  or `id >= abs(random()) % max_id` walk loops. A 100-item batch must return in < 2 s.
- Established teacher gap (stage 0 facts): `tv_kind` needs filenames — every teacher prompt must
  include the torrent's file list, not just the name.
- At 33,000 samples the 95% CI on a ~90% proportion is ≈ ±0.3%, so the 33k total is far more than
  statistically necessary; treat it as the fixed acceptance bar, not a minimum.

## Scope

1. Crawler (`~/apps/dht_crawler` — vendored-repo commits are sanctioned per the stage 5 precedent;
   never edit `lib/`):
   - New **read-only** JSON endpoint `GET /random-sample?count=N&clf_ver=V`:
     - Returns up to N torrents with a `torrent_class` row at `clf_ver` (omit/`current` → latest
       `classifier_meta`), sampled uniformly at random via random-rowid probing.
     - Item JSON: `{id, infohash, name, files: [top ~20 filenames], student: {category, adult,
       tv_kind, confidence}}` — filenames included because the teacher needs them for `tv_kind`.
     - No DB writes anywhere in the handler; endpoint is stateless (dedup is the client's job).
     - The host-run crawler binds its HTTP API to loopback and points `db_path` at the classified
       copy; document the exact `config.ini` + build/run commands in the commit message or repo docs.
   - This endpoint ships to the phone only via a future gitlink bump in the app repo — **not** part
     of this stage's exit criteria.

2. classifier-model (`cm audit`):
   - `cm audit --base-url http://127.0.0.1:<port> --target 33000 [--batch N] [--clf-ver V]`:
     - Loop: fetch a random batch → teacher-classify each item (Stage 2 prompt + `json_schema`,
       thinking off) → compare teacher vs student per field → append one result row.
     - Cumulative, resumable state in `data/audit/`: `results.jsonl` (id, student, teacher, per-field
       agree flags) + `state.json` (total audited, per-model tallies, audited-id set). An
       interrupted run resumes without re-counting or re-auditing already-seen torrent ids; batches
       containing already-audited ids are re-rolled client-side.
     - Comparison semantics: `adult` = boolean agreement; `category` = exact taxonomy match;
       `tv_kind` = agreement over rows where either side is non-null (both null excluded), and
       one-sided null counts as a miss.
   - `cm audit --report`: prints cumulative N and per-model agreement %, and writes mismatches to
     `data/audit/mismatches-<date>.jsonl` (kept for any future retrain effort — out of scope here).
   - Pure comparison/sampling logic is unit-tested with a mocked endpoint and mocked teacher
     (`uv run pytest`); the parent runs the real thing.

## Exit criteria

1. `/random-sample` serves correct JSON from the classified DB copy; a 100-item batch returns in
   < 2 s; the original `~/torrents.db` is bit-untouched (same mtime/size).
2. Resume works: run a few hundred items, kill, restart — no duplicate ids audited, tallies add up
   exactly across runs.
3. **The gate:** after a cumulative total of 33,000 audited torrents, per-model agreement with the
   teacher is ≥ 90% for **each** of `adult`, `category`, and `tv_kind` → the model is considered
   working. Record the three final numbers in this file's Results section.
4. If any model is < 90%: record which one(s) and the numbers, and the stage ends "model not
   working" — mismatches are preserved in the mismatches file; remediation/retraining is explicitly
   out of scope and would be a new stage.
5. `uv run pytest` passes in classifier-model (audit comparison, dedup, and resume logic tests).

## Commit (per repo)

- dht_crawler: `crawler: stage 10 — read-only /random-sample audit endpoint`
- classifier-model: `classifier-model: stage 10 — cm audit teacher accuracy gate (33k @ ≥90%)`

## Results (2026-10-17) — GATE NOT MET, remediation staged

- Tooling shipped: crawler `708d849` (`/random-sample`), classifier-model `3eea635` (tclassify-db,
  executed as stage 9), `8993326` (`cm audit`) + `80efcf5` (review fix-ups: teacher stall bound).
  Full fresh-context review: PASS-WITH-NOTES (P1 live-lock fixed; P2s noted).
- Stage 9 (folded in): 7,765,374-row backfill of the classified copy at ~1,391 rows/s
  (79.7 min), 100% coverage, zero tparse rejects, original `~/torrents.db` untouched.
  Host crawler served the endpoint from the classified copy (50k-node warm BEP51 cache
  to skip bootstrap; 100-item batches in ~0.1 s).
- **Audit stopped at n=300** (user decision): adult 88.3% / category 78.7% / tv_kind 76.3%
  vs teacher FASTx1 — statistically conclusive failure; the ~10–15 h full 33k run was not
  executed. Protocol deviation (33k bar not reached) recorded here honestly.
  Resume/crash-safety criterion met: SIGKILL + restart, 0 duplicate ids, tallies exact.
- FASTx2 adjudication of 30 mismatch rows: majority genuine student errors (adult 9/11,
  category 10/18, tv_kind 8/10); adult misses almost all under-flagging (student 0,
  teacher 1). FASTx1 teacher noise + all-differ ambiguity account for the rest.
- Root cause notes: (a) audit teacher FASTx1 ≠ gold-cascade teacher FASTx2/SLOWx2
  (13% vs 0% invalid-rows reject rate); (b) students genuinely underperform on
  extension-ambiguous content (.pdf/.epub) and adult detection.
- Remediation: **stage-11-v2-retrain.md** — fold adjudicated failures into the training
  corpus, retrain models/v2, re-gate 33k vs FASTx2.
