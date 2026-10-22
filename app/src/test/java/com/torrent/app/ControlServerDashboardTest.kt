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
import com.torrent.app.transmission.TransmissionSummary
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
        rpcSummary: () -> TransmissionSummary? = { null },
        crawlerPausedNoWifi: () -> Boolean = { false },
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
            rpcSummary = rpcSummary,
            usbFreeSpace = { null },
            lastApplyRolledBack = { rolledBack },
            restartDaemon = { false },
            stopDaemon = { false },
            startDaemon = { false },
            applyTransmission = { _, _ -> notWired },
            applyFtp = { notWired },
            crawlerPausedNoWifi = crawlerPausedNoWifi,
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

    /**
     * The cards show a human title while the raw daemon label stays the key the
     * live client addresses (`data-daemon`, form actions, `data-log-for`), so
     * only the visible `.dname` text is humanized.
     */
    @Test fun daemonCards_showHumanTitlesAndKeepTheRawLabelHooks() {
        val titles = mapOf(
            DaemonService.CRAWLER_LABEL to "DHT Crawler",
            DaemonService.TRANSMISSION_LABEL to "Transmission",
            DaemonService.FTP_LABEL to "FTP Server",
        )
        for ((label, title) in titles) {
            assertTrue(
                "$label card must show the human title",
                card(html, label).contains("<span class=\"dname\">$title</span>"),
            )
            assertFalse(
                "$label must not be rendered as its own title",
                html.contains("<span class=\"dname\">$label</span>"),
            )
            assertTrue(
                "$label card must keep the raw data-daemon hook",
                html.contains("data-daemon=\"$label\""),
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

    /** The search link is labelled "Search": the crawler card carries no room
     * for the old "Search dashboard" wording. */
    @Test fun crawlerCard_labelsTheSearchLinkAsSearch() {
        val crawler = card(html, DaemonService.CRAWLER_LABEL)
        assertTrue(
            "crawler card must label the search link 'Search'",
            crawler.contains("<span class=\"k\">Search</span>"),
        )
        assertFalse(
            "the old 'Search dashboard' label must be gone",
            html.contains("Search dashboard"),
        )
    }

    /**
     * Corrective stage 9 (P2.2a): the crawler's paused badge is rendered on
     * every render and hidden by attribute when the crawler is not paused, so
     * a pause that *starts* after the page loaded shows up within one push
     * instead of waiting for a reload. Only the crawler card carries the field.
     */
    @Test fun crawlerCard_pausedBadgeAlwaysRenderedAndHiddenWhenNotPaused() {
        val notPaused = card(html, DaemonService.CRAWLER_LABEL)
        assertTrue(
            "the crawler card must always carry the paused badge hook",
            notPaused.contains("data-field=\"pausedNoWifi\""),
        )
        assertTrue(
            "the badge must render hidden when the crawler is not paused",
            notPaused.contains("data-field=\"pausedNoWifi\" hidden>"),
        )
        // It stays a `.state` pill: only the attribute toggles, never the class.
        assertTrue(notPaused.contains("class=\"state STOPPED\" data-field=\"pausedNoWifi\""))
        for (label in LABELS - DaemonService.CRAWLER_LABEL) {
            assertFalse(
                "$label must not gain a pausedNoWifi field",
                card(html, label).contains("pausedNoWifi"),
            )
        }

        val paused = card(
            newServer(crawlerPausedNoWifi = { true }).buildDashboard(),
            DaemonService.CRAWLER_LABEL,
        )
        assertTrue(paused.contains("data-field=\"pausedNoWifi\""))
        assertFalse(
            "the badge must be visible while paused",
            paused.contains("data-field=\"pausedNoWifi\" hidden"),
        )
    }

    /**
     * Corrective stage 9 (P2.3): the log signature hashes the whole 30-line
     * tail. The old `len:lines:last-80-chars` signature missed an update that
     * happened earlier in the tail while keeping line count and total length.
     */
    @Test fun dashboardScript_logSignatureCoversTheWholeTail() {
        val script = inlineScript(html)
        assertTrue("the signature must be an FNV-1a hash", script.contains("Math.imul"))
        assertTrue("the FNV-1a prime must be used", script.contains("16777619"))
        assertTrue("the hash must fold in every character", script.contains("charCodeAt"))
        assertTrue(
            "the lossy last-80-chars signature must be gone",
            !script.contains("text.slice(-80)"),
        )
        // The signature still gates the write (no DOM write on an unchanged tail).
        assertTrue(script.contains("data-logsig"))
        assertTrue(
            "the hash must be computed before the signature gate",
            script.indexOf("16777619") < script.indexOf("data-logsig"),
        )
    }

    /** The client flips the badge's `hidden` attribute; it never rewrites markup. */
    @Test fun dashboardScript_togglesThePausedBadgeByAttribute() {
        val script = inlineScript(html)
        assertTrue("the client must toggle the badge's hidden attribute",
            script.contains("paused.hidden ="))
        assertTrue(
            "the badge must not be toggled through inline styles",
            !script.contains("paused.style.display"),
        )
        assertFalse(
            "the badge must never be added/removed by markup",
            Regex("innerHTML\\s*=").find(script) != null,
        )
    }

    /**
     * Webui stage 4: the torrent table is patched row by row from the frame's
     * `transmission.rows`, keyed by infohash. New rows are built with
     * document.createElement and filled via patchText, so no markup is rebuilt
     * (card shell, page scroll and open/closed state survive by construction).
     */
    @Test fun dashboardScript_patchesTorrentRowsByInfohash() {
        val script = inlineScript(html)
        assertTrue("the torrent row writer is missing", script.contains("patchTorrentRows("))
        assertTrue("rows must be addressed by infohash", script.contains("tr[data-infohash]"))
        assertTrue(
            "a new row must be built as an element, not as markup",
            script.contains("createElement('tr')"),
        )
        assertTrue("the frame rows hook is missing", script.contains("tx.rows"))
        assertTrue("the empty-state row must be toggleable", script.contains("tx-empty"))
        // Cells go through the single text writer and the row list is not
        // rebuilt: the forbidden writers stay out (see the never-rebuilds test).
        assertTrue(
            "the row cells must be written with patchText",
            script.indexOf("patchTorrentRows(") < script.indexOf("patchText(cells[0]"),
        )
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

    /**
     * Webui stage 7 (fixes the false exit-criterion-8 mechanism): the `GET /`
     * render path must never call the transmission RPC — the extras come from
     * the ticker's cached snapshot, so a wedged (not crashed) daemon cannot
     * stall the page behind two 3 s loopback calls. With no snapshot yet the
     * card shows the unreachable banner and `—` values, which is exactly what
     * the first pushed frame then confirms (no flicker).
     */
    @Test fun dashboardRenderDoesNotCallRpcSummary() {
        val page = newServer(
            rpcSummary = { throw AssertionError("GET / must not hit RPC") },
        ).buildDashboard()
        val tx = card(page, DaemonService.TRANSMISSION_LABEL)
        assertTrue(
            "the unreachable banner must be visible without a snapshot",
            tx.contains("data-field=\"rpcUnreachable\">Transmission RPC not reachable."),
        )
        assertFalse(
            "the banner must not be hidden when the cache is empty",
            tx.contains("data-field=\"rpcUnreachable\" hidden"),
        )
        assertTrue(
            "limits must render as an em dash without a snapshot",
            tx.contains("data-field=\"limits\">—</span>"),
        )
        assertTrue(
            "the download dir must render as an em dash without a snapshot",
            tx.contains("data-field=\"downloadDir\">—</code>"),
        )
    }

    @Test fun dashboardCss_hasTheCardAndLogRules() {
        assertTrue(html.contains(".daemon-card{"))
        assertTrue(html.contains(".daemon-head{"))
        assertTrue(html.contains(".daemon-body[hidden]{"))
        // `.state` sets display, so the hidden badge needs its own rule.
        assertTrue(html.contains(".daemon-head .state[hidden]{display:none}"))
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

    /**
     * Corrective stage 8: a frame that fails to parse must never be a silent
     * no-op. Before this, one control byte in a log line made `JSON.parse`
     * throw, the frame was dropped, and the `#live` chip stayed green while
     * the feed froze. The guard now flips the chip to `bad`/`feed error` and
     * restores `on`/`live` on the next frame that parses.
     */
    @Test fun dashboardScript_surfacesAFrameParseFailure() {
        val script = inlineScript(html)
        assertTrue("the parse guard must label the failure", script.contains("'feed error'"))
        assertTrue(
            "the parse guard must flip the live chip to bad",
            script.contains("setLive('bad', 'feed error')"))
        assertTrue(
            "the live chip must come back on the next good frame",
            script.contains("setLive('on', 'live')"))
        // The failure path returns before any patching, so a broken frame never
        // half-applies (and never blanks a cell).
        val failure = script.indexOf("'feed error'")
        assertTrue(
            "the failure path must return before the patch block",
            failure < script.indexOf("if (f.service)"))
        // The chip's `bad` state must be styled, not just its text changed.
        assertTrue(html.contains("#live.bad{"))
    }

    /**
     * The crawler panel's DB-restore poll renders the import progress the
     * restore status carries: `objectsDone` of `objectCount` objects, so a
     * 17k-object restore reads "(X of 17000 objects imported)" instead of a
     * bare total. `objectsDone` is null before the count publishes, and the
     * fragment degrades to "(N objects imported)" then.
     */
    @Test fun crawlerPanel_dbRestorePoll_showsNofMObjectsImported() {
        val page = newServer().buildCrawlerPanel(null, true, false)
        assertTrue(
            "the restore poll must read objectsDone",
            page.contains("st.objectsDone"))
        assertTrue(
            "the restore poll must label the progress as imported",
            page.contains("objects imported"))
        assertTrue(
            "the 5 s restore poll must stay wired",
            page.contains("setInterval(pollDbRestore, 5000)"))
    }
}
