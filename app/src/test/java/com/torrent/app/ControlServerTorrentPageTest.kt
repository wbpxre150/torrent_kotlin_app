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
        // pills are rendered from the torrent name, textContent only, and only
        // for words present in the crawler's extracted media title (filtered_name).
        assertTrue(page.contains("tokenizeName"))
        assertTrue(page.contains("wordInFiltered"))
        assertTrue(page.contains("if (!wordInFiltered(words[i], filteredName)) continue;"))
        assertTrue(page.contains("pill.className = 'pill on'"))
        assertFalse(page.contains("'pill' + (wordInFiltered"))
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
        // The refresh button shows an inline SVG glyph only: its accessible name
        // comes from title/aria-label, and it shares the compact .refresh-btn size.
        assertTrue(page.contains("class=\"btn secondary refresh-btn\""))
        assertTrue(page.contains("title=\"Refresh peers\""))
        assertTrue(page.contains("aria-label=\"Refresh peers\""))
        assertTrue(page.contains("<svg"))
        assertFalse(page.contains("&#8635; Refresh peers"))
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

    @Test fun js_imdbOpensExternally() {
        // webui-ux stage 1: IMDb is the one destination that keeps leaving the
        // webview (so Android can offer the installed IMDb app).
        assertTrue(page.contains("a.target = '_blank'"))
        assertTrue(page.contains("a.rel = 'noopener noreferrer'"))
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

    @Test fun js_refreshKeepsTheAbortArmedThroughTheBodyRead() {
        // Stage 2: the 25 s abort timer must survive the response body read, so a
        // stalled r.json() still aborts and the button is re-enabled. It is disarmed
        // only by the terminal handlers (success and catch), never by the header .then.
        assertFalse(
            "the header .then must not disarm the abort before the body is read",
            page.contains("clearTimeout(timer); return r.json();"),
        )
        val clears = page.split("clearTimeout(timer)").size - 1
        assertTrue("expected the timer cleared in the success and catch terminals, found $clears",
            clears >= 2)
    }

    @Test fun buildTorrentPage_isPureAndDeterministic() {
        // No parameters (hash/token come from location.search), no clock, no
        // config, no network: two calls produce the identical shell.
        val first = ControlServer.buildTorrentPage()
        val second = ControlServer.buildTorrentPage()
        assertEquals(first, second)
        assertTrue(first.contains("new URLSearchParams(location.search).get('hash')"))
        // The shared header replaces the old history-based back link.
        assertTrue(first.contains("<a href=\"/stats\">Crawler stats</a>"))
        assertTrue(first.contains("<a href=\"/search\">Search</a>"))
        assertFalse(first.contains("id=\"back\""))
    }

    @Test fun page_hasSelectionHelpers() {
        // Stage 6: three helpers in the file-tools row plus the running count.
        for (id in listOf("sel-all-btn", "sel-none-btn", "sel-invert-btn", "sel-count")) {
            assertTrue("missing id $id", page.contains("id=\"$id\""))
        }
        for (label in listOf("Select all", "Select none", "Invert")) {
            assertTrue("missing label $label", page.contains(">$label</button>"))
        }
        // The four pinned action buttons keep their exact ids and labels.
        assertTrue(page.contains(
            "id=\"add-btn\" class=\"btn secondary\" type=\"button\">&#10133; Add to Transmission</button>"))
    }

    @Test fun page_rowsGetCheckboxes() {
        // Every row gets a checkbox built in the loadFiles() loop and is checked
        // unless its path was deselected (absence from `unchecked` means selected,
        // so the whole torrent stays the default).
        assertTrue(page.contains("var unchecked = {};"))
        assertTrue(page.contains("cb.type = 'checkbox';"))
        assertTrue(page.contains("cb.className = 'file-check';"))
        assertTrue(page.contains("cb.checked = !unchecked[fpath];"))
        assertTrue(page.contains("row.appendChild(cb);"))
        // The path stays a closure variable: no data-* attribute, no innerHTML.
        assertFalse(page.contains("data-path"))
        assertFalse(page.contains("innerHTML"))
    }

    @Test fun page_addUsesTheSelectionEndpointWhenFilesAreUnchecked() {
        assertTrue(page.contains("var un = Object.keys(unchecked);"))
        assertTrue(page.contains("/api/transmission/add-selected?hash="))
        assertTrue(page.contains("headers: { 'Content-Type': 'application/json' }"))
        assertTrue(page.contains("JSON.stringify({ unselected: un, timeoutMs: 600000 })"))
        // Nothing unchecked = the whole torrent: the old route and contract stay.
        assertTrue(page.contains(
            "fetch('/api/transmission/add?hash=' + encodeURIComponent(hash), { method: 'POST' })"))
    }

    @Test fun page_pollsSelectionStatus() {
        assertTrue(page.contains("/api/transmission/selection-status?hash="))
        // The five states the watcher reports, and the paused banner wording.
        assertTrue(page.contains("paused_metadata_timeout"))
        assertTrue(page.contains("paused_mismatch"))
        assertTrue(page.contains("paused_apply_failed"))
        assertTrue(page.contains("Waiting for metadata to apply the file selection"))
        assertTrue(page.contains("File selection applied"))
        assertTrue(page.contains("Metadata did not arrive in time "))
        assertTrue(page.contains("Selection could not be matched to torrent files "))
        assertTrue(page.contains("Could not apply the selection "))
        // Every paused/error state renders the shared red banner.
        assertTrue(page.contains("box.className = isErr ? 'banner red' : 'banner';"))
        // The subset toast and the bad_selection message.
        assertTrue(page.contains("selecting files"))
        assertTrue(page.contains("Invalid file selection"))
    }

    @Test fun page_hasNoDollarSign() {
        // The raw-string trap: a dollar sign inside TORRENT_JS/TORRENT_CSS is
        // Kotlin template interpolation and cannot be escaped in a raw string, so
        // the rendered page must never contain one (no JS template literals, no
        // jQuery). Nothing guards this property today.
        assertFalse("buildTorrentPage() must not contain a dollar sign", page.contains("$"))
    }
}
