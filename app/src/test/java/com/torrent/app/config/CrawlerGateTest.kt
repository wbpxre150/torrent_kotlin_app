package com.torrent.app.config

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

    @Test fun exitedDuringWatch_onlyWhenRestartCountGrew() {
        assertFalse(CrawlerGate.exitedDuringWatch(2, 2))
        assertTrue(CrawlerGate.exitedDuringWatch(2, 3))
    }

    @Test fun mayStart_wizardCompleteAndGateOpen_starts() {
        assertTrue(CrawlerGate.mayStart(true, true, false, true))
        assertTrue(CrawlerGate.mayStart(true, true, false, false))
        assertTrue(CrawlerGate.mayStart(true, true, true, true))
    }

    @Test fun mayStart_wizardIncomplete_neverStarts() {
        assertFalse(CrawlerGate.mayStart(false, true, false, true))
        assertFalse(CrawlerGate.mayStart(false, true, true, true))
    }

    @Test fun mayStart_disabled_neverStarts() {
        assertFalse(CrawlerGate.mayStart(true, false, false, true))
    }

    @Test fun mayStart_wifiOnlyWithoutWifi_doesNotStart() {
        assertFalse(CrawlerGate.mayStart(true, true, true, false))
    }
}
