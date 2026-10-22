package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's restore card is service-anchored: these are the decisions that let an
 * activity recreated mid-restore (fresh ViewModel, local status `null`) re-attach to the
 * restore the service is still running, or pick up the outcome that landed while it was dead.
 */
class WizardRestorePollPolicyTest {

    private fun running(done: Int?, total: Int? = 17_357) = DbRestoreStatus(
        state = DbRestoreState.RUNNING,
        running = true,
        message = "importing ${(done ?: 0) + 1} of $total object(s)",
        objectCount = total,
        objectsDone = done,
    )

    private fun terminal(state: DbRestoreState, message: String = "done") =
        DbRestoreStatus(state = state, running = false, message = message)

    private val idle = DbRestoreStatus(state = DbRestoreState.IDLE, running = false, message = "")

    // (a) null-local-but-service-RUNNING
    @Test fun nullLocal_runningService_isAdoptedAndPollingStarts() {
        val service = running(done = 5)
        val adopted = WizardRestorePollPolicy.adopt(local = null, service = service)

        assertSame(service, adopted)
        assertTrue(WizardRestorePollPolicy.attemptSeen(adopted))
        assertTrue("poll loop must run off the ADOPTED snapshot", WizardRestorePollPolicy.shouldPoll(adopted))
        // ...and the switch is re-attached ON, the buttons stay disabled mid-restore.
        assertFalse(WizardRestorePollPolicy.restoreSwitchEnabled(adopted))
        assertFalse(WizardRestorePollPolicy.restoreNowEnabled(bound = true, restoreEnabled = true, adopted = adopted))
    }

    // (b) recreation mid-run: local is null again, the service keeps progressing
    @Test fun recreationMidRun_resumesPollingAndShowsProgress() {
        val recreated = WizardRestorePollPolicy.adopt(local = null, service = running(done = 64))
        assertEquals(64, recreated?.objectsDone)
        assertEquals(DbRestoreState.RUNNING, recreated?.state)
        assertTrue(WizardRestorePollPolicy.shouldPoll(recreated))

        // Each tick adopts the newest snapshot: progress keeps advancing after the re-attach.
        val next = WizardRestorePollPolicy.adopt(local = recreated, service = running(done = 65))
        assertEquals(65, next?.objectsDone)
        assertTrue(WizardRestorePollPolicy.shouldPoll(next))
    }

    // (c) SUCCESS arrived while the activity was dead
    @Test fun successWhileActivityDead_isAdoptedStopsPollingAndUnlocksGate() {
        val adopted = WizardRestorePollPolicy.adopt(
            local = null,
            service = terminal(DbRestoreState.SUCCESS, "restore complete"),
        )

        assertEquals(DbRestoreState.SUCCESS, adopted?.state)
        assertFalse(WizardRestorePollPolicy.shouldPoll(adopted))
        assertTrue(
            "a recreated wizard must unlock Finish setup on a SUCCESS it did not witness",
            WizardRestoreGate.canFinish(restoreEnabled = true, state = adopted?.state),
        )
        assertTrue(WizardRestorePollPolicy.restoreSwitchEnabled(adopted))
    }

    // (d) FAILED
    @Test fun failed_isAdoptedStopsPollingAndReenablesButton() {
        val adopted = WizardRestorePollPolicy.adopt(
            local = null,
            service = terminal(DbRestoreState.FAILED, "chunk 64/17357 failed"),
        )

        assertEquals(DbRestoreState.FAILED, adopted?.state)
        assertFalse(WizardRestorePollPolicy.shouldPoll(adopted))
        assertTrue(WizardRestorePollPolicy.restoreNowEnabled(bound = true, restoreEnabled = true, adopted = adopted))
        assertFalse(WizardRestoreGate.canFinish(restoreEnabled = true, state = adopted?.state))
    }

