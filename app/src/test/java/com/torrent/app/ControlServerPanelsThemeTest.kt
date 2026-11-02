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
        )
    }

    private val server = newServer()

    private fun transmissionPanel() = server.buildTransmissionPanel(null, false)
    private fun ftpPanel() = server.buildFtpPanel(null, false)
    private fun storagePanel() = server.buildStoragePanel()
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
}
