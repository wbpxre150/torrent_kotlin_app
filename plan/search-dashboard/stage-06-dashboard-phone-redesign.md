# Stage 6: Dashboard — phone redesign (card daemons, horizontal controls, no horizontal scroll)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (Kotlin `ControlServer.kt` only — no C changes).
> Depends on stage 5 (the shared `WEBUI_CSS` theme is already in place and every page interpolates it).
> Index: `plan/search-dashboard/README.md`.

## Goal

Redesign the `/` dashboard so it reads like the `/search` page: replace the fixed 6-column daemon
`<table>` (which overflows a phone viewport and wraps its content vertically) with one card per
daemon built from the shared `.card` / `.state` / `.btn` / `.meta` components; put Start/Stop/Restart
on a single horizontal row; restyle the downloads table and the service/config sections to match; and
remove the `.wrap wide` (860px) width so the page fits a phone with **no horizontal scrolling**.

## Context

After stage 5, `/` interpolates `$WEBUI_CSS` plus a dashboard-specific `<style>` block (daemon/`tx`
tables, `.actions`, `a.cfg`, a red `.warn` override). The page still wraps in `<div class="wrap wide">`
(max-width 860px) and renders the daemons as a 6-column `<table>` (Name / State / PID / Uptime /
Restarts / Control). Measured at a 412px viewport: `scrollWidth ≈ 471 > clientWidth = 412` (the user
must zoom out to see everything), and the narrow Control cell makes the three buttons wrap vertically.

Dashboard sections (current): `header`/`nav`; a `Service` meta line; six `a.cfg` settings links;
`<h2>Daemons</h2>` + the 6-column table; `<h2>Transmission</h2>` (a 5-column `tx` table + RPC link);
`<h2>USB Storage</h2>`; `<h2>FTP Server (wifi only)</h2>`; `<h2>DHT Crawler</h2>`.

The shared components to reuse (already in `WEBUI_CSS`): `.wrap` (640px), `.card`, `.state` pills
(RUNNING/STARTING/STOPPING/STOPPED), `.btn` / `.btn.secondary`, `.chip`, `.badge`, `.meta`,
`.banner` (.red/.amber/.ok), `header`/`nav`. The search page also defines `.actions{display:flex;
align-items:center;gap:14px;margin-top:10px}` (search-only) and `.add-btn`.

## Design

1. **Frame.** Use `.wrap` (max-width 640px, the same centred phone column as `/search`) instead of
   `.wrap wide`. After the tables are gone there is no need for the 860px width. Hard requirement:
   `documentElement.scrollWidth <= clientWidth` at 360–412px viewports.

2. **Daemons — replace the `<table>` with one `.card` per daemon.** For each daemon emit:
   ```html
   <section class="card">
     <div class="daemon-head"><strong>dht_crawler</strong><span class="state RUNNING">RUNNING</span></div>
     <p class="meta">PID 16278 · Uptime 8m 11s · Restarts 1</p>
     <div class="actions">
       <form method="post" action="/daemon/dht_crawler/start"><button class="btn">Start</button></form>
       <form method="post" action="/daemon/dht_crawler/stop"><button class="btn secondary">Stop</button></form>
       <form method="post" action="/daemon/dht_crawler/restart"><button class="btn secondary">Restart</button></form>
     </div>
     <pre class="loglines">…</pre>
   </section>
   ```
   - `.daemon-head{display:flex;justify-content:space-between;align-items:center;gap:8px}` (name left,
     state pill right) — add this to the dashboard-specific block.
   - `.actions{display:flex;gap:8px;flex-wrap:wrap}` in the dashboard-specific block so the three
     buttons are always on one horizontal row (they are only ~3×65px + gaps).
   - Keep `escapedLabel` and the exact `action="/daemon/<label>/{start,stop,restart}"` URLs, `method="post"`,
     and the redirect behaviour **byte-identical**. Preserve the per-daemon log tail (its `&`/`<`/`>`
     escaping) as a `.loglines` block inside the card.

3. **Downloads (`Transmission`).** Replace the 5-column `.tx` table with a card list: one `.card`
   (or a `.meta` row) per torrent showing the name plus a `.meta` line `Done 0% · Down — · Up — ·
   Size 236.1 MB`. Keep the RPC link (`http://<host>:9091/`, user `torrentapp`).

