package com.torrent.app.config

object TransmissionApplyMode {
    /**
     * Fields whose changes require restarting the daemon to persist.
     *
     * These are the RPC credentials/port and data root that transmission cannot change live, plus the
     * four speed limits: transmission only writes `settings.json` on a clean exit, so a speed-only
     * save must take the restart branch to rewrite `settings.json` before the daemon reloads it.
     */
    fun needsRestart(before: AppConfig, after: AppConfig): Boolean =
        before.transmission.username != after.transmission.username ||
            before.transmission.password != after.transmission.password ||
            before.transmission.peerPort != after.transmission.peerPort ||
            before.transmission.speedLimitDown != after.transmission.speedLimitDown ||
            before.transmission.speedLimitUp != after.transmission.speedLimitUp ||
            before.transmission.altSpeedDown != after.transmission.altSpeedDown ||
            before.transmission.altSpeedUp != after.transmission.altSpeedUp ||
            before.dataSubpath != after.dataSubpath
}