package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownExitGateTest {

    /** Records every exit request instead of killing the test JVM. */
    private class Recorder {
        val exits = mutableListOf<String>()
        fun exit() { exits.add("exit") }
    }

    private fun gate(recorder: Recorder) = ShutdownExitGate { recorder.exit() }

    @Test fun requestingExitDoesNotExitOnItsOwn() {
        val recorder = Recorder()
        val gate = gate(recorder)
        gate.requestExit()
        // The teardown has not run yet: killing now would SIGKILL the crawler
        // before it can flush.
        assertEquals(emptyList<String>(), recorder.exits)
        assertTrue(gate.isExitRequested)
    }

    @Test fun exitsOnCompletedShutdownCycleAfterRequest() {
        val recorder = Recorder()
        val gate = gate(recorder)
        gate.requestExit()
        assertTrue(gate.onShutdownCycleCompleted())
        assertEquals(listOf("exit"), recorder.exits)
    }

    @Test fun neverExitsWhenNoExitWasRequested() {
        val recorder = Recorder()
        val gate = gate(recorder)
        assertFalse(gate.isExitRequested)
        // A plain shutdown (in-app Stop / system teardown) completes without exit.
        assertFalse(gate.onShutdownCycleCompleted())
        assertFalse(gate.onShutdownCycleCompleted())
        assertEquals(emptyList<String>(), recorder.exits)
    }

    @Test fun exitRequestedWhileCycleInFlightExitsThatCyclesCompletion() {
        val recorder = Recorder()
        val gate = gate(recorder)
        // The hook has NOT run yet (that is what "in flight" means); the intent is
        // recorded here and read by the completion hook at the end of the cycle.
        gate.requestExit()
        assertTrue(gate.onShutdownCycleCompleted())
        assertEquals(listOf("exit"), recorder.exits)
    }

    @Test fun exitRequestedAfterACompletionWaitsForTheNextCycle() {
        val recorder = Recorder()
        val gate = gate(recorder)
        // A cycle completed before the request: nothing to exit yet.
        assertFalse(gate.onShutdownCycleCompleted())
        gate.requestExit()
        assertEquals(emptyList<String>(), recorder.exits)
        assertTrue(gate.onShutdownCycleCompleted())
        assertEquals(listOf("exit"), recorder.exits)
    }

    @Test fun exitsAtMostOnceAcrossReportedCompletions() {
        val recorder = Recorder()
        val gate = gate(recorder)
        gate.requestExit()
        assertTrue(gate.onShutdownCycleCompleted())
        assertFalse(gate.onShutdownCycleCompleted())
        assertFalse(gate.onShutdownCycleCompleted())
        assertEquals(listOf("exit"), recorder.exits)
    }
}