4. **Service / status sections.** USB Storage, FTP Server, DHT Crawler: keep the data; wrap each
   section in a `.card` and render its values with `.meta`/`.state`/`.badge`/`<code>` instead of raw
   `&nbsp;`-separated text. Keep the FTP URL (`<code>ftp://…</code>`) and the `/search` link.

5. **Config links.** Keep all six `a.cfg` links (Transmission settings / FTP settings / Storage /
   Crawler / Crawler Stats / Search); restyle them as themed chips/buttons (e.g. `.chip` or
   `.btn.secondary`) so the top of the page matches the search header language. Do not drop any link.

6. **Dashboard-specific `<style>` block.** Shrink it to only the new rules needed: `.daemon-head`,
   `.actions`, a compact `.actions .btn` size, `.loglines`, any `.meta`/`.warn` overrides that remain,
   and the card-list rules for the downloads/service sections. Delete the old grey table/th/td/`a.cfg`
   rules that no longer apply. Keep a red `.warn` override only if a warning element still exists on
   the page.

## Out of scope (explicit)

- `CrawlerProxyServer` and `/torrent?hash=…`; the vendored crawler C HTML.
- No route, WS-protocol, auth, form, JSON, or `/api/transmission/add` behaviour change.
- The daemon POST semantics and redirect target are unchanged (presentation-only).
- No C / submodule changes.

## Scope (files)

- `app/src/main/java/com/torrent/app/ControlServer.kt` — `buildDashboard()` markup + its
  dashboard-specific `<style>` block.
- `app/src/test/java/com/torrent/app/ControlServerDashboardTest.kt` (new) — plus possibly a small
  visibility change to make `buildDashboard` unit-testable (see Tests).

## Constraints

- No external assets (no CDN fonts/JS/CSS). No jQuery.
- All torrent/user-controlled text keeps flowing through `textContent` (search cards) or the existing
  Kotlin `esc()` — never introduce `innerHTML` with crawler data. The dashboard log lines keep their
  existing `&`/`<`/`>` escaping.
- Keep the six `a.cfg` links and every daemon form `action`/`method`/redirect unchanged.
- `WEBUI_CSS` stays `internal val`; do not change any route/cookie/`Set-Cookie`/WS/JSON path.
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

- New `ControlServerDashboardTest` (plain JUnit, no Robolectric). `buildDashboard()` is currently a
  `private fun` (class scope) that reads instance state; make it `internal` as the testable seam and
  construct a `ControlServer` with the same fakes the existing `ControlServerWebSocketTest` uses (or
  extract a pure companion `internal` string builder if instance construction is heavy). Assert the
  dashboard HTML:
  - contains `--blue:#1a73e8` and `class="card"`;
  - contains `>Start</button>`, `>Stop</button>`, `>Restart</button>`;
  - contains `action="/daemon/` (start and stop and restart forms);
  - does **not** contain a 6-column daemon `<table>` (assert the `<h2>Daemons</h2>` region uses cards,
    e.g. the daemon name appears inside a `.card`, or simply assert no `<table>` for daemons);
  - has no external assets (no `<script src`, `<link `, `cdn.`).
- Keep every existing test green.

## Exit criteria (implementer) — all must pass

1. `./gradlew assembleDebug` and `./gradlew :app:testDebugUnitTest` pass (incl. the new dashboard test).
2. Read-only spot-check of the generated `/` string: daemons are `.card`s with name+state on a
   horizontal row and Start/Stop/Restart in a horizontal `.actions` row; `scrollWidth <= clientWidth`
   at 360–412px (full visual check is on the phone).
3. Dashboard form actions unchanged: `POST /daemon/<label>/{start,stop,restart}` + redirect still work.
4. Downloads table and service/config sections match the search card/chip/button language; six config
   links still present.
5. Diff review: no `innerHTML` with crawler content, no external asset URLs, no route/WS/auth/JSON
   change, no C/submodule/`CrawlerProxyServer` change, and daemon POST semantics unchanged.
6. On-device (final, on the phone over USB after `installDebug`): `/` fits a phone with no horizontal
   scroll, daemons read left-to-right (name/state row + horizontal Start/Stop/Restart), and a
   Start/Stop/Restart POST still works (200 redirect back to `/`).

## Commit

```text
app: webui stage 6 — dashboard phone redesign (card daemons, horizontal controls, no scroll)
```