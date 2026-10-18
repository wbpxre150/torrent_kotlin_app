package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string tests for the /torrent detail shell (torrent-page stage 2).
 *
 * The page is one inline raw string with no server-side data: the info hash and
 * the optional token are read client-side from `location.search`, exactly like
 * the token on /search. These tests pin the static contract — the pill
 * container, the four action buttons, the paginated file list, and that the JS
 * talks only to this server's own :8090 routes (IMDb excepted). The shell must
 * stay a pure string builder: no fetcher, no clock, no config.
 */
class ControlServerTorrentPageTest {

    private val page = ControlServer.buildTorrentPage()

    @Test fun page_hasKeywordPillContainerAndPreview() {
        assertTrue(page.contains("id=\"pills\""))
        assertTrue(page.contains("class=\"pills\""))
        assertTrue(page.contains("id=\"query-preview\""))
        // pills are rendered from the torrent name, textContent only.
        assertTrue(page.contains("tokenizeName"))
        assertTrue(page.contains("wordInFiltered"))
    }

    @Test fun page_hasTheFourActionButtons() {
        for (label in listOf(
            "Search IMDb", "Search DHT", "Refresh peers", "Add to Transmission",
        )) {
            assertTrue("missing button label $label", page.contains(label))
        }
        for (id in listOf("imdb-btn", "dht-btn", "refresh-btn", "add-btn")) {
            assertTrue("missing button id $id", page.contains(id))
        }
    }

    @Test fun page_hasFileListSentinelAndFilter() {
        assertTrue(page.contains("id=\"file-list\""))
        assertTrue(page.contains("id=\"sentinel\""))
        assertTrue(page.contains("id=\"file-filter\""))
        assertTrue(page.contains("IntersectionObserver"))
        // 250 ms debounce on the filter box.
        assertTrue(page.contains("}, 250);"))
        assertTrue(page.contains("has_more"))
    }

    @Test fun js_callsTheSameOriginRoutes() {
        assertTrue(page.contains("/api/crawler/torrent/info"))
        assertTrue(page.contains("/api/crawler/torrent/files"))
        assertTrue(page.contains("/api/crawler/refresh"))
        assertTrue(page.contains("/api/transmission/add"))
        assertTrue(page.contains("/search?q="))
    }

    @Test fun js_hasNoAbsoluteCrawlerOrProxyUrls() {
        // IMDb is the only external destination on the page.
        assertTrue(page.contains("https://www.imdb.com/find/?q="))
        val withoutImdb = page.replace("https://www.imdb.com/find/?q=", "")
        assertFalse(withoutImdb.contains("http://"))
        assertFalse(withoutImdb.contains("https://"))
        assertFalse(page.contains("127.0.0.1"))
        assertFalse(page.contains("<script src"))
        assertFalse(page.contains("<link "))
    }

    @Test fun js_formatsSizesLikeTheCrawler() {
        // %.2f with 1024 steps — tp_format_size parity.
        assertTrue(page.contains("toFixed(2)"))
        assertTrue(page.contains("'B', 'KB', 'MB', 'GB', 'TB'"))
        // the insufficient_space message carries both byte counts.
        assertTrue(page.contains("fmtGiB(d.sizeBytes)"))
        assertTrue(page.contains("fmtGiB(d.freeBytes)"))
    }

    @Test fun js_mapsEveryDetailState() {
        assertTrue(page.contains("bad_request"))
        assertTrue(page.contains("'not_found'"))
        assertTrue(page.contains("unavailable"))
        assertTrue(page.contains("'Refresh failed'"))
        assertTrue(page.contains("'Failed to load files'"))
    }

    @Test fun buildTorrentPage_isPureAndDeterministic() {
        // No parameters (hash/token come from location.search), no clock, no
        // config, no network: two calls produce the identical shell.
        val first = ControlServer.buildTorrentPage()
        val second = ControlServer.buildTorrentPage()
        assertEquals(first, second)
        assertTrue(first.contains("new URLSearchParams(location.search).get('hash')"))
        // Back link degrades to / when there is no history.
        assertTrue(first.contains("history.length > 1"))
    }
}
