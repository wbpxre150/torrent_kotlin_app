package com.torrent.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardRestoreGateTest {
    @Test fun restoreOff_allowsFinishRegardlessOfState() {
        assertTrue(WizardRestoreGate.canFinish(restoreEnabled = false, state = null))
        for (s in DbRestoreState.values())
            assertTrue("OFF with $s", WizardRestoreGate.canFinish(restoreEnabled = false, state = s))
    }

    @Test fun restoreOn_withoutAttempt_blocksFinish() {
        assertFalse(WizardRestoreGate.canFinish(restoreEnabled = true, state = null))
        assertFalse(WizardRestoreGate.canFinish(restoreEnabled = true, state = DbRestoreState.IDLE))
    }

    @Test fun restoreOn_runningOrFailedStates_blockFinish() {
        for (s in listOf(
            DbRestoreState.RUNNING,
            DbRestoreState.FAILED,
            DbRestoreState.REFUSED_BUSY,
            DbRestoreState.REFUSED_RUNNING,
            DbRestoreState.NOT_CONFIGURED,
        )) {
            assertFalse("ON with $s", WizardRestoreGate.canFinish(restoreEnabled = true, state = s))
        }
    }

    @Test fun restoreOn_success_allowsFinish() {
        assertTrue(WizardRestoreGate.canFinish(restoreEnabled = true, state = DbRestoreState.SUCCESS))
    }

    @Test fun onlySuccessUnlocksFinish() {
        val unlocked = DbRestoreState.values().filter { WizardRestoreGate.canFinish(true, it) }
        assertTrue(unlocked == listOf(DbRestoreState.SUCCESS))
    }
}
