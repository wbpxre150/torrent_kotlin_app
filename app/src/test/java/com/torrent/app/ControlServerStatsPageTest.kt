package com.torrent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string tests for the /stats page (webui stage 2). The page is a
 * single inline raw string, so these pin its behavioural contract: the
 * live websocket feed path, the reconnect/backoff logic, the top-level
 * summary strip, the "not running" fallback, the expand/collapse state
 * persistence, and the
 * no-external-assets rule.
 */
class ControlServerStatsPageTest {

    private val page = ControlServer.buildStatsPage()

    @Test fun page_hasHeadingAndTitle() {
        assertTrue(page.contains("<title>Torrent App — Crawler Stats</title>"))
        assertTrue(page.contains("<h1>Crawler Stats</h1>"))
    }

    @Test fun page_usesSharedWebuiTheme() {
        assertTrue(page.contains("--blue:#1a73e8"))
    }

    /**
     * Stage 7: the stats page rides the shared /search theme — accents come
     * from the WEBUI_CSS custom properties rather than hard-coded greys.
     */
    @Test fun page_usesSharedAccentColors() {
        assertTrue(page.contains("var(--blue)"))
        assertTrue(page.contains("var(--muted)"))
    }

    /**
     * The legacy grey #666 (old .strip b / .head .tri / .row .k) is gone, and
     * long values wrap instead of overflowing their row.
     */
    @Test fun page_hasNoLegacyGreyOrOverflow() {
        assertFalse(page.contains("#666"))
        assertTrue(page.contains("overflow-wrap:anywhere"))
    }

    @Test fun page_pointsAtTheStatsWebSocket() {
        assertTrue(page.contains("/stats/ws"))
        assertTrue(page.contains("location.host"))
    }

    @Test fun page_reconnectsWithCappedBackoff() {
        assertTrue(page.contains("reconnecting"))
        assertTrue(page.contains("MAX_BACKOFF = 30000"))
        assertTrue(page.contains("backoff * 2"))
        assertTrue(page.contains("setTimeout(connect, wait)"))
    }

    @Test fun page_summaryStripCoversTopLevelScalars() {
        for (id in listOf("s-version", "s-uptime", "s-torrents", "s-files", "s-last-hour", "s-timestamp")) {
            assertTrue("missing summary strip cell $id", page.contains("id=\"$id\""))
        }
    }

    @Test fun page_handlesNotRunningFallback() {
        assertTrue(page.contains("data.running === false"))
        assertTrue(page.contains("Crawler not running"))
        assertTrue(page.contains("STARTING"))
    }

    @Test fun page_persistsExpandCollapseStateAcrossRenders() {
        assertTrue(page.contains("openOverrides"))
        assertTrue(page.contains("data-path"))
        assertTrue(page.contains("defaultOpen"))
    }

    @Test fun page_titlesTreeAndPartitionItems() {
        assertTrue(page.contains("'tree '"))
        assertTrue(page.contains("tree_id"))
        assertTrue(page.contains("item.phase"))
        assertTrue(page.contains("'partition '"))
    }

    @Test fun page_hasNoExternalAssets() {
        assertFalse(page.contains("<script src"))
        assertFalse(page.contains("<link "))
        assertFalse(page.contains("cdn."))
    }
}
