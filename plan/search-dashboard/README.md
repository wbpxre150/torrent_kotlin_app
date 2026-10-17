# Search dashboard redesign — stage index

Per-stage implementation packets live here, one file per stage, implemented **one at a time**
via `/dev <stage file>` (dev-workflow). This document is the parent's design + on-phone exit criteria.

> **Target repo: `~/apps/torrent_kotlin_app`** (Kotlin `ControlServer` + the `dht_crawler` submodule).
> Test phone on USB required for the final verification only.

## Goal

Replace the crawler's own C-generated HTML search dashboard (served by the crawler and proxied
through `CrawlerProxyServer` on the proxy port) with a phone-first, blue-themed dashboard served
directly by the Kotlin `ControlServer` (`http://<phone-ip>:8090/search`). The page loads instantly
and fetches search/browse/random results over a WebSocket that mirrors the existing `/stats` →
`/stats/ws` pattern: the browser speaks to Kotlin, Kotlin fetches the crawler's **JSON** endpoints
on loopback (`127.0.0.1:8081`) and pushes the results back over the socket. A loading spinner
shows while a request is in flight — the page never blocks.

User decisions (locked):

- **Categories:** full taxonomy grid — all 12 categories as chips; TV sub-kind (episode / multi_episode /
  season_pack / multi_season / complete_series / daily) is a secondary selector shown when `tv` or
  `anime` is active.
- **Add to Transmission:** keep the per-result ➕ button, same-origin on `:8090`.
- **Old C HTML:** retire it (root/search/browse/random HTML builders and the `/random-tv|-movies|-music`
  handlers). Keep the JSON endpoints, `/stats`, `/refresh`, and the `/torrent` + `/torrent/files` detail pages.
- **Results:** infinite scroll — 50 results per batch, appended as you scroll.

## Design

### Category taxonomy (frozen by the data)

`category` (12): `unknown`, `movie`, `tv`, `anime`, `music`, `audiobook`, `ebook`, `comic`,
`software`, `game`, `image`, `other`.
`tv_kind` (7): `none` (stored NULL), `episode`, `multi_episode`, `season_pack`, `multi_season`,
`complete_series`, `daily`.

The crawler's `/search` and `/browse` already accept `category`, `tv_kind`, `adult=hide|show|only`,
`min_conf` and (browse only) `sort=peers|added|size`. The new `/random` endpoint (stage 1) adds
`category`, `tv_kind`, `adult`, `min_peers`.

### Crawler JSON endpoints (loopback `127.0.0.1:<crawlerHttpPort>`, default 8081)

| Endpoint | Params | Returns |
|---|---|---|
| `/search` | `q`, `category`, `tv_kind`, `adult`, `min_conf`, `page` | `{count,total_count,page,limit,has_more,timestamp,results:[…]}` (50/page) |
| `/browse` | `category`, `tv_kind`, `adult`, `min_conf`, `sort`, `page` | same envelope |
| `/random` *(new)* | `category`, `tv_kind`, `adult`, `min_peers` (default 10 = "≥10") | same envelope, up to 50, `total_count=count` |
| `/stats`, `/refresh?hash=…`, `/torrent?hash=…`, `/torrent/files?hash=…` | — | unchanged |

Each result object: `info_hash`, `name`, `size`, `size_bytes`, `total_peers`, `added`,
`num_files`, `category`, `tv_kind`, `adult`, `confidence`, `files:[{path,size,size_bytes}…]`.

### Kotlin surface (`:8090`)

- `GET /search` — the static dashboard page (HTML/CSS/JS inline; no external assets).
- `WS  /search/ws` — request/response over the socket (see protocol below). Auth same as `/stats/ws`.
- `POST /api/transmission/add?hash=…` — same-origin add-to-Transmission (reuses `CrawlerAddHandler`).

### WebSocket protocol (client → server JSON text frames)

```jsonc
{ "id": 1, "type": "search", "q": "house", "category": "music", "tv_kind": null,
  "adult": "hide", "sort": null, "page": 1 }
{ "id": 2, "type": "browse", "category": "tv", "tv_kind": "season_pack",
  "adult": "hide", "sort": "peers", "page": 1 }
{ "id": 3, "type": "random", "category": "music", "tv_kind": null, "adult": "hide" }
```

