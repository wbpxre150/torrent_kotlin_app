# Stage 5: Schema and ingest-time classification in tag mode (crawler)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (the app; crawler-native work). Design: `plan/classifier.md` §7 (schema), §8 (ingest hook).
> Requires the test phone on USB. No LLM involved — normal subagent rules apply.

## Goal

Every new torrent on the phone gets a `torrent_class` row (tag mode; the existing porn filter stays untouched in this stage).

## Depends on

Stage 4 (tparse + ftc proven on arm64; `models/v1/{adult,category,tv_kind}.ftz` + `manifest.json` exist).

## Scope

1. Vendor `tparse` + `ftc` + fastText inference sources into `app/src/main/cpp/dht_crawler-android/` and wire
   them into the CMake build used by `buildDhtCrawler` (app/build.gradle.kts).
   - Do **not** edit `dht_crawler/lib/` (vendored; changes are wiped on rebuild).
   - Crawler source changes go through the vendored repo's commits or a new patch in `dht_crawler/patches/`,
     applied by `prepareDhtCrawlerSrc`.
   - Ship all three `.ftz` (`adult`, `category`, `tv_kind`) and the manifest as assets alongside the keyword files
     (`copyDhtCrawlerAssets`).
2. In the crawler:
   - Create the `torrent_class` and `classifier_meta` tables (classifier.md §7). This runs in both
     `database.c` and `batch_writer.c`.
   - Classify in `tree_metadata.c` next to `porn_filter_check`, carrying `classification_t` through
     `batch_writer_add`, and insert it in the same transaction.
   - `tv_kind` comes from tparse (`tp_result_t`); run the `tv_kind.ftz` model only when the parser can't decide
     (classifier.md §4). Each resident model costs ~19 MB RSS on the phone (stage 4 measurement), so load
     `tv_kind.ftz` lazily or at startup with the total RSS budget in mind.
   - Model load failure → parser-only classification plus a warning (no crash).
   - `/stats` gains classifier counters (by category, adult, mean µs/item).
3. Tag mode only in this stage: the existing `porn_filter_enabled` behaviour is unchanged and on its own toggle.

## Constraints (app invariants)

- Submodules: `git submodule update --init --recursive` inside `app/src/main/cpp/dht_crawler/` before building.
- `jniLibs/arm64-v8a/*.so` are checked in — after native changes, re-run the Gradle native build tasks so they
  are regenerated, and commit the refreshed binaries.
- Build env: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`, `ANDROID_HOME=$HOME/Android/Sdk`,
  `ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/android-ndk-r27`. Build: `./gradlew installDebug`.
- The crawler binaries are exec'd in place from `nativeLibraryDir` — never extract to a writable dir.

## Exit criteria (on the new phone over USB)

1. `./gradlew installDebug` succeeds.
2. After 30 min of crawling,
   `SELECT count(*) FROM torrents t LEFT JOIN torrent_class c ON c.torrent_id=t.id WHERE c.torrent_id IS NULL`
   = 0 for rows added after the install (check via `adb shell run-as com.torrent.app sqlite3 ...`).
3. `/stats` shows classifier counters.
4. Ingest rate stays within 5% of the pre-change rate (compare `/stats` hourly count).
5. No new crashes in logcat over the 30 min run.
6. `./gradlew :app:testDebugUnitTest` passes.

## Results (recorded 2026-10-15, via dev-workflow)

App commits: `839eb7d` (vendor tparse/ftc/fastText + CMake), `019e4be` (gitlink → crawler `c42736f`:
classifier module, torrent_class/classifier_meta schema in database.c **and** the batch_writer backup
mirror, same-transaction insert, /stats counters, config keys), `586df58` (models as assets + binary-safe
Kotlin staging + 3 fidelity tests), `26e7f65` (review fix-ups: stats-mutex on model_load_failures,
classifier_meta in the backup mirror, cmake comment).

1. **Build: PASS.** `./gradlew installDebug` on JDK 21; APK `app/build/outputs/apk/debug/app-debug.apk`.
2. **Zero unclassified new rows: PASS.** 35-min window on CPH2305; DB pulled to host (no on-device
   sqlite3): `SELECT count(*) FROM torrents t LEFT JOIN torrent_class c ON c.torrent_id=t.id WHERE
   c.torrent_id IS NULL AND t.added_timestamp >= <restart>` → **0** (5,921/5,921 new rows classified;
   940 adult-tagged, 1,616 tv_kind non-null).
3. **/stats classifier counters: PASS.** by_category, adult_tagged, mean µs/item, tv_kind_model_used,
   parser_only_fallback=0, model_load_failures=0. Note: live mean ≈ 5.6 ms/item (stage-4 standalone
   bench p50 was 134 µs) — counter includes cold-start model loads + worker contention; ingest rate
   unaffected, investigate only if it ever matters.
4. **Ingest rate: PASS (no regression).** ≈15,870/hr over the window vs 13,287/hr pre-change baseline
   (+19.5%, i.e. above the −5% bar; DHT rates fluctuate).
5. **No crashes: PASS.** 0 fatal signals/exceptions/tombstones in logcat over the window.
6. **Unit tests: PASS.** `:app:testDebugUnitTest` 539/539 (incl. 3 binary-staging fidelity tests +
   key-preservation test).

Deviations (accepted): `libdhtcrawler.so` NOT committed — repo policy (.gitignore:21-22; AGENTS.md
invariant 4 is stale for crawler/transmission binaries, only libftpserver.so is tracked); crawler
changes as vendored-repo commits (sanctioned) instead of patches; `calibration.json` not shipped
(manifest carries the thresholds); classifier keys are parsed by the crawler but not yet in the
Kotlin `CrawlerKeys` allow-list — stage 6 owns form exposure.

## Commit

`crawler: stage 5 — torrent_class schema + ingest-time tparse/ftc classification (tag mode)`
