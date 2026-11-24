package com.torrent.app.config

/**
 * A snapshot of the first-run wizard's **in-progress** form.
 *
 * The wizard's fields live in an activity-scoped `WizardFormViewModel`, which survives a
 * rotation but dies with the activity and the process. That is right for the sections a Next
 * tap already committed, and wrong for the ones being typed: switching to another app to copy
 * the R2 credentials out of a password manager (or any process kill, or an activity the OS
 * reclaims while it is stopped) restarted the wizard on step 1 with an empty form, losing the
 * pasted credentials. The flow therefore writes this draft on every change and the ViewModel
 * restores it on construction, so leaving the app resumes on the same step with the same
 * values.
 *
 * [screenIndex] is `WizardNav.screenIndex`'s token — the 0-based step index, or the step count
 * for the restore progress screen. Nothing here is a wizard type, so this stays a plain data
 * holder in the config package and [ConfigStore] can implement [WizardDraftStore] without
 * depending on the wizard package.
 *
 * Deliberately **not** part of the draft: `wizardCompleted` (persisted on its own) and the
 * restore status (owned by the service, adopted by the progress screen).
 */
data class WizardDraft(
    val screenIndex: Int = 0,
    // Step 2: Transmission
    val txUser: String = "",
    val txPass: String = "",
    val peerPort: String = "",
    val dlSubpath: String = "",
    // Step 3: FTP
    val ftpUser: String = "",
    val ftpPass: String = "",
    val ftpPort: String = "",
    val ftpWifiOnly: Boolean = false,
    // Step 4: crawler
    val crawlerEnabled: Boolean = false,
    val crawlerAdultDrop: Boolean = false,
    val saveCategories: Set<String> = emptySet(),
    // Step 5: DB download (R2)
    val r2Endpoint: String = "",
    val r2Bucket: String = "",
    val r2ObjectKey: String = "",
    val r2AccessKeyId: String = "",
    val r2SecretAccessKey: String = "",
    val uploadEnabled: Boolean = false,
    val dbBackupEnabled: Boolean = false,
    val r2DbBucket: String = "",
    val r2DbObjectPrefix: String = "",
    val bootstrapEnabled: Boolean = false,
    val bootstrapUrl: String = "",
)

/**
 * Where the in-progress wizard draft lives.
 *
 * [ConfigStore] is the real implementation (a dedicated `wizard_draft` SharedPreferences file,
 * so clearing it after setup is one `clear()` and a finished wizard leaves no secret behind);
 * the wizard's tests use an in-memory fake. The default in `WizardFormViewModel` remembers
 * nothing, which is what a ViewModel built without persistence should do.
 */
interface WizardDraftStore {
    /** The saved draft, or null when no wizard is in progress. */
    fun loadWizardDraft(): WizardDraft?

    /** Replaces the saved draft with [draft]. */
    fun saveWizardDraft(draft: WizardDraft)

    /** Forgets the draft: the wizard is finished, so there is nothing left to resume. */
    fun clearWizardDraft()
}

/** A store that remembers nothing — the default for a form built without persistence. */
object NoWizardDraftStore : WizardDraftStore {
    override fun loadWizardDraft(): WizardDraft? = null
    override fun saveWizardDraft(draft: WizardDraft) = Unit
    override fun clearWizardDraft() = Unit
}
