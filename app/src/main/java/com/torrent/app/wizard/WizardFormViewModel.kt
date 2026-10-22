package com.torrent.app.wizard

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.torrent.app.config.AppConfig
import com.torrent.app.config.ConfigDefaults

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
 * `ViewModelStore` and die with the process — nothing here is persisted to disk, the steps
 * persist their own section when the user taps Next.
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
class WizardFormViewModel(cfg: AppConfig? = null) : ViewModel() {

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

    /** True once a config has been applied, so [seedIfNeeded] can only ever seed once. */
    private var seeded: Boolean = cfg != null

    init {
        // The binder was already connected: seed exactly as the old remember initialisers did.
        cfg?.let { applySeed(it, onlyUntouched = false) }
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
