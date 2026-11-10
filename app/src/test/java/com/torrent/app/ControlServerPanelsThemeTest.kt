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
 * Contract tests for the four settings-panel HTML builders (webui stage 8,
 * part 1 of 2). The panel builders are exposed as an internal seam; this pins
 * their current behavioural contract so the stage-2 restyle can't silently
 * change the markup: every panel keeps the shared theme token, none of them
 * pulls in external assets, each form still posts to its route, and the
 * crawler panel keeps its JS hook ids/classes.
 *
 * Plain JUnit4 + Mockito only (no Robolectric): the harness mirrors
 * [ControlServerWebSocketTest.startTestServer], driving the builders through
 * their injected collaborator lambdas.
 */
class ControlServerPanelsThemeTest {

    private companion object {
        const val TOKEN = "unit-test-token"
        const val THEME_TOKEN = "--blue:#1a73e8"
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
        backup: BackupSettings = BackupSettings(),
        crawlerBackupDbStatus: () -> com.torrent.app.backup.DbBackupStatus =
            { com.torrent.app.backup.DbBackupStatus() },
        crawlerMaintenanceStatus: () -> MaintenanceStatus = { MaintenanceStatus() },
        crawlerApplyStatus: () -> CrawlerApplyStatus = { CrawlerApplyStatus() },
    ): ControlServer {
        val config = AppConfig(
            wizardCompleted = true,
            transmission = TransmissionSettings("u", "p", 51413, 0, 0, 0, 0),
            ftp = FtpSettings("kodi", "pw", 2121, false),
            crawler = CrawlerSettings(),
            backup = backup,
            dataSubpath = "data",
        )
        val notWired = ApplyResult(false, "test", false)
        return ControlServer(
            tokenManager = tokenManagerWith(TOKEN),
            serviceState = MutableStateFlow(ServiceState.Running),
            daemonStatuses = { emptyList() },
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
            crawlerBackupDbStatus = crawlerBackupDbStatus,
            crawlerMaintenanceStatus = crawlerMaintenanceStatus,
            crawlerApplyStatus = crawlerApplyStatus,
        )
    }

    private val server = newServer()

    private fun transmissionPanel() = server.buildTransmissionPanel(null, false)
    private fun ftpPanel() = server.buildFtpPanel(null, false)
    private fun storagePanel() = server.buildStoragePanel()
    /** Counts non-overlapping occurrences of a literal needle. */
    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    private fun crawlerPanel() = server.buildCrawlerPanel(null, false, false)

    /** Every panel must carry the shared theme token and no external assets. */
    private fun assertThemedAndSelfContained(html: String) {
        assertTrue("panel is missing the shared theme token", html.contains(THEME_TOKEN))
        assertFalse("panel must not load external scripts", html.contains("<script src"))
        assertFalse("panel must not load external stylesheets", html.contains("<link "))
        assertFalse("panel must not reference a CDN", html.contains("cdn."))
    }

    @Test fun transmissionPanel_themeAndRoute() {
        val html = transmissionPanel()
        assertThemedAndSelfContained(html)
        assertTrue("transmission form must post to its route", html.contains("action=\"/settings/transmission\""))
        assertTrue("transmission panel must use the shared card chrome", html.contains("class=\"card\""))
    }

    @Test fun ftpPanel_themeAndRoute() {
        val html = ftpPanel()
        assertThemedAndSelfContained(html)
        assertTrue("ftp form must post to its route", html.contains("action=\"/settings/ftp\""))
        assertTrue("ftp panel must use the shared card chrome", html.contains("class=\"card\""))
    }

    @Test fun storagePanel_themeAndSelfContained() {
        val html = storagePanel()
        assertThemedAndSelfContained(html)
        assertTrue("storage panel keeps its heading", html.contains("<h1>USB storage</h1>"))
        assertTrue("storage panel must use the shared card chrome", html.contains("class=\"card\""))
        assertFalse("storage panel is read-only: no form", html.contains("<form"))
    }

