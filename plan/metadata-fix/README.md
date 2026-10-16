# Metadata fetch reliability: implementation stages (index)

Per-stage implementation packets live here, one file per stage, implemented **one at a time** via
`/dev <stage file>` (dev-workflow). Each stage file is a complete cold-start packet: goal, target
repo(s), dependencies, scope, constraints, build/validate commands, exit criteria with verification
methods, and the planned commit message(s).

- Stage 1 (crawler + app): **`stage-01-metadata-timeout.md`** — configurable `tree_metadata_timeout_ms`
  (default **60 s**, was hardcoded 30 s).
- Stage 2 (crawler + app): **`stage-02-piece-aggregation.md`** — one shared metadata buffer/bitmap
  per fetch; window peers fetch **different missing pieces** (≤ 2 outstanding per peer) instead of
  racing 4 whole-metadata downloads.
- Stage 3 (crawler + app): **`stage-03-piece-reask.md`** — 3 s per-piece re-ask across peers +
  per-piece reask counters.

## Problem statement (measured/observed)

Crawler metadata fetch success is **~15 %** (`/stats` `metadata_success_rate_percent`). Two causes
established in code:

1. **Race, not aggregation** — `tree_fetch_metadata_parallel` (`src/tree_metadata.c:980`) opens up to
   `MAX_PARALLEL_PEERS = 4` conns, but each conn keeps its **own** `metadata_buffer`/`pieces_bitmap`
   (`:758-770`) and downloads the **whole** metadata from its one peer; first complete copy wins
   (`:949-962`, documented `include/tree_metadata.h:57-61`). Four redundant full downloads; one slow
   or lossy peer kills its entire copy.
2. **All-at-once blast** — after the extended handshake each conn immediately requests **every**
   piece (`:911-913`); BEP 9 allows serving-side flood rejection, so polite clients don't do this.

Plus a user requirement: the metadata fetch deadline is a hardcoded **30 s**
(`tree_metadata.c:1254`), should be ~**60 s** and configurable.

## Research record (web, 2026-10-16)

Web-researcher subagents mapped libtorrent, aria2, Transmission 4.x, BEP 9/10, webtorrent, and
other crawlers. **No production client races whole-metadata per peer; all three aggregate 16 KiB
pieces across peers via shared per-torrent piece state, small per-peer outstanding caps, and short
re-ask intervals:**

