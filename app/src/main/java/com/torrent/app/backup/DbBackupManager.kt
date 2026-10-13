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
fun interface DbDumper {
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
 * [FIRST_CHECK_DELAY_MINUTES] after [start]) uploads at most once per
 * calendar day (per [zone]); a manual trigger (dashboard) bypasses the
 * daily gate only — the configured/busy/wifi gates always apply. Every check
 * runs on [executor] (a single-thread [ScheduledExecutorService] by
 * default) so at most one upload is in flight at a time; a trigger while one
 * is running is refused with "already running".
 *
 * Flow of a check: not configured ([BackupSettings.dbBackupEnabled] false or
 * [ConfigValidator.validateDbBackup] errors) →
 * [DbBackupState.NOT_CONFIGURED]; [isBusy] →
 * [DbBackupState.SKIPPED_BUSY]; no Wi-Fi → [DbBackupState.SKIPPED_NO_WIFI];
 * daily gate; DB file missing → [DbBackupState.SKIPPED_NO_DB];
 * [DbDumper] skips (empty range) → [DbBackupState.SKIPPED_EMPTY]; otherwise
 * PUT the gzip dump → [DbBackupState.SUCCESS] / [DbBackupState.FAILED].
 *
 * Upload-first ordering (the critical invariant): the [DbBookmark] is
 * advanced to the dump's [DbDumpOutcome.Dump.newCursor] ONLY after a
 * successful PUT, and the daily date is persisted right after that. A
 * failed/ killed upload leaves the bookmark unchanged, so the next run
 * re-dumps (and re-uploads) the exact same range — idempotent thanks to
 * `INSERT OR IGNORE`.
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
    private val executor: ScheduledExecutorService = defaultExecutor(),
    private val readLastSuccessDate: () -> String? = { null },
    private val writeLastSuccessDate: (String) -> Unit = {},
) {
    @Volatile
    var status: DbBackupStatus = DbBackupStatus()
        private set

    private val lock = Any()
    @Volatile
    private var started = false
    @Volatile
    private var inFlight = false

    /**
     * Queues an upload check on [executor] and returns the resulting
     * (optimistic) [status]. Refused with "already running" when a check is
     * in flight, or "shutting down" once [stop] has run. The returned
     * status is the trigger result — poll [status] for the outcome.
     */
    fun uploadNow(manual: Boolean): DbBackupStatus {
        val accepted = synchronized(lock) {
            if (inFlight) {
                // Only a manual trigger surfaces the refusal; a periodic tick
                // must not clobber the in-flight upload's status message.
                if (manual) {
                    status = status.copy(message = "refused: an upload is already running")
                }
                false
            } else {
                inFlight = true
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
                } catch (e: Exception) {
                    // The check must never take the periodic schedule down.
                    status = status.copy(
                        state = DbBackupState.FAILED,
                        message = "upload failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                } finally {
                    inFlight = false
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlight = false
            status = status.copy(state = DbBackupState.FAILED, message = "refused: service is shutting down")
        }
        return status
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
        val bookmark = DbBookmark.read(bookmarkFile())
        val outcome = try {
            dumperFactory(settings).dump(file, bookmark)
        } catch (e: Exception) {
            return finish(DbBackupState.FAILED, "dump failed: ${e.message ?: e.javaClass.simpleName}",
                uploadEnabled = true, configured = true)
        }
        val dump = when (outcome) {
            is DbDumpOutcome.Skipped ->
                return finish(DbBackupState.SKIPPED_EMPTY,
                    "skipped: no new torrents (max id ${outcome.maxTorrentId})",
                    uploadEnabled = true, configured = true)
            is DbDumpOutcome.Dump -> outcome
        }
        val result = try {
            clientFactory(settings).put(dump.objectKey, CONTENT_TYPE_GZIP, dump.sqlBytes)
        } catch (e: Exception) {
            return finish(DbBackupState.FAILED, "upload failed: ${e.message ?: e.javaClass.simpleName}",
                uploadEnabled = true, configured = true)
        }
        return when (result) {
            is S3PutResult.Success -> {
                // Upload-first: the bookmark advances ONLY after the PUT
                // landed, so a failed/killed upload re-dumps the same range
                // next run (INSERT OR IGNORE makes the re-upload idempotent).
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
                val now = clock()
                writeLastSuccessDate(today)
                finish(
                    DbBackupState.SUCCESS,
                    "uploaded ${dump.sqlBytes.size} bytes to ${dump.objectKey} " +
                        "(${dump.statementCount} statements)",
                    uploadEnabled = true, configured = true,
                    uploadedBytes = dump.sqlBytes.size.toLong(),
                    sqlBytes = dump.sqlBytes.size.toLong(),
                    statementCount = dump.statementCount,
                    objectKey = dump.objectKey, lastSuccess = now)
            }
            is S3PutResult.ClientError -> finish(
                DbBackupState.FAILED,
                "R2 rejected the upload (HTTP ${result.statusCode})",
                uploadEnabled = true, configured = true)
            is S3PutResult.ServerError -> finish(
                DbBackupState.FAILED,
                "R2 server error (HTTP ${result.statusCode} after ${result.attempts} attempts)",
                uploadEnabled = true, configured = true)
            is S3PutResult.Failure -> finish(
                DbBackupState.FAILED,
                "upload failed: ${result.cause.message ?: result.cause.javaClass.simpleName}",
                uploadEnabled = true, configured = true)
        }
    }

    /** Schedules the periodic (daily-gated) check; idempotent. */
    fun start() {
        if (started) return
        started = true
        executor.scheduleWithFixedDelay({ uploadNow(manual = false) },
            FIRST_CHECK_DELAY_MINUTES, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    /** Stops the scheduler; a queued/running check is dropped. */
    fun stop() {
        executor.shutdownNow()
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

        private fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(ThreadFactory {
                Thread(it, "db-backup").apply { isDaemon = true }
            })
    }
}
