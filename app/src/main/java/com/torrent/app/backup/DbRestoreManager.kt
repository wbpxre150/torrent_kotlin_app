package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigValidator
import com.torrent.app.s3.S3GetResult
import com.torrent.app.s3.S3ListResult
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * One listing + download of the incremental DB-dump chain (plan 2,
 * Stage 4 restore). A thin seam over [com.torrent.app.s3.S3Client]
 * (`list` + `get`) so unit tests can fake the cloud; adapt a real
 * client with a lambda (the client itself is unchanged).
 */
interface S3DbRestorer {
    fun list(prefix: String): S3ListResult
    fun get(objectKey: String): S3GetResult
}

/** Outcome states of the last DB restore attempt. */
enum class DbRestoreState {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
    REFUSED_BUSY,
    REFUSED_RUNNING,
    NOT_CONFIGURED,
}

/**
 * Thread-safe snapshot of the DB restore manager for the dashboard
 * (`GET /api/crawler/restore`). [objectCount]/[objectKeys] describe
 * the last restore's chain (kept visible after later failures);
 * [lines] is the tail of the one-shot's output (a ring buffer capped
 * at [DbRestoreManager.MAX_LINES]); [exitCode] is the one-shot's
 * exit code (-1 when it never ran).
 */
data class DbRestoreStatus(
    val state: DbRestoreState = DbRestoreState.IDLE,
    val running: Boolean = false,
    val message: String = "",
    val lastAttempt: Long? = null,
    val lastSuccess: Long? = null,
    val objectCount: Int? = null,
    val objectKeys: List<String> = emptyList(),
    val exitCode: Int? = null,
    val lines: List<String> = emptyList(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val configured: Boolean = false,
)

/**
 * Restores the dht_crawler's database from the incremental SQL-dump
 * chain in the R2 DB bucket (plan 2, Stage 4). An explicit,
 * dashboard-triggered one-shot — there is no schedule.
 *
 * Flow of [restoreNow] (stop-first, on [executor], never a request
 * thread): load settings (R2 endpoint + creds + DB bucket + prefix
 * must be present; [ConfigValidator.validateDbBackup] is run with the
 * enabled flag forced so the shared checks apply) →
 * [DbRestoreState.NOT_CONFIGURED]; [busy] (an apply/maintenance/
 * backup job is in flight) → [DbRestoreState.REFUSED_BUSY]; a
 * restore already in flight →
 * [DbRestoreState.REFUSED_RUNNING]; otherwise stop the crawler,
 * LIST the `db/` prefix, download + gunzip every parseable object in
 * chain order (sorted by start id, then date; malformed keys are
 * skipped), concatenate them into ONE temp `.sql`, and run
 * `libdhtcrawler.so --restore-database <abs path>` as a one-shot in
 * the crawler work dir (the C side replays the dump into a FRESH db
 * and swaps it in atomically).
 *
 * Bookmark ordering (the critical invariant): the app-side
 * [DbBookmark] is reset to [DbBookmark.FIRST_RUN] ONLY after the
 * one-shot exits 0 — a failed restore leaves the bookmark alone so
 * the next upload continues the chain. The crawler is restarted
 * (when enabled) after EVERY outcome that attempted to stop it, so a
 * failed restore — even a throwing stopCrawler — never leaves it
 * dead. The temp file is deleted in a `finally` on every path.
 *
 * All collaborators are constructor-injected (plain-JUnit
 * testable); [runRestore] is package-internal so tests can run it
 * synchronously on the test thread.
 */
class DbRestoreManager(
    private val loadSettings: () -> BackupSettings,
    private val isBusy: () -> Boolean,
    private val clientFactory: (BackupSettings) -> S3DbRestorer,
    private val oneShot: (List<String>, File, (String) -> Unit) -> Int,
    private val workDir: () -> File,
    private val tmpDir: () -> File,
    private val bookmarkFile: () -> File,
    private val stopCrawler: () -> Unit,
    private val startCrawlerIfEnabled: () -> Unit,
    private val executor: Executor,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    var status: DbRestoreStatus = DbRestoreStatus()
        private set

    private val lock = Any()
    @Volatile
    private var inFlight = false
    @Volatile
    private var stopped = false

    /**
     * Queues a restore on [executor] and returns the resulting
     * (optimistic) [status]. Refused when a restore is in flight,
     * [isBusy] is set, or [stop] has run. Poll [status] for the
     * outcome.
     */
    fun restoreNow(): DbRestoreStatus {
        val accepted = synchronized(lock) {
            if (stopped) {
                status = status.copy(state = DbRestoreState.FAILED,
                    message = "refused: service is shutting down")
                false
            } else if (inFlight) {
                status = status.copy(state = DbRestoreState.REFUSED_RUNNING,
                    message = "refused: a restore is already running")
                false
            } else if (isBusy()) {
                status = status.copy(state = DbRestoreState.REFUSED_BUSY,
                    message = "refused: an apply/maintenance/backup job is running")
                false
            } else {
                inFlight = true
                status = status.copy(state = DbRestoreState.RUNNING,
                    running = true, exitCode = null, lines = emptyList(),
                    startedAt = clock(), finishedAt = null,
                    message = "db restore queued")
                true
            }
        }
        if (!accepted) return status
        try {
            executor.execute {
                try {
                    runRestore()
                } catch (e: Exception) {
                    // A restore must never take the control executor down.
                    finish(
                        DbRestoreState.FAILED,
                        "restore failed: ${e.message ?: e.javaClass.simpleName}",
                        exitCode = -1,
                    )
                } finally {
                    inFlight = false
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlight = false
            status = status.copy(state = DbRestoreState.FAILED, running = false,
                message = "refused: service is shutting down")
        }
        return status
    }

    /**
     * Runs one full restore synchronously (settings → stop → list →
     * download/gunzip/concat → one-shot → bookmark → restart).
     * Called on [executor] by [restoreNow]; package-internal so unit
     * tests can run it on the test thread.
     */
    internal fun runRestore(): DbRestoreStatus {
        val lines = ArrayDeque<String>()
        val settings = try {
            loadSettings()
        } catch (e: Exception) {
            return finish(DbRestoreState.FAILED,
                "failed to load settings: ${e.message ?: e.javaClass.simpleName}",
                configured = false)
        }
        // Restore requires the R2 endpoint + creds + DB bucket + prefix
        // regardless of the upload toggle: run the shared validator with
        // the enabled flag forced so its checks all apply.
        val errors = ConfigValidator.validateDbBackup(
            settings.copy(dbBackupEnabled = true)).errors
        if (errors.isNotEmpty()) {
            return finish(DbRestoreState.NOT_CONFIGURED,
                "not configured: " + errors.joinToString("; "),
                configured = false)
        }
        val tmpFile = File(tmpDir(), "restore-${clock()}.sql")
        tmpDir().mkdirs()
        try {
            status = status.copy(message = "stopping crawler (up to 60 s)")
            stopCrawler()

            status = status.copy(message = "listing ${prefixDir(settings)} objects")
            val listed = when (val r = clientFactory(settings).list(prefixDir(settings))) {
                is S3ListResult.Success -> r.objects
                is S3ListResult.Capped ->
                    throw IllegalStateException("listing was capped at the page limit " +
                        "(${r.objects.size} objects so far)")
                is S3ListResult.ClientError ->
                    throw IllegalStateException("R2 rejected the listing (HTTP ${r.statusCode})")
                is S3ListResult.ServerError ->
                    throw IllegalStateException("R2 server error (HTTP ${r.statusCode} " +
                        "after ${r.attempts} attempts)")
                is S3ListResult.Failure ->
                    throw IllegalStateException("listing failed: " +
                        "${r.cause.message ?: r.cause.javaClass.simpleName}")
            }
            // Chain order: ID-MAJOR — sort by (start id, then date) so an
            // overlapping re-dump of the same range replays oldest-first;
            // skip keys that do not match the dump convention exactly, keys
            // outside the configured prefix, and zero-size objects (which
            // would restore an EMPTY database).
            val chain = listed.mapNotNull { o ->
                if (o.size == 0L) return@mapNotNull null
                val k = DbObjectKey.parse(o.key) ?: return@mapNotNull null
                if (k.prefix != settings.r2DbObjectPrefix) return@mapNotNull null
                o.key to k
            }.sortedWith(compareBy({ it.second.start }, { it.second.date }))
            if (chain.isEmpty()) {
                throw IllegalStateException("nothing to restore: no parseable dump objects " +
                    "under ${prefixDir(settings)}")
            }
            // Chain coherence: the chain legitimately starts at ANY id —
            // the first object's start defines the chain start (history
            // before it is deliberately not in the bucket) — so there is no
            // "missing baseline" failure.
            //
            // A real bucket can hold a MIXED chain of two shapes that share
            // a UTC date: legacy objects from the old seeded baseline
            // (starting at e.g. 34,500,594) and from-zero objects produced
            // after a restore reset the bookmark to first run (starting at
            // 0). Sorted id-major those two shapes interleave instead of
            // stacking by date — e.g. `[0-2000]` is followed by
            // `[34501812-34503000]` on a shared date — so the coherence
            // check is a RUNNING-MAX-END pass, never an adjacency check: an
            // object may start anywhere at or before `runningMaxEnd + 1`,
            // contiguous OR overlapping. Overlap is legal because every dump
            // statement is `INSERT OR IGNORE` and the id-major order makes
            // the replay first-writer-wins deterministically. A TRUE gap —
            // `start > runningMaxEnd + 1`, i.e. no earlier object covers the
            // ids right below it — means the bucket is missing data and
            // restoring it would silently drop rows; fail that case.
            val first = chain.first().second
            var runningMaxEnd = Long.MIN_VALUE
            var runningMaxEndKey: String? = null
            for ((key, obj) in chain) {
                if (runningMaxEnd != Long.MIN_VALUE && obj.start - runningMaxEnd > 1L) {
                    throw IllegalStateException(
                        "backup chain has a gap: object $runningMaxEndKey ends at " +
                            "$runningMaxEnd but next object $key starts at ${obj.start}")
                }
                if (obj.end > runningMaxEnd) {
                    runningMaxEnd = obj.end
                    runningMaxEndKey = key
                }
            }
            val keys = chain.map { it.first }

            status = status.copy(objectCount = keys.size, objectKeys = keys,
                message = "downloading ${keys.size} object(s)")
            tmpFile.outputStream().use { out ->
                val client = clientFactory(settings)
                for (key in keys) {
                    when (val g = client.get(key)) {
                        is S3GetResult.Success ->
                            out.write(gunzipBytes(g.body))
                        is S3GetResult.ClientError ->
                            throw IllegalStateException("R2 rejected the download of $key " +
                                "(HTTP ${g.statusCode})")
                        is S3GetResult.ServerError ->
                            throw IllegalStateException("R2 server error downloading $key " +
                                "(HTTP ${g.statusCode} after ${g.attempts} attempts)")
                        is S3GetResult.Failure ->
                            throw IllegalStateException("download of $key failed: " +
                                "${g.cause.message ?: g.cause.javaClass.simpleName}")
                    }
                }
            }

            status = status.copy(message = "running --restore-database")
            val exitCode = oneShot(
                listOf("--restore-database", tmpFile.absolutePath), workDir()) { line ->
                pushLine(lines, line)
            }
            return if (exitCode == 0) {
                // Only a successful restore resets the cursor: the next
                // upload then re-dumps the whole (restored) database.
                DbBookmark.write(bookmarkFile(), DbBookmark.FIRST_RUN)
                finish(
                    DbRestoreState.SUCCESS,
                    "restored ${keys.size} object(s); chain starts at torrent id " +
                        "${first.start} (pre-baseline history is not in the " +
                        "bucket); bookmark reset to first run",
                    exitCode = exitCode,
                    lastSuccess = clock(),
                )
            } else {
                finish(
                    DbRestoreState.FAILED,
                    "restore exited with code $exitCode (bookmark unchanged)",
                    exitCode = exitCode,
                )
            }
        } catch (e: Exception) {
            return finish(DbRestoreState.FAILED, "restore failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            // Restart the crawler if it is enabled (and the wifi gate
            // allows) regardless of the outcome — the same ceremony as
            // CrawlerMaintenance.runAction. The try block begins with the
            // stopCrawler() attempt, so by here the crawler is stopped or
            // partially stopped (stopCrawler threw) and must be brought
            // back; the supervisor's start() is idempotent, so a redundant
            // call is harmless.
            startCrawlerIfEnabled()
            // The temp dump is always deleted, on success and failure.
            tmpFile.delete()
        }
    }

    /** Marks the manager shut down; further [restoreNow] calls are refused. */
    fun stop() { stopped = true }

    private fun prefixDir(settings: BackupSettings): String = settings.r2DbObjectPrefix + "/"

    private fun pushLine(lines: ArrayDeque<String>, line: String) {
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            status = status.copy(lines = lines.toList())
        }
    }

    private fun finish(
        state: DbRestoreState,
        message: String,
        exitCode: Int? = null,
        lastSuccess: Long? = null,
        configured: Boolean = true,
    ): DbRestoreStatus {
        val st = DbRestoreStatus(
            state = state,
            running = false,
            message = message,
            lastAttempt = if (state != DbRestoreState.NOT_CONFIGURED) clock() else status.lastAttempt,
            lastSuccess = lastSuccess ?: status.lastSuccess,
            objectCount = status.objectCount,
            objectKeys = status.objectKeys,
            exitCode = exitCode ?: status.exitCode,
            lines = status.lines,
            startedAt = status.startedAt,
            finishedAt = clock(),
            configured = configured,
        )
        status = st
        return st
    }

    companion object {
        /** Ring-buffer cap for the one-shot output tail. */
        internal const val MAX_LINES = 2000
    }
}
