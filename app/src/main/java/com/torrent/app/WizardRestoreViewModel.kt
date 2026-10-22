package com.torrent.app

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.torrent.app.backup.DbRestoreStatus

/**
 * Session-scoped state for wizard section 6 ("Restore crawler DB from R2").
 *
 * The restore gate is deliberately **session-only**: "Finish setup" is unlocked only by a real
 * SUCCESS observed in this session, so a re-shown wizard (or a fresh process) must never inherit
 * a stale "restored" verdict. A plain `remember` loses that on configuration change (rotation),
 * which silently re-locked the gate while the restore itself kept running in the service.
 *
 * Holding the state in a [ViewModel] gives exactly the semantics the plan's Assumptions ask for:
 * it survives configuration changes via the activity's `ViewModelStore`, and dies with the
 * process. Nothing here is persisted to disk.
 *
 * Surviving a configuration change is not enough on its own: a restore runs for hours in the
 * service, so an activity the OS *recreates* mid-restore comes back with a fresh ViewModel and
 * `restoreStatus == null`. The card is therefore service-anchored — [adopt] takes the
 * service's snapshot as the truth (see `WizardRestorePollPolicy`) and the composable pulls one
 * on every composition/resume, so the status, the switch and the buttons re-attach.
 *
 * The properties are exposed as the [androidx.compose.runtime.MutableState] objects themselves so
 * the composable can delegate (`var x by vm.x`) and keep writing through to the same state.
 */
class WizardRestoreViewModel : ViewModel() {
    /** Section-6 switch: "restore the crawler DB from R2". */
    val restoreEnabled = mutableStateOf(false)

    /** Last observed restore status (null = no attempt seen this session). */
    val restoreStatus = mutableStateOf<DbRestoreStatus?>(null)

    /** Rejected-save message from the "Restore now" save step (form error, not a restore outcome). */
    val restoreError = mutableStateOf<String?>(null)

    /** Terminal verdict dropped by an edit/rejected save — never re-adopted; see [adopt]. */
    private var restoreInvalidated: DbRestoreStatus? = null

    /**
     * Editing any field the restore consumes (or a rejected save) invalidates a stale verdict:
     * a SUCCESS would otherwise keep "Finish setup" unlocked for values that were never
     * restored from. A live RUNNING status is never hidden — the poll loop stays attached to it.
     */
    fun invalidate() {
        val current = restoreStatus.value ?: return
        if (current.running) return
        restoreInvalidated = current
        restoreStatus.value = null
    }

    /**
     * Adopt one snapshot pulled from the service's restore-status getter.
     *
     * The service owns the restore, so its snapshot wins (see
     * [com.torrent.app.backup.WizardRestorePollPolicy.adopt]). Adopting a snapshot that
     * carries an attempt also switches section 6 back ON — a recreated wizard would
     * otherwise show the restore switched off while it is still running. The switch is only
     * re-armed when the snapshot actually changed, so a deliberate OFF after a FAILED (the
     * user skipping the restore) is not undone by the next resume. A verdict dropped by
     * [invalidate] is never re-adopted, but any newer snapshot is.
     */
    fun adopt(snapshot: DbRestoreStatus?) {
        val adopted = com.torrent.app.backup.WizardRestorePollPolicy.adopt(
            restoreStatus.value, snapshot, restoreInvalidated)
        if (adopted == restoreStatus.value) return
        restoreStatus.value = adopted
        restoreInvalidated = null
        if (com.torrent.app.backup.WizardRestorePollPolicy.attemptSeen(adopted)) restoreEnabled.value = true
    }
}
