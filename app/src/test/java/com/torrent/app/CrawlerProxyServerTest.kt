package com.torrent.app

import android.content.Context
import android.content.SharedPreferences
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class CrawlerProxyServerTest {

    /** Ktor 3's headersOf takes List values; this keeps the test call-sites tidy. */
    private fun headers(vararg pairs: Pair<String, String>): Headers =
        HeadersBuilder().apply { pairs.forEach { (k, v) -> append(k, v) } }.build()

    @Test fun isHopByHop_recognizesStandardHeadersCaseInsensitive() {
        assertTrue(CrawlerProxyServer.isHopByHop("Connection"))
        assertTrue(CrawlerProxyServer.isHopByHop("keep-alive"))
        assertTrue(CrawlerProxyServer.isHopByHop("Transfer-Encoding"))
        assertTrue(CrawlerProxyServer.isHopByHop("Proxy-Authorization"))
        assertTrue(CrawlerProxyServer.isHopByHop("Upgrade"))
        assertTrue(CrawlerProxyServer.isHopByHop("Trailer"))
        assertFalse(CrawlerProxyServer.isHopByHop("Content-Type"))
        assertFalse(CrawlerProxyServer.isHopByHop("Content-Length"))
        assertFalse(CrawlerProxyServer.isHopByHop("X-Custom"))
    }

    @Test fun filteredRequestHeaders_dropsHostHopByHopAndAuthorization() {
        val headers = headers(
            "Host" to "192.168.1.5:8080",
            "Connection" to "close",
            "Transfer-Encoding" to "chunked",
            "Authorization" to "Basic abc",
            "Authorization" to "Bearer x",
            "Accept" to "text/html",
            "User-Agent" to "JUnit",
        )
        val out = CrawlerProxyServer.filteredRequestHeaders(headers)
        assertNull(out["Host"])
        assertNull(out["Connection"])
        assertNull(out["Transfer-Encoding"])
        assertTrue(out.getAll("Authorization").isNullOrEmpty())
        assertEquals(listOf("text/html"), out.getAll("Accept"))
        assertEquals(listOf("JUnit"), out.getAll("User-Agent"))
    }

    @Test fun filteredRequestHeaders_keepsNonControlCookieParts() {
        val out = CrawlerProxyServer.filteredRequestHeaders(
            headers(
                "Cookie" to "control_token=secret; theme=dark",
                "Accept" to "*/*",
            ))
        val cookie = out["Cookie"]
        assertTrue(cookie!!.contains("theme=dark"))
        assertFalse(cookie.contains("secret"))
        assertFalse(cookie.contains("control_token"))
        assertEquals(listOf("*/*"), out.getAll("Accept"))
    }

    @Test fun filteredRequestHeaders_dropsCookieHeaderWhenOnlyControlToken() {
        val out = CrawlerProxyServer.filteredRequestHeaders(
            headers("Cookie" to "control_token=secret"))
        assertNull(out["Cookie"])
    }

    @Test fun filteredRequestHeaders_isCaseInsensitiveOnHeaderNames() {
        val out = CrawlerProxyServer.filteredRequestHeaders(
            headers(
                "host" to "lan:8080",
                "authorization" to "Bearer x",
                "cookie" to "CONTROL_TOKEN=secret; a=b",
                "Accept" to "text/html",
            ))
        assertNull(out.getAll("host"))
        assertNull(out.getAll("authorization"))
        assertEquals(listOf("a=b"), out.getAll("cookie"))
        assertEquals(listOf("text/html"), out.getAll("Accept"))
    }

    @Test fun stripTokenFromQuery_removesTokenKeepsOthersInOrder() {
        assertEquals(
            "q=abc&lang=en",
            CrawlerProxyServer.stripTokenFromQuery("q=abc&token=SECRET&lang=en"))
    }

    @Test fun stripTokenFromQuery_allTokenOrEmptyReturnsEmpty() {
        assertEquals("", CrawlerProxyServer.stripTokenFromQuery("token=SECRET"))
        assertEquals("", CrawlerProxyServer.stripTokenFromQuery(""))
    }

    @Test fun stripTokenFromQuery_noTokenParamIsUnchanged() {
        assertEquals("a=1&b=2", CrawlerProxyServer.stripTokenFromQuery("a=1&b=2"))
    }

    @Test fun stripTokenFromQuery_removesBareTokenParam() {
        assertEquals("a=1", CrawlerProxyServer.stripTokenFromQuery("a=1&token"))
    }

    @Test fun stripTokenFromQuery_isCaseInsensitiveOnKey() {
        assertEquals("a=1", CrawlerProxyServer.stripTokenFromQuery("a=1&TOKEN=x"))
    }

    @Test fun stripTokenFromQuery_preservesSearchFilterParamsInOrder() {
        assertEquals(
            "q=ubuntu&category=tv&tv_kind=season_pack&adult=only&min_conf=0.8",
            CrawlerProxyServer.stripTokenFromQuery(
                "token=SECRET&q=ubuntu&category=tv&tv_kind=season_pack" +
                    "&adult=only&min_conf=0.8"))
    }

    @Test fun unauthorizedPage_containsLoginLink() {
        val html = CrawlerProxyServer.unauthorizedPage("http://192.168.1.5:8090/login")
        assertTrue(html.contains("http://192.168.1.5:8090/login"))
        assertTrue(html.contains("Authentication required"))
    }

    @Test fun badGatewayPage_containsDashboardLinkAndDetail() {
        val html = CrawlerProxyServer.badGatewayPage("Connection refused", "http://192.168.1.5:8090/")
        assertTrue(html.contains("Connection refused"))
        assertTrue(html.contains("http://192.168.1.5:8090/"))
    }

    // ── live routing: real CIO proxy + JDK upstream on free ports ──────────
    // No ktor-server-test-host dependency; a raw-socket upstream is enough
    // to prove the proxy reaches non-root paths (a bare `handle` under
    // `routing` only matched "/" and 404'd everything else).

    private companion object {
        private const val TOKEN = "unit-test-token"
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Minimal raw-socket upstream: 200 + echo of `METHOD target`. When
     * [echoAuthorization] is set the body is prefixed with `auth:<value|none>`
     * read from the request headers, so a test can prove a header never arrives.
     */
    private fun startUpstream(echoAuthorization: Boolean = false): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                Thread {
                    try {
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        // Drain with available() polling: the proxy's JDK
                        // HttpURLConnection writes the request in many small
                        // writes, and Nagle + delayed-ACK pacing stalls a
                        // blocking byte-by-byte read loop.
                        val deadline = System.currentTimeMillis() + 5_000
                        while (!header.toString().endsWith("\r\n\r\n") &&
                            System.currentTimeMillis() < deadline
                        ) {
                            val available = input.available()
                            if (available > 0) {
                                header.append(String(input.readNBytes(available)))
                            } else {
                                Thread.sleep(20)
                            }
                        }
                        val fields = (header.lineSequence().firstOrNull() ?: "").split(' ')
                        val echo = StringBuilder()
                        if (echoAuthorization) {
                            val auth = header.lineSequence()
                                .firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
                                ?.substringAfter(':')?.trim() ?: "none"
                            echo.append("auth:").append(auth).append('\n')
                        }
                        echo.append(
                            "upstream:${fields.getOrNull(0) ?: "GET"} " +
                                "${fields.getOrNull(1) ?: "/"}")
                        val body = echo.toString().toByteArray()
                        val responseHeaders =
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        val out = socket.getOutputStream()
                        out.write(responseHeaders.toByteArray())
                        out.write(body)
                        out.flush()
                    } catch (_: Exception) {
                        // A per-connection failure must not kill the upstream.
                    } finally {
                        runCatching { socket.close() }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    private fun tokenManagerWith(token: String): TokenManager {
        val prefs = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(prefs.getString("auth_token", null)).thenReturn(token)
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(
            context.getSharedPreferences("control_auth", Context.MODE_PRIVATE)
        ).thenReturn(prefs)
        return TokenManager(context)
    }

    /** GET with a short retry so the CIO listener has a beat to come up. */
    private fun httpGet(
        url: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        var last: Exception? = null
        repeat(40) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                requestHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                return code to body
            } catch (e: ConnectException) {
                last = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("proxy never came up for $url", last)
    }

    /** POST with a short retry so the CIO listener has a beat to come up. */
    private fun httpPost(url: String): Pair<Int, String> {
        var last: Exception? = null
        repeat(40) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                return code to body
            } catch (e: ConnectException) {
                last = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("proxy never came up for $url", last)
    }

    /** Mockito stub of the add handler (Mockito 5's inline maker mocks finals). */
    private fun mockAddHandler(outcome: CrawlerAddOutcome): CrawlerAddHandler {
        val handler = Mockito.mock(CrawlerAddHandler::class.java)
        Mockito.`when`(handler.add(Mockito.anyString())).thenReturn(outcome)
        return handler
    }

    @Test fun proxyForwardsNonRootPath_withValidToken_returnsUpstreamBody() {
        val upstream = startUpstream()
        val proxy = CrawlerProxyServer(
            tokenManagerWith(TOKEN), { upstream.localPort }, freePort())
        proxy.start()
        try {
            val (code, body) = httpGet(
                "http://127.0.0.1:${proxy.port}/search?q=x&format=html&token=$TOKEN")
            assertEquals(200, code)
            // token is stripped before forwarding; path + query pass through.
            // (The JDK's HttpURLConnection forces POST when doOutput is set,
            // so the echoed method is not asserted.)
            assertTrue(body.endsWith(" /search?q=x&format=html"))
        } finally {
            proxy.stop()
            upstream.close()
        }
    }

    // ── stage 7: crawler search/browse filter params pass through verbatim ─

    @Test fun proxyForwardsSearchFilterParamsVerbatim_andStripsToken() {
        val upstream = startUpstream()
        val proxy = CrawlerProxyServer(
            tokenManagerWith(TOKEN), { upstream.localPort }, freePort())
        proxy.start()
        try {
            val (code, body) = httpGet(
                "http://127.0.0.1:${proxy.port}/search?token=$TOKEN&q=ubuntu" +
                    "&category=tv&tv_kind=season_pack&adult=only&min_conf=0.8")
            assertEquals(200, code)
            assertTrue(
                "expected verbatim filter query, got: $body",
                body.endsWith(
                    " /search?q=ubuntu&category=tv&tv_kind=season_pack" +
                        "&adult=only&min_conf=0.8"))
            assertFalse("token must be stripped, got: $body", body.contains("token="))
        } finally {
            proxy.stop()
            upstream.close()
        }
    }

    @Test fun proxyForwardsBrowseSortAndCategoryVerbatim_andStripsToken() {
        val upstream = startUpstream()
        val proxy = CrawlerProxyServer(
            tokenManagerWith(TOKEN), { upstream.localPort }, freePort())
        proxy.start()
        try {
            val (code, body) = httpGet(
                "http://127.0.0.1:${proxy.port}/browse" +
                    "?sort=peers&category=movie&token=$TOKEN")
            assertEquals(200, code)
            assertTrue(
                "expected verbatim browse query, got: $body",
                body.endsWith(" /browse?sort=peers&category=movie"))
            assertFalse("token must be stripped, got: $body", body.contains("token="))
        } finally {
            proxy.stop()
            upstream.close()
        }
    }

    @Test fun proxyNeverForwardsAuthorizationHeaderToUpstream() {
        val upstream = startUpstream(echoAuthorization = true)
        val proxy = CrawlerProxyServer(
            tokenManagerWith(TOKEN), { upstream.localPort }, freePort())
        proxy.start()
        try {
            val (code, body) = httpGet(
                "http://127.0.0.1:${proxy.port}/search?q=x&token=$TOKEN",
                mapOf("Authorization" to "Bearer x"))
            assertEquals(200, code)
            assertTrue("upstream must echo its auth view, got: $body", body.startsWith("auth:"))
            assertTrue(
                "Authorization must not reach the crawler, got: $body",
                body.startsWith("auth:none"))
            assertFalse("Bearer must not reach the crawler, got: $body", body.contains("Bearer"))
        } finally {
            proxy.stop()
            upstream.close()
        }
    }

    @Test fun proxyRootPath_withValidToken_returnsUpstreamBody() {
        val upstream = startUpstream()
        val proxy = CrawlerProxyServer(
            tokenManagerWith(TOKEN), { upstream.localPort }, freePort())
        proxy.start()
        try {
            val (code, body) = httpGet("http://127.0.0.1:${proxy.port}/?token=$TOKEN")
            assertEquals(200, code)
            assertTrue(body.endsWith(" /"))
        } finally {
            proxy.stop()
            upstream.close()
        }
    }

    @Test fun proxyNonRootPath_withoutToken_returns401Not404() {
        val proxy = CrawlerProxyServer(tokenManagerWith(TOKEN), { 0 }, freePort())
        proxy.start()
        try {
            val (code, body) = httpGet("http://127.0.0.1:${proxy.port}/random-tv")
            assertEquals(401, code)
            assertTrue(body.contains("Authentication required"))
        } finally {
            proxy.stop()
        }
    }

    // ── plan 11 D2: /api/transmission/add ──────────────────────────────────

    @Test fun addRoute_withoutToken_returns401Json() {
        val proxy = CrawlerProxyServer(
            tokenManagerWith(TOKEN), { 0 }, freePort(),
            addHandler = mockAddHandler(CrawlerAddOutcome.Added(false, "x", true)),
        )
        proxy.start()
        try {
            val (code, body) = httpPost(
                "http://127.0.0.1:${proxy.port}/api/transmission/add?hash=${"a".repeat(40)}")
            assertEquals(401, code)
            assertEquals("""{"ok":false,"error":"unauthorized"}""", body)
        } finally {
            proxy.stop()
        }
    }

    @Test fun addRoute_withToken_returnsHandlerJson() {
        val proxy = CrawlerProxyServer(
            tokenManagerWith(TOKEN), { 0 }, freePort(),
            addHandler = mockAddHandler(CrawlerAddOutcome.Added(false, "Movie", true)),
        )
        proxy.start()
        try {
            val (code, body) = httpPost(
                "http://127.0.0.1:${proxy.port}/api/transmission/add?hash=${"a".repeat(40)}&token=$TOKEN")
            assertEquals(200, code)
            assertEquals(
                """{"ok":true,"added":true,"duplicate":false,"name":"Movie","checked":true}""",
                body)
        } finally {
            proxy.stop()
        }
    }

    @Test fun addRoute_withTokenAndNoHandler_returns503DbUnavailable() {
        val proxy = CrawlerProxyServer(tokenManagerWith(TOKEN), { 0 }, freePort())
        proxy.start()
        try {
            val (code, body) = httpPost(
                "http://127.0.0.1:${proxy.port}/api/transmission/add?hash=${"a".repeat(40)}&token=$TOKEN")
            assertEquals(503, code)
            assertEquals("""{"ok":false,"error":"db_unavailable"}""", body)
        } finally {
            proxy.stop()
        }
    }
}
