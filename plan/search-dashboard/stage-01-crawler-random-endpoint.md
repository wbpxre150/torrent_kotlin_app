# Stage 1: Crawler `/random` JSON endpoint

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`**, submodule `app/src/main/cpp/dht_crawler`.
> Index/design: `plan/search-dashboard/README.md`.

## Goal

Add a general-purpose JSON `/random` endpoint to the crawler's CivetWeb API. It samples up to 50
random torrents above a configurable peer floor (`min_peers`, default 10 = "at least 10 peers"),
optionally filtered by `category` / `tv_kind` / `adult`, and returns the **same JSON envelope** as
`/search` and `/browse` (`send_json_results`). This is additive: the existing `/random-tv`,
`/random-movies` and `/random-music` handlers stay working until stage 4 removes them.

## Context (already in the code — do not re-derive)

- `http_api.c` has stage-7 class filtering: `cls_filter_t` / `parse_class_filter()` /
  `class_filter_needs_join()` / `append_class_conditions()` / `bind_class_filter()`.
- Category names ↔ ints live in `classifier.c` `classifier_category_name()` and are mirrored in
  `http_api.c` (`cls_category_t`); `tv_kind` names in `CLS_TV_KIND_NAMES` + `class_tv_kind_from_name()`.
- `send_json_results()` serialises a `search_result_t[]` into
  `{count,total_count,page,limit,has_more,timestamp,results:[…]}`; `limit` is `HTTP_API_MAX_RESULTS` (50).
- The existing random samplers (`get_random_video_torrents_filtered`, `get_random_music_torrents`)
  pick a random `id` in `[1, MAX(id)]` and re-query `… WHERE id = ? AND total_peers = 50`, with a
  `RANDOM_CLASS_SQL` probe (`SELECT category, adult FROM torrent_class WHERE torrent_id = ?`) and
  `random_candidate_eligible()` for class-first + legacy-heuristic eligibility.
- Validation helpers: `parse_long_param()` / `parse_double_param()` / `send_json_error()`.

## Scope

In `app/src/main/cpp/dht_crawler/src/http_api.c` **only**:

1. Add a class probe for the class-first path that also returns `tv_kind`, e.g.
   `#define RANDOM_CLASS_SQL_EX "SELECT category, tv_kind, adult FROM torrent_class WHERE torrent_id = ?"`
   (tv_kind is NULL for rows with none — handle `SQLITE_NULL`).
2. Add `random_candidate_eligible_ex(db, stmt, torrent_id, name, want_category, want_tv_kind, adult_mode)`:
   - `want_category == -1` ⇒ any category; `want_tv_kind == -1` ⇒ any (incl. NULL), `0`/`tp_tv_kind none`
     ⇒ require NULL, `>0` ⇒ equality.
   - A torrent **with** a class row is judged by that row alone:
     `adult` handling per `adult_mode` (hide ⇒ `adult==0`, only ⇒ `adult==1`, show ⇒ no condition),
     then category/tv_kind equality. No class row ⇒ **ineligible** whenever the caller passes a real
     filter (category/tv_kind or adult=hide/only); the legacy extension/title heuristics may only be
     used when `adult_mode == show && want_category == -1 && want_tv_kind == -1`. Keep it simple and
     favour correctness over legacy coverage — the phone DB is fully classified.
3. Add a generic sampler `get_random_torrents(db, filter, min_peers, results, &count)`:
   - Reuse the existing random-id + `MAX(id)` technique, `srand` seeding, 100-result-array cap
     (we only ever return 50), dedup by `info_hash`, and a 50000-attempt safety limit.
   - The fetch SQL is `SELECT id, info_hash, name, size_bytes, total_peers, added_timestamp FROM
     torrents WHERE id = ? AND total_peers >= ?` (bind `min_peers`, **not** `= 50`).
   - Populate `num_files` + up to 10 file rows exactly like the existing samplers
     (`fetch_result_files()` already provides the files for a `search_result_t` — prefer it).
   - Stop at `HTTP_API_MAX_RESULTS` (50) found.
4. Add `random_handler`:
   - Parse `category` (optional name via `class_category_from_name`), `tv_kind` (optional name via
     `class_tv_kind_from_name`), `adult` (`hide|show|only`, default `hide`), `min_peers`
     (`parse_long_param`, range 0..100000, default 10; the SQL is `total_peers >= min_peers`).
   - Malformed value ⇒ `send_json_error(400, "Invalid value for '…'")`.
   - Fill a `cls_filter_t` (set `has_category`/`has_tv_kind`/`adult` as parsed) and derive
     `want_category` (-1 when absent), `want_tv_kind` (-1 when absent), `adult_mode`.
   - Call the sampler; on SQL error ⇒ `send_json_error(500, "Database query failed")`.
   - On success answer `send_json_results(conn, results, count, count, 1)` (so `total_count=count`,
     `has_more=false`, `page=1`, `limit=50`).
5. Register it: `mg_set_request_handler(api->mg_ctx, "/random", random_handler, api);` in
   `http_api_start()` next to the other handlers. **Leave** the three `/random-*` registrations in place.

Do **not** touch `root_handler`, `generate_search_results_html`, `/search`, `/browse`, `/stats`,
`/refresh`, `/torrent*`, or anything outside `http_api.c`.

## Constraints

- The crawler is a git **submodule**. Commit the change inside it, then bump the parent gitlink
  (see Commits below). Do not edit `dht_crawler/lib/`, and do not touch the 3 build patches.
- The crawler must still compile for its own host `make` (no `CLF_ENABLED`) — do not gate the new
  code behind the classifier build.
- Do not weaken auth (this endpoint is loopback-only today, reached via the Kotlin proxy/ControlServer;
  there is no direct LAN exposure unless `lanDirect` is set, which is an existing operator choice).
- Read all DB access under the existing `database_read_lock`/`database_read_unlock`.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
# 1) host compile of the crawler (fast; the new code is not CLF-gated):
git -C app/src/main/cpp/dht_crawler clean -n   # ensure no stray files
(cd app/src/main/cpp/dht_crawler && make -j"$(nproc)")
# 2) full Android cross-compile:
./gradlew assembleDebug
# 3) unit tests still green:
./gradlew :app:testDebugUnitTest
```

If host `make` needs deps not present, that's a blocker to report — do not skip straight to the NDK build.

## Exit criteria (implementer) — all must pass

1. `make` (host crawler build) and `./gradlew assembleDebug` both compile cleanly.
2. `./gradlew :app:testDebugUnitTest` passes.
3. Code review of the diff (`git -C app/src/main/cpp/dht_crawler diff`): `/random` is additive;
   `/search`, `/browse`, `/stats`, `/refresh`, `/torrent*` and the three `/random-*` handlers are
   byte-for-byte untouched (except the one new registration line).

## Commits

1. Inside the submodule:
   `git -C app/src/main/cpp/dht_crawler add src/http_api.c && git -C app/src/main/cpp/dht_crawler commit -m "crawler: add /random JSON endpoint (category/tv_kind/adult/min_peers)"`
2. Bump the parent gitlink:
   `git add app/src/main/cpp/dht_crawler && git commit -m "app: search-dashboard stage 1 — crawler gitlink (/random endpoint)"`