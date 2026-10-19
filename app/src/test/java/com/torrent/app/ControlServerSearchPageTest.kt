package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.torrent.app.config.CrawlerTaxonomy

/**
 * Pure string tests for the /search page shell (search-dashboard stage 3a).
 * The page is a single inline raw string, so these pin its static contract:
 * the frozen category taxonomy (the chips follow the enabled crawler
 * categories), the /search/ws feed reference, the spinner
 * the frozen category taxonomy, the /search/ws feed reference, the spinner
 * and randomize controls, the TV/adult control rows, and the
 * no-external-assets rule. Stage 3b's interaction logic (infinite scroll,
 * result paging, the add-to-transmission button) is pinned by the
 * js_* tests below.
 */
class ControlServerSearchPageTest {

    /** The page as the /search route renders it with no category filter saved
     * (an empty `save_categories` means "save everything"). */
    private val page = ControlServer.buildSearchPage(CrawlerTaxonomy.CATEGORIES.toSet())

    @Test fun page_hasAllCategoryChips() {
        for (name in listOf(
            "All", "Movie", "TV", "Anime", "Music", "Audiobook",
            "Ebook", "Comic", "Software", "Game", "Image", "Other",
        )) {
            assertTrue("missing category chip $name", page.contains(name))
        }
        assertTrue(page.contains("id=\"category-grid\""))
    }

    // ── category chips follow the enabled crawler categories ────────────────

    private val chipRegex = Regex(
        "<button class=\"chip\" type=\"button\" data-category=\"([^\"]*)\" " +
            "aria-pressed=\"(true|false)\">([^<]+)</button>")

    /** [html]'s category chips as (data-category, aria-pressed, label). */
    private fun chipsIn(html: String): List<Triple<String, String, String>> =
        chipRegex.findAll(html).map { m ->
            Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }.toList()

    @Test fun page_emptyEnabledSetRendersTheWholeTaxonomy() {
        // An empty stored `save_categories` means "save everything" (the
        // CrawlerSettings convention), so the page keeps all 12 categories.
        val chips = chipsIn(ControlServer.buildSearchPage(emptySet()))
        assertEquals(listOf("") + CrawlerTaxonomy.CATEGORIES, chips.map { it.first })
        assertTrue("the unknown chip keeps its label",
            chips.any { it.first == "unknown" && it.third == "Unknown" })
    }

    @Test fun page_rendersOnlyEnabledCategoryChips() {
        val html = ControlServer.buildSearchPage(setOf("movie", "tv", "anime", "music"))
        val chips = chipsIn(html)
        assertEquals(listOf("", "movie", "tv", "anime", "music"), chips.map { it.first })
        assertEquals(listOf("All", "Movie", "TV", "Anime", "Music"), chips.map { it.third })
        for (disabled in listOf(
            "unknown", "audiobook", "ebook", "comic", "software", "game", "image", "other",
        )) {
            assertFalse("disabled category $disabled must not be rendered",
                html.contains("data-category=\"$disabled\""))
        }
    }

    @Test fun page_keepsCanonicalCategoryOrder() {
        // A scrambled stored selection still renders in taxonomy order.
        val chips = chipsIn(ControlServer.buildSearchPage(setOf("game", "music", "unknown")))
        assertEquals(listOf("", "unknown", "music", "game"), chips.map { it.first })
    }

