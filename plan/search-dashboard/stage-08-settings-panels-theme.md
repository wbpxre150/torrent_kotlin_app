# Stage 8: Settings panels — themed card forms (crawler panel priority)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`** (Kotlin `ControlServer.kt` only — no C changes).
> Depends on stage 5 (the shared `WEBUI_CSS` theme is in place; all four panels already interpolate it).
> Index: `plan/search-dashboard/README.md`.

## Goal

Make the four settings panels — `/settings/transmission`, `/settings/ftp`, `/settings/storage`,
`/settings/crawler` — read as grouped, themed card forms like the `/search` page instead of a wall of
full-width fields. The dense crawler panel (flagged by the user as ugly) is the priority: group its
many fieldsets into visual cards, restyle its apply/maintenance/backup sections to the theme, and keep
every form field, action, and validation byte-identical. `/stats` is covered separately by stage 7.

## Context

After stage 5, each panel emits `<style>$WEBUI_CSS …</style>` (the crawler panel also keeps its
crawler-specific rules `.warn-num`, `#apply-status`, `.apply-log`, `#maint-*`). The crawler panel is
one large `<form method="post" action="/settings/crawler">` containing ~8 `<fieldset>`s (Enabled/WiFi,
log/dht/nice, tree workers/queues, porn filter, classifier, bloom filters, proxy/ports, R2 backup)
with dozens of `label input`/`select` rows, plus maintenance buttons (`#maint-btns form/button`),
`#apply-status`, `pre.apply-log`, `#maint-status`, `pre#maint-log`. The other three panels are smaller
single-fieldset forms. Form controls come from `WEBUI_CSS` (full-width stacked inputs with modern reset),
but the panels currently lack the visual grouping and card language that `/search` has.

The reference look: `.card` (white, 1px `#e3e7ee` border, 12px radius, subtle shadow), `.badge`,
`.meta`, `--muted`/`--text`/`--blue`, `.btn`/`.btn.secondary` pills.

## Design

1. **Card grouping.** Wrap each `<fieldset>` in a `.card` (or style `fieldset` itself as a card) so
   related settings are visually grouped. Give each `<legend>` themed styling (small, `--muted`, space
   from the card edge). The result must read as a sequence of titled cards, not one undifferentiated
   column.

2. **Form controls.** Keep the `WEBUI_CSS` form-control reset and spacing (full-width `input`/`select`,
   ≥44px touch targets, blue focus ring) — no functional change. Keep `.row` checkbox labels inline
   (label left / checkbox right, as today). Ensure labels and their controls stay visually associated
   (`label` on top of its control, consistent gaps).

3. **Crawler panel specifics (must survive, restyled to theme):**
   - `#apply-status` (+`.busy`) and `#maint-status` (+`.busy`) — render as `.banner` (`.ok`/`.amber`)
     or a `.badge`-style status pill;
   - `pre.apply-log` / `pre.maint-log` — styled as the shared `.loglines` block;
   - the maintenance `#maint-btns` buttons and the apply/save button — styled as `.btn` / `.btn.secondary`;
   - `.warn-num` and any warning boxes — keep their intent (red/amber emphasis) aligned to `.banner.red`/`.amber`.

4. **No overflow.** Ensure all four panels have `scrollWidth <= clientWidth` at 360–412px viewports
   (they currently do not overflow; verify after grouping, especially long numeric inputs and the
   advanced tree-worker rows).

5. **No behaviour change.** Keep every `name`, `action`, `method`, the apply/`rebuild_bloom` POST
   semantics, and `ConfigValidator` logic untouched. Keep the crawler maintenance section and its
   `#maint-*` ids so the existing maintenance JS/logic keeps working.

## Out of scope (explicit)

- `CrawlerProxyServer` and `/torrent?hash=…`; the vendored crawler C HTML.
- No route, WS-protocol, auth, JSON, or `/api/transmission/add` behaviour change.
- No C / submodule changes.
- `/stats` (stage 7) and the `/` dashboard (stage 6) are separate.

## Scope (files)

- `app/src/main/java/com/torrent/app/ControlServer.kt` — `buildTransmissionPanel`, `buildFtpPanel`,
  `buildStoragePanel`, `buildCrawlerPanel` markup + the crawler-specific `<style>` block.
- `app/src/test/java/com/torrent/app/ControlServerPanelsThemeTest.kt` (new) — plus a visibility change
  to make the panel builders unit-testable (see Tests).

## Constraints

- No external assets (no CDN). No jQuery.
- All user-controlled form values keep flowing through the existing Kotlin `esc()`.
- Keep every form `name`/`action`/`method`, and the crawler `#apply-status`/`#maint-btns`/`#maint-status`/
  `warn-num`/`apply-log`/`maint-log` ids intact.
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

- New `ControlServerPanelsThemeTest` (plain JUnit, no Robolectric). The four panel builders are
  currently `private fun` (class scope); make them `internal` as the testable seam, constructing a
  `ControlServer` with the same fakes `ControlServerWebSocketTest` uses (or extract pure companion
  `internal` string builders). Assert, for each of the four panels:
  - contains `--blue:#1a73e8` and `class="card"` (the grouped-fieldset card);
  - keeps its form `action` (`/settings/transmission|ftp|storage|crawler`);
  - no external assets (no `<script src`, `<link `, `cdn.`).
  - For the crawler panel specifically: keep `#apply-status`, `#maint-btns`, `#maint-status`, and the
    `warn-num`/`apply-log` classes.
- Keep every existing test green (including `ControlServerStatsPageTest` and `ControlServerSearchPageTest`).

## Exit criteria (implementer) — all must pass

1. `./gradlew assembleDebug` and `./gradlew :app:testDebugUnitTest` pass (incl. the new panels test).
2. Read-only spot-check of the generated panel strings: each panel groups its fieldsets into themed
   cards and references `$WEBUI_CSS`; the crawler panel is no longer one undifferentiated wall (full
   visual check is on the phone).
3. Every form field `name`/`action`/`method` and the apply/`rebuild_bloom` POST semantics are unchanged;
   `ConfigValidator` behaviour is untouched.
4. `scrollWidth <= clientWidth` at 360–412px viewports on all four panels.
5. Diff review: no `innerHTML` with crawler content, no external asset URLs, no route/WS/auth/JSON
   change, no C/submodule/`CrawlerProxyServer` change.
6. On-device (final, on the phone over USB after `installDebug`): open `/settings/crawler` (and one
   other panel) — fields are grouped into themed cards, the page fits the phone without horizontal
   scroll, and applying settings still works (status reflects success/error).

## Commit

```text
app: webui stage 8 — settings panels themed cards (crawler form grouping)
```