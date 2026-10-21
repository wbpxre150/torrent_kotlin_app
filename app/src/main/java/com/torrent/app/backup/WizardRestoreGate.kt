package com.torrent.app.backup

/**
 * Pure "Finish setup" gate for the wizard's restore section (section 6).
 *
 * The restore switch is in-session only: when it is ON the wizard may not be
 * finished until a real restore has actually [DbRestoreState.SUCCESS]. Every
 * other state — no attempt yet (`null`/[DbRestoreState.IDLE]), a restore in
 * flight, a failure, a refusal, or a manager that was never configured —
 * keeps the button disabled.
 */
object WizardRestoreGate {
    fun canFinish(restoreEnabled: Boolean, state: DbRestoreState?): Boolean =
        !restoreEnabled || state == DbRestoreState.SUCCESS
}