    @Test fun page_allChipComesFirstAndIsSelected() {
        val chips = chipsIn(ControlServer.buildSearchPage(setOf("tv")))
        assertEquals(Triple("", "true", "All"), chips.first())
        assertEquals(2, chips.size)
        assertEquals(Triple("tv", "false", "TV"), chips[1])
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

    // ── the adult control follows the crawler's adult_mode ──────────────────

    /** `adult_mode=drop` discards adult-tagged rows at ingest, so a per-query
     * adult filter can only be a no-op: the page must not offer it. The chips
     * are gone from the markup and the JS wiring is gated off (it would
     * otherwise dereference a missing `#adult-control` and kill the page). */
    @Test fun page_dropModeHidesTheAdultSelection() {
        for (mode in listOf("drop", "DROP", " Drop ")) {
            val drop = ControlServer.buildSearchPage(
                CrawlerTaxonomy.CATEGORIES.toSet(), adultMode = mode)
            assertFalse("adult_mode=$mode must not render the adult row",
                drop.contains("id=\"adult-control\""))
            // `data-adult` still appears once, inside the (never-executed) gated
            // handler, so only the rendered attribute form is banned.
            assertFalse("adult_mode=$mode must not render adult chips",
                drop.contains("data-adult=\""))
            assertFalse("adult_mode=$mode must drop the row's a11y label",
                drop.contains("aria-label=\"Adult results\""))
            assertFalse("adult_mode=$mode must drop the seg-label row",
                drop.contains("class=\"seg-label\""))
            assertTrue("adult_mode=$mode must disable the adult wiring flag",
                drop.contains("var ADULT_SELECTION = false;"))
            val guard = drop.indexOf("if (ADULT_SELECTION) {")
            assertTrue(
                "adult_mode=$mode must keep the adult wiring inside the guard",
                guard >= 0 && drop.indexOf("el('adult-control')") > guard,
            )
            // The rest of the page is untouched.
            assertTrue(drop.contains("id=\"tv-kind-row\""))
            assertTrue(drop.contains("id=\"category-grid\""))
            assertTrue(drop.contains("/search/ws"))
        }
    }

    @Test fun page_tagModeKeepsTheAdultSelection() {
        val tag = ControlServer.buildSearchPage(
            CrawlerTaxonomy.CATEGORIES.toSet(), adultMode = "tag")
        for (html in listOf(page, tag)) {
            assertTrue("tag mode must keep the adult row",
                html.contains("id=\"adult-control\""))
            assertTrue("tag mode must keep all three adult chips",
                listOf("hide", "show", "only").all {
                    html.contains("data-adult=\"$it\"")
                })
            assertTrue("tag mode must keep the row's a11y label",
                html.contains("aria-label=\"Adult results\""))
            assertTrue("tag mode must enable the adult wiring flag",
                html.contains("var ADULT_SELECTION = true;"))
            assertTrue("tag mode must keep the guarded wiring",
                html.indexOf("if (ADULT_SELECTION) {") >= 0 &&
                    html.indexOf("el('adult-control')") >
                        html.indexOf("if (ADULT_SELECTION) {"))
        }
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

    @Test fun js_distinguishesBusyCrawlerFromDownCrawler() {
        // A `busy` reply means the server checked the daemon state: the crawler
        // process is RUNNING while the loopback fetch outlasted the search read
        // timeout (big-DB search) — a different banner from the `unavailable`
        // (starting or stopped) one, which stays for a down/starting crawler.
        assertTrue(page.contains("err === 'busy'"))
        assertTrue("the busy banner must mention the timed-out search",
            page.contains("the search timed out"))
        assertTrue("the unavailable banner must keep its starting-or-stopped wording",
            page.contains("Crawler is starting or stopped"))
    }
    @Test fun page_hasNoProxyOriginParameter() {
        // Torrent-page stage 2: detail links are same-origin, so the page no
        // longer carries a crawler proxy origin (and no `proxyOrigin` const).
        assertFalse(page.contains("proxyOrigin"))
        assertTrue(page.contains("/torrent?hash="))
    }

    @Test fun js_linksDetailPagesSameOrigin() {
        // webui-ux stage 1: the `…` link navigates in the same tab (no target/rel).
        assertTrue(page.contains("detail.href = '/torrent?hash=' + encodeURIComponent(r.info_hash || '')"))
        assertFalse(page.contains("detail.target"))
        assertFalse(page.contains("detail.rel"))
    }

    @Test fun js_prefillsTheQueryFromTheUrl() {
        // /torrent's "Search DHT" button links to /search?q=<words>; the page
        // fills the box and runs the same search on the first socket open.
        assertTrue(page.contains(".get('q')"))
        assertTrue(page.contains("prefill"))
        assertTrue(page.contains("el('search-input').value = prefill"))
    }

    @Test fun js_chipControlsDoNotAutoLoad() {
        // webui-ux stage 3: the chip controls only update in-page selection
        // state; results load only on Search (button/Enter) or Randomize.
        val sends = page.split("sendBrowse();").size - 1
        assertTrue("expected exactly one sendBrowse(); call, found $sends", sends == 1)
        assertTrue(
            "sendBrowse(); must sit inside doSearch()",
            page.indexOf("function doSearch()") < page.indexOf("sendBrowse();"),
        )
        assertTrue(page.contains("sel.category"))
        assertTrue(page.contains("sel.tv_kind"))
        assertTrue(page.contains("sel.adult"))
        assertFalse(page.contains("Pick a category to browse"))
        assertTrue(page.contains("Pick your filters, then Search or Randomize"))
    }

    @Test fun js_sortControlRemoved() {
        // webui-ux stage 6: searches rank by relevance, so the sort dropdown and
        // its JS wiring are gone and the status line says so for search results.
        assertFalse(page.contains("id=\"sort\""))
        assertFalse(page.contains("sortLabel"))
        assertFalse(page.contains("sorted by"))
        assertFalse(page.contains(">Newest<"))
        assertFalse(page.contains(">Largest<"))
        assertFalse(page.contains("sel.sort"))
        assertTrue(page.contains("best matches first"))
    }

    @Test fun js_chipFocusMode_helpersExistAndAreWired() {
        // webui-ux stage 4: pressing a chip focuses it (its siblings disappear) and
        // re-pressing it reverts the row to its default chip.
        assertTrue(page.contains("function chipFocus("))
        assertTrue(page.contains("function revealRow("))
        assertTrue(
            "category handler must use the focus helper",
            handlerBody("el('category-grid').onclick").contains("chipFocus("),
        )
        assertTrue(
            "tv-kind handler must use the focus helper",
            handlerBody("el('tv-kind-row').onclick").contains("chipFocus("),
        )
        assertFalse(
            "the Adult control keeps its plain select-only behaviour",
            handlerBody("el('adult-control').onclick").contains("chipFocus("),
        )
    }

    @Test fun js_chipFocusMode_hidesSiblingsWithTheHiddenClass() {
        // Visibility is toggled with the existing `.hidden` class only: no chip is
        // removed from the DOM, so selectOnly()/aria-pressed keep working.
        assertTrue(page.contains("classList.add('hidden')"))
        assertTrue(page.contains("classList.remove('hidden')"))
    }

    @Test fun js_chipFocusMode_revertResetsToRowDefaults() {
        // Re-pressing the focused chip restores the full row and the default
        // selection: All for the category grid, None for the TV-kind row.
        assertTrue(page.contains("sel.category = null"))
        assertTrue(page.contains("sel.tv_kind = null"))
        assertTrue(page.contains(".chip[data-category=\"\"]"))
        assertTrue(page.contains(".chip[data-tv-kind=\"none\"]"))
    }

    @Test fun js_chipFocusMode_handlersStayNetworkFree() {
        for (marker in listOf("el('category-grid').onclick", "el('tv-kind-row').onclick")) {
            val body = handlerBody(marker)
            assertFalse("$marker must not load results", body.contains("send("))
            assertFalse("$marker must not call sendBrowse", body.contains("sendBrowse"))
        }
    }

    /** The body of the inline handler starting at [marker], up to its closing `};`. */
    private fun handlerBody(marker: String): String {
        val start = page.indexOf(marker)
        assertTrue("missing handler $marker", start >= 0)
        val end = page.indexOf("};", start)
        assertTrue("unterminated handler $marker", end > start)
        return page.substring(start, end)
    }
}
