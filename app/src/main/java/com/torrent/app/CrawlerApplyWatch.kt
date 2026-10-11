package com.torrent.app

import com.torrent.app.config.CrawlerGate

/** Verdicts the post-apply early-exit watch (plan 10 §1) can reach. */
enum class CrawlerWatchVerdict {
    /** Crawler launched but not yet observed RUNNING (still starting). */
    WAITING,
    /** Crawler observed RUNNING; the window is still open. */
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
 * `ProcessSupervisor` reports RUNNING as soon as the child process is
 * forked and bumps `restartCount` on every (re)launch. The baseline for
 * "exited during the watch" must therefore be the restartCount **of the
 * launch this apply started**, which the watch ARMS at the first poll that
 * observes the crawler RUNNING. Snapshotting `restartCount` immediately
 * after `start()` (as the previous implementation did) reads the
 * pre-launch count — the run loop has not forked the child yet — so the
 * launch's own bump looked like an early exit and every apply that started
 * the crawler was rolled back (taking `enabled=true` with it, which also
 * stopped the crawler from auto-loading with the app).
 *
 * A slow start is fine: with a large DB the crawler takes a while before
 * it is functional (its HTTP API comes up after the DB is opened), but
 * `restartCount` only moves when a process actually exits.
 */
class CrawlerApplyWatch {

    /** restartCount observed when the crawler was first seen RUNNING; null until then. */
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
            if (current.state == ProcessState.RUNNING) {
                armedRestarts = current.restartCount
                return CrawlerWatchVerdict.WATCHING
            }
            // Not RUNNING yet: the run loop is still launching (or backing
            // off after a failed launch). Exits before the first RUNNING
            // observation are caught by [onDeadline].
            return CrawlerWatchVerdict.WAITING
        }
        return if (CrawlerGate.exitedDuringWatch(armed, current.restartCount)) {
            CrawlerWatchVerdict.EARLY_EXIT
        } else {
            CrawlerWatchVerdict.WATCHING
        }
    }

    /**
     * Verdict when the watch window expired. A crawler that was never
     * observed RUNNING counts as an early exit: a healthy launch reports
     * RUNNING (process forked) within one poll, so never seeing it means it
     * kept exiting between polls or never launched at all.
     */
    fun onDeadline(current: ProcessStatus): CrawlerWatchVerdict {
        if (current.state == ProcessState.STOPPED || current.state == ProcessState.STOPPING) {
            return CrawlerWatchVerdict.STOPPED_EXTERNALLY
        }
        val armed = armedRestarts
        return when {
            armed == null -> CrawlerWatchVerdict.EARLY_EXIT
            CrawlerGate.exitedDuringWatch(armed, current.restartCount) -> CrawlerWatchVerdict.EARLY_EXIT
            current.state != ProcessState.RUNNING -> CrawlerWatchVerdict.EARLY_EXIT
            else -> CrawlerWatchVerdict.OK
        }
    }
}
