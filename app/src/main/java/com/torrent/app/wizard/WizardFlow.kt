package com.torrent.app.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.torrent.app.DaemonService
import com.torrent.app.WizardRestoreViewModel
import com.torrent.app.config.ApplyResult
import com.torrent.app.config.ConfigDefaults
import com.torrent.app.config.ConfigValidator
import com.torrent.app.config.CrawlerTaxonomy
import com.torrent.app.config.FtpSettings
import com.torrent.app.config.TransmissionSettings
import com.torrent.app.config.WizardDraftStore
import com.torrent.app.config.WizardSubmit
import com.torrent.app.storage.SafStatus
import com.torrent.app.ui.theme.TorrentPalette

/**
 * The first-run wizard: one screen per step, with a "Step N of 5" header, the step dots,
 * that step's form and a Back/Next/Skip row.
 *
 * The form state lives in the activity-scoped [WizardFormViewModel] (so a rotation keeps
 * every value, and the draft it writes to [draftStore] keeps them across an activity
 * recreation or a process kill) and the restore state in [WizardRestoreViewModel]. This file
 * owns the actions: Next validates and persists exactly one section, Skip leaves that
 * section's stored values untouched, "Start download" on the last step persists the restore
 * inputs and hands over to the dedicated [RestoreProgressScreen], and "Finish without
 * downloading" completes setup without a download.
 *
 * The wizard ends exactly once: [completeSetup] persists `wizardCompleted`, starts the
 * dashboard/proxy/daemons and shows the main screen, guarded by
 * [WizardFormViewModel.wizardFinished] so the download's auto-advance cannot run it twice.
 */
