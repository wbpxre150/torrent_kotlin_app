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
import com.torrent.app.transmission.TorrentSummary
import com.torrent.app.transmission.TransmissionSummary
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * Live markup tests for the transmission torrent table (webui stage 4): the
 * rows the frame ships under `transmission.rows` must have a matching
 * server-rendered row keyed by infohash, and the empty-state row must be
 * rendered on every render (hidden while there are torrents) so the client can
 * always toggle it.
 *
 * A real ControlServer on a free port, a raw `HttpURLConnection` GET (house
 * style: android.jar has no java.net.http), and a bounded poll for the RPC
 * ticker's cached snapshot — the render path itself never calls the RPC.
 */
@RunWith(RobolectricTestRunner::class)
class ControlServerTorrentLiveTest {

    private companion object {
        const val TOKEN = "unit-test-token"

        /** Synthetic 40-hex infohash (never a real torrent's). */
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
        const val OTHER_HASH = "fedcba9876543210fedcba9876543210fedcba98"
    }

    private val summary = TransmissionSummary(
        downloadDir = "/saf/usb/data/torrents",
        speedLimitDown = 500,
        speedLimitUp = 125,
        torrents = listOf(
            TorrentSummary(
                name = "Ubuntu 24.04 desktop amd64",
                percentDone = 0.42,
                rateDownload = 2_048_000,
                rateUpload = 131_072,
                status = 4,
                sizeWhenDone = 3L shl 30,
                hashString = HASH,
            ),
            // Hostile name: every cell value must be escaped by the renderer.
            TorrentSummary(
                name = "A <b>&\"x",
                percentDone = 1.0,
                rateDownload = 0,
                rateUpload = 0,
                status = 4,
                sizeWhenDone = 0,
                hashString = OTHER_HASH,
            ),
        ),
    )

    @Test fun dashboard_markup_rendersInfohashKeyedRowsAndAHiddenEmptyState() {
        val page = awaitPage(summary) { it.contains("data-infohash=\"$HASH\"") }

        assertTrue(
            "the torrent row must be keyed by its infohash with the same 5 cells",
            page.contains(
                "<tr data-infohash=\"$HASH\"><td>Ubuntu 24.04 desktop amd64</td>" +
                    "<td>42%</td><td>2000.0 KB/s</td><td>128.0 KB/s</td><td>3.0 GB</td></tr>",
            ),
        )
        assertTrue(
            "the empty-state row must still be rendered, hidden while rows exist",
            page.contains(
                "<tr class=\"tx-empty\" hidden><td colspan=\"5\"><em>No torrents.</em></td></tr>",
            ),
        )
        assertTrue(
            "every row value must be escaped, never injected as markup",
            page.contains("<td>A &lt;b&gt;&amp;&quot;x</td>"),
        )
        assertFalse("an unescaped torrent name must never reach the page", page.contains("A <b>"))
        assertTrue(
            "the second torrent must get its own infohash-keyed row",
            page.contains("<tr data-infohash=\"$OTHER_HASH\">"),
        )
    }

    @Test fun dashboard_markup_withNoTorrents_showsTheEmptyStateAndNoRows() {
        val empty = summary.copy(torrents = emptyList())
        // The RPC snapshot lands on its own ticker loop; until it does there is
        // no table at all (the honest `rpc == null` render), so wait for the
        // reachable marker before asserting the markup.
        val page = awaitPage(empty) { it.contains("data-field=\"rpcUnreachable\" hidden") }

        assertTrue(
            "the empty state must be visible when there are no torrents",
            page.contains(
                "<tr class=\"tx-empty\"><td colspan=\"5\"><em>No torrents.</em></td></tr>",
            ),
        )
        assertTrue("the table itself must be rendered", page.contains("<table class=\"tx\">"))
        // The inline client script mentions the selector; only the markup matters.
        assertFalse("no torrent rows without torrents", page.contains("<tr data-infohash"))
    }

    // ── plumbing (same wiring as ControlServerWebSocketTest.startTestServer) ──

    /** GETs `/` until [ready] accepts the page, bounded to 10 s. */
    private fun awaitPage(rpc: TransmissionSummary, ready: (String) -> Boolean): String {
        val port = freePort()
        val control = newServer(port, rpc)
        control.start()
        try {
            val deadline = System.currentTimeMillis() + 10_000
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                last = getPage(port)
                if (ready(last)) return last
                Thread.sleep(100)
            }
            throw AssertionError(
                "the dashboard never rendered the expected torrent markup within 10s; last page: " +
                    last.take(2_000),
            )
        } finally {
            control.stop()
        }
    }

    private fun getPage(port: Int): String {
        val conn = connectWithRetry(port)
        try {
            if (conn.responseCode !in 200..299) {
                throw AssertionError("GET / answered ${conn.responseCode}")
            }
            return conn.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /** GET `/` with the query token, retrying while the CIO listener comes up. */
    private fun connectWithRetry(port: Int): HttpURLConnection {
        var last: Exception? = null
        repeat(40) {
            try {
                val conn = URL("http://127.0.0.1:$port/?token=$TOKEN")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                if (conn.responseCode in 200..299) return conn
                return conn
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun newServer(port: Int, rpc: TransmissionSummary): ControlServer {
        // The torrent table lives inside the transmission card, so that daemon
        // must be in the list for the table to render at all.
        val daemons = listOf(
            ProcessStatus(
                label = DaemonService.TRANSMISSION_LABEL,
                state = ProcessState.RUNNING,
            ),
            ProcessStatus(
                label = DaemonService.CRAWLER_LABEL,
                state = ProcessState.STOPPED,
            ),
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
            daemonStatuses = { daemons },
            safStatus = { SafStatus(false, false, null, null) },
            wifiIp = { null },
            ftpStatus = { FtpStatus(false, "STOPPED", null, 0, null, 2121, "kodi", null, false) },
            loadConfig = { config },
            wizardCompleted = { true },
            rpcSummary = { rpc },
            usbFreeSpace = { null },
            lastApplyRolledBack = { false },
            restartDaemon = { false },
            stopDaemon = { false },
            startDaemon = { false },
            applyTransmission = { _, _ -> notWired },
            applyFtp = { notWired },
            crawlerHttpPort = { 1 },
            port = port,
            wsPushPeriodMs = 150,
        )
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
}
