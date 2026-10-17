# Stage 7: Crawler stats page — blue theme (drop the grey #666)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (Kotlin `ControlServer.kt` only — no C changes).
> Depends on stage 5 (the shared `WEBUI_CSS` theme is in place; `/stats` already interpolates it).
> Index: `plan/search-dashboard/README.md`.

## Goal

Restyle `/stats` (Crawler Stats) so it looks like the `/search` page using the shared theme: replace
the plain-white `.strip` summary, the `#666` grey key labels, and the white `.head`/`.body` sections
with the blue `.card` / `.badge` / `.meta` / `--muted` language. This is a presentation-only pass —
the stats feed, collapse/expand logic, every id, and `/stats/ws` + `location.search` stay unchanged.

## Context

After stage 5, `buildStatsPage()` emits `<style>$WEBUI_CSS …statistics-specific…</style>`. The
statistics-specific rules that survived still carry the legacy grey look:

- `.strip{display:flex;flex-wrap:wrap;…;background:#fff;border-radius:6px;…box-shadow:…}` — a white
  summary bar whose cells wrap onto multiple lines at phone width (~145px tall at 412px).
- `.strip b{…;color:#666;…}` — small uppercase grey key labels.
- `.row .k{color:#666}` (measured `rgb(102,102,102)`) — grey row keys.
- `.head` (section header) renders as a plain white bar; `.body` holds `.row .k/.v` key/value lines.
- `.banner.stop` / `.banner.start` colour the status banner.

There is **no horizontal overflow** on `/stats` (`scrollWidth == clientWidth == 412` at phone width)
— this stage is about colour/component language, not width. The sections/rows are generated client-side
by `childrenHtml` / `sectionNode` / `rowHtml` (the stats JS), with `openOverrides`/`defaultOpen` driving
collapse state; every value already goes through `esc()`.

The reference look is the search page: `--card:#fff` cards with 1px `#e3e7ee` borders and 12px radius,
`--muted:#5f6368` secondary text, `--text:#1f2937` primary text, `--chip:#e8f0fe` accents, `.badge`
pills, and the blue `--blue`/`--blue-dark` actions.

## Design

1. **Frame.** Align `/stats` to the same centred column as `/search` (`.wrap`, max-width 640px) instead
   of the current `body{max-width:820px;margin:0 auto;padding:0 12px}` override. Keep the page markup
   (`<h1>Crawler Stats</h1>`, the `a.cfg` nav, `#live`, `#banner`, `#tree`, the `.strip` cells) intact.
   If wrapping in `.wrap` would touch the JS-generated tree, it is acceptable to keep the body max-width
   but drop it to 640px; the requirement is a consistent column width with `/search`, not a specific
   class.

2. **Summary `.strip`.** Restyle the strip cells as themed stat cards: keep `.strip` as a flex/grid
   container (it already wraps), but give it the shared card language — e.g. the container transparent
   with `gap` only, and each cell a `.card`/`.badge`-style block with `--muted` uppercase key and
   `--text` value. Replace `.strip b{color:#666}` with `color:var(--muted)`.

3. **Sections (`.head` / `.body`).** Render each collapsible `<section>` (or at least its `.head` bar)
   as a themed card: white card background, rounded corners, a subtle border, with the ▾ triangle in a
   blue/`--muted` accent instead of default black. `.body` keeps its white card affinity. Replace
   `.row .k{color:#666}` with `color:var(--muted)`; keep `.row .v` on `--text`.

4. **Banners.** Keep `.banner.stop` / `.banner.start` but align their colours to the shared
   `.banner.red` / `.banner.amber` / `.banner.ok` semantics so "stopped/running" banners read the same
   as search-page banners.

5. **Do not change:** the stats feed path, `#tree` id, `#banner` id, `#live` id, every summary-strip
   id, `openOverrides` / `defaultOpen` / `isOpen` logic, the `sectionNode` / `rowHtml` / `childrenHtml`
   output shape, `/stats/ws` + `location.search`, or the `esc()` textContent escaping round-trip.

## Out of scope (explicit)

- `CrawlerProxyServer` and `/torrent?hash=…`; the vendored crawler C HTML.
- No route, WS-protocol, auth, JSON, or `/api/transmission/add` behaviour change.
- `crawlerStatsPayload`, `handleSearchFrame`, `buildCrawlerQuery` untouched.
- No C / submodule changes.

## Scope (files)

- `app/src/main/java/com/torrent/app/ControlServer.kt` — the statistics-specific `<style>` block in
  `buildStatsPage()` (the rules after `$WEBUI_CSS`). No JS or markup changes.
- `app/src/test/java/com/torrent/app/ControlServerStatsPageTest.kt` — extend (see Tests).

## Constraints

- No external assets (no CDN). No jQuery.
- Keep `buildStatsPage()` `internal`; keep it a pure companion function (already testable).
- Keep every id, WS string, and JS token intact; only the CSS/colour values change.
- `WEBUI_CSS` stays `internal val`; do not change any route/cookie/`Set-Cookie`/WS path.
- No C / submodule changes; do not touch `CrawlerProxyServer`.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

## Tests

Extend `ControlServerStatsPageTest` (keep every existing assertion — the strip ids, `/stats/ws` +
`location.search`, reconnect/backoff strings, `openOverrides`/`data-path`/`defaultOpen`, tree/partition
titles, no external assets). Add assertions that the page:

- contains the shared theme marker `--blue:#1a73e8`;
- contains `var(--muted)` (or the `--muted:#5f6368` token);
- no longer contains the legacy grey `#666` (the old `.strip b` / `.row .k` colour).

## Exit criteria (implementer) — all must pass

1. `./gradlew assembleDebug` and `./gradlew :app:testDebugUnitTest` pass (incl. the extended stats test).
2. Read-only spot-check of the generated `/stats` string: it references `$WEBUI_CSS`, uses `--blue` and
   `--muted`, and no `#666` grey survives (full visual check is on the phone).
3. Every existing id and JS/stats string is unchanged: `#tree`, `#banner`, `#live`, `#stats/ws` +
   `location.search`, `openOverrides`/`defaultOpen`.
4. Diff review: only CSS/colour changes; the stats JS and the route/WS/JSON code are untouched; no
   external assets; no C/submodule/`CrawlerProxyServer` change.
5. On-device (final, on the phone over USB after `installDebug`): `/stats` shows the summary strip and
   collapsible sections in the blue card theme, sections still expand/collapse, and the live feed still
   updates.

## Commit

```text
app: webui stage 7 — crawler stats page blue theme (drop grey #666)
```