    @Test fun crawlerPanel_themeRouteAndHooks() {
        val html = crawlerPanel()
        assertThemedAndSelfContained(html)
        assertTrue("crawler form must post to its route", html.contains("action=\"/settings/crawler\""))
        assertTrue("missing #apply-status element", html.contains("id=\"apply-status\""))
        assertTrue("missing #apply-log element", html.contains("id=\"apply-log\""))
        assertTrue("missing #maint-btns element", html.contains("id=\"maint-btns\""))
        assertTrue("missing #maint-status element", html.contains("id=\"maint-status\""))
        assertTrue("missing #maint-log element", html.contains("id=\"maint-log\""))
        assertTrue("missing #backup-status element", html.contains("id=\"backup-status\""))
        assertTrue("missing #db-backup-status element", html.contains("id=\"db-backup-status\""))
        assertTrue("missing #db-restore-status element", html.contains("id=\"db-restore-status\""))
        assertTrue("missing #est-threads element", html.contains("id=\"est-threads\""))
        assertTrue("missing #est-warn element", html.contains("id=\"est-warn\""))
        assertTrue("missing .warn-num CSS rule", html.contains(".warn-num"))
        assertTrue("missing warn-num className flip", html.contains("'warn-num'"))
        assertTrue("missing apply-log class on the <pre> logs", html.contains("apply-log"))
        assertTrue("crawler panel must use the shared card chrome", html.contains("class=\"card\""))
        assertTrue(
            "maintenance one-shot buttons must use the secondary pill",
            html.contains("class=\"btn secondary\"")
        )
    }

    /**
     * Stage 5: three tabs over one websocket. The five `setInterval` pollers are
     * gone, and the two actions removed in Stage 2 must not come back.
     */
    @Test fun crawlerPanel_threeTabsOneSocketNoPollers() {
        val html = crawlerPanel()
        assertEquals("exactly three tab buttons", 3, occurrences(html, "role=\"tab\""))
        assertEquals("exactly three tab panels", 3, occurrences(html, "role=\"tabpanel\""))
        for (id in listOf("tab-backup", "tab-settings", "tab-maintenance")) {
            assertTrue("missing panel #$id", html.contains("id=\"$id\""))
        }
        // The Backup fieldset (R2 credentials + daily DB dump) must stay inside the
        // Settings form: moving it would break crawlerSettingsFromForm's category
        // validation. Only the bootstrap lines are gone from the UI, not the config.
        val settingsPanel = html.substringAfter("id=\"tab-settings\"", "")
            .substringBefore("id=\"tab-maintenance\"")
        assertTrue(
            "the settings form must live in the Settings tab",
            settingsPanel.contains("action=\"/settings/crawler\"")
        )
        assertTrue(
            "the Backup fieldset must stay in the Settings tab",
            settingsPanel.contains("<legend>Backup <button")
        )
        assertFalse("the bootstrap checkbox must be gone", html.contains("bootstrapEnabled"))
        assertFalse("the bootstrap card must be gone", html.contains("Bootstrap cache download"))
        assertFalse("the bootstrap status div must be gone", html.contains("bootstrap-status"))
        assertEquals("no setInterval pollers may remain", 0, occurrences(html, "setInterval("))
        assertEquals(
            "exactly one settings socket",
            1,
            occurrences(html, "connectSocket('/settings/crawler/ws'")
        )
        assertFalse("the compact action must not reappear", html.contains("Compact database"))
        assertFalse("the category purge must not reappear", html.contains("Category filter update"))
        // The duplicate rebuild-bloom-filter button is gone; the query param only
        // renders a hint pointing at the Maintenance tab's own button.
        val hinted = server.buildCrawlerPanel(null, false, true)
        assertFalse("no second rebuild button", hinted.contains("Run --rebuild-bloom-filter"))
        assertTrue("the hint must point at the Maintenance tab", hinted.contains("href=\"#maintenance\""))
    }

