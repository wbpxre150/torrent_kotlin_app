package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigValidator
import com.torrent.app.s3.S3DeleteResult
import com.torrent.app.s3.S3ListResult
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
 * Lists and deletes the objects of one bucket prefix — the seam for the
 * "reset backup from zero" purge. A thin seam over
 * [com.torrent.app.s3.S3Client.list] / [com.torrent.app.s3.S3Client.delete]
 * so unit tests can fake the purge; adapt a real client with an object
 * expression (the client itself is unchanged). A DELETE that answers 404
 * means the object is already gone.
 */
interface S3DbPurger {
    fun list(prefix: String): S3ListResult
    fun delete(objectKey: String): S3DeleteResult
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
    /**
     * A `db_reset_pending` marker exists: a "reset from zero" purge is
     * unfinished, so uploads are blocked until a later check completes it.
     */
    val resetPending: Boolean = false,
    /**
     * True only while a check's drain loop is actually working (mirrors
     * [DbRestoreStatus.running]): false while the check is merely queued, while
     * a gate decides to skip, and after the outcome is recorded.
     */
    val running: Boolean = false,
    /**
     * Epoch millis at which the current/last drain loop started. Kept after the
     * check ends, so the frame can still say when the last check ran.
     */
    val startedAt: Long? = null,
    /**
     * Chunks uploaded by the current/last drain (0 while a fresh check runs, the
     * final count once it finished). There is no backlog total to divide by —
     * the cursor is a [DbBookmark] — so this is a counter, never a percentage.
     */
    val chunks: Int? = null,
    /** Bytes (SQL artifact + class-index sidecar) uploaded by those chunks. */
    val totalBytes: Long? = null,
    /** SQL statements contained in those chunks. */
    val totalStatements: Int? = null,
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
 * chunk's PUTs succeeded — the SQL artifact AND its class-index sidecar.
 * A failed/killed upload leaves the bookmark unchanged, so the next run
 * re-dumps (and re-uploads) the exact same range — idempotent thanks to
 * `INSERT OR IGNORE`. A failure in the middle of a drain likewise costs only
 * the failed chunk: every earlier chunk's PUTs were already followed by its
 * own bookmark write.
 *
 * Reset from zero (crash-safe): [resetNow] deletes every object under
 * `<r2DbObjectPrefix>/` in the DB bucket, drops the local [DbBookmark] and
 * the persisted daily date, then drains the whole database again. The order
 * inside such a check is gate checks → marker → purge → daily gate, so a
 * pending purge is never blocked by the daily gate and never runs when the
 * configured/busy/wifi gates fail (no marker is written then either). A
 * durable `db_reset_pending` marker file next to the bookmark is written
 * BEFORE the first delete and removed only once the purge, the bookmark and
 * the daily date are all cleared: a purge that fails halfway (or a process
 * killed mid-purge) leaves the marker behind, which fails the check with a
 * "reset pending:" message, blocks every later upload from re-uploading onto
 * a half-deleted prefix, and makes the next check retry the purge. Deletes
 * are prefix-only: an object whose key does not start with the prefix is
 * never deleted, whatever the server returns.
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
    private val purgerFactory: (BackupSettings) -> S3DbPurger = {
        throw IllegalStateException("reset not wired")
    },
    private val resetMarkerFile: () -> File = {
        File(bookmarkFile().parentFile, "db_reset_pending")
    },
    private val clearLastSuccessDate: () -> Unit = {},
) {
    @Volatile
    var status: DbBackupStatus = DbBackupStatus()
        private set

    /**
     * Live "a check is in flight" signal for the busy predicates: true
     * from acceptance in [uploadNow] (including the time it is queued on
     * [executor]) until the check completes. It is deliberately BROADER than
     * [DbBackupStatus.running]: a queued or gated check is in flight but not yet
     * running. [status.state] only becomes [DbBackupState.RUNNING] while the
     * drain loop actually works, so the previous real outcome is preserved
     * through the gates and a skip never reports RUNNING.
     */
    val inFlight: Boolean
        get() = inFlightFlag

    /**
     * True while an unfinished "reset from zero" purge is pending: the
     * `db_reset_pending` marker exists, so every upload is blocked (and the
     * next check retries the purge). Unreadable marker path reads as false.
     */
    val resetPending: Boolean
        get() = runCatching { resetMarkerFile().exists() }.getOrDefault(false)

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
    fun uploadNow(manual: Boolean): DbBackupStatus =
        enqueue(manual = manual, reset = false, queuedMessage = "db backup queued")

    /**
     * Queues a "reset backup from zero" check on [executor]: every object
     * under `<r2DbObjectPrefix>/` is deleted, the local bookmark and the
     * persisted daily date are dropped, and the whole database is drained
     * again (see "Reset from zero" in the class KDoc). It runs as a
     * non-manual check, so a still-capped drain keeps chaining fast
     * follow-ups until the backlog catches up. The purge starts on
     * [executor]; poll [status] for the outcome.
     */
    fun resetNow(): DbBackupStatus =
        enqueue(manual = false, reset = true, queuedMessage = "db backup reset queued")

    /**
     * Shared acceptance/enqueue body of [uploadNow] and [resetNow]. A trigger
     * is operator-initiated when [manual] or [reset]: only then is the
     * in-flight refusal written to [status], so a periodic tick never clobbers
     * the running check's message. [queuedMessage] is the optimistic message
     * of an accepted trigger; its queued task runs `runCheck(manual, reset)`.
     */
    private fun enqueue(manual: Boolean, reset: Boolean, queuedMessage: String): DbBackupStatus {
        val accepted = synchronized(lock) {
            if (inFlightFlag) {
                // Only an operator-initiated trigger surfaces the refusal; a
                // periodic tick must not clobber the in-flight upload's status
                // message.
                if (manual || reset) {
                    status = status.copy(message = "refused: an upload is already running")
                }
                false
            } else {
                inFlightFlag = true
                // No RUNNING marker here: runCheck's gates "preserve the
                // previous state", which must be the last real outcome. RUNNING
                // is published by runCheck itself once the drain loop starts.
                status = status.copy(message = queuedMessage)
                true
            }
        }
        if (!accepted) return status
        try {
            executor.execute {
                try {
                    runCheck(manual, reset)
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
                        running = false,
                    )
                } finally {
                    inFlightFlag = false
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlightFlag = false
            status = status.copy(
                state = DbBackupState.FAILED,
                message = "refused: service is shutting down",
                running = false,
            )
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
     * [reset] additionally requests a "reset from zero": the durable marker
     * is written once the wifi gate passed, a pending purge runs before the
     * daily gate, and the purge's object count prefixes the outcome message.
     * Called on [executor] by [enqueue]; package-internal so unit tests
     * can run it on the test thread.
     */
    internal fun runCheck(manual: Boolean, reset: Boolean = false): DbBackupStatus {
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
        // Reset from zero: gate checks → marker → purge → daily gate. The
        // marker is written BEFORE the first delete, so a purge that fails
        // halfway (or a process killed mid-purge) blocks every later upload
        // and is retried by the next check. The daily gate is consulted only
        // afterwards: a pending purge must never wait for tomorrow, and a
        // failing gate above never writes a marker.
        var purgedCount: Int? = null
        if (reset) {
            val marker = resetMarkerFile()
            val markerFailure = try {
                marker.parentFile?.mkdirs()
                marker.writeText("")
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (markerFailure != null) {
                return finish(
                    DbBackupState.FAILED,
                    "reset failed: could not write marker: $markerFailure",
                    uploadEnabled = true, configured = true)
            }
        }
        if (resetPending) {
            when (val purged = purge(settings)) {
                is PurgeResult.Ok -> purgedCount = purged.count
                is PurgeResult.Failed -> return finish(
                    DbBackupState.FAILED, purged.message,
                    uploadEnabled = true, configured = true)
            }
        }
        val resetPrefix =
            if (purgedCount != null) "reset: deleted $purgedCount objects; " else ""
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
            return finish(DbBackupState.SKIPPED_NO_DB, resetPrefix + "skipped: crawler DB is missing",
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
        // Every gate passed and a dump is about to run: this is where the check
        // actually starts working, so publish the truthful RUNNING state and
        // reset the drain counters. Deliberately AFTER the gates — the daily
        // gate above preserves status.state, so an earlier RUNNING marker would
        // survive a "skipped: already uploaded today" as a stale running job.
        status = status.copy(
            state = DbBackupState.RUNNING,
            message = "uploading",
            running = true,
            startedAt = clock(),
            chunks = 0,
            totalBytes = 0L,
            totalStatements = 0,
        )
        var chunks = 0
        var totalBytes = 0L
        var totalStatements = 0
        var totalIndexBytes = 0L
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
                    resetPrefix + "skipped: no new torrents (max id ${outcome.maxTorrentId})",
                    uploadEnabled = true, configured = true)
            }
            val dump = outcome as DbDumpOutcome.Dump
            val uploadFailure = putObject(settings, dump.objectKey, dump.sqlBytes)
            if (uploadFailure != null) {
                return finish(DbBackupState.FAILED, uploadFailure,
                    uploadEnabled = true, configured = true)
            }
            // The class-index sidecar goes up right after its SQL artifact and
            // still BEFORE the bookmark write: a sidecar failure fails the
            // check with the bookmark unchanged, so the next run re-dumps and
            // re-uploads BOTH objects (INSERT OR IGNORE keeps that idempotent).
            val indexFailure = putObject(settings, dump.sidecarObjectKey, dump.sidecarBytes)
            if (indexFailure != null) {
                return finish(DbBackupState.FAILED, "class index: $indexFailure",
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
            totalBytes += dump.sqlBytes.size + dump.sidecarBytes.size
            totalStatements += dump.statementCount
            totalIndexBytes += dump.sidecarBytes.size
            // Only the last chunk's dump is retained (for the status fields);
            // the earlier chunks' byte arrays are released as the loop runs.
            lastDump = dump
            // Publish the drain's own counters after every chunk, so a 1 Hz
            // observer sees them advance between "queued" and the terminal
            // message. No percentage: a DbBookmark cursor has no backlog total.
            status = status.copy(
                state = DbBackupState.RUNNING,
                running = true,
                chunks = chunks,
                totalBytes = totalBytes,
                totalStatements = totalStatements,
            )
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
        // The byte figures include the class-index sidecars (each chunk PUTs
        // one); the index share is spelled out separately.
        val message = (if (chunks > 1) {
            "uploaded $chunks chunks, $totalBytes bytes to ${last.objectKey} " +
                "($totalStatements statements)"
        } else {
            "uploaded ${last.sqlBytes.size + last.sidecarBytes.size} bytes to " +
                "${last.objectKey} (${last.statementCount} statements)"
        }) + ", +$totalIndexBytes index bytes"
        return finish(
            DbBackupState.SUCCESS,
            resetPrefix + (if (last.capped) {
                // Budget exhausted while still capped: the daily date is
                // deliberately NOT written (see the class KDoc), and a
                // periodic check chains a fast follow-up instead of waiting
                // for the next 15-minute tick.
                if (!manual) scheduleFastFollowUp()
                "$message; backlog continues " +
                    "(capped at torrent id ${cursor.lastTorrentId})"
            } else {
                message
            }),
            uploadEnabled = true, configured = true,
            uploadedBytes = last.sqlBytes.size.toLong(),
            sqlBytes = last.sqlBytes.size.toLong(),
            statementCount = last.statementCount,
            objectKey = last.objectKey, lastSuccess = clock())
    }

    /** Outcome of [purge]: the number of deleted objects, or a failure message. */
    private sealed class PurgeResult {
        data class Ok(val count: Int) : PurgeResult()
        data class Failed(val message: String) : PurgeResult()
    }

    /**
     * Deletes every object under `<r2DbObjectPrefix>/` in the DB bucket, then
     * drops the local cursor and the persisted daily date — the "reset from
     * zero" half of [resetNow]. LIST is repeated while it answers
     * [S3ListResult.Capped] (at most [MAX_PURGE_LIST_ROUNDS] rounds), and only
     * keys that start with the prefix are ever deleted. The first failure
     * aborts with a `reset pending:` message and leaves the marker in place,
     * so the next check retries the purge instead of uploading onto a
     * half-deleted prefix. A DELETE answering 404 means the object is already
     * gone and counts as success.
     */
    private fun purge(settings: BackupSettings): PurgeResult {
        val prefix = settings.r2DbObjectPrefix + "/"
        require(prefix.length > 1) { "refusing to purge the whole bucket: empty r2DbObjectPrefix" }
        val purger = try {
            purgerFactory(settings)
        } catch (e: Exception) {
            return PurgeResult.Failed("reset pending: ${e.message ?: e.javaClass.simpleName}")
        }
        var deleted = 0
        var round = 0
        while (true) {
            if (round == MAX_PURGE_LIST_ROUNDS) {
                return PurgeResult.Failed("reset pending: purge did not converge")
            }
            round++
            val listing = try {
                purger.list(prefix)
            } catch (e: Exception) {
                return PurgeResult.Failed(
                    "reset pending: list failed: ${e.message ?: e.javaClass.simpleName}")
            }
            val listed = when (listing) {
                is S3ListResult.Success -> listing.objects
                is S3ListResult.Capped -> listing.objects
                is S3ListResult.ClientError -> return PurgeResult.Failed(
                    "reset pending: list failed: HTTP ${listing.statusCode}")
                is S3ListResult.ServerError -> return PurgeResult.Failed(
                    "reset pending: list failed: HTTP ${listing.statusCode} " +
                        "after ${listing.attempts} attempts")
                is S3ListResult.Failure -> return PurgeResult.Failed(
                    "reset pending: list failed: " +
                        "${listing.cause.message ?: listing.cause.javaClass.simpleName}")
            }
            for (objectKey in listed.map { it.key }) {
                // Prefix-only: a key the server returned outside the prefix is
                // never deleted, and never counted.
                if (!objectKey.startsWith(prefix)) continue
                val result = try {
                    purger.delete(objectKey)
                } catch (e: Exception) {
                    return PurgeResult.Failed(
                        "reset pending: delete of $objectKey failed: " +
                            "${e.message ?: e.javaClass.simpleName}")
                }
                when (result) {
                    is S3DeleteResult.Success -> Unit
                    // 404: already gone (a retried purge after a partial one).
                    is S3DeleteResult.ClientError -> if (result.statusCode != 404) {
                        return PurgeResult.Failed(
                            "reset pending: delete of $objectKey failed: " +
                                "HTTP ${result.statusCode}")
                    }
                    is S3DeleteResult.ServerError -> return PurgeResult.Failed(
                        "reset pending: delete of $objectKey failed: " +
                            "HTTP ${result.statusCode} after ${result.attempts} attempts")
                    is S3DeleteResult.Failure -> return PurgeResult.Failed(
                        "reset pending: delete of $objectKey failed: " +
                            "${result.cause.message ?: result.cause.javaClass.simpleName}")
                }
                deleted++
                // Poller-visible progress on a long purge; the check's final
                // finish() overwrites the message with its outcome.
                if (deleted % 100 == 0) {
                    status = status.copy(message = "reset: deleted $deleted objects")
                }
            }
            if (listing is S3ListResult.Success) break
        }
        // The prefix is empty now: drop the local cursor, the daily date and
        // only then the marker (a failure here keeps the marker, so the next
        // check re-lists an empty prefix and finishes the job).
        val bookmark = bookmarkFile()
        if (bookmark.exists() && !bookmark.delete()) {
            return PurgeResult.Failed("reset pending: bookmark delete failed: ${bookmark.path}")
        }
        clearLastSuccessDate()
        val marker = resetMarkerFile()
        if (marker.exists() && !marker.delete()) {
            return PurgeResult.Failed("reset pending: marker delete failed: ${marker.path}")
        }
        // finish() carries these over from the previous status, so they must be
        // cleared on the status itself BEFORE the check's final finish().
        status = status.copy(lastSuccess = null, uploadedBytes = null, sqlBytes = null,
            statementCount = null, objectKey = null)
        return PurgeResult.Ok(deleted)
    }

    /**
     * One object PUT of a check, mapped to a failure message (`null` = the
     * object landed). Both objects of a chunk — the SQL artifact and its
     * class-index sidecar — go through this, so they share the exact same
     * [S3PutResult] handling (any failure → [DbBackupState.FAILED], bookmark
     * unchanged).
     */
    private fun putObject(
        settings: BackupSettings,
        objectKey: String,
        body: ByteArray,
    ): String? {
        val result = try {
            clientFactory(settings).put(objectKey, CONTENT_TYPE_GZIP, body)
        } catch (e: Exception) {
            return "upload failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return when (result) {
            is S3PutResult.Success -> null
            is S3PutResult.ClientError ->
                "R2 rejected the upload (HTTP ${result.statusCode})"
            is S3PutResult.ServerError ->
                "R2 server error (HTTP ${result.statusCode} after ${result.attempts} attempts)"
            is S3PutResult.Failure ->
                "upload failed: ${result.cause.message ?: result.cause.javaClass.simpleName}"
        }
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
            // Read from the marker file, not carried over: the flag must stay
            // truthful even after a restart that left a purge unfinished.
            resetPending = resetPending,
            // The drain's live signal ends with this outcome; the run's start
            // stamp and its counters stay visible so a frame can still say when
            // the last check ran and how much it moved.
            running = false,
            startedAt = status.startedAt,
            chunks = status.chunks,
            totalBytes = status.totalBytes,
            totalStatements = status.totalStatements,
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

        /**
         * Upper bound on LIST→DELETE rounds of one reset purge. The purge ends
         * on the first uncapped listing; a server that keeps answering
         * `Capped` (with objects left) fails the check with
         * "reset pending: purge did not converge" instead of looping forever.
         */
        internal const val MAX_PURGE_LIST_ROUNDS = 50

        private fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(ThreadFactory {
                Thread(it, "db-backup").apply { isDaemon = true }
            })
    }
}
