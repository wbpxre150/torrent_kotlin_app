package com.torrent.app.wizard

/**
 * Pure navigation for the first-run wizard: which steps exist, what Next/Back do at each
 * boundary, the "Step N of 5" indicator, and whether the current step may advance.
 *
 * No Compose and no Android dependencies — the flow composables only render what these
 * functions return, so the navigation rules are unit-tested without a device.
 */
object WizardNav {

    /** The steps in display order (also the source of the "of 5"). */
    val steps: List<WizardStep> = WizardStep.entries

    /** The screen the wizard starts on. */
    val first: WizardScreenKind = WizardStep.entries.first().kind

    /**
     * The screen Next leads to.
     *
     * The last step has no successor: finishing the wizard is an explicit action
     * ("Finish setup" / "Finish without downloading"), not a Next. The progress screen
     * likewise stays put — it leaves via auto-advance on SUCCESS or an explicit skip.
     */
    fun next(kind: WizardScreenKind): WizardScreenKind = when (kind) {
        WizardScreenKind.Progress -> WizardScreenKind.Progress
        is WizardScreenKind.Step -> {
            val index = steps.indexOf(kind.step)
            if (index >= 0 && index < steps.lastIndex) steps[index + 1].kind else kind
        }
    }

    /**
     * The screen Back leads to. Back on the first step is a no-op (the activity's own back
     * handling stays in charge there); Back on the progress screen returns to the
     * DB-download step that started the restore.
     */
    fun back(kind: WizardScreenKind): WizardScreenKind = when (kind) {
        WizardScreenKind.Progress -> WizardStep.DB_DOWNLOAD.kind
        is WizardScreenKind.Step -> {
            val index = steps.indexOf(kind.step)
            if (index > 0) steps[index - 1].kind else kind
        }
    }

    /** 1-based number of the step a screen belongs to (the progress screen is the last step's). */
    fun stepNumber(kind: WizardScreenKind): Int = when (kind) {
        WizardScreenKind.Progress -> steps.size
        is WizardScreenKind.Step -> steps.indexOf(kind.step) + 1
    }

    /** The "Step N of 5" indicator shown in the flow's header. */
    fun stepIndicator(kind: WizardScreenKind): String = "Step ${stepNumber(kind)} of ${steps.size}"

    /**
     * The screen as the wizard draft stores it: the 0-based step index, or [steps]`.size` for
     * the restore progress screen. Pure and total, so a persisted draft round-trips without
     * the store ever holding a wizard type.
     */
    fun screenIndex(kind: WizardScreenKind): Int = when (kind) {
        WizardScreenKind.Progress -> steps.size
        is WizardScreenKind.Step -> steps.indexOf(kind.step)
    }

    /**
     * Inverse of [screenIndex]. An out-of-range token falls back to [first] instead of
     * throwing: a draft written by a build with a different step list must not brick the
     * wizard, it just resumes at the beginning.
     */
    fun screenFromIndex(index: Int): WizardScreenKind = when {
        index == steps.size -> WizardScreenKind.Progress
        else -> steps.getOrNull(index)?.kind ?: first
    }

    /**
     * Whether the current step may advance to the next one.
     *
     * Every step needs a clean validation state; the USB step additionally requires the
     * granted SAF access (the old wizard's required-field gating, which made "Finish setup"
     * unavailable until the drive was granted).
     */
    fun canProceed(step: WizardStep, usbGranted: Boolean, errors: List<String> = emptyList()): Boolean =
        errors.isEmpty() && (step != WizardStep.USB || usbGranted)
}
