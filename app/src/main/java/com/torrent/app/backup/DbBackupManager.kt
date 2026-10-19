package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigValidator
import com.torrent.app.s3.S3PutResult
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One S3 object PUT of the compressed incremental DB dump (plan 2, Stage 3).
 * A thin seam over [com.torrent.app.s3.S3Client.put] so unit tests can fake
 * the upload; adapt a real client with a lambda (the client itself is
 * unchanged).
 */
fun interface S3DbUploader {
    fun put(objectKey: String, contentType: String, body: ByteArray): S3PutResult
}

/**
 * One incremental dump of the crawler database (plan 2, Stage 1+2). The
 * production adapter is [CrawlerDbDumper]; unit tests of
 * [DbBackupManager] fake this seam.
 */
interface DbDumper {
    fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome
}

/** Outcome states of the last DB dump upload check. */
enum class DbBackupState {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED_BUSY,
    SKIPPED_NO_WIFI,
    SKIPPED_NO_DB,
    SKIPPED_EMPTY,
    NOT_CONFIGURED,
}

/**
 * Thread-safe snapshot of the DB backup manager for the dashboard
 * (`GET /api/crawler/backup/db`). [lastAttempt] is set only when a check
 * gets past the configured gate (a skip/attempt, not a config refusal);
 * [uploadedBytes] is the gzip size of the last successful PUT and
 * [objectKey]/[sqlBytes]/[statementCount] describe the last successful
 * upload (kept visible after later skips/failures).
 */
data class DbBackupStatus(
    val state: DbBackupState = DbBackupState.IDLE,
    val message: String = "",
    val lastAttempt: Long? = null,
    val lastSuccess: Long? = null,
    val uploadedBytes: Long? = null,
    val sqlBytes: Long? = null,
    val statementCount: Int? = null,
    val objectKey: String? = null,
    val uploadEnabled: Boolean = false,
    val configured: Boolean = false,
)

