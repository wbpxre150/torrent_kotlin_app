package com.torrent.app.ftp

import android.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bounds FTP's pressure on the SAF provider (plan 07 §2.2): at most [PERMITS]
 * concurrent ContentResolver metadata/open calls from FTP, leaving the
 * ExternalStorageProvider binder pool for transmission IPC.
 *
 * The semaphore is fair, and acquisition uses a timeout so a wedged provider
 * cannot stall FTP control threads forever (the call simply fails with null).
 * Streaming reads on an already-open fd are NOT under this semaphore.
 */
object FtpSafThrottle {
    const val PERMITS = 2
    const val DEFAULT_TIMEOUT_MS = 30_000L

    private val semaphore = Semaphore(PERMITS, /* fair = */ true)

    fun <T> withLimit(block: () -> T): T? = withLimit(DEFAULT_TIMEOUT_MS, block)

    fun <T> withLimit(timeoutMs: Long, block: () -> T): T? {
        var acquired = false
        try {
            acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
            if (!acquired) {
                runCatching { Log.w("FtpSafThrottle", "timed out after ${timeoutMs}ms; dropping FTP SAF call") }
                return null
            }
            return block()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            if (acquired) semaphore.release()
        }
    }
}
