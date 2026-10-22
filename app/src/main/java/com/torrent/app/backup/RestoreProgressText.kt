package com.torrent.app.backup

/**
 * Pure "N of M objects imported" label for the wizard's restore section (section 6).
 *
 * Both counts are nullable in [DbRestoreStatus]: [DbRestoreStatus.objectsDone] stays
 * null until the object count is published, and [DbRestoreStatus.objectCount] stays
 * null until the dump chain has been listed. Whenever either is unknown there is no
 * honest progress to show (IDLE / NOT_CONFIGURED / REFUSED, or a RUNNING restore that
 * is still listing), so [format] returns null and callers render nothing.
 */
object RestoreProgressText {
    fun format(objectsDone: Int?, objectCount: Int?): String? =
        if (objectsDone == null || objectCount == null) null
        else "$objectsDone of $objectCount objects imported"
}
