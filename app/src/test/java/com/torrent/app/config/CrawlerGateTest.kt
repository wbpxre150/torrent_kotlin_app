package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrawlerGateTest {

    @Test fun disabled_neverRuns() {
        assertFalse(CrawlerGate.shouldRun(false, false, true))
        assertFalse(CrawlerGate.shouldRun(false, true, true))
        assertFalse(CrawlerGate.shouldRun(false, true, false))
    }

    @Test fun enabledNotWifiOnly_runsRegardlessOfWifi() {
        assertTrue(CrawlerGate.shouldRun(true, false, true))
        assertTrue(CrawlerGate.shouldRun(true, false, false))
    }

    @Test fun enabledWifiOnly_followsWifi() {
        assertTrue(CrawlerGate.shouldRun(true, true, true))
        assertFalse(CrawlerGate.shouldRun(true, true, false))
    }

    @Test fun crawlerArgs_defaultHasNoArgs() {
        assertEquals(emptyList<String>(), CrawlerGate.crawlerArgs(CrawlerSettings()))
    }

    @Test fun crawlerArgs_skipIntegrityCheckAddsFlag() {
        assertEquals(
            listOf("--dont-check-database"),
            CrawlerGate.crawlerArgs(CrawlerSettings(skipIntegrityCheck = true)))
    }

    @Test fun exitedDuringWatch_onlyWhenRestartCountGrew() {
        assertFalse(CrawlerGate.exitedDuringWatch(2, 2))
        assertTrue(CrawlerGate.exitedDuringWatch(2, 3))
    }
}
