# Stage 09 — Unified settings & service control web UI

**Builds on:** every prior stage (it configures and controls them all).
**De-risks:** the requirement that *everything* is configured from one web page, and that
each service can be restarted after config changes.

## Goal

Flesh out the Stage 01 control server into the single web surface that configures every
component, shows status, and starts/stops/restarts each service — plus the first-run wizard.

## What gets built

1. **First-run wizard** (the requirement: "tell the user to set everything up"):
   - Grant USB drive (Stage 03), choose download root, set Transmission RPC user/pass,
     FTP creds, rclone remote, backup schedule. Optionally import an existing `data/` dir.
   - Until completed, the landing page is the wizard.
2. **Per-service settings panels**, each editing the real config and applying it:
   - **dht_crawler:** edit `config.ini` (subset of its ~130 keys that matter); restart to
     apply; expose maintenance actions (`--compact`, `--check-database`,
     `--recover-database`, `--rebuild-bloom-filter`). **Note:** the HTTP port (8080) is
     compile-time (`http_api.c`/`http_api.h`), so it is *not* a live setting unless the
     crawler is patched to read it from config — present it read-only or behind a "requires
     rebuild" caveat. Surface the mobile resource-tuning keys (`num_trees`, `tree_*_workers`)
     prominently since defaults are heavy (~4,500 threads).
   - **transmission:** prefer **live RPC** changes (speed limits, peer port, paths);
     fall back to `settings.json` rewrite + restart for settings RPC can't change. Link
     through to its own web client / the user's remote.
   - **FTP:** port, auth/anonymous, wifi-only toggle, exposed root.
   - **rclone:** remote setup, schedule, backup-now / restore-now, last-run status.
   - **Storage:** show USB status/free space, re-grant drive, download-root selection.
3. **Config ownership & apply flow** (the cross-cutting rule): the control server is the
   **single writer** of each config file. Apply = stop affected daemon → atomic write (temp
   + rename) → restart (or push via RPC). Show success/failure.
4. **Status dashboard:** per-service state, PID, uptime, restart count, recent logs (from
   Stage 02 ring buffers), USB + wifi state, disk/free space, current downloads (via
   Transmission RPC summary).
5. **One entry point:** reverse-proxy / link out to dht_crawler `:8080` and Transmission RPC
   `:9091` so the user has a single LAN URL.
6. **Auth:** token/login on the control server; manage the other services' credentials from
   here.

## Key decisions & gotchas

- **Validate config before applying**; never leave a daemon with a broken config that
  crash-loops. Keep a last-known-good copy to roll back.
- Some transmission settings need restart (peer port, RPC bind) vs live RPC — encode which
  is which.
- Don't expose secrets in plaintext in the UI more than necessary; the page is LAN-reachable.
- Keep the UI dependency-light (server-rendered or a tiny SPA) — it runs on-device.

## Out of scope (later stages)

- Boot-start, battery/Doze, OEM survival (Stage 10) — though their toggles/guidance links
  live in this UI.

## Acceptance test

- A fresh install lands on the wizard; completing it brings all services up configured.
- Changing a setting in each panel (e.g. transmission speed cap, FTP port, crawler config,
  backup interval) applies and persists across a restart.
- A deliberately bad config is rejected/rolled back instead of crash-looping the daemon.
- All status (services, downloads, USB, wifi, backups) is visible on one page.
