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
}
