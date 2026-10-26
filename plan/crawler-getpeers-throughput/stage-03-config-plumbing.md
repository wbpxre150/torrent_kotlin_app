# Stage 3: config plumbing for the get_peers lookup

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`**, submodule `app/src/main/cpp/dht_crawler`.
> Index/design: `plan/crawler-getpeers-throughput/README.md`.
> Depends on stage 2.

## Goal

Promote stage 2's three hardcoded constants to config, so the deadline and packet budget can be tuned
on the phone without a rebuild, and so stage 4 has its query cap. Also fix the one wart stage 2 leaves
behind: the metadata retry path allocates a response queue per failed fetch.

Three new keys:

| Key | Default | Clamp | Meaning |
|---|---|---|---|
| `tree_get_peers_deadline_ms` | **3000** | 500 – 8000 | hard cap on one `tree_lookup_peers()` call. Must stay **< 10000** (the shared dispatcher deletes TID registrations older than 10 s). |
| `tree_get_peers_fanout` | **8** | 2 – 12 | concurrent get_peers queries per round. Must stay ≤ `TREE_GP_RESPONSE_QUEUE_CAP - 4`. |
| `tree_get_peers_max_queries` | **16** | 8 – 32 | total packets per infohash (bounds UDP). Consumed by stage 4; parsed and exposed here. |

## Context (measured — do not re-derive)

- Stage 2 hardcodes `GP_FANOUT 8` / `GP_DEADLINE_MS 3000` in `thread_tree.c` and adds
  `TREE_GP_RESPONSE_QUEUE_CAP 16` in `include/tree_response_queue.h`.
- `tree->get_peers_timeout_ms` already flows from `tree_get_peers_timeout_ms` through
  `tree_config_t` (`include/thread_tree.h:82`) → `thread_tree_create()` (`thread_tree.c:1019-1023`) →
  `spawn_tree()` (`supervisor.c:461-500`). Follow that exact path for the new fields; `supervisor.h`
  needs matching members (`supervisor.h:163-170` is the existing block).
- The metadata worker creates and destroys a response queue **per failed fetch**
  (`tree_metadata.c:1482-1492`): `tree_response_queue_create(10)` … `tree_response_queue_destroy()`.
  At ~87 k relookups/h that is ~24 allocations/second of a ~35 KB structure, and after stage 2 a
  capacity-10 queue is under-sized for a fan-out of 8.
- The app side needs exactly **two** edits (verified: no other reference enumerates crawler keys):
  `app/src/main/java/com/torrent/app/config/CrawlerKeys.kt` (allow-list, AGENTS.md invariant 7) and
  `app/src/main/assets/dht_crawler/config.ini.default` (sanitized template).
- `CrawlerKeys` rejection is silent-by-failure: a key missing from the list is dropped on write, so the
  operator's dashboard edit appears to succeed and never reaches the daemon. This is the most common
  way a new crawler key dies.

## Scope

### 1. Crawler config (`include/config.h`, `src/config.c`)

- `config.h`: add the three fields to `crawler_config_t` next to
  `tree_get_peers_timeout_ms` (line ~123), with the clamp ranges in the comment.
- `config.c`:
  - defaults in `config_init_defaults()` (3000 / 8 / 16) — next to `tree_get_peers_timeout_ms`;
  - parse in `config_load_file()` beside the existing `tree_get_peers_timeout_ms` branch, using the
    same `strtol` + range-check idiom;
  - **clamp with a `LOG_WARN`** naming the key, the requested value and the applied value. Clamp rules:
    - `deadline_ms`: `<500 → 500`, `>8000 → 8000` (the 10 s dispatcher cleanup is the hard ceiling);
    - `fanout`: `<2 → 2`, `>12 → 12`;
    - `max_queries`: `<fanout → fanout` (a cap below the fan-out would make one round impossible),
      `>32 → 32`;
  - `config_print()`: print all three.

### 2. Supervisor → tree plumbing

- `include/supervisor.h`: add `int get_peers_deadline_ms; int get_peers_fanout; int get_peers_max_queries;`
  beside `get_peers_timeout_ms` (line ~165).
- `supervisor.c`: copy them in `supervisor_create()` where the other `get_peers_*` fields are set, and
  into `tree_config_t` in `spawn_tree()` (`supervisor.c:461-500`).
- `include/thread_tree.h`: add the three to `tree_config_t` (line ~79) and to `thread_tree_t`
  (line ~146), with the same "Stage 4 config" comment style.
- `thread_tree.c`: read them in `thread_tree_create()` (`thread_tree.c:1019-1023`) with the same
  `> 0 ? value : default` idiom, then use `tree->get_peers_deadline_ms` / `tree->get_peers_fanout`
  in `tree_lookup_peers()` instead of `GP_DEADLINE_MS` / `GP_FANOUT`. Keep the constants as the
  fallback defaults (do not delete them).

### 3. Response queue hoist (`src/tree_metadata.c`)

In `tree_metadata_worker_func()`:

- create **one** queue at worker start:
  `tree_response_queue_t *relookup_queue = tree_response_queue_create(TREE_GP_RESPONSE_QUEUE_CAP);`
  (bail out of the worker with a `LOG_ERROR` if it fails, matching the other early-exit paths);
- use it for every fresh-peer relookup instead of creating/destroying one per failure;
- destroy it once at the end of the worker function, on every exit path (including the
  `SHUTDOWN_REASON_SUPERVISOR` breaks).

### 4. App side

- `CrawlerKeys.kt`: add the three keys to the allow-list beside `tree_get_peers_timeout_ms` (line 49).
- `app/src/main/assets/dht_crawler/config.ini.default`: add the three keys under the get_peers block
  (line ~119) with their defaults and a one-line comment each.

### 5. `/stats`

Echo the effective values under each tree's `get_peers` object (stage 1 created it):
`deadline_ms`, `fanout`, `max_queries`. Two runs of `gp_probe.py` must be provably comparable.

## Constraints

- Clamping is **not** an error: never refuse to start. Warn and continue with the clamped value.
- Keep the existing key names and defaults byte-identical (`tree_get_peers_timeout_ms` stays 1500 and
  keeps its stage-2 meaning: the drain-slice cap).
- Do not add a key for `TREE_GP_RESPONSE_QUEUE_CAP` — it is a compile-time sizing constraint, not a
  tunable.
- Do not edit `dht_crawler/lib/` or `dht_crawler/patches/`.
- No behaviour change at default values: with 3000/8/16 the crawler must measure identically to
  stage 2.

## Pitfalls

1. **The allow-list is the trap.** Adding the keys to `config.c` and the template but not to
   `CrawlerKeys.kt` means the wizard writes them, the app filters them out, and the daemon never sees
   them — with no error anywhere. Verify by writing a non-default value from the dashboard and reading
   `/stats` back.
2. **`max_queries` < `fanout` deadlocks the round.** A cap of 4 with a fan-out of 8 would let the first
   round send 8 packets and then have nothing left; clamp `max_queries >= fanout` at parse time, not in
   the hot path.
3. **The deadline ceiling is a hard 10 s, not a suggestion.** `tree_dispatcher_cleanup_stale_tids(…, 10)`
   runs every 30 s; a registration removed under a live lookup loses that response and leaks the slot
   until the sweep. Clamp at 8000 to leave margin.
4. **The hoisted queue changes lifetime semantics.** Today the queue dies with each relookup, so a late
   response is dropped by the dispatcher. With a long-lived queue, late responses from the *previous*
   relookup can arrive while the next one is in flight — `tree_lookup_peers()` handles that via its TID
   match and the entry purge (stage 2), but only if the purge is present. Confirm it is.
5. **`thread_tree_t` and `tree_config_t` both need the fields.** Missing the `tree_config_t` half
   compiles fine (the struct is aggregate-initialised in `spawn_tree()`) and silently leaves the tree
   with 0 → the `> 0 ? value : default` fallback hides it. Grep for every use before building.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
(cd app/src/main/cpp/dht_crawler && make -j"$(nproc)")
./gradlew installDebug
./gradlew :app:testDebugUnitTest
```

