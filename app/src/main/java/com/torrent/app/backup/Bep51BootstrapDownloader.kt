package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigValidator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * Outcome of one BEP51 bootstrap-cache download (Stage 5).
 */
sealed class BootstrapResult {
    /** The cache was validated and atomically placed at [dest]. */
    data class Placed(val nodeCount: Int, val bytes: Long) : BootstrapResult()

    /** The destination file already existed; nothing was downloaded. */
    data object SkippedExists : BootstrapResult()

    /** Download/validation/placement failed; the destination is untouched. */
    data class Failed(val reason: String) : BootstrapResult()
}

/**
 * Downloads the dht_crawler's BEP51 bootstrap cache (`bep51_cache.dat`) from
 * an anonymous HTTPS URL for fresh installs (Stage 5) and places it
 * atomically so the crawler warm-starts.
 *
 * Pure Kotlin (no Android deps): [opener], the timeouts, [maxBytes] and
 * [clock] are constructor-injected for host-JVM unit tests.
 *
 * Safety rules:
 *  - only `https://` URLs with a host are accepted (`http://` is refused);
 *  - the compressed download AND the decompressed output are both capped at
 *    [maxBytes] (gzip-bomb / huge-file guard);
 *  - the payload must pass [Bep51CacheValidator] with at least one node
 *    before anything is written to disk;
 *  - the file is written to a temp file in [tempDir] (which must be on the
 *    same filesystem as [dest] — the caller passes `dest.parentFile`),
 *    fsynced, re-checked, then atomically renamed;
 *  - every failure returns [BootstrapResult.Failed] and never throws; the
 *    destination is left untouched and the temp file is deleted.
 */
class Bep51BootstrapDownloader(
    private val opener: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Downloads [url] and places it at [dest] (via a temp file in
     * [tempDir]) when [dest] does not exist yet. Never throws.
     */
    fun bootstrap(url: String, dest: File, tempDir: File): BootstrapResult {
        try {
            if (dest.exists()) return BootstrapResult.SkippedExists
            if (!ConfigValidator.validHttpsUrl(url)) {
                return BootstrapResult.Failed(
                    "bootstrap URL must be an https:// URL with a host")
            }
            val compressed = download(url)
            val data = if (isGzip(compressed)) gunzip(compressed) else compressed
            return when (val check = Bep51CacheValidator.validate(data)) {
                is Bep51CacheCheck.Invalid ->
                    BootstrapResult.Failed("downloaded cache is invalid: ${check.reason}")
                is Bep51CacheCheck.Valid ->
                    if (check.nodeCount == 0) {
                        BootstrapResult.Failed("downloaded cache is empty (0 nodes)")
                    } else {
                        place(data, check.nodeCount, dest, tempDir)
                    }
            }
        } catch (e: Exception) {
            return BootstrapResult.Failed("bootstrap failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Anonymous plain GET of [url] with the configured timeouts and [maxBytes] cap. */
    private fun download(url: String): ByteArray {
        val conn = opener(URL(url)).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            // No redirects: the user configured this exact URL, and a
            // protocol/host change (e.g. https → http) must never happen
            // silently.
            instanceFollowRedirects = false
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { readCapped(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) {
                throw IOException("payload exceeds the ${maxBytes}-byte cap")
            }
        }
        return out.toByteArray()
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1

    /** Gunzip [compressed], capping the DECOMPRESSED size at [maxBytes] (bomb guard). */
    private fun gunzip(compressed: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(compressed)).use { readCapped(it) }

    /** fsync + re-check + atomic rename of [data] into [dest]; deletes [temp] on failure. */
    private fun place(data: ByteArray, nodeCount: Int, dest: File, tempDir: File): BootstrapResult {
        dest.parentFile?.mkdirs()
        tempDir.mkdirs()
        val temp = File(tempDir, ".bep51_cache.dat.bootstrap-${tempCounter.incrementAndGet()}.tmp")
        try {
            FileOutputStream(temp).use { os ->
                os.write(data)
                os.flush()
                // fsync (FileDescriptor.sync is the Android/JVM spelling of fdSync)
                os.fd.sync()
            }
            if (dest.exists()) {
                // Placed between our first check and now — do not overwrite.
                return BootstrapResult.SkippedExists
            }
            val destPath = dest.toPath()
            try {
                Files.move(temp.toPath(), destPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                if (!temp.renameTo(dest)) {
                    Files.move(temp.toPath(), destPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return BootstrapResult.Placed(nodeCount, data.size.toLong())
        } finally {
            if (temp.exists()) temp.delete() // best effort; a no-op after a successful move
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        /** Cap for BOTH the compressed download and the decompressed output (16 MiB). */
        const val DEFAULT_MAX_BYTES = 16L * 1024 * 1024
        /** A failed bootstrap attempt is not retried for at least this long (1 hour). */
        const val FAILURE_RETRY_INTERVAL_MS = 60L * 60L * 1000L
        private const val GZIP_MAGIC_0 = 0x1F.toByte()
        private const val GZIP_MAGIC_1 = 0x8B.toByte()
        private val tempCounter = AtomicLong()

        /**
         * Pure gate for the bootstrap (Stage 5): bootstrap enabled + a valid
         * https URL + a missing cache file + not within the retry interval
         * after the last failed attempt.
         */
        fun shouldBootstrap(
            settings: BackupSettings,
            cacheExists: Boolean,
            lastFailureAt: Long?,
            now: Long,
        ): Boolean {
            if (!settings.bootstrapEnabled) return false
            if (settings.bootstrapUrl.isBlank()) return false
            if (!ConfigValidator.validHttpsUrl(settings.bootstrapUrl)) return false
            if (cacheExists) return false
            if (lastFailureAt != null && now - lastFailureAt < FAILURE_RETRY_INTERVAL_MS) return false
            return true
        }
    }
}
