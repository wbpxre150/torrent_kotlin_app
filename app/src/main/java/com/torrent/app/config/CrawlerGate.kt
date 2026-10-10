package com.torrent.app.config

/**
 * Pure dht_crawler gating and arg logic (plan 10 §1/§5).
 *
 * Kept free of Android dependencies so it is unit-testable in plain JUnit.
 * `DaemonService` uses it to drive the crawler's start/stop from the
 * `WifiGateMonitor` state, the same way the FTP gate works.
 */
object CrawlerGate {

    /**
     * Whether the crawler should be running right now: it must be [enabled]
     * and, when [wifiOnly], wifi must be up.
     */
    fun shouldRun(enabled: Boolean, wifiOnly: Boolean, wifiUp: Boolean): Boolean =
        enabled && (!wifiOnly || wifiUp)

    /**
     * The crawler process's CLI args derived from [s]: `--dont-check-database`
     * when the startup integrity check is skipped. The supervisor must be
     * (re)created whenever these args change.
     */
    fun crawlerArgs(s: CrawlerSettings): List<String> =
        if (s.skipIntegrityCheck) listOf("--dont-check-database") else emptyList()

    /**
     * Post-apply rollback trigger (plan 10 §1): the crawler exited at least once
     * within the watch window after the restart. `ProcessSupervisor` bumps
     * `restartCount` on every relaunch, and a healthy crawler never exits.
     */
    fun exitedDuringWatch(restartsAtApply: Int, restartsNow: Int): Boolean =
        restartsNow > restartsAtApply
}
