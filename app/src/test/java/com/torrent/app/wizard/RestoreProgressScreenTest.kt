package com.torrent.app.wizard

import com.torrent.app.backup.DbRestoreState
import com.torrent.app.backup.DbRestoreStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colour severity of the restore status line: a refusal or a not-configured answer attempted
 * nothing, so it is a warning (amber), while a restore that actually ran and failed stays an
 * error (red). A RUNNING snapshot is never a warning — a mid-restore double tap answers
 * REFUSED_RUNNING with `running = true`.
 */
class RestoreProgressScreenTest {

    @Test fun refusalsAndNotConfigured_readAsWarnings() {
        assertTrue(restoreStatusIsWarning(DbRestoreStatus(state = DbRestoreState.NOT_CONFIGURED)))
        assertTrue(restoreStatusIsWarning(DbRestoreStatus(state = DbRestoreState.REFUSED_BUSY)))
        assertTrue(restoreStatusIsWarning(DbRestoreStatus(state = DbRestoreState.REFUSED_RUNNING)))
    }

    @Test fun aRealFailureStaysAnError() {
        assertFalse(restoreStatusIsWarning(DbRestoreStatus(state = DbRestoreState.FAILED)))
        assertFalse(restoreStatusIsWarning(DbRestoreStatus(state = DbRestoreState.SUCCESS)))
        assertFalse(restoreStatusIsWarning(DbRestoreStatus()))
    }

    @Test fun aRunningSnapshotIsNeverAWarning() {
        assertFalse(
            restoreStatusIsWarning(
                DbRestoreStatus(state = DbRestoreState.REFUSED_RUNNING, running = true),
            ),
        )
        assertFalse(
            restoreStatusIsWarning(
                DbRestoreStatus(state = DbRestoreState.RUNNING, running = true),
            ),
        )
    }
}
