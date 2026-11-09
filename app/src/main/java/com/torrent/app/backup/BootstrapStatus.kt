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
 * message, the run's timestamps and the last placed cache's size — never an
 * invented percentage (the downloader buffers the whole body, so no
 * Content-Length is ever surfaced).
 *
 * Memory-only, like every other progress status: an app restart resets it to
 * [BootstrapState.IDLE].
 */
data class BootstrapStatus(
    val state: BootstrapState = BootstrapState.IDLE,
    val message: String = "",
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** True only while the download is on the wire. */
    val running: Boolean = false,
    /** Node count of the last successfully placed cache file. */
    val lastPlacedNodeCount: Int? = null,
    /** Size in bytes of the last successfully placed cache file. */
    val lastPlacedBytes: Long? = null,
    /**
     * Epoch millis of the last failed attempt. This doubles as the in-memory
     * retry gate ([Bep51BootstrapDownloader.shouldBootstrap]'s `lastFailureAt`):
     * a failure is not retried for at least
     * [Bep51BootstrapDownloader.FAILURE_RETRY_INTERVAL_MS].
     */
    val lastFailureAt: Long? = null,
)

/**
 * The single mutable holder of the bootstrap download's [BootstrapStatus]
 * (plan Stage 6). Owned and wired by `DaemonService` — the composition point
 * (AGENTS.md invariant 12) — which calls the `mark*` methods around the one
 * download it performs and hands [status] to `ControlServer` as the settings
 * frame's `bootstrap` family supplier.
 *
 * Plain Kotlin with no Android dependency (plain-JUnit testable); [clock] is
 * injectable so the timestamps are deterministic in tests. Every mutation is a
 * whole-snapshot [BootstrapStatus.copy] under [@Volatile], the same
 * best-effort-snapshot contract as the backup managers' `status` properties.
 */
class BootstrapStatusHolder(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    var status: BootstrapStatus = BootstrapStatus()
        private set

    /** A download attempt starts: RUNNING, with a fresh start stamp. */
    fun markRunning(): BootstrapStatus {
        val st = status.copy(
            state = BootstrapState.RUNNING,
            message = "downloading",
            running = true,
            startedAt = clock(),
            finishedAt = null,
        )
        status = st
        return st
    }

    /** The cache file was placed: terminal OK carrying its size. */
    fun markPlaced(nodeCount: Int, bytes: Long): BootstrapStatus {
        val st = status.copy(
            state = BootstrapState.OK,
            message = "placed $bytes bytes ($nodeCount nodes)",
            running = false,
            finishedAt = clock(),
            lastPlacedNodeCount = nodeCount,
            lastPlacedBytes = bytes,
        )
        status = st
        return st
    }

    /** The download was not attempted (cache already present): terminal SKIPPED. */
    fun markSkipped(message: String): BootstrapStatus {
        val st = status.copy(
            state = BootstrapState.SKIPPED,
            message = message,
            running = false,
            finishedAt = clock(),
        )
        status = st
        return st
    }

    /**
     * The attempt failed: terminal FAILED, and the attempt stamp doubles as the
     * retry gate. The crawler cold-starts either way — this is never fatal.
     */
    fun markFailed(message: String): BootstrapStatus {
        val now = clock()
        val st = status.copy(
            state = BootstrapState.FAILED,
            message = message,
            running = false,
            finishedAt = now,
            lastFailureAt = now,
        )
        status = st
        return st
    }
}
