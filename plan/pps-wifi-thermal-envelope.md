# PLAN_pps_wifi_thermal — find the UDP pps at which wifi actually disconnects, and prove the movie-kind LLM never thermally throttles

Status: **proposed — not started.** Follow-up to the patches 20–23 run (`plan/getpeers-metadata-results.md`),
which established that get_peers throughput is **deadline-bound**, not discovery- or rate-limiter-bound.

## Why this plan exists

`udp_rate_limit_pps` was introduced **because wifi disconnected constantly** without it. It now sits at **3000**,
which has held for **days of continuous operation**, with the crawler measuring 2,512–2,640 pps and 0–22,942
throttled acquires per 30-minute window. What is unknown is the *ceiling*: 3000 may be far below the point where
the link actually breaks, in which case the throttle is discarding throughput for nothing. Nothing has ever
counted dropouts, so 3000's safety is an operational belief rather than a measurement — Stage 1 fixes that, and
the ladder starts at **4000**.

The rate limiter is a **process-wide token bucket that sleeps rather than drops** (`src/udp_rate_limit.c:174-192`,
single call site `src/tree_socket.c:156-160` before a per-tree `send_lock`), so when it binds it lengthens lookups
instead of losing packets — which is why the ceiling was never visible as packet loss.

Two hard facts that shape the experiment:

1. **Burst is capped independently of pps.** `burst = pps/10`, clamped **8..128 packets**
   (`src/udp_rate_limit.c:57-63`). Above **1280 pps the burst allowance is already saturated at 128**, so each
   +1000 rung raises the *sustained* rate only. Do not expect burst-driven gains at any rung.
2. **The hard ceiling is 20000 pps**, clamped twice — config parse (`src/config.c:299-305`) and
   `udp_rate_limit_init` (`include/udp_rate_limit.h:11-13`, `src/udp_rate_limit.c:50-55`). A mistyped value cannot
   run away.

## Definitions

**WIFI BREAK (the verdict signal) = a disconnect.** Concretely, in the instrumented counter: a gate-down edge
lasting **≥ 2000 ms** (`edges_over_2s_total` increments). Rationale: the app pauses the crawler whenever the
default network stops being wifi (`WifiGateMonitor` → `DaemonService.kt:276,303,748,785,806,856,877,1341`), and a
sub-2-second capability flap is not a disconnect — counting those would fail rungs on noise.

Corroboration, recorded but **not** a stopping rule on its own:
- a `dumpsys wifi` disconnect/roam record inside the window (the link-level truth, used to confirm a counter edge);
- **(A) crawler stall** — `Δudp_sends_total ≈ 0` and `Δget_peers_lookups_total ≈ 0` for ≥3 consecutive 5 s samples
  while `uptime` advances. This is the *consequence* of a dropout, so it corroborates; it cannot substitute.

**THERMAL BREAK** = at any rung `dumpsys thermalservice` Thermal Status reaches **≥3 (SEVERE)**, or
`movie_kind.mean_us_per_item` rises **>20 %** above the rung-0 value. Stops the ladder immediately — pps is not
to be bought with heat.

**MOVIE-KIND THROTTLE** = `/stats movie_kind.thermal_pauses` must stay **0** at every rung. This is *structurally*
0 for inline ingest classification (the thermal gate lives in the backfill engine, `src/movie_backfill.c:57-63`,
and the backfill daemon is not started in production), so a non-zero value is a **regression alarm**. The real
movie-kind thermal observable is `mean_us_per_item` — a throttled CPU inflates it.

## Baseline (measured 2026-11-17, patches 20–23 installed)

| quantity | value | source |
|---|---|---|
| `udp_rate_limit_pps` | **3000** | live `config.ini` |
| safe floor | **3000 — days of operation** | operator experience: no dropouts since the throttle was introduced; *not* counter-measured |
| measured pps | 2,512–2,640 | windows W0–W5 |
| throttled acquires / 30 min | 0–22,942 | same |
| `movie_kind.thermal_pauses` | **0** | `/stats movie_kind` |
| `movie_kind.mean_us_per_item` | **232.8 ms** | `/stats` (v13 G6 bench median 188.8 ms) |
| `movie_kind_thermal_backoff_c` | 82 | live `config.ini` (backfill engine only) |
| thermal status | **2 (MODERATE)** | `dumpsys thermalservice` — *not 0 at baseline* |
| CPU zones | 58.5–72.8 °C | same |
| battery | 38.5 °C | `dumpsys battery` |
| link | RSSI −60 to −65 dBm, 5745 MHz, 288–432 Mbps, SSID `Aussie Broadband 7594` | `dumpsys wifi` |
| **wifi dropouts** | **unmeasured — nothing counts them** | this plan's Stage 1 |

**Thermal status 2 is the resting state at 3000 pps.** "Keep throttles at zero" therefore means: movie-kind
pauses stay 0, `mean_us_per_item` stays within +20 %, and the device status does not escalate. Do not write a
criterion the device cannot meet before the experiment starts.