    // (e) IDLE / IDLE: nothing was attempted, nothing is shown, nothing polls
    @Test fun idleServiceAndIdleLocal_noPollingButtonEnabled() {
        val adopted = WizardRestorePollPolicy.adopt(local = null, service = idle)

        assertNull("a fresh service must not surface an empty \"Restore running\" line", adopted)
        assertFalse(WizardRestorePollPolicy.attemptSeen(adopted))
        assertFalse(WizardRestorePollPolicy.shouldPoll(adopted))
        assertTrue(WizardRestorePollPolicy.restoreSwitchEnabled(adopted))
        assertTrue(WizardRestorePollPolicy.restoreNowEnabled(bound = true, restoreEnabled = true, adopted = adopted))
    }

    @Test fun unboundService_keepsLocalState() {
        val local = running(done = 3)
        assertSame(local, WizardRestorePollPolicy.adopt(local = local, service = null))
        assertNull(WizardRestorePollPolicy.adopt(local = null, service = null))
    }

    @Test fun serviceRestart_idleSnapshotDoesNotEraseLocalSuccess() {
        val local = terminal(DbRestoreState.SUCCESS)
        assertSame(
            local,
            WizardRestorePollPolicy.adopt(local = local, service = idle),
        )
        assertTrue(WizardRestoreGate.canFinish(restoreEnabled = true, state = local.state))
    }

    @Test fun staleLocalRunning_isDroppedWhenServiceReportsNothingInFlight() {
        val stale = running(done = 12)
        assertNull(
            "the restore can only run in the service — a local RUNNING with no service restore is stale",
            WizardRestorePollPolicy.adopt(local = stale, service = idle),
        )
    }

    @Test fun serviceSnapshotWinsOverLocalProgress() {
        val adopted = WizardRestorePollPolicy.adopt(local = running(done = 1), service = running(done = 40))
        assertEquals(40, adopted?.objectsDone)
    }

    @Test fun refusalStates_areAdoptedAsAttempts() {
        for (s in listOf(
            DbRestoreState.REFUSED_BUSY,
            DbRestoreState.REFUSED_RUNNING,
            DbRestoreState.NOT_CONFIGURED,
        )) {
            val adopted = WizardRestorePollPolicy.adopt(local = null, service = terminal(s))
            assertEquals("adopted $s", s, adopted?.state)
            assertFalse("polling stops on $s", WizardRestorePollPolicy.shouldPoll(adopted))
            assertTrue(
                "button re-enabled on $s",
                WizardRestorePollPolicy.restoreNowEnabled(bound = true, restoreEnabled = true, adopted = adopted),
            )
        }
    }

    @Test fun invalidatedVerdict_isNotResurrectedByAResume() {
        val success = terminal(DbRestoreState.SUCCESS)
        assertNull(
            "an edit dropped this verdict — a resume must not unlock Finish setup for the edited values",
            WizardRestorePollPolicy.adopt(local = null, service = success, invalidated = success),
        )
        // A restore that is actually in flight is still adopted.
        val live = WizardRestorePollPolicy.adopt(local = null, service = running(done = 2), invalidated = success)
        assertTrue(WizardRestorePollPolicy.shouldPoll(live))
    }

    @Test fun newAttempt_isAdoptedDespiteAnInvalidatedVerdict() {
        val stale = terminal(DbRestoreState.SUCCESS)
        val fresh = terminal(DbRestoreState.FAILED, "chunk 1/17357 failed")
        assertEquals(
            DbRestoreState.FAILED,
            WizardRestorePollPolicy.adopt(local = null, service = fresh, invalidated = stale)?.state,
        )
    }

    @Test fun restoreNowRequiresBindingAndSwitch() {
        assertFalse(WizardRestorePollPolicy.restoreNowEnabled(bound = false, restoreEnabled = true, adopted = null))
        assertFalse(WizardRestorePollPolicy.restoreNowEnabled(bound = true, restoreEnabled = false, adopted = null))
    }
}
