package com.torrent.app.wizard

/**
 * The first-run wizard, one screen per step, in the order they are shown.
 *
 * [title] is the step's screen heading (and the uiautomator-visible title on device).
 * [isSkippable] marks the optional sections: FTP, the crawler and the DB download can be
 * left at their stored values (a fresh install keeps `ConfigDefaults`), while the USB grant
 * and the Transmission credentials are required — the same gating the old single-form
 * wizard applied to its "Finish setup" button (`safStatus.granted`).
 */
enum class WizardStep(val title: String, val isSkippable: Boolean) {
    USB("USB drive", isSkippable = false),
    TRANSMISSION("Transmission", isSkippable = false),
    FTP("FTP (Kodi)", isSkippable = true),
    CRAWLER("DHT crawler", isSkippable = true),
    DB_DOWNLOAD("Database download", isSkippable = true),
    ;

    /** This step as a screen kind, for [WizardNav] / the flow's `screen` state. */
    val kind: WizardScreenKind get() = WizardScreenKind.Step(this)
}

/**
 * What the wizard is currently showing: one of the five [WizardStep]s, or the dedicated
 * DB-download progress screen.
 *
 * The progress screen is deliberately **not** a sixth step — it is entered by starting a
 * restore from the DB-download step and leaves by a SUCCESS (auto-advance to the main
 * screen) or an explicit skip, so it must not count towards "Step N of 5".
 */
sealed interface WizardScreenKind {
    /** One step's form. */
    data class Step(val step: WizardStep) : WizardScreenKind

    /** The restore progress screen that follows "Start download". */
    data object Progress : WizardScreenKind
}

/** The step this screen belongs to, or null for the progress screen. */
val WizardScreenKind.stepOrNull: WizardStep?
    get() = (this as? WizardScreenKind.Step)?.step
