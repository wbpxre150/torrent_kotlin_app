# Classifier stage index

Per-stage implementation packets live here, one file per stage, implemented **one at a time** via
`/dev <stage file>` (dev-workflow). `plan/classifier.md` is the design/research doc; `plan/classifier-stages.md`
is a stub pointing here. Stages 0–4 target repo **`~/apps/classifier-model`**; stages 5–10 target **this app**.

Each stage file is a complete, cold-start task packet: goal, repo, dependencies, scope, constraints,
build/validate commands, exit criteria with verification methods, and the planned commit message.

## Global hard constraint: the teacher LLM

Any step that sends torrent data (from `~/torrents.db`, its samples, or DB exports) to the llama.cpp teacher
at `http://192.168.0.24:8081/v1` (aliases `FASTx1` / `FASTx2` / `SLOW`) is executed **directly by the parent
session itself** — never by an implementer or explorer subagent. Implementers may *write* the labelling code
(`cm label`, `labeler.py`, prompts, retry/resume logic, evaluation tooling), but the parent runs the actual
labelling/probe/gold-prelabel requests against the model. Explorers never call the endpoint.
This applies to Stages 2 and 10 (and any ad-hoc teacher probe done along the way).

## Facts established 2026-10-14 (they replace the estimates in classifier.md)

| Item | Value |
|---|---|
| Source DB | `~/torrents.db`: **84 GB, 34,398,754 torrents, 1,411,404,500 file rows**, WAL. Schema as in classifier.md §2. Adult content is well represented, so the old filter was not dropping most of it. |
| Teacher LLM | llama.cpp router `http://192.168.0.24:8081/v1`, alias **`FASTx1`** = Ornith-1.5-35B-A3B Q6_K (parallel 1). `FASTx2` is the same model with 2 slots, and `SLOW` is a 27B dense model, both unloaded. The V100 32 GB is **fully used** by llama-server. |
| Teacher probe | 19 real rows, names only, `json_schema` output, thinking off: **5.4 s → ~3.5 items/s**. Prompt 613 tok/s, generation 152 tok/s, ~27 output tok/item. JAV, hentai, Italian adult titles, anime and TV episodes were all correct. `Petbooks` and the cosplay zip were judged non-adult, a correct but borderline call. Gap: `tv_kind` guesses without a file list (a series pack labelled `complete_series`), so prompts must include filenames. |
| Teacher budget | 100k rows ≈ 8 h, 300k ≈ 24 h at one slot. Batches of 20 with a cached system prompt may improve on this. |
| Training box | This workstation: 8 threads, 31 GB RAM, Python 3.14, `uv`, gcc/g++/cmake/clang/ninja, adb. fastText is CPU-only, built from source, **no sudo needed**. |
| GPU box (ssh `adam@192.168.0.24`) | Manjaro, 12 threads, 30 GB RAM, Python 3.14 + pip, gcc/cmake, **no `uv`, no `nvcc`**. Not needed unless the e5 fallback (classifier.md §5) becomes necessary, which would also require freeing VRAM. |
| Test phone | A new phone over USB, building a fresh DB from new torrents. Backfill code is tested there. The 34M DB is backfilled on the PC (Stage 9). |

**Manual installs:** none required so far. If the e5 fallback is ever needed: CUDA toolkit or PyTorch on the GPU box
(the user installs it, since there is no sudo), and llama-server would have to release VRAM while training runs.

## Order and effort (rough)

| Stage | File | Where | Effort | Blocking wait |
|---|---|---|---|---|
| 0 Data | `stage-00-data-foundations.md` | classifier-model | S | — |
| 1 tparse | `stage-01-tparse.md` | classifier-model | M–L | — |
| 2 Labels + gold | `stage-02-teacher-labels-gold.md` | classifier-model | M | ~10 h teacher run + ~3 h human review |
| 3 Train | `stage-03-train-student.md` | classifier-model | S–M | — |
| 4 ftc + phone bench | `stage-04-ftc-phone-bench.md` | classifier-model | M | phone on USB |
| 5 Ingest | `stage-05-ingest-classification.md` | app | L | phone on USB |
| 6 Adult config | `stage-06-adult-config.md` | app | M | — |
| 7 Search/UI | `stage-07-search-dashboard.md` | app | M–L | — |
| 8 Phone backfill | `stage-08-phone-backfill.md` | app | M | — |
| 9 PC backfill | `stage-09-pc-backfill.md` | classifier-model | M | long run (measure first) |
| 10 Feedback | `stage-10-feedback-loop.md` | both | M | — |

Stages 0 and 1 can overlap. The Stage 2 teacher run can start on name-only prompts once Stage 0 is done, but
then `tv_kind` labels will be weaker, so prefer waiting for tparse hints.
