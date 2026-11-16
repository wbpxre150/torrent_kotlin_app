package com.torrent.app

import android.content.Context
import android.content.SharedPreferences
import com.torrent.app.ControlServer.Companion.CloudflaredFormResult
import com.torrent.app.config.AppConfig
import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CloudflaredSettings
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
 * Stage 5 of the Cloudflare-tunnel work: the operator-facing card on the crawler
 * settings page, plus the pure form parser behind its POST. Plain JUnit4 +
 * Mockito, driving [ControlServer.buildCrawlerPanel] through its injected
 * collaborator lambdas (same harness as [ControlServerCrawlerCategoriesTest]).
 *
 * Stage 6 moved the card into its OWN `<form id="cloudflared-form">` posting to
 * `POST /settings/cloudflared`, so a tunnel save never runs the crawler apply
 * (HTML forbids nested forms, so the card sits after the crawler form).
 *
 * The token is a secret: the card must never interpolate the stored value into
 * the HTML, so every render assertion here also re-checks that.
 */
class ControlServerCloudflaredCardTest {

    private companion object {
        const val TOKEN = "unit-test-token"
        const val STORED_TUNNEL_TOKEN = "eyJhIjoiYWJjZGVmIn0-stored-tunnel-token"
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

    private fun panel(cloudflared: CloudflaredSettings): String {
        val config = AppConfig(
            wizardCompleted = true,
            transmission = TransmissionSettings("u", "p", 51413, 0, 0, 0, 0),
            ftp = FtpSettings("kodi", "pw", 2121, false),
            crawler = CrawlerSettings(),
            backup = BackupSettings(),
            dataSubpath = "data",
            cloudflared = cloudflared,
        )
        val notWired = com.torrent.app.config.ApplyResult(false, "test", false)
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
            saveCloudflared = { notWired },
            cloudflaredStatus = { CloudflaredStatus(enabled = cloudflared.enabled) },
        ).buildCrawlerPanel(null, false, false)
    }

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    /**
     * Stage 6: the card has its OWN form and its OWN route. HTML forbids nested
     * forms, so the card is emitted after the crawler form's `</form>` — a tunnel
     * save must never be able to run the crawler apply.
     */
    @Test fun panel_rendersTheTunnelCardInItsOwnForm() {
        val html = panel(CloudflaredSettings())
        val crawlerFormOpen = html.indexOf("<form id=\"settings-form\"")
        assertTrue("the crawler form must still render", crawlerFormOpen > 0)
        val crawlerFormClose = html.indexOf("</form>", crawlerFormOpen)
        val formOpen = html.indexOf(
            "<form id=\"cloudflared-form\" method=\"post\" action=\"/settings/cloudflared\">")
        assertTrue("the card must have its own form posting to its own route", formOpen > 0)
        val formClose = html.indexOf("</form>", formOpen)
        assertTrue("the tunnel form must be closed", formClose > formOpen)
        assertTrue("the tunnel form must follow the crawler form (no nested forms)",
            formOpen > crawlerFormClose)
        val card = html.substring(formOpen, formClose)
        assertTrue("the card needs its fieldset legend",
            card.contains("<fieldset class=\"card\"><legend>Cloudflare Tunnel "))
        assertTrue("the card must say it is app-side only",
            card.contains("app-side only; not written to the crawler config"))
        assertTrue("the enable toggle is the form's checkbox convention",
            card.contains("<input type=\"checkbox\" name=\"cloudflaredEnabled\" >"))
        assertTrue("the tunnel form needs its own submit button",
            card.contains("<button type=\"submit\" class=\"btn\">Save tunnel</button>"))
        val crawlerForm = html.substring(crawlerFormOpen, crawlerFormClose)
        assertFalse("no tunnel field may ride the crawler form",
            crawlerForm.contains("cloudflared"))
        assertTrue("the Access card must still render",
            crawlerForm.contains("<legend>Access</legend>"))
    }

    @Test fun panel_tokenRowStartsHiddenWhileDisabled() {
        val html = panel(CloudflaredSettings(enabled = false))
        assertTrue("the token/hostname row must start hidden while disabled",
            html.contains("id=\"cf-token-row\" hidden"))
        // Still rendered (and still posting) so a stored value survives a save.
        assertTrue(html.contains("name=\"cloudflaredToken\""))
        assertTrue(html.contains("name=\"cloudflaredHostname\""))
        // The CSS rule that makes the attribute actually hide the row.
        assertTrue(html.contains("#cf-token-row[hidden]{display:none}"))
    }

    @Test fun panel_tokenRowIsVisibleWhenEnabled() {
        val html = panel(CloudflaredSettings(enabled = true))
        assertFalse("an enabled tunnel must not hide the token row",
            html.contains("id=\"cf-token-row\" hidden"))
        assertTrue(html.contains("id=\"cf-token-row\">"))
        assertTrue("the enable toggle renders checked",
            html.contains("<input type=\"checkbox\" name=\"cloudflaredEnabled\" checked>"))
    }

