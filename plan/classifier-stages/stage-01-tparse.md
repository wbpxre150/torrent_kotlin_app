# Stage 1: `tparse`, the C parser and feature extractor

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/classifier-model`**, code under `native/tparse/`. Design: `plan/classifier.md` §4 (taxonomy + TV-granularity decision).

## Goal

Deterministic name and file-list parsing shared by training and the phone — C11, no dependencies beyond libc
(it is later vendored into the Android crawler build).

## Depends on

Stage 0 (samples exist, used as parser input for timing/fuzz). Stages 0 and 1 can overlap.

## Scope

- C11, no dependencies. API as specified in `native/tparse/README.md` (`tp_parse`, `tp_feature_text`).
  - Release-name regexes, hand-written matchers ported from Sonarr/guessit patterns: `SxxEyy`, `NxNN`,
    `Season N`, `S01-S05`, `Complete`, daily `YYYY.MM.DD`, anime `[Group] Title - NN`, resolution, source.
  - File-list rules: video/audio/image/archive/exec/doc counts, sample and extra exclusion, (season, episode)
    set → `tv_kind` decision (classifier.md §4: 1 pair → `episode`; ≥ 2 episodes of one season → `season_pack`;
    several seasons → `multi_season`; all seasons + `Complete`-style token, no gaps → `complete_series`;
    gaps inside a season range → flag `incomplete`).
  - Flags: JAV-code shape, whole-token `xxx`, CJK, Cyrillic, URL/site prefix.
  - `tp_feature_text()` emits normalised tokens plus `__feat` tokens.
- `cm gen-header` generates `native/tparse/taxonomy.h` from `config/taxonomy.json` (do not hand-edit the header).
- Host CLI: `tparse < items.jsonl > features.jsonl` (used by the Python pipeline).
- CMake + ctest golden tests: ≥ 150 hand-written cases, including Scunthorpe traps (`Sussex`, `Analysis`,
  `Essex`) and every `tv_kind`.
- Build clean under `-Wall -Wextra -Werror`, ASan/UBSan, and a fuzz smoke test (1 M random names, no crash).

## Constraints

- Stay C11, libc only (train/serve parity: the training pipeline gets its features from this same C code via
  the CLI, never a Python re-implementation).
- This code is copied into `torrent_kotlin_app/app/src/main/cpp/dht_crawler-android` in Stage 5 — keep it
  self-contained (single directory, no generated-code deps besides `taxonomy.h`).

## Build / validate

- `cmake -S native/tparse -B build/tparse && cmake --build build/tparse && ctest --test-dir build/tparse`
- `uv run pytest` (parser golden tests live under `tests/` too, if wired there).
- Cross-compile check only (no run): NDK r27 at `$HOME/Android/Sdk/ndk/android-ndk-r27`, target
  `arm64-v8a` / android-24, e.g.
  `cmake -S native/tparse -B build/tparse-android -DCMAKE_TOOLCHAIN_FILE=$HOME/Android/Sdk/ndk/android-ndk-r27/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24`

## Exit criteria

1. Golden tests pass (≥ 150 cases, all `tv_kind`s, Scunthorpe traps).
2. ASan/UBSan clean; the 1 M random-name fuzz smoke run completes without crash.
3. The Stage 0 120k sample parses in < 10 s on the host (record the measured time).
4. Cross-compiles cleanly with NDK r27 for `arm64-v8a`/android-24 (compile only).
5. `-Wall -Wextra -Werror` build clean.

## Commit

`classifier-model: stage 1 — tparse release-name/file-list parser + golden tests (NDK-clean)`
