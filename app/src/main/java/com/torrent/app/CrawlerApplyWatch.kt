package com.torrent.app

import com.torrent.app.config.CrawlerGate

/** Verdicts the post-apply early-exit watch (plan 10 §1) can reach. */
enum class CrawlerWatchVerdict {
    /** Not yet armed: the poll has not observed a launched child (no pid) yet. */
    WAITING,
    /** Launched child observed (pid set); the window is still open. */
    WATCHING,
    /** Crawler exited (and was relaunched) after being observed up — roll back. */
    EARLY_EXIT,
    /** Crawler stopped externally (user / wifi gate) — nothing to judge. */
    STOPPED_EXTERNALLY,
    /** Window over, crawler healthy. */
    OK,
}

/**
 * Pure early-exit watch for the crawler settings apply (plan 10 §1):
 * [DaemonService] feeds the crawler supervisor's [ProcessStatus] at each
 * poll (~2 s) and acts on the verdict. Kept free of Android/coroutine
 * dependencies so it is unit-testable in plain JUnit (like [RollbackPolicy]).
 *
 * `ProcessSupervisor` publishes the child's `pid` at fork and bumps
 * `restartCount` on every (re)launch; with a readiness probe wired it stays
 * in STARTING until the daemon's RPC answers (and without one it publishes
 * RUNNING immediately at the fork). The watch therefore judges ONLY by real
 * process exits: it ARMS at the first poll that observes the launched
 * process — `pid != null`, whether STARTING or RUNNING — and uses that
 * launch's `restartCount` as the baseline for "exited during the watch".
 * Snapshotting `restartCount` immediately after `start()` (as the previous
 * implementation did) reads the pre-launch count — the run loop has not
 * forked the child yet — so the launch's own bump looked like an early exit
 * and every apply that started the crawler was rolled back (taking
 * `enabled=true` with it, which also stopped the crawler from auto-loading
 * with the app).
 *
 * A slow start is fine: a crawler that lingers in STARTING for minutes while
 * its RPC/HTTP API comes up (large DB load before the API answers) is a
 * healthy slow start, not an early exit — `restartCount` only moves when a
 * process actually exits. Only a window that never observes a pid at all
 * (launch failed and kept backing off, or the crawler kept exiting between
 * polls) is judged an early exit, at the deadline.
 */
class CrawlerApplyWatch {

    /** restartCount observed when the launched child was first seen; null until then. */
    private var armedRestarts: Int? = null

    /**
     * Feed the crawler supervisor's status at this poll.
     */
    fun onPoll(current: ProcessStatus): CrawlerWatchVerdict {
        if (current.state == ProcessState.STOPPED || current.state == ProcessState.STOPPING) {
            return CrawlerWatchVerdict.STOPPED_EXTERNALLY
        }
        val armed = armedRestarts
        if (armed == null) {
            if (current.pid != null) {
                // The run loop forked a child: this launch is the baseline.
                armedRestarts = current.restartCount
                return CrawlerWatchVerdict.WATCHING
            }
            // Not launched yet: the run loop is still starting (or backing off
            // after a failed launch). Exits before the first observation are
            // caught by [onDeadline].
            return CrawlerWatchVerdict.WAITING
        }
        return if (CrawlerGate.exitedDuringWatch(armed, current.restartCount)) {
            CrawlerWatchVerdict.EARLY_EXIT
        } else {
            CrawlerWatchVerdict.WATCHING
        }
    }

    /**
     * Verdict when the watch window expired. A window that never observed a
     * launched child (no pid) counts as an early exit: the crawler kept
     * exiting between polls or never launched at all. Once a launch was
     * observed, only a `restartCount` move after arming is an exit — a child
     * that is still alive stays OK even if it is still STARTING (a slow DB
     * load awaiting RPC readiness is not a crash).
     */
    fun onDeadline(current: ProcessStatus): CrawlerWatchVerdict {
        if (current.state == ProcessState.STOPPED || current.state == ProcessState.STOPPING) {
            return CrawlerWatchVerdict.STOPPED_EXTERNALLY
        }
        val armed = armedRestarts
        return when {
            armed == null -> CrawlerWatchVerdict.EARLY_EXIT
            CrawlerGate.exitedDuringWatch(armed, current.restartCount) -> CrawlerWatchVerdict.EARLY_EXIT
            else -> CrawlerWatchVerdict.OK
        }
    }
}
