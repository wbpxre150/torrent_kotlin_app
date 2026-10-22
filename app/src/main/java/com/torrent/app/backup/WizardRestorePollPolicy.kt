package com.torrent.app.backup

/**
 * Pure decisions for the wizard's **service-anchored** restore card (section 6).
 *
 * The restore itself runs in `DaemonService` for hours, so the card may not treat its
 * own copy of the status as the truth: an activity recreated mid-restore gets a fresh
 * `WizardRestoreViewModel` whose status is `null`, and a poll loop keyed on that local
 * copy never starts — the card then showed no status at all, reset the switch OFF and
 * kept "Finish setup" locked even after a SUCCESS. Every decision below therefore works
 * off the snapshot *adopted from the service*, which is why they are pure and testable.
 *
 * Nothing here decides whether a restore may finish: that stays [WizardRestoreGate]'s
 * SUCCESS-only rule.
 */
object WizardRestorePollPolicy {

    /**
     * Merge one service snapshot into the card state.
     *
     * The service is authoritative whenever it knows about an attempt (running or any
     * terminal outcome), so a stale local RUNNING is dropped once the service says nothing
     * is in flight, and an outcome that landed while the activity was dead is picked up.
     * A service that has never seen an attempt in this process (a fresh/restarted service
     * reports [DbRestoreState.IDLE]) must not erase a verdict this session produced, so the
     * local state is kept instead. `null` means "no attempt known", which keeps the card's
     * status block hidden.
     *
     * [invalidated] is the one exception: when an edit to a section-5 field (or a rejected
     * save) deliberately drops a terminal verdict, the service keeps reporting that same
     * snapshot, and re-adopting it would unlock "Finish setup" for values that were never
     * restored from. Exactly that snapshot is therefore ignored; anything newer — including a
     * restore still in flight — is adopted normally.
     */
    fun adopt(
        local: DbRestoreStatus?,
        service: DbRestoreStatus?,
        invalidated: DbRestoreStatus? = null,
    ): DbRestoreStatus? = when {
        service == null -> local
        // This exact snapshot was discarded on purpose this session — do not resurrect it.
        service == invalidated -> local
        attemptSeen(service) -> service
        // Local RUNNING with nothing in flight in the service: the restore can only run
        // there, so this copy is stale — dropping it also stops the poll loop.
        local != null && local.running -> null
        else -> local
    }

    /** True when [status] describes a restore attempt of this session (running or terminal). */
    fun attemptSeen(status: DbRestoreStatus?): Boolean =
        status != null && (status.running || status.state != DbRestoreState.IDLE)

    /** The poll loop runs only while the ADOPTED snapshot reports a restore in flight. */
    fun shouldPoll(adopted: DbRestoreStatus?): Boolean = adopted?.running == true

    /**
     * The section-6 switch: a restore in flight owns the flag (re-attached ON on adoption),
     * so it must not be switched off mid-restore; FAILED/IDLE leave it editable again.
     */
    fun restoreSwitchEnabled(adopted: DbRestoreStatus?): Boolean = adopted?.running != true

    /** "Restore now": bound, switched on, and no restore already in flight. */
    fun restoreNowEnabled(
        bound: Boolean,
        restoreEnabled: Boolean,
        adopted: DbRestoreStatus?,
    ): Boolean = bound && restoreEnabled && adopted?.running != true
}
