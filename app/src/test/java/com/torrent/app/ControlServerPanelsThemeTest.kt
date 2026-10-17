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

    private fun newServer(): ControlServer {
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
        assertTrue("missing #apply-status hook", html.contains("#apply-status"))
        assertTrue("missing #maint-btns hook", html.contains("#maint-btns"))
        assertTrue("missing #maint-status hook", html.contains("#maint-status"))
        assertTrue("missing warn-num class", html.contains("warn-num"))
        assertTrue("missing apply-log class", html.contains("apply-log"))
        assertTrue("crawler panel must use the shared card chrome", html.contains("class=\"card\""))
        assertTrue(
            "maintenance one-shot buttons must use the secondary pill",
            html.contains("class=\"btn secondary\"")
        )
    }
}