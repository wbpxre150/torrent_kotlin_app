package com.torrent.app.s3

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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

/** Result of an S3 object GET. */
sealed class S3GetResult {
    /** 2xx response. [body] is the full response body; [etag] is the server's `ETag` header with surrounding quotes stripped (null when absent). */
    data class Success(val statusCode: Int, val body: ByteArray, val etag: String?) : S3GetResult()

    /** 4xx response — treated as permanent, returned without retry. */
    data class ClientError(val statusCode: Int, val bodySnippet: String) : S3GetResult()

    /** 5xx (or other non-2xx/4xx) response on the final attempt after retries were exhausted. */
    data class ServerError(val statusCode: Int, val bodySnippet: String, val attempts: Int) : S3GetResult()

    /** Transport-level ([java.io.IOException]) failure on the final attempt after retries were exhausted. */
    data class Failure(val cause: Exception) : S3GetResult()
}

/** Result of an S3 object DELETE. R2 answers 204 on success; any 2xx is treated as success. */
sealed class S3DeleteResult {
    /** 2xx response (204 on R2). */
    data class Success(val statusCode: Int) : S3DeleteResult()

    /** 4xx response — treated as permanent, returned without retry. */
    data class ClientError(val statusCode: Int, val bodySnippet: String) : S3DeleteResult()

    /** 5xx (or other non-2xx/4xx) response on the final attempt after retries were exhausted. */
    data class ServerError(val statusCode: Int, val bodySnippet: String, val attempts: Int) : S3DeleteResult()

    /** Transport-level ([java.io.IOException]) failure on the final attempt after retries were exhausted. */
    data class Failure(val cause: Exception) : S3DeleteResult()
}

/** One object entry from a ListObjectsV2 response, in document (server) order. */
data class S3ListedObject(val key: String, val size: Long?)

/** Result of a ListObjectsV2 (LIST) request. */
sealed class S3ListResult {
    /** 2xx response; [objects] is the complete listing (all continuation pages were fetched). */
    data class Success(val statusCode: Int, val objects: List<S3ListedObject>) : S3ListResult()

    /**
     * The listing was still truncated after [com.torrent.app.s3.S3Client.MAX_LIST_PAGES] pages
     * were fetched (a hard cap, not a silent truncation). [objects] holds everything fetched
     * so far; [nextContinuationToken] (when the server supplied one) can be passed back to
     * [com.torrent.app.s3.S3Client.list] to resume.
     */
    data class Capped(
        val statusCode: Int,
        val objects: List<S3ListedObject>,
        val nextContinuationToken: String?,
    ) : S3ListResult()

    /** 4xx response — treated as permanent, returned without retry. */
    data class ClientError(val statusCode: Int, val bodySnippet: String) : S3ListResult()

    /** 5xx (or other non-2xx/4xx) response on the final attempt after retries were exhausted. */
    data class ServerError(val statusCode: Int, val bodySnippet: String, val attempts: Int) : S3ListResult()

    /** Transport-level ([java.io.IOException]) failure on the final attempt after retries were exhausted. */
    data class Failure(val cause: Exception) : S3ListResult()
}

/**
 * Minimal S3-compatible object client (Cloudflare R2: path-style addressing, region "auto",
 * service "s3") over plain [HttpURLConnection] — the house HTTP pattern (see
 * [com.torrent.app.transmission.TransmissionRpcClient] for the conventions). Supports PUT,
 * GET, DELETE and ListObjectsV2 (LIST).
 *
 * [opener], [sleeper] and [timeSource] are injectable for host-JVM unit tests.
 *
 * Retry policy (all verbs): [maxAttempts] attempts total, exponential backoff from
 * [retryDelaysMs] between attempts, on 5xx/other non-2xx/4xx responses and on [IOException].
 * 4xx responses are returned immediately as `ClientError` (no retry).
 *
 * The request sent on the wire always matches the signed canonical request: for LIST the
 * query string appended to the URL is built with [SigV4.canonicalQueryString] from the very
 * same map passed to [SigV4.sign], so the signed query and the sent query cannot drift.
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
        // Sign the path exactly as sent on the wire: `url.path` is the raw (percent-encoded)
        // path of the request URL, which also covers endpoints that already carry a path
        // prefix (e.g. `https://acct.r2.cloudflarestorage.com/unjumble`).
        val path = url.path.ifEmpty { "/" }
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
        val outcome = execute(url, { conn ->
            conn.requestMethod = "PUT"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", contentType)
            conn.applySignedHeaders(signed.headers)
        }) { conn ->
            conn.outputStream.use { it.write(body) }
            classify(conn, readBody = false)
        }
        return when (val o = outcome) {
            is AttemptOutcome.Success -> S3PutResult.Success(o.statusCode, o.etag)
            is AttemptOutcome.ClientError -> S3PutResult.ClientError(o.statusCode, o.snippet)
            is AttemptOutcome.RetryableServer -> S3PutResult.ServerError(o.statusCode, o.snippet, maxAttempts)
            is AttemptOutcome.Transport -> S3PutResult.Failure(o.exception)
        }
    }

    /**
     * SigV4-signed GET of `endpoint/bucket/objectKey` (path-style, empty query, empty
     * payload). Returns the response body bytes on 2xx.
     */
    fun get(objectKey: String): S3GetResult {
        val url = objectUrl(objectKey)
        val path = url.path.ifEmpty { "/" }
        val date = amzDate(timeSource())
        val signed = SigV4.sign(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            method = "GET",
            host = hostHeader(url),
            path = path,
            payload = ByteArray(0),
            timestamp = date,
            region = region,
            service = SERVICE,
        )
        val outcome = execute(url, { conn ->
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.applySignedHeaders(signed.headers)
        }) { conn -> classify(conn, readBody = true) }
        return when (val o = outcome) {
            is AttemptOutcome.Success -> S3GetResult.Success(o.statusCode, o.payload, o.etag)
            is AttemptOutcome.ClientError -> S3GetResult.ClientError(o.statusCode, o.snippet)
            is AttemptOutcome.RetryableServer -> S3GetResult.ServerError(o.statusCode, o.snippet, maxAttempts)
            is AttemptOutcome.Transport -> S3GetResult.Failure(o.exception)
        }
    }

    /**
     * SigV4-signed DELETE of `endpoint/bucket/objectKey` (path-style, empty query). R2
     * answers 204 on success; any 2xx is treated as success.
     */
    fun delete(objectKey: String): S3DeleteResult {
        val url = objectUrl(objectKey)
        val path = url.path.ifEmpty { "/" }
        val date = amzDate(timeSource())
        val signed = SigV4.sign(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            method = "DELETE",
            host = hostHeader(url),
            path = path,
            payload = ByteArray(0),
            timestamp = date,
            region = region,
            service = SERVICE,
        )
        val outcome = execute(url, { conn ->
            conn.requestMethod = "DELETE"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.applySignedHeaders(signed.headers)
        }) { conn -> classify(conn, readBody = false) }
        return when (val o = outcome) {
            is AttemptOutcome.Success -> S3DeleteResult.Success(o.statusCode)
            is AttemptOutcome.ClientError -> S3DeleteResult.ClientError(o.statusCode, o.snippet)
            is AttemptOutcome.RetryableServer -> S3DeleteResult.ServerError(o.statusCode, o.snippet, maxAttempts)
            is AttemptOutcome.Transport -> S3DeleteResult.Failure(o.exception)
        }
    }

    /**
     * SigV4-signed ListObjectsV2 of `endpoint/bucket/` for [prefix].
     *
     * Continuation: follows `NextContinuationToken` across pages automatically — each page is
     * its own signed request whose query string is built with [SigV4.canonicalQueryString]
     * and appended to the URL exactly as signed (the signed and sent queries cannot drift).
     * The loop stops when the response is not truncated, or after [MAX_LIST_PAGES] pages —
     * a hard cap of 100 pages × 1000 keys = 100,000 objects, deliberately far beyond a
     * DB-backup bucket. When the cap is hit while the response is still truncated the result
     * is [S3ListResult.Capped] (objects so far + the continuation token to resume), never a
     * silently truncated [S3ListResult.Success].
     *
     * [maxKeys] is sent as `max-keys` on the first page only (S3/R2 apply it across the whole
     * listing) and is ignored when [continuationToken] is given.
     *
     * [S3ListedObject] entries are in server (document) order — lexicographic by key for R2;
     * [S3ListedObject.size] is null when the response omits `<Size>`.
     */
    fun list(prefix: String, maxKeys: Int? = null, continuationToken: String? = null): S3ListResult {
        require(maxKeys == null || maxKeys > 0) { "maxKeys must be positive when set: $maxKeys" }
        val objects = mutableListOf<S3ListedObject>()
        var token = continuationToken
        var page = 0
        while (true) {
            page += 1
            val query = buildMap {
                put("list-type", "2")
                put("prefix", prefix)
                if (token == null) maxKeys?.let { put("max-keys", it.toString()) }
                token?.let { put("continuation-token", it) }
            }
            // Wire-matches-signed: append exactly the canonical form of the same map that is
            // passed to SigV4.sign below.
            val canonicalQuery = SigV4.canonicalQueryString(query)
            val url = URL(bucketUrl().toString() + "?$canonicalQuery")
            val path = url.path.ifEmpty { "/" }
            val date = amzDate(timeSource())
            val signed = SigV4.sign(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                method = "GET",
                host = hostHeader(url),
                path = path,
                payload = ByteArray(0),
                timestamp = date,
                region = region,
                service = SERVICE,
                query = query,
            )
            val outcome = execute(url, { conn ->
                conn.requestMethod = "GET"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.applySignedHeaders(signed.headers)
            }) { conn -> classify(conn, readBody = true) }
            when (val o = outcome) {
                is AttemptOutcome.Success -> {
                    val parsed = parseListObjectsXml(o.payload)
                    objects.addAll(parsed.objects)
                    if (parsed.truncated) {
                        if (page >= MAX_LIST_PAGES) {
                            return S3ListResult.Capped(o.statusCode, objects, parsed.nextContinuationToken)
                        }
                        val next = parsed.nextContinuationToken
                        if (next == null) {
                            // Truncated with no token to continue with: report what we have.
                            return S3ListResult.Capped(o.statusCode, objects, null)
                        }
                        token = next
                        continue
                    }
                    return S3ListResult.Success(o.statusCode, objects)
                }

                is AttemptOutcome.ClientError -> return S3ListResult.ClientError(o.statusCode, o.snippet)
                is AttemptOutcome.RetryableServer ->
                    return S3ListResult.ServerError(o.statusCode, o.snippet, maxAttempts)
                is AttemptOutcome.Transport -> return S3ListResult.Failure(o.exception)
            }
        }
    }

    /** Path-style object URL: `endpoint/bucket/key` with every segment percent-encoded. */
    fun objectUrl(objectKey: String): URL =
        URL("${endpoint.trimEnd('/')}/${SigV4.encodeS3PathSegment(bucket)}/${encodedKeyPath(objectKey)}")

    /** Path-style bucket URL (no object key): `endpoint/bucket`. */
    private fun bucketUrl(): URL =
        URL("${endpoint.trimEnd('/')}/${SigV4.encodeS3PathSegment(bucket)}")

    private fun encodedKeyPath(objectKey: String): String =
        objectKey.split('/').joinToString("/") { SigV4.encodeS3PathSegment(it) }

    private fun hostHeader(url: URL): String =
        if (url.port == -1) url.host else "${url.host}:${url.port}"

    private fun amzDate(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'")
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }

    /**
     * Runs [maxAttempts] attempts (with backoff from [retryDelaysMs] between them) against
     * the single signed [url]. [connect] configures the opened connection (method, body,
     * extra headers); [read] maps the response to an [AttemptOutcome]. 2xx and 4xx outcomes
     * terminate immediately; 5xx/other and transport failures are retried.
     */
    private fun execute(
        url: URL,
        connect: (HttpURLConnection) -> Unit,
        read: (HttpURLConnection) -> AttemptOutcome,
    ): AttemptOutcome {
        var lastRetryable: AttemptOutcome? = null
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) sleeper(retryDelaysMs.getOrElse(attempt - 2) { retryDelaysMs.lastOrNull() ?: 0L })
            val conn = try {
                opener(url).apply(connect)
            } catch (e: IOException) {
                lastRetryable = AttemptOutcome.Transport(e)
                continue
            }
            val outcome = try {
                read(conn)
            } catch (e: IOException) {
                AttemptOutcome.Transport(e)
            } finally {
                conn.disconnect()
            }
            lastRetryable = outcome
            if (outcome !is AttemptOutcome.RetryableServer && outcome !is AttemptOutcome.Transport) {
                return outcome
            }
        }
        return lastRetryable ?: AttemptOutcome.Transport(IOException("no attempts were made for $url"))
    }

    /**
     * Classifies an HTTP response: 2xx → [AttemptOutcome.Success] (reading the response body
     * when [readBody]), 4xx → [AttemptOutcome.ClientError], anything else (3xx/5xx) →
     * [AttemptOutcome.RetryableServer] — a signed request should answer 2xx or 4xx; anything
     * else is treated as a transient server-side condition.
     */
    private fun classify(conn: HttpURLConnection, readBody: Boolean): AttemptOutcome {
        val code = conn.responseCode
        return when {
            code in 200..299 -> {
                val body = if (readBody) conn.inputStream.use { it.readBytes() } else ByteArray(0)
                AttemptOutcome.Success(code, body, etagOf(conn))
            }

            code in 400..499 -> AttemptOutcome.ClientError(code, bodySnippet(conn))
            else -> AttemptOutcome.RetryableServer(code, bodySnippet(conn))
        }
    }

    private fun HttpURLConnection.applySignedHeaders(headers: Map<String, String>) {
        for ((name, value) in headers) setRequestProperty(name, value)
    }

    /** One parsed ListObjectsV2 page. */
    private data class ListPage(
        val objects: List<S3ListedObject>,
        val truncated: Boolean,
        val nextContinuationToken: String?,
    )

    /**
     * Parses a (small) ListObjectsV2 XML response: `<Contents><Key>`/`<Size>` entries in
     * document order plus `<IsTruncated>`/`<NextContinuationToken>`. Uses the stock
     * [DocumentBuilderFactory] (available on Android API 24+ and the host JVM) — no external
     * dependencies.
     */
    private fun parseListObjectsXml(xml: ByteArray): ListPage {
        val factory = DocumentBuilderFactory.newInstance()
        // Best-effort hardening (no DOCTYPE/external entities); ignored on XML factories that
        // do not support the feature (the response is small and comes from an authenticated
        // HTTPS endpoint).
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-dec", true) }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val root = doc.documentElement
        val contents = doc.getElementsByTagName("Contents")
        val objects = (0 until contents.length).map { i ->
            val node = contents.item(i) as Element
            S3ListedObject(childText(node, "Key").orEmpty(), childText(node, "Size")?.toLongOrNull())
        }
        return ListPage(
            objects = objects,
            truncated = childText(root, "IsTruncated")?.trim()?.toBoolean() ?: false,
            nextContinuationToken = childText(root, "NextContinuationToken")?.ifEmpty { null },
        )
    }

    /** Text of the first direct child element of [parent] named [name], or null. */
    private fun childText(parent: Element, name: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.tagName == name) return child.textContent
        }
        return null
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

    /** Internal outcome of one request attempt, shared by all verbs. */
    private sealed class AttemptOutcome {
        /** 2xx; [payload] is the response body (empty for verbs that carry none). */
        data class Success(val statusCode: Int, val payload: ByteArray, val etag: String?) : AttemptOutcome()

        data class ClientError(val statusCode: Int, val snippet: String) : AttemptOutcome()

        data class RetryableServer(val statusCode: Int, val snippet: String) : AttemptOutcome()

        data class Transport(val exception: IOException) : AttemptOutcome()
    }

    companion object {
        const val SERVICE = "s3"
        const val DEFAULT_REGION = "auto"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 60_000
        /** Hard page cap for [list]: 100 pages × 1000 keys = 100,000 objects. */
        const val MAX_LIST_PAGES = 100
        private const val SNIPPET_MAX_BYTES = 512
    }
}