    /**
     * Stage 5: the served markup must agree with the first websocket frame. The
     * old page hardcoded "Maintenance: idle" / "Apply status: IDLE" and lied for
     * as long as a one-shot or an apply was in flight (triage found both).
     */
    @Test fun crawlerPanel_initialStatusComesFromTheLiveStatus() {
        val live = MaintenanceStatus(
            action = MaintenanceAction.REBUILD_BLOOM_FILTER,
            running = true,
            startedAt = 1_000L,
            message = "running --rebuild-bloom-filter",
            phase = "scan",
            percent = 42,
            done = 42L,
            total = 100L,
            lines = listOf("[MPROG] action=rebuild-bloom-filter phase=scan done=42 total=100 pct=42"),
        )
        val html = newServer(
            crawlerMaintenanceStatus = { live },
            crawlerApplyStatus = {
                CrawlerApplyStatus(
                    phase = CrawlerApplyPhase.ROLLED_BACK, message = "boom", terminal = true)
            },
        ).buildCrawlerPanel(null, false, false)
        val status = html.substringAfter("id=\"maint-status\"", "").substringBefore("</div>")
        assertTrue("served status must carry the live action: $status", status.contains("rebuild-bloom-filter"))
        assertTrue("served status must carry the live phase: $status", status.contains("scan"))
        assertTrue("served status must carry the live percent: $status", status.contains("42%"))
        assertFalse("served status must not be the idle literal: $status", status.contains("Maintenance: idle"))
        assertTrue(
            "the percent bar must be rendered from the live status",
            html.contains("id=\"maint-progress-bar\" style=\"width:42%\"")
        )
        assertTrue(
            "the console must be server-rendered from the ring buffer",
            html.contains("[MPROG] action=rebuild-bloom-filter phase=scan done=42 total=100 pct=42")
        )
        val apply = html.substringAfter("id=\"apply-status\"", "").substringBefore("</div>")
        assertTrue("served apply status must be the live phase: $apply", apply.contains("ROLLED_BACK"))
        assertFalse("served apply status must not be the idle literal: $apply", apply.contains("Apply status: IDLE"))
    }

    /**
     * "Reset backup from zero": the DB backup card carries the reset form and
     * a submit handler that calls the `window.confirm()` builtin. The route itself
     * is covered by [ControlServerAuthRedirectTest]; here we only pin the markup
     * contract.
     */
    @Test fun dbBackupSection_rendersResetForm() {
        val html = crawlerPanel()
        assertTrue(
            "missing the reset form action",
            html.contains("action=\"/crawler/backup/db/reset\"")
        )
        assertTrue("missing the hidden confirm field", html.contains("name=\"confirm\""))
        assertTrue("missing the button label", html.contains("Reset backup from zero"))
        assertTrue("the hidden field must pre-set the confirm word", html.contains("value=\"RESET\""))
        // An inline handler's scope chain reaches the form's named controls before
        // `window`, and this form has an input named `confirm`: the builtin must stay
        // qualified, otherwise the handler throws `TypeError: confirm is not a function`
        // and the submit goes through unconfirmed.
        assertTrue(
            "the dialog must be the window-qualified builtin",
            html.contains("onsubmit=\"return window.confirm(")
        )
        // The guards below are scoped to the reset form's own markup: the panel's other
        // forms legitimately use the bare confirm() builtin (none of them has a control
        // named `confirm`), so only this form's handler can be shadowed by that name.
        val afterResetAction = html.substringAfter("action=\"/crawler/backup/db/reset\"", "")
        val resetFormHtml = afterResetAction.substringBefore("</form>", "")
        assertTrue("could not isolate the reset form markup", resetFormHtml.isNotEmpty())
        assertTrue(
            "the reset form must carry the qualified handler",
            resetFormHtml.contains("onsubmit=\"return window.confirm(")
        )
        assertFalse(
            "an unqualified confirm() resolves to the form's own input",
            resetFormHtml.contains("onsubmit=\"return confirm(")
        )
        assertFalse(
            "the inline handler must not reference a page-scoped function",
            resetFormHtml.contains("dbResetConfirm")
        )
        // The explanation must name the prefix the purge will delete, escaped.
        val prefixed = newServer(backup = BackupSettings(r2DbObjectPrefix = "db"))
            .buildCrawlerPanel(null, false, false)
        assertTrue(
            "the explanation must render the configured prefix",
            prefixed.contains("<code>db/</code>")
        )
    }

    /** A pending reset blocks every upload, so the card must say so. */
    @Test fun dbBackupSection_warnsWhenResetPending() {
        val pending = newServer(
            backup = BackupSettings(r2DbObjectPrefix = "db"),
            crawlerBackupDbStatus = { com.torrent.app.backup.DbBackupStatus(resetPending = true) },
        ).buildCrawlerPanel(null, false, false)
        assertTrue(
            "a pending reset must be announced",
            pending.contains("A backup reset is pending")
        )
        assertFalse(
            "no pending banner without the marker",
            crawlerPanel().contains("A backup reset is pending")
        )
    }

