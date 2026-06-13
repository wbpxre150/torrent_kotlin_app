# Stage 09 — Resilience, 24/7 survival & startup

**Builds on:** all prior stages.
**De-risks:** the "runs forever like Termux did, restarts on crash, survives reboot"
requirement against modern Android's aggressive background restrictions.

## Goal

Make the whole system genuinely always-on: start on boot, survive Doze/OEM killers, recover
every service from crashes, and work around the Android 15 foreground-service timeout.

## What gets built

1. **Start on boot:** `RECEIVE_BOOT_COMPLETED` receiver → start the foreground service →
   re-grant SAF access from the persisted permission (Stage 03) → bring up all configured
   services. Handle "USB not yet connected at boot" gracefully (wait/retry).
2. **Android 15 `dataSync` ~6h/24h cap mitigation (decide here):**
   - For an always-on torrent/seed/crawl box, `dataSync`'s timeout will stop the service.
   - **Recommended (sideload):** declare **`FOREGROUND_SERVICE_SPECIAL_USE`** with a
     justification `<property>`, avoiding the timer. Document that Play would scrutinize
     this — acceptable since distribution is sideload-only (Stage 00 §7).
   - Implement `onTimeout()` handling defensively regardless (restart the FGS) as a backstop.
3. **Wake/wifi locks tuned:** `PARTIAL_WAKE_LOCK` held while running; `WifiLock`
   (`WIFI_MODE_FULL_HIGH_PERF`) while serving/downloading; release when idle/stopped to be a
   good citizen. Reacquire on resume.
4. **Battery-optimization exemption:** prompt the user to disable battery optimization for
   the app (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` / request dialog), with in-UI
   explanation. Required for reliable 24/7 operation.
5. **OEM survival guidance:** in-app instructions (and deep links where possible) for
   Samsung/Xiaomi/Huawei/OnePlus "don't optimize / allow autostart" settings
   (dontkillmyapp-style). No API fixes this; the user must whitelist.
6. **Crash recovery, end to end:**
   - Per-daemon supervisor restart (Stage 02) with backoff and crash-count surfacing.
   - Watchdog: if a service is configured-on but dead beyond N retries, alert in the UI /
     notification.
   - On app/process death, boot/restart brings everything back from persisted config and
     transmission resume data (USB payloads intact, resume state internal).
   - Stale lock/pid/`.part` cleanup on restart.
7. **Log rotation & diagnostics:** cap per-service log size; a "collect diagnostics" action
   bundling logs + status for troubleshooting.
8. **Health checks:** periodic liveness pings (crawler `:8080`, transmission RPC, FTP bind,
   rclone rcd) feeding the dashboard; restart on hung-but-alive processes.

## Key decisions & gotchas

- **The Android 15 FGS-timeout choice (`specialUse` vs living with `dataSync`) is the
  critical resilience decision** — settle it here; it affects the manifest and possibly the
  whole "always-on" promise.
- No amount of code beats **OEM killers** — the user-whitelisting step is mandatory, not
  optional.
- After reboot, **SAF permission must be reclaimed** from the persisted grant before any
  service that needs USB starts; sequence startup accordingly.
- Don't hold wakelocks when fully idle with nothing configured-on (battery citizenship), but
  do hold them throughout active download/seed/serve.

## Acceptance test

- Reboot the phone (USB attached): the service auto-starts, reclaims USB, and all configured
  services come up without manual intervention.
- Kill individual daemons and the whole app: each recovers (daemon via supervisor; app via
  restart) and resumes downloads/serving.
- Leave running 24+ hours with the screen off on wifi: services stay up past the old
  `dataSync` window (confirming the timeout mitigation), downloads/seeds/serves continue.
- Battery-optimization exemption and OEM-whitelist steps are presented and, once applied,
  the app is no longer killed in background.
