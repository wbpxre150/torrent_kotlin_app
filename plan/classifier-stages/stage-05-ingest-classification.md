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

## Commit

`crawler: stage 5 — torrent_class schema + ingest-time tparse/ftc classification (tag mode)`