    /**
     * Stage 7: the terminal-frame reconcile is keyed on the apply `seq`, not on a
     * terminal edge. A page loaded while the apply status is *already* terminal
     * used to seed `lastApplyTerminal = true`, so the false→true transition never
     * happened for the next apply's terminal frame and the form was never
     * re-rendered from server config (tester-c6 run 2: no page fetch at the
     * seq-12 terminal; after a ROLLED_BACK such a page keeps showing the rejected
     * values).
     *
     * The served page JS is not JUnit-executable, so this pins the shape of the
     * script that is served: the named predicate keeps the decision greppable, so
     * a revert to an edge trigger cannot pass silently.
     */
    @Test fun crawlerPanel_reconcileIsKeyedOnApplySeq() {
        val html = crawlerPanel()
        assertTrue(
            "the page must track the last reconciled apply seq",
            html.contains("var lastReconciledApplySeq = ")
        )
        assertTrue(
            "the reconcile decision must be the named predicate",
            html.contains("function applyNeedsReconcile(lastReconciledSeq, a) {")
        )
        assertTrue(
            "the predicate must require a terminal frame with a newer seq",
            html.contains("return !!a.terminal && a.seq > lastReconciledSeq;")
        )
        assertTrue(
            "the reconcile call must be gated on the predicate",
            html.contains("if (applyNeedsReconcile(lastReconciledApplySeq, a)) {")
        )
        assertTrue(
            "the tracked seq must advance only when reconciling",
            html.contains("lastReconciledApplySeq = a.seq;")
        )
        // The edge trigger is exactly what skipped the reconcile; it must not return.
        assertFalse("the terminal edge flag must be gone", html.contains("lastApplyTerminal"))
        assertFalse(
            "the terminal edge condition must be gone",
            html.contains("a.terminal === true) reconcileForm()")
        )
        assertFalse("the reconcile must never reload the page", html.contains("location.reload"))
    }

    /**
     * Stage 7: the seed is the seq the page was rendered at, for a terminal status
     * (a page loaded mid-terminal must not treat that apply as unreconciled) and
     * for a non-terminal one (a fresh page is ready for the first terminal frame).
     */
    @Test fun crawlerPanel_reconcileSeedIsTheRenderedApplySeq() {
        val midTerminal = newServer(
            crawlerApplyStatus = {
                CrawlerApplyStatus(
                    phase = CrawlerApplyPhase.ROLLED_BACK, message = "boom",
                    seq = 7L, terminal = true)
            },
        ).buildCrawlerPanel(null, false, false)
        assertTrue(
            "a page rendered mid-terminal must seed the live seq: $midTerminal",
            midTerminal.contains("var lastReconciledApplySeq = 7;")
        )
        assertFalse(
            "the seed must not be a boolean",
            midTerminal.contains("var lastReconciledApplySeq = true")
        )
        val idle = newServer(
            crawlerApplyStatus = { CrawlerApplyStatus(seq = 0L, terminal = false) },
        ).buildCrawlerPanel(null, false, false)
        assertTrue(
            "an idle page must seed the live seq",
            idle.contains("var lastReconciledApplySeq = 0;")
        )
        assertFalse(
            "the seed must not be a boolean",
            idle.contains("var lastReconciledApplySeq = false")
        )
    }

    /**
     * Stage 2: the crawler page's Feed line is the dashboard's chip, not a bare
     * 10px dot. It must open in the amber "connecting…" state, use the
     * dashboard's `on`/`bad` vocabulary, and carry the chip override in its own
     * <style> block (the shared theme only styles the plain dot).
     */
    @Test fun crawlerPanel_feedChipMatchesDashboard() {
        val html = crawlerPanel()
        assertTrue(
            "the Feed line must be the dashboard's chip in its initial wait state",
            html.contains(
                "<p class=\"meta\">Feed: <span id=\"live\" class=\"live wait\">connecting…</span></p>")
        )
        assertTrue(
            "the crawler page must carry the chip override",
            html.contains("#live{width:auto;height:auto;")
        )
        for (rule in listOf("#live.on{", "#live.wait{", "#live.bad{")) {
            assertTrue("the crawler page must carry the chip override $rule", html.contains(rule))
        }
        assertTrue(
            "an open socket must use the dashboard's `on` state",
            html.contains("setLive('on', 'live')")
        )
        assertFalse(
            "the crawler page must not keep the unstyled `ok` state",
            html.contains("setLive('ok'")
        )
    }
}
