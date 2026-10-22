package com.torrent.app.wizard

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.torrent.app.DaemonService
import com.torrent.app.WizardRestoreViewModel
import com.torrent.app.backup.WizardRestoreGate
import com.torrent.app.backup.WizardRestorePollPolicy
import com.torrent.app.config.ApplyResult
import com.torrent.app.config.ConfigDefaults
import com.torrent.app.config.ConfigValidator
import com.torrent.app.config.CrawlerTaxonomy
import com.torrent.app.config.FtpSettings
import com.torrent.app.config.TransmissionSettings
import com.torrent.app.config.WizardSubmit
import com.torrent.app.storage.SafStatus
import com.torrent.app.ui.theme.TorrentPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The first-run wizard: one screen per step, with a "Step N of 5" header, the step dots,
 * that step's form and a Back/Next/Skip row.
 *
 * The form state lives in the activity-scoped [WizardFormViewModel] (so a rotation keeps
 * every value) and the restore state in [WizardRestoreViewModel]. This file owns the
 * actions: Next validates and persists exactly one section, Skip leaves that section's
 * stored values untouched, and "Finish setup" on the last step keeps the full
 * [WizardSubmit] sequence as the safety net.
 *
 * The restore card on the last step is service-anchored exactly as before: the service owns
 * the restore, so the card adopts its snapshot on every composition/resume and polls while
 * one is in flight.
 */