## Exit criteria (implementer) — all must pass

1. Host `make`, `./gradlew installDebug`, `:app:testDebugUnitTest` clean.
2. `config.ini` round-trip: set `tree_get_peers_deadline_ms = 5000` from the dashboard → restart the
   daemon → `/stats` `trees[].get_peers.deadline_ms == 5000` and the value is present in the on-device
   `files/crawler/config.ini`. Repeat for `fanout = 4` and `max_queries = 24`.
3. Clamping: `deadline_ms = 99999` → daemon starts, warns, `/stats` shows `8000`; `fanout = 1` → `2`;
   `max_queries = 2` → raised to the fan-out value.
4. Defaults reproduce stage 2: `gp_probe.py --minutes 10` within ±10 % of the stage-2 run on drain,
   packets per lookup and mean lookup ms.
5. No per-failure allocation remains in the metadata worker: grep shows one
   `tree_response_queue_create` and one `tree_response_queue_destroy` in
   `tree_metadata_worker_func()`, both outside the loop.
6. 30-minute soak with `fanout = 12`, `deadline_ms = 8000`: no crash, RSS flat, `queue_full_drops` flat,
   and the drain is **not** lower than at 8/3000 (a lower drain means packets, not the deadline, are
   binding — report it for stage 4).

## Commits

1. Inside the submodule:
   `git -C app/src/main/cpp/dht_crawler commit -am "crawler: get_peers deadline/fanout/max-queries config + hoist retry response queue"`
2. Parent (allow-list + template + gitlink):
   `git add app/src/main/cpp/dht_crawler app/src/main/java/com/torrent/app/config/CrawlerKeys.kt app/src/main/assets/dht_crawler/config.ini.default && git commit -m "app: crawler get_peers throughput stage 3 — config plumbing"`
