# Stage 1: Configurable metadata fetch timeout — `tree_metadata_timeout_ms` (default 60 s)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repos: `~/apps/torrent_kotlin_app`** (app-side Kotlin/assets + gitlink) **and the vendored
> crawler repo at `app/src/main/cpp/dht_crawler/`** (its own `.git`, HEAD `077bada`).
> Requires the test phone on USB. Index: `plan/metadata-fix/README.md`.

## Goal

Replace the hardcoded 30 s metadata fetch deadline with a config key `tree_metadata_timeout_ms`,
**default 60000 ms** (~60 s — parity with libtorrent's `request_timeout` default, the setting that
bounds a re-request from another peer), clamped to `[5000, 600000]`. Nothing else changes in this
stage: the fetch state machine, window, and blast behavior stay exactly as they are. Stages 2–3
build on the new tree field.

## Depends on

- Nothing (first stage of this plan). **Before starting, record the on-device baseline**: crawl for
  ≥ 30 min and save `/stats` `metadata_success_rate_percent` — every later stage and the final
  report compare against it.

## Scope — vendored crawler repo (`app/src/main/cpp/dht_crawler/`)

Plumb the key along the same path every other `tree_*` Stage-5 key takes
(`README.md` "Config plumbing chain"):

1. `include/config.h` (~`:128`, next to `tree_parallel_peers`): new field
   `int tree_metadata_timeout_ms;`.
2. `src/config.c` defaults (~`:115`, after `tree_metadata_fresh_peer_retries`):
   `config->tree_metadata_timeout_ms = 60000;` and a parser entry near the
   `tree_parallel_peers` case (~`:403`): key `tree_metadata_timeout_ms`,
   `atoi`, clamp `5000–600000`.
3. `src/main.c` `supervisor_config_t` init (~`:678-680`, "Stage 5 settings"):
   `.metadata_timeout_ms = config.tree_metadata_timeout_ms`.
4. `include/supervisor.h`: `metadata_timeout_ms` in `supervisor_config_t` (~`:78-79`) and in the
   tree-spawn config struct (`supervisor.c:481-483` initializes it from `sup->…`); `src/supervisor.c`:
   copy in `supervisor_create` (~`:106-107`) with a `> 0 ? : 60000` fallback, and pass it into
   `tree_config_t` (~`:481-483`).
5. `include/thread_tree.h`: `metadata_timeout_ms` in `tree_config_t` (~`:82-84`) and in
   `thread_tree_t` (~`:148-150`); `src/thread_tree.c` init (~`:1021`):
   `tree->metadata_timeout_ms = config->metadata_timeout_ms > 0 ? config->metadata_timeout_ms : 60000;`.
6. `src/tree_metadata.c`:
   - `DEFAULT_METADATA_TIMEOUT_MS` (`:51`) → `60000` (NULL-config fallback consistency).
   - Worker fetch config (`:1252-1258`): replace the hardcoded `.metadata_timeout_ms = 30000` with
     `tree->metadata_timeout_ms > 0 ? tree->metadata_timeout_ms : DEFAULT_METADATA_TIMEOUT_MS`.
7. No `/stats` changes, no new counters in this stage.

## Scope — parent repo (`~/apps/torrent_kotlin_app`)

1. `app/src/main/java/com/torrent/app/config/CrawlerKeys.kt`: add `"tree_metadata_timeout_ms"` to
   `KNOWN_KEYS` (Thread trees group, `:49-51`). NOT form-managed — advanced textarea only, like the
   other `tree_*` keys (invariant: the settings form rejects unknown keys).
2. `app/src/main/assets/dht_crawler/config.ini.default`: add
   `tree_metadata_timeout_ms = 60000` with a one-line comment (~`:120`, next to
   `tree_tcp_connect_timeout_ms`).
3. Tests: extend the `KNOWN_KEYS` assertions in `app/src/test/java/com/torrent/app/config/CrawlerKeysTest.kt`
   and add a `parseAdvanced` acceptance case in `CrawlerConfigTest.kt` (key round-trips through the
   advanced textarea; unknown-key rejection still holds).

## Constraints

- Global constraints from `plan/metadata-fix/README.md` apply (JDK 21 env, submodules first, never
  edit `lib/`, two commits, credentials in `config.ini`).
- Do not touch fetch behavior: no scheduler, window, or request changes — those are Stages 2–3.
- `tree_parallel_peers` semantics and the `MAX_PARALLEL_PEERS` cap stay untouched.
- Existing installs keep stored prefs: new defaults reach fresh installs only; existing installs
  pick the key up via one apply through the settings form (write-through on apply) — same caveat as
  `plan/optimise/` stage 1.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
git submodule update --init --recursive   # inside app/src/main/cpp/dht_crawler/
./gradlew :app:testDebugUnitTest
./gradlew installDebug
```

## Exit criteria (on the phone over USB)

1. `./gradlew :app:testDebugUnitTest` passes; `./gradlew installDebug` succeeds.
2. `adb shell run-as com.torrent.app cat files/crawler/config.ini` shows
   `tree_metadata_timeout_ms = 60000` after a fresh config apply.
3. **The key drives the deadline**: set it to `5000` via the advanced textarea (apply), crawl
   10 min, observe `metadata_success_rate_percent` drop sharply vs. the recorded baseline (most
   real fetches need > 5 s), then restore `60000` and confirm the rate recovers toward baseline.
4. No regression: over a ≥ 30 min crawl at `60000`, `metadata_success_rate_percent` is **not lower**
   than the pre-stage baseline; `fresh_peer_rescues_total` still moves; no crashes/tombstones in
   logcat; force-stop exits promptly.

## Commits

Vendored crawler repo:

```
crawler: metadata-fix stage 1 — configurable metadata fetch timeout (tree_metadata_timeout_ms, default 60s)
```

Parent repo (gitlink + Kotlin + asset, together):

```
app: metadata-fix stage 1 — expose tree_metadata_timeout_ms (advanced key) + crawler gitlink
```