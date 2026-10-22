package com.torrent.app.wizard

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
 * [cfg] is the config loaded when the wizard was first composed (null while the service
 * binder is still connecting); it only seeds the initial values, and every default mirrors
 * the old `remember` initialisers verbatim.
 *
 * The properties are exposed as the [androidx.compose.runtime.MutableState] objects
 * themselves so a composable can delegate (`var txUser by vm.txUser`) and keep writing
 * through to the same state.
 */
class WizardFormViewModel(private val cfg: AppConfig? = null) : ViewModel() {

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

    val txUser = mutableStateOf(cfg?.transmission?.username ?: ConfigDefaults.TRANSMISSION_USERNAME)
    val txPass = mutableStateOf(cfg?.transmission?.password ?: "")
    val peerPort =
        mutableStateOf((cfg?.transmission?.peerPort ?: ConfigDefaults.TRANSMISSION_PEER_PORT).toString())
    val dlSubpath = mutableStateOf(cfg?.dataSubpath ?: "")

    // --- Step 3: FTP (Kodi) ---------------------------------------------------------------

    val ftpUser = mutableStateOf(cfg?.ftp?.username ?: ConfigDefaults.FTP_USERNAME)
    val ftpPass = mutableStateOf(cfg?.ftp?.password ?: "")
    val ftpPort = mutableStateOf((cfg?.ftp?.port ?: ConfigDefaults.FTP_PORT).toString())
    val ftpWifiOnly = mutableStateOf(cfg?.ftp?.wifiOnly ?: ConfigDefaults.FTP.wifiOnly)

    // --- Step 4: DHT crawler --------------------------------------------------------------

    val crawlerEnabled = mutableStateOf(cfg?.crawler?.enabled ?: false)
    val crawlerAdultDrop = mutableStateOf((cfg?.crawler?.adultMode ?: "tag") == "drop")

    /**
     * Non-empty store value wins; an empty list means "save all", in which case the wizard
     * shows (and therefore writes) the media-only fresh-install default.
     */
    val saveCategories = mutableStateOf(
        (cfg?.crawler?.saveCategories?.takeIf { it.isNotEmpty() }
            ?: WizardDefaultSaveCategories).toSet()
    )

    // --- Step 5: DB download (R2) ---------------------------------------------------------

    val r2Endpoint = mutableStateOf(cfg?.backup?.r2Endpoint ?: "")
    val r2Bucket = mutableStateOf(cfg?.backup?.r2Bucket ?: "")
    val r2ObjectKey = mutableStateOf(cfg?.backup?.r2ObjectKey ?: ConfigDefaults.BACKUP_OBJECT_KEY)
    val r2AccessKeyId = mutableStateOf(cfg?.backup?.r2AccessKeyId ?: "")
    /** Never seeded from the store: the form only writes a secret the user actually typed. */
    val r2SecretAccessKey = mutableStateOf("")
    val uploadEnabled = mutableStateOf(cfg?.backup?.uploadEnabled ?: false)
    val dbBackupEnabled = mutableStateOf(cfg?.backup?.dbBackupEnabled ?: false)
    val r2DbBucket = mutableStateOf(cfg?.backup?.r2DbBucket ?: ConfigDefaults.DB_BACKUP_BUCKET)
    val r2DbObjectPrefix =
        mutableStateOf(cfg?.backup?.r2DbObjectPrefix ?: ConfigDefaults.DB_BACKUP_OBJECT_PREFIX)
    val bootstrapEnabled = mutableStateOf(cfg?.backup?.bootstrapEnabled ?: false)
    val bootstrapUrl = mutableStateOf(cfg?.backup?.bootstrapUrl ?: "")

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
