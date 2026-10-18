package com.torrent.app

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A dht_crawler maintenance action (plan 10 §4). Each maps to a single CLI
 * flag understood by the crawler's `main.c`. [needsSpaceCheck] marks the
 * actions that rewrite the database and therefore need roughly a DB-sized
 * amount of free internal space before they can run.
 */
enum class MaintenanceAction(val route: String, val flag: String, val needsSpaceCheck: Boolean) {
    CHECK_DATABASE("check-database", "--check-database", false),
    COMPACT("compact", "--compact", true),
    RECOVER_DATABASE("recover-database", "--recover-database", true),
    REBUILD_BLOOM_FILTER("rebuild-bloom-filter", "--rebuild-bloom-filter", false),
    CLEAR_FAILURE_FILTER("clear-failure-filter", "--clear-failure-filter", false),
    // The purge re-scans the database, runs the language-filter pass and then
    // auto-compacts (VACUUM INTO), so it needs a DB-sized amount of free space.
    CATEGORY_FILTER_UPDATE("category-filter-update", "--category-filter-update", true),
    ;

    companion object {
        /** Maps a `/crawler/maintenance/{action}` route segment to an action. */
        fun fromRoute(route: String): MaintenanceAction? =
            entries.firstOrNull { it.route.equals(route, ignoreCase = true) }
    }
}

/**
 * Observable status of the last/running maintenance action (plan 10 §4).
 * [lines] is the tail of the one-shot's output (a ring buffer capped at
 * [CrawlerMaintenance.MAX_LINES]); the control page polls it with an offset.
 */
data class MaintenanceStatus(
    val action: MaintenanceAction? = null,
    val running: Boolean = false,
    val exitCode: Int? = null,
    val lines: List<String> = emptyList(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val message: String = "",
)

/**
 * Runs dht_crawler maintenance actions (plan 10 §4) one at a time. Each
 * action stops the supervised crawler, runs `libdhtcrawler.so <flag>` as a
 * one-shot in the crawler work dir (via [OneShotRunner], the same exec path as
 * [ProcessSupervisor]), streams its output into [status], and restarts the
 * crawler if it is enabled (and the wifi gate allows).
 *
 * Refusals are recorded in [status].message and never thrown:
 *  - an action is already running (the mutex),
 *  - the [busy] predicate is set (an apply job is running),
 *  - a [MaintenanceAction.needsSpaceCheck] action lacks free internal space for a
 *    DB-sized copy.
 *
 * All work runs on [executor] (the crawler-control executor) so the 60 s
 * crawler stop never blocks a request thread.
 */
class CrawlerMaintenance(
    private val oneShotRunner: OneShotRunner,
    private val workDir: File,
    private val executor: Executor,
    private val dbSize: () -> Long,
    private val freeSpace: () -> Long,
    private val stopCrawler: () -> Unit,
    private val startCrawlerIfEnabled: () -> Unit,
    private val busy: () -> Boolean = { false },
) {
    @Volatile var status: MaintenanceStatus = MaintenanceStatus()
        private set

    /**
     * Queues [action] on [executor], returning the resulting [status]. Refuses
     * (see class doc) when another action is running, [busy] is set, or the
     * space pre-check fails.
     */
    fun start(action: MaintenanceAction): MaintenanceStatus {
        val current = status
        if (current.running) {
            status = current.copy(
                message = "refused: maintenance already running (${current.action?.route})")
            return status
        }
        if (busy()) {
            status = current.copy(message = "refused: an apply job is running")
            return status
        }
        if (action.needsSpaceCheck) {
            val need = dbSize()
            val have = freeSpace()
            if (need > have) {
                status = current.copy(
                    message = "refused: not enough free space for ${action.route} " +
                        "(need ${formatBytes(need)}, have ${formatBytes(have)})")
                return status
            }
        }
        // Mark running optimistically so a concurrent start() is refused even
        // before the executor picks the job up.
        status = current.copy(
            action = action, running = true, exitCode = null, lines = emptyList(),
            startedAt = System.currentTimeMillis(), finishedAt = null,
            message = "starting ${action.flag} (stopping crawler)")
        try {
            executor.execute { runAction(action) }
        } catch (e: RejectedExecutionException) {
            status = status.copy(running = false, message = "refused: service is shutting down")
        }
        return status
    }

    /** Cancels the running action (SIGTERM the one-shot, then SIGKILL). */
    fun cancel() { oneShotRunner.cancel() }

    private fun runAction(action: MaintenanceAction) {
        val lines = ArrayDeque<String>()
        status = status.copy(message = "stopping crawler (up to 60 s)")
        try {
            stopCrawler()
            status = status.copy(message = "running ${action.flag}")
            val exitCode = oneShotRunner.run(listOf(action.flag), workDir) { line ->
                pushLine(lines, line)
            }
            val ok = exitCode == 0
            status = status.copy(
                running = false, exitCode = exitCode,
                finishedAt = System.currentTimeMillis(),
                message = if (ok) "${action.flag} succeeded"
                else "${action.flag} exited with code $exitCode")
        } catch (e: Exception) {
            status = status.copy(
                running = false, exitCode = -1,
                finishedAt = System.currentTimeMillis(),
                message = "${action.flag} failed: ${e.message}")
        } finally {
            // Restart the crawler if it is enabled (and the wifi gate allows),
            // regardless of the action's outcome.
            startCrawlerIfEnabled()
        }
    }

    private fun pushLine(lines: ArrayDeque<String>, line: String) {
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            status = status.copy(lines = lines.toList())
        }
    }

    companion object {
        /** Ring-buffer cap for the one-shot output tail. */
        internal const val MAX_LINES = 2000

        /** Human-readable byte count for refusal messages. */
        internal fun formatBytes(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
            b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
            b >= 1L shl 10 -> "%.1f KB".format(b.toDouble() / (1L shl 10))
            else -> "$b B"
        }
    }
}
