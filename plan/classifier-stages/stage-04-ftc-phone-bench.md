# Stage 4: `ftc` inference wrapper and phone benchmark

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/classifier-model`**, code under `native/ftc/`. Requires the test phone on USB.
> No LLM involved (inference uses the Stage 3 fastText students) — normal subagent rules apply.

## Goal

Prove inference in C on arm64 before touching the app.

## Depends on

Stage 3 (`models/v1/{adult,category}.ftz` + manifest) and Stage 1 (tparse).

## Scope

- An `extern "C"` wrapper built from the fastText inference sources, API as specified in
  `native/ftc/README.md` (`ftc_load` / `ftc_predict` / `ftc_free`), compiled from args, dictionary, matrix,
  quantmatrix, model, loss, fasttext, productquantizer, vector, densematrix, utils only. Thread-safe for
  concurrent `ftc_predict` calls on one loaded model.
- `ftc_bench`: loads both models and classifies a JSONL file with tparse + ftc, printing labels and µs/item.
- Parity test: on 10k rows, host `ftc` output equals the `fasttext predict-prob` CLI (labels identical,
  probabilities within 1e-5).
- NDK build of `ftc_bench` (`$HOME/Android/Sdk/ndk/android-ndk-r27`, `arm64-v8a`/android-24), `adb push`
  to the new phone's `/data/local/tmp`, run against 10k rows.

## Build / validate

- Host: cmake build of `ftc` + `ftc_bench`; parity test script.
- Android: NDK build, `adb push`, run on device with the 10k row file + both `.ftz` models pushed alongside.

## Exit criteria

1. Parity passes (10k rows, labels identical, probs within 1e-5).
2. Phone p50/p99 latency recorded in this stage file; target < 200 µs/item including tparse.
3. Peak RSS on the phone < 40 MB (record measured value).

## Commit

`classifier-model: stage 4 — ftc fastText C wrapper, host parity, arm64 phone benchmark`

## Results (recorded 2026-10-15, via dev-workflow)

Commits in `classifier-model`: `827b11c` (stage 4a — wrapper + host bench + parity), `ee8be76` (stage 4 — NDK build script), `e6f0f56` (stage 4 fix — review fix-ups + streaming rows).

1. **Parity: PASS.** 10,000 rows (first 10k of `data/samples/s120000_42.jsonl`), both models: top-1 labels identical to `fasttext predict-prob`, max |Δprob| = 1e-6 ≤ 1e-5 (host, `scripts/ftc_parity.sh`). Feature texts byte-identical to the tparse CLI; TOL=0 negative control fails (gate not vacuous).
2. **Phone latency: PASS at p50.** OPPO CPH2305 (arm64-v8a, API 35), `build/ftc-ndk/ftc_bench`, 10k rows, warmup 1000, second warm run: **p50 = 134.4 µs/item** (run 2: 134.9), mean = 140.5 µs/item, **p99 = 247.4 µs/item** (run 2: 257.6). Target < 200 µs/item including tparse is met at p50; p99 is recorded as tail data (operator ruling 2026-10-15: the 200 µs target applies to typical cost; p99 is record-only, tail comes from large rows).
3. **Peak RSS: NOT MET, accepted as honest miss.** Measured **41,480–41,536 KB VmHWM (~40.5 MiB)** on the phone. Root cause: two resident models dominate — a 1-row floor run with both models loaded measures 41,300 KB (~19 MB per 8.9 MB `.ftz`); the bench itself adds only ~0.2 MB (rows are streamed, not buffered). No ftc_bench-side change can reach < 40 MB with both models resident on this device (operator ruling 2026-10-15: record the miss; footprint work would be a separate stage if the app budget requires it).
