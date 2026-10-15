package com.torrent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string tests for the /stats page (webui stage 2). The page is a
 * single inline raw string, so these pin its behavioural contract: the
 * live websocket feed path, the reconnect/backoff logic, the top-level
 * summary strip, the "not running" fallback, the expand/collapse state
 * persistence, the stage-8 classifier.backfill cell, and the
 * no-external-assets rule.
 */
class ControlServerStatsPageTest {

    private val page = ControlServer.buildStatsPage()

    @Test fun page_hasHeadingAndTitle() {
        assertTrue(page.contains("<title>Torrent App — Crawler Stats</title>"))
        assertTrue(page.contains("<h1>Crawler Stats</h1>"))
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

    /**
     * Stage 8: the crawler's resumable backfill/re-classify job reports itself
     * at `classifier.backfill`, and the summary strip carries a cell fed
     * straight from it. These tests have no JS engine to run the page's inline
     * script, so — like the rest of this file — the contract is pinned as page
     * source: the render function reads classifier.backfill and formats the
     * running / complete / idle states exactly like this.
     */
    @Test fun page_backfillCellRendersProgressFromClassifierStats() {
        assertTrue(page.contains("data.classifier ? data.classifier.backfill : null"))
        assertTrue(page.contains("function fmtInt(v)"))
        assertTrue(page.contains("text = 'running \\u2014 ' + fmtInt(bf.done) + '/' + fmtInt(bf.total)"))
        assertTrue(page.contains("' rows' + (typeof bf.rate_rows_s === 'number'"))
        assertTrue(page.contains("Math.round(bf.rate_rows_s).toLocaleString() + ' rows/s)'"))
        assertTrue(page.contains("text = 'complete \\u2014 ' + fmtInt(bf.done) + '/' + fmtInt(bf.total) + ' rows'"))
        assertTrue(page.contains("text = bf.state;"))
        assertTrue(page.contains("out.textContent = text;"))
        assertTrue(page.contains("renderBackfill(data);"))
    }

    /**
     * The backfill cell degrades to nothing when the field is absent: an older
     * crawler binary, an unusable classifier (available:false), or a
     * malformed/empty state all leave the cell hidden rather than showing a
     * misleading number.
     */
    @Test fun page_hidesBackfillCellWhenAbsentOrUnavailable() {
        assertTrue(page.contains("<div id=\"bf-cell\" style=\"display:none\">"))
        assertTrue(page.contains("if (!bf || typeof bf !== 'object' || bf.available === false ||"))
        assertTrue(page.contains("typeof bf.state !== 'string' || bf.state === ''"))
        assertTrue(page.contains("cell.style.display = 'none';"))
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
