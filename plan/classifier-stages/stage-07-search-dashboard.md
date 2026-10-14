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
