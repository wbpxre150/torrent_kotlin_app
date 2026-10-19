# Fix_Movie_Name — structural "Title Year" from the crawler (C-side fix)

Status: REPLANNED 2026-10-19, not implemented. Supersedes the earlier JS-only plan.
Plan owner: the `/dev` parent session. Implementers: cold-start, this file is your only context.
Repo: `torrent_kotlin_app` (single module `:app`, package `com.torrent.app`) + the crawler submodule.

## Goal

On the `/torrent` detail page (the page behind the "..." on a search result), the IMDb and
DHT search buttons build their query from the **pre-selected** keyword pills. Today the pills
default to *every* word the crawler's `filtered_name` kept, which is either junk-laden
(new release groups / service tags / codecs not in the crawler's 330-line keyword file) or
title-mangled (title words that collide with keyword entries are deleted and their pills
never render at all). The user has to click a pile of pills OFF before every IMDb/DHT
search.

Fix: make `filtered_name` mean **"movie title + year"** — computed *structurally* from the
raw torrent name (scene-release grammar) inside the crawler's `extract_media_title()` — so
the default query is right **at the source**. The Kotlin pill layer then needs no
algorithmic change: it already defaults ON exactly the tokens present in `filtered_name`.
Owner decision (2026-10-19): editing the crawler is in scope; **we own the submodule**, so
the fix is a direct commit in `app/src/main/cpp/dht_crawler/` — no new patch file.

## Why the C side is the better fix (decision record)

- `filtered_name` is **presentation-only and single-consumer**: `extract_media_title()`
  (`app/src/main/cpp/dht_crawler/src/torrent_search.c:450`) has exactly one call site —
  `http_torrent_page.c:257` — and its output only ever reaches the `/torrent/info` JSON
  (`filtered_name` key, `http_torrent_page.c:273`). It is **not** written to the SQLite DB,
  **not** in the FTS index, **not** used by `/browse` or `/search`. Verified by grep.
  Changing it cannot corrupt search results or stored data.
- **One implementation, not two.** The JS-only plan duplicated the pivot grammar
  (year/season/quality regexes) as ~40 lines of ES5 inside a Kotlin raw string —
  unlintable, unminifiable, and drifting from the C keyword file it was meant to replace.
  The crawler is the owner of name-parsing (keyword file + season regex already live
  there); the fix belongs next to them. The crawler's standalone host deployment (see its
  `CLAUDE.md`) benefits too.
- **Real tests.** The crawler repo builds on-host (`make`, `make asan`), so the fix gets a
  plain C unit test asserting exact strings ("1917 2019" etc.). The JS-only plan could only
  "pin literal fragments" of the ES5 blob via Kotlin `contains` checks — a far weaker
  contract.
- **Minimal Kotlin blast radius.** With `filtered_name` = "Title Year":
  `TORRENT_JS`/`renderPills`/`wordInFiltered` (`ControlServer.kt:2539-2544`) keep working
  unchanged — they already default ON exactly the `filtered_name` tokens. Zero Kotlin
  source change is *required*; `ControlServerTorrentPageTest.kt` stays untouched and green
  as the regression guard. (An optional 2-line pill nicety is Stage 4 below.)