    @Test fun panel_neverInterpolatesTheStoredToken() {
        val html = panel(
            CloudflaredSettings(
                enabled = true,
                tunnelToken = STORED_TUNNEL_TOKEN,
                publicHostname = "abc.example.com",
            ))
        assertFalse("the stored tunnel token must never reach the page",
            html.contains(STORED_TUNNEL_TOKEN))
        assertTrue("the token field is a masked input with an \"unchanged\" hint",
            html.contains("<input type=\"password\" name=\"cloudflaredToken\" placeholder=\"unchanged\">"))
        assertTrue("the token row must explain that blank keeps the stored value",
            html.contains("leave blank to keep"))
    }

    @Test fun panel_rendersTheStoredHostname() {
        val html = panel(CloudflaredSettings(enabled = true, publicHostname = "abc.example.com"))
        assertTrue(html.contains("<input name=\"cloudflaredHostname\" value=\"abc.example.com\">"))
        assertTrue("the hostname row must say detection is authoritative",
            html.contains("auto-detected when connected"))
    }

    @Test fun panel_bindsAndRebindsTheTunnelToggle() {
        val html = panel(CloudflaredSettings())
        assertTrue("the reveal handler must be present", html.contains("function syncCfTokenRow()"))
        // Once on load, once inside reconcileForm() — the form swap would
        // otherwise leave a dead listener behind.
        assertEquals("bindCloudflared must run on load and after a form reconcile",
            2, occurrences(html, "bindCloudflared();"))
    }

    // ── the pure parser behind the POST ──────────────────────────────────────

    @Test fun cloudflaredSettingsFromForm_blankTokenKeepsStoredValue() {
        val current = CloudflaredSettings(
            enabled = false, tunnelToken = STORED_TUNNEL_TOKEN, publicHostname = "abc.example.com")
        val s = ControlServer.cloudflaredSettingsFromForm(
            mapOf("cloudflaredToken" to "  ", "cloudflaredHostname" to "abc.example.com"), current)
        assertTrue(s is CloudflaredFormResult.Ok)
        val settings = (s as CloudflaredFormResult.Ok).settings
        assertEquals("a blank field keeps the stored secret", STORED_TUNNEL_TOKEN, settings.tunnelToken)
        assertFalse("an absent checkbox means disabled", settings.enabled)
        assertEquals("abc.example.com", settings.publicHostname)
    }

    @Test fun cloudflaredSettingsFromForm_enabledWithoutTokenIsRejected() {
        val current = CloudflaredSettings()
        val s = ControlServer.cloudflaredSettingsFromForm(
            mapOf("cloudflaredEnabled" to "", "cloudflaredToken" to ""), current)
        assertTrue("enabling with no token must be rejected", s is CloudflaredFormResult.Bad)
        assertEquals(
            "Tunnel token is required when the Cloudflare tunnel is enabled",
            (s as CloudflaredFormResult.Bad).message)
    }

    @Test fun cloudflaredSettingsFromForm_enabledWithStoredTokenIsAccepted() {
        val current = CloudflaredSettings(tunnelToken = STORED_TUNNEL_TOKEN)
        val s = ControlServer.cloudflaredSettingsFromForm(mapOf("cloudflaredEnabled" to ""), current)
        assertTrue(s is CloudflaredFormResult.Ok)
        val settings = (s as CloudflaredFormResult.Ok).settings
        assertTrue("checkbox presence = enabled", settings.enabled)
        assertEquals(STORED_TUNNEL_TOKEN, settings.tunnelToken)
    }

    @Test fun cloudflaredSettingsFromForm_trimsAndStripsPastedScheme() {
        val current = CloudflaredSettings(tunnelToken = STORED_TUNNEL_TOKEN)
        val s = ControlServer.cloudflaredSettingsFromForm(
            mapOf(
                "cloudflaredEnabled" to "",
                "cloudflaredToken" to "  new-token  ",
                "cloudflaredHostname" to "  https://Pasted.example.com  ",
            ), current)
        val settings = (s as CloudflaredFormResult.Ok).settings
        assertEquals("new-token", settings.tunnelToken)
        assertEquals("a pasted scheme must not reach the store", "Pasted.example.com", settings.publicHostname)
        val http = ControlServer.cloudflaredSettingsFromForm(
            mapOf("cloudflaredHostname" to "http://plain.example.com"), current)
        assertEquals(
            "plain.example.com",
            (http as CloudflaredFormResult.Ok).settings.publicHostname)
    }

    @Test fun cloudflaredSettingsFromForm_blankHostnameClearsAndMissingFallsBack() {
        val current = CloudflaredSettings(tunnelToken = "t", publicHostname = "abc.example.com")
        val cleared = ControlServer.cloudflaredSettingsFromForm(
            mapOf("cloudflaredHostname" to ""), current)
        assertEquals(
            "an explicitly blank field clears the hostname",
            "",
            (cleared as CloudflaredFormResult.Ok).settings.publicHostname)
        val kept = ControlServer.cloudflaredSettingsFromForm(emptyMap(), current)
        assertEquals(
            "an absent field keeps the stored hostname",
            "abc.example.com",
            (kept as CloudflaredFormResult.Ok).settings.publicHostname)
    }
}
