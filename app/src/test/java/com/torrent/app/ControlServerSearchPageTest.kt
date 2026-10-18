package com.torrent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string tests for the /search page shell (search-dashboard stage 3a).
 * The page is a single inline raw string, so these pin its static contract:
 * the frozen category taxonomy, the /search/ws feed reference, the spinner
 * and randomize controls, the TV/adult control rows, and the
 * no-external-assets rule. Stage 3b's interaction logic (infinite scroll,
 * result paging, the add-to-transmission button) is pinned by the
 * js_* tests below.
 */
class ControlServerSearchPageTest {

    private val page = ControlServer.buildSearchPage()

    @Test fun page_hasAllCategoryChips() {
        for (name in listOf(
            "All", "Movie", "TV", "Anime", "Music", "Audiobook",
            "Ebook", "Comic", "Software", "Game", "Image", "Other",
        )) {
            assertTrue("missing category chip $name", page.contains(name))
        }
        assertTrue(page.contains("id=\"category-grid\""))
    }

    @Test fun page_pointsAtTheSearchWebSocket() {
        assertTrue(page.contains("/search/ws"))
        assertTrue(page.contains("location.search"))
    }

    @Test fun page_hasSpinner() {
        assertTrue(page.contains("id=\"spinner\""))
    }

    @Test fun page_hasRandomizeControl() {
        assertTrue(page.contains("id=\"randomize-btn\""))
        assertTrue(page.contains("Randomize"))
    }

    @Test fun page_hasTvKindAndAdultControls() {
        assertTrue(page.contains("id=\"tv-kind-row\""))
        assertTrue(page.contains("id=\"adult-control\""))
        assertTrue(page.contains("class=\"seg-label\""))
        assertTrue(page.contains("Adult"))
    }

    @Test fun page_hasNoExternalAssets() {
        assertFalse(page.contains("<script src"))
        assertFalse(page.contains("<link "))
        assertFalse(page.contains("cdn."))
    }

    @Test fun js_wiresInfiniteScroll() {
        assertTrue(page.contains("IntersectionObserver"))
        assertTrue(page.contains("#sentinel") || page.contains("sentinel"))
    }

    @Test fun js_tracksPaging() {
        assertTrue(page.contains("has_more"))
    }

    @Test fun js_hasAddButton() {
        assertTrue(page.contains("/api/transmission/add"))
    }
    @Test fun page_hasNoProxyOriginParameter() {
        // Torrent-page stage 2: detail links are same-origin, so the page no
        // longer carries a crawler proxy origin (and no `proxyOrigin` const).
        assertFalse(page.contains("proxyOrigin"))
        assertTrue(page.contains("/torrent?hash="))
    }

    @Test fun js_linksDetailPagesSameOrigin() {
        assertTrue(page.contains("detail.href = '/torrent?hash=' + encodeURIComponent(r.info_hash || '')"))
        assertTrue(page.contains("detail.target = '_blank'"))
        assertTrue(page.contains("detail.rel = 'noopener'"))
    }

    @Test fun js_prefillsTheQueryFromTheUrl() {
        // /torrent's "Search DHT" button links to /search?q=<words>; the page
        // fills the box and runs the same search on the first socket open.
        assertTrue(page.contains(".get('q')"))
        assertTrue(page.contains("prefill"))
        assertTrue(page.contains("el('search-input').value = prefill"))
    }
}