## Stage 1 — Instrument the dropout counter (build + install)

**Scope.** Kotlin only. No crawler patch, no C change.

- **`app/src/main/java/com/torrent/app/net/WifiGateStats.kt` (NEW, pure, no Android deps — unit-testable as plain
  JUnit4, per house style):** `fun onSample(wifi: Boolean, nowMs: Long)` updating
  `edgesTotal` (true→false edges), `edgesOver2sTotal` (edges whose down duration reached 2000 ms, incremented at
  the false→true transition), `downMsTotal`, `maxDownMs`, `lastDropoutAtMs`, and `downSinceMs`. Repeated `false`
  samples must not re-count an edge; a `false`→`true` transition closes the open edge. Include
  `fun noteDeliberateStop(nowMs: Long)` so shutdown is not recorded as a dropout.
- **`WifiGateMonitor`**: own a `WifiGateStats`, feed it from `onCapabilitiesChanged` / `onLost` with
  `SystemClock.elapsedRealtime()`, expose it (e.g. `val gateStats: WifiGateStats`), and call `noteDeliberateStop`
  from `stop()` before setting the state false.
- **`ControlServer`**: extend `buildStatusJson()` (served at `get("api/status")`, `ControlServer.kt:240`) with
  `wifiDropoutsTotal`, `wifiDropoutsOver2sTotal`, `wifiDownMsTotal`, `wifiMaxDownMs`, `wifiDownNowMs`,
  `lastWifiDropoutAt` — injected as a `() -> …` lambda like the existing `wifiIp` / `crawlerPausedNoWifi` params
  (`ControlServer.kt:40,66`), wired in `DaemonService`.
- **`app/src/test/java/com/torrent/app/net/WifiGateStatsTest.kt` (NEW)**: edge counting, no double-count on
  repeated false, duration accumulation, the ≥2 s classification, deliberate-stop suppression.

**Why an app-level counter is the right instrument:** the ladder restarts the **crawler** on every settings apply,
which zeroes every crawler counter — but the app process keeps running, so the gate counter keeps counting across
rungs. Take a baseline reading at the start of each window and diff.

**Build/install** (JDK 21 mandatory):
`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME="$HOME/Android/Sdk"
ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"`, then `./gradlew :app:testDebugUnitTest`,
`./gradlew :app:assembleDebug`, and
`adb -s 'adb-b078fce-dgRfE0._adb-tls-connect._tcp' install -r app/build/outputs/apk/debug/app-debug.apk`.
adb trap: plain serial `b078fce` fails; use the mDNS transport string.
**Validation:** counter present in `api/status`; toggle wifi off/on by hand once and watch `wifiDropoutsTotal` go
+1 with a plausible `wifiDownMsTotal`; confirm a deliberate app stop does not increment it.

## Stage 2 — Ladder: +1000 pps per rung starting at 4000, until a disconnect

**Safe floor is 3000, established by operation rather than by a window.** The device ran at
`udp_rate_limit_pps = 3000` for days without the dropouts that motivated the throttle, so 3000 is not re-validated
and the ladder starts at **4000**. Caveat to record: no dropout counter existed during those days, so 3000's
safety is an operational observation, not a measured one — the counter's first baseline is taken at the first rung.

**Scope.** Device settings only (`udp_rate_limit_pps` via the dashboard `advancedOverrides` textarea).

**Change.** One variable per rung: **4000 → 5000 → 6000 → …**, capped at 20000. Stop at the first rung that
disconnects. Per rung:

1. Apply `udp_rate_limit_pps = <rung>`, keeping every other advanced key as-is (`tree_parallel_peers = 2`,
   `tree_metadata_peer_blacklist_ttl_sec = 600`, the four patch-19 knobs at 0, `tree_metadata_timeout_ms = 60000`).
2. Settle **5 min**; confirm `/stats udp_rate_limit_pps` echoes the rung and `metadata_peer_fail` still shows
   `enabled:true, capacity:131072, ttl_sec:600`.
3. Snapshot the gate counter baseline, then run a **10-minute** window at 5 s sampling (`--minutes 10`). Ten
   minutes, not sixty: this is a break *search*; Stage 3 proves stability.
4. Record: dropouts (all three counters), pps achieved vs cap, throttled count and share, lookups/s, yield,
   metadata/s, mean lookup ms, `deadline_exits_total`/lookups, threads, RSS, thermal status + max CPU zone +
   battery °C, `movie_kind.thermal_pauses`, `movie_kind.mean_us_per_item`.
5. **Stop** on the first rung with `edgesOver2s > 0` (a disconnect) or a THERMAL BREAK. Corroborate a disconnect
   with a `dumpsys wifi` record; if the counter fired but no link record and no (A) stall exists, record it as a
   **suspected flap** and repeat that rung once before accepting it as the break.

**If the first rung (4000) disconnects**, the answer is immediately "3000 is the ceiling": stop, and Stage 3's
stability hour runs at 3000 with no dial-back. Do not climb further.

