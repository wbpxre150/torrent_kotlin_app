# Torrent classifier: implementation stages (index)

The stage list has been split into **one self-contained file per stage** in **`plan/classifier-stages/`**,
so each stage can be implemented one at a time via `/dev <stage file>` (dev-workflow). Each stage file is a
complete cold-start packet: goal, target repo, dependencies, scope, constraints, build/validate commands,
exit criteria with verification methods, and the planned commit message.

- Index, measured facts, and order/effort table: **`plan/classifier-stages/README.md`**
- Stage files: `stage-00-data-foundations.md` … `stage-10-feedback-loop.md`

Stages 0–4 target repo **`~/apps/classifier-model`**; stages 5–10 target **this app**.

**Hard constraint (all stages):** any step that sends torrent data to the llama.cpp teacher
(`http://192.168.0.24:8081/v1`) is executed directly by the parent session itself — never by implementer or
explorer subagents (applies to Stage 2, Stage 10, and any ad-hoc teacher probe).

Companion design/research doc: `plan/classifier.md`.