@Composable
fun WizardFlow(
    safStatus: SafStatus?,
    boundService: DaemonService.LocalBinder?,
    draftStore: WizardDraftStore,
    onGrantUsb: () -> Unit,
    onFinished: () -> Unit,
) {
    // The ViewModel seeds itself from the stored config once and then owns every field for
    // the activity's lifetime. It can now be created BEFORE the binder connects: the activity
    // reads `wizardCompleted` synchronously from the store, so a first-run wizard is on screen
    // from the very first frame. The effect below therefore seeds the form the moment a config
    // exists — once, and only into fields the user has not touched (WizardFormViewModel).
    val form = viewModel<WizardFormViewModel> {
        WizardFormViewModel(boundService?.loadConfig(), draftStore)
    }
    LaunchedEffect(boundService) { form.seedIfNeeded(boundService?.loadConfig()) }
    val restoreVm = viewModel<WizardRestoreViewModel>()

    // Every edit goes to the draft store, so leaving the app mid-setup — to copy the R2
    // credentials out of a password manager, or because the OS reclaimed this activity or the
    // whole process while it was stopped — resumes on the same step with the same values
    // instead of restarting on step 1 with an empty form. `snapshotFlow` observes exactly the
    // state `draft()` reads, and the store writes with `apply()`, so this is one small batched
    // prefs write per edit.
    LaunchedEffect(form) {
        snapshotFlow { form.draft() }.collect { form.persistDraft(it) }
    }

    var screen by form.screen
    var restoreStatus by restoreVm.restoreStatus
    var restoreError by restoreVm.restoreError

    val bound = boundService != null
    val usbGranted = safStatus?.granted == true
    // The persisted R2 secret: a blank field keeps it (same rule as the dashboard form), so
    // the download block stays usable on a re-shown wizard without retyping the secret.
    val storedSecret = boundService?.loadConfig()?.backup?.r2SecretAccessKey ?: ""

    /** The whole form as [WizardSubmit.Form], with the blank-secret-keeps-stored rule applied. */
    fun buildForm(): WizardSubmit.Form = submitFormFrom(form, storedSecret)

    /**
     * Finishes the wizard: `completeWizard()` persists the flag and starts the control
     * server, the crawler proxy and the daemons, then the activity shows the main screen.
     *
     * Both exits land here — the download screen's auto-advance on SUCCESS and the explicit
     * "Finish without downloading" — so it must be ONCE-ONLY: starting the servers twice is
     * not idempotent. The guard lives in the activity-scoped ViewModel, so it survives a
     * rotation but not a process death (where the wizard resumes from its draft).
     */
    fun completeSetup() {
        if (form.wizardFinished.value) return
        val svc = boundService ?: return
        form.wizardFinished.value = true
        // The wizard is done: drop the draft (including any pasted R2 secret) before the main
        // screen appears, so a later launch can never resume a finished setup.
        form.clearDraft()
        svc.completeWizard()
        onFinished()
    }

    /**
     * "Start download" (and the progress screen's "Retry"): persists the restore inputs, then
     * starts the one-shot restore.
     *
     * This is the old `doRestoreNow()` order and it is load-bearing: the chip selection is
     * saved FIRST, because the restore's category filter reads the persisted selection inside
     * its own run — i.e. after `startDbRestore` has returned. `saveBackupForRestore` runs
     * `validateDbRestore` in the service, so a rejection is a form error, not an attempt: it
     * clears any previous verdict (a stale SUCCESS must not survive inputs that were never
     * restored from) but keeps a restore that is still RUNNING visible.
     */
    fun startDownload() {
        val svc = boundService ?: return
        val data = buildForm()
        // The form only emits known names, so normalisation can only reorder/drop — the error
        // branch is unreachable in practice and aborts rather than restoring from a store
        // that disagrees with the chips.
        val (categoryErrors, categories) =
            WizardSubmit.normalizeSaveCategories(data.crawlerSaveCategories)
        val r: ApplyResult = if (categoryErrors.isNotEmpty()) {
            ApplyResult(false, categoryErrors.joinToString("; "), false)
        } else {
            val saved = svc.saveCrawlerSaveCategoriesForRestore(categories)
            if (!saved.ok) saved else svc.saveBackupForRestore(WizardSubmit.backupSettings(data))
        }
        if (!r.ok) {
            restoreError = r.message
            restoreVm.invalidate()
            svc.dbRestoreStatus().takeIf { it.running }?.let { restoreVm.adopt(it) }
            return
        }
        restoreError = null
        val started = svc.startDbRestore()
        restoreVm.adopt(started)
        // A queued restore (RUNNING) and one that was already in flight (REFUSED_RUNNING)
        // both belong on the progress screen. A busy/shutting-down refusal does not: it never
        // starts anything, so it stays on this step with its reason.
        if (started.running) screen = WizardScreenKind.Progress
    }

    // --- Per-step Next actions: validate, then persist ONE section ------------------------

    fun applyTransmissionSection(): Boolean {
        val tx = transmissionFrom(form)
        // The same two checks WizardSubmit.submit runs for this section.
        val base = boundService?.loadConfig() ?: ConfigDefaults.INITIAL
        val errors = ConfigValidator.validateTransmission(tx).errors +
            ConfigValidator.validateDataDir(base.copy(dataSubpath = form.dlSubpath.value)).errors
        if (errors.isNotEmpty()) {
            form.setErrors(WizardStep.TRANSMISSION, errors)
            return false
        }
        val svc = boundService
        if (svc == null) {
            form.setErrors(WizardStep.TRANSMISSION, listOf("Still connecting to the service — try again."))
            return false
        }
        // load-modify-copy is REQUIRED: applyTransmissionSettings persists the whole AppConfig
        // (configStore.save), so every other section must be carried over unchanged or the
        // crawler/backup sections would be clobbered with their loaded values lost.
        val r = svc.applyTransmissionSettings(
            svc.loadConfig().copy(transmission = tx, dataSubpath = form.dlSubpath.value),
            liveOnly = false,
        )
        if (!r.ok) {
            form.setErrors(WizardStep.TRANSMISSION, listOf(r.message))
            return false
        }
        return true
    }

    fun applyFtpSection(): Boolean {
        val svc = boundService
        if (svc == null) {
            form.setErrors(WizardStep.FTP, listOf("Still connecting to the service — try again."))
            return false
        }
        // Only the FTP section is written; the stored extras (e.g. the download cap) survive.
        val ftp = ftpFrom(form, svc.loadConfig().ftp)
        val errors = ConfigValidator.validateFtp(ftp).errors
        if (errors.isNotEmpty()) {
            form.setErrors(WizardStep.FTP, errors)
            return false
        }
        // "applied; waiting for wifi" and "applied; FTP starts when USB granted" are SUCCESS:
        // applyFtpSettings only starts the daemon once the wizard has been completed.
        val r = svc.applyFtpSettings(ftp)
        if (!r.ok) {
            form.setErrors(WizardStep.FTP, listOf(r.message))
            return false
        }
        return true
    }

    fun applyCrawlerSection(): Boolean {
        val svc = boundService
        if (svc == null) {
            form.setErrors(WizardStep.CRAWLER, listOf("Still connecting to the service — try again."))
            return false
        }
        val (categoryErrors, categories) =
            WizardSubmit.normalizeSaveCategories(selectedCategories(form))
        if (categoryErrors.isNotEmpty()) {
            form.setErrors(WizardStep.CRAWLER, categoryErrors)
            return false
        }
        // Order matters: the categories and adult-mode writes are queued on the crawler-control
        // executor and the crawler reads both keys only at startup, so they must land before
        // setCrawlerEnabled can launch it. All three are section-scoped store writes.
        svc.setCrawlerSaveCategories(categories)
        svc.setCrawlerAdultMode(if (form.crawlerAdultDrop.value) "drop" else "tag")
        svc.setCrawlerEnabled(form.crawlerEnabled.value)
        return true
    }

    fun proceed() {
        val current = screen
        val currentStep = current.stepOrNull ?: return
        val ok = when (currentStep) {
            WizardStep.USB -> usbGranted
            WizardStep.TRANSMISSION -> applyTransmissionSection()
            WizardStep.FTP -> applyFtpSection()
            WizardStep.CRAWLER -> applyCrawlerSection()
            // The last step has no Next: it starts a download or finishes explicitly.
            WizardStep.DB_DOWNLOAD -> false
        }
        if (!ok) return
        form.clearErrors()
        screen = WizardNav.next(current)
    }

    fun skip() {
        val current = screen
        if (current.stepOrNull?.isSkippable != true) return
        // Skip leaves this section's stored values untouched — no validation, no write.
        form.clearErrors()
        screen = WizardNav.next(current)
    }

    // System Back steps back through the wizard exactly like the in-row Back button instead
    // of leaving the activity. On the first step there is no previous step, so the handler is
    // not registered there and Back keeps its usual meaning (leave the app). The progress
    // screen installs its own handler: a running restore must not be detached.
    val backTarget = WizardNav.back(screen)
    BackHandler(enabled = screen is WizardScreenKind.Step && backTarget != screen) {
        screen = backTarget
    }

    // --- Shell ---------------------------------------------------------------------------

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val current = screen) {
            // The dedicated download screen replaces the whole step layout (header, dots,
            // action row): it owns its own title and actions while the restore runs.
            WizardScreenKind.Progress -> RestoreProgressScreen(
                modifier = Modifier.weight(1f),
                boundService = boundService,
                restoreVm = restoreVm,
                onRetry = { startDownload() },
                onFinishWithoutDownload = { completeSetup() },
                onSucceeded = { completeSetup() },
                onBack = { screen = WizardNav.back(screen) },
            )
            is WizardScreenKind.Step -> {
                val step = current.step
                val canAdvance = WizardNav.canProceed(step, usbGranted)
                Text(
                    text = WizardNav.stepIndicator(current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepDots(WizardNav.stepNumber(current))
                Text(step.title, style = MaterialTheme.typography.headlineMedium)
                // Only the current step is composed: one screen per step, no scrolling
                // mega-form.
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (step) {
                        WizardStep.USB -> StepUsb(
                            safStatus = safStatus,
                            bound = bound,
                            onGrantUsb = onGrantUsb,
                        )
                        WizardStep.TRANSMISSION -> StepTransmission(form)
                        WizardStep.FTP -> StepFtp(form)
                        WizardStep.CRAWLER -> StepCrawler(form)
                        WizardStep.DB_DOWNLOAD -> StepDbDownload(
                            form = form,
                            bound = bound,
                            storedSecret = storedSecret,
                            restoreStatus = restoreStatus,
                            restoreError = restoreError,
                            onEditRestoreInputs = { restoreVm.invalidate() },
                            onStartDownload = { startDownload() },
                            onFinishWithoutDownload = { completeSetup() },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { screen = WizardNav.back(current) },
                        // Back on the first step is a no-op (WizardNav.back) — disable it
                        // instead of letting a tap do nothing.
                        enabled = step != WizardNav.steps.first(),
                    ) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    // The DB-download step's Skip affordance is its own "Finish without
                    // downloading" button, so the generic Skip stays off the last step.
                    if (step.isSkippable && step != WizardStep.DB_DOWNLOAD) {
                        TextButton(onClick = { skip() }) { Text("Skip") }
                    }
                    if (step != WizardStep.DB_DOWNLOAD) {
                        Button(
                            onClick = { proceed() },
                            enabled = canAdvance,
                        ) { Text("Next") }
                    }
                }
                // Only the USB step can block Next (its grant is required); the other steps
                // validate on the tap and show their own messages, so they have no hint.
                if (step != WizardStep.DB_DOWNLOAD) {
                    nextDisabledHint(step, usbGranted)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The step dots under the "Step N of 5" header: the current step is the filled blue one. */
@Composable
private fun StepDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WizardNav.steps.forEachIndexed { index, _ ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (index + 1 == current) MaterialTheme.colorScheme.primary
                                else TorrentPalette.color(TorrentPalette.CHIP),
                        shape = CircleShape,
                    )
            )
        }
    }
}

// --- Pure field mapping (no Compose state, so it is unit-testable) ------------------------

/** The Transmission section as the form shows it (speed limits stay at their wizard defaults). */
internal fun transmissionFrom(form: WizardFormViewModel): TransmissionSettings =
    TransmissionSettings(
        username = form.txUser.value,
        password = form.txPass.value,
        peerPort = form.peerPort.value.toIntOrNull() ?: 0,
        speedLimitDown = 0,
        speedLimitUp = 0,
        altSpeedDown = 0,
        altSpeedUp = 0,
    )

/**
 * The FTP section as the form shows it, keeping every stored field the form does not edit
 * (the download cap) — the wizard only writes the four values it asks for.
 */
internal fun ftpFrom(form: WizardFormViewModel, stored: FtpSettings): FtpSettings =
    stored.copy(
        username = form.ftpUser.value,
        password = form.ftpPass.value,
        port = form.ftpPort.value.toIntOrNull() ?: 0,
        wifiOnly = form.ftpWifiOnly.value,
    )

/** The chip selection as `save_categories` names, in the crawler's own order. */
internal fun selectedCategories(form: WizardFormViewModel): List<String> =
    CrawlerTaxonomy.CATEGORIES.filter { it in form.saveCategories.value }

/**
 * The line shown under a step whose Next is disabled, so a greyed-out button always says
 * why. Only the USB step can block Next (its grant is required before anything can be
 * written); every other step validates on the tap and renders its own messages instead.
 */
internal fun nextDisabledHint(step: WizardStep, usbGranted: Boolean): String? = when {
    WizardNav.canProceed(step, usbGranted) -> null
    step == WizardStep.USB -> "Grant the USB drive to continue."
    else -> "Fix the highlighted fields to continue."
}

/**
 * The whole form as [WizardSubmit.Form]. [storedSecret] is the persisted R2 secret: a blank
 * field keeps it (same rule as the dashboard form), so re-showing the wizard with upload
 * enabled cannot fail on a secret the user never retypes.
 */
internal fun submitFormFrom(form: WizardFormViewModel, storedSecret: String): WizardSubmit.Form =
    WizardSubmit.Form(
        txUsername = form.txUser.value,
        txPassword = form.txPass.value,
        peerPort = form.peerPort.value.toIntOrNull() ?: 0,
        dataSubpath = form.dlSubpath.value,
        ftpUsername = form.ftpUser.value,
        ftpPassword = form.ftpPass.value,
        ftpPort = form.ftpPort.value.toIntOrNull() ?: 0,
        ftpWifiOnly = form.ftpWifiOnly.value,
        crawlerEnabled = form.crawlerEnabled.value,
        crawlerAdultMode = if (form.crawlerAdultDrop.value) "drop" else "tag",
        crawlerSaveCategories = selectedCategories(form),
        r2Endpoint = form.r2Endpoint.value,
        r2Bucket = form.r2Bucket.value,
        r2ObjectKey = form.r2ObjectKey.value,
        r2AccessKeyId = form.r2AccessKeyId.value,
        r2SecretAccessKey = effectiveSecret(form.r2SecretAccessKey.value, storedSecret),
        uploadEnabled = form.uploadEnabled.value,
        dbBackupEnabled = form.dbBackupEnabled.value,
        r2DbBucket = form.r2DbBucket.value,
        r2DbObjectPrefix = form.r2DbObjectPrefix.value,
        bootstrapEnabled = form.bootstrapEnabled.value,
        bootstrapUrl = form.bootstrapUrl.value,
    )
