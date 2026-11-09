package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigValidator
import com.torrent.app.s3.S3PutResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * One S3 object PUT of the compressed cache (plan: Stage 4). A thin seam over
 * [com.torrent.app.s3.S3Client.put] so unit tests can fake the upload; adapt
 * a real client with a lambda (the client itself is unchanged).
 */
fun interface S3Uploader {
    fun put(objectKey: String, contentType: String, body: ByteArray): S3PutResult
}

/** Outcome states of the last BEP51 cache upload check. */
enum class BackupState {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED_BUSY,
    SKIPPED_NO_WIFI,
    SKIPPED_NO_FILE,
    SKIPPED_EMPTY,
    NOT_CONFIGURED,
}

/**
 * Thread-safe snapshot of the backup manager for the dashboard
 * (`GET /api/crawler/backup`). [lastAttempt] is set only when a check gets
 * past the configured gate (a skip/attempt, not a config refusal);
 * [uploadedBytes] is the compressed (gzip) size of the last successful PUT.
 */
data class BackupStatus(
    val state: BackupState = BackupState.IDLE,
    val message: String = "",
    val lastAttempt: Long? = null,
    val lastSuccess: Long? = null,
    val uploadedBytes: Long? = null,
    val uploadEnabled: Boolean = false,
    val configured: Boolean = false,
    /**
     * True only while the upload's work runs (cache read → validate → gzip →
     * PUT); false while the check is queued, while a gate skips it, and after
     * the outcome is recorded.
     */
    val running: Boolean = false,
    /**
     * Epoch millis at which the current/last upload started. Kept after the
     * check ends, so the frame can still say when the last check ran.
     */
    val startedAt: Long? = null,
)

/**
 * Uploads the dht_crawler's BEP51 bootstrap cache
 * (`filesDir/crawler/data/bep51_cache.dat`) to Cloudflare R2 (Stage 4).
 *
 * A periodic check (every [CHECK_INTERVAL_MINUTES] minutes, first one
 * [FIRST_CHECK_DELAY_MINUTES] after [start]) uploads at most once per
 * calendar day (per [zone]); a manual trigger (dashboard) bypasses the
 * daily gate only — the configured/busy/wifi gates always apply. Every check
 * runs on [executor] (a single-thread [ScheduledExecutorService] by
 * default) so at most one upload is in flight at a time; a trigger while one
 * is running is refused with "already running".
 *
 * Flow of a check: not configured ([BackupSettings.uploadEnabled] false or
 * [ConfigValidator.validateBackup] errors) → [BackupState.NOT_CONFIGURED];
 * [isBusy] → [BackupState.SKIPPED_BUSY]; no Wi-Fi →
 * [BackupState.SKIPPED_NO_WIFI]; file missing →
 * [BackupState.SKIPPED_NO_FILE]; [Bep51CacheValidator] rejects →
 * [BackupState.FAILED]; 0 nodes → [BackupState.SKIPPED_EMPTY]; otherwise
 * gzip + PUT → [BackupState.SUCCESS] / [BackupState.FAILED]. On success the
 * local date is persisted via [writeLastSuccessDate] so the calendar-day
 * gate survives restarts.
 *
 * All collaborators are constructor-injected (plain-JUnit testable);
 * [clock] and [zone] drive the calendar-day gate.
 */
class Bep51BackupManager(
    private val cacheFile: () -> File,
    private val loadSettings: () -> BackupSettings,
    private val isBusy: () -> Boolean,
    private val isWifi: () -> Boolean,
    private val clientFactory: (BackupSettings) -> S3Uploader,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private var executor: ScheduledExecutorService = defaultExecutor(),
    private val readLastSuccessDate: () -> String? = { null },
    private val writeLastSuccessDate: (String) -> Unit = {},
) {
    @Volatile
    var status: BackupStatus = BackupStatus()
        private set

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
    private var inFlight = false

    /**
     * Queues an upload check on [executor] and returns the resulting
     * (optimistic) [status]. Refused with "already running" when a check is
     * in flight, or "shutting down" once [stop] has run. The returned
     * status is the trigger result — poll [status] for the outcome.
     */
    fun uploadNow(manual: Boolean): BackupStatus {
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
                // No RUNNING marker here: runCheck's gates "preserve the
                // previous state", which must be the last real outcome. RUNNING
                // is published by runCheck once the upload work actually starts.
                status = status.copy(message = "upload queued")
                true
            }
        }
        if (!accepted) return status
        try {
            executor.execute {
                try {
                    runCheck(manual)
                } catch (e: Throwable) {
                    // Throwable, not Exception: an OutOfMemoryError must mark
                    // the check FAILED and release inFlight — unwinding this
                    // frame frees the gzip buffer — instead of killing the
                    // process and wedging the backup managers. (The check must
                    // never take the periodic schedule down either.)
                    status = status.copy(
                        state = BackupState.FAILED,
                        message = "upload failed: ${e.message ?: e.javaClass.simpleName}",
                        running = false,
                    )
                } finally {
                    inFlight = false
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlight = false
            status = status.copy(
                state = BackupState.FAILED,
                message = "refused: service is shutting down",
                running = false,
            )
        }
        return status
    }

    /**
     * Re-evaluates only the config gates ([BackupSettings.uploadEnabled] +
     * [ConfigValidator.validateBackup] errors) against the current settings
     * and updates [status] synchronously — no executor, no network, no
     * upload. Called by the settings apply path (DaemonService's
     * saveBackupSettings) so the crawler panel's "R2 upload is disabled"
     * note reflects a just-applied change immediately instead of after the
     * next periodic check (up to [CHECK_INTERVAL_MINUTES] minutes later).
     *
     * A previous NOT_CONFIGURED state is reset to IDLE once the settings
     * are enabled and valid (that state only ever described the config
     * gate, which has just been cleared); any real outcome state
     * (SUCCESS/FAILED/SKIPPED_*) and its message is preserved, only the
     * config flags are corrected. Races with an in-flight check are
     * benign — status is a best-effort volatile snapshot and the check's
     * own finish() re-applies its outcome.
     */
    fun refreshConfigStatus() {
        val settings = try {
            loadSettings()
        } catch (e: Exception) {
            finish(BackupState.FAILED, "failed to load settings: ${e.message}",
                uploadEnabled = false, configured = false)
            return
        }
        if (!settings.uploadEnabled) {
            finish(BackupState.NOT_CONFIGURED, "R2 upload is disabled",
                uploadEnabled = false, configured = false)
            return
        }
        val errors = ConfigValidator.validateBackup(settings).errors
        if (errors.isNotEmpty()) {
            finish(BackupState.NOT_CONFIGURED,
                "not configured: " + errors.joinToString("; "),
                uploadEnabled = true, configured = false)
            return
        }
        status = if (status.state == BackupState.NOT_CONFIGURED)
            status.copy(state = BackupState.IDLE, message = "",
                uploadEnabled = true, configured = true)
        else
            status.copy(uploadEnabled = true, configured = true)
    }

    /**
     * Runs one full upload check synchronously (gates, validation, gzip,
     * PUT). Called on [executor] by [uploadNow]; package-internal so unit
     * tests can run it on the test thread.
     */
    internal fun runCheck(manual: Boolean): BackupStatus {
        val settings = try {
            loadSettings()
        } catch (e: Exception) {
            return finish(
                BackupState.FAILED, "failed to load settings: ${e.message}",
                uploadEnabled = false, configured = false)
        }
        val enabled = settings.uploadEnabled
        val errors = ConfigValidator.validateBackup(settings).errors
        if (!enabled) {
            return finish(BackupState.NOT_CONFIGURED, "R2 upload is disabled",
                uploadEnabled = false, configured = false)
        }
        if (errors.isNotEmpty()) {
            return finish(BackupState.NOT_CONFIGURED,
                "not configured: " + errors.joinToString("; "),
                uploadEnabled = true, configured = false)
        }
        if (isBusy()) {
            return finish(BackupState.SKIPPED_BUSY,
                "skipped: an apply/maintenance job is running",
                uploadEnabled = true, configured = true)
        }
        if (!isWifi()) {
            return finish(BackupState.SKIPPED_NO_WIFI, "skipped: no Wi-Fi",
                uploadEnabled = true, configured = true)
        }
        val today = todayString()
        if (!manual && readLastSuccessDate() == today) {
            // Daily gate: already uploaded today. Keep the previous state, but
            // route through finish() so lastAttempt is updated (per the
            // BackupStatus KDoc).
            return finish(
                status.state,
                "skipped: already uploaded today ($today)",
                uploadEnabled = true, configured = true)
        }
        val file = cacheFile()
        if (!file.exists()) {
            return finish(BackupState.SKIPPED_NO_FILE, "skipped: cache file is missing",
                uploadEnabled = true, configured = true)
        }
        // Every gate passed and the upload work starts here: publish the
        // truthful RUNNING state. Deliberately AFTER the gates — the daily gate
        // above preserves status.state, so a RUNNING marker set earlier would
        // survive a "skipped: already uploaded today" as a stale running job.
        // Phase-only: read → validate → gzip → one PUT has no denominator.
        status = status.copy(
            state = BackupState.RUNNING,
            message = "uploading",
            running = true,
            startedAt = clock(),
        )
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return finish(BackupState.FAILED, "failed to read cache file: ${e.message}",
                uploadEnabled = true, configured = true)
        }
        when (val check = Bep51CacheValidator.validate(bytes)) {
            is Bep51CacheCheck.Invalid ->
                return finish(BackupState.FAILED, "invalid cache: ${check.reason}",
                    uploadEnabled = true, configured = true)
            is Bep51CacheCheck.Valid ->
                if (check.nodeCount == 0) {
                    return finish(BackupState.SKIPPED_EMPTY,
                        "skipped: cache is empty (0 nodes)",
                        uploadEnabled = true, configured = true)
                }
        }
        val gz = gzip(bytes)
        val result = try {
            clientFactory(settings).put(settings.r2ObjectKey, CONTENT_TYPE_GZIP, gz)
        } catch (e: Exception) {
            return finish(BackupState.FAILED, "upload failed: ${e.message ?: e.javaClass.simpleName}",
                uploadEnabled = true, configured = true)
        }
        return when (result) {
            is S3PutResult.Success -> {
                val now = clock()
                writeLastSuccessDate(today)
                finish(
                    BackupState.SUCCESS,
                    "uploaded ${gz.size} bytes to ${settings.r2ObjectKey}",
                    uploadEnabled = true, configured = true,
                    uploadedBytes = gz.size.toLong(), lastSuccess = now)
            }
            is S3PutResult.ClientError -> finish(
                BackupState.FAILED,
                "R2 rejected the upload (HTTP ${result.statusCode})",
                uploadEnabled = true, configured = true)
            is S3PutResult.ServerError -> finish(
                BackupState.FAILED,
                "R2 server error (HTTP ${result.statusCode} after ${result.attempts} attempts)",
                uploadEnabled = true, configured = true)
            is S3PutResult.Failure -> finish(
                BackupState.FAILED,
                "upload failed: ${result.cause.message ?: result.cause.javaClass.simpleName}",
                uploadEnabled = true, configured = true)
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
            inFlight = false
        }
    }

    /** Local calendar date (per [clock]/[zone]) of "today", `yyyy-MM-dd`. */
    internal fun todayString(): String =
        Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toString()

    private fun finish(
        state: BackupState,
        message: String,
        uploadEnabled: Boolean,
        configured: Boolean,
        uploadedBytes: Long? = null,
        lastSuccess: Long? = null,
    ): BackupStatus {
        val now = clock()
        val isAttempt = state != BackupState.NOT_CONFIGURED
        val st = BackupStatus(
            state = state,
            message = message,
            lastAttempt = if (isAttempt) now else status.lastAttempt,
            lastSuccess = lastSuccess ?: status.lastSuccess,
            uploadedBytes = uploadedBytes ?: status.uploadedBytes,
            uploadEnabled = uploadEnabled,
            configured = configured,
            // The upload's live signal ends with this outcome; the run's start
            // stamp stays visible so a frame can still say when it ran.
            running = false,
            startedAt = status.startedAt,
        )
        status = st
        return st
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    companion object {
        const val CONTENT_TYPE_GZIP = "application/gzip"
        const val FIRST_CHECK_DELAY_MINUTES = 2L
        const val CHECK_INTERVAL_MINUTES = 15L

        private fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(ThreadFactory {
                Thread(it, "bep51-backup").apply { isDaemon = true }
            })
    }
}
