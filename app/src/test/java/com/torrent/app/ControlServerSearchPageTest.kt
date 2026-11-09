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

    // ── back-navigation restore ──────────────────────────────────────────────

    @Test fun js_restoresTheLastResultsFromSessionStorage() {
        // Search → torrent detail → Back is a full page load, so the last result
        // set is mirrored into sessionStorage (per-tab: survives Back/Forward,
        // never persisted across tabs or restarts) and repainted on load instead
        // of the page coming back empty.
        assertTrue(page.contains("sessionStorage"))
        assertTrue(page.contains("torrent.search.v1"))
        assertTrue(page.contains("function restore()"))
        assertTrue(page.contains("resultsAccum"))
    }

    @Test fun js_restoreOnlyRepaintsAndNeverRefiresTheQuery() {
        // restore() re-renders the saved cards through buildCard(); it must not
        // touch the feed. The page carries exactly one sendBrowse(); call: doSearch()'s
        // (filter chips only clear stale results, they never fetch).
        val start = page.indexOf("function restore()")
        assertTrue("missing restore()", start >= 0)
        val end = page.indexOf("function applySel(", start)
        assertTrue("unterminated restore()", end > start)
        val body = page.substring(start, end)
        assertFalse("restore must not send a query", body.contains("send("))
        assertFalse("restore must not call sendBrowse", body.contains("sendBrowse"))
        assertTrue("restore must repaint through buildCard", body.contains("buildCard("))
        assertEquals(
            "the page keeps exactly one sendBrowse(); call per refresh path",
            1, page.split("sendBrowse();").size - 1,
        )
    }

    @Test fun js_restoreReappliesFocusModeHiding() {
        // Focus mode (pressed chip hides its siblings) must survive Back: save()
        // mirrors each row's focus state into the payload and applySel() re-hides
        // the siblings of the pressed chip instead of revealing the whole row.
        assertTrue("save must mirror the focus state", page.contains("focus: { category:"))
        assertTrue("restore must hand the saved focus to applySel", page.contains("applySel(saved.focus"))
        val start = page.indexOf("function applySel(focus) {")
        assertTrue("missing applySel(focus)", start >= 0)
        val end = page.indexOf("if (ADULT_SELECTION) {", start)
        assertTrue("unterminated applySel()", end > start)
        val body = page.substring(start, end)
        assertTrue("applySel must re-hide focused category chips", body.contains("focusRow(grid)"))
        assertTrue("applySel must reveal unfocused rows", body.contains("revealRow(grid)"))
        assertTrue("applySel must re-hide focused tv-kind chips", body.contains("focusRow(tvRow)"))
    }

    @Test fun js_chipControlsDoNotAutoLoad() {
        // webui-ux stage 3: the category/tv-kind chip controls only update in-page
        // selection state; results load only on Search (button/Enter) or Randomize.
        // The multi-select movie-kind row follows the same rule (a toggle only
        // clears the stale result set), so the page carries a single
        // sendBrowse(); call — doSearch()'s.
        val sends = page.split("sendBrowse();").size - 1
        assertTrue("expected exactly one sendBrowse(); call, found $sends", sends == 1)
        assertTrue(
            "doSearch()'s sendBrowse(); must sit inside doSearch()",
            page.indexOf("function doSearch()") < page.lastIndexOf("sendBrowse();"),
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

    // ── movie-kind facet: chips, badges, persistence ───────────────────────

    /** [html]'s movie-kind chips as (data-movie-kind, aria-pressed, label). */
    private fun movieChipsIn(html: String): List<Triple<String, String, String>> =
        Regex("<button class=\"chip\" type=\"button\" data-movie-kind=\"([^\"]*)\" " +
            "aria-pressed=\"(true|false)\">([^<]+)</button>")
            .findAll(html).map { m ->
                Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            }.toList()

    @Test fun page_hasMovieKindChipRow() {
        // The row is generated from CrawlerTaxonomy.MOVIE_KINDS (never a third
        // hand-written genre list), starts hidden (it is movie-only) and leads with
        // a `none` chip matching rows without a verdict.
        assertTrue(page.contains("id=\"movie-kind-row\" hidden"))
        assertTrue(page.contains("data-movie-kind=\"none\""))
        for (name in listOf("scifi", "western")) {
            assertTrue("missing the $name genre chip", page.contains("data-movie-kind=\"$name\""))
        }
        val chips = movieChipsIn(page)
        assertEquals(listOf("none") + CrawlerTaxonomy.MOVIE_KINDS, chips.map { it.first })
        assertTrue("every movie chip starts unpressed", chips.all { it.second == "false" })
        assertEquals(Triple("none", "false", "None"), chips.first())
        assertEquals("Sci-Fi", chips.first { it.first == "scifi" }.third)
        assertEquals("Western", chips.first { it.first == "western" }.third)
    }

    @Test fun page_keepsTheMovieKindRowInDropMode() {
        // The row is independent of adult_mode (only the adult row is gated), so the
        // facet survives a crawler that drops adult rows at ingest.
        val drop = ControlServer.buildSearchPage(
            CrawlerTaxonomy.CATEGORIES.toSet(), adultMode = "drop")
        assertTrue(drop.contains("id=\"movie-kind-row\" hidden"))
        assertTrue(drop.contains("data-movie-kind=\"scifi\""))
        assertEquals(listOf("none") + CrawlerTaxonomy.MOVIE_KINDS,
            movieChipsIn(drop).map { it.first })
    }

    @Test fun js_movieKindStateAndLabels() {
        // `sel.movie_kinds` is the facet ([] = no filter) and the chip/badge labels
        // are emitted from CrawlerTaxonomy.MOVIE_KIND_LABELS, not re-typed.
        assertTrue(page.contains("movie_kinds: []"))
        assertTrue(page.contains("var MOVIE_KIND_LABELS = {"))
        for ((name, label) in CrawlerTaxonomy.MOVIE_KIND_LABELS) {
            assertTrue("missing the $name label", page.contains("\"$name\":\"$label\""))
        }
    }

    @Test fun js_movieKindFacetRidesEveryRequest() {
        // Every request builder carries the facet (null when empty ⇒ no URL param),
        // and pendingReq/current mirror it so Load more and Back keep it.
        val expr = "movie_kinds: sel.movie_kinds && sel.movie_kinds.length ? sel.movie_kinds : null"
        assertEquals(
            "sendBrowse(), doSearch(), randomize and the restored current must all carry it",
            4, page.split(expr).size - 1,
        )
        assertTrue("pendingReq must mirror the facet",
            page.contains("tv_kind: obj.tv_kind, movie_kinds: obj.movie_kinds,"))
        assertTrue("the rendered current must keep the facet",
            page.contains("movie_kinds: req.movie_kinds, adult: req.adult, page: page,"))
        assertTrue("Load more must keep the facet",
            page.contains("movie_kinds: c.movie_kinds, adult: c.adult, page: c.page + 1"))
    }

    @Test fun js_movieKindChipsAreMultiSelectToggles() {
        // Multi-select facet: a click toggles the genre in/out of sel.movie_kinds
        // and flips only that chip; the facet is conjunctive, so the request it
        // builds asks for rows carrying ALL the selected genres. It must NOT use
        // the tv-kind focus mode, whose sibling-hiding assumes single-select.
        val body = handlerBody("el('movie-kind-row').onclick")
        assertFalse("the movie row must not use focus mode", body.contains("chipFocus("))
        assertFalse("the movie row must not hide siblings", body.contains("focusRow("))
        assertTrue(body.contains("sel.movie_kinds.indexOf(k)"))
        assertTrue(body.contains("sel.movie_kinds.push(k)"))
        assertTrue(body.contains("sel.movie_kinds.splice(at, 1)"))
        assertTrue("a toggle clears the stale result set", body.contains("clearResults();"))
        assertFalse("a toggle must not fetch", body.contains("sendBrowse("))
        // Every filter chip only clears; Search and Randomise are the load triggers.
        for (marker in listOf(
            "el('category-grid').onclick",
            "el('tv-kind-row').onclick",
            "el('adult-control').onclick",
            "el('movie-kind-row').onclick",
        )) {
            assertFalse("$marker must not fetch", handlerBody(marker).contains("sendBrowse("))
        }
    }

    @Test fun js_movieKindRowIsMovieOnlyAndClearsOnSwitch() {
        // The row is revealed only for the movie category; switching away drops the
        // facet and repaints every chip unpressed.
        assertTrue(page.contains("function hideMovieRow()"))
        assertTrue(page.contains("row.setAttribute('hidden', '')"))
        assertTrue(page.contains("sel.movie_kinds = [];"))
        assertTrue(page.contains("function repaintMovieChips()"))
        assertTrue("the category handler must gate the row on the movie category",
            handlerBody("el('category-grid').onclick")
                .contains("if (sel.category === 'movie') el('movie-kind-row').removeAttribute('hidden');"))
        val start = page.indexOf("function applySel(focus) {")
        assertTrue("missing applySel(focus)", start >= 0)
        val end = page.indexOf("if (ADULT_SELECTION) {", start)
        assertTrue("unterminated applySel()", end > start)
        val applyBody = page.substring(start, end)
        assertTrue("applySel must reveal the movie row for the movie category",
            applyBody.contains("movieRow.removeAttribute('hidden')"))
        assertTrue("applySel must repaint the movie chips",
            applyBody.contains("repaintMovieChips()"))
    }

    @Test fun js_cardsShowUpToThreeGenreBadges() {
        // Genre badges use the pretty labels, cap at 3 and summarise the rest as
        // `+N`; rows without a verdict (null/[]/absent movie_kind) get none. The
        // active genre facet is pinned ahead of the rest so a matching genre can
        // never hide behind the overflow badge.
        val start = page.indexOf("function buildCard(r) {")
        assertTrue("missing buildCard()", start >= 0)
        val end = page.indexOf("function render(", start)
        assertTrue("unterminated buildCard()", end > start)
        val body = page.substring(start, end)
        assertTrue(body.contains("if (r.movie_kind && r.movie_kind.length) {"))
        assertTrue("the active facet must be read from sel",
            body.contains("var act = sel.movie_kinds || [];"))
        assertTrue("the pinned partition must be an explicit filter",
            body.contains("return act.indexOf(String(g)) !== -1;"))
        assertTrue("the rest partition must be the complement filter",
            body.contains("return act.indexOf(String(g)) === -1;"))
        assertTrue(body.contains("var kinds = pinned.concat(rest);"))
        assertFalse("the partition must not rely on sort stability",
            body.contains(".sort("))
        assertTrue("the cap loop must iterate the partitioned list",
            body.contains("i < kinds.length && i < 3"))
        assertTrue(body.contains("MOVIE_KIND_LABELS[genre] || genre"))
        assertTrue("the overflow must count the partitioned list",
            body.contains("badge('+' + (kinds.length - 3))"))
    }

    @Test fun js_savesAndRestoresTheMovieKindFacet() {
        // Additive field in the existing torrent.search.v1 payload (no key/version
        // bump); a blob saved before stage 3 restores as [] and applySel repaints.
        assertTrue("save must persist the facet",
            page.contains("movie_kinds: sel.movie_kinds, adult: sel.adult"))
        assertTrue("restore must read it back",
            page.contains("sel.movie_kinds = Array.isArray(saved.sel.movie_kinds)"))
        assertTrue(page.contains("? saved.sel.movie_kinds.map(String) : [];"))
        assertTrue(page.contains("var SAVE_KEY = 'torrent.search.v1';"))
    }

    // ── filter-aware empty state (strict genre facet) ───────────────────────

    /** The empty-state helpers plus `render()`: from `showEmpty(req)` up to the
     * next function (`send`), so the helpers and the render tail are one slice. */
    private fun renderRegion(): String {
        val start = page.indexOf("function showEmpty(req) {")
        assertTrue("missing showEmpty(req)", start >= 0)
        val end = page.indexOf("function send(", start)
        assertTrue("unterminated render()", end > start)
        return page.substring(start, end)
    }

    @Test fun page_hasAFilterAwareEmptyState() {
        // An always-rendered, `hidden`-by-default message element right below the
        // results column (the same pattern as the torrent page's tr.tx-empty
        // rows), with its own style rule.
        assertTrue(page.contains("<div id=\"results-empty\" hidden></div>"))
        assertTrue("missing the #results-empty rule", page.contains("#results-empty{"))
        assertTrue(
            "the empty state must sit below the results column",
            page.indexOf("<div id=\"results\"></div>") <
                page.indexOf("<div id=\"results-empty\" hidden></div>"),
        )
    }

    @Test fun js_emptyResultNamesTheStrictFacet() {
        // The message names the genres of the request that produced the payload
        // (`req` is the pendingReq snapshot), never the live `sel` a chip toggle
        // mutates without refetching, and an append page never shows it at all
        // (an empty load-more page can coexist with has_more, and the message
        // would break the infinite-scroll sentinel).
        val body = renderRegion()
        val helper = body.substring(0, body.indexOf("function hideEmpty("))
        assertTrue(helper.contains("el('results-empty')"))
        assertTrue(helper.contains("var kinds = (req && req.movie_kinds) || [];"))
        assertTrue(helper.contains("'No torrents match all of ' + labels.join(' + ')"))
        assertTrue("labels come from the chip table",
            helper.contains("MOVIE_KIND_LABELS[g] || g"))
        assertTrue("the none token renders as None", helper.contains("g === 'none' ? 'None' :"))
        assertTrue("the no-genre case keeps a generic message",
            helper.contains("'No torrents match these filters'"))
        assertFalse("showEmpty must read the request, not the live selection",
            helper.contains("sel.movie_kinds"))
        assertTrue("an append page must never show the message",
            body.contains("if (!wasAppend && results.length === 0) showEmpty(req); else hideEmpty();"))
        assertTrue("a non-empty page hides the message", body.contains("else hideEmpty();"))
    }

    @Test fun js_randomEmptyShowsTheMessageBeforeReturning() {
        // The random branch nulls `current` and returns early, so it paints the
        // empty state itself — from `req` only.
        val body = renderRegion()
        val start = body.indexOf("if (req.type === 'random') {")
        assertTrue("missing the random branch", start >= 0)
        val end = body.indexOf("var page = ", start)
        assertTrue("unterminated random branch", end > start)
        val branch = body.substring(start, end)
        assertTrue(branch.contains("if (results.length === 0) showEmpty(req); else hideEmpty();"))
        assertTrue("the empty state must be painted before the early return",
            branch.indexOf("showEmpty(req)") < branch.indexOf("return;"))
        assertFalse("the random branch must not read the live selection",
            branch.contains("sel.movie_kinds"))
        assertTrue("the status line still says Random pick",
            branch.contains("statusText('Random pick');"))
    }

    @Test fun js_clearResultsResetsTheStatusLine() {
        // A chip toggle clears the result set; leaving the old "N results" line
        // (and a stale empty-state message) behind would describe a set that is
        // no longer on screen.
        val start = page.indexOf("function clearResults() {")
        assertTrue("missing clearResults()", start >= 0)
        val end = page.indexOf("function rowFocused(", start)
        assertTrue("unterminated clearResults()", end > start)
        val body = page.substring(start, end)
        assertTrue(body.contains("hideEmpty();"))
        assertTrue(body.contains("statusText('Pick your filters, then Search or Randomize');"))
        assertFalse("clearResults must not fetch", body.contains("sendBrowse("))
    }

    // ── per-card peer refresh on the search results ─────────────────────────

    /** The `refreshPeers(hash, btn, peers)` body: everything from its declaration
     * up to the next function (the cards' `selectOnly`). */
    private fun refreshBody(): String {
        val start = page.indexOf("function refreshPeers(")
        assertTrue("missing refreshPeers()", start >= 0)
        val end = page.indexOf("function selectOnly(", start)
        assertTrue("unterminated refreshPeers()", end > start)
        return page.substring(start, end)
    }

    @Test fun page_hasRefreshPeersButton() {
        // Every result card carries a peer-refresh button in its actions row,
        // built exactly like the Add button (no inline markup) and wired to the
        // card's own hash and peers span. Its visible text is gone: the button is
        // an inline SVG glyph whose accessible name lives on title/aria-label.
        assertTrue(page.contains("refreshBtn.type = 'button'"))
        assertTrue(page.contains("refreshBtn.className = 'btn refresh-btn'"))
        assertTrue(page.contains("refreshBtn.setAttribute('title', 'Refresh peers')"))
        assertTrue(page.contains("refreshBtn.setAttribute('aria-label', 'Refresh peers')"))
        assertTrue(page.contains("refreshBtn.innerHTML = '<svg viewBox=\"0 0 24 24\""))
        assertTrue(page.contains("stroke-linejoin=\"round\" aria-hidden=\"true\">"))
        assertFalse(page.contains("refreshBtn.textContent = '\\u21bb Refresh peers'"))
        assertTrue(
            "the button must refresh its own card",
            page.contains("refreshBtn.onclick = function () { refreshPeers(r.info_hash, refreshBtn, peers); }"),
        )
        assertTrue("missing the .refresh-btn style", page.contains(".refresh-btn{"))
        assertTrue(
            "the action row must wrap so the icon buttons keep their size",
            page.contains(".actions{display:flex;flex-wrap:wrap;"),
        )
    }

    // ── per-card IMDb search on the search results ──────────────────────────

    @Test fun page_hasImdbButton() {
        // Every movie/tv result card offers an IMDb name search built from the
        // crawler's extracted media title (filtered_name), never from the raw
        // release name (1080p/x265/group tags make such a query useless). The
        // button is gated to the movie and tv categories — anime is excluded by
        // operator decision, along with every other category — and is disabled
        // (no raw-name fallback) when no usable title was extracted. The link is
        // opened through a synthetic anchor (target=_blank, noopener/noreferrer)
        // — no window.open and no external asset, which page_hasNoExternalAssets
        // pins.
        assertTrue(page.contains("var IMDB_CATEGORIES = ['movie', 'tv'];"))
        assertTrue(page.contains("function imdbQuery("))
        assertTrue("must query the crawler's filtered_name, never the raw name",
            page.contains("r.filtered_name"))
        assertTrue(page.contains("function imdbCategory("))
        assertTrue(page.contains("IMDB_CATEGORIES.indexOf(String(r.category)) !== -1"))
        assertTrue(page.contains("imdbBtn.className = 'btn imdb-btn'"))
        assertTrue(page.contains("imdbBtn.textContent = 'Search IMDb'"))
        assertTrue(page.contains(
            "a.href = 'https://www.imdb.com/find/?q=' + encodeURIComponent(imdbQuery(r));"))
        assertTrue("a blank extracted title must disable the button, not fall back",
            page.contains("imdbBtn.disabled = true"))
        assertTrue(page.contains("'No IMDb-searchable title'"))
        assertTrue("the button is only appended for IMDb-searchable categories",
            page.contains("if (imdbCategory(r)) {"))
        assertTrue(page.contains("actions.appendChild(imdbBtn)"))
        assertTrue(page.contains("a.target = '_blank'"))
        assertTrue(page.contains("a.rel = 'noopener noreferrer'"))
        assertTrue("missing the .imdb-btn style", page.contains(".imdb-btn{"))
        assertFalse("the search card must not tokenize the raw release name anymore",
            page.contains("imdbQuery(r.name)"))
    }

    @Test fun js_refreshCallsSameOriginRoute() {
        // The card refresh reuses the torrent page's route through this server, so
        // the search page still needs no crawler proxy origin.
        assertTrue(
            "must call the same-origin refresh route with the card hash",
            refreshBody().contains(
                "fetch('/api/crawler/refresh?hash=' + encodeURIComponent(hash), { signal: ctrl.signal })"),
        )
        assertTrue("the click wiring must pass the card's info_hash",
            page.contains("refreshPeers(r.info_hash, refreshBtn, peers)"))
        assertFalse(page.contains("proxyOrigin"))
        assertFalse(page.contains("127.0.0.1:8081"))
    }

    @Test fun js_refreshAbortsBeforeServerTimeout() {
        // /api/crawler/refresh holds the connection up to 30 s; the card gives up
        // at 25 s so the button never stays disabled on a stalled crawler.
        val body = refreshBody()
        assertTrue(body.contains("var ctrl = new AbortController();"))
        assertTrue("must abort before the server's 30 s timeout",
            body.contains("}, 25000);"))
        assertTrue(body.contains("clearTimeout(timer)"))
        assertTrue("the abort signal must reach fetch", body.contains("{ signal: ctrl.signal }"))
    }

    @Test fun js_refreshUpdatesTheCardPeersSpan() {
        // A successful refresh rewrites the card's peers span in place, with the
        // same hi/mid/lo colour thresholds buildCard uses, and re-enables the
        // button on every path (success and catch).
        val body = refreshBody()
        assertTrue(body.contains("var n = j.payload.total_peers;"))
        assertTrue(body.contains("peers.textContent = n + ' peers';"))
        assertTrue(body.contains("peers.className = 'peers ' + (n > 100 ? 'hi' : (n > 10 ? 'mid' : 'lo'));"))
        val reenables = body.split("btn.disabled = false;").size - 1
        assertTrue("expected the button re-enabled on success and catch, found $reenables",
            reenables >= 2)
        assertTrue("the catch path must re-enable the button right after disarming the abort",
            body.contains(
                ".catch(function () { clearTimeout(timer); btn.disabled = false; " +
                    "peers.textContent = 'Refresh failed'; toast('Refresh failed', true); });"))
    }

    @Test fun js_refreshFailureIsAToast() {
        // Failures (bad reply or aborted fetch) surface as the page's toast, never
        // a blocking browser dialog.
        val body = refreshBody()
        assertTrue(body.contains("toast('Refresh failed', true)"))
        assertTrue(body.contains("if (!hash) { toast('Bad info hash', true); return; }"))
        assertFalse(page.contains("alert("))
    }

    @Test fun js_refreshGuardsReentryAndShowsFeedback() {
        // webui parity with /torrent: a repeat press is dropped by the re-entry
        // guard, and the in-flight state is visible in the card itself (a
        // disabled button alone renders no visual change for this style).
        val body = refreshBody()
        assertTrue("missing the re-entry guard", body.contains("if (btn.disabled) return;"))
        assertTrue("missing the in-flight peers feedback",
            body.contains("peers.textContent = 'Refreshing\\u2026';"))
        assertTrue("missing the inline failure feedback",
            body.contains("peers.textContent = 'Refresh failed';"))
    }

    @Test fun js_refreshKeepsTheAbortArmedThroughTheBodyRead() {
        // Stage 2: the 25 s timer is disarmed only by the terminal handlers. Clearing
        // it when the headers arrive would leave a stalled r.json() (the body read)
        // with nothing to abort, so the card button would stay disabled forever.
        val body = refreshBody()
        assertFalse(
            "the header .then must not disarm the abort before the body is read",
            body.contains("clearTimeout(timer); return r.json();"),
        )
        val clears = body.split("clearTimeout(timer)").size - 1
        assertTrue("expected the timer cleared in the success and catch terminals, found $clears",
            clears >= 2)
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
