package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure tests for the maintenance action mapping, the space pre-check, the
 * mutex and the busy refusal. A capturing executor is used so the job body is
 * never run (no real binary, no Android) — only [CrawlerMaintenance.start]'s
 * synchronous decisions are exercised.
 */
class CrawlerMaintenanceTest {

    /** Captures submitted runnables without running them. */
    private class CapturingExecutor : Executor {
        val submitted = AtomicInteger(0)
        @Volatile var last: Runnable? = null
        override fun execute(command: Runnable) {
            submitted.incrementAndGet()
            last = command
        }
    }

    private fun newMaintenance(
        executor: Executor,
        dbSize: Long = 0L,
        freeSpace: Long = Long.MAX_VALUE,
        busy: Boolean = false,
    ) = CrawlerMaintenance(
        oneShotRunner = OneShotRunner("libdhtcrawler.so", "/nonexistent"),
        workDir = File("/tmp/crawler"),
        executor = executor,
        dbSize = { dbSize },
        freeSpace = { freeSpace },
        stopCrawler = {},
        startCrawlerIfEnabled = {},
        busy = { busy },
    )

    @Test fun fromRoute_mapsAllActions() {
        assertEquals(MaintenanceAction.CHECK_DATABASE, MaintenanceAction.fromRoute("check-database"))
        assertEquals(MaintenanceAction.COMPACT, MaintenanceAction.fromRoute("compact"))
        assertEquals(MaintenanceAction.RECOVER_DATABASE, MaintenanceAction.fromRoute("recover-database"))
        assertEquals(MaintenanceAction.REBUILD_BLOOM_FILTER, MaintenanceAction.fromRoute("rebuild-bloom-filter"))
        assertEquals(MaintenanceAction.CLEAR_FAILURE_FILTER, MaintenanceAction.fromRoute("clear-failure-filter"))
        assertEquals(
            MaintenanceAction.CATEGORY_FILTER_UPDATE,
            MaintenanceAction.fromRoute("category-filter-update")
        )
        assertEquals(
            MaintenanceAction.LANGUAGE_FILTER_CLEANUP,
            MaintenanceAction.fromRoute("language-filter-cleanup")
        )
        assertNull(MaintenanceAction.fromRoute("porn-filter-update"))
    }

    @Test fun fromRoute_isCaseInsensitiveAndUnknownIsNull() {
        assertEquals(MaintenanceAction.COMPACT, MaintenanceAction.fromRoute("COMPACT"))
        assertEquals(MaintenanceAction.COMPACT, MaintenanceAction.fromRoute("Compact"))
        assertNull(MaintenanceAction.fromRoute("bogus"))
        assertNull(MaintenanceAction.fromRoute(""))
    }

    @Test fun maintenanceRouteAllowed_refusesTheTwoCliOnlyActions() {
        // The enum keeps all seven members, so both CLI flags stay reachable…
        assertEquals(7, MaintenanceAction.entries.size)
        assertEquals(
            listOf("compact", "category-filter-update"),
            MaintenanceAction.entries.filterNot { it.phoneSupported }.map { it.route }
        )
        // …but only the phone-supported routes may be posted from the page.
        for (a in MaintenanceAction.entries) {
            assertEquals("$a", a.phoneSupported, maintenanceRouteAllowed(a.route))
        }
        assertFalse(maintenanceRouteAllowed("compact"))
        assertFalse(maintenanceRouteAllowed("category-filter-update"))
        // The route lookup stays case-insensitive, exactly as fromRoute is.
        assertFalse(maintenanceRouteAllowed("COMPACT"))
        assertTrue(maintenanceRouteAllowed("check-database"))
        assertTrue(maintenanceRouteAllowed("Rebuild-Bloom-Filter"))
        // An unmapped segment is refused exactly like an unknown action.
        assertFalse(maintenanceRouteAllowed("porn-filter-update"))
        assertFalse(maintenanceRouteAllowed(""))
    }

    @Test fun flagsMatchMainCArgv() {
        assertEquals("--check-database", MaintenanceAction.CHECK_DATABASE.flag)
        assertEquals("--compact", MaintenanceAction.COMPACT.flag)
        assertEquals("--recover-database", MaintenanceAction.RECOVER_DATABASE.flag)
        assertEquals("--rebuild-bloom-filter", MaintenanceAction.REBUILD_BLOOM_FILTER.flag)
        assertEquals("--clear-failure-filter", MaintenanceAction.CLEAR_FAILURE_FILTER.flag)
        assertEquals("--category-filter-update", MaintenanceAction.CATEGORY_FILTER_UPDATE.flag)
        assertEquals("--language-filter-cleanup", MaintenanceAction.LANGUAGE_FILTER_CLEANUP.flag)
    }

    @Test fun needsSpaceCheckOnlyForDbRewritingActions() {
        assertFalse(MaintenanceAction.CHECK_DATABASE.needsSpaceCheck)
        assertTrue(MaintenanceAction.COMPACT.needsSpaceCheck)
        assertTrue(MaintenanceAction.RECOVER_DATABASE.needsSpaceCheck)
        assertFalse(MaintenanceAction.REBUILD_BLOOM_FILTER.needsSpaceCheck)
        assertFalse(MaintenanceAction.CLEAR_FAILURE_FILTER.needsSpaceCheck)
        // The purge only deletes rows (+ WAL checkpoint) and no longer compacts
        // the database, so it is not space-gated.
        assertFalse(MaintenanceAction.CATEGORY_FILTER_UPDATE.needsSpaceCheck)
        // The non-Latin sweep only deletes rows (+ WAL checkpoint) too.
        assertFalse(MaintenanceAction.LANGUAGE_FILTER_CLEANUP.needsSpaceCheck)
    }

