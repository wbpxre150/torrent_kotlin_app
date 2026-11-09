package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the `[MPROG]` maintenance progress contract: the parser only —
 * no Android, no child process. The line shapes are what the crawler emits
 * through `log_msg(LOG_INFO, …)`: a `[ts] [LEVEL] ` prefix in front of the
 * `[MPROG]` token, whitespace-separated `k=v` pairs after it.
 */
class MaintenanceProgressTest {

    @Test fun parsesATimestampedLogMsgLine() {
        val p = parseMaintenanceProgress(
            "[2026-11-10 12:00:01] [INFO] [MPROG] action=rebuild-bloom-filter phase=scan " +
                "done=1000 total=6098645 pct=1"
        )!!
        assertEquals("rebuild-bloom-filter", p.action)
        assertEquals("scan", p.phase)
        assertEquals(1000L, p.done)
        assertEquals(6098645L, p.total)
        assertEquals(1, p.percent)
        assertTrue("known keys must not leak into extras", p.extras.isEmpty())
    }

    @Test fun parsesWithoutTheLogMsgPrefix() {
        val p = parseMaintenanceProgress(
            "[MPROG] action=recover-database phase=torrents done=200000 total=6098645 pct=3"
        )!!
        assertEquals("recover-database", p.action)
        assertEquals("torrents", p.phase)
        assertEquals(3, p.percent)
    }

    @Test fun phaseOnlyLineCarriesNoPercent() {
        // check-database has no denominator: the emitter omits done/total/pct.
        val p = parseMaintenanceProgress(
            "[2026-11-10 12:00:01] [INFO] [MPROG] action=check-database phase=integrity_check"
        )!!
        assertEquals("check-database", p.action)
        assertEquals("integrity_check", p.phase)
        assertNull(p.done)
        assertNull(p.total)
        assertNull(p.percent)
        assertTrue(p.extras.isEmpty())
    }

    @Test fun missingDoneAndTotalLeaveThemNull() {
        val p = parseMaintenanceProgress("[MPROG] action=recover-database phase=verify")!!
        assertNull(p.done)
        assertNull(p.total)
        assertNull(p.percent)
    }

    @Test fun terminalDoneLineCarriesRcInExtras() {
        val p = parseMaintenanceProgress(
            "[2026-11-10 12:00:02] [INFO] [MPROG] action=clear-failure-filter phase=done rc=0"
        )!!
        assertEquals(MaintenanceProgress.PHASE_DONE, p.phase)
        assertEquals(mapOf("rc" to "0"), p.extras)
        assertNull(p.percent)
    }

    @Test fun terminalFailedLineCarriesRcInExtras() {
        val p = parseMaintenanceProgress("[MPROG] action=recover-database phase=failed rc=1")!!
        assertEquals(MaintenanceProgress.PHASE_FAILED, p.phase)
        assertEquals("1", p.extras["rc"])
    }

    @Test fun nonProgressLinesAreIgnored() {
        val ordinary = listOf(
            "[2026-11-10 12:00:01] [INFO] Checking database integrity: data/torrents.db",
            "[2026-11-10 12:00:01] [INFO] recover: copied 11210522 path prefixes",
            "[2026-11-10 12:00:01] [WARN] something went wrong",
            "",
            "   ",
            "MPROG action=check-database phase=integrity_check", // brackets are the token
            "[INFO] maintenance starting",
        )
        for (line in ordinary) assertNull(line, parseMaintenanceProgress(line))
    }

    @Test fun garbageNeverThrows() {
        val garbage = listOf(
            "[MPROG]",
            "[MPROG]    ",
            "[MPROG] action=",
            "[MPROG] action= phase=",
            "[MPROG] action=check-database", // no phase: not displayable progress
            "[MPROG] phase=integrity_check", // no action
            "[MPROG] = = =",
            "[MPROG] =value action=x phase=y",
            "[MPROG] action=x phase=y done=abc total=xyz pct=zz",
            "[MPROG] action=x phase=y done=-5 total=0 pct=-3",
            "[MPROG] action=x phase=y pct=140",
            "[MPROG] action=ünïcode phase=slüg",
            "[MPROG] action=x phase=y done=1 total=2 pct=1 extra-without-equals",
        )
        for (line in garbage) parseMaintenanceProgress(line) // must not throw
        assertNull(parseMaintenanceProgress("[MPROG]"))
        assertNull(parseMaintenanceProgress("[MPROG] action= phase="))
        assertNull(parseMaintenanceProgress("[MPROG] action=check-database"))
        assertNull(parseMaintenanceProgress("[MPROG] = = ="))
    }

    @Test fun percentIsClampedTo0Through100() {
        val high = parseMaintenanceProgress("[MPROG] action=a phase=b done=9 total=10 pct=140")!!
        assertEquals(100, high.percent)
        val low = parseMaintenanceProgress("[MPROG] action=a phase=b done=1 total=10 pct=-3")!!
        assertEquals(0, low.percent)
    }

    @Test fun percentIsDerivedWhenTheEmitterOmitsIt() {
        val p = parseMaintenanceProgress("[MPROG] action=movie-kind-bench phase=encode done=50 total=200")!!
        assertEquals(25, p.percent)
        assertEquals(50L, p.done)
        assertEquals(200L, p.total)
    }

    @Test fun percentWithoutADenominatorIsDropped() {
        // Contradicts the contract (pct only with a total); honesty wins.
        val p = parseMaintenanceProgress("[MPROG] action=a phase=b pct=42")!!
        assertNull(p.total)
        assertNull(p.percent)
    }

    @Test fun doneBeyondTotalKeepsDoneOnly() {
        val p = parseMaintenanceProgress("[MPROG] action=a phase=b done=11 total=10 pct=110")!!
        assertEquals(11L, p.done)
        assertNull(p.total)
        assertNull(p.percent)
    }

    @Test fun extrasCarryActionSpecificKeysAndDropKnownOnes() {
        val p = parseMaintenanceProgress(
            "[2026-11-10 12:00:03] [INFO] [MPROG] action=recover-database phase=torrents " +
                "done=500000 total=6098645 pct=8 table=torrents removed=0"
        )!!
        assertEquals(mapOf("table" to "torrents", "removed" to "0"), p.extras)
    }

    @Test fun toleratesRunsOfWhitespace() {
        val p = parseMaintenanceProgress("[MPROG]   action=a\tphase=b   done=1  total=4 pct=25")!!
        assertEquals("a", p.action)
        assertEquals("b", p.phase)
        assertEquals(25, p.percent)
    }
}
