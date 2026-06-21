package com.torrent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollbackPolicyTest {
    private fun status(state: ProcessState, restarts: Int) =
        ProcessStatus("transmission", state, restartCount = restarts)

    @Test fun running_neverRollsBack() {
        val atApply = status(ProcessState.STARTING, 0)
        assertFalse(RollbackPolicy.shouldRollback(atApply, status(ProcessState.RUNNING, 5), 3))
    }
    @Test fun notRunningButFewRestarts_noRollback() {
        val atApply = status(ProcessState.STARTING, 0)
        assertFalse(RollbackPolicy.shouldRollback(atApply, status(ProcessState.STARTING, 2), 3))
    }
    @Test fun notRunningAndRestartsAtThreshold_rollsBack() {
        val atApply = status(ProcessState.STARTING, 0)
        assertTrue(RollbackPolicy.shouldRollback(atApply, status(ProcessState.STOPPED, 3), 3))
    }
    @Test fun accountsForRestartsAlreadyPresentAtApply() {
        val atApply = status(ProcessState.STARTING, 4)
        // only 2 new restarts since apply -> below threshold
        assertFalse(RollbackPolicy.shouldRollback(atApply, status(ProcessState.STOPPED, 6), 3))
    }
    @Test fun stoppedExactlyAtThreshold_rollsBack() {
        val atApply = status(ProcessState.STARTING, 1)
        assertTrue(RollbackPolicy.shouldRollback(atApply, status(ProcessState.STOPPED, 4), 3))
    }
}