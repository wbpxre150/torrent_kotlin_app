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
    private val executor: ScheduledExecutorService = defaultExecutor(),
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
     * Queues an upload check on [executor] and returns the resulting
     * (optimistic) [status]. Refused with "already running" when a check is
     * in flight, or "shutting down" once [stop] has run. The returned
     * status is the trigger result — poll [status] for the outcome.
     */
    fun uploadNow(manual: Boolean): BackupStatus {
        val accepted = synchronized(lock) {
            if (status.state == BackupState.RUNNING) {
                status = status.copy(message = "refused: an upload is already running")
                false
            } else {
                status = status.copy(state = BackupState.RUNNING, message = "upload queued")
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
                        state = BackupState.FAILED,
                        message = "upload failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        } catch (e: RejectedExecutionException) {
            status = status.copy(state = BackupState.FAILED, message = "refused: service is shutting down")
        }
        return status
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
                "skipped: an apply/maintenance/migration job is running",
                uploadEnabled = true, configured = true)
        }
        if (!isWifi()) {
            return finish(BackupState.SKIPPED_NO_WIFI, "skipped: no Wi-Fi",
                uploadEnabled = true, configured = true)
        }
        val today = todayString()
        if (!manual && readLastSuccessDate() == today) {
            // Daily gate: already uploaded today. Keep the previous state.
            return status.copy(
                message = "skipped: already uploaded today ($today)",
                uploadEnabled = true, configured = true)
        }
        val file = cacheFile()
        if (!file.exists()) {
            return finish(BackupState.SKIPPED_NO_FILE, "skipped: cache file is missing",
                uploadEnabled = true, configured = true)
        }
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
