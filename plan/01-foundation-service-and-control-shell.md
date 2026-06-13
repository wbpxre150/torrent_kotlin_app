# Stage 01 — Foundation: app shell, foreground service & control web stub

**Builds on:** nothing (first build stage).
**De-risks:** the "stays alive with the screen off" runtime that every later stage relies on.

## Goal

Stand up the minimum app that can run forever in the background and expose a localhost web
page. No daemons yet — just the chassis.

## What gets built

1. **Android project skeleton** (Kotlin, single module, `minSdk` 26, `targetSdk` current).
   - ABI filters: `arm64-v8a` (plus `armeabi-v7a`/`x86_64` only if emulator testing is
     wanted).
   - `packaging { jniLibs { useLegacyPackaging = true } }` set now, so it's never
     forgotten (Stage 02 depends on it).
2. **Foreground service** (`DaemonService`) that:
   - Calls `ServiceCompat.startForeground(...)` with a persistent notification.
   - Declares a foreground service type in the manifest (start with `dataSync`; revisit in
     Stage 09 re: the Android 15 timeout).
   - Holds a `PowerManager.PARTIAL_WAKE_LOCK` and a `WifiManager.WifiLock`
     (`WIFI_MODE_FULL_HIGH_PERF`) while running; releases on stop.
   - Exposes start/stop and a simple state machine (STARTING / RUNNING / STOPPING).
3. **Control web server stub** (Ktor, CIO engine) bound to `127.0.0.1:8090` **and** the
   wifi interface, serving a single status page ("services: none yet") and a health
   endpoint. Protected by a generated auth token (shown in the notification / first-run
   screen).
4. **App entry UI**: a thin activity that, on first run, tells the user to open the
   settings web page (per the requirement) and starts the service. No real settings yet.
5. **Permissions wired in manifest:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
   `WAKE_LOCK`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_WIFI_STATE`,
   `ACCESS_NETWORK_STATE`. (USB/SAF permission flow comes in Stage 03.)

## Key decisions & gotchas

- **Set `useLegacyPackaging = true` now.** It's invisible until Stage 02, where its absence
  silently breaks native exec in release builds.
- **One process, one foreground service.** All daemons and Kotlin servers will be children
  of / coroutines within this service. Don't spread across multiple services.
- **Bind the control server to loopback + wifi IP, never `0.0.0.0` blindly**; require the
  auth token even on loopback (other apps share loopback).
- Notification channel + `POST_NOTIFICATIONS` runtime permission (Android 13+) or the FGS
  notification won't show.

## Out of scope (later stages)

- Spawning native binaries (Stage 02).
- USB / SAF (Stage 03).
- Real settings content & service controls (Stage 08).
- Boot-on-start, battery exemption, OEM guidance, Android 15 FGS-timeout fix (Stage 09).

## Acceptance test

- Install, launch, grant notification permission. App shows "go to settings page" message
  and starts the service.
- The control page loads from another device at `http://<phone-wifi-ip>:8090/` (with token).
- Turn the screen off for 30+ minutes; the service is still alive (notification present,
  page still responds). Wakelock held (verify via `dumpsys power` if available).
