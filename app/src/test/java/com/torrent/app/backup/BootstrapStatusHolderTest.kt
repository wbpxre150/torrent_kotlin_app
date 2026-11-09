package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JUnit tests for [BootstrapStatusHolder] — the holder `DaemonService`
 * publishes the BEP51 bootstrap-cache download's state into (plan Stage 6).
 * The clock is injected, so every timestamp assertion is deterministic.
 */
class BootstrapStatusHolderTest {

    private var now = 1_700_000_000_000L

    private fun holder() = BootstrapStatusHolder(clock = { now })

    @Test fun startsIdle() {
        val st = holder().status
        assertEquals(BootstrapState.IDLE, st.state)
        assertFalse(st.running)
        assertNull(st.startedAt)
        assertNull(st.finishedAt)
        assertNull(st.lastFailureAt)
        assertNull(st.lastPlacedBytes)
    }

    @Test fun markRunningPublishesTruthfulRunningState() {
        val h = holder()
        h.markRunning()
        val st = h.status
        assertEquals(BootstrapState.RUNNING, st.state)
        assertTrue(st.running)
        assertEquals(now, st.startedAt!!)
        assertNull(st.finishedAt)
        // No invented progress: the whole body is buffered, so there is no
        // denominator to expose.
        assertNull(st.lastPlacedBytes)
    }

    @Test fun markPlacedRecordsTheTerminalOutcomeAndTheCacheSize() {
        val h = holder()
        h.markRunning()
        now += 5_000
        h.markPlaced(nodeCount = 10, bytes = 4_096)
        val st = h.status
        assertEquals(BootstrapState.OK, st.state)
        assertFalse(st.running)
        assertEquals(1_700_000_000_000L, st.startedAt!!)
        assertEquals(1_700_000_005_000L, st.finishedAt!!)
        assertEquals(10, st.lastPlacedNodeCount!!)
        assertEquals(4_096L, st.lastPlacedBytes!!)
        assertTrue(st.message, st.message.contains("4096"))
        assertNull(st.lastFailureAt)
    }

    @Test fun markFailedStampsTheRetryGate() {
        val h = holder()
        h.markRunning()
        now += 1_000
        h.markFailed("boom")
        val st = h.status
        assertEquals(BootstrapState.FAILED, st.state)
        assertFalse(st.running)
        assertEquals("boom", st.message)
        // lastFailureAt is the in-memory retry gate shouldBootstrap() consults.
        assertEquals(1_700_000_001_000L, st.lastFailureAt!!)
        assertEquals(st.lastFailureAt, st.finishedAt)
    }

    @Test fun markSkippedKeepsTheLastPlacedSizeAndArmsNoRetryGate() {
        val h = holder()
        h.markPlaced(nodeCount = 3, bytes = 12)
        now += 60_000
        h.markSkipped("cache file already present")
        val st = h.status
        assertEquals(BootstrapState.SKIPPED, st.state)
        assertFalse(st.running)
        assertEquals(12L, st.lastPlacedBytes!!)
        assertNull(st.lastFailureAt)
    }
}