| Client | Piece strategy | Per-peer outstanding | Re-ask interval | Fetch deadline |
|---|---|---|---|---|
| libtorrent `src/ut_metadata.cpp` | one shared per-torrent piece table; next request goes to **least-requested** piece | **≤ 2** (`maybe_send_request`, `m_sent_requests.size() < 2`) | **3 s** per-piece cooldown; `dont_have` → 1 min peer penalty | none metadata-specific; `request_timeout` default **60 s** = re-request from *another* peer |
| aria2 `UTMetadataRequestTracker.cc` | shared piece storage, 1 piece in flight per peer, released back to pool on timeout for another peer | **1** (`MAX_OUTSTANDING_REQUEST = 1`) | per-piece **20 s** (`TIMEOUT = 20_s`, swept 1/s) | none overall |
| Transmission 4.x `torrent-magnet.cc` | torrent-level rotating `pieces_needed_` queue shared by **all** peers; ≤ 1 request per pulse; duplicate replies dropped | ~1 per pulse | **`MinRepeatIntervalSecs = 3`**, re-ask from any peer | none metadata-specific |
| webtorrent `ut_metadata` | all-at-once blast (same as our crawler) | all | reject budget numPieces×2 | none in module (caller's problem) |

BEP 9 facts that shape this: pieces are 16 KiB (last may be smaller); a request is answered by data
**or reject** carrying the same index; reject means "peer lacks that piece"; **SHA-1 verification is
only possible over the fully assembled metadata** (no per-piece check exists); serving-side flood
protection is optional (MAY) — the blast is exactly what it punishes. The spec gives **no**
requester-side timeout/pipelining guidance. No published metadata-fetch success rates were found
for other crawlers, so the 15 % baseline has no external comparator.

Decision (user-approved direction): move to the libtorrent/Transmission shape — shared per-fetch
piece state, **2** outstanding requests per peer, **3 s** re-ask across peers, **60 s** default
deadline — keeping the existing window refill and the fresh-peer re-lookup from
`plan/optimise/` stage 3.

## Facts established this session (verified in code — supersede assumptions)

| Item | Value / location |
|---|---|
| Hardcoded 30 s deadline | `tree_metadata.c:1254` (`.metadata_timeout_ms = 30000` in `tree_metadata_worker_func`); `DEFAULT_METADATA_TIMEOUT_MS` define `:51` is only the NULL-config fallback; **no config key exists** |
| All-at-once blast | single path `:679-681`, parallel path `:911-913` (`for i < total_pieces: send_metadata_request`) |
| Race-not-aggregate | per-conn `metadata_buffer`/`pieces_bitmap`/`pieces_received` `:758-770`, `:856-965`; completion `:949-962` |
| Window | `MAX_PARALLEL_PEERS = 4` cap `:755` overrides `tree_parallel_peers` (config 2 default, clamp 1-10 at `config.c:405-406`) |
| Config plumbing chain | `include/config.h:126-128` → `src/config.c:114/:403` → `src/main.c:652-697` (`supervisor_config_t`) → `include/supervisor.h:78-79/:150s` + `src/supervisor.c:106-107/:481-483` (`tree_config_t`) → `include/thread_tree.h:82-84/:148-150` + `src/thread_tree.c:~1021` → `src/tree_metadata.c:1252-1258` |
| Success-rate observable | `/stats` `metadata_success_rate_percent` = total metadata ÷ total attempts (`http_api.c:1227-1231`); per-tree `metadata_count` `:1125`; attempts counted at `tree_metadata.c:1268` |
| Reject handling today | `UT_METADATA_REJECT` → `CONN_STATE_ERROR` → close + window refill (`:926-929`, `:1111-1114`) — already aria2-equivalent, keep |
| Fresh-peer rescue exists | `tree_metadata.c:1285-1303` (`tree_metadata_fresh_peer_retries`, plan/optimise stage 3) — runs **before** the bloom strike; unchanged by this plan |
| Dead single-peer path | `tree_fetch_metadata_from_peer` (`:513`) has no callers in `src/` — do not refactor it in these stages |
| App key allow-list | `config/CrawlerKeys.kt:47-55` `KNOWN_KEYS` Thread-trees group; `tree_*` keys are advanced-textarea only (NOT form-managed); tests `config/CrawlerKeysTest.kt`, root `CrawlerConfigTest.kt` |
| Config template | `app/src/main/assets/dht_crawler/config.ini.default:118-124` (tree_* block) |
| Vendored-change route | crawler `src/` changes are **direct commits in the vendored repo** (sanctioned precedent: classifier stage 5, optimise stages 2-3; parent commit records the gitlink). `patches/` is only for `dht_crawler/lib/` submodule fixes. Never edit `lib/` |
| Baselines (2026-10-16) | parent repo `c5a8fea` (clean; only untracked `plan/optimise*`), vendored crawler `077bada` clean. **Record the current `metadata_success_rate_percent` before Stage 1** — every stage compares against the previous stage's number |

## Order and effort (rough)

| Stage | File | Where | Effort | Blocking wait |
|---|---|---|---|---|
| 1 Timeout key | `stage-01-metadata-timeout.md` | crawler + app | S | native rebuild + phone on USB |
| 2 Piece aggregation | `stage-02-piece-aggregation.md` | crawler (+ gitlink) | L | native rebuild + phone on USB |
| 3 Re-ask timer | `stage-03-piece-reask.md` | crawler (+ gitlink) | M | native rebuild + phone on USB |

Stages must land in order: 2's deadline comes from 1's tree field, and 3's scheduler is 2's
scheduler plus timestamps.

## Deliberately deferred (do not fold into these stages)

- **uTP (BEP 29) transport** and IPv6 peer preference — separate experiment after measurements.
- **Raising `MAX_PARALLEL_PEERS` above 4** or making the outstanding cap / re-ask interval
  config keys — measure with the defaults first.
- **Per-piece verification before full assembly** — impossible per BEP 9 (SHA-1 only over the whole
  info dict); do not attempt.
- **Retuning `tree_metadata_fresh_peer_retries`, worker counts, or the window** — after the
  ≥ 1 h measurements from these stages.
- **Cross-worker (per-tree) fetch dedup** — stays per-fetch-entry; revisit only if duplicate
  infohash entries in the peers queue measurably waste fetches.

## Global constraints (all stages)

- Build env: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`, `ANDROID_HOME=$HOME/Android/Sdk`,
  `ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/android-ndk-r27`. Build: `./gradlew installDebug`.
- Tests: `./gradlew :app:testDebugUnitTest`. No androidTest; JUnit4 + Robolectric + Mockito.
- Submodules before any native build: `git submodule update --init --recursive` inside
  `app/src/main/cpp/dht_crawler/`.
- Never edit `dht_crawler/lib/` or tarball-extracted transmission sources.
- Vendored `config.ini` (live) contains backup credentials — never print, commit, or copy its
  values; the sanitized template is `app/src/main/assets/dht_crawler/config.ini.default`.
- Two commits per crawler-touching stage: one in the vendored repo, then one in the parent repo
  recording the gitlink + app-side changes together.
- On-device checks: `/stats` via `adb forward tcp:18081 tcp:8081` then
  `curl http://127.0.0.1:18081/stats`; config via
  `adb shell run-as com.torrent.app cat files/crawler/config.ini`.