package com.torrent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string tests for the /search page shell (search-dashboard stage 3a).
 * The page is a single inline raw string, so these pin its static contract:
 * the frozen category taxonomy, the /search/ws feed reference, the spinner
 * and randomize controls, the TV/adult control rows, and the
 * no-external-assets rule. The interaction logic itself lands in stage 3b.
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
    }

    @Test fun page_hasNoExternalAssets() {
        assertFalse(page.contains("<script src"))
        assertFalse(page.contains("<link "))
        assertFalse(page.contains("cdn."))
    }
}
