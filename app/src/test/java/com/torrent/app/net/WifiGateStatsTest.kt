package com.torrent.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit4 (no Android, no Robolectric): [WifiGateStats] is pure and takes the clock
 * from the caller, so every case here is deterministic.
 */
class WifiGateStatsTest {

    private val s = WifiGateStats()

    @Test
    fun wifiUpAloneCountsNothing() {
        s.onSample(true, 1_000)
        s.onSample(true, 2_000)
        assertEquals(0L, s.edgesTotal)
        assertEquals(0L, s.edgesOver2sTotal)
        assertEquals(0L, s.downMsTotal)
        assertNull(s.downSinceMs)
        assertNull(s.lastDropoutAtMs)
    }

    @Test
    fun oneEdgeCountedOnceAcrossRepeatedFalseSamples() {
        s.onSample(true, 0)
        s.onSample(false, 1_000)
        s.onSample(false, 1_500)
        s.onSample(false, 9_000)
        assertEquals("repeated false must not re-count the edge", 1L, s.edgesTotal)
        assertEquals(1_000L, s.lastDropoutAtMs)
        assertEquals(1_000L, s.downSinceMs)
    }

    @Test
    fun subTwoSecondFlapIsAnEdgeButNotADisconnect() {
        s.onSample(true, 0)
        s.onSample(false, 10_000)
        s.onSample(true, 10_500)
        assertEquals(1L, s.edgesTotal)
        assertEquals("500 ms flap must not count as a disconnect", 0L, s.edgesOver2sTotal)
        assertEquals(500L, s.downMsTotal)
        assertEquals(500L, s.maxDownMs)
        assertNull(s.downSinceMs)
    }

    @Test
    fun exactlyTwoSecondsIsADisconnect() {
        s.onSample(true, 0)
        s.onSample(false, 1_000)
        s.onSample(true, 3_000)
        assertEquals(1L, s.edgesOver2sTotal)
    }

    @Test
    fun oneMillisecondShortIsNotADisconnect() {
        s.onSample(true, 0)
        s.onSample(false, 1_000)
        s.onSample(true, 2_999)
        assertEquals(1L, s.edgesTotal)
        assertEquals(0L, s.edgesOver2sTotal)
    }

    @Test
    fun durationsAccumulateAndMaxKeepsTheWorst() {
        s.onSample(true, 0)
        s.onSample(false, 1_000)
        s.onSample(true, 2_000)      // 1000 ms
        s.onSample(false, 5_000)
        s.onSample(true, 8_000)      // 3000 ms
        s.onSample(false, 9_000)
        s.onSample(true, 9_200)      // 200 ms
        assertEquals(3L, s.edgesTotal)
        assertEquals(1L, s.edgesOver2sTotal)
        assertEquals(4_200L, s.downMsTotal)
        assertEquals(3_000L, s.maxDownMs)
    }

    @Test
    fun snapshotReportsTheOpenEdgesAge() {
        s.onSample(true, 0)
        s.onSample(false, 1_000)
        val open = s.snapshot(4_500)
        assertEquals(3_500L, open.downNowMs)
        assertEquals("an open edge is not yet in downMsTotal", 0L, open.downMsTotal)
        assertEquals(1L, open.edgesTotal)
        assertEquals(0L, open.edgesOver2sTotal)
        assertEquals(1_000L, open.lastDropoutAtMs)
        s.onSample(true, 5_000)
        assertEquals(0L, s.snapshot(9_999).downNowMs)
        assertEquals(4_000L, s.snapshot(9_999).downMsTotal)
    }

    @Test
    fun snapshotOnAFreshInstanceIsEmpty() {
        assertEquals(WifiGateStats.Snapshot.EMPTY, s.snapshot(123_456))
    }

    @Test
    fun deliberateStopIsNotADropout() {
        s.onSample(true, 0)
        s.noteDeliberateStop(1_000)   // stop(): the next sample is the shutdown's `false`
        s.onSample(false, 1_000)
        assertEquals(0L, s.edgesTotal)
        assertEquals(0L, s.downMsTotal)
        // A real dropout afterwards still counts.
        s.onSample(true, 2_000)
        s.onSample(false, 3_000)
        s.onSample(true, 4_000)
        assertEquals(1L, s.edgesTotal)
        assertEquals(1_000L, s.downMsTotal)
    }

    @Test
    fun deliberateStopClosesAnOpenEdgeAsUsual() {
        s.onSample(true, 0)
        s.onSample(false, 1_000)
        s.noteDeliberateStop(5_000)   // 4000 ms open when the app stopped
        assertEquals(1L, s.edgesOver2sTotal)
        assertEquals(4_000L, s.downMsTotal)
        assertNull(s.downSinceMs)
    }

    @Test
    fun aWifiSampleClearsTheDeliberateStopSuppression() {
        s.onSample(true, 0)
        s.noteDeliberateStop(1_000)
        s.onSample(true, 2_000)       // monitor restarted and the gate is up
        s.onSample(false, 3_000)      // a genuine dropout
        assertEquals(1L, s.edgesTotal)
    }

    @Test
    fun aBackwardsClockCannotProduceNegativeDurations() {
        s.onSample(true, 10_000)
        s.onSample(false, 10_000)
        s.onSample(true, 9_000)
        assertEquals(0L, s.downMsTotal)
        assertEquals(0L, s.maxDownMs)
    }
}