/**
 * Uploads the dht_crawler's SQLite database as incremental SQL dumps to the
 * R2 DB bucket (plan 2, Stage 3).
 *
 * A periodic check (every [CHECK_INTERVAL_MINUTES] minutes, first one
 * [FIRST_CHECK_DELAY_MINUTES] after [start]) drains the incremental backlog
 * in bounded chunks, at most once per calendar day (per [zone]) while the
 * backlog is caught up; a still-capped check instead chains a fast
 * [FAST_FOLLOW_DELAY_MS] follow-up check, so a large backlog drains
 * near-continuously (see "Near-continuous backlog drain" below). A manual
 * trigger (dashboard) bypasses the daily gate only — the configured/busy/
 * wifi gates always apply. Every check runs on [executor] (a single-thread
 * [ScheduledExecutorService] by default) so at most one upload is in flight
 * at a time; a trigger while one is running is refused with "already
 * running".
 *
 * Flow of a check: not configured ([BackupSettings.dbBackupEnabled] false or
 * [ConfigValidator.validateDbBackup] errors) →
 * [DbBackupState.NOT_CONFIGURED]; [isBusy] →
 * [DbBackupState.SKIPPED_BUSY]; no Wi-Fi → [DbBackupState.SKIPPED_NO_WIFI];
 * daily gate; DB file missing → [DbBackupState.SKIPPED_NO_DB];
 * bookmark missing (first-ever run, also against a pre-existing DB) →
 * drained from [DbBookmark.FIRST_RUN], i.e. from the start of the database;
 * [DbDumper] skips (nothing to dump, e.g. empty DB) →
 * [DbBackupState.SKIPPED_EMPTY]; otherwise the bounded chunk drain below →
 * [DbBackupState.SUCCESS] / [DbBackupState.FAILED].
 *
 * Bounded chunk drain: [DbDumper] itself caps one dump at
 * [CrawlerDbDumper.DEFAULT_MAX_NEW_TORRENT_IDS_PER_DUMP] new torrent ids and
 * flags the truncation via [DbDumpOutcome.Dump.capped] (materializing the
 * whole backlog in RAM used to OOM-crash-loop the process). One check
 * therefore uploads at most [MAX_CHUNKS_PER_CHECK] chunks — up to 128,000
 * torrents per check, with only one bounded chunk in memory at a time — and
 * stops early on the first chunk that is NOT capped. The daily date is
 * persisted only when the backlog is drained (the check's final chunk was
 * uncapped).
 *
 * Near-continuous backlog drain (the initial ~84 GB DB is a year-plus of work
 * at four chunks per 15-minute tick): a periodic check that ends still capped
 * schedules exactly ONE one-shot follow-up check on [executor],
 * [fastFollowUpDelayMs] milliseconds later (default [FAST_FOLLOW_DELAY_MS]),
 * which continues from the advanced bookmark. A follow-up that also ends
 * capped schedules the next one, so the chain drains back to back;
 * [followUpPending] keeps at most one follow-up pending and [stop] cancels it
 * (the single-thread executor's shutdown drops queued and scheduled tasks).
 * The chain ends on the first check that is uncapped (the backlog caught up —
 * the daily date is then written as today, so follow-up and periodic ticks
 * skip until tomorrow), on FAILED, or on any skip (the periodic
 * [CHECK_INTERVAL_MINUTES] cadence is then the retry path). Steady state is
 * unchanged: one check per day at the same 15-minute cadence.
 *
 * Upload-first ordering (the critical invariant): the [DbBookmark] is
 * advanced to a chunk's [DbDumpOutcome.Dump.newCursor] ONLY after that
 * chunk's PUT succeeded. A failed/ killed upload leaves the bookmark
 * unchanged, so the next run re-dumps (and re-uploads) the exact same range
 * — idempotent thanks to `INSERT OR IGNORE`. A failure in the middle of a
 * drain likewise costs only the failed chunk: every earlier chunk's PUT was
 * already followed by its own bookmark write.
 *
 * All collaborators are constructor-injected (plain-JUnit testable);
 * [clock] and [zone] drive the calendar-day gate.
 */
