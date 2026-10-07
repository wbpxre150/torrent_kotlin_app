# Stage 5: preallocation decision + instrumentation cleanup

Part of `TRANSMISSION_USB_SAF.md`. Requires stages 0–4. Read the **Conventions** in
`TRANSMISSION_USB_SAF_0.md`.

**Goal**: with all piece I/O off the session thread, measure `"preallocation": 0` (current)
against `"preallocation": 1` (sparse) and commit the better one. Turn down instrumentation
noise. Close out the plan.

**Exit criterion**: the preallocation setting is committed with its measurements recorded in
`TRANSMISSION_USB_SAF.md`, worker-thread `[DISKSLOW]` noise is reduced, and the master plan
status is updated.

---

## Background

- Preallocation runs inside the worker's file open (`tr_open_files::get`, during the first
  write job to a new file). On exFAT/FUSE, extending a file zero-fills it, so with mode 1 the
  first write to a new 1.8 GB file holds the single worker for minutes (zero-filling the whole
  file at once). With mode 0, every write past EOF zero-fills the gap up to that offset (the
  same total work, spread out but in random order).
- Neither mode can stall RPC any more. What differs: **seeding read latency** (High jobs wait
  while the worker is stuck in a long Low job), **download throughput**, and **disk usage of
  partly downloaded torrents** (mode 1 allocates every wanted file up front).
- Transmission values: `0` = off, `1` = sparse (`tr_sys_file_preallocate(..., SPARSE)`, falling
  back to writing the last byte + truncate), `2` = full. Don't test `2`: it writes every byte
  itself and is strictly slower here.

---

## Fixed decision rule (apply it, don't re-litigate)

Run the same measurement for mode 0 and mode 1, then choose:

1. If either mode makes RPC p99 ≥ 500 ms in any scenario, that's a bug from an earlier stage.
   Stop and report it instead of choosing.
2. Otherwise choose the mode with the lower **p99 worker read latency** in scenario 2 (seeding
   while downloading a new torrent).
3. If the two are within 20% of each other, keep **0** (no change, and partial torrents don't
   take their full size on disk).

---

## Steps

### 5.1 Measure worker read latency

Add a temporary log, guarded by a marker comment, in `tr_disk_io::read`'s session-thread
completion wrapper (stage 3): measure from enqueue (capture `steady_clock::now()` in `read()`)
to completion, and log `"[DISKREAD] ms={}"` at Warn when it's over 0 ms. That is every read,
so it's temporary: delete it in 5.3. Build and install.

### 5.2 Run both modes

For each mode (`TransmissionConfig.buildSettingsJson` in
`app/src/main/java/com/torrent/app/TransmissionConfig.kt`, the line
`append("  \"preallocation\": 0\n")`, set to `0` then `1`; rebuild and install for each):

1. Remove the test torrents and their data from the USB drive.
2. Scenario 2: seed one complete torrent to several peers, and at the same time add a new
   multi-GB multi-file torrent. Run `rpc_probe.sh` and `sample_threads.sh` for 15 minutes.
3. Record: RPC p50/p99/max, `[DISKREAD]` p50/p99/max (parse from the log), the average download
   rate over the 15 minutes (RPC `torrent-get` `downloadedEver` delta / 900 s), and the on-disk
   size of the new torrent's folder after 15 minutes vs its `haveValid`.

Put both rows in `## Measurements` in `TRANSMISSION_USB_SAF.md`, with a one-line note on which
mode won under the decision rule.

### 5.3 Commit the setting and clean up

- Set `"preallocation"` in `TransmissionConfig.kt` to the chosen value. If it changes to `1`,
  update any comment there that explains why it is `0` (commit `a3e9bd9` introduced 0).
- Delete the temporary `[DISKREAD]` log from 5.1.
- In `disk-timing.h`, set `OtherThresholdMs = 2000` (worker and verify threads). Keep
  `SessionThresholdMs = 100`: any session-thread disk op over 100 ms is still worth a warning.
- Keep the scripts in `tools/usb-io/`. Add a short `tools/usb-io/README.md` (5–10 lines: what
  each script does, its arguments, and what "good" looks like: RPC p99 < 500 ms, no 67/68 on
  `tr-session`).

### 5.4 Final regression pass

With the final build, run the master plan's full **Verification** list once:
1. scenario 1 (the original repro);
2. scenario 2;
3. scenario 3 (stop/remove/relocate under load);
4. kill the daemon mid-download, restart, run a full verify: no piece that was "have" before
   the kill turns out missing;
5. unplug the USB drive mid-write: the affected torrents show a local error, no crash, no hang,
   and RPC stays responsive. Plug it back in, then start and verify the torrent.

Report any failure to the user rather than fixing it ad hoc in this stage.

### 5.5 Close out the master plan

In `TRANSMISSION_USB_SAF.md`: change `**Status**` to `Implemented (stages 0–5)` and add a
short "Outcome" paragraph under it: the final RPC p99 vs the stage 0 baseline, the chosen
preallocation mode, and the known remaining session-thread disk calls (startup
`load_checked_pieces` stats, `is_new_torrent_a_seed` piece-0 check, `.part` rename, `find_file`
from RPC).

Commit: `feat(transmission): stage 5 preallocation decision and cleanup`.
