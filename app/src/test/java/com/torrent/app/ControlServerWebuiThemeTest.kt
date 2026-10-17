package com.torrent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string tests for the shared WebUI theme (search-dashboard stage 5a).
 * `ControlServer.WEBUI_CSS` is the single source of the blue palette and the
 * reusable chrome (phone column, header/nav, buttons, chips, cards, banners,
 * toasts); each page's CSS is that theme plus its own rules. These pin the
 * theme tokens and the /search page's no-external-assets contract.
 */
class ControlServerWebuiThemeTest {

    private val css = ControlServer.WEBUI_CSS
    private val page = ControlServer.buildSearchPage()

    @Test fun theme_hasBluePaletteTokens() {
        for (token in listOf(
            "--blue:#1a73e8",
            "--blue-dark:#1557b0",
            "--chip:#e8f0fe",
            "--text:#1f2937",
            "--muted:#5f6368",
            "--card:#fff",
        )) {
            assertTrue("missing theme token $token", css.contains(token))
        }
    }

    @Test fun theme_hasBodyGradient() {
        assertTrue(css.contains("linear-gradient(180deg,#eef4fd,#f5f7fa)"))
    }

    @Test fun searchPage_usesSharedTheme() {
        assertTrue(page.contains("--blue:#1a73e8"))
        assertTrue(page.contains(".wrap"))
        assertTrue(page.contains(".btn"))
    }

    @Test fun searchPage_hasNoExternalAssets() {
        assertFalse(page.contains("<script src"))
        assertFalse(page.contains("<link "))
        assertFalse(page.contains("cdn."))
    }

    @Test fun statsPage_usesSharedTheme() {
        val stats = ControlServer.buildStatsPage()
        assertTrue(stats.contains("--blue:#1a73e8"))
        assertTrue(stats.contains(".wrap") || stats.contains(".btn"))
    }

    @Test fun statsPage_hasNoExternalAssets() {
        val stats = ControlServer.buildStatsPage()
        assertFalse(stats.contains("<script src"))
        assertFalse(stats.contains("<link "))
        assertFalse(stats.contains("cdn."))
    }
}
