package com.torrent.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4 parser tests. Plain JUnit4: [CloudflaredTunnel] has no Android deps and
 * its HTTP GET is injected, so none of this needs Robolectric.
 *
 * The samples are real captures from the staged web-research report: the escaped
 * form is cloudflared's own log line (the `config="…"` payload is a JSON string
 * inside a log line, hence the doubled quotes), the unescaped form is what
 * `GET /config` returns.
 */
class CloudflaredTunnelTest {

    /** Real capture: the config-update line of a token-run tunnel. */
    private val escapedConfigLine =
        "INF Updated to new configuration config=\"{\\\"ingress\\\":[{\\\"service\\\":\\\"smb://localhost:445\\\"," +
            "\\\"hostname\\\":\\\"share.not-malicio.us\\\",\\\"originRequest\\\":{}}," +
            "{\\\"service\\\":\\\"rdp://localhost:3389\\\",\\\"hostname\\\":\\\"access.not-malicio.us\\\"," +
            "\\\"originRequest\\\":{}},{\\\"service\\\":\\\"http_status:404\\\"}]," +
            "\\\"warp-routing\\\":{\\\"enabled\\\":true}}\" version=16"

    private val unescapedConfigJson =
        "{\"ingress\":[{\"hostname\":\"dash.example.com\",\"service\":\"http://localhost:8090\"}," +
            "{\"service\":\"http_status:404\"}],\"warp-routing\":{\"enabled\":false}}"

    private val readyBody =
        "{\"status\":200,\"readyConnections\":2,\"connections\":[" +
            "{\"connIndex\":0,\"protocol\":\"http2\",\"edge\":\"ams01\"}," +
            "{\"connIndex\":1,\"protocol\":\"http2\",\"edge\":\"ams02\"}]}"

    // ── parseReady ─────────────────────────────────────────────────────────

    @Test
    fun parseReady_readsStatusAndConnections() {
        val probe = parseReady(readyBody)!!
        assertTrue(probe.ready)
        assertEquals(2, probe.readyConnections)
    }

    @Test
    fun parseReady_zeroConnectionsIsNotReady() {
        val probe = parseReady("{\"status\":503,\"readyConnections\":0}")!!
        assertFalse(probe.ready)
        assertEquals(0, probe.readyConnections)
    }

    @Test
    fun parseReady_ignoresSurroundingWhitespaceAndTrailingFields() {
        val probe = parseReady("{ \"status\" : 200 , \"readyConnections\" : 0 , \"x\":1 }")!!
        assertTrue(probe.ready)
        assertEquals(0, probe.readyConnections)
    }

    @Test
    fun parseReady_returnsNullForNonReadyBodies() {
        assertNull(parseReady(""))
        assertNull(parseReady("<html>404 page not found</html>"))
        assertNull(parseReady(unescapedConfigJson))
    }

    // ── extractHostname ────────────────────────────────────────────────────

    @Test
    fun extractHostname_escapedLogLine() {
        assertEquals("share.not-malicio.us", extractHostname(escapedConfigLine))
    }

    @Test
    fun extractHostname_unescapedJson() {
        assertEquals("dash.example.com", extractHostname(unescapedConfigJson))
    }

    @Test
    fun extractHostname_returnsNullWhenAbsent() {
        assertNull(extractHostname("INF Starting tunnel tunnelID=abcd"))
        assertNull(extractHostname("{\"ingress\":[{\"service\":\"http_status:404\"}]}"))
        assertNull(extractHostname("{\"hostname\":\"\"}"))
    }

    @Test
    fun hostnameFromLines_prefersTheNewestLine() {
        val lines = listOf(
            "INF Updated to new configuration config=\"{\\\"ingress\\\":[{\\\"hostname\\\":\\\"old.example.com\\\"}]}\"",
            "WRN something unrelated",
            "INF Updated to new configuration config=\"{\\\"ingress\\\":[{\\\"hostname\\\":\\\"new.example.com\\\"}]}\"",
        )
        assertEquals("new.example.com", hostnameFromLines(lines))
        assertNull(hostnameFromLines(emptyList()))
    }

    // ── CloudflaredTunnel.probe ────────────────────────────────────────────

    private fun tunnel(
        ready: Pair<Int, String>? = 200 to readyBody,
        config: Pair<Int, String>? = 200 to unescapedConfigJson,
    ): CloudflaredTunnel = CloudflaredTunnel(fetch = { url ->
        when {
            url.endsWith("/ready") -> ready
            url.endsWith("/config") -> config
            else -> null
        }
    })

    @Test
    fun probe_connectedWithHostnameFromLogLine() {
        val state = tunnel().probe(listOf(escapedConfigLine), storedHostname = "")
        assertTrue(state.connected)
        assertEquals("share.not-malicio.us", state.publicHostname)
    }

    @Test
    fun probe_fallsBackToConfigEndpointWhenTheLogLineIsMissing() {
        val state = tunnel().probe(emptyList(), storedHostname = "stored.example.com")
        assertTrue(state.connected)
        assertEquals("dash.example.com", state.publicHostname)
    }

    @Test
    fun probe_keepsTheStoredHostnameWhenNeitherSourceAnswers() {
        val state = tunnel(config = 500 to "ERR: no config").probe(emptyList(), storedHostname = "stored.example.com")
        assertTrue(state.connected)
        assertEquals("stored.example.com", state.publicHostname)
    }

    @Test
    fun probe_notReadyIsDisconnectedAndKeepsTheStoredHostname() {
        val state = tunnel(ready = 503 to "{\"status\":503,\"readyConnections\":0}")
            .probe(listOf(escapedConfigLine), storedHostname = "stored.example.com")
        assertFalse(state.connected)
        assertEquals("stored.example.com", state.publicHostname)
    }

    @Test
    fun probe_metricsServerDownIsDisconnected() {
        val state = tunnel(ready = null, config = null).probe(listOf(escapedConfigLine), storedHostname = "")
        assertFalse(state.connected)
        assertEquals("", state.publicHostname)
    }

    @Test
    fun probe_nonReadyHttp200BodyIsDisconnected() {
        // A 200 that is not the /ready payload (e.g. a proxy error page) is not "ready".
        val state = tunnel(ready = 200 to "<html>ok</html>").probe(emptyList(), storedHostname = "stored.example.com")
        assertFalse(state.connected)
        assertEquals("stored.example.com", state.publicHostname)
    }
}
