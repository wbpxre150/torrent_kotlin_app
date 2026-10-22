package com.torrent.app.wizard

import com.torrent.app.backup.DbRestoreState
import com.torrent.app.backup.DbRestoreStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The progress screen's decisions are pure, so they are pinned here instead of on a device:
 * the state prefix of the status line, its severity bucket, and the bar fraction — including
 * the non-monotonic cases the restore manager really produces.
 */
class RestoreProgressUiTest {

    private fun status(
        state: DbRestoreState,
        running: Boolean = false,
        message: String = "msg",
    ) = DbRestoreStatus(state = state, running = running, message = message)

    // --- progressFraction ------------------------------------------------------------------

    @Test fun fraction_isNullUntilBothCountsAreKnown() {
        assertNull(RestoreProgressUi.progressFraction(null, null))
        assertNull(RestoreProgressUi.progressFraction(null, 12))
        // A RUNNING restore that is still listing the chain has no total yet.
        assertNull(RestoreProgressUi.progressFraction(3, null))
    }

    @Test fun fraction_isNullForANonPositiveTotal() {
        assertNull(RestoreProgressUi.progressFraction(0, 0))
        assertNull(RestoreProgressUi.progressFraction(1, -2))
    }

    @Test fun fraction_isDoneOverTotal() {
        assertEquals(0f, RestoreProgressUi.progressFraction(0, 10)!!, 0f)
        assertEquals(0.5f, RestoreProgressUi.progressFraction(5, 10)!!, 0f)
        assertEquals(1f, RestoreProgressUi.progressFraction(10, 10)!!, 0f)
    }

    @Test fun fraction_clampsAnOvershootAndARegression() {
        // objectCount counts filter-skipped chunks too, so a finished import can sit below 1.
        assertEquals(0.4f, RestoreProgressUi.progressFraction(4, 10)!!, 0f)
        // objectsDone restarts from max(0, marker-3) on resume: it may go backwards, and the
        // bar must simply follow it down (never assume monotonicity).
        assertEquals(0.7f, RestoreProgressUi.progressFraction(7, 10)!!, 0f)
        assertEquals(0.1f, RestoreProgressUi.progressFraction(1, 10)!!, 0f)
        // A bogus negative counter still renders a 0..1 bar.
        assertEquals(0f, RestoreProgressUi.progressFraction(-3, 10)!!, 0f)
        assertEquals(1f, RestoreProgressUi.progressFraction(12, 10)!!, 0f)
    }

    // --- statusKind / statusText -----------------------------------------------------------

    @Test fun running_winsOverTheState() {
        // REFUSED_RUNNING keeps running=true from the in-flight snapshot.
        val refused = status(DbRestoreState.REFUSED_RUNNING, running = true)
        assertEquals(RestoreStatusKind.RUNNING, RestoreProgressUi.statusKind(refused))
        assertEquals("Restore running — msg", RestoreProgressUi.statusText(refused))
        assertEquals(
            RestoreStatusKind.RUNNING,
            RestoreProgressUi.statusKind(status(DbRestoreState.RUNNING, running = true)),
        )
    }

    @Test fun success_isItsOwnBucket() {
        val ok = status(DbRestoreState.SUCCESS, message = "imported 10 of 10 objects")
        assertEquals(RestoreStatusKind.SUCCESS, RestoreProgressUi.statusKind(ok))
        assertEquals("Restore succeeded — imported 10 of 10 objects", RestoreProgressUi.statusText(ok))
    }

    @Test fun failuresAndRefusals_areErrors() {
        listOf(
            DbRestoreState.FAILED,
            DbRestoreState.NOT_CONFIGURED,
            DbRestoreState.REFUSED_BUSY,
            DbRestoreState.REFUSED_RUNNING,
        ).forEach { state ->
            val st = status(state, running = false, message = "why")
            assertEquals(state.name, RestoreStatusKind.ERROR, RestoreProgressUi.statusKind(st))
            assertEquals("Restore failed — why", RestoreProgressUi.statusText(st))
        }
    }

    @Test fun idle_isNeutralAndSaysSo() {
        val idle = status(DbRestoreState.IDLE, message = "")
        assertEquals(RestoreStatusKind.IDLE, RestoreProgressUi.statusKind(idle))
        assertEquals("Restore not started — ", RestoreProgressUi.statusText(idle))
    }
}
