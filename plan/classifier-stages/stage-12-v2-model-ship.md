# Stage 12: Ship classifier models/v2 into the app (staging + threshold migration)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repos: `~/apps/classifier-model` (step 0 only: promote + export, one commit) then
> `~/apps/torrent_kotlin_app`** (Kotlin + Gradle + assets; no crawler C changes).
> Requires the test phone on USB (fresh-install and upgrade-install paths are both exit criteria).
> No LLM involved — normal subagent rules apply.

## Context (v2 model — the fold-all feedback retrain)

- **The v2 students to ship live in `~/apps/classifier-model/data/feedback_all/models/`**:
  `adult.ftz`, `category.ftz`, `tv_kind.ftz` (+ `calibration.json`; **no `manifest.json` — step 0
  creates it**). These are the fold-all retrain on the full feedback corpus (157,825 teacher labels
  from all `cm feedback` rounds, seed 43, heldout 15,582) — they supersede the round-010 export
  currently sitting in `models/v2/`.
  - sha256: `adult e8af1398ac5061185e8db1a8a8b1a93f7511be7028d9372a97cd9de835ec2ae9`,
    `category adbffb5522318cb336d2f2632b92d398375b2d03da525715580e7eb3b650133c`,
    `tv_kind e47aa2333379eaf274806370e4df2eb061f59e17084b645b8480c58725bd7f60`
    (verified to match `calibration.json` 2026-10-18).
  - Heldout: adult precision **0.9702** / recall **0.6123** at hide (v1: 0.9710/0.4937), category
    accuracy **0.8152** (v1: 0.7905), tv_kind accuracy **0.9566** (v1: 0.9413) — clears every
    criteria bar incl. the restated 0.79 category bar.
  - Recalibrated thresholds: `adult_hide_threshold = 0.897417`, `adult_drop_threshold = 0.993899`
    (v1 shipped 0.917039 / 0.995516 — stale under v2 probabilities).
- The crawler's `classifier.c` takes the manifest `version` string's digits as `clf_ver` →
  manifest must say `"v2"` → rows land at `clf_ver = 2` and the stage-8 backfill re-classifies them.
- **Feature parity is already proven**: the app's vendored `dht_crawler-android/tparse/` and `ftc/`
  are byte-identical to classifier-model at `34fdbd4`, and HEAD `03bdc1a` (the feedback-all commit)
  touches only Python/scripts — so the parity holds for the fold-all students too. The only tparse
  change since v1 training was label-name lookups in `taxonomy.h`, which do not alter `ft` feature
  strings. **No crawler C changes and no vendored-repo commits are needed for v2.**

## Goal

Load v2 on the phone for both fresh installs and upgrades over existing v1 installs, so that
(a) ingest classifies at `clf_ver=2` and (b) the always-on backfill job (stage 8) sees every
existing row as pending and re-classifies it — the full backfill itself is verified in stage 13.

## Depends on

Stage 11 (fold-all v2 trained). Stages 5/6/8 (ingest classification, adult config, backfill engine)
are already in the shipped crawler.

## Facts you must respect (measured/verified, do not re-derive)

- **Manifest version comes from the dir name:** `cm export` writes `"version": models_dir.name`
  (`src/classifier_model/export.py:360`). Exporting from `data/feedback_all/models` would emit
  `"version": "models"` → `clf_ver=0`. The students must be promoted into a directory named
  `v2` before running export.
- **Restage bug (must fix here):** `CrawlerConfig.ensureAssets()` skips copying when the
  `.assets_version` stamp equals `versionCode` — and `versionCode` is **statically 1**
  (`app/build.gradle.kts:15`). An upgrade install over the real phone therefore never stages v2.
- **Threshold precedence:** the crawler resolves thresholds as **config.ini > manifest > default**
  (`classifier.c`). `config.ini` is only rewritten by apply jobs (`runCrawlerApply`,
  `setCrawlerAdultMode`) — the daemon-startup path calls `ensureLayout()` only. Existing installs
  hold v1 thresholds in SharedPreferences (`crawler_adult_hide_threshold` etc. + LKG copies) and
  in the on-device `config.ini`; without a migration the phone runs v2 models with v1 thresholds.
- Staging/paths today: `CrawlerConfig.MODELS_SUBDIR = "models/v1"` (drives the asset source path,
  the staged dir, and the `PINNED_KEYS` `classifier_model_dir` entry); `MODEL_FILES` is the fixed
  4-name list (`adult.ftz`, `category.ftz`, `tv_kind.ftz`, `manifest.json`); Gradle
  `copyDhtCrawlerAssets` includes `v1/**` from `src/main/cpp/dht_crawler-android/models`.
- Phone backfill throughput (stage 8, this DB): ~1,480–1,902 rows/s, 7.9k rows ≈ 7 s.

## Scope

0. **Promote + export the manifest (classifier-model repo, one commit — do this first):**
   - Copy `data/feedback_all/models/{adult,category,tv_kind}.ftz` + `calibration.json` into
     `models/v2/`, **replacing** the superseded round-010 export (that provenance goes in the
     commit message).
   - Run `cm export` from the promoted dir so the manifest carries `"version": "v2"`:
     `uv run cm export --models-dir models/v2 --calibration models/v2/calibration.json
     --labels data/feedback_all/corpus/labels.jsonl --features data/feedback_all/corpus/features.jsonl
     --seed 43 --out models/v2/manifest.json` (check `cm export --help` for the exact flag set;
     the required outcome is a manifest with `"version": "v2"`, the three sha256s above, and
     thresholds 0.897417 / 0.993899).
   - Verify: `models/v2/manifest.json` sha256s == the on-disk `.ftz` files, `version` == `"v2"`.
   - Commit: `classifier-model: promote fold-all v2 students to models/v2 + manifest`.
