package com.torrent.app

object RollbackPolicy {
    /**
     * Roll back when the daemon has not reached RUNNING and has restarted at least
     * [threshold] times since the apply snapshot was taken.
     */
    fun shouldRollback(statusAtApply: ProcessStatus, current: ProcessStatus, threshold: Int = 3): Boolean {
        val delta = current.restartCount - statusAtApply.restartCount
        return current.state != ProcessState.RUNNING && delta >= threshold
    }
}