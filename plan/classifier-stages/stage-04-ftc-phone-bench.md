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