@Composable
fun WizardFlow(
    safStatus: SafStatus?,
    boundService: DaemonService.LocalBinder?,
    onGrantUsb: () -> Unit,
    onFinished: () -> Unit,
) {
    // The ViewModel seeds itself from the stored config once (the binder is connected before
    // the wizard can be shown: `wizardCompleted` only flips false after a service load) and
    // then owns every field for the activity's lifetime.
    val form = viewModel<WizardFormViewModel> { WizardFormViewModel(boundService?.loadConfig()) }
    val restoreVm = viewModel<WizardRestoreViewModel>()

    var screen by form.screen
    var restoreEnabled by restoreVm.restoreEnabled
    var restoreStatus by restoreVm.restoreStatus
    var restoreError by restoreVm.restoreError

    val step = screen.stepOrNull
    val bound = boundService != null
    val usbGranted = safStatus?.granted == true

    // --- Restore card plumbing (unchanged from the single-form wizard) ---------------------

    // The form builder shared by "Restore now" and "Finish setup" so both see exactly the
    // same mapping (including blank-secret-keeps-current).
    fun buildForm(): WizardSubmit.Form =
        submitFormFrom(form, boundService?.loadConfig()?.backup?.r2SecretAccessKey ?: "")

    // Persists the restore inputs, then starts the one-shot. The chip selection is
    // saved FIRST: the restore's category filter reads the persisted selection, so the
    // chips must reach the store before startDbRestore queues the restore — otherwise
    // wizard display, persisted config and the filter could disagree.
    // A rejected save is a form error, not a restore attempt: it clears any previous
    // verdict so a stale SUCCESS can't keep "Finish setup" unlocked — but a restore
    // that is still RUNNING stays visible, or its polling effect would detach mid-restore.
    fun doRestoreNow() {
        val svc = boundService ?: return
        val (categoryErrors, saveCategories) =
            WizardSubmit.normalizeSaveCategories(buildForm().crawlerSaveCategories)
        val r: ApplyResult = if (categoryErrors.isNotEmpty()) {
            ApplyResult(false, categoryErrors.joinToString("; "), false)
        } else {
            val saved = svc.saveCrawlerSaveCategoriesForRestore(saveCategories)
            if (!saved.ok) saved else svc.saveBackupForRestore(WizardSubmit.backupSettings(buildForm()))
        }
        if (!r.ok) {
            restoreError = r.message
            restoreVm.invalidate()
            svc.dbRestoreStatus().takeIf { it.running }?.let { restoreVm.adopt(it) }
        } else {
            restoreError = null
            restoreStatus = svc.startDbRestore()
        }
    }

    // The restore lives in the service, so the card is SERVICE-anchored: the local copy is
    // only a cache. An activity the OS recreates mid-restore (guaranteed with a multi-hour
    // restore) comes back with a fresh ViewModel and restoreStatus == null, which used to
    // leave the card blank, the section switch OFF and "Finish setup" locked even on SUCCESS.
    val svcState = rememberUpdatedState(boundService)
    fun adoptServiceStatus() {
        val svc = svcState.value ?: return
        restoreVm.adopt(svc.dbRestoreStatus())
    }

    // One snapshot on every restore-card composition, on each resume, and whenever the binder
    // (re)appears — a recreated activity composes before the async onServiceConnected callback.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(boundService, resumeTick) { adoptServiceStatus() }

    // Follows RUNNING → SUCCESS/FAILED without blocking the main thread: the restore itself
    // runs on the service's crawlerControlExecutor, and these status reads are cheap
    // volatile/isBusy snapshots. Keyed on the ADOPTED snapshot, so the re-attach above is
    // what starts the loop; every tick adopts through the same policy and always reads the
    // latest binder.
    LaunchedEffect(restoreStatus?.running) {
        while (isActive && WizardRestorePollPolicy.shouldPoll(restoreStatus)) {
            delay(1000)
            adoptServiceStatus()
        }
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
            // Finishing is the explicit "Finish setup" button on the last step, never a Next.
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

    // The last step's "Finish setup" keeps the old full-submit sequence as the safety net: it
    // re-validates every section and re-applies the ones the earlier steps already persisted.
    fun finish() {
        val svc = boundService ?: return
        val data = buildForm()
        val outcome = WizardSubmit.submit(data)
        if (!outcome.ok) {
            form.setErrors(WizardStep.DB_DOWNLOAD, outcome.errors)
            return
        }
        // Transmission apply first: it persists the shared data dir, which the FTP controller
        // construction below reads from the store (Stage 11).
        val newCfg = svc.loadConfig().copy(
            transmission = outcome.transmission!!,
            ftp = outcome.ftp!!,
            backup = outcome.backup!!,
            dataSubpath = data.dataSubpath,
        )
        val txR = svc.applyTransmissionSettings(newCfg, liveOnly = false)
        val ftpR = svc.applyFtpSettings(newCfg.ftp)
        if (txR.ok && ftpR.ok) {
            // Written before the start below (same serialized executor), so the first crawler
            // launch already sees the chosen adult_mode. The categories write is queued ahead
            // of it for the same reason: the crawler reads save_categories only at startup.
            svc.setCrawlerSaveCategories(outcome.crawlerSaveCategories)
            svc.setCrawlerAdultMode(outcome.crawlerAdultMode)
            if (outcome.crawlerEnabled) svc.setCrawlerEnabled(true)
            svc.completeWizard()
            onFinished()
        } else {
            form.setErrors(
                WizardStep.DB_DOWNLOAD,
                listOf(ftpR.message, txR.message).filter { it.isNotEmpty() },
            )
        }
    }

    // --- Shell ---------------------------------------------------------------------------

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = WizardNav.stepIndicator(screen),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepDots(WizardNav.stepNumber(screen))
        Text(
            text = step?.title ?: WizardStep.DB_DOWNLOAD.title,
            style = MaterialTheme.typography.headlineMedium,
        )
        // Only the current step is composed: one screen per step, no scrolling mega-form.
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
                    restoreEnabled = restoreEnabled,
                    onRestoreEnabledChange = { restoreEnabled = it },
                    restoreStatus = restoreStatus,
                    restoreError = restoreError,
                    onEditRestoreInputs = { restoreVm.invalidate() },
                    onRestoreNow = { doRestoreNow() },
                    onFinish = { finish() },
                    canFinish = bound && usbGranted &&
                        WizardRestoreGate.canFinish(restoreEnabled, restoreStatus?.state),
                )
                // The dedicated progress screen arrives in Stage 4; nothing is composed here.
                null -> Unit
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { screen = WizardNav.back(screen) },
                // Back on the first step is a no-op (WizardNav.back) — disable it instead of
                // letting a tap do nothing.
                enabled = step != null && step != WizardNav.steps.first(),
            ) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (step != null && step.isSkippable && step != WizardStep.DB_DOWNLOAD) {
                TextButton(onClick = { skip() }) { Text("Skip") }
            }
            if (step != null && step != WizardStep.DB_DOWNLOAD) {
                Button(
                    onClick = { proceed() },
                    enabled = WizardNav.canProceed(step, usbGranted),
                ) { Text("Next") }
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
