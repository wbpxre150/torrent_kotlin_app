package com.torrent.app.s3

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** Result of an S3 object PUT. */
sealed class S3PutResult {
    /** 2xx response. [etag] is the server's `ETag` header with surrounding quotes stripped (null when absent). */
    data class Success(val statusCode: Int, val etag: String?) : S3PutResult()

    /** 4xx response — treated as permanent, returned without retry. */
    data class ClientError(val statusCode: Int, val bodySnippet: String) : S3PutResult()

    /** 5xx (or other non-2xx/4xx) response on the final attempt after retries were exhausted. */
    data class ServerError(val statusCode: Int, val bodySnippet: String, val attempts: Int) : S3PutResult()

    /** Transport-level ([java.io.IOException]) failure on the final attempt after retries were exhausted. */
    data class Failure(val cause: Exception) : S3PutResult()
}

/**
 * Minimal S3-compatible object PUT client (Cloudflare R2: path-style addressing, region "auto",
 * service "s3") over plain [HttpURLConnection] — the house HTTP pattern (see
 * [com.torrent.app.transmission.TransmissionRpcClient] for the conventions).
 *
 * [opener], [sleeper] and [timeSource] are injectable for host-JVM unit tests.
 *
 * Retry policy: [maxAttempts] attempts total, exponential backoff from [retryDelaysMs] between
 * attempts, on 5xx/other non-2xx/4xx responses and on [IOException]. 4xx responses are returned
 * immediately as [S3PutResult.ClientError] (no retry).
 */
class S3Client(
    private val endpoint: String,
    private val bucket: String,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val region: String = DEFAULT_REGION,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxAttempts: Int = 3,
    private val retryDelaysMs: List<Long> = listOf(1000L, 2000L, 4000L),
    private val opener: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val timeSource: () -> Long = System::currentTimeMillis,
) {

    /**
     * Puts [body] to `endpoint/bucket/objectKey` (path-style; each segment percent-encoded) as
     * a SigV4-signed PUT with the given [contentType].
     */
    fun put(objectKey: String, contentType: String, body: ByteArray): S3PutResult {
        val url = objectUrl(objectKey)
        val path = "/" + SigV4.encodeS3PathSegment(bucket) + "/" + encodedKeyPath(objectKey)
        val date = amzDate(timeSource())
        val signed = SigV4.sign(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            method = "PUT",
            host = hostHeader(url),
            path = path,
            payload = body,
            timestamp = date,
            region = region,
            service = SERVICE,
        )
        var lastRetryable: PutOutcome? = null
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) sleeper(retryDelaysMs.getOrElse(attempt - 2) { retryDelaysMs.lastOrNull() ?: 0L })
            when (val outcome = attemptPut(url, signed.headers, contentType, body)) {
                is PutOutcome.Success -> return S3PutResult.Success(outcome.statusCode, outcome.etag)
                is PutOutcome.ClientError -> return S3PutResult.ClientError(outcome.statusCode, outcome.snippet)
                is PutOutcome.RetryableServer -> lastRetryable = outcome
                is PutOutcome.Transport -> lastRetryable = outcome
            }
        }
        return when (val last = lastRetryable) {
            is PutOutcome.RetryableServer -> S3PutResult.ServerError(last.statusCode, last.snippet, maxAttempts)
            is PutOutcome.Transport -> S3PutResult.Failure(last.exception)
            // Only RetryableServer/Transport are ever stored in lastRetryable; the other
            // outcomes return above. else also covers the (impossible) null.
            else -> S3PutResult.Failure(
                IllegalStateException("PUT of $objectKey failed without a recorded outcome"),
            )
        }
    }

    /** Path-style object URL: `endpoint/bucket/key` with every segment percent-encoded. */
    fun objectUrl(objectKey: String): URL =
        URL("${endpoint.trimEnd('/')}/${SigV4.encodeS3PathSegment(bucket)}/${encodedKeyPath(objectKey)}")

    private fun encodedKeyPath(objectKey: String): String =
        objectKey.split('/').joinToString("/") { SigV4.encodeS3PathSegment(it) }

    private fun hostHeader(url: URL): String =
        if (url.port == -1) url.host else "${url.host}:${url.port}"

    private fun amzDate(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'")
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }

    private fun attemptPut(
        url: URL,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray,
    ): PutOutcome {
        val conn = try {
            opener(url).apply {
                requestMethod = "PUT"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", contentType)
                for ((name, value) in headers) setRequestProperty(name, value)
            }
        } catch (e: IOException) {
            return PutOutcome.Transport(e)
        }
        return try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                PutOutcome.Success(code, etagOf(conn))
            } else if (code in 400..499) {
                PutOutcome.ClientError(code, bodySnippet(conn))
            } else {
                // 3xx/5xx: retryable. A signed PUT should answer 2xx or 4xx; anything else is
                // treated as a transient server-side condition.
                PutOutcome.RetryableServer(code, bodySnippet(conn))
            }
        } catch (e: IOException) {
            PutOutcome.Transport(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun etagOf(conn: HttpURLConnection): String? =
        conn.getHeaderField("ETag")?.trim()?.removeSurrounding("\"")?.ifEmpty { null }

    private fun bodySnippet(conn: HttpURLConnection): String =
        runCatching {
            val stream = conn.errorStream ?: return@runCatching ""
            stream.use { input ->
                val all = input.readBytes()
                val bytes = if (all.size > SNIPPET_MAX_BYTES) all.copyOf(SNIPPET_MAX_BYTES) else all
                String(bytes, StandardCharsets.UTF_8)
            }
        }.getOrDefault("")

    private sealed class PutOutcome {
        data class Success(val statusCode: Int, val etag: String?) : PutOutcome()
        data class ClientError(val statusCode: Int, val snippet: String) : PutOutcome()
        data class RetryableServer(val statusCode: Int, val snippet: String) : PutOutcome()
        data class Transport(val exception: IOException) : PutOutcome()
    }

    companion object {
        const val SERVICE = "s3"
        const val DEFAULT_REGION = "auto"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 60_000
        private const val SNIPPET_MAX_BYTES = 512
    }
}
