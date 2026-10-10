package com.torrent.app

/** Phase of the crawler settings-apply job (plan 10 §1). */
enum class CrawlerApplyPhase { IDLE, STOPPING, WRITING, STARTING, WATCHING, OK, ROLLED_BACK, FAILED;

    /** True while the apply job is doing work (used to refuse maintenance). */
    val isActive: Boolean get() = this in setOf(STOPPING, WRITING, STARTING, WATCHING)
}

/**
 * Observable status of the crawler settings-apply job. The job runs on the
 * crawler-control executor and its entry point returns immediately; the
 * control page polls this object (via the `crawlerApplyStatus` lambda on
 * `ControlServer`).
 */
data class CrawlerApplyStatus(
    val phase: CrawlerApplyPhase = CrawlerApplyPhase.IDLE,
    val message: String = "",
    val logTail: List<String> = emptyList(),
)
