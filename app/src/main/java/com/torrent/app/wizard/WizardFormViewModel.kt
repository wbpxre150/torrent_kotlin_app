package com.torrent.app.wizard

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.torrent.app.config.AppConfig
import com.torrent.app.config.ConfigDefaults
import com.torrent.app.config.NoWizardDraftStore
import com.torrent.app.config.WizardDraft
import com.torrent.app.config.WizardDraftStore

/** Fresh-install pre-check of the wizard's categories step: media only. */
internal val WizardDefaultSaveCategories = listOf("movie", "tv", "anime", "music")

/**
 * Blank secret keeps the stored one (same rule as the dashboard form), so re-showing the
 * wizard with the upload/bootstrap switches enabled cannot fail on a secret the user never
 * retypes. Pure so the fallback is unit-tested without a store or a service.
 */
internal fun effectiveSecret(input: String, stored: String): String =
    if (input.isBlank()) stored else input

/**
 * Every editable field of the first-run wizard, plus the screen it is showing and the
 * per-step validation messages.
 *
 * These used to be `remember` vars inside the single scrolling `WizardScreen`, which lost
 * every value on a configuration change (rotation mid-setup wiped the form). Hoisting them
 * into an activity-scoped [ViewModel] gives the same contract as
 * [com.torrent.app.WizardRestoreViewModel]: values survive rotation via the activity's
 * `ViewModelStore` and die with the activity or the process. What dies with them is recovered
 * from the [WizardDraft] the flow keeps in a [WizardDraftStore], so leaving the app mid-setup
 * (to copy the R2 credentials out of a password manager, or because the OS reclaimed a stopped
 * activity or the whole process) resumes on the same step with the same values instead of
 * restarting on step 1 with an empty form. The steps still persist their own committed section
 * when the user taps Next.
 *
 * The fields start at the same fallbacks the old `remember` initialisers used and are then
 * seeded from the stored config, which can arrive in either of two ways:
 *  - [cfg] is non-null when the service binder was already connected on the first
 *    composition (the wizard used to be reachable only that way) — seeded here in `init`;
 *  - the binder connects LATER, now that the activity reads `wizardCompleted` synchronously
 *    from the store and can therefore show step 1 before the service is up. The flow then
 *    calls [seedIfNeeded] once with the freshly loaded config.
 *
 * The late seed is deliberately conservative: it only fills fields that still hold their
 * fallback, so anything the user managed to type in the meantime is never clobbered, and it
 * runs at most once (a field the user later resets to its default is not re-seeded).
 *
 * The properties are exposed as the [androidx.compose.runtime.MutableState] objects
 * themselves so a composable can delegate (`var txUser by vm.txUser`) and keep writing
 * through to the same state.
 */
class WizardFormViewModel(
    cfg: AppConfig? = null,
    private val draftStore: WizardDraftStore = NoWizardDraftStore,
) : ViewModel() {

    /** The screen currently shown; the wizard starts on the USB step. */
    val screen = mutableStateOf(WizardNav.first)

    /**
     * Set once the wizard has been finished (by the download's auto-advance or by "Finish
     * without downloading"), so finishing can never run twice: `completeWizard()` starts the
     * control server, the crawler proxy and the daemons, and a second call would restart
     * them. Activity-scoped like the rest of the form — it survives a rotation and dies with
     * the process, where a fresh wizard legitimately starts over.
     */
    val wizardFinished = mutableStateOf(false)

    // --- Step 2: Transmission -------------------------------------------------------------

    val txUser = mutableStateOf(ConfigDefaults.TRANSMISSION_USERNAME)
    val txPass = mutableStateOf("")
    val peerPort = mutableStateOf(ConfigDefaults.TRANSMISSION_PEER_PORT.toString())
    val dlSubpath = mutableStateOf("")

    // --- Step 3: FTP (Kodi) ---------------------------------------------------------------

    val ftpUser = mutableStateOf(ConfigDefaults.FTP_USERNAME)
    val ftpPass = mutableStateOf("")
    val ftpPort = mutableStateOf(ConfigDefaults.FTP_PORT.toString())
    val ftpWifiOnly = mutableStateOf(ConfigDefaults.FTP.wifiOnly)

    // --- Step 4: DHT crawler --------------------------------------------------------------

    val crawlerEnabled = mutableStateOf(false)
    val crawlerAdultDrop = mutableStateOf(false)

    /**
     * Non-empty store value wins; an empty list means "save all", in which case the wizard
     * shows (and therefore writes) the media-only fresh-install default.
     */
    val saveCategories = mutableStateOf(WizardDefaultSaveCategories.toSet())

    // --- Step 5: DB download (R2) ---------------------------------------------------------

    val r2Endpoint = mutableStateOf("")
    val r2Bucket = mutableStateOf("")
    val r2ObjectKey = mutableStateOf(ConfigDefaults.BACKUP_OBJECT_KEY)
    val r2AccessKeyId = mutableStateOf("")
    /** Never seeded from the store: the form only writes a secret the user actually typed. */
    val r2SecretAccessKey = mutableStateOf("")
    val uploadEnabled = mutableStateOf(false)
    val dbBackupEnabled = mutableStateOf(false)
    val r2DbBucket = mutableStateOf(ConfigDefaults.DB_BACKUP_BUCKET)
    val r2DbObjectPrefix = mutableStateOf(ConfigDefaults.DB_BACKUP_OBJECT_PREFIX)
    val bootstrapEnabled = mutableStateOf(false)
    val bootstrapUrl = mutableStateOf("")

    // --- Seeding --------------------------------------------------------------------------

    /** True once a draft or a config has been applied, so [seedIfNeeded] seeds at most once. */
    private var seeded: Boolean = false

    init {
        // A restored draft WINS over the store: it is a complete snapshot of a form the user was
        // already editing, so re-seeding it from the config would undo what they typed. Only when
        // there is no draft do we seed exactly as the old remember initialisers did.
        val restored = draftStore.loadWizardDraft()
        when {
            restored != null -> {
                applyDraft(restored)
                seeded = true
            }
            cfg != null -> {
                applySeed(cfg, onlyUntouched = false)
                seeded = true
            }
        }
    }

    /**
     * Restores every field from [draft], including the step it was showing and the R2 secret the
     * user had pasted but not yet committed by a Next tap.
     */
    private fun applyDraft(draft: WizardDraft) {
        screen.value = WizardNav.screenFromIndex(draft.screenIndex)
        txUser.value = draft.txUser
        txPass.value = draft.txPass
        peerPort.value = draft.peerPort
        dlSubpath.value = draft.dlSubpath
        ftpUser.value = draft.ftpUser
        ftpPass.value = draft.ftpPass
        ftpPort.value = draft.ftpPort
        ftpWifiOnly.value = draft.ftpWifiOnly
        crawlerEnabled.value = draft.crawlerEnabled
        crawlerAdultDrop.value = draft.crawlerAdultDrop
        saveCategories.value = draft.saveCategories
        r2Endpoint.value = draft.r2Endpoint
        r2Bucket.value = draft.r2Bucket
        r2ObjectKey.value = draft.r2ObjectKey
        r2AccessKeyId.value = draft.r2AccessKeyId
        r2SecretAccessKey.value = draft.r2SecretAccessKey
        uploadEnabled.value = draft.uploadEnabled
        dbBackupEnabled.value = draft.dbBackupEnabled
        r2DbBucket.value = draft.r2DbBucket
        r2DbObjectPrefix.value = draft.r2DbObjectPrefix
        bootstrapEnabled.value = draft.bootstrapEnabled
        bootstrapUrl.value = draft.bootstrapUrl
    }

    /**
     * Seeds the form from the store the first time a config becomes available (the binder
     * connected after the wizard was already on screen). A null [cfg] or a second call does
     * nothing, and only fields still holding their fallback are written, so user input is
     * never overwritten. Returns whether this call seeded.
     */
    fun seedIfNeeded(cfg: AppConfig?): Boolean {
        if (seeded || cfg == null) return false
        seeded = true
        applySeed(cfg, onlyUntouched = true)
        return true
    }

    /**
     * Applies [cfg] to every field. [onlyUntouched] keeps any field whose current value is
     * no longer the fallback — i.e. one the user has edited — untouched.
     */
    private fun applySeed(cfg: AppConfig, onlyUntouched: Boolean) {
        seed(txUser, ConfigDefaults.TRANSMISSION_USERNAME, cfg.transmission.username, onlyUntouched)
        seed(txPass, "", cfg.transmission.password, onlyUntouched)
        seed(
            peerPort,
            ConfigDefaults.TRANSMISSION_PEER_PORT.toString(),
            cfg.transmission.peerPort.toString(),
            onlyUntouched,
        )
        seed(dlSubpath, "", cfg.dataSubpath, onlyUntouched)

        seed(ftpUser, ConfigDefaults.FTP_USERNAME, cfg.ftp.username, onlyUntouched)
        seed(ftpPass, "", cfg.ftp.password, onlyUntouched)
        seed(ftpPort, ConfigDefaults.FTP_PORT.toString(), cfg.ftp.port.toString(), onlyUntouched)
        seed(ftpWifiOnly, ConfigDefaults.FTP.wifiOnly, cfg.ftp.wifiOnly, onlyUntouched)

        seed(crawlerEnabled, false, cfg.crawler.enabled, onlyUntouched)
        seed(crawlerAdultDrop, false, cfg.crawler.adultMode == "drop", onlyUntouched)
        // An empty stored list means "save all": the form shows the media-only default then.
        val storedCategories = cfg.crawler.saveCategories.takeIf { it.isNotEmpty() }?.toSet()
            ?: WizardDefaultSaveCategories.toSet()
        seed(
            saveCategories,
            WizardDefaultSaveCategories.toSet(),
            storedCategories,
            onlyUntouched,
        )

        seed(r2Endpoint, "", cfg.backup.r2Endpoint, onlyUntouched)
        seed(r2Bucket, "", cfg.backup.r2Bucket, onlyUntouched)
        seed(r2ObjectKey, ConfigDefaults.BACKUP_OBJECT_KEY, cfg.backup.r2ObjectKey, onlyUntouched)
        seed(r2AccessKeyId, "", cfg.backup.r2AccessKeyId, onlyUntouched)
        seed(uploadEnabled, false, cfg.backup.uploadEnabled, onlyUntouched)
        seed(dbBackupEnabled, false, cfg.backup.dbBackupEnabled, onlyUntouched)
        seed(r2DbBucket, ConfigDefaults.DB_BACKUP_BUCKET, cfg.backup.r2DbBucket, onlyUntouched)
        seed(
            r2DbObjectPrefix,
            ConfigDefaults.DB_BACKUP_OBJECT_PREFIX,
            cfg.backup.r2DbObjectPrefix,
            onlyUntouched,
        )
        seed(bootstrapEnabled, false, cfg.backup.bootstrapEnabled, onlyUntouched)
        seed(bootstrapUrl, "", cfg.backup.bootstrapUrl, onlyUntouched)
        // r2SecretAccessKey is intentionally absent: a blank field keeps the stored secret.
    }

    /** Writes [stored] into [state] unless the user has already moved it off [fallback]. */
    private fun <T> seed(state: MutableState<T>, fallback: T, stored: T, onlyUntouched: Boolean) {
        if (onlyUntouched && state.value != fallback) return
        state.value = stored
    }

    // --- Draft persistence ----------------------------------------------------------------

    /**
     * The whole form as a [WizardDraft]. It reads every field, so the flow's
     * `snapshotFlow { form.draft() }` observes exactly the values the draft has to keep.
     */
    fun draft(): WizardDraft = WizardDraft(
        screenIndex = WizardNav.screenIndex(screen.value),
        txUser = txUser.value,
        txPass = txPass.value,
        peerPort = peerPort.value,
        dlSubpath = dlSubpath.value,
        ftpUser = ftpUser.value,
        ftpPass = ftpPass.value,
        ftpPort = ftpPort.value,
        ftpWifiOnly = ftpWifiOnly.value,
        crawlerEnabled = crawlerEnabled.value,
        crawlerAdultDrop = crawlerAdultDrop.value,
        saveCategories = saveCategories.value,
        r2Endpoint = r2Endpoint.value,
        r2Bucket = r2Bucket.value,
        r2ObjectKey = r2ObjectKey.value,
        r2AccessKeyId = r2AccessKeyId.value,
        r2SecretAccessKey = r2SecretAccessKey.value,
        uploadEnabled = uploadEnabled.value,
        dbBackupEnabled = dbBackupEnabled.value,
        r2DbBucket = r2DbBucket.value,
        r2DbObjectPrefix = r2DbObjectPrefix.value,
        bootstrapEnabled = bootstrapEnabled.value,
        bootstrapUrl = bootstrapUrl.value,
    )

    /** Writes [draft] to the store; the flow calls this on every change. */
    fun persistDraft(draft: WizardDraft = draft()) { draftStore.saveWizardDraft(draft) }

    /** Forgets the saved draft: the wizard is finished, so nothing is left to resume. */
    fun clearDraft() { draftStore.clearWizardDraft() }

    // --- Validation ----------------------------------------------------------------------

    /** Validation messages per step; only the step whose Next was rejected carries any. */
    val errors = mutableStateOf<Map<WizardStep, List<String>>>(emptyMap())

    /** The messages shown under [step]'s fields (empty when the step is clean). */
    fun errorsFor(step: WizardStep): List<String> = errors.value[step].orEmpty()

    /** Replaces [step]'s messages; an empty list clears them. */
    fun setErrors(step: WizardStep, messages: List<String>) {
        errors.value =
            if (messages.isEmpty()) errors.value - step else errors.value + (step to messages)
    }

    /** Clears every step's messages (a fresh attempt, or a finished step). */
    fun clearErrors() {
        errors.value = emptyMap()
    }
}
