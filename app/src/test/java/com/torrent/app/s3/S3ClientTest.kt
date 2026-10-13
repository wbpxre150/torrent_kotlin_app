package com.torrent.app.s3

import org.junit.After
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
}
