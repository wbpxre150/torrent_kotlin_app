# Stage 10 — dht_crawler & rclone UI panels

**Builds on:** Stage 09 (dht_crawler cross-compiled, supervised, running).
**De-risks:** the requirement that the crawler and backups are configured from the same
single web surface as every other service.

## Goal

Wire up the two stub panels introduced in Stage 07 now that the crawler and rclone binaries
exist. No new backend logic — just expose what Stage 09 built through the control UI.

## What gets built

1. **dht_crawler config panel** (replaces Stage 07 stub):
   - Edit the relevant subset of `config.ini` (~130 keys; surface the important ones):
     resource-tuning keys (`num_trees`, `tree_*_workers`) prominently — defaults are heavy
     (~4,500 threads); ports (HTTP port 8080 is compile-time; present read-only).
   - Start / stop / restart the crawler from the UI.
   - Expose maintenance actions: `--compact`, `--check-database`, `--recover-database`,
     `--rebuild-bloom-filter`.
   - Link through to the crawler's own HTTP search UI at `:8080`.
2. **rclone / backup panel** (replaces Stage 07 stub):
   - Remote setup: write `rclone.conf` managed by the control server.
   - Backup schedule (interval), backup-now button, restore-now button, last-run status.
3. **Reverse-proxy / entry-point update:** wire the previously-stubbed `:8080` link so it
   actually reaches the crawler's search UI.
4. **First-run wizard update:** add crawler config and rclone remote setup steps (or offer
   them as optional post-wizard steps if the user skips).

## Key decisions & gotchas

- Apply the same config ownership rule from Stage 07: control server is the single writer
  of `config.ini` and `rclone.conf`; validate before applying; keep last-known-good copy.
- The crawler's HTTP port (8080) is baked at compile time — display it read-only with a
  note explaining why it can't be changed without a rebuild.
- rclone secrets in `rclone.conf` — manage with rclone's own obscure mechanism; do not
  echo them into the page.

## Out of scope

- The backup *execution* logic lives in Stage 11 (rclone backups); this stage only builds
  the UI surface for configuring it.

## Acceptance test

- The crawler panel shows live status, allows config edits that survive a crawler restart,
  and the maintenance actions complete without error.
- The `:8080` link in the control UI reaches the crawler search page.
- The rclone panel accepts a remote definition, writes `rclone.conf`, and the backup-now
  button (from Stage 11) can use it successfully.
