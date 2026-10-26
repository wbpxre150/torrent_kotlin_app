# Stage 5: throttle bloom persistence (consequence guard)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`**, submodule `app/src/main/cpp/dht_crawler`.
> Index/design: `plan/crawler-getpeers-throughput/README.md`.
> Depends on stage 2 (its throughput increase is what makes this necessary).
> **Ship only if the measured flush rate rises** — see "Trigger".

## Goal

Stop rewriting 108 MB of bloom filters on every batch flush.

`batch_writer_flush()` saves **both** filters after every flush that wrote rows
(`batch_writer.c:982-1001`): `data/bloom.dat` and `data/failure_bloom.dat`, 53.9 MB each, measured on the
phone. With `batch_size=500` and 4,920 torrents/h that is ~10 flushes/h → **~1.08 GB/h of flash writes**
for a filter whose contents change by a few hundred bits per flush. After stage 2 raises the stored rate
2–4×, the same code writes **2–4 GB/h**.

Throttle the *disk write* to at most one per `bloom_persist_interval_sec` (default 300). The in-memory
add stays on every flush — that is what keeps dedup correct within a run.

## Trigger

Measure before implementing:

```bash
adb shell run-as com.torrent.app stat -c '%y %n' \
  /data/data/com.torrent.app/files/crawler/data/bloom.dat \
  /data/data/com.torrent.app/files/crawler/data/failure_bloom.dat
# sample the mtime twice, 10 minutes apart, and count distinct mtimes / torrents_last_hour
```

- **flushes/h ≥ 15** → implement and ship.
- **flushes/h < 15** → still implement (it is a ~20-line change and it removes a growing cost), but it
  becomes the lowest-priority stage and may be batched with the next maintenance build.

Record the measured flush rate in `plan/crawler-getpeers-throughput/measurements.md` either way.

## Context (measured — do not re-derive)

- Filter files: 53,915,997 bytes each (`bloom.dat`, `failure_bloom.dat`), 30 M capacity, 0.001 error rate.
- Save points today: `batch_writer.c:990` (success bloom) and `:997` (failure bloom) on every flush;
  `main.c:351` after `--rebuild-bloom-filter`; `main.c:900` at shutdown; `supervisor.c:984` when the
  supervisor stops (failure bloom). **The throttled path must not disturb the last three.**
- `batch_writer_flush()` is also called from `batch_writer_add()` when the batch is full
  (`batch_writer.c:896`) and from the 300 s flush thread (`batch_writer.c:71-99`), so the save happens on
  both paths.
- Losing bloom state is safe: `torrents.info_hash` is `UNIQUE` and `database_check_exists()` /
  `database_has_infohash()` gate inserts, so a stale bloom only costs a duplicate metadata fetch, never a
  duplicate row. A stale *failure* bloom only gives already-failed hashes another chance — which is the
  behaviour the two-strike policy already permits.
- `batch_writer_shutdown()` calls `batch_writer_flush()`; the final save must not be throttled away.

## Scope

### 1. Config (`include/config.h`, `src/config.c`)

New key `bloom_persist_interval_sec`, default **300**, clamp **30 – 3600** (`0` = save on every flush,
i.e. today's behaviour, for debugging). Parse next to `bloom_persist`, clamp with a `LOG_WARN`, print in
`config_print()`. Add it to `CrawlerKeys.kt` and `app/src/main/assets/dht_crawler/config.ini.default`
(same two-file rule as stage 3).

### 2. Throttle in `batch_writer.c`

- Add `time_t last_bloom_save;` and `int bloom_persist_interval_sec;` to `struct batch_writer`
  (`batch_writer.c:14-67`) and a `bool bloom_save_force;`.
- Plumb the interval in through `batch_writer_init()` (new parameter) or a setter next to
  `batch_writer_set_bloom()` — a setter is less churn: `batch_writer_set_bloom_persist_interval(writer, sec)`.
  Wire it from `main.c` where `batch_writer_set_bloom()` / `batch_writer_set_failure_bloom()` are called.
- In `batch_writer_flush()`, replace the two unconditional saves with:

  ```
  in-memory bloom_filter_add() for every written hash      /* unchanged, every flush */
  if (bloom_save_force || interval == 0 || now - last_bloom_save >= interval) {
      save bloom.dat (if set);
      save failure_bloom.dat (if set);
      last_bloom_save = now;
      bloom_saves_total++;
  }
  ```

- `batch_writer_shutdown()`: set `bloom_save_force = true` **before** its final flush, so the last state
  always reaches disk. Clear it after.

### 3. Counters (`/stats`)

Expose under the existing supervisor/aggregate object: `bloom_saves_total` and
`bloom_bytes_written_total` (saves × filter file size, computed from the bloom stats already available via
`bloom_filter_stats()`). This is the only way to verify the exit criterion without a flash-write probe.

## Constraints

- Never skip the in-memory `bloom_filter_add()` — dedup correctness is not negotiable.
- The final save on shutdown and the save after `--rebuild-bloom-filter` must always happen.
- Do not change filter capacity, error rate, file format, or paths. An existing `bloom.dat` must load
  unchanged.
- Do not edit `dht_crawler/lib/` or `dht_crawler/patches/`.

## Pitfalls

1. **The force flag must be set before the final flush, not after.** `batch_writer_shutdown()` flushes
   internally; setting the flag afterwards writes nothing and the run ends with a stale filter.
2. **`flush_in_progress` / `backup_in_progress` re-entrancy.** `batch_writer_flush()` can return early
   (another flush running, backup hold). Only update `last_bloom_save` when the save actually ran,
   otherwise a burst of early-returning flushes pushes the next save out indefinitely.
3. **Clock changes.** `time(NULL)` can jump. Use a monotonic clock (`clock_gettime(CLOCK_MONOTONIC)`) for
   the interval comparison, or accept the drift and document it — do not use a realtime delta as a
   timeout without a clamp.
4. **Two filters, one timestamp.** They are saved together today; keep it that way. Saving them on
   different schedules doubles the write bursts for no benefit.

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
2. With `bloom_persist_interval_sec = 300`: over a 30-minute run, `bloom_saves_total` grows by
   **≤7** (it was ~5 per 30 min at the baseline rate and would be 2–4× that after stage 2), while
   `torrents_last_hour` and the dedup behaviour are unchanged.
3. `bloom_persist_interval_sec = 0` reproduces today's behaviour exactly (saves == flushes).
4. Restart after a 30-minute run: the daemon loads both filters, logs the item counts, and
   `torrents_indexed` does not jump by a duplicate-inflated amount (spot-check `SELECT COUNT(*)` before and
   after 10 minutes of running — growth rate must match `torrents_last_hour`, not exceed it).
5. Shutdown (`--rebuild-bloom-filter` then a normal stop) still writes both filters: file mtimes update
   after the process exits.
6. No `WARN`/`ERROR` about bloom save failures in `logs/dht_crawler.log`.

## Commits

1. Inside the submodule:
   `git -C app/src/main/cpp/dht_crawler commit -am "crawler: throttle bloom persistence to one save per interval"`
2. Parent (allow-list + template + gitlink):
   `git add app/src/main/cpp/dht_crawler app/src/main/java/com/torrent/app/config/CrawlerKeys.kt app/src/main/assets/dht_crawler/config.ini.default && git commit -m "app: crawler get_peers throughput stage 5 — bloom persist throttle"`
