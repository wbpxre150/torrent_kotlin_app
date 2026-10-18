package com.torrent.app

import android.content.Context
import android.content.SharedPreferences
import com.torrent.app.config.AppConfig
import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.CrawlerTaxonomy
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
 * The crawler settings page's stage-4 category control: the twelve
 * `saveCategories` toggles, the seven `saveTvKinds` toggles revealed for
 * tv/anime, the "checked = saved / unchecked = dropped" help text, and the
 * human-labelled `category-filter-update` maintenance button. Plain JUnit4 +
 * Mockito, driving [ControlServer.buildCrawlerPanel] through its injected
 * collaborator lambdas (same harness as [ControlServerPanelsThemeTest]).
 */
class ControlServerCrawlerCategoriesTest {

    private companion object {
        const val TOKEN = "unit-test-token"

        fun checkbox(name: String, value: String, checked: Boolean): String =
            "<input type=\"checkbox\" name=\"$name\" value=\"$value\"${if (checked) " checked" else ""}>"
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

    private fun panel(crawler: CrawlerSettings): String {
        val config = AppConfig(
            wizardCompleted = true,
            transmission = TransmissionSettings("u", "p", 51413, 0, 0, 0, 0),
            ftp = FtpSettings("kodi", "pw", 2121, false),
            crawler = crawler,
            backup = BackupSettings(),
            dataSubpath = "data",
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
        ).buildCrawlerPanel(null, false, false)
    }

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    @Test fun panel_rendersATogglePerTaxonomyName() {
        val html = panel(CrawlerSettings())
        assertTrue("missing the Categories to save fieldset", html.contains("<legend>Categories to save</legend>"))
        assertTrue(
            "expected one saveCategories toggle per taxonomy category",
            occurrences(html, "type=\"checkbox\" name=\"saveCategories\"") == CrawlerTaxonomy.CATEGORIES.size)
        assertTrue(
            "expected one saveTvKinds toggle per taxonomy tv kind",
            occurrences(html, "type=\"checkbox\" name=\"saveTvKinds\"") == CrawlerTaxonomy.TV_KINDS.size)
    }

    @Test fun panel_emptyStoredSelectionMeansAllSaved() {
        val html = panel(CrawlerSettings())
        for (c in CrawlerTaxonomy.CATEGORIES)
            assertTrue("category $c should render checked by default", html.contains(checkbox("saveCategories", c, true)))
        for (k in CrawlerTaxonomy.TV_KINDS)
            assertTrue("tv kind $k should render checked by default", html.contains(checkbox("saveTvKinds", k, true)))
        assertFalse("the tv-kind row is revealed when every category is saved", html.contains("id=\"save-tv-kind-row\" hidden"))
    }

    @Test fun panel_storedSelectionRendersOnlyThoseTogglesChecked() {
        val html = panel(
            CrawlerSettings(
                saveCategories = listOf("tv", "anime"),
                saveTvKinds = listOf("episode", "daily")))
        for (c in CrawlerTaxonomy.CATEGORIES) {
            val on = c == "tv" || c == "anime"
            assertTrue("category $c checked=$on", html.contains(checkbox("saveCategories", c, on)))
        }
        for (k in CrawlerTaxonomy.TV_KINDS) {
            val on = k == "episode" || k == "daily"
            assertTrue("tv kind $k checked=$on", html.contains(checkbox("saveTvKinds", k, on)))
        }
        assertFalse("tv/anime are saved, so the tv-kind row must be visible",
            html.contains("id=\"save-tv-kind-row\" hidden"))
    }

    @Test fun panel_tvKindRowIsHiddenWithoutTvOrAnime() {
        val html = panel(CrawlerSettings(saveCategories = listOf("movie", "music")))
        assertTrue("the tv-kind row must start hidden without tv/anime",
            html.contains("id=\"save-tv-kind-row\" hidden"))
        // The row is still rendered (and still posts) so a stored tv-kind
        // selection survives a form submit.
        for (k in CrawlerTaxonomy.TV_KINDS)
            assertTrue("tv kind $k toggle missing", html.contains(checkbox("saveTvKinds", k, true)))
        assertTrue("missing the client-side reveal hook", html.contains("syncTvKindRow"))
    }

    @Test fun panel_statesCheckedSavedAndUncheckedDroppedUnambiguously() {
        val html = panel(CrawlerSettings())
        assertTrue(html.contains("Checked categories are saved"))
        assertTrue(html.contains("unchecked categories are dropped"))
        assertTrue(
            "the help text must say the maintenance button deletes unchecked rows and compacts",
            html.contains("deletes the") && html.contains("compacts the database"))
        // The paragraph wraps in the source, so only a single-line fragment matches.
        assertTrue("the help text must ask for at least one category",
            html.contains("least one category."))
        assertTrue("the tv-kind row must say what it applies to",
            html.contains("TV kinds — applies to TV and anime torrents only"))
    }

    @Test fun panel_maintenanceNamesTheCategoryPurge() {
        val html = panel(CrawlerSettings())
        assertTrue(
            "the purge button must post to its route",
            html.contains("action=\"/crawler/maintenance/category-filter-update\""))
        assertTrue(
            "the purge button needs a human label",
            html.contains(">Category filter update</button>"))
        assertTrue(
            "the confirm() must say what is deleted",
            html.contains("Delete all torrents in unchecked categories from the database and compact it?"))
        assertTrue(
            "the section must point back at the selection",
            html.contains("you left unchecked in <em>Categories to save</em> above"))
        // The other actions keep one button each, with the flag as its tooltip.
        assertTrue(html.contains("title=\"--compact\""))
        assertTrue(html.contains(">Compact database</button>"))
    }

    @Test fun panel_hasNoPornFilterLeftovers() {
        val html = panel(CrawlerSettings())
        assertFalse("the removed porn filter must not be referenced in the UI", html.contains("porn"))
        assertTrue("the ingest-time classifier toggle survives",
            html.contains("name=\"classifierEnabled\""))
        assertTrue("the adult fieldset survives", html.contains("<legend>Adult content handling</legend>"))
    }
}
