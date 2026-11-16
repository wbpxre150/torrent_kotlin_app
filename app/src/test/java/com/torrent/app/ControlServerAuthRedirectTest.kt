package com.torrent.app

import android.content.Context
import android.content.SharedPreferences
import com.torrent.app.config.AppConfig
import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.FtpSettings
import com.torrent.app.config.TransmissionSettings
import com.torrent.app.ftp.FtpStatus
import com.torrent.app.storage.SafStatus
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * Live routing tests for the POST redirects (webui B1): the control token must
 * never ride in a `Location` header. Every form POST used to echo it back as
 * `?token=…`, which leaked it into browser history, server logs and Referer
 * headers even though the client never reads the redirect target. The
 * redirects now answer with a token-free path and re-assert the
 * `control_token` cookie instead.
 *
 * A real ControlServer on a free port with a raw-socket POST client (house
 * style: android.jar has no java.net.http), every collaborator a
 * side-effect-free lambda. Runs under Robolectric so the real
 * `io.ktor.server.*` stack is exercised end to end.
 */
@RunWith(RobolectricTestRunner::class)
class ControlServerAuthRedirectTest {

    private companion object {
        const val TOKEN = "unit-test-token"
        const val COOKIE = "control_token=$TOKEN"

        /** Side-effect-free daemon POST: `status` matches no action branch. */
        val DAEMON_PATH = "/daemon/${DaemonService.CRAWLER_LABEL}/status"
    }

    private var port = 0
    private lateinit var control: ControlServer

    /** How often the injected reset lambda ran; proves the route's guard. */
    private val resetCalls = AtomicInteger()

    /** How often the injected cloudflared-save lambda ran; proves the split. */
    private val cloudflaredCalls = AtomicInteger()

    @Before fun startServer() {
        port = freePort()
        control = newServer(port)
        control.start()
    }

    @After fun stopServer() {
        control.stop()
    }

    // ── daemon route ───────────────────────────────────────────────────────

    @Test fun daemonPost_withCookie_redirectsToRoot_withoutToken() {
        val r = postWithRetry(DAEMON_PATH, cookie = COOKIE)
        assertEquals(r.statusLine, 302, r.status)
        assertEquals("/", r.header("Location"))
        val setCookie = r.header("Set-Cookie")
        assertNotNull("expected a Set-Cookie on the redirect", setCookie)
        assertTrue("cookie must carry the token", setCookie!!.contains("control_token="))
        assertTrue("cookie must stay HttpOnly", setCookie.contains("HttpOnly"))
        assertTrue("cookie must stay SameSite", setCookie.contains("SameSite"))
    }

    @Test fun daemonPost_withQueryToken_redirectsToRoot_withoutToken() {
        val r = postWithRetry("$DAEMON_PATH?token=$TOKEN")
        assertEquals(r.statusLine, 302, r.status)
        assertEquals("/", r.header("Location"))
        val setCookie = r.header("Set-Cookie")
        assertNotNull("expected a Set-Cookie on the redirect", setCookie)
        assertTrue("cookie must carry the token", setCookie!!.contains("control_token="))
        assertTrue("cookie must stay HttpOnly", setCookie.contains("HttpOnly"))
        assertTrue("cookie must stay SameSite", setCookie.contains("SameSite"))
    }

    @Test fun daemonPost_withoutToken_isRejected() {
        val r = postWithRetry(DAEMON_PATH)
        assertEquals(r.statusLine, 401, r.status)
    }

    // ── sibling form routes ────────────────────────────────────────────────

    @Test fun transmissionPost_unchanged_redirectsWithoutToken() {
        assertSettingsRedirect("/settings/transmission", "")
    }

    @Test fun transmissionPost_applyBranch_redirectsWithoutToken() {
        // A different peer port makes needsRestart() true, so the apply branch
        // (not the unchanged one) builds the redirect.
        assertSettingsRedirect("/settings/transmission", "peerPort=51414")
    }

    @Test fun ftpPost_redirectsWithoutToken() {
        assertSettingsRedirect("/settings/ftp", "")
    }

    @Test fun crawlerPost_rejectedForm_redirectsWithoutToken() {
        // No saveCategories at all: the form is rejected before anything runs.
        assertSettingsRedirect("/settings/crawler", "")
    }

    @Test fun crawlerPost_acceptedForm_redirectsWithoutToken() {
        assertSettingsRedirect("/settings/crawler", "saveCategories=movie")
    }

    // ── the tunnel card's own route (Stage 6) ──────────────────────────────

