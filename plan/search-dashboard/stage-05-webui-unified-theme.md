# Stage 5: WebUI — unify on the search blue theme + label the adult control

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (parent repo, Kotlin `ControlServer.kt` only — no C changes).
> Depends on stages 1–4 (the blue `/search` dashboard and the C-HTML retirement are already done).
> Index: `plan/search-dashboard/README.md`.

## Goal

Apply the **same blue phone theme** that the `/search` dashboard already uses to the *entire*
`ControlServer` web UI on `:8090` — the dashboard (`/`), the crawler stats page (`/stats`), and all
settings panels (`/settings/transmission`, `/settings/ftp`, `/settings/storage`,
`/settings/crawler`), plus the login and setup-gate pages — so every page feels like one app rather
than a mix of a blue phone dashboard and gray desktop tables. Also fix the `/search` adult control:
it currently shows three bare chips `Hide / Show / Only` with **no visible "Adult" label**, so it is
unclear what those buttons do.

The remaining `torrent?hash=…` per-torrent detail page is **out of scope** — the user will replace it
later.

## Context

`ControlServer.kt` builds every HTML page of the `:8090` web UI as inline Kotlin raw strings. Current
styling is split three ways:

| Page | Builder | Current style |
|---|---|---|
| `/search` | `buildSearchPage()` | `SEARCH_CSS` — the blue phone theme (this is the canonical theme) |
| `/stats` | `buildStatsPage()` | `panelCss` + a gray statistics-specific `<style>` block |
| `/` | `buildDashboard()` | a gray inline `<style>` block (tables, `.cfg` links, `S`/`X`/`R` daemon buttons) |
| settings panels | `buildTransmissionPanel()` / `buildFtpPanel()` / `buildStoragePanel()` / `buildCrawlerPanel()` | `panelCss` (+ small crawler-specific rules) |
| `/login` | `buildLoginPage()` | a minimal gray inline `<style>` |
| setup gate | `buildSetupGate()` | a minimal gray inline `<style>` |

The canonical theme is the search page's palette and component language:

- **Tokens** (`SEARCH_CSS` `:root`): `--blue:#1a73e8`, `--blue-dark:#1557b0`, `--chip:#e8f0fe`,
  `--text:#1f2937`, `--muted:#5f6368`, `--card:#fff`.
- **Page frame**: `body` gradient `linear-gradient(180deg,#eef4fd,#f5f7fa)`, a centred `.wrap`
  column, `system-ui` font stack, `16px` base, safe-area padding, `max-width: 640px`.
- **Components**: `.btn` (blue pill, `.secondary` light-blue), `.chip` (selectable pill with
  `aria-pressed`), `.card`, `.banner` (`.red`/`.amber`), `.badge`, `.toast`, `.meta`, a `.live`
  status dot, and a sticky control/header area.

The other pages keep their *behaviour* (routes, auth, forms, WS feeds, and the stats/backfill/maintenance
logic) — this stage is a presentation-only pass plus the adult-label fix.

## Design

### 1. Extract one shared theme, then have every page use it

Introduce a single shared stylesheet (recommended name: `private val WEBUI_CSS`) that is the
**single source of truth** for the blue theme, and refactor `SEARCH_CSS` to consume it so search and
the rest of the UI can never drift apart.

`WEBUI_CSS` must contain at least:

- the `:root` tokens above (keep the exact values — do not invent new shades);
- `* { box-sizing:border-box; -webkit-tap-highlight-color:transparent }`;
- `html,body` reset + `body` gradient/font/colour;
- `.wrap { max-width:640px; margin:0 auto; padding:…safe-area… }` for form/login/setup pages;
  expose a `.wrap.wide { max-width:860px }` (or an equivalent responsive width) for the dashboard and
  stats page, whose tables need more room than the search column;
- shared `header`/`nav` with the same link styling + `.live` status dot used by `/search`;
- `.btn` / `.btn.secondary` (≥44px touch targets, `touch-action:manipulation`, same radii/colours);
- `.chip` + `.chip[aria-pressed="true"]`;
- `.card`, `.banner` (`red`/`amber`, plus `.ok` where a success banner is used), `.badge`, `.toast`, `.meta`;
- a shared `.state` pill for the RUNNING/STARTING/STOPPING/STOPPED statuses (reuse the current
  dashboard `.state` colours but restyle them as rounded pills consistent with the theme);