- **Costs accepted:** native rebuild + APK reinstall (`buildDhtCrawler` →
  `libdhtcrawler.so` → `installDebug` — routine; the build graph already regenerates
  the `.so` on every build) and a crawler-repo commit. No new patch file, no
  `build.gradle.kts` change: **no existing patch touches `src/torrent_search.c`**
  (checked: all 8 patches under `dht_crawler/patches/` touch `lib/bencode-c`,
  `lib/civetweb`, `src/main.c`, `src/supervisor.c`, `src/database.c` only), and
  `prepareDhtCrawlerSrc` copies `src/` verbatim every build, so a committed crawler edit
  persists — nothing is "wiped" (that invariant is about `dht_crawler/lib/` submodule
  content and the transmission tarball, not the crawler's own `src/`).

## Root cause (for context — do not re-derive)

- Pills are rendered by `TORRENT_JS` in `ControlServer.kt` (`app/src/main/java/com/torrent/app/ControlServer.kt`,
  `TORRENT_JS` starts :2438): `tokenizeName(name)` :2502 (dots/underscores → spaces,
  `()[]{}` stripped), `wordInFiltered(word, filtered)` :2515 (case-insensitive whole-token
  match), `renderPills(name, filteredName)` :2539 — **skips** any raw-name token not in
  `filtered_name` (:2544) and defaults every rendered pill to `class = 'pill on'` (:2546).
  So default query = all of `filtered_name`, and tokens deleted from it never render.
- `filtered_name` comes from the crawler's `/torrent/info` JSON, built by
  `extract_media_title()` (`app/src/main/cpp/dht_crawler/src/torrent_search.c:450`):
  separators → brackets dropped → parens (years 1900-2099 kept) → truncate at
  season/episode regex (:394) → **case-insensitive whole-word deletion** of every entry in
  `torrent_search_keywords.txt` (330 lines: codecs, sources, groups, languages, streaming
  tags) → trim.
- Two failure modes, both user-visible and both *created by the keyword-deletion step*:
  1. Junk not in the keyword file (new groups/tags/codecs) survives → pills render **ON**.
  2. Real title words that *do* match keyword entries — confirmed present in the file:
     `HD`, `WEB`, `REAL`, `FINAL`, `IT`, `RED`, `MA`, `DON` — are deleted → those pills
     never render, the words are unrecoverable, and the query preview lies about the title.
- The crawler `/search` that `dht-btn` targets is token/FTS (porter, `http_api.c`
  `parse_search_query` :1215), and IMDb's `find/?q=` expects "Title Year" — so a
  `"Title Year"` default query is the correct shape for both.

## Rejected alternatives (do not do)

- **fastText** (asked about explicitly): the existing fastText stack
  (`dht_crawler-android/ftc/ftc.cc`, adult/category/tv_kind students) is a whole-line
  bag-of-features linear classifier. "Which tokens are title vs. junk" is a span/order
  decision fastText has no representation for; it would need per-token labeling of
  thousands of release names, a new `.ftz` in the APK, ABI plumbing, model versioning — and
  a fallback parser anyway (scene names follow the strict grammar
  `Title.Year.Source.Codec-Group`; guessit/scene-release tools all do this with pure
  heuristics, zero ML).
- **JS-side pivot algorithm in `TORRENT_JS`** (the superseded plan): works, but duplicates
  the grammar in a raw ES5 string with only fragment-pinning Kotlin tests, and leaves the
  crawler's own consumers with the broken `filtered_name`.
- **Keyword stripping inside the pivot span** (considered during re-plan): reintroduces
  failure mode 2 ("It.2017.1080p…" → "2017 1080p…"). No. On the pivot path the keyword
  list is **not consulted at all**; grammar only.

## Non-negotiable invariants (from AGENTS.md, relevant subset)

- JDK 21 for Gradle (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`, `ANDROID_HOME=$HOME/Android/Sdk`,
  `ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/android-ndk-r27`).
- Crawler submodules initialized first: `git submodule update --init --recursive` inside
  `app/src/main/cpp/dht_crawler/` (host test needs `lib/libuv/include` + `lib/uthash/src`;
  the Gradle build hard-fails without it).
- **`dht_crawler/lib/` and the transmission tarball stay untouched.** The crawler's own
  `src/` is editable **by direct commit** (owner decision above): do NOT add a new
  `patches/*.patch` for this, do NOT touch the patch `listOf(...)` in
  `app/build.gradle.kts`, and keep patch order untouched.
- `buildTorrentPage()` stays a PURE string builder: no fetcher, no network, no config, no
  token inlined into markup; every crawler value still reaches the DOM via `textContent`.
  The page still talks only to same-origin :8090 routes; IMDb stays the one external link
  (`a.target='_blank'`, `rel='noopener noreferrer'`). (Stage 4, if taken, respects all of
  this.)
- House test style: the Kotlin tests in `app/src/test/java/com/torrent/app/` are **not
  modified** by Stages 1–3 (no Kotlin source change → `ControlServerTorrentPageTest.kt`
  pins keep passing untouched = regression guard). New tests live in the crawler repo.
- Submodule hygiene: commit inside `app/src/main/cpp/dht_crawler/` **first** (its own
  history, message prefix `crawler: …` per its log), then bump the parent repo
  (submodule pointer + this plan) in the same change set.

## Stage 1 — C: structural pivot in `extract_media_title`

Scope: `app/src/main/cpp/dht_crawler/src/torrent_search.c` only. No other crawler file. No
Kotlin file. No patch file.

Add `static int cut_at_release_pivot(char *str)` and restructure `extract_media_title()`
so the pipeline becomes:

```
1. replace_separators       (unchanged)
2. remove_brackets          (unchanged)
3. process_parentheses      (unchanged)
   — NEW: if cut_at_release_pivot(result) → cleanup + return (skip 4+5)
4. truncate_at_season       (legacy path only; make it return int, it is static with one caller)
5. strip_keywords           (legacy path only)
6. collapse_spaces / clean_trailing_separators / trim_whitespace (both paths)
```

`cut_at_release_pivot` ladder, first hit wins (all token scans over the space-split
processed string):

1. **Year pivot** — smallest token index `i > 0` with `is_year(tok, len)` (the existing
   static helper, :107, 1900-2099) → cut **inclusive** after that token (title + year).
   `i > 0` so year-shaped *titles* ("1917.2019.1080p…") survive as title words; a
   title-word that also looks like a year ("Blade.Runner.2049.2017…") resolves to the
   *first* such token → "Blade Runner 2049".
2. **Season pivot** (no year anywhere) — `search_state.season_regex` match (the existing
   pattern, REG_ICASE) → cut **exclusive** at `rm_so`
   ("Show.Name.S01E05.1080p.x264-GRP" → "Show Name"; "Weeds Season 2 1080p" → "Weeds").
   Reuse the exact cut `truncate_at_season` performs today.
3. **Quality pivot** (no year, no season) — first token matching
   `^\d{3,4}p$` (case-insensitive p) or `uhd|hdr|hdr10|4k|fhd|qhd` → cut **exclusive**
   ("Some.Movie.1080p.BluRay.x264" → "Some Movie").
4. **No pivot** → return 0; caller falls through to the legacy path (4+5) unchanged.

On pivot paths do **not** run `strip_keywords` — grammar only, no keyword consultation
(failure mode 2 is impossible by construction; post-pivot junk — codecs, sources, group
suffixes — never survives, failure mode 1 is impossible for scene-shaped names).

Accepted edges (document in a comment, do not chase): pre-pivot language tags may survive
("Movie.Name.FRENCH.1080p" → "Movie Name FRENCH") — at most one pill to click off, never a
title-mangle; source tags before a quality token survive ("…WEB-DL.1080p" → "…WEB-DL") —
IMDb and the crawler FTS both tolerate the extra token. Known and fine.

Update the `extract_media_title` doc comment (:435-449) to describe the new ladder.

## Stage 2 — C: host unit tests in the crawler repo

New `tests/` in `app/src/main/cpp/dht_crawler/` (repo has none today; its `CLAUDE.md`
build commands stay as-is):

- `tests/test_torrent_search.c` — plain C `main` with `assert`-style checks calling
  `torrent_search_init("torrent_search_keywords.txt")` (repo root, the real file) then
  `extract_media_title()` on a table of inputs, expecting exact output strings:
  - `"1917.2019.1080p.BluRay.x264-NOGRP"` → `"1917 2019"`
  - `"Some.Movie.2020.1080p.WEB-DL.x264-RARBG"` → `"Some Movie 2020"`
  - `"Blade.Runner.2049.2017.2160p.WEB-DL.x265-GROUP"` → `"Blade Runner 2049"`
  - `"It.2017.1080p.BluRay.x264-GRP"` → `"It 2017"` (title word `IT` survives — the old
    pipeline mangled this to `"2017"`)
  - `"Show.Name.S01E05.1080p.x264-GRP"` → `"Show Name"`
  - `"Movie.Name.2019.Season.1.1080p"` → `"Movie Name 2019"` (year beats season)
  - `"Some.Movie.1080p.BluRay.x264"` → `"Some Movie"` (quality pivot, no year)
  - `"Movie.Name.(2020).1080p"` → `"Movie Name 2020"` (parens year kept)
  - a non-scene name with no pivot → legacy-path behavior (assert exact string; the
    legacy path is unchanged, so compute the expectation once by hand)
  - `NULL`/empty inputs → NULL/empty, no crash.
- `tests/stub_log.c` — `void log_msg(log_level_t level, const char *format, ...)` no-op
  stub matching `include/dht_crawler.h:40` (torrent_search.c only *includes* uv.h; no
  libuv symbols are linked).
- Build line (host, after `git submodule update --init --recursive`):
  `cc -O2 -Iinclude -Ilib/uthash/src -Ilib/libuv/include -Isrc -o build/test_torrent_search tests/test_torrent_search.c src/torrent_search.c tests/stub_log.c`
  plus a `tests` (or `test`) target in the crawler `Makefile` running it. Also run it once
  under `-fsanitize=address,undefined` (matches the repo's `make asan` spirit).
- Run `make tests` on host — all green, exit code 0.

Commit in the submodule: `crawler: extract_media_title cuts title+year at scene-release pivot`
(or its style) — `src/torrent_search.c`, `tests/*`, `Makefile` target.

## Stage 3 — app build + on-device exit criteria (all on the real phone)

No Kotlin source change. Build with the JDK-21 env: `./gradlew installDebug`
(rebuilds `buildDhtCrawler` → `libdhtcrawler.so` → `mergeDebugJniLibFolders`).
Before the device pass, `./gradlew :app:testDebugUnitTest` must be green **unmodified**
(pure regression guard — no app sources changed).

On the phone, via the control dashboard (token from
`adb shell run-as com.torrent.app cat shared_prefs/control_auth.xml`):

1. Open a search result "…" → `/torrent?hash=…` page: exactly the title words + year
   pills render and are ON (e.g. "Some Movie" + "2020"); junk (codec/source/group) pills
   do **not** render at all (they are no longer in `filtered_name`).
2. `Search IMDb` → new tab opens `imdb.com/find/?q=Title%20Year` with the right title+year.
3. `Search DHT` → `/search?q=Title Year` returns matches from the crawler DB.
4. Toggle a pill off/on → preview updates (existing behavior intact).
5. Edge sanity on real rows: a TV-episode name ("…S01E05…") → title-only ON; a
   year-titled movie ("1917.2019…") → "1917" + "2019" ON; a title word colliding with the
   keyword file ("It", "Final", "Red" — pick a real row from the DB) → the word now
   **appears** and is ON; a non-scene name → legacy path, no worse than today.
6. `/torrent/info?hash=…` JSON directly (via the crawler proxy) shows
   `"filtered_name":"Title Year"`.
7. Scope check: `git status` in the crawler repo shows only `src/torrent_search.c`,
   `tests/`, `Makefile` committed; in the parent repo only the submodule pointer bump +
   this plan. `app/build.gradle.kts` and the patch list untouched.

## Stage 4 — OPTIONAL Kotlin pill nicety (only if the C fix is not enough)

If junk tokens should be *toggleable* (OFF pills instead of not rendered): in
`TORRENT_JS renderPills` (`ControlServer.kt:2539`), drop the `continue` at :2544 and
default from membership:
`var on = wordInFiltered(words[i], filteredName); pill.className = 'pill' + (on ? ' on' : '');`.
~2 lines, no algorithm, no regexes — nothing structural lives in JS. Then update
`ControlServerTorrentPageTest.kt` pins accordingly. Do **not** do this in the same stage
as Stage 1; verify Stage 3 on-device first.

## After landing

`graphify update .` — `ControlServer.kt` is a god node (75 edges) and the crawler commit
changes the `/torrent` page community (`torrent_search.c` is in the graph too).