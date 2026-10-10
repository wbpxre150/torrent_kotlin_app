package com.torrent.app

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            "Accept" to "text/html",
            "User-Agent" to "JUnit",
        )
        val out = CrawlerProxyServer.filteredRequestHeaders(headers)
        assertNull(out["Host"])
        assertNull(out["Connection"])
        assertNull(out["Transfer-Encoding"])
        assertNull(out["Authorization"])
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
}