- form controls: `label`, `input`, `select`, `textarea`, `fieldset`/`legend`, `.row` checkboxes —
  fold in what `panelCss` has today, restyled to the theme (same spacing so no functional change);
- `table`, `th`, `td`, `pre`/`code`, and a `.loglines` style for the log tails.

Refactor direction (lower-risk than rewriting `SEARCH_CSS`):

1. Add `WEBUI_CSS` as a `private val` (a `trimIndent()` expression like `SEARCH_CSS`, so it is a
   `val`, not `const val`).
2. Change `SEARCH_CSS` to be `$WEBUI_CSS` + a search-only block (`.control-bar`, `.search-row`,
   `#search-input`, `#sort`, `#category-grid`, `#tv-kind-row`, `.seg*`, `#results*`, `.filelist`,
   `.peers.*`, `.detail`, `#load-more`, `#sentinel`, `#spinner`/@keyframes, `.actions`/`.add-btn`).
   Keep the page HTML and `SEARCH_JS` structurally intact — the search tests pin element ids and JS
   strings, not the CSS, so this is safe as long as no id/JS token changes.
3. Replace `panelCss` with `$WEBUI_CSS` and delete the now-duplicated rules (keep any
   crawler-panel-specific rules — `.warn-num`, `#apply-status`, `.apply-log`, `#maint-*` — in
   `buildCrawlerPanel`'s own `<style>` block, layered on top of the shared theme).
4. `buildStatsPage()`: replace `<style>$panelCss …</style>` with `<style>$WEBUI_CSS …statistics-specific…</style>`
   (`.strip`, `.head`/`.body` collapsible sections, `.row .k/.v`). Keep the summary strip and the
   collapse/open-overrides markup + JS untouched.
5. `buildDashboard()`: replace its inline `<style>` with `$WEBUI_CSS` + a small dashboard-specific
   block (daemon table, `.tx` downloads table, `.actions`, `.cfg` nav links). Add the same
   `header`/`nav` (Dashboard / Crawler stats / Search) used by `/search`.
6. `buildLoginPage()` and `buildSetupGate()`: use `$WEBUI_CSS` (their small `.box`/`.err` blocks can
   stay page-local).

Make `WEBUI_CSS` `internal` (companion object) so it is unit-testable as a source string.

### 2. Fix the `/search` adult control (visible label)

Today the adult control is three bare chips with only an `aria-label` (invisible to sighted users):

```html
<div id="adult-control" class="seg" role="group" aria-label="Adult results">
  <button class="chip" type="button" data-adult="hide" aria-pressed="true">Hide</button>
  <button class="chip" type="button" data-adult="show" aria-pressed="false">Show</button>
  <button class="chip" type="button" data-adult="only" aria-pressed="false">Only</button>
</div>
```

Change it to give the group a **visible** label, preserving the `id="adult-control"` (the search test
pins it) and the existing `data-adult`/`aria-pressed` contract (the JS pins it):

```html
<div class="seg-row">
  <span class="seg-label">Adult</span>
  <div id="adult-control" class="seg" role="group" aria-label="Adult results">
    <button class="chip" type="button" data-adult="hide" aria-pressed="true">Hide</button>
    <button class="chip" type="button" data-adult="show" aria-pressed="false">Show</button>
    <button class="chip" type="button" data-adult="only" aria-pressed="false">Only</button>
  </div>
</div>
```

Add `.seg-row{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:0 0 12px}` and
`.seg-label{color:var(--muted);font-size:.9rem;font-weight:600}` to the search-only CSS.

### 3. (Dashboard passthrough) label the daemon control buttons

The dashboard's daemon table uses cryptic `S` / `X` / `R` submit buttons — the same "unlabeled button"
problem the user flagged. While restyling the dashboard, replace them with explicit labels:

```html
<form method="post" action="/daemon/$escapedLabel/start"><button>Start</button></form>
<form method="post" action="/daemon/$escapedLabel/stop"><button>Stop</button></form>
<form method="post" action="/daemon/$escapedLabel/restart"><button>Restart</button></form>
```

styled as small `.btn` pills. This is presentation-only: the form `action`s and the redirect behaviour
are unchanged.

## Out of scope (explicit)

- **Everything served through `CrawlerProxyServer`, including the per-torrent detail page
  `/torrent?hash=…`** and the proxy's own `unauthorizedPage`/`badGatewayPage`. The user will replace
  the detail page later — do not touch it.
- The vendored crawler C HTML (already retired except detail pages).
- No route, WS-protocol, auth, form, JSON, or add-handler behaviour changes.
- No changes to `/api/transmission/add`, `buildCrawlerQuery`, `handleSearchFrame`, or
  `crawlerStatsPayload`.

## Scope (files)

- `app/src/main/java/com/torrent/app/ControlServer.kt` — add `WEBUI_CSS`; refactor `SEARCH_CSS`
  to use it; apply it to `buildStatsPage`, `buildDashboard`, the four settings panels,
  `buildLoginPage`, `buildSetupGate`; add the visible adult label; label the dashboard daemon buttons.
- `app/src/test/java/com/torrent/app/ControlServerSearchPageTest.kt` — add an assertion for the
  visible adult label.
- `app/src/test/java/com/torrent/app/ControlServerStatsPageTest.kt` — add an assertion that the
  stats page now carries the shared theme.
- Add `app/src/test/java/com/torrent/app/ControlServerWebuiThemeTest.kt` — pin the shared theme
  tokens and their use across the two testable pages (see Build / validate).

## Constraints

- No external assets (no CDN fonts/JS/CSS); everything inline. No jQuery.
- All torrent/user-controlled text keeps flowing through `textContent` (search cards) or the existing
  `esc()` (server-side Kotlin) — never introduce `innerHTML` with crawler data.
- `location.search` must remain on the WS URLs so `?token=` auth keeps working on first visit.
- Keep `id="adult-control"`, `id="tv-kind-row"`, every `data-*` attribute, and every `SEARCH_JS`
  string intact — the existing search/stats tests are pinning strings, not semantics.
- Do not change any route path, query param, cookie/`Set-Cookie` header, or the
  `private val`-vs-`const val` nature of the CSS constants.
- No C / submodule changes.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

Add/extend these pure string tests:

- `ControlServerWebuiThemeTest`:
  - `WEBUI_CSS` contains the canonical tokens: `--blue:#1a73e8`, `--blue-dark:#1557b0`,
    `--chip:#e8f0fe`, `--text:#1f2937`, `--muted:#5f6368`, `--card:#fff`, and the body gradient
    `linear-gradient(180deg,#eef4fd,#f5f7fa)`.
  - `buildSearchPage()` and `buildStatsPage()` both reference the shared CSS and the `--blue` token.
  - No external assets in either page (no `<script src`, `<link `, `cdn.`).
- `ControlServerSearchPageTest`: assert the visible adult label — the page contains
  `class="seg-label"` and the text `Adult`.
- `ControlServerStatsPageTest`: assert the page contains the shared theme marker (e.g. the
  `--blue:#1a73e8` token or a shared class such as `.wrap`/`.btn`), while preserving every existing
  assertion unchanged.

## Exit criteria (implementer) — all must pass

1. `./gradlew assembleDebug` and `./gradlew :app:testDebugUnitTest` pass (including the new/extended
   tests above).
2. `/stats`, `/`, `/settings/transmission`, `/settings/ftp`, `/settings/storage`,
   `/settings/crawler`, `/login`, and the setup gate all render with the blue theme — same palette,
   gradient background, card/chip/button language and header nav as `/search`. (Read-only spot-check of
   the generated strings is sufficient at the implementer stage; full visual check is on the phone.)
3. `/search` still renders its blue theme and now shows a visible **Adult** label beside the
   Hide/Show/Only chips; `id="adult-control"`, `data-adult`, and `aria-pressed` are unchanged (the
   interaction logic is untouched).
4. Dashboard daemon control buttons read Start / Stop / Restart (no more `S`/`X`/`R`).
5. Diff review: no `innerHTML` with crawler content, no external asset URLs, no route/WS/auth
   behaviour change, no C/submodule changes, and no change to `CrawlerProxyServer` (the
   `/torrent?hash=…` page is untouched).
6. On-device (final, on the phone over USB after `installDebug`): open `/`, `/stats`, `/search`, one
   settings panel, and `/login` — all share the blue theme; on `/search` the adult control shows the
   visible "Adult" label and browsing still works; `/torrent?hash=…` via the proxy is unchanged.

## Commit

```text
app: webui stage 5 — unify all ControlServer pages on the search blue theme, add visible adult label on /search
```