# Stage 4: Crawler — retire the C HTML dashboard (keep JSON + `/torrent`)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`**, submodule `app/src/main/cpp/dht_crawler`.
> Depends on stages 1–3 (Kotlin UI is live). Index: `plan/search-dashboard/README.md`.

## Goal

Delete the crawler's own HTML search dashboard now that the Kotlin dashboard serves search. Keep every
**JSON** endpoint and the per-torrent detail HTML; remove only the page builders and the three
`/random-tv|-movies|-music` HTML handlers.

## What to remove (in `app/src/main/cpp/dht_crawler/src/http_api.c` only)

1. `root_handler` (the Google-style page) and its `mg_set_request_handler(…, "/", root_handler, …)`
   registration. After removal, `/` is no longer registered → CivetWeb answers 404, which is correct
   (the proxied old dashboard is gone).
2. The whole HTML result renderer and its helpers: `generate_search_results_html` (and the
   `results_page_opts_t` type), `html_escape`, `url_encode`/`url_decode` **if** now unused anywhere else,
   the `RANDOM_TV_MODE_SENTINEL` / `RANDOM_MOVIE_MODE_SENTINEL` / `RANDOM_MUSIC_MODE_SENTINEL` macros.
3. The `format=html` branches + empty-state HTML in `search_handler` and `browse_handler`. After removal
   both handlers always answer JSON (`format` param can be dropped/ignored). Keep the `q`-required check
   in `/search` and the `/browse` filter/sort/page parsing exactly as-is.
4. `random_tv_handler`, `random_movie_handler`, `random_music_handler`, `random_video_handler_impl`,
   `get_random_tv_torrents`, `get_random_movie_torrents`, `get_random_music_torrents`,
   `get_random_video_torrents_filtered`, `is_video_torrent`, `is_music_torrent`,
   `has_tv_episode_pattern`, `RANDOM_CLASS_SQL`, and `random_candidate_eligible` — **only after**
   confirming the stage-1 `/random` path (which uses `RANDOM_CLASS_SQL_EX` + `random_candidate_eligible_ex`
   + `get_random_torrents`) does not call them.
5. The three `/random-*` `mg_set_request_handler` registrations.
6. `VIDEO_FILTER_TV`/`VIDEO_FILTER_MOVIE` enum and anything else that becomes dead as a result — verified
   by a clean `make` with no `-Wunused-function` warnings.

## What must stay (unchanged)

- `/search` (JSON), `/browse` (JSON), `/stats` (JSON), `/refresh`, `/random` (JSON, stage 1),
  `/torrent` and `/torrent/files` (`http_torrent_page.c` — untouched), and all their registrations.
- `format_class_filter_qs` / `format_chip_qs` are **removed only if** no remaining code uses them
  (they existed to build HTML links; with the HTML gone they should be dead — confirm, then remove).
- `parse_class_filter`, `append_class_conditions`, `bind_class_filter`, `class_filter_needs_join`,
  `send_json_results`, `send_json_error`, category/tv_kind name helpers — all still used by JSON endpoints.

## Constraints

- The crawler is a git **submodule**: commit inside it, then bump the parent gitlink (see Commits).
- Do not edit `dht_crawler/lib/`, `http_torrent_page.c`, or the 3 build patches.
- Must compile for the crawler's own host `make` (no `CLF_ENABLED`) **and** the Android NDK build.
- Do not touch any Kotlin file.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
(cd app/src/main/cpp/dht_crawler && make -j"$(nproc)")   # host build; watch for unused-function warnings
./gradlew assembleDebug                                   # full NDK cross-compile
./gradlew :app:testDebugUnitTest
```

## Exit criteria (implementer) — all must pass

1. Host `make` and `./gradlew assembleDebug` compile cleanly (no new warnings, no dead-code warnings).
2. `./gradlew :app:testDebugUnitTest` passes.
3. Diff review: JSON handlers (`/search`, `/browse`, `/stats`, `/refresh`, `/random`) and
   `http_torrent_page.c` untouched; all HTML builders/helpers and the three `/random-*` handlers removed;
   no dangling references (`root_handler`, `generate_search_results_html`, the sentinels) anywhere in
   `grep -rn 'root_handler\|generate_search_results_html\|random_tv_handler\|RANDOM_.*_MODE_SENTINEL' app/src/main/cpp/dht_crawler/src`.

## Commits

1. Inside the submodule:
   `git -C app/src/main/cpp/dht_crawler add src/http_api.c && git -C app/src/main/cpp/dht_crawler commit -m "crawler: retire C HTML search dashboard (keep JSON + /torrent)"`
2. Bump the parent gitlink:
   `git add app/src/main/cpp/dht_crawler && git commit -m "app: search-dashboard stage 4 — crawler gitlink (retire C HTML)"`