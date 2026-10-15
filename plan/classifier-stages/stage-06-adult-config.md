# Stage 6: Configurable adult handling and hard-block list

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (crawler C + Kotlin). Design: `plan/classifier.md` §9.
> Requires the test phone on USB. No LLM involved — normal subagent rules apply.

## Goal

`adult_mode=tag|drop`, calibrated thresholds, and a hard-block list.

## Depends on

Stage 5 (ingest-time classification in place) and Stage 3 (calibrated thresholds in `models/v1/manifest.json`).

## Scope

1. Crawler config keys: `classifier_enabled`, `adult_mode`, `adult_hide_threshold`,
   `adult_drop_threshold`, `hard_block_file`. Defaults are `tag` and the calibrated values from the manifest.
2. The old keyword filter becomes a fallback, used when the models are missing.
3. Kotlin side:
   - Add the keys to `CrawlerKeys` (the allow-list mirrored from crawler `config.c`).
   - Validation lives in `ConfigValidator`.
   - Settings UI in the dashboard and wizard.
   - Unit tests following house style (pure classes, constructor injection, Robolectric only where
     `Context` is needed).

## Constraints (app invariants)

- Crawler settings form is allow-listed by `CrawlerKeys` — path/backup/http_* keys are rejected; the new keys
  must be mirrored from crawler `config.c`.
- Crawler config writes go through `CrawlerConfig.kt` (atomic writes, pinned keys).
- Apply-watch invariant: the baseline `restartCount` must be taken from the launch the apply actually started,
  not pre-`start()` (see `CrawlerApplyWatch` KDoc) — otherwise every apply gets rolled back.
- Config keys in `config.ini.default` (sanitized template) only; never copy values from the vendored live
  `dht_crawler/config.ini`.
- Build env: JDK 21 + Android SDK/NDK r27 (see AGENTS.md); build `./gradlew installDebug`.

## Results (recorded 2026-10-15, via dev-workflow)

App commits: `3f926b3` (gitlink → crawler `a0e0500`: adult_mode tag|drop + thresholds + hard_block.c/h/txt,
classifier threshold/counter API, supervisor/tree plumbing, tree_metadata ingest hook, /stats counters,
keyword-filter demotion to models-missing fallback), `b757c77` (CrawlerKeys allow-list incl. classifier keys,
CrawlerSettings fields, ConfigValidator adultMode/fraction, ConfigStore persistence, ControlServer form
field-set, wizard switch + DaemonService setCrawlerAdultMode, asset staging of hard_block.txt,
config.ini.default section, 16 new tests).

1. **Unit tests: PASS.** `:app:testDebugUnitTest --rerun`: 555/555 (was 539).
2. **Build + install: PASS.** assembleDebug green; `adb install -r` Success on CPH2305 (USB).
3. **Dashboard apply: PASS.** `/settings/crawler` renders the Adult content handling field-set
   (classifierEnabled, adultMode select, both thresholds); switching to drop applies cleanly — device
   config.ini shows `adult_mode = drop`, crawler restarts (apply-watch OK, no rollback).
4. **Drop mode: PASS** (verified via /stats per operator): `adult_dropped` grows (21→47 and 29 in 90s
   observed in short windows); `adult_tagged` retains a small residual band `[0.917039, 0.995516)` by
   design (drop threshold stricter — reviewer-accepted P2). No 15-min DB pull (4.7 GB): operator
   accepted stats-based verification.
5. **Tag resume: PASS.** Switching back: `adult_tagged` 18→209 over 5 min, `adult_dropped` = 0.
6. **Hard-block both modes: PASS.** Test entry `x264` (device file only): tag-mode `hard_block_dropped`
   6→96 over 5 min; drop-mode 23 in ~90s; after removing the entry the rate collapsed to the shipped
   list's background (1 in 808) with ingest continuing.
7. **No regressions: PASS.** Crash buffer clean, no new tombstones (latest 07:39, pre-change), no
   FATAL/signal lines since install; ingest throughout (~1.3k classified / 5 min).

Deviations (accepted): wireless ADB dropouts twice (user restart once); `hard_block.txt` matches the
torrent NAME only (file-list scan deferred); `adult_hide_threshold = 0` remains the crawler's
"use manifest" sentinel, unreachable from the validated form; keyword porn filter now runs only when
models are missing (classifier owns adult tagging) — `porn_filter_enabled` keeps its key but its
semantics are fallback-only.

## Exit criteria (on the phone)

1. Switching to `drop` via the dashboard → crawler restarts (apply watch OK) → adult rows stop being inserted
   (adult count stays flat over 15 min while total grows).
2. Switching back to `tag` resumes inserting them.
3. A hard-block test entry is always dropped in both modes.
4. `./gradlew :app:testDebugUnitTest` passes.

## Commit

`crawler: stage 6 — adult_mode tag|drop, thresholds, hard-block list, settings UI`