**If the ladder reaches 20000 without a disconnect that is a finding**, not a failure: the limit is not the
network, and 3000 was throttling away headroom for no reason. Record it and take Stage 3's stability hour at the
highest rung reached.

## Stage 3 — Dial back 500 and prove one hour of stability

From the failing rung `R`, set `udp_rate_limit_pps = R − 500` (break at 4000 ⇒ 3500; break at 5000 ⇒ 4500). If the
ladder never broke, use the highest rung reached and say so explicitly.

1. Apply, settle 5 min, confirm the echo and snapshot the gate counters.
2. Run **one 60-minute** window at 5 s sampling.
3. Acceptance — **all** must hold:
   - **zero** disconnect edges (`edgesOver2s` unchanged) across the hour;
   - pps achieved ≥ **80 %** of the cap;
   - throttled acquires **< 1 %** of sends;
   - lookups/s and metadata/s **not lower** than the 3000-pps baseline windows (W0/W3 range);
   - 64/64 trees with no respawn churn;
   - `movie_kind.thermal_pauses` **0**, `mean_us_per_item` within **+20 %** of 232.8 ms;
   - thermal status ≤ 2, no zone more than ~5 °C above the first rung's maximum;
4. Any failure ⇒ dial back another 500 and repeat the hour. **Cap: 2 dial-back attempts**, then record the highest
   value that passed a full hour, or fall back to 3000 with the evidence.

## Stage 4 — Record

Write `plan/pps-wifi-thermal-results.md`: the rung table (disconnects per rung with the three counters), which
signal fired at the break, the dial-back arithmetic, the stability hour, the thermal rows, and the **attribution** —
phone wifi chip, AP, or not-the-network — with the link evidence (RSSI, link speed, channel, AP SSID) and whether a
`dumpsys wifi` record corroborated the counter. Update `AGENTS.md`'s rate-limit sentence if the shipped value
changes, and add the answer to `plan/getpeers-metadata-results.md`'s dead-ends table so it is not re-litigated.

## Exit criteria

1. `api/status` publishes the wifi-gate dropout counters, and a hand-toggled wifi off/on produces exactly one edge
   with a plausible duration (Stage 1 validation).
2. The break point is identified by a **disconnect** (`edgesOver2s` increment), corroborated by a `dumpsys wifi`
   record or an (A) stall, and the rung below it did not disconnect (3000 counts as the rung below if 4000 fails).
3. The dialed-back value runs a full hour with zero disconnect edges and meets every Stage-3 acceptance item
   (if the first rung 4000 disconnected, that hour runs at 3000 instead).
4. `movie_kind.thermal_pauses` was **0** at every rung and `mean_us_per_item` stayed within +20 % of 232.8 ms; no
   rung reached thermal status ≥3.
5. Thermal status, max CPU zone and battery °C are recorded for every window; the final `udp_rate_limit_pps` is set
   on the device, echoed by `/stats`, survives a force-stop + relaunch, and the record states the attribution.

## Guardrails and traps

- **One variable at a time.** Only `udp_rate_limit_pps` moves. Keep `log_level = INFO` (DEBUG costs ~2.5 GB/h and
  would confound every thermal and throughput reading), `num_trees = 64`, `tree_get_peers_workers = 18`,
  `tree_bep51_workers = 3`, `tree_parallel_peers = 2`, `tree_metadata_peer_blacklist_ttl_sec = 600`.
- **Rate deltas only for crawler counters** — every apply restarts the crawler. The **gate counter is app-level**
  and survives those restarts, so diff it per window; but it resets on app restart (install, force-stop), so do
  not force-stop the app mid-experiment and re-baseline after any install.
- **A gate flap is not a disconnect.** Count edges and edges ≥2 s separately; a rung fails on the latter only.
  A brief roam or a mobile-data failover will produce a short edge — report it, do not fail on it.
- **`WifiGateMonitor.stop()` sets the state false** — feed the stats a deliberate-stop signal first or shutdown
  will look like a dropout.
- **Burst is already saturated** at 128 packets for pps ≥ 1280; if a rung shows no pps increase, check the
  achieved rate rather than assuming the bucket ignored the setting.
- **Never set `udp_rate_limit_pps = 0`** — 0 means passthrough/unlimited (`include/udp_rate_limit.h:43`). It would
  bypass the very protection this plan characterises.
- **Attribution honesty.** The crawler cannot distinguish "the AP is saturated" from "the phone's wifi chip is
  saturated". Record RSSI, link speed, channel and AP SSID per rung; if the evidence does not separate them, say
  the limit is unattributed.
- **Thermal baseline is not zero.** Status 2 is the resting state. Do not write a verdict requiring status 0.

## Install

Stage 1 needs one build + install (command above). Stages 2–4 are settings-only, applied through the dashboard
(`POST /settings/crawler`, `advancedOverrides`); no further install unless the counter needs a fix.
