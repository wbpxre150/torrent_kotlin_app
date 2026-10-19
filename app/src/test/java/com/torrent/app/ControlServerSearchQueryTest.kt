package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Test

import com.torrent.app.ControlServer.Companion.CrawlerQueryResult
import com.torrent.app.config.CrawlerTaxonomy

/**
 * Pure tests for the /search/ws query builder + validator (search-dashboard
 * stage 2). No server, no network: [ControlServer.buildCrawlerQuery] owns all
 * the validation rules and the deterministic path/param order. A `category`
 * must be a taxonomy name *and* one the crawler settings enable
 * ([ControlServer.enabledCategories]), so a category the crawler does not
 * ingest is rejected server-side.
 */
class ControlServerSearchQueryTest {

    private fun build(
        type: String?,
        q: String? = null,
        category: String? = null,
        tvKind: String? = null,
        adult: String? = null,
        page: String? = null,
        allowedCategories: Set<String> = CrawlerTaxonomy.CATEGORIES.toSet(),
    ) = ControlServer.buildCrawlerQuery(
        type = type, q = q, category = category,
        allowedCategories = allowedCategories,
        tvKind = tvKind, adult = adult, page = page)

    private fun okPath(
        type: String?,
        q: String? = null,
        category: String? = null,
        tvKind: String? = null,
        adult: String? = null,
        page: String? = null,
        allowedCategories: Set<String> = CrawlerTaxonomy.CATEGORIES.toSet(),
    ): String {
        val r = build(type, q, category, tvKind, adult, page, allowedCategories)
        return (r as CrawlerQueryResult.Ok).path
    }

    private fun badField(
        type: String?,
        q: String? = null,
        category: String? = null,
        tvKind: String? = null,
        adult: String? = null,
        page: String? = null,
        allowedCategories: Set<String> = CrawlerTaxonomy.CATEGORIES.toSet(),
    ): String {
        val r = build(type, q, category, tvKind, adult, page, allowedCategories)
        return (r as CrawlerQueryResult.Bad).field
    }

    // ── valid requests produce exact, deterministic URLs ───────────────────

    @Test fun search_minimalOnlyEncodesQ() {
        assertEquals("/search?q=hello", okPath(type = "search", q = "hello"))
    }

    @Test fun search_allParamsInOrder() {
        assertEquals(
            "/search?q=a+b&category=movie&tv_kind=episode&adult=show&page=2",
            okPath(
                type = "search", q = "a b", category = "movie",
                tvKind = "episode", adult = "show", page = "2"))
    }

    @Test fun browse_omitsAbsentParams() {
        assertEquals("/browse?category=tv&page=3", okPath(type = "browse", category = "tv", page = "3"))
    }

    @Test fun browse_allOptionalAbsentHasNoQueryString() {
        assertEquals("/browse", okPath(type = "browse"))
    }

    @Test fun browse_ignoresQ() {
        assertEquals("/browse", okPath(type = "browse", q = "should-be-ignored"))
    }

    @Test fun random_alwaysCarriesMinPeersAndIgnoresPage() {
        assertEquals("/random?min_peers=10", okPath(type = "random", page = "9"))
    }

    @Test fun random_withCategory() {
        assertEquals(
            "/random?category=music&min_peers=10",
            okPath(type = "random", category = "music"))
    }

    @Test fun adultOmittedByDefault() {
        assertEquals("/search?q=hi", okPath(type = "search", q = "hi", adult = null))
    }

    // ── q is percent-encoded via URLEncoder ────────────────────────────────

    @Test fun search_qIsPercentEncoded() {
        assertEquals(
            "/search?q=h%C3%A9llo+%26+w%C3%B6rld%2B1",
            okPath(type = "search", q = "héllo & wörld+1"))
    }

    // ── invalid input is rejected with a concrete field ────────────────────

    @Test fun badType() {
        assertEquals("type", badField(type = "nope"))
        assertEquals("type", badField(type = null))
    }

    @Test fun searchRequiresNonBlankQ() {
        assertEquals("q", badField(type = "search", q = null))
        assertEquals("q", badField(type = "search", q = ""))
        assertEquals("q", badField(type = "search", q = "   "))
    }

    @Test fun badCategory() {
        assertEquals("category", badField(type = "search", q = "x", category = "porn"))
    }

    @Test fun badTvKind() {
        assertEquals("tv_kind", badField(type = "browse", tvKind = "movie"))
    }

    @Test fun badAdult() {
        assertEquals("adult", badField(type = "browse", adult = "maybe"))
    }

    @Test fun badPage() {
        assertEquals("page", badField(type = "search", q = "x", page = "0"))
        assertEquals("page", badField(type = "browse", page = "-3"))
        assertEquals("page", badField(type = "browse", page = "x"))
    }

    @Test fun validEdgePage() {
        assertEquals("/browse?page=1", okPath(type = "browse", page = "1"))
    }

    @Test fun tvKindNamesAreAccepted() {
        for (k in listOf(
            "none", "episode", "multi_episode", "season_pack",
            "multi_season", "complete_series", "daily",
        )) {
            assertEquals("/browse?tv_kind=$k", okPath(type = "browse", tvKind = k))
        }
    }

    @Test fun categoryNamesAreAccepted() {
        for (c in listOf(
            "unknown", "movie", "tv", "anime", "music", "audiobook",
            "ebook", "comic", "software", "game", "image", "other",
        )) {
            assertEquals("/browse?category=$c", okPath(type = "browse", category = c))
        }
    }

    // ── the category must also be enabled in the crawler settings ───────────

    @Test fun disabledCategoryIsRejected() {
        // A category the crawler's `save_categories` filter drops is not
        // searchable: the server answers bad_request instead of forwarding it.
        assertEquals("category", badField(
            type = "browse", category = "comic",
            allowedCategories = setOf("movie", "tv")))
    }

    @Test fun enabledCategoryIsAccepted() {
        assertEquals("/browse?category=movie", okPath(
            type = "browse", category = "movie",
            allowedCategories = setOf("movie", "tv")))
    }

    @Test fun emptyAllowedSetRejectsEveryCategory() {
        // An empty allowed set can only mean "nothing enabled" here; the route
        // resolves the stored empty list to the full taxonomy first (see
        // enabledCategories_*), so no category reaches the crawler unchecked.
        for (c in CrawlerTaxonomy.CATEGORIES) {
            assertEquals("category", badField(
                type = "browse", category = c, allowedCategories = emptySet()))
        }
    }

    @Test fun absentCategoryIgnoresTheAllowList() {
        assertEquals("/browse", okPath(type = "browse", allowedCategories = emptySet()))
    }

    // ── enabledCategories: stored selection -> allowed set ──────

    @Test fun enabledCategories_emptySelectionMeansAll() {
        // `save_categories` is empty on a fresh install = "save everything".
        assertEquals(
            CrawlerTaxonomy.CATEGORIES.toSet(),
            ControlServer.enabledCategories(emptyList()))
    }

    @Test fun enabledCategories_dropsNamesOutsideTheTaxonomy() {
        assertEquals(
            setOf("movie", "tv"),
            ControlServer.enabledCategories(listOf("movie", "porn", "tv", "")))
    }
}
