package com.torrent.app

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A dht_crawler maintenance action (plan 10 §4). Each maps to a single CLI
 * flag understood by the crawler's `main.c`. [needsSpaceCheck] marks the
 * actions that rewrite the database and therefore need roughly a DB-sized
 * amount of free internal space before they can run.
 *
 * [phoneSupported] is false for the actions the crawler still accepts on its
 * command line but the phone no longer offers: the WebUI renders no button for
 * them and `POST /crawler/maintenance/{route}` refuses them (see
 * [maintenanceRouteAllowed]). The enum member, its [route], its [flag] and
 * [CrawlerMaintenance.argv] stay, so the CLI surface is unchanged.
 */
enum class MaintenanceAction(
    val route: String,
    val flag: String,
    val needsSpaceCheck: Boolean,
    val phoneSupported: Boolean = true,
) {
    CHECK_DATABASE("check-database", "--check-database", false),
    // CLI-only on the phone: a full DB rewrite is not a WebUI action.
    COMPACT("compact", "--compact", true, phoneSupported = false),
    RECOVER_DATABASE("recover-database", "--recover-database", true),
    REBUILD_BLOOM_FILTER("rebuild-bloom-filter", "--rebuild-bloom-filter", false),
    CLEAR_FAILURE_FILTER("clear-failure-filter", "--clear-failure-filter", false),
    // The purge only deletes rows (plus a WAL checkpoint): it never rewrites
    // the database file, so it needs no DB-sized free space. CLI-only on the
    // phone: the unattended row deletion is gone from the WebUI.
    CATEGORY_FILTER_UPDATE(
        "category-filter-update", "--category-filter-update", false, phoneSupported = false
    ),
    // The non-Latin sweep only deletes rows (plus a WAL checkpoint), so it is
    // not space-gated either. Unlike CATEGORY_FILTER_UPDATE it stays
    // phone-supported: it is the explicit catch-up pass for rows stored while
    // the filter was off, it refuses (exit 1) when the threshold is 0, and it
    // prints its own scanned/deleted summary.
    LANGUAGE_FILTER_CLEANUP(
        "language-filter-cleanup", "--language-filter-cleanup", false
    ),
    ;

    companion object {
        /** Maps a `/crawler/maintenance/{action}` route segment to an action. */
        fun fromRoute(route: String): MaintenanceAction? =
            entries.firstOrNull { it.route.equals(route, ignoreCase = true) }
    }
}

/**
 * True when `POST /crawler/maintenance/{route}` may run the action from the
 * phone. [MaintenanceAction.COMPACT] and
 * [MaintenanceAction.CATEGORY_FILTER_UPDATE] keep their enum member, `route`,
 * `--flag` and argv (the crawler CLI still runs them) but are refused here, so
 * the route answers with the same refusal shape it gives an unmapped segment.
 *
 * The lookup goes through [MaintenanceAction.fromRoute] so the route match
 * stays case-insensitive exactly as before.
 */
fun maintenanceRouteAllowed(route: String): Boolean =
    MaintenanceAction.fromRoute(route)?.phoneSupported == true

/**
 * Observable status of the last/running maintenance action (plan 10 §4).
 * [lines] is the tail of the one-shot's output (a ring buffer capped at
 * [CrawlerMaintenance.MAX_LINES]); the control page polls it with an offset.
 *
 * [phase], [percent], [done] and [total] come from the action's machine-readable
 * `[MPROG]` lines ([MaintenanceProgress], merged by [withProgress]). They stay
 * null for an action that reports no progress, and [percent] stays null whenever
 * the action has no denominator: phase-only is the honest state.
 *
 * [extras] keeps the `k=v` pairs of the last progress line that have no field of
 * their own (`rc`, `removed`, …).
 */
data class MaintenanceStatus(
    val action: MaintenanceAction? = null,
    val running: Boolean = false,
    val exitCode: Int? = null,
    val lines: List<String> = emptyList(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val message: String = "",
    val phase: String? = null,
    val percent: Int? = null,
    val done: Long? = null,
    val total: Long? = null,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * Merges one parsed [MaintenanceProgress] line into the status. The progress
 * fields change and [MaintenanceStatus.extras] keeps the line's remaining `k=v`
 * pairs; the raw line still goes to [MaintenanceStatus.lines] (the console keeps
 * every line verbatim).
 */
internal fun MaintenanceStatus.withProgress(p: MaintenanceProgress): MaintenanceStatus =
    copy(
        phase = p.phase, percent = p.percent, done = p.done, total = p.total,
        extras = p.extras)

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
 * All work runs on [executor] (the crawler-control executor) so the 20 s
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
        // before the executor picks the job up. The progress fields are cleared
        // here so a previous run's `phase=done pct=100` cannot read as this
        // run's state before its first [MaintenanceProgress] line arrives.
        status = current.copy(
            action = action, running = true, exitCode = null, lines = emptyList(),
            startedAt = System.currentTimeMillis(), finishedAt = null,
            message = "starting ${action.flag} (stopping crawler)",
            phase = null, percent = null, done = null, total = null,
            extras = emptyMap())
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
        status = status.copy(message = "stopping crawler (up to 20 s)")
        try {
            stopCrawler()
            status = status.copy(message = "running ${action.flag}")
            val exitCode = oneShotRunner.run(argv(action), workDir) { line ->
                // Parse the machine-readable line first, then keep it verbatim in
                // the console ring buffer: the raw `[MPROG]` lines are part of
                // the log the control page streams.
                parseMaintenanceProgress(line)?.let { status = status.withProgress(it) }
                pushLine(lines, line)
            }
            val ok = exitCode == 0
            val outcome = if (ok) "${action.flag} succeeded"
            else "${action.flag} exited with code $exitCode"
            status = status.copy(
                running = false, exitCode = exitCode,
                finishedAt = System.currentTimeMillis(),
                message = outcome)
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

        /** argv for [action]'s one-shot: a bare flag. */
        internal fun argv(action: MaintenanceAction): List<String> = listOf(action.flag)

        /** Human-readable byte count for refusal messages. */
        internal fun formatBytes(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
            b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
            b >= 1L shl 10 -> "%.1f KB".format(b.toDouble() / (1L shl 10))
            else -> "$b B"
        }
    }
}
