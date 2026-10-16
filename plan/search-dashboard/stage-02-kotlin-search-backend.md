# Stage 2: Kotlin search backend — loopback JSON client, `/search/ws`, add route

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (parent repo, Kotlin only — no C changes).
> Depends on stage 1 (`/random` exists in the crawler). Index: `plan/search-dashboard/README.md`.

## Goal

Give the Kotlin `ControlServer` (:8090) the plumbing for the new search dashboard: a loopback client
that fetches the crawler's JSON endpoints, a request/response WebSocket `/search/ws` that proxies
search/browse/random *queries* to those JSON endpoints (exactly the `/stats` → `/stats/ws` pattern),
and a same-origin `POST /api/transmission/add` route. Ship a **minimal** `/search` page now — it only
proves the WebSocket round-trip (a search box + JSON dump); stage 3 replaces it with the real UI.

## Context (already in the code — do not re-derive)

- `ControlServer.kt` already has `crawlerHttpPort: () -> Int`, `fetchCrawlerStats(port)` (loopback GET
  `/stats`), the `/stats` page + `webSocket("stats/ws")` (auth re-check, `resolveAuthToken`,
  `send(Frame.Text(...))`, `Frame.Text`, close with `CloseReason.Codes.VIOLATED_POLICY`), and helper
  `esc()`, `jsonStr`-style `buildString` JSON building, `urlEnc()`.
- `CrawlerProxyServer.kt` already implements `POST /api/transmission/add?hash=…` for the proxy: resolve
  auth, then `CrawlerAddHandler.add(hash)` → `outcome.toJson()` + `HttpStatusCode.fromValue(outcome.httpStatus)`.
  **Copy that shape onto the ControlServer**, it must not depend on the proxy being up.
- `CrawlerAddHandler` (`CrawlerAddHandler.kt`) is stateless: `(configStore, gateway: () -> SafStorageGateway?,
  crawlerDbFile: () -> File)`. Today it is constructed inside `DaemonService.syncCrawlerProxy()`.
- `DaemonService.onCreate` constructs `ControlServer(...)` **once** (line ~290), before `syncCrawlerProxy`
  runs in `onStartCommand`. `gateway` is a `@Volatile` field read dynamically.
- Ktor 3.5.0 (CIO) + `io.ktor:ktor-server-websockets`; `org.json.JSONObject/JSONArray` is available
  (used by `TransmissionRpcClient`). No kotlinx.serialization.

## Scope

### A. ControlServer loopback JSON client

Add one generic helper in `ControlServer`:

- `private fun fetchCrawlerJson(pathAndQuery: String): String?` — `GET http://127.0.0.1:${crawlerHttpPort()}$pathAndQuery`
  (connect 2000 ms, read **15000 ms**), return the raw body on 200 else null. Refactor
  `fetchCrawlerStats` to delegate to it (`fetchCrawlerJson("/stats")`) so there is one code path.
  Keep `crawlerStatsPayload()` behaviour unchanged.

### B. `/search/ws` request/response WebSocket

Inside `route("/")` (so the existing auth interceptor still gates the upgrade — mirror `/stats/ws`):

- Add `webSocket("search/ws") { … }` with the same auth re-check before doing anything else
  (close `VIOLATED_POLICY` on bad/missing token).
- Receive text frames; parse each as `JSONObject`. Protocol (see README):
  - `{ "id": N, "type": "search"|"browse"|"random", "q"?, "category"?, "tv_kind"?, "adult"?, "sort"?, "page"? }`
  - Validate strictly, build the loopback path **only** from enumerated/parsed values, URL-encode `q`:
    - `category` ∈ the 12 taxonomy names (or absent); `tv_kind` ∈ the 7 names (or absent);
      `adult` ∈ hide|show|only (default hide); `sort` ∈ peers|added|size (browse only);
      `page` int ≥ 1 (default 1; random ignores page); `min_peers` not exposed to clients yet (baked default 10 = "≥10").
    - `search` ⇒ `/search?q=<enc>&category=..&tv_kind=..&adult=..&page=..` (omit absent params).
    - `browse` ⇒ `/browse?category=..&tv_kind=..&adult=..&sort=<sort|peers>&page=..` (browse has no `q`).
    - `random` ⇒ `/random?category=..&tv_kind=..&adult=..&min_peers=10`.
  - Invalid input ⇒ reply `{"id":N,"ok":false,"error":"bad_request: <field>"}`.
  - Fetch `fetchCrawlerJson(...)`; null ⇒ `{"id":N,"ok":false,"error":"unavailable"}`; malformed JSON ⇒
    `{"id":N,"ok":false,"error":"db_error"}`; else `{"id":N,"ok":true,"payload":<raw crawler JSON verbatim>}`.
  - On `CancellationException` rethrow; on socket gone break (same as `/stats/ws`).
