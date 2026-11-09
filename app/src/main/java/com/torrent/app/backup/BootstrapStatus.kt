package com.torrent.app.backup

/**
 * Lifecycle state of the BEP51 bootstrap-cache download (the settings
 * websocket frame's `bootstrap` family, plan Stage 4).
 */
enum class BootstrapState { IDLE, RUNNING, OK, SKIPPED, FAILED }

/**
 * Observable status of the BEP51 bootstrap-cache download. It only runs once,
 * at crawler start, and [com.torrent.app.backup.Bep51BootstrapDownloader]
 * never throws, so this is deliberately small and honest: a state, a terminal
 * message and the run's timestamps — never an invented percentage.
 *
 * Memory-only, like every other progress status: an app restart resets it to
 * [BootstrapState.IDLE].
 */
data class BootstrapStatus(
    val state: BootstrapState = BootstrapState.IDLE,
    val message: String = "",
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)
