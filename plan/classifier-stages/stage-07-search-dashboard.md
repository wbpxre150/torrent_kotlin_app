# Stage 7: Search API and dashboard

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (crawler HTTP API + Kotlin dashboard). Design: `plan/classifier.md` §7 (schema/search), §2 (current endpoints).
> Requires the test phone on USB. No LLM involved — normal subagent rules apply.

## Goal

The new search experience: category / TV-kind / adult filters on the crawler API, surfaced in the dashboard.

## Depends on

Stage 5 (`torrent_class` populated at ingest on the test phone; partial index `idx_class_cat` exists).

## Scope

1. Crawler `/search` gains `category`, `tv_kind`, `adult=hide|show|only`, and `min_conf`. FTS `MATCH` runs
   first, then the rowid join, with the partial index `idx_class_cat`.
2. New browse endpoint with no query, e.g. `/browse?category=tv&tv_kind=season_pack&sort=peers`.
3. `/random-tv`, `/random-movies` and `/random-music` switch to `torrent_class` lookups (falling back to the
   old extension-`LIKE` heuristics for unclassified rows).
4. `CrawlerProxyServer` passes the new params through (it strips auth when forwarding; do not break that).
   Dashboard filter chips: TV → episodes / season packs / complete series, adult toggle.

## Constraints (app invariants)

- The crawler HTTP API is civetweb in the vendored `dht_crawler` — changes go through `dht_crawler/patches/`,
  applied by `prepareDhtCrawlerSrc` (fixed patch order: bencode-cap-fix → bencode-init-fix → civetweb-stop-flag-atomic → new patches).
- Don't rely on FTS5 `UNINDEXED` columns for filtering (they can't be matched and aren't indexed for `WHERE`).
- The Kotlin `CrawlerProxyServer` strips `control_token` and never forwards `Authorization` — keep that.
- Search UI is the crawler's own dashboard HTML (served by the crawler, proxied by Kotlin); token auth via
  `resolveAuthToken` (cookie > `?token=` > `Authorization`), constant-time checks — don't weaken it.
- Build env: JDK 21 + SDK/NDK r27; build `./gradlew installDebug`.

## Exit criteria (on the phone)

1. Each filter returns only matching rows (spot-check 20 results per filter via curl through the proxy).
2. `adult=hide` returns 0 rows with `adult=1`.
3. p95 latency of `/search` with filters ≤ 1.5× unfiltered on the phone DB (measure with curl timing).
4. `EXPLAIN QUERY PLAN` (via `adb shell run-as com.torrent.app sqlite3 ...`) shows FTS + index use, with no
   full `torrent_class` scan.
5. `./gradlew :app:testDebugUnitTest` passes.

## Commit

`crawler: stage 7 — torrent_class search/browse filters + dashboard chips`

## Results (recorded 2026-10-16, via dev-workflow)

Commits: submodule `19bdae8` (search/browse/random filters), `2cdddb0` (dashboard chips),
`0f89a2e` + `2d1eea1` (review fix-ups: quoted-path stack overflow, stmt leaks, browse sort
chips, HTML escaping, sb_append); parent gitlink bumps `ce13575`, `6dc8880`, `52e6692`,
`a224627`, Kotlin passthrough tests `a0e4cae`. Review: PASS-WITH-NOTES, both fix stages applied
and re-reviewed.

1. **Filters match: PASS.** 50/50 rows per filter (category=tv, category=movie,
   tv_kind=season_pack, adult=only, min_conf=0.9) via curl through the proxy. complete_series:
   5/5 match — only 5 such rows exist in the DB (data-limited, all verified).
2. **adult=hide: PASS.** 100-row sweep (q=1080&category=tv): 0 rows with adult=1.
3. **Latency: PASS.** p95 filtered/unfiltered = 0.94× (tv+season_pack+hide+min_conf=0.5) and
   0.81× (movie+min_conf=0.9); 30 samples each through the proxy.
4. **EQP: PASS.** On the pulled phone DB (no on-device sqlite3 CLI — stage-5 precedent):
   filtered /search shows `SEARCH c USING COVERING INDEX idx_class_cat (category=? AND tv_kind=?
   AND torrent_id=?)` + FTS virtual-table scan; /browse drives from the same index. No
   `SCAN torrent_class` anywhere.
5. **Unit tests: PASS.** `:app:testDebugUnitTest` --rerun-tasks: 559 tests, 0 failures
   (includes 4 new CrawlerProxyServer passthrough tests).
6. **/browse: PASS.** season_pack sorted by peers desc; movies sort=added verified desc.
7. **/random-*: tv/movies PASS** (200, ~50 cards each). **music: 200 with empty page** —
   data-limited, not a code fault: only 13 classified music rows exist, none meet the sampler's
   `total_peers=50`; the 2 peers=50 extension-music torrents are classified `audiobook`; zero
   unclassified rows exist for the LIKE fallback. Class-first behavior is as designed.
8. **Dashboard: PASS.** TV episodes/season packs/complete series chips + adult select (default
   hide) present; filters and sort survive pagination on the results page.

Notes: plain `/search?q=x` (no filter params) keeps exact legacy behavior (adult rows visible);
   `adult=hide` becomes the default only when one of the four filter params is present. An
   unrelated operator commit (`8cb4410` throttle) landed mid-workflow and coexists cleanly.
