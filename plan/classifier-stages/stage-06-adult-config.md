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

## Exit criteria (on the phone)

1. Switching to `drop` via the dashboard → crawler restarts (apply watch OK) → adult rows stop being inserted
   (adult count stays flat over 15 min while total grows).
2. Switching back to `tag` resumes inserting them.
3. A hard-block test entry is always dropped in both modes.
4. `./gradlew :app:testDebugUnitTest` passes.

## Commit

`crawler: stage 6 — adult_mode tag|drop, thresholds, hard-block list, settings UI`