1. **Ship the model files (app repo):** copy the four files from `~/apps/classifier-model/models/v2/`
   into `app/src/main/cpp/dht_crawler-android/models/v2/` (binary-exact; the v1 dir layout is the
   precedent — do NOT add `calibration.json`, the crawler reads only `manifest.json`). Keep
   `models/v1/` in the repo for history, but ship **only v2 in the APK**: change the Gradle
   include to `v2/**` (net APK size ~0: −26.6 MB v1, +26.6 MB v2).
2. **Point the app at v2:** `CrawlerConfig.MODELS_SUBDIR = "models/v2"` (the pinned
   `classifier_model_dir` key follows automatically); update `config.ini.default` values and
   comments (`classifier_model_dir=models/v2`, `adult_hide_threshold=0.897417`,
   `adult_drop_threshold=0.993899`) and the KDoc/comment references to `models/v1`.
3. **Fix the restage trigger:** make the `.assets_version` stamp content include the model dir
   (e.g. `"<versionCode>|<MODELS_SUBDIR>"`) so a model-dir change forces restaging even when
   `versionCode` is unchanged. On stamp change, also **delete the stale `workDir/models/v1`
   directory** (nothing reads it once `classifier_model_dir` flips; ~26 MB reclaimed; never delete
   `models/v2`).
4. **Threshold migration (the load-bearing piece):** new persisted setting
   `classifier_model_version` (ConfigStore). At daemon startup, after `ensureLayout()`
   (DaemonService is the composition point — invariant 12), if the stored value ≠ `MODELS_SUBDIR`:
   - overwrite `adultHideThreshold`/`adultDropThreshold` prefs (and their LKG copies) with the new
     defaults (0.897417 / 0.993899), logging the previous values;
   - store `classifier_model_version = MODELS_SUBDIR`;
   - re-render `config.ini` via `applySettings(load().crawler)` **before the crawler starts**, so
     v2 never runs a single second with v1 thresholds.
   Policy decision (made; alternative noted): reset **unconditionally**, including user-customized
   values — any threshold tuned against v1 probabilities is invalid under v2's distribution, and
   the safety-critical failure (under-hiding adult) is on the stale side. The wizard/Settings form
   can re-customize afterwards.
   House style: extract the decision as a pure class with constructor-injected store (plain
   JUnit4-testable), no Android deps beyond what ConfigStore already has.
5. **Defaults:** `AppConfig.CRAWLER` defaults → 0.897417 / 0.993899 (drives wizard, ConfigStore
   fallbacks, and the migration's reset values).
6. **Tests:** update `CrawlerConfigTest` (MODELS_SUBDIR assertions, staging/stamp behavior incl.
   the v1→v2 restamp + v1-dir deletion, thresholds in rendered ini), `ConfigStoreTest` (new
   defaults + migration round-trip), leave `CrawlerKeysTest` allow-list untouched
   (`classifier_model_dir` stays app-owned/pinned). Grep the repo for remaining `models/v1` and
   `0.917039`/`0.995516` references and update each (crawler `config.c` default "models/v1" stays —
   the pinned key always overrides it; note it in the commit message).

## Constraints (app invariants)

- JDK 21 + NDK r27 env; build `./gradlew installDebug`; tests `./gradlew :app:testDebugUnitTest`
  (note: needs `JAVA_TOOL_OPTIONS=-Djava.tmpdir…` workaround if /tmp quota is still exhausted).
- No vendored-source edits (none are required — tparse/ftc parity verified above).
- Models are binary: stage via the existing streamed `copyBinaryAtomically` path only.
- New wiring lives in `DaemonService`; lifecycle ops never block the main thread.
- `classifier_model_dir` stays in `PINNED_KEYS` (CrawlerKeys allow-list precedent, stage 6).

## Exit criteria (with verification)

1. `models/v2/manifest.json` (classifier-model repo) says `"version": "v2"` with the three
   sha256s above; `sha256sum` of the four files copied into
   `app/src/main/cpp/dht_crawler-android/models/v2/` matches it (the app copies are
   byte-identical to the promoted export).
2. **Fresh install** (wiped app data): `workDir/models/v2/` staged (4 files, sha-verified on-device
   via `run-as`); `config.ini` shows `classifier_model_dir = models/v2`, hide 0.897417, drop
   0.993899; crawler log shows all three models loaded with manifest sha match; `/stats`
   `classifier.available=true`, `model_load_failures=0`.
3. **Upgrade install over the real phone (the v1 data phone)**: v2 restaged despite unchanged
   `versionCode` (stamp scheme worked); `workDir/models/v1` gone; prefs migrated (old values in
   logcat); `config.ini` carries v2 thresholds — verified via `run-as … cat` of the prefs file and
   `config.ini` before any user interaction.
4. New ingest rows land at `torrent_class.clf_ver = 2` (pull the DB or query the crawler's
   `/random-sample?clf_ver=2`).
5. The backfill job arms for the old rows: `/stats` `classifier.backfill` reports `clf_ver=2` with
   `total > 0` (all v1 rows pending) — completion is stage 13's criterion, not this one.
6. APK contents: no `models/v1` assets (e.g. `unzip -l` on the built APK), `models/v2` present.
7. `./gradlew :app:testDebugUnitTest` passes.

## Commit

Two commits, one per repo:
- `classifier-model: promote fold-all v2 students to models/v2 + manifest`
- `app: stage 12 — ship classifier models/v2 (asset staging, restamp fix, threshold migration)`

## Results

(record after the stage run)
