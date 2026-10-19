package com.torrent.app

/**
 * Pure decision behind [DaemonService]'s "write then restart" setters
 * ([DaemonService.setCrawlerAdultMode], [DaemonService.setCrawlerSaveCategories]).
 *
 * The crawler reads `adult_mode` / `save_categories` from `config.ini` only at
 * process startup, so a wizard write to a crawler that is already up is
 * invisible to the running child until it is relaunched — on-device this left a
 * crawler running in `tag` mode while `config.ini` said `adult_mode=drop`, so
 * adult torrents kept being saved.
 *
 * Restart is therefore driven by the live supervisor state:
 *  - `STARTING` / `RUNNING` — the child is up (or coming up) with the old
 *    config: restart it so it re-reads `config.ini`.
 *  - `STOPPED` — nothing to restart. Never start from here: the first-run
 *    wizard writes these keys *before* [DaemonService.setCrawlerEnabled] /
 *    [DaemonService.startCrawlerSupervisor] launch the crawler, and those paths
 *    already render the write into the fresh launch.
 *  - `STOPPING` — a stop is in flight; let it finish and let the wifi-gate
 *    observer decide whether to start again, rather than fighting the stop.
 *  - no supervisor at all (service not composed yet) — nothing to restart.
 *
 * Kept free of Android dependencies so it is unit-testable in plain JUnit (like
 * [RollbackPolicy]).
 */
object CrawlerRestartPolicy {

    /** True when a running/starting crawler must be relaunched after a config write. */
    fun shouldRestart(state: ProcessState?): Boolean =
        state == ProcessState.STARTING || state == ProcessState.RUNNING
}
