package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MainActivity auto-start / Shutdown-button decisions, extracted as pure functions so
 * the user-requested-shutdown suppression (the teardown ends in `Stopped`, which is also
 * the auto-start trigger) is covered without a Compose/Robolectric harness.
 */
class MainActivityAutoStartTest {

    @Test fun autoStartsWhenStoppedAndNoShutdownRequested() {
        assertTrue(shouldAutoStartService(ServiceState.Stopped, shutdownRequested = false))
    }

    @Test fun doesNotAutoStartAfterUserShutdown() {
        assertFalse(shouldAutoStartService(ServiceState.Stopped, shutdownRequested = true))
    }

    @Test fun doesNotAutoStartInAnyNonStoppedState() {
        for (state in listOf(ServiceState.Starting, ServiceState.Running, ServiceState.Stopping)) {
            assertFalse(
                "state=$state",
                shouldAutoStartService(state, shutdownRequested = false),
            )
            assertFalse(
                "state=$state with shutdown requested",
                shouldAutoStartService(state, shutdownRequested = true),
            )
        }
    }

    @Test fun shutdownIsAvailableWhileRunningOrStarting() {
        assertTrue(shutdownButtonEnabled(ServiceState.Running, shutdownRequested = false))
        assertTrue(shutdownButtonEnabled(ServiceState.Starting, shutdownRequested = false))
    }

    @Test fun shutdownIsInertWhileStoppingOrAfterRequest() {
        assertFalse(shutdownButtonEnabled(ServiceState.Stopping, shutdownRequested = false))
        assertFalse(shutdownButtonEnabled(ServiceState.Stopped, shutdownRequested = false))
        assertFalse(shutdownButtonEnabled(ServiceState.Running, shutdownRequested = true))
        assertFalse(shutdownButtonEnabled(ServiceState.Starting, shutdownRequested = true))
    }

    @Test fun labelTracksTheInFlightTeardown() {
        assertEquals("Shutdown", shutdownButtonLabel(ServiceState.Running, shutdownRequested = false))
        assertEquals("Shutdown", shutdownButtonLabel(ServiceState.Starting, shutdownRequested = false))
        assertEquals("Stopping…", shutdownButtonLabel(ServiceState.Stopping, shutdownRequested = false))
        assertEquals("Stopping…", shutdownButtonLabel(ServiceState.Running, shutdownRequested = true))
        assertEquals("Stopping…", shutdownButtonLabel(ServiceState.Starting, shutdownRequested = true))
    }

    @Test fun startAffordanceAppearsOnlyAfterACompletedTeardown() {
        assertTrue(showsStartAffordance(ServiceState.Stopped, shutdownRequested = true))
        // Not while any shutdown is still in flight…
        for (state in listOf(ServiceState.Starting, ServiceState.Running, ServiceState.Stopping)) {
            assertFalse("state=$state", showsStartAffordance(state, shutdownRequested = true))
        }
        // …and not before the user asked for one.
        assertFalse(showsStartAffordance(ServiceState.Stopped, shutdownRequested = false))

        assertTrue(shutdownButtonEnabled(ServiceState.Stopped, shutdownRequested = true))
        assertEquals("Start daemons", shutdownButtonLabel(ServiceState.Stopped, shutdownRequested = true))
    }

    @Test fun startTapRelaunchesThroughTheAutoStartDecision() {
        // The Start tap clears the suppression; this is the decision that then fires.
        assertTrue(shouldAutoStartService(ServiceState.Stopped, shutdownRequested = false))
        assertFalse(shouldAutoStartService(ServiceState.Stopped, shutdownRequested = true))
    }
}
