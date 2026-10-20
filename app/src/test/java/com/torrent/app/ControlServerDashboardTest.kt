package com.torrent.app

import android.content.Context
import android.content.SharedPreferences
import com.torrent.app.config.AppConfig
import com.torrent.app.config.ApplyResult
import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.FtpSettings
import com.torrent.app.config.TransmissionSettings
import com.torrent.app.ftp.FtpStatus
import com.torrent.app.storage.SafStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Contract tests for the dashboard's daemons area (webui stage 2): one
 * collapsible card per daemon in `daemonStatuses()` order, each carrying the
 * stable `data-*` hooks the live client patches in stage 3 (`data-daemon`,
 * `data-field`, `data-log-for`, `data-toggle`) plus `#service-state`, `#live`
 * and `#rollback-banner`.
 *
 * The old six-column daemons table and the standalone DHT-crawler section are
 * gone (the crawler used to render twice) and its 5 s `/api/crawler/stats`
 * poll is gone with it. Plain JUnit4 + Mockito: the builders are driven through
 * their injected collaborator lambdas, no server and no Robolectric.
 */
class ControlServerDashboardTest {

    private companion object {
        const val TOKEN = "unit-test-token"
        val LABELS = listOf(
            DaemonService.TRANSMISSION_LABEL,
            DaemonService.CRAWLER_LABEL,
            DaemonService.FTP_LABEL,
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

    private fun newServer(
        daemons: List<ProcessStatus> = defaultDaemons(),
        rolledBack: Boolean = false,
    ): ControlServer {
        val config = AppConfig(
            wizardCompleted = true,
            transmission = TransmissionSettings("u", "p", 51413, 0, 0, 0, 0),
            ftp = FtpSettings("kodi", "pw", 2121, false),
            crawler = CrawlerSettings(),
            backup = BackupSettings(),
            dataSubpath = "data",
        )
        val notWired = ApplyResult(false, "test", false)
        return ControlServer(
            tokenManager = tokenManagerWith(TOKEN),
            serviceState = MutableStateFlow(ServiceState.Running),
            daemonStatuses = { daemons },
            safStatus = { SafStatus(false, false, null, null) },
            wifiIp = { null },
            ftpStatus = {
                FtpStatus(true, "RUNNING", 4242, 1, "127.0.0.1", 2121, "kodi", "pw", true)
            },
            loadConfig = { config },
            wizardCompleted = { true },
            rpcSummary = { null },
            usbFreeSpace = { null },
            lastApplyRolledBack = { rolledBack },
            restartDaemon = { false },
            stopDaemon = { false },
            startDaemon = { false },
            applyTransmission = { _, _ -> notWired },
            applyFtp = { notWired },
        )
    }

    /** One status per daemon, with a PID/uptime/log tail so every cell renders. */
    private fun defaultDaemons(): List<ProcessStatus> {
        val now = System.currentTimeMillis()
        return listOf(
            ProcessStatus(
                label = DaemonService.TRANSMISSION_LABEL,
                state = ProcessState.RUNNING,
                pid = 101,
                startTimeMs = now - 65_000,
                restartCount = 2,
                lastLogLines = listOf("tx <line> & \"quoted\""),
            ),
            ProcessStatus(
                label = DaemonService.CRAWLER_LABEL,
                state = ProcessState.STOPPED,
                restartCount = 1,
                lastLogLines = listOf("crawler stopped"),
            ),
            ProcessStatus(
                label = DaemonService.FTP_LABEL,
                state = ProcessState.RUNNING,
                pid = 303,
                startTimeMs = now - 5_000,
            ),
        )
    }

    private val html = newServer().buildDashboard()

    /** The markup of one card, from its `data-daemon` hook to its closing tag. */
    private fun card(page: String, label: String): String {
        val start = page.indexOf("data-daemon=\"$label\"")
        assertTrue("no card for daemon $label", start >= 0)
        val end = page.indexOf("</section>", start)
        assertTrue("card for $label is not closed", end > start)
        return page.substring(start, end)
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            n++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return n
    }

    @Test fun daemonsArea_rendersExactlyOneCardPerDaemon() {
        for (label in LABELS) {
            assertEquals(
                "expected exactly one $label card",
                1,
                count(html, "data-daemon=\"$label\""),
            )
        }
    }

    @Test fun daemonsArea_keepsDaemonStatusOrder() {
        // Render order follows the supplier, so a fake that supplies a single
        // daemon (or a reordered list) still gets a coherent page.
        val reversed = newServer(daemons = defaultDaemons().reversed()).buildDashboard()
        assertTrue(
            "cards must follow daemonStatuses() order",
            reversed.indexOf("data-daemon=\"${DaemonService.FTP_LABEL}\"") <
                reversed.indexOf("data-daemon=\"${DaemonService.TRANSMISSION_LABEL}\""),
        )
        val crawlerOnly = newServer(
            daemons = defaultDaemons().filter { it.label == DaemonService.CRAWLER_LABEL },
        ).buildDashboard()
        assertEquals(1, count(crawlerOnly, "data-daemon="))
        assertEquals(1, count(crawlerOnly, "data-log-for=\"${DaemonService.CRAWLER_LABEL}\""))
    }

    @Test fun daemonsArea_dropsTheOldTableAndTheDuplicateCrawlerSection() {
        assertFalse("the 6-column daemons table must be gone", html.contains("<th>Name</th>"))
        assertFalse("no daemons table row spans 6 columns", html.contains("colspan=\"6\""))
        assertFalse("the standalone crawler section must be gone", html.contains("<h2>DHT Crawler</h2>"))
        assertFalse(
            "the FTP section must be folded into its card",
            html.contains("<h2>FTP Server (wifi only)</h2>"),
        )
        // The crawler's legacy 5 s poll is replaced by the stage-3 push.
        assertFalse("no legacy crawler stats poll", html.contains("/api/crawler/stats"))
        assertFalse("no legacy poll interval", html.contains("setInterval"))
    }

    @Test fun crawlerCard_carriesItsExtraFields() {
        val crawler = card(html, DaemonService.CRAWLER_LABEL)
        for (field in listOf("dbSize", "internalFree", "statsLine")) {
            assertTrue("crawler card is missing data-field=$field", crawler.contains("data-field=\"$field\""))
        }
        assertTrue("crawler card must link the search page", crawler.contains("<a href=\"/search\">"))
    }

    @Test fun everyCard_hasThreeActionFormsAndOneLogPane() {
        for (label in LABELS) {
            val c = card(html, label)
            assertEquals("$label card must keep three action forms", 3, count(c, "<form"))
            for (action in listOf("start", "stop", "restart")) {
                assertTrue(
                    "$label card is missing its $action form",
                    c.contains("action=\"/daemon/$label/$action\""),
                )
            }
            assertEquals("$label card must have one log pane", 1, count(c, "data-log-for="))
            assertTrue(c.contains("data-log-for=\"$label\""))
            assertTrue(c.contains("class=\"loglines daemon-log\""))
            assertTrue("$label head must be the collapse hook", c.contains("data-toggle"))
            for (field in listOf("state", "pid", "uptime", "restarts")) {
                assertTrue("$label card is missing data-field=$field", c.contains("data-field=\"$field\""))
            }
        }
    }

    @Test fun header_carriesTheLiveHooks() {
        assertTrue(html.contains("id=\"service-state\""))
        assertTrue(html.contains("id=\"rollback-banner\""))
        assertTrue(html.contains("id=\"live\""))
        // The banner is server-filled but hidden unless an apply was rolled back.
        assertTrue(html.contains("id=\"rollback-banner\" class=\"warn\" hidden"))
    }

    @Test fun rollbackBanner_shownWhenAnApplyRolledBack() {
        val rolledBack = newServer(rolledBack = true).buildDashboard()
        assertFalse(rolledBack.contains("id=\"rollback-banner\" class=\"warn\" hidden"))
        assertTrue(rolledBack.contains("id=\"rollback-banner\" class=\"warn\""))
    }

    @Test fun dashboardCss_hasTheCardAndLogRules() {
        assertTrue(html.contains(".daemon-card{"))
        assertTrue(html.contains(".daemon-head{"))
        assertTrue(html.contains(".daemon-body[hidden]{"))
        assertTrue(html.contains(".row .k{"))
        assertTrue(html.contains(".row .v{"))
        assertTrue(html.contains(".daemon-log{overflow:auto}"))
        // The shared `pre` cap keeps a 30-line tail from growing the page.
        assertTrue(html.contains("max-height:200px"))
        // Card buttons use the shared 44px .btn: the 36px table override is gone.
        assertFalse(html.contains(".actions .btn{"))
    }

    /** The one inline `<script>` of the dashboard (there is no external one). */
    private fun inlineScript(page: String): String {
        assertFalse("the dashboard must not load external scripts", page.contains("<script src"))
        val start = page.lastIndexOf("<script>")
        assertTrue("the dashboard must inline its live client", start >= 0)
        val end = page.indexOf("</script>", start)
        assertTrue("the inline script is not closed", end > start)
        return page.substring(start, end)
    }

    @Test fun dashboardScript_connectsTheDashboardSocketOverTheSharedClient() {
        val script = inlineScript(html)
        // One socket URL, and it carries the page's query string so the
        // ?token= (and the control_token cookie) reach the upgrade.
        assertEquals("exactly one /dashboard/ws URL", 1, count(html, "/dashboard/ws"))
        assertTrue("the ws URL must ride location.search", script.contains("location.search"))
        assertTrue("the shared client marker is missing", script.contains("connectSocket"))
        assertTrue(script.contains("wsUrl("))
        assertTrue(script.contains("setLive("))
        assertTrue("the log writer hook is missing", script.contains("patchLogs("))
        // Surgical patching only: the value/class/attribute writers are there.
        assertTrue(script.contains("patchText("))
        assertTrue(script.contains("patchState("))
        assertTrue(script.contains(".textContent"))
        // Collapse and action-form delegation.
        assertTrue("the collapse handler is missing", script.contains(".daemon-head[data-toggle]"))
        assertTrue(script.contains("preventDefault()"))
        assertTrue(script.contains("redirect:'manual'"))
        assertTrue(script.contains("credentials:'same-origin'"))
    }

    /**
     * Stage 5: all three live pages ride the one inline shared websocket
     * client (no per-page proto/backoff copies, no external script).
     */
    @Test fun allLivePages_inlineTheSharedWebsocketClient() {
        val pages = mapOf(
            "/" to html,
            "/stats" to ControlServer.buildStatsPage(),
            "/search" to ControlServer.buildSearchPage(),
        )
        for ((route, page) in pages) {
            assertTrue("$route must inline the shared client", page.contains("connectSocket"))
            assertTrue("$route must not load an external script", !page.contains("<script src"))
        }
    }

    @Test fun dashboardScript_neverNavigatesOrRebuildsMarkup() {
        val script = inlineScript(html)
        // Scroll position and card open/closed state survive a push because the
        // script neither navigates nor replaces any section.
        for (forbidden in listOf("location.reload", "location.href")) {
            assertFalse("$forbidden must not appear in the dashboard script",
                script.contains(forbidden))
        }
        assertTrue(
            "innerHTML assignment must not appear in the dashboard script",
            Regex("innerHTML\\s*=").find(script) == null
        )
    }

    @Test fun dashboardScript_tailFollowsLogsAndHoldsTheScrollPosition() {
        val script = inlineScript(html)
        // The writer measures the pane's scroll box to decide whether the user
        // is already at the bottom before it writes anything.
        for (metric in listOf("scrollHeight", "scrollTop", "clientHeight")) {
            assertTrue("the log writer must read $metric", script.contains(metric))
        }
        // A cheap signature of the tail gates the write: an unchanged tail
        // (the common case, a 10 s push repeating the same lines) costs no DOM
        // write and no scroll touch, and the gate runs before the write.
        assertTrue("the log signature hook is missing", script.contains("data-logsig"))
        assertTrue(
            "the signature check must precede the log write",
            script.indexOf("data-logsig") < script.indexOf("pre.textContent"),
        )
        // Logs are plain text: one textContent writer, no markup rebuild.
        assertTrue("logs must be written via textContent", script.contains("pre.textContent"))
        // Only the pane's own scrollTop may be written; the page must never be
        // scrolled and the pane must never be scrolled into view.
        val pageScroll = listOf("scrollIntoView", "window.scrollY", "document.scrollingElement")
        for (forbidden in pageScroll) {
            assertFalse("$forbidden must not appear in the dashboard script",
                script.contains(forbidden))
        }
    }
}