- Gate the whole handler so an unknown/empty frame is ignored (or answered `bad_request`), never throws.
- Add a small pure helper for the URL building + a pure validator (e.g.
  `internal fun buildCrawlerQuery(type, q, category, tvKind, adult, sort, page): String?` + validation)
  that returns `null` on invalid input, so it is **unit-testable without a server**.

### C. `POST /api/transmission/add` on the ControlServer

- Add constructor param `private val addHandler: CrawlerAddHandler? = null` to `ControlServer`.
- Inside `route("/")` (after the auth interceptor) add
  `post("api/transmission/add") { … }` replicating `CrawlerProxyServer`'s handler: null handler ⇒
  503 `{"ok":false,"error":"db_unavailable"}`, else `addHandler.add(hash).let { call.respondText(it.toJson(), Application.Json, HttpStatusCode.fromValue(it.httpStatus)) }`.
- Do **not** weaken auth: this route sits behind the same token interceptor.

### D. Minimal `/search` page

- Add `get("search") { call.respondText(buildSearchPageStub(), ContentType.Text.Html) }` inside `route("/")`.
- `buildSearchPageStub()` returns a tiny page: a search input, a "Music" quick category + "search"/
  "random" test buttons, opens `ws(s)://host/search/ws` (+ `location.search` for the token, exactly like
  the stats page), sends the typed command, and dumps the reply JSON into a `<pre>`. This is throwaway —
  keep it < ~120 lines. It exists so the implementer and reviewer can prove the WS works end to end.

### E. DaemonService wiring

- Hoist the add handler so the same instance can serve both servers:
  - Add a field `@Volatile private var crawlerAddHandler: CrawlerAddHandler? = null`.
  - Construct it once in `onCreate` **before** `controlServer = ControlServer(...)`:
    `crawlerAddHandler = CrawlerAddHandler(store = configStore, gateway = { gateway }, crawlerDbFile = { File(filesDir, "crawler/data/torrents.db") })`.
  - Pass `addHandler = crawlerAddHandler` into `ControlServer(...)`.
  - In `syncCrawlerProxy(...)` replace the local `val addHandler = CrawlerAddHandler(...)` with the hoisted
    field (guard `?: return` so a proxy start before onCreate-init cannot NPE — it cannot, but stay safe).
- Add a "Search" link on the dashboard (`buildDashboard()`, next to the "Crawler Stats" link) pointing at `/search`.

## Constraints

- No C changes; do not touch the submodule.
- Follow house JSON style (`buildString`/`jsonStr` or `JSONObject`) — choose one and be consistent.
- Never interpolate crawler output into HTML unescaped; the stub page dumps JSON into a `<pre>` via
  `textContent` (not `innerHTML`).
- The WS handler must not leak an unhandled exception on a half-open socket — reuse `/stats/ws`'s
  cancellation/break pattern.
- `resolveAuthToken` is the only auth path; do not add a second mechanism.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

Add focused unit tests (pure JUnit4, Robolectric only if needed) for the query builder + validator:
valid combinations produce the expected encoded URLs; invalid category/tv_kind/adult/sort/page are
rejected; `q` is percent-encoded (spaces, `&`, `+`, unicode).

## Exit criteria (implementer) — all must pass

1. `./gradlew assembleDebug` and `./gradlew :app:testDebugUnitTest` pass with the new tests.
2. `ControlServer` now exposes `/search` (stub), `/search/ws`, and `POST /api/transmission/add` (JSON,
   no dependency on `CrawlerProxyServer`); `fetchCrawlerStats` delegates to `fetchCrawlerJson`.
3. `DaemonService` constructs the add handler once and shares it with both servers; the dashboard has
   a working "Search" link.
4. Diff review: no auth weakening, no C changes, no changes to `/stats` or `/stats/ws` behaviour.

## Commit

Single parent commit:
`git add -A && git commit -m "app: search-dashboard stage 2 — crawler JSON client + /search/ws + same-origin add route"`