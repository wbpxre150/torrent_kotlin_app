package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the post-apply early-exit watch (plan 10 §1).
 *
 * The watch must ARM on the first poll that observes the launched child
 * (`pid != null`, STARTING or RUNNING) and use that launch's restartCount as
 * the baseline, so the launch's own restartCount bump is never mistaken for
 * an early exit. The previous implementation snapshotted restartCount
 * immediately after `start()`, before the supervisor's run loop had forked the
 * child, so the launch's own bump looked like a crash and every apply that
 * started the crawler was rolled back (together with `enabled=true`).
 *
 * A child that stays STARTING for the whole window (slow DB load before its
 * RPC/HTTP API answers) is a healthy slow start: only real exits — i.e.
 * restartCount movement after arming — roll back.
 */
class CrawlerApplyWatchTest {

    // Tests construct ProcessStatus directly, so a pid can be faked even on the
    // JVM (the pid limitation only applies to real spawned processes).
    private fun status(state: ProcessState, restarts: Int, pid: Int? = FAKE_PID) =
        ProcessStatus("dht_crawler", state, pid = pid, restartCount = restarts)

    @Test fun slowStart_neverReady_deadlineOk() {
        val w = CrawlerApplyWatch()
        // Forked (pid published) but the RPC/HTTP API is still loading a large
        // DB: STARTING for the whole 30 s window with a stable restartCount.
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        assertEquals(CrawlerWatchVerdict.OK, w.onDeadline(status(ProcessState.STARTING, 6)))
    }

    @Test fun slowStart_thenReadyBeforeDeadline_ok() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        assertEquals(CrawlerWatchVerdict.OK, w.onDeadline(status(ProcessState.RUNNING, 6)))
    }

    @Test fun waitingUntilForked_thenArms() {
        val w = CrawlerApplyWatch()
        // Poll 1: the run loop has not forked the child yet (no pid).
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5, pid = null)))
        // Poll 2: forked (restartCount bumped 5→6) — this launch is the baseline.
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        assertEquals(CrawlerWatchVerdict.OK, w.onDeadline(status(ProcessState.STARTING, 6)))
    }

    @Test fun firstLaunchBump_neverCountsAsExit() {
        // The exact old regression: a pre-launch snapshot (restartCount 5) made
        // the launch's own bump (6) look like an exit within 2 s.
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5, pid = null)))
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        assertEquals(CrawlerWatchVerdict.OK, w.onDeadline(status(ProcessState.RUNNING, 6)))
    }

    @Test fun runningAtFirstPoll_fastPath_stillOk() {
        // Old fast behavior (no readiness probe): RUNNING arrives at the fork.
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        assertEquals(CrawlerWatchVerdict.OK, w.onDeadline(status(ProcessState.RUNNING, 6)))
    }

    @Test fun crashLoopDuringWindow_earlyExitAtPoll() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        // Crashed and was relaunched: restartCount bumped again.
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onPoll(status(ProcessState.STARTING, 7)))
    }

    @Test fun exitCaughtEvenWhileBackingOff() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.RUNNING, 6)))
        // Crashed; the next poll lands in the restart backoff (STARTING, no pid),
        // but the restartCount bump is still visible.
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onPoll(status(ProcessState.STARTING, 7, pid = null)))
    }

    @Test fun crashBeforeFirstObservation_relaunchCaughtAtDeadline() {
        val w = CrawlerApplyWatch()
        // The pre-launch poll: nothing forked yet.
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5, pid = null)))
        // First launch forked → armed at 6.
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        // That launch exits and is relaunched between polls; only the deadline sees it.
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onDeadline(status(ProcessState.STARTING, 7)))
    }

    @Test fun crashBeforeAnyObservation_earlyExitAtDeadline() {
        val w = CrawlerApplyWatch()
        // Never armed: the crawler crashed before the first poll and the relaunch
        // is still in its backoff at the deadline.
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5, pid = null)))
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5, pid = null)))
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onDeadline(status(ProcessState.STARTING, 7, pid = null)))
    }

    @Test fun neverForked_launchFailed_earlyExitAtDeadline() {
        val w = CrawlerApplyWatch()
        // Launch failures (e.g. missing binary) never bump restartCount and never
        // set a pid, so the watch never arms.
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5, pid = null)))
        assertEquals(CrawlerWatchVerdict.WAITING, w.onPoll(status(ProcessState.STARTING, 5, pid = null)))
        assertEquals(CrawlerWatchVerdict.EARLY_EXIT, w.onDeadline(status(ProcessState.STARTING, 5, pid = null)))
    }

    @Test fun stoppedExternally_noJudgement() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.STOPPED_EXTERNALLY, w.onPoll(status(ProcessState.STOPPING, 6)))
        assertEquals(CrawlerWatchVerdict.STOPPED_EXTERNALLY, w.onPoll(status(ProcessState.STOPPED, 6, pid = null)))
        assertEquals(
            CrawlerWatchVerdict.STOPPED_EXTERNALLY,
            w.onDeadline(status(ProcessState.STOPPED, 6, pid = null)))
    }

    @Test fun stoppedExternally_midWatch_noJudgement() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        assertEquals(CrawlerWatchVerdict.STOPPED_EXTERNALLY, w.onPoll(status(ProcessState.STOPPING, 6)))
    }

    @Test fun stoppedExternally_atDeadline_noJudgement() {
        val w = CrawlerApplyWatch()
        assertEquals(CrawlerWatchVerdict.WATCHING, w.onPoll(status(ProcessState.STARTING, 6)))
        assertEquals(
            CrawlerWatchVerdict.STOPPED_EXTERNALLY,
            w.onDeadline(status(ProcessState.STOPPED, 6, pid = null)))
    }

    private companion object {
        const val FAKE_PID = 4242
    }
}