class DbBackupManager(
    private val dbFile: () -> File,
    private val dumperFactory: (BackupSettings) -> DbDumper,
    private val loadSettings: () -> BackupSettings,
    private val isBusy: () -> Boolean,
    private val isWifi: () -> Boolean,
    private val clientFactory: (BackupSettings) -> S3DbUploader,
    private val bookmarkFile: () -> File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private var executor: ScheduledExecutorService = defaultExecutor(),
    private val fastFollowUpDelayMs: Long = FAST_FOLLOW_DELAY_MS,
    private val readLastSuccessDate: () -> String? = { null },
    private val writeLastSuccessDate: (String) -> Unit = {},
) {
    @Volatile
    var status: DbBackupStatus = DbBackupStatus()
        private set

    /**
     * Live "a check is in flight" signal for the busy predicates: true
     * from acceptance in [uploadNow] (including the time it is queued on
     * [executor]) until the check completes. [status.state] deliberately
     * never becomes [DbBackupState.RUNNING] (the previous state is
     * preserved while queued/in-flight), so this is the only way another
     * operator can observe a running DB backup check.
     */
    val inFlight: Boolean
        get() = inFlightFlag

    private val lock = Any()
    @Volatile
    private var started = false
    /**
     * Set by [stop] when it shuts [executor] down; [start] then rebuilds the
     * executor instead of scheduling on a dead one. Not gated on
     * `executor.isShutdown` so an injected executor behaves the same way.
     */
    @Volatile
    private var executorStopped = false
    @Volatile
    private var inFlightFlag = false

    /**
     * True while a fast follow-up check is scheduled: set when [runCheck]
     * chains one, cleared the moment it starts running (so the next capped
     * check can chain again, but never more than one follow-up is pending).
     */
    private val followUpPending = AtomicBoolean(false)

    /**
     * Queues an upload check on [executor] and returns the resulting
     * (optimistic) [status]. Refused with "already running" when a check is
     * in flight, or "shutting down" once [stop] has run. The returned
     * status is the trigger result — poll [status] for the outcome.
     */
    fun uploadNow(manual: Boolean): DbBackupStatus {
        val accepted = synchronized(lock) {
            if (inFlightFlag) {
                // Only a manual trigger surfaces the refusal; a periodic tick
                // must not clobber the in-flight upload's status message.
                if (manual) {
                    status = status.copy(message = "refused: an upload is already running")
                }
                false
            } else {
                inFlightFlag = true
                // No RUNNING marker here: runCheck's daily gate "preserves the
                // previous state", which must be the last real outcome.
                status = status.copy(message = "db backup queued")
                true
            }
        }
        if (!accepted) return status
        try {
            executor.execute {
                try {
                    runCheck(manual)
                } catch (e: Throwable) {
                    // Throwable, not Exception: an OutOfMemoryError raised
                    // while materializing a chunk must mark the check FAILED
                    // and release inFlight — unwinding this frame frees the
                    // dump's memory — instead of killing the process and
                    // wedging the backup managers. (The check must never take
                    // the periodic schedule down either.)
                    status = status.copy(
                        state = DbBackupState.FAILED,
                        message = "upload failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                } finally {
                    inFlightFlag = false
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlightFlag = false
            status = status.copy(state = DbBackupState.FAILED, message = "refused: service is shutting down")
        }
        return status
    }

    /**
     * Re-evaluates only the config gates ([BackupSettings.dbBackupEnabled]
     * + [ConfigValidator.validateDbBackup] errors) against the current
     * settings and updates [status] synchronously — no executor, no
     * network, no dump. Called by the settings apply path
     * (DaemonService's saveBackupSettings) so the crawler panel's
     * "DB dump upload is disabled" note reflects a just-applied change
     * immediately instead of after the next periodic check (up to
     * [CHECK_INTERVAL_MINUTES] minutes later).
     *
     * A previous NOT_CONFIGURED state is reset to IDLE once the settings
     * are enabled and valid (that state only ever described the config
     * gate, which has just been cleared); any real outcome state
     * (SUCCESS/FAILED/SKIPPED_*) and its message is preserved, only
     * the config flags are corrected. Races with an in-flight check are
     * benign — status is a best-effort volatile snapshot and the check's
     * own finish() re-applies its outcome.
     */
    fun refreshConfigStatus() {
        val settings = try {
            loadSettings()
        } catch (e: Exception) {
            finish(DbBackupState.FAILED, "failed to load settings: ${e.message}",
                uploadEnabled = false, configured = false)
            return
        }
        if (!settings.dbBackupEnabled) {
            finish(DbBackupState.NOT_CONFIGURED, "DB dump upload is disabled",
                uploadEnabled = false, configured = false)
            return
        }
        val errors = ConfigValidator.validateDbBackup(settings).errors
        if (errors.isNotEmpty()) {
            finish(DbBackupState.NOT_CONFIGURED,
                "not configured: " + errors.joinToString("; "),
                uploadEnabled = true, configured = false)
            return
        }
        status = if (status.state == DbBackupState.NOT_CONFIGURED)
            status.copy(state = DbBackupState.IDLE, message = "",
                uploadEnabled = true, configured = true)
        else
            status.copy(uploadEnabled = true, configured = true)
    }

    /**
     * Runs one full upload check synchronously (gates, dump, PUT, bookmark).
     * Called on [executor] by [uploadNow]; package-internal so unit tests
     * can run it on the test thread.
     */
    internal fun runCheck(manual: Boolean): DbBackupStatus {
        val settings = try {
            loadSettings()
        } catch (e: Exception) {
            return finish(
                DbBackupState.FAILED, "failed to load settings: ${e.message}",
                uploadEnabled = false, configured = false)
        }
        val enabled = settings.dbBackupEnabled
        val errors = ConfigValidator.validateDbBackup(settings).errors
        if (!enabled) {
            return finish(DbBackupState.NOT_CONFIGURED, "DB dump upload is disabled",
                uploadEnabled = false, configured = false)
        }
        if (errors.isNotEmpty()) {
            return finish(DbBackupState.NOT_CONFIGURED,
                "not configured: " + errors.joinToString("; "),
                uploadEnabled = true, configured = false)
        }
        if (isBusy()) {
            return finish(DbBackupState.SKIPPED_BUSY,
                "skipped: an apply/maintenance job is running",
                uploadEnabled = true, configured = true)
        }
        if (!isWifi()) {
            return finish(DbBackupState.SKIPPED_NO_WIFI, "skipped: no Wi-Fi",
                uploadEnabled = true, configured = true)
        }
        val today = todayString()
        if (!manual && readLastSuccessDate() == today) {
            // Daily gate: already uploaded today. Keep the previous state, but
            // route through finish() so lastAttempt is updated (per the
            // DbBackupStatus KDoc).
            return finish(
                status.state,
                "skipped: already uploaded today ($today)",
                uploadEnabled = true, configured = true)
        }
        val file = dbFile()
        if (!file.exists()) {
            return finish(DbBackupState.SKIPPED_NO_DB, "skipped: crawler DB is missing",
                uploadEnabled = true, configured = true)
        }
        // Missing bookmark file = first-ever run: drain from the very
        // beginning of the database. [DbBookmark.readOrNull] only tells "no
        // bookmark file yet" apart from "a bookmark exists"; both resolve to
        // a concrete cursor here, and a missing/unparseable file reads as
        // [DbBookmark.FIRST_RUN] ("dump everything"). The sentinel is
        // deliberately NOT written to disk: a missing file already reads back
        // as FIRST_RUN, and an empty DB writes nothing at all (SKIPPED_EMPTY
        // below), so the next check simply retries from the start.
        //
        // Bounded drain of the backlog: each chunk is dumped (the dumper caps
        // it at its own row cap), PUT, and only then followed by its bookmark
        // write (upload-first holds per chunk). The loop budget keeps one
        // check's wall time bounded; if it runs out while still capped the
        // next check continues from the advanced bookmark.
        var cursor: DbBookmark = DbBookmark.readOrNull(bookmarkFile()) ?: DbBookmark.FIRST_RUN
        var chunks = 0
        var totalBytes = 0L
        var totalStatements = 0
        var lastDump: DbDumpOutcome.Dump? = null
        while (chunks < MAX_CHUNKS_PER_CHECK) {
            val outcome = try {
                dumperFactory(settings).dump(file, cursor)
            } catch (e: Exception) {
                return finish(DbBackupState.FAILED, "dump failed: ${e.message ?: e.javaClass.simpleName}",
                    uploadEnabled = true, configured = true)
            }
            if (outcome is DbDumpOutcome.Skipped) {
                // Empty range (no new torrents in this chunk's range): nothing
                // to upload, exactly as before the drain loop.
                return finish(DbBackupState.SKIPPED_EMPTY,
                    "skipped: no new torrents (max id ${outcome.maxTorrentId})",
                    uploadEnabled = true, configured = true)
            }
            val dump = outcome as DbDumpOutcome.Dump
            val result = try {
                clientFactory(settings).put(dump.objectKey, CONTENT_TYPE_GZIP, dump.sqlBytes)
            } catch (e: Exception) {
                return finish(DbBackupState.FAILED, "upload failed: ${e.message ?: e.javaClass.simpleName}",
                    uploadEnabled = true, configured = true)
            }
            when (result) {
                is S3PutResult.Success -> Unit
                is S3PutResult.ClientError -> return finish(
                    DbBackupState.FAILED,
                    "R2 rejected the upload (HTTP ${result.statusCode})",
                    uploadEnabled = true, configured = true)
                is S3PutResult.ServerError -> return finish(
                    DbBackupState.FAILED,
                    "R2 server error (HTTP ${result.statusCode} after ${result.attempts} attempts)",
                    uploadEnabled = true, configured = true)
                is S3PutResult.Failure -> return finish(
                    DbBackupState.FAILED,
                    "upload failed: ${result.cause.message ?: result.cause.javaClass.simpleName}",
                    uploadEnabled = true, configured = true)
            }
            // Upload-first: the bookmark advances ONLY after this chunk's PUT
            // landed, so a failed/killed upload re-dumps the same range next
            // run (INSERT OR IGNORE makes the re-upload idempotent).
            try {
                DbBookmark.write(bookmarkFile(), dump.newCursor)
            } catch (e: Exception) {
                // The object is on R2 but the cursor did not move: the
                // next run re-dumps and re-uploads the same range.
                return finish(
                    DbBackupState.FAILED,
                    "upload succeeded but bookmark write failed: ${e.message ?: e.javaClass.simpleName}",
                    uploadEnabled = true, configured = true)
            }
            chunks++
            cursor = dump.newCursor
            totalBytes += dump.sqlBytes.size
            totalStatements += dump.statementCount
            // Only the last chunk's dump is retained (for the status fields);
            // the earlier chunks' byte arrays are released as the loop runs.
            lastDump = dump
            if (!dump.capped) {
                // Uncapped final chunk: the backlog is drained for today, so
                // the daily date is persisted (the periodic gate then stops
                // re-running us until tomorrow).
                writeLastSuccessDate(today)
                break
            }
        }
        // The loop can only exit with at least one uploaded chunk: every other
        // exit path returns above.
        val last = checkNotNull(lastDump) { "drain loop exited without a chunk" }
        val message = if (chunks > 1) {
            "uploaded $chunks chunks, $totalBytes bytes to ${last.objectKey} " +
                "($totalStatements statements)"
        } else {
            "uploaded ${last.sqlBytes.size} bytes to ${last.objectKey} " +
                "(${last.statementCount} statements)"
        }
        return finish(
            DbBackupState.SUCCESS,
            if (last.capped) {
                // Budget exhausted while still capped: the daily date is
                // deliberately NOT written (see the class KDoc), and a
                // periodic check chains a fast follow-up instead of waiting
                // for the next 15-minute tick.
                if (!manual) scheduleFastFollowUp()
                "$message; backlog continues " +
                    "(capped at torrent id ${cursor.lastTorrentId})"
            } else {
                message
            },
            uploadEnabled = true, configured = true,
            uploadedBytes = last.sqlBytes.size.toLong(),
            sqlBytes = last.sqlBytes.size.toLong(),
            statementCount = last.statementCount,
            objectKey = last.objectKey, lastSuccess = clock())
    }

    /**
     * Schedules the periodic (daily-gated) check; idempotent. Re-arms after a
     * [stop] with a fresh executor — the service instance survives a graceful
     * stop, so the daemons' restart path calls start() again.
     */
    fun start() {
        synchronized(lock) {
            if (started) return
            if (executorStopped) {
                executor = defaultExecutor()
                executorStopped = false
            }
            started = true
            // Scheduled under the same lock as the stop: no shutdownNow() can
            // land between the rebuild check and the schedule.
            executor.scheduleWithFixedDelay({ uploadNow(manual = false) },
                FIRST_CHECK_DELAY_MINUTES, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
        }
    }

    /**
     * Stops the scheduler; a queued/running check is dropped. Resets the
     * lifecycle flags AFTER the shutdown so a later [start] fully re-arms.
     */
    fun stop() {
        // shutdownNow() also drops (cancels) a pending fast follow-up: it is
        // an ordinary scheduled task of this executor.
        followUpPending.set(false)
        synchronized(lock) {
            executor.shutdownNow()
            executorStopped = true
            started = false
            // A check accepted by uploadNow() but still QUEUED is dropped here
            // without ever running, so its wrapper's finally never clears the
            // guard: without this, every later upload (manual and periodic) is
            // refused as "already running" for the rest of the process
            // lifetime — the service instance survives a graceful stop. Safe to
            // clear here: stop() is the only place the executor is torn down,
            // so no live check can be running against the released guard.
            inFlightFlag = false
        }
    }

    /**
     * Chains ONE fast follow-up periodic check while the backlog is still
     * capped, on the same single-thread [executor]. [followUpPending] makes
     * this idempotent — a second capped check (e.g. a periodic tick landing
     * between two follow-ups) never stacks another one — and it is cleared
     * when the follow-up starts running, so a follow-up that also ends capped
     * chains the next one. A rejecting (shut-down) executor is a no-op.
     */
    private fun scheduleFastFollowUp() {
        if (!followUpPending.compareAndSet(false, true)) return
        try {
            executor.schedule({
                // Cleared first: this check may itself end capped and chain.
                followUpPending.set(false)
                uploadNow(manual = false)
            }, fastFollowUpDelayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            followUpPending.set(false)
        }
    }

    /** Local calendar date (per [clock]/[zone]) of "today", `yyyy-MM-dd`. */
    internal fun todayString(): String =
        Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toString()

    private fun finish(
        state: DbBackupState,
        message: String,
        uploadEnabled: Boolean,
        configured: Boolean,
        uploadedBytes: Long? = null,
        sqlBytes: Long? = null,
        statementCount: Int? = null,
        objectKey: String? = null,
        lastSuccess: Long? = null,
    ): DbBackupStatus {
        val now = clock()
        val isAttempt = state != DbBackupState.NOT_CONFIGURED
        val st = DbBackupStatus(
            state = state,
            message = message,
            lastAttempt = if (isAttempt) now else status.lastAttempt,
            lastSuccess = lastSuccess ?: status.lastSuccess,
            uploadedBytes = uploadedBytes ?: status.uploadedBytes,
            sqlBytes = sqlBytes ?: status.sqlBytes,
            statementCount = statementCount ?: status.statementCount,
            objectKey = objectKey ?: status.objectKey,
            uploadEnabled = uploadEnabled,
            configured = configured,
        )
        status = st
        return st
    }

    companion object {
        const val CONTENT_TYPE_GZIP = "application/gzip"
        const val FIRST_CHECK_DELAY_MINUTES = 2L
        const val CHECK_INTERVAL_MINUTES = 15L

        /**
         * Delay before a capped periodic check's fast follow-up runs. While
         * chunks remain the backlog is drained near-continuously (a chain of
         * these); once a check ends uncapped the normal
         * [CHECK_INTERVAL_MINUTES] cadence takes over again. Injected via the
         * constructor ([fastFollowUpDelayMs]) so tests stay deterministic.
         */
        const val FAST_FOLLOW_DELAY_MS = 15_000L

        /**
         * Upper bound on the chunks one check uploads (the real stop signal is
         * the first uncapped chunk). Per-chunk memory is already bounded —
         * the dumper's ~16 MB SQL budget and its 2,000-new-torrent-id cap,
         * freed before the next chunk — so the chunk count does not widen the
         * heap envelope. With 64 chunks that is up to 128,000 torrents per
         * check; a check that exhausts this budget while still capped leaves
         * the daily date unwritten and chains a fast follow-up check
         * ([FAST_FOLLOW_DELAY_MS] later).
         */
        const val MAX_CHUNKS_PER_CHECK = 64

        private fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(ThreadFactory {
                Thread(it, "db-backup").apply { isDaemon = true }
            })
    }
}