    /**
     * Stage 6: `POST /settings/cloudflared` is the tunnel card's own route. A form
     * that ticks "enabled" with no stored token is rejected before anything is
     * written — [cloudflaredCalls] proves the save slot never ran, so no store
     * write and no cloudflared start/stop happened.
     */
    @Test fun cloudflaredPost_rejectedForm_redirectsWithoutTokenAndWritesNothing() {
        val r = postWithRetry(
            "/settings/cloudflared", body = "cloudflaredEnabled=on", cookie = COOKIE)
        assertEquals(r.statusLine, 302, r.status)
        val location = r.header("Location")
        assertNotNull("expected a Location on the tunnel redirect", location)
        assertTrue(
            "expected a /settings/crawler?msg= redirect, got: $location",
            location!!.startsWith("/settings/crawler?msg=")
        )
        assertFalse("the Location must not carry token=: $location", location.contains("token="))
        assertEquals(
            "Tunnel token is required when the Cloudflare tunnel is enabled",
            redirectMessage(location)
        )
        assertTrue("expected ok=false, got: $location", location.endsWith("&ok=false"))
        assertEquals("a rejected tunnel form must write nothing", 0, cloudflaredCalls.get())
    }

    @Test fun cloudflaredPost_acceptedForm_savesThroughItsOwnSlot() {
        val r = postWithRetry(
            "/settings/cloudflared", body = "cloudflaredHostname=abc.example.com", cookie = COOKIE)
        assertEquals(r.statusLine, 302, r.status)
        val location = r.header("Location")
        assertNotNull("expected a Location on the tunnel redirect", location)
        assertTrue(
            "expected a /settings/crawler?msg= redirect, got: $location",
            location!!.startsWith("/settings/crawler?msg=")
        )
        assertFalse("the Location must not carry token=: $location", location.contains("token="))
        assertEquals("the save result's message is the redirect's msg", "tunnel saved",
            redirectMessage(location))
        assertTrue("expected ok=true, got: $location", location.endsWith("&ok=true"))
        assertEquals("the tunnel save must reach its own slot exactly once",
            1, cloudflaredCalls.get())
    }

    /**
     * The point of Stage 6: a crawler apply never reconciles the tunnel, and a
     * tunnel save never applies the crawler — the two forms have separate routes.
     */
    @Test fun crawlerPost_neverTouchesTheCloudflaredSlot() {
        val r = postWithRetry("/settings/crawler", body = "saveCategories=movie", cookie = COOKIE)
        assertEquals(r.statusLine, 302, r.status)
        assertEquals("the crawler POST must not save tunnel settings",
            0, cloudflaredCalls.get())
    }

    @Test fun crawlerMaintenancePost_redirectsWithoutToken() {
        // A surviving action: compact/category-filter-update are refused by the route.
        assertSettingsRedirect("/crawler/maintenance/check-database", "", "/settings/crawler")
    }

    @Test fun crawlerBackupUploadPost_redirectsWithoutToken() {
        assertSettingsRedirect("/crawler/backup/upload", "", "/settings/crawler")
    }

    @Test fun crawlerBackupDbPost_redirectsWithoutToken() {
        assertSettingsRedirect("/crawler/backup/db", "", "/settings/crawler")
    }

    @Test fun crawlerRestoreDbPost_redirectsWithoutToken() {
        assertSettingsRedirect("/crawler/restore/db", "", "/settings/crawler")
    }

    /**
     * "Reset backup from zero" wipes the whole remote DB dump chain, so a form
     * post without the typed confirmation must be refused *and* must not reach
     * the manager — the JS prompt is a convenience, not the guard.
     */
    @Test fun dbResetPost_refusesWrongConfirmWithoutCallingReset() {
        val r = postWithRetry("/crawler/backup/db/reset", body = "confirm=nope", cookie = COOKIE)
        assertEquals(r.statusLine, 302, r.status)
        val location = r.header("Location")
        assertNotNull("expected a Location on the reset redirect", location)
        assertTrue(
            "expected a /settings/crawler?msg= redirect, got: $location",
            location!!.startsWith("/settings/crawler?msg=")
        )
        assertFalse("the Location must not carry token=: $location", location.contains("token="))
        val msg = redirectMessage(location)
        assertTrue("expected a refusal message, got: $msg", msg.contains("refused"))
        assertTrue("expected ok=false, got: $location", location.endsWith("&ok=false"))
        assertEquals("the reset must not run without the typed word", 0, resetCalls.get())
    }

    @Test fun dbResetPost_confirmedCallsResetOnce() {
        val r = postWithRetry("/crawler/backup/db/reset", body = "confirm=RESET", cookie = COOKIE)
        assertEquals(r.statusLine, 302, r.status)
        val location = r.header("Location")
        assertNotNull("expected a Location on the reset redirect", location)
        assertTrue(
            "expected a /settings/crawler?msg= redirect, got: $location",
            location!!.startsWith("/settings/crawler?msg=")
        )
        assertFalse("the Location must not carry token=: $location", location.contains("token="))
        assertEquals("db backup reset queued", redirectMessage(location))
        assertTrue("expected ok=true, got: $location", location.endsWith("&ok=true"))
        assertEquals("the reset must be queued exactly once", 1, resetCalls.get())
    }