    @Test fun compact_refusedWhenNotEnoughFreeSpace() {
        val m = newMaintenance(CapturingExecutor(), dbSize = 1_000_000L, freeSpace = 500_000L)
        val st = m.start(MaintenanceAction.COMPACT)
        assertTrue(st.message, st.message.startsWith("refused: not enough free space"))
        assertFalse(st.running)
    }

    @Test fun recoverDatabase_refusedWhenNotEnoughFreeSpace() {
        val m = newMaintenance(CapturingExecutor(), dbSize = 10_000L, freeSpace = 5_000L)
        val st = m.start(MaintenanceAction.RECOVER_DATABASE)
        assertTrue(st.message, st.message.startsWith("refused: not enough free space"))
        assertFalse(st.running)
    }

    @Test fun categoryFilterUpdate_skipsSpaceCheckEvenWithNoSpace() {
        // Purge-only since the auto-compact was dropped, so no DB-sized free space.
        val ex = CapturingExecutor()
        val m = newMaintenance(ex, dbSize = 10_000L, freeSpace = 5_000L)
        val st = m.start(MaintenanceAction.CATEGORY_FILTER_UPDATE)
        assertTrue("category-filter-update must not be space-gated", st.running)
        assertEquals(1, ex.submitted.get())
    }

    @Test fun compact_startsWhenEnoughFreeSpace() {
        val ex = CapturingExecutor()
        val m = newMaintenance(ex, dbSize = 1_000L, freeSpace = 2_000L)
        val st = m.start(MaintenanceAction.COMPACT)
        assertTrue(st.running)
        assertEquals(1, ex.submitted.get())
    }

    @Test fun checkDatabase_skipsSpaceCheckEvenWithNoSpace() {
        val ex = CapturingExecutor()
        val m = newMaintenance(ex, dbSize = 1_000L, freeSpace = 0L)
        val st = m.start(MaintenanceAction.CHECK_DATABASE)
        assertTrue("check-database must not be space-gated", st.running)
        assertEquals(1, ex.submitted.get())
    }

    @Test fun secondAction_refusedWhileOneIsRunning() {
        val ex = CapturingExecutor() // does not run the job, so it stays running
        val m = newMaintenance(ex)
        m.start(MaintenanceAction.CHECK_DATABASE)
        val st = m.start(MaintenanceAction.COMPACT)
        assertTrue(st.message, st.message.startsWith("refused: maintenance already running"))
        assertEquals("only the first action may be queued", 1, ex.submitted.get())
    }

    @Test fun action_refusedWhenBusy() {
        val ex = CapturingExecutor()
        val m = newMaintenance(ex, busy = true)
        val st = m.start(MaintenanceAction.COMPACT)
        assertTrue(st.message, st.message.startsWith("refused: an apply job is running"))
        assertFalse(st.running)
        assertEquals(0, ex.submitted.get())
    }

    @Test fun withProgress_mergesTheProgressFieldsAndKeepsTheExtras() {
        val before = MaintenanceStatus(
            action = MaintenanceAction.REBUILD_BLOOM_FILTER,
            running = true,
            exitCode = null,
            lines = listOf("[MPROG] action=rebuild-bloom-filter phase=scan done=10 total=40 pct=25"),
            startedAt = 1_000L,
            finishedAt = null,
            message = "running --rebuild-bloom-filter",
        )
        val after = before.withProgress(
            MaintenanceProgress(
                "rebuild-bloom-filter", "scan", done = 10, total = 40, percent = 25,
                extras = mapOf("rc" to "0"))
        )
        assertEquals("scan", after.phase)
        assertEquals(25, after.percent)
        assertEquals(10L, after.done)
        assertEquals(40L, after.total)
        // The lifecycle fields and the raw console stay exactly as they were.
        assertEquals(before.action, after.action)
        assertTrue(after.running)
        assertEquals(before.lines, after.lines)
        assertEquals(1_000L, after.startedAt)
        assertEquals(before.message, after.message)
        // The line's other k=v pairs are kept instead of dropped.
        assertEquals(mapOf("rc" to "0"), after.extras)
    }

    @Test fun withProgress_phaseOnlyLineLeavesTheDenominatorNull() {
        val st = MaintenanceStatus(action = MaintenanceAction.CHECK_DATABASE, running = true)
            .withProgress(MaintenanceProgress("check-database", "integrity_check"))
        assertEquals("integrity_check", st.phase)
        assertNull(st.percent)
        assertNull(st.done)
        assertNull(st.total)
    }

    @Test fun start_beginsWithNoProgressFields() {
        // start() clears the progress fields, so a previous run's terminal
        // `phase=done pct=100` can never read as this run's state.
        val st = newMaintenance(CapturingExecutor()).start(MaintenanceAction.CHECK_DATABASE)
        assertTrue(st.running)
        assertNull(st.phase)
        assertNull(st.percent)
        assertNull(st.done)
        assertNull(st.total)
        // …and no counter from a previous run survives either.
        assertTrue(st.extras.isEmpty())
    }
}
