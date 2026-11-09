package com.torrent.app

/** Phase of the crawler settings-apply job (plan 10 §1). */
enum class CrawlerApplyPhase { IDLE, STOPPING, WRITING, STARTING, WATCHING, OK, ROLLED_BACK, FAILED;

    /** True while the apply job is doing work (used to refuse maintenance). */
    val isActive: Boolean get() = this in setOf(STOPPING, WRITING, STARTING, WATCHING)

    /** True once the job reached a phase it never leaves (OK/ROLLED_BACK/FAILED). */
    val isTerminal: Boolean get() = this in setOf(OK, ROLLED_BACK, FAILED)
}

/**
 * Observable status of the crawler settings-apply job. The job runs on the
 * crawler-control executor and its entry point returns immediately; the
 * control page polls this object (via the `crawlerApplyStatus` lambda on
 * `ControlServer`) and the settings websocket pushes it once per second.
 *
 * `DaemonService.setCrawlerApplyStatus` is the **single write point**: it is
 * the only place that bumps [seq], stamps [startedAt] on the first phase of a
 * run, and stamps [finishedAt]/[terminal] on a terminal phase. [seq] therefore
 * makes the write order observable, so a client can drop an out-of-order frame
 * instead of rendering a stale phase.
 */
data class CrawlerApplyStatus(
    val phase: CrawlerApplyPhase = CrawlerApplyPhase.IDLE,
    val message: String = "",
    val logTail: List<String> = emptyList(),
    /** Monotonic write counter; 0 until the first status write. */
    val seq: Long = 0L,
    /** Epoch millis of the current run's first phase (null before any run). */
    val startedAt: Long? = null,
    /** Epoch millis of the terminal phase (null while a run is in flight). */
    val finishedAt: Long? = null,
    /** The job reached OK/ROLLED_BACK/FAILED and will not advance again. */
    val terminal: Boolean = false,
    /** The crawler was idle for lack of wifi at the time of the last write. */
    val pausedNoWifi: Boolean = false,
)
