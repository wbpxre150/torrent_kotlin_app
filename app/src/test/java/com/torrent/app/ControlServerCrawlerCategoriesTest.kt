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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * The crawler settings page's stage-4 category control: the twelve
 * `saveCategories` toggles, the seven `saveTvKinds` toggles revealed for
 * tv/anime, the "checked = saved / unchecked = dropped" help text, and the
 * maintenance buttons — which no longer include the CLI-only `compact` and
 * `category-filter-update` actions. Plain JUnit4 +
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
            selectionWatcher = inertSelectionWatcher(),
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
        assertFalse(
            "the purge button is gone, so the help text must not promise it",
            html.contains("Category filter update") ||
                html.contains("maintenance section below then deletes") ||
                html.contains("plus every adult-tagged"))
        assertFalse("the auto-compact was dropped, so the help text must not promise it",
            html.contains("compacts the database"))
        // The paragraph wraps in the source, so only a single-line fragment matches.
        assertTrue("the help text must ask for at least one category",
            html.contains("least one category."))
        assertTrue("the tv-kind row must say what it applies to",
            html.contains("TV kinds — applies to TV and anime torrents only"))
    }

    @Test fun panel_maintenanceHidesTheCliOnlyActions() {
        val html = panel(CrawlerSettings())
        // compact + category-filter-update keep their enum members and CLI flags,
        // but the phone neither offers nor routes them any more.
        assertFalse(
            "the purge button must be gone",
            html.contains("action=\"/crawler/maintenance/category-filter-update\""))
        assertFalse("the purge label must be gone", html.contains(">Category filter update</button>"))
        assertFalse(
            "the purge confirm() must be gone",
            html.contains("Delete all torrents in unchecked categories"))
        assertFalse("the compact button must be gone", html.contains(">Compact database</button>"))
        assertFalse("the compact tooltip must be gone", html.contains("title=\"--compact\""))
        // The five surviving actions keep one button each, with the flag as its tooltip.
        for (flag in listOf(
            "--check-database", "--recover-database", "--rebuild-bloom-filter",
            "--clear-failure-filter", "--movie-kind-bench"
        )) {
            assertTrue("missing a button for $flag", html.contains("title=\"$flag\""))
        }
        assertTrue(html.contains(">Check database</button>"))
        assertTrue(html.contains(">Recover database</button>"))
    }

    @Test fun panel_hasNoPornFilterLeftovers() {
        val html = panel(CrawlerSettings())
        assertFalse("the removed porn filter must not be referenced in the UI", html.contains("porn"))
        assertTrue("the ingest-time classifier toggle survives",
            html.contains("name=\"classifierEnabled\""))
        assertTrue("the adult fieldset survives", html.contains("<legend>Adult content handling</legend>"))
    }

    /**
     * Stage 4: every bracketed explanation on the crawler page is a ⓘ toggle —
     * 24 buttons, 24 paired explanation paragraphs that stay in the DOM while
     * collapsed, and none of the old parenthetical label text left behind.
     * (Stage 5 of the tunnel work added the Cloudflare Tunnel card's three:
     * the legend, the token and the hostname; Stage 8's "Clear stored token" hint
     * is a bare info text with no ⓘ button, so the two counts differ by one.)
     */
    @Test fun crawlerPanel_explanationsAreInfoIcons() {
        val html = panel(CrawlerSettings())
        assertEquals(24, occurrences(html, "class=\"info\""))
        assertEquals(24, occurrences(html, "aria-expanded=\"false\""))
        // Stage 8: the "Clear stored token" hint is a bare info text (the plan's
        // card markup gives it no ⓘ button), so the text count is one ahead.
        assertEquals(25, occurrences(html, "class=\"info-text\" id=\"info-"))
        assertTrue(
            "the toggle must be one delegated listener",
            html.contains("document.addEventListener('click'")
        )
        // spot pairs: a legend, a numeric range, and a credential hint
        assertTrue(html.contains(">Bloom filters <button"))
        assertTrue(html.contains("changing these invalidates the persisted filters"))
        assertTrue(html.contains("id=\"info-btn-tree-count\""))
        assertTrue(html.contains("id=\"info-tree-count\" hidden"))
        // the bracketed forms are gone
        assertFalse(html.contains("Tree count (1..64)"))
        assertFalse(html.contains("Bloom filters ("))
        assertFalse(html.contains("Nice level ("))
        // the non-bracketed legends are untouched
        assertTrue(html.contains("<legend>Categories to save</legend>"))
        assertTrue(html.contains("<legend>Adult content handling</legend>"))
    }
}