Server replies (one per request, matched by `id`):

```jsonc
{ "id": 1, "ok": true,  "payload": { /* crawler JSON envelope verbatim */ } }
{ "id": 1, "ok": false, "error": "bad_request: …" | "unavailable" | "db_error" }
```

Kotlin validates every incoming field itself (category/tv_kind/adult/sort enumerated, `page` int ≥ 1,
`min_peers` int); the crawler is only ever called with validated, URL-encoded values. `q` is the only
free-text field and is `URLEncoder`-encoded. The crawler is fetched with short timeouts; a down/crawler
startup answers `{ok:false,error:"unavailable"}` so the UI renders a clear status instead of freezing.

## Stage order

| Stage | File | Repo | Effort | Notes |
|---|---|---|---|---|
| 1 | `stage-01-crawler-random-endpoint.md` | crawler submodule | S–M | additive `/random` JSON |
| 2 | `stage-02-kotlin-search-backend.md` | app (Kotlin) | M | loopback client + WS + add route + wiring; minimal `/search` page |
| 3 | `stage-03-kotlin-search-ui.md` | app (Kotlin) | L | full blue phone UI (replaces the minimal page) |
| 4 | `stage-04-crawler-retire-html.md` | crawler submodule | M | delete C HTML builders + old `/random-*` |
| 5 | `stage-05-webui-unified-theme.md` | app (Kotlin) | M | unify all `:8090` pages on the search blue theme + visible adult label on `/search` |

Ordering keeps a working search path at every commit: the old proxied dashboard stays alive through
stage 3 and is removed only in stage 4, after the Kotlin UI exists.

## Build / install

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
./gradlew installDebug          # full build incl. native cross-compiles
./gradlew :app:testDebugUnitTest # unit tests
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The `dht_crawler` is a **git submodule** (`app/src/main/cpp/dht_crawler`, upstream
`wbpxre150/dht_crawler` with local commits). Stages 1 and 4 must commit inside the submodule, then
bump the gitlink in the parent repo. Do not touch `dht_crawler/lib/` or the 3 build patches
(`bencode-cap-fix`, `bencode-init-fix`, `civetweb-stop-flag-atomic`).

## Exit criteria (verify on the phone, all must pass)

1. **Instant page.** `curl -s -o /dev/null -w '%{time_total}' "http://<phone-ip>:8090/search?token=<t>"` < 1 s;
   the body is the static HTML shell (search box + category grid present), no server-side blocking.
2. **Browse filtering.** Through the proxy/loopback forward, `/browse?category=music&adult=hide`
   returns only `"category":"music"` rows; `/browse?category=tv&tv_kind=season_pack&adult=hide`
   returns only `"category":"tv"` + `"tv_kind":"season_pack"`.
3. **Search inside a category.** In the UI, select *Music*, type a query, hit search: rendered
   results are all music and match the query (spot-check names).
4. **Random.** Select *Music*, hit Randomize: up to 50 results, every card `total_peers >= 10`
   ("at least 10") and category `music`. Same spot-check for *TV → Season packs*.
5. **Add to Transmission.** On the search page tap ➕: a JSON outcome (ok:true, or a mapped error)
   is returned by `POST /api/transmission/add?hash=…` on `:8090` — no 404, no CORS failure.
6. **Old HTML gone.** Proxy `GET /` and `/search?format=html` no longer render the C dashboard
   (404 or JSON); `/torrent?hash=…` still renders the per-torrent detail page.
7. **Loading state.** While a request is in flight the results area shows a spinner, and a
   "connecting…/unavailable" status appears when the crawler is down (verifiable by stopping the
   crawler daemon from the dashboard and re-running a search).
8. **Survives restart.** After `adb shell am force-stop com.torrent.app` + relaunch, `/search`
   still loads and returns results.
9. **Tests.** `./gradlew :app:testDebugUnitTest` passes (incl. any new ControlServer/WS tests).