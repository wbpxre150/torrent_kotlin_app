package com.torrent.app.s3

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-JVM test (plain JUnit4, no Robolectric): exercises [S3Client] against a minimal
 * HTTP/1.1 stub server bound to 127.0.0.1 on an ephemeral port. The server records every
 * request (method/path/headers/body) and serves a scripted sequence of responses.
 *
 * Note: the JDK-bundled `com.sun.net.httpserver.HttpServer` is NOT usable here — AGP compiles
 * unit tests against the mockable android.jar (no full JDK platform on the test compile
 * classpath), so `com.sun.*` does not resolve. The stub below uses only `java.net`/`java.io`,
 * which are available at both compile time and host-JVM runtime.
 */
class S3ClientTest {

    private data class PlannedResponse(
        val code: Int,
        val body: String = "",
        val etag: String? = null,
        val closeWithoutAnswering: Boolean = false,
    )

    private data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    )

    /**
     * Casts this result to [T], failing the test when it is a different variant.
     * (JUnit's `Assert.fail` is Java `void`, so `x as? T ?: fail(...)` does not
     * narrow the type in Kotlin; `throw` is a true `Nothing`.)
     */
    private inline fun <reified T : S3PutResult> S3PutResult.expect(): T =
        this as? T ?: throw AssertionError("expected ${T::class.simpleName}, got $this")

    private inline fun <reified T : S3GetResult> S3GetResult.expect(): T =
        this as? T ?: throw AssertionError("expected ${T::class.simpleName}, got $this")

    private inline fun <reified T : S3DeleteResult> S3DeleteResult.expect(): T =
        this as? T ?: throw AssertionError("expected ${T::class.simpleName}, got $this")

    private inline fun <reified T : S3ListResult> S3ListResult.expect(): T =
        this as? T ?: throw AssertionError("expected ${T::class.simpleName}, got $this")

    private var socket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val planned = ArrayDeque<PlannedResponse>()
    private val requests = mutableListOf<Recorded>()

    @Before
    fun setUp() {
        requests.clear()
        planned.clear()
        val server = ServerSocket()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        socket = server
        serverThread = Thread {
            while (true) {
                try {
                    server.accept().use { client -> handle(client) }
                } catch (e: SocketException) {
                    break // server socket closed in tearDown
                } catch (e: IOException) {
                    // Per-connection failure; keep serving the next connection.
                }
            }
        }.apply {
            isDaemon = true
            name = "s3-client-test-stub"
            start()
        }
    }

    @After
    fun tearDown() {
        socket?.close()
        serverThread = null
    }

    private fun port(): Int = socket?.localPort ?: error("server socket not started")

    private fun handle(client: Socket) {
        // Byte-level parsing (kotlin.io's `buffered()` returns a BufferedInputStream in
        // Kotlin 2.2): read the head up to the blank line, then exactly Content-Length
        // body bytes from the same stream.
        val input = client.getInputStream().buffered()
        val head = mutableListOf<Byte>()
        while (!endsWithCrlfCrlf(head)) {
            val b = input.read()
            if (b == -1) return
            head.add(b.toByte())
        }
        val lines = String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n")
        val parts = lines.first().split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        val headers = LinkedHashMap<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                headers.getOrPut(name) { mutableListOf() }.add(line.substring(idx + 1).trim())
            }
        }
        val contentLength = headers["content-length"]?.firstOrNull()?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            if (read == -1) break
            offset += read
        }
        synchronized(requests) {
            requests.add(Recorded(method, path, headers.mapValues { it.value.toList() }, body))
        }
        val response = synchronized(planned) { planned.removeFirstOrNull() ?: PlannedResponse(200) }
        if (response.closeWithoutAnswering) {
            // Simulate a dead server: drop the connection without sending an HTTP response.
            return
        }
        val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream().bufferedWriter()
        out.write("HTTP/1.1 ${response.code} ${reasonPhrase(response.code)}\r\n")
        response.etag?.let { out.write("ETag: $it\r\n") }
        out.write("Content-Length: ${bodyBytes.size}\r\n")
        out.write("Connection: close\r\n")
        out.write("\r\n")
        out.flush()
        out.write(response.body)
        out.flush()
    }

    private fun endsWithCrlfCrlf(bytes: List<Byte>): Boolean =
        bytes.size >= 4 &&
            bytes[bytes.size - 4] == 0x0D.toByte() &&
            bytes[bytes.size - 3] == 0x0A.toByte() &&
            bytes[bytes.size - 2] == 0x0D.toByte() &&
            bytes[bytes.size - 1] == 0x0A.toByte()

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        403 -> "Forbidden"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    private fun client(
        endpoint: String = "http://127.0.0.1:${port()}",
        sleeper: (Long) -> Unit = { },
        openCount: AtomicInteger? = null,
    ): S3Client {
        return S3Client(
            endpoint = endpoint,
            bucket = "my-bucket",
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000,
            sleeper = sleeper,
            opener = { url ->
                openCount?.incrementAndGet()
                url.openConnection() as HttpURLConnection
            },
        )
    }

    @Test(timeout = 10_000)
    fun putSendsPathStyleSignedRequest() {
        planned.add(PlannedResponse(200, etag = "\"abc123\""))
        val body = "hello r2".toByteArray()
        val result = client().put("dir/my file.pdf", "application/pdf", body)

        val success = result.expect<S3PutResult.Success>()
        assertEquals(200, success.statusCode)
        assertEquals("abc123", success.etag)

        val recorded = synchronized(requests) { requests.toList() }.single()
        assertEquals("PUT", recorded.method)
        assertEquals("/my-bucket/dir/my%20file.pdf", recorded.path)
        assertEquals("application/pdf", recorded.headers.getValue("content-type").single())
        assertEquals(body.toList(), recorded.body.toList())
        // SHA-256("hello r2"), computed independently of the production code.
        assertEquals(
            "f18d11218e7814319b7a555383d8eaa42b97ae6ec36594f37f1e256b0a386b12",
            recorded.headers.getValue("x-amz-content-sha256").single(),
        )
        val date = recorded.headers.getValue("x-amz-date").single()
        assertTrue("bad x-amz-date: $date", date.matches(Regex("\\d{8}T\\d{6}Z")))
        val auth = recorded.headers.getValue("authorization").single()
        assertTrue(auth, auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"))
        assertTrue(auth, auth.contains("/auto/s3/aws4_request"))
        assertTrue(auth, auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        assertTrue(auth, auth.matches(Regex(".*Signature=[0-9a-f]{64}$")))
    }

    @Test(timeout = 10_000)
    fun putSignsPathExactlyAsSentWithEndpointPrefix() {
        planned.add(PlannedResponse(200, etag = "\"abc123\""))
        val body = "hello r2".toByteArray()
        val result = client(endpoint = "http://127.0.0.1:${port()}/base")
            .put("dir/my file.pdf", "application/pdf", body)

        result.expect<S3PutResult.Success>()

        val recorded = synchronized(requests) { requests.toList() }.single()
        assertEquals("PUT", recorded.method)
        // The bucket/key are appended after the endpoint's own path prefix.
        assertEquals("/base/my-bucket/dir/my%20file.pdf", recorded.path)
        val date = recorded.headers.getValue("x-amz-date").single()
        // Re-sign over exactly the path the stub observed on the wire, with the same
        // host header and the request's own timestamp: the Authorization must match.
        val expected = SigV4.sign(
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            method = "PUT",
            host = "127.0.0.1:${port()}",
            path = recorded.path,
            payload = body,
            timestamp = date,
            region = "auto",
            service = "s3",
        ).headers.getValue("Authorization")
        assertEquals(expected, recorded.headers.getValue("authorization").single())
    }

    @Test(timeout = 10_000)
    fun putRetriesOn500ThenSucceeds() {
        planned.add(PlannedResponse(500, "Internal Server Error"))
        planned.add(PlannedResponse(200))
        val sleeps = mutableListOf<Long>()
        val open = AtomicInteger()
        val result = client(sleeper = { sleeps.add(it) }, openCount = open)
            .put("k", "text/plain", "x".toByteArray())

        assertTrue("expected Success, got $result", result is S3PutResult.Success)
        assertEquals(2, open.get())
        assertEquals(listOf(1000L), sleeps)
    }

    @Test(timeout = 10_000)
    fun putReturnsClientErrorImmediatelyOn403() {
        planned.add(PlannedResponse(403, "<Error><Code>AccessDenied</Code></Error>"))
        val open = AtomicInteger()
        val result = client(openCount = open).put("k", "text/plain", "x".toByteArray())

        val error = result.expect<S3PutResult.ClientError>()
        assertEquals(403, error.statusCode)
        assertTrue(error.bodySnippet, error.bodySnippet.contains("AccessDenied"))
        assertEquals("only one request should hit the server for a 4xx", 1, open.get())
    }

    @Test(timeout = 10_000)
    fun putRetriesOnConnectionDropThenSucceeds() {
        planned.add(PlannedResponse(500, closeWithoutAnswering = true))
        planned.add(PlannedResponse(200))
        val open = AtomicInteger()
        val result = client(openCount = open).put("k", "text/plain", "x".toByteArray())

        assertTrue("expected Success, got $result", result is S3PutResult.Success)
        assertEquals(2, open.get())
    }

    @Test(timeout = 10_000)
    fun putReturnsFailureAfterExhaustingRetriesOnConnectionDrop() {
        repeat(3) { planned.add(PlannedResponse(500, closeWithoutAnswering = true)) }
        val open = AtomicInteger()
        val result = client(openCount = open).put("k", "text/plain", "x".toByteArray())

        val failure = result.expect<S3PutResult.Failure>()
        assertTrue("expected IOException cause, got ${failure.cause}", failure.cause is IOException)
        assertEquals(3, open.get())
    }

    @Test(timeout = 10_000)
    fun putReturnsServerErrorAfterExhaustingRetriesOn500() {
        repeat(3) { planned.add(PlannedResponse(500, "boom")) }
        val open = AtomicInteger()
        val result = client(openCount = open).put("k", "text/plain", "x".toByteArray())

        val error = result.expect<S3PutResult.ServerError>()
        assertEquals(500, error.statusCode)
        assertEquals(3, error.attempts)
        assertTrue(error.bodySnippet, error.bodySnippet.contains("boom"))
        assertEquals(3, open.get())
    }

    // ---------- GET ----------

    @Test(timeout = 10_000)
    fun getSendsPathStyleSignedRequestAndReturnsBodyBytes() {
        planned.add(PlannedResponse(200, "dump-bytes", etag = "\"deadbeef\""))
        val result = client().get("dir/my file.pdf")

        val success = result.expect<S3GetResult.Success>()
        assertEquals(200, success.statusCode)
        assertArrayEquals("dump-bytes".toByteArray(), success.body)
        assertEquals("deadbeef", success.etag)

        val recorded = synchronized(requests) { requests.toList() }.single()
        assertEquals("GET", recorded.method)
        assertEquals("/my-bucket/dir/my%20file.pdf", recorded.path)
        assertTrue("GET sends no body", recorded.body.isEmpty())
        assertEquals(
            SigV4.EMPTY_PAYLOAD_SHA256,
            recorded.headers.getValue("x-amz-content-sha256").single(),
        )
    }

    @Test(timeout = 10_000)
    fun getSignsPathExactlyAsSentWithEndpointPrefix() {
        planned.add(PlannedResponse(200, "x"))
        client(endpoint = "http://127.0.0.1:${port()}/base").get("dir/my file.pdf")
            .expect<S3GetResult.Success>()

        val recorded = synchronized(requests) { requests.toList() }.single()
        assertEquals("GET", recorded.method)
        assertEquals("/base/my-bucket/dir/my%20file.pdf", recorded.path)
        val date = recorded.headers.getValue("x-amz-date").single()
        // Re-sign over exactly the path the stub observed on the wire: the Authorization
        // must match.
        val expected = SigV4.sign(
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            method = "GET",
            host = "127.0.0.1:${port()}",
            path = recorded.path,
            payload = ByteArray(0),
            timestamp = date,
            region = "auto",
            service = "s3",
        ).headers.getValue("Authorization")
        assertEquals(expected, recorded.headers.getValue("authorization").single())
    }

    @Test(timeout = 10_000)
    fun getReturnsClientErrorImmediatelyOn404() {
        planned.add(PlannedResponse(404, "<Error><Code>NoSuchKey</Code></Error>"))
        val open = AtomicInteger()
        val result = client(openCount = open).get("missing/key")

        val error = result.expect<S3GetResult.ClientError>()
        assertEquals(404, error.statusCode)
        assertTrue(error.bodySnippet, error.bodySnippet.contains("NoSuchKey"))
        assertEquals("only one request should hit the server for a 4xx", 1, open.get())
    }

    @Test(timeout = 10_000)
    fun getRetriesOn500ThenSucceeds() {
        planned.add(PlannedResponse(500, "Internal Server Error"))
        planned.add(PlannedResponse(200, "dump-bytes"))
        val sleeps = mutableListOf<Long>()
        val open = AtomicInteger()
        val result = client(sleeper = { sleeps.add(it) }, openCount = open).get("k")

        assertTrue("expected Success, got $result", result is S3GetResult.Success)
        assertEquals(2, open.get())
        assertEquals(listOf(1000L), sleeps)
    }

    @Test(timeout = 10_000)
    fun getRetriesOnTransportFailureThenSucceeds() {
        // A pre-response connection drop is transparently retried by the JDK for bodyless
        // GETs (the injected opener is not re-invoked), so simulate the transport failure
        // in the opener itself to exercise the client's own retry path.
        planned.add(PlannedResponse(200, "dump-bytes"))
        val open = AtomicInteger()
        val result = S3Client(
            endpoint = "http://127.0.0.1:${port()}",
            bucket = "my-bucket",
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000,
            opener = { url ->
                if (open.incrementAndGet() == 1) throw IOException("simulated transport failure")
                url.openConnection() as HttpURLConnection
            },
        ).get("k")

        assertTrue("expected Success, got $result", result is S3GetResult.Success)
        assertEquals(2, open.get())
    }

    @Test(timeout = 10_000)
    fun getReturnsServerErrorAfterExhaustingRetries() {
        repeat(3) { planned.add(PlannedResponse(500, "boom")) }
        val open = AtomicInteger()
        val result = client(openCount = open).get("k")

        val error = result.expect<S3GetResult.ServerError>()
        assertEquals(500, error.statusCode)
        assertEquals(3, error.attempts)
        assertTrue(error.bodySnippet, error.bodySnippet.contains("boom"))
        assertEquals(3, open.get())
    }

    // ---------- DELETE ----------

    @Test(timeout = 10_000)
    fun deleteSendsSignedDeleteAndTreats204AsSuccess() {
        planned.add(PlannedResponse(204))
        val result = client().delete("old/dump.sql.gz")

        val success = result.expect<S3DeleteResult.Success>()
        assertEquals(204, success.statusCode)

        val recorded = synchronized(requests) { requests.toList() }.single()
        assertEquals("DELETE", recorded.method)
        assertEquals("/my-bucket/old/dump.sql.gz", recorded.path)
        assertTrue("DELETE sends no body", recorded.body.isEmpty())
        assertEquals(
            SigV4.EMPTY_PAYLOAD_SHA256,
            recorded.headers.getValue("x-amz-content-sha256").single(),
        )
    }

    @Test(timeout = 10_000)
    fun deleteReturnsClientErrorImmediatelyOn404() {
        planned.add(PlannedResponse(404, "<Error><Code>NoSuchKey</Code></Error>"))
        val open = AtomicInteger()
        val result = client(openCount = open).delete("missing")

        val error = result.expect<S3DeleteResult.ClientError>()
        assertEquals(404, error.statusCode)
        assertEquals("only one request should hit the server for a 4xx", 1, open.get())
    }

    // ---------- LIST (ListObjectsV2) ----------

    /** Builds a minimal ListObjectsV2 XML page for the stub. */
    private fun listXml(
        truncated: Boolean,
        token: String? = null,
        vararg entries: Pair<String, Long?>,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
        append("<KeyCount>").append(entries.size).append("</KeyCount>")
        append("<IsTruncated>").append(truncated).append("</IsTruncated>")
        if (token != null) append("<NextContinuationToken>").append(token).append("</NextContinuationToken>")
        for ((key, size) in entries) {
            append("<Contents><Key>").append(key).append("</Key>")
            if (size != null) append("<Size>").append(size).append("</Size>")
            append("<StorageClass>STANDARD</StorageClass></Contents>")
        }
        append("</ListBucketResult>")
    }

    @Test(timeout = 10_000)
    fun listSendsCanonicalQueryAndParsesKeysAndSizes() {
        planned.add(
            PlannedResponse(
                200,
                listXml(
                    truncated = false,
                    token = null,
                    "dump/2025-01-01/cache.sql.gz" to 123L,
                    "dump/2025-01-02/cache.sql.gz" to null,
                ),
            ),
        )
        val result = client().list("dump/", maxKeys = 5)

        val success = result.expect<S3ListResult.Success>()
        assertEquals(200, success.statusCode)
        assertEquals(
            listOf("dump/2025-01-01/cache.sql.gz", "dump/2025-01-02/cache.sql.gz"),
            success.objects.map { it.key },
        )
        // Size is null when <Size> is absent.
        assertEquals(listOf(123L, null), success.objects.map { it.size })

        val recorded = synchronized(requests) { requests.toList() }.single()
        assertEquals("GET", recorded.method)
        // The wire query is exactly the canonical form of the signed query map.
        val expectedQuery = SigV4.canonicalQueryString(
            mapOf("list-type" to "2", "prefix" to "dump/", "max-keys" to "5"),
        )
        assertEquals("/my-bucket?$expectedQuery", recorded.path)
    }

    @Test(timeout = 10_000)
    fun listAuthorizationMatchesReSignOfWireQuery() {
        planned.add(PlannedResponse(200, listXml(truncated = false, token = null, "dump/one.sql.gz" to 1L)))
        client().list("dump dir", maxKeys = 7).expect<S3ListResult.Success>()

        val recorded = synchronized(requests) { requests.toList() }.single()
        // Split the recorded request target back into path + wire query.
        val qIndex = recorded.path.indexOf('?')
        assertTrue("expected a query on the wire, got ${recorded.path}", qIndex > 0)
        val path = recorded.path.substring(0, qIndex)
        val wireQuery = recorded.path.substring(qIndex + 1)
        // Decode the wire pairs to raw values; the canonical form of that map must be
        // byte-identical to what was sent (wire matches signed).
        val queryMap = wireQuery.split("&").associate { pair ->
            val (k, v) = pair.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
        assertEquals(wireQuery, SigV4.canonicalQueryString(queryMap))
        val date = recorded.headers.getValue("x-amz-date").single()
        val expected = SigV4.sign(
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            method = "GET",
            host = "127.0.0.1:${port()}",
            path = path,
            payload = ByteArray(0),
            timestamp = date,
            region = "auto",
            service = "s3",
            query = queryMap,
        ).headers.getValue("Authorization")
        assertEquals(expected, recorded.headers.getValue("authorization").single())
    }

    @Test(timeout = 10_000)
    fun listFollowsContinuationTokenAcrossPages() {
        planned.add(PlannedResponse(200, listXml(truncated = true, token = "tok-1", "dump/a.sql.gz" to 1L)))
        planned.add(PlannedResponse(200, listXml(truncated = false, token = null, "dump/b.sql.gz" to 2L)))

        val result = client().list("dump/")

        val success = result.expect<S3ListResult.Success>()
        assertEquals(listOf("dump/a.sql.gz", "dump/b.sql.gz"), success.objects.map { it.key })

        val recorded = synchronized(requests) { requests.toList() }
        assertEquals(2, recorded.size)
        assertEquals(
            "/my-bucket?${SigV4.canonicalQueryString(mapOf("list-type" to "2", "prefix" to "dump/"))}",
            recorded[0].path,
        )
        assertEquals(
            "/my-bucket?${SigV4.canonicalQueryString(mapOf("list-type" to "2", "prefix" to "dump/", "continuation-token" to "tok-1"))}",
            recorded[1].path,
        )
    }

    @Test(timeout = 10_000)
    fun listReturnsCappedWhenPageLimitReachedWhileTruncated() {
        repeat(S3Client.MAX_LIST_PAGES) {
            planned.add(PlannedResponse(200, listXml(truncated = true, token = "tok-$it", "dump/k$it" to 1L)))
        }
        val open = AtomicInteger()
        val result = client(openCount = open).list("dump/")

        val capped = result.expect<S3ListResult.Capped>()
        assertEquals(S3Client.MAX_LIST_PAGES, capped.objects.size)
        assertEquals("tok-99", capped.nextContinuationToken)
        assertEquals(S3Client.MAX_LIST_PAGES, open.get())
    }

    @Test(timeout = 10_000)
    fun listReturnsClientErrorImmediatelyOn403() {
        planned.add(PlannedResponse(403, "<Error><Code>AccessDenied</Code></Error>"))
        val open = AtomicInteger()
        val result = client(openCount = open).list("dump/")

        val error = result.expect<S3ListResult.ClientError>()
        assertEquals(403, error.statusCode)
        assertEquals("only one request should hit the server for a 4xx", 1, open.get())
    }
}
