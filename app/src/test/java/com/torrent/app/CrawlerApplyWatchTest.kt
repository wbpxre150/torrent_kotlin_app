package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the post-apply early-exit watch (plan 10 §1).
 *
 * The watch must ARM on the first RUNNING observation — the restartCount of
 * the launch the apply started — and must not treat that launch's own
 * restartCount bump as an early exit. The previous implementation
 * snapshotted restartCount immediately after `start()`, before the
 * supervisor's run loop had forked the child, so the launch's own bump
 * looked like a crash and every apply that started the crawler was rolled
 * back (together with `enabled=true`).
 */
class CrawlerApplyWatchTest {

    private fun status(state: ProcessState, restarts: Int) =
        ProcessStatus("dht_crawler", state, restartCount = restarts)

    @Test fun healthySlowStart_noRollback() {
        val w = CrawlerApplyWatch()
        // Poll 1: the run loop has not forked the child yet.
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5)))
        // Poll 2+: child launched (restartCount bumped 5→6); the crawler is
        // still opening its large DB but that never moves restartCount.
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        assertEquals(CrawlerWatchVerdict.OK, w.onDeadline(status(ProcessState.RUNNING, 6)))
    }

    @Test fun firstLaunchBump_neverCountsAsExit() {
        // The exact regression: a pre-launch snapshot (restartCount 5) made
        // the launch's own bump (6) look like an exit within 2 s.
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        assertEquals(CrawlerWatchVerdict.OK, w.onDeadline(status(ProcessState.RUNNING, 6)))
    }

    @Test fun exitAfterArming_rollsBack() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        // Crashed; the supervisor relaunched (restartCount bumped again).
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onPoll(status(ProcessState.RUNNING, 7)))
    }

    @Test fun exitCaughtEvenWhileBackingOff() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        // Crashed; the next poll lands in the restart backoff (STARTING),
        // but the restartCount bump is still visible.
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onPoll(status(ProcessState.STARTING, 7)))
    }

    @Test fun stoppedExternally_noJudgement() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.STOPPED_EXTERNALLY, w.onPoll(status(ProcessState.STOPPING, 6)))
        assertEquals(CrawlerWatchVerdict.STOPPED_EXTERNALLY, w.onPoll(status(ProcessState.STOPPED, 6)))
        assertEquals(CrawlerWatchVerdict.STOPPED_EXTERNALLY, w.onDeadline(status(ProcessState.STOPPED, 6)))
    }

    @Test fun stoppedExternally_evenAfterArming() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        assertEquals(CrawlerWatchVerdict.STOPPED_EXTERNALLY, w.onPoll(status(ProcessState.STOPPING, 6)))
    }

    @Test fun neverRunningByDeadline_rollsBack() {
        val w = CrawlerApplyWatch()
        // Launch failures (e.g. missing binary) never bump restartCount.
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5)))
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5)))
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onDeadline(status(ProcessState.STARTING, 5)))
    }

    @Test fun relaunchesMissedByPolls_rollsBackAtDeadline() {
        val w = CrawlerApplyWatch()
        // The crawler kept crash-looping between the 2 s polls, which only
        // ever caught the backoff windows. Two launches happened (5 → 7).
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5)))
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 7)))
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onDeadline(status(ProcessState.RUNNING, 7)))
    }
}
