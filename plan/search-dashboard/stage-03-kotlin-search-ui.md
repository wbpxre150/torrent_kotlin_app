# Stage 3: Kotlin search UI — blue phone dashboard

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (parent repo, Kotlin only — no C changes).
> Depends on stage 2 (`/search/ws`, `/search` stub, add route exist). Index: `plan/search-dashboard/README.md`.

## Goal

Replace the stage-2 stub page with a beautiful, phone-first, blue-themed search dashboard served at
`/search` on `:8090`. It talks to `/search/ws` using the protocol from the README, shows a **loading
spinner** while any request is in flight (never freezes the page), offers the full 12-category grid +
TV sub-kind selector, a single **Randomize** button, and infinite-scroll results with an add-to-Transmission
button per card.

## Context

- WS protocol and crawler JSON result shape are in `plan/search-dashboard/README.md` and stage 2.
- The page is built as an inline Kotlin string (`ControlServer.buildSearchPageStub()` is replaced by
  `buildSearchPage()`). All CSS/JS inline, no external assets/fonts/CDNs (offline-friendly, like `/stats`).
- The page must carry the auth token the same way `/stats` does: open `ws(s)://host/search/ws` + `location.search`.
- `POST /api/transmission/add?hash=…` is already same-origin (stage 2) — the ➕ button calls it via `fetch`.

## UI spec (phone-first, blue theme)

Palette: primary blue `#1a73e8`, hover `#1557b0`, light chip `#e8f0fe`, text-on-light `#1f2937`,
muted `#5f6368`, card background `#fff`, page background a very light blue-grey gradient
(`linear-gradient(180deg,#eef4fd,#f5f7fa)`). `max-width: 640px` centred; touch targets ≥ 44px;
`viewport` meta already required; safe-area padding.

Layout, top to bottom:

1. **Header** — "Search" title + small nav links (Dashboard / Crawler stats). A live status dot
   (green `live` / amber `connecting…` / red `unavailable`) driven by the WS open/close/message state.
2. **Control bar** (sticky at top on phones) — a search input (autofocus on desktop, not on phone to
   avoid keyboard pop), a blue **Search** button (magnifier), and a distinct **Randomize** (🎲) button.
   Sort selector (Peers / Newest / Largest) applies to the no-query browse view only.
3. **Category grid** — a wrap grid of 12 chips: All, Movie, TV, Anime, Music, Audiobook, Ebook, Comic,
   Software, Game, Image, Other. Exactly one is selected (radio-like, `aria-pressed`). Selecting a chip
   clears current results and triggers an immediate **browse** (empty-query, sorted) so the category is
   instantly useful.
4. **TV sub-kind row** — visible only when `TV` or `Anime` is selected: None, Episodes, Multi-episode,
   Season packs, Multi-season, Complete series, Daily. Optional selection; also re-browses on change.
5. **Adult mode** — a small segmented control: Hide (default) / Show / Only.
6. **Results area** — a status line ("N results", "sorted by peers", etc.) then the cards.
7. **Infinite scroll** — a bottom sentinel observed with `IntersectionObserver` (fallback: a "Load more"
   button if the observer is missing). When the sentinel is ≥ 150px from the viewport bottom, `has_more`
   is true, and no request is in flight, send the next `page` and **append**.

Card content (one per result, `textContent`-built to avoid any HTML injection from torrent names):

- **Name** (bold) — first line; on tap it expands an inline **file list** (up to the 10 files from JSON,
  each `path` + size, plus "… and N more"). No separate detail navigation required from the card.
- **Meta chips/line** — `category` badge, `tv_kind` badge (when present), size (`size`), `total_peers`
  peers (colour-coded: >100 green, >10 amber, else grey), age relative to now, confidence (when present).
- **➕ Add** button (blue) — `fetch('/api/transmission/add?hash=' + hash, {method:'POST'})`; show a
  friendly inline toast mapped from the JSON (reuse the outcome→message mapping already in the crawler's
  old JS: ok/duplicate/insufficient_space/not_found/rpc_failed/no_storage/db_unavailable/bad_hash).
- **"…" detail link** optional: a small link to the per-torrent page on the proxy (stage 4 keeps
  `/torrent`); the ControlServer injects the proxy origin (`http://<wifiIp>:<proxyPort>`) into the page.
  If the proxy is unavailable the link still renders — it just 502s server-side.

Behaviour:

- **Spinner:** every request sets the status area/spinner visible before sending and clears it on the
  reply (success or error). A request in flight does not block the rest of the page (new category clicks
  may supersede the current request — cancel-by-ignoring by bumping the `id`; latest reply wins via an
  `activeId` check, older replies are dropped).
- **Randomize:** sends `{type:"random", category, tv_kind, adult}` with the current selections; replaces
  results (no paging); count cap 50 is the server's default.
- **Search:** Enter or Search button sends `{type:"search", q, category, tv_kind, adult, page:1}`;
  empty query on a category sends `browse`.
- **Reconnect:** WS retry with exponential backoff (like `/stats`) and a "connecting…/unavailable"
  banner; on reconnect, no automatic re-query (the user re-taps) except re-marking the connection live.
- **Error states:** `bad_request` → red banner with the field; `unavailable` → amber "crawler is
  starting/stopped" banner; `db_error` → red banner. No `alert()` dialogs — inline banners/toasts.

## Scope (files)

- `ControlServer.kt`: replace `buildSearchPageStub()` with `buildSearchPage()`; inject the proxy origin
  (use the existing `wifiIp()` and `loadConfig().crawler.proxyPort`). Keep `buildSearchPage()` readable —
  extract CSS/JS into `private const val` properties, not one mega-string of inline literals.
- No changes to the WS contract, `/api/transmission/add`, or the crawler.

## Constraints

- No external assets (no CDN fonts/JS/CSS); everything inline. No jQuery.
- All torrent/user-controlled text is written via `textContent` (never `innerHTML`) after JSON parse.
- `location.search` must be preserved on the WS URL so `?token=` auth keeps working on first visit.
- Phone: `-webkit-tap-highlight-color` disabled on chips/buttons, `touch-action: manipulation`.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

Add a small page-structure unit test (model it on the existing `ControlServerStatsPageTest`-style
tests): assert the page HTML contains the 12 category names, a `/search/ws` endpoint reference, a
spinner element, and the Randomize control.

## Exit criteria (implementer) — all must pass

1. `./gradlew assembleDebug` and `./gradlew :app:testDebugUnitTest` pass (incl. the new page test).
2. `/search` page HTML contains: 12 category chips, the TV sub-kind row, the adult segmented control,
   the Randomize button, and references `/search/ws` + `location.search` for the token.
3. Diff review: no `innerHTML` with crawler content, no external asset URLs, no `/stats` regression,
   no C/submodule changes.

## Commit

`git add -A && git commit -m "app: search-dashboard stage 3 — blue phone search UI (category grid, infinite scroll, add button)"`