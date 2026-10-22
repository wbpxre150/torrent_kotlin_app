package com.torrent.app.wizard

import com.torrent.app.backup.DbRestoreState
import com.torrent.app.backup.DbRestoreStatus

/**
 * How the dedicated download-progress screen should read one adopted [DbRestoreStatus].
 *
 * [ERROR] is the "the attempt did not succeed" bucket the screen colours red and offers
 * Retry for: a failure, a manager that was never configured, and both refusals. [IDLE] is
 * "no attempt known" — the screen is only ever entered by starting a download, so on this
 * screen it means the service lost the attempt (process death) and Retry is the way out.
 */
enum class RestoreStatusKind { IDLE, RUNNING, SUCCESS, ERROR }

/**
 * Pure decisions for the dedicated DB-download progress screen (Stage 4 of the wizard
 * rework): the state-prefixed status line, its severity bucket, and the progress fraction.
 *
 * Kept free of Compose and Android so the awkward cases are unit-tested instead of assumed:
 * [DbRestoreStatus.objectsDone] is NOT monotonic (`DbRestoreManager` restarts from
 * `max(0, marker-3)` on resume) and [DbRestoreStatus.objectCount] counts filter-skipped
 * chunks, so the bar must clamp rather than trust the numbers to be well behaved.
 */
object RestoreProgressUi {

    /** Severity bucket for [status]; [DbRestoreStatus.running] wins, as it does in the manager. */
    fun statusKind(status: DbRestoreStatus): RestoreStatusKind = when {
        status.running -> RestoreStatusKind.RUNNING
        status.state == DbRestoreState.SUCCESS -> RestoreStatusKind.SUCCESS
        status.state == DbRestoreState.FAILED ||
            status.state == DbRestoreState.NOT_CONFIGURED ||
            status.state == DbRestoreState.REFUSED_BUSY ||
            status.state == DbRestoreState.REFUSED_RUNNING -> RestoreStatusKind.ERROR
        else -> RestoreStatusKind.IDLE
    }

    /**
     * The status line, always prefixed with the state so a refusal or a not-configured
     * attempt can never read as success. A RUNNING snapshot is checked first: a
     * mid-restore double-tap answers REFUSED_RUNNING with `running = true` and must read
     * as running, not as failed.
     */
    fun statusText(status: DbRestoreStatus): String =
        when (statusKind(status)) {
            RestoreStatusKind.RUNNING -> "Restore running — ${status.message}"
            RestoreStatusKind.SUCCESS -> "Restore succeeded — ${status.message}"
            RestoreStatusKind.ERROR -> "Restore failed — ${status.message}"
            RestoreStatusKind.IDLE -> "Restore not started — ${status.message}"
        }

    /**
     * The bar's fraction, or null when there is no honest progress to draw: either count
     * unknown (a RUNNING restore that is still listing has no total) or a non-positive
     * total. Clamped to 0..1 because `objectsDone` can overshoot or regress across a
     * resume — the raw counter text is rendered as-is next to it.
     */
    fun progressFraction(objectsDone: Int?, objectCount: Int?): Float? {
        if (objectsDone == null || objectCount == null || objectCount <= 0) return null
        return (objectsDone.toFloat() / objectCount.toFloat()).coerceIn(0f, 1f)
    }
}
