package com.torrent.app.config

/**
 * The ordering every crawler start path must obey: **migration before config render before daemon
 * start**. The three steps are injected so the order is unit-testable without a service, a
 * `Context`, or a real crawler; [com.torrent.app.DaemonService.startCrawlerSupervisor] wires the
 * production callbacks in.
 *
 * The invariant:
 * 1. [prepare] stages the classifier model assets — including the `v\d+`-guarded sweep of stale
 *    generation dirs — and seeds/pins `config.ini`.
 * 2. [migrate] resets the adult threshold to the current generation's default and, when it did
 *    reset, re-renders `config.ini` BEFORE returning (`DaemonService.migrateClassifierVersion`).
 * 3. Only then may the daemon [start].
 *
 * Consequences: a new classifier generation never runs with the previous generation's thresholds,
 * and stale model dirs are already gone before the daemon resolves its model path.
 *
 * [com.torrent.app.DaemonService.runCrawlerApply] keeps its own explicit sequence instead of using
 * this class, because its gate can close after the render and return before any start.
 */
class CrawlerStartOrder(
    private val prepare: () -> Unit,
    private val migrate: () -> Boolean,
    private val start: () -> Unit,
) {
    /**
     * Runs the three steps in order. Returns the [migrate] result: `true` when the thresholds were
     * migrated, `false` when the sentinel already matched this build's model generation.
     */
    fun run(): Boolean {
        prepare()
        val migrated = migrate()
        start()
        return migrated
    }
}