    /** The `msg=` status the redirect carries, URL-decoded. */
    private fun redirectMessage(location: String): String = java.net.URLDecoder.decode(
        location.substringAfter("msg=").substringBefore("&ok="), "UTF-8")

    /**
     * Posts an authenticated form to [path] and pins the redirect contract: a
     * 302 to [expect] with its `msg=` status, and no token anywhere in the
     * Location. [expect] differs from [path] for the crawler maintenance /
     * backup / restore actions, which land on the crawler settings page.
     */
    private fun assertSettingsRedirect(path: String, body: String, expect: String = path) {
        val r = postWithRetry(path, body = body, cookie = COOKIE)
        assertEquals(r.statusLine, 302, r.status)
        val location = r.header("Location")
        assertNotNull("expected a Location on the $path redirect", location)
        assertTrue(
            "expected a $expect?msg= redirect, got: $location",
            location!!.startsWith("$expect?msg="),
        )
        assertFalse("the Location must not carry token=: $location", location.contains("token="))
        assertFalse("the Location must not carry ?token: $location", location.contains("?token"))
    }

    // ── plumbing (same wiring as ControlServerFaviconTest) ─────────────────

    private fun newServer(port: Int): ControlServer {
        val daemon = ProcessStatus(
            label = DaemonService.CRAWLER_LABEL,
            state = ProcessState.STOPPED,
        )
        val config = AppConfig(
            wizardCompleted = true,
            transmission = TransmissionSettings("u", "p", 51413, 0, 0, 0, 0),
            ftp = FtpSettings("kodi", "pw", 2121, false),
            crawler = CrawlerSettings(),
            backup = BackupSettings(),
            dataSubpath = "data",
        )
        val notWired = com.torrent.app.config.ApplyResult(false, "test", false)
        return ControlServer(
            tokenManager = tokenManagerWith(TOKEN),
            serviceState = MutableStateFlow(ServiceState.Running),
            daemonStatuses = { listOf(daemon) },
            safStatus = { SafStatus(false, false, null, null) },
            wifiIp = { null },
            ftpStatus = { FtpStatus(false, "STOPPED", null, 0, null, 2121, "kodi", null, false) },
            loadConfig = { config },
            wizardCompleted = { true },
            rpcSummary = { null },
            usbFreeSpace = { null },
            lastApplyRolledBack = { false },
            restartDaemon = { false },
            stopDaemon = { false },
            startDaemon = { false },
            applyTransmission = { _, _ -> notWired },
            applyFtp = { notWired },
            crawlerBackupDbReset = {
                resetCalls.incrementAndGet()
                "db backup reset queued"
            },
            saveCloudflared = {
                cloudflaredCalls.incrementAndGet()
                com.torrent.app.config.ApplyResult(true, "tunnel saved", false)
            },
            port = port,
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun tokenManagerWith(token: String): TokenManager {
        val prefs = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(prefs.getString("auth_token", null)).thenReturn(token)
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(
            context.getSharedPreferences("control_auth", Context.MODE_PRIVATE)
        ).thenReturn(prefs)
        return TokenManager(context)
    }

    /** One HTTP response as the client sees it (status line + header lines). */
    private class Response(val status: Int, val statusLine: String, private val headers: List<String>) {
        fun header(name: String): String? = headers
            .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    }

    /** POSTs to the live server, retrying while the CIO listener comes up. */
    private fun postWithRetry(path: String, body: String = "", cookie: String? = null): Response {
        var last: Exception? = null
        repeat(40) {
            try {
                return post(path, body, cookie)
            } catch (e: ConnectException) {
                last = e
                Thread.sleep(50)
            } catch (e: SocketException) {
                last = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("server never came up on port $port", last)
    }

    private fun post(path: String, body: String, cookie: String?): Response {
        // Deliberately not `Socket().apply { ... }`: inside that block `port`
        // would resolve to Socket.getPort() (0) instead of the field.
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 10_000
        socket.tcpNoDelay = true
        try {
            val payload = body.toByteArray()
            val request = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                if (cookie != null) append("Cookie: $cookie\r\n")
                append("Content-Type: application/x-www-form-urlencoded\r\n")
                append("Content-Length: ${payload.size}\r\n\r\n")
                append(body)
            }
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val input = socket.getInputStream().buffered()
            val header = StringBuilder()
            while (!header.toString().endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b == -1) break
                header.append(b.toChar())
            }
            val lines = header.toString().split("\r\n").filter { it.isNotEmpty() }
            val statusLine = lines.firstOrNull() ?: ""
            val status = statusLine.substringAfter(" ", "").substringBefore(" ").toIntOrNull() ?: 0
            return Response(status, statusLine, lines.drop(1))
        } finally {
            runCatching { socket.close() }
        }
    }
}
