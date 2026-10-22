package com.torrent.app.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.torrent.app.DaemonService
import com.torrent.app.WizardRestoreViewModel
import com.torrent.app.backup.DbRestoreState
import com.torrent.app.backup.RestoreProgressText
import com.torrent.app.backup.WizardRestorePollPolicy
import com.torrent.app.ui.theme.TorrentPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The dedicated screen that follows "Start download" on the DB-download step.
 *
 * It is a separate [WizardScreenKind] rather than a sixth step: the restore is a background
 * job of the service, not another thing to fill in, and it leaves on its own.
 *
 * The restore lives in `DaemonService` for hours, so this screen is SERVICE-anchored exactly
 * as the old restore card was: the [WizardRestoreViewModel] copy is only a cache. It adopts
 * the service's snapshot on every composition, on each `ON_RESUME`, and polls once a second
 * while a restore is in flight — an activity the OS recreates mid-restore therefore comes
 * back attached to the running job instead of showing a blank screen.
 *
 * On [DbRestoreState.SUCCESS] it calls [onSucceeded] with no further tap (the flow then runs
 * `completeWizard()` and shows the main screen). On a failure it shows the reason plus a
 * Retry and an explicit "Finish without downloading". While a restore is running there are no
 * buttons at all: the job owns the screen until it lands.
 */
@Composable
fun RestoreProgressScreen(
    boundService: DaemonService.LocalBinder?,
    restoreVm: WizardRestoreViewModel,
    onRetry: () -> Unit,
    onFinishWithoutDownload: () -> Unit,
    onSucceeded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var restoreStatus by restoreVm.restoreStatus
    var restoreError by restoreVm.restoreError

    // Always read the LATEST binder: a recreated activity composes before the async
    // onServiceConnected callback, so the first adopt is a no-op and the next one lands.
    val svcState = rememberUpdatedState(boundService)
    fun adoptServiceStatus() {
        val svc = svcState.value ?: return
        restoreVm.adopt(svc.dbRestoreStatus())
    }

    // One snapshot on composition, on each resume, and whenever the binder (re)appears.
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

    // Follows RUNNING → SUCCESS/FAILED without blocking the main thread: the restore runs on
    // the service's crawler-control executor and these reads are cheap volatile snapshots.
    // Keyed on the ADOPTED snapshot, so the re-attach above is what starts the loop; every
    // tick adopts through the same policy and always reads the latest binder.
    LaunchedEffect(restoreStatus?.running) {
        while (isActive && WizardRestorePollPolicy.shouldPoll(restoreStatus)) {
            delay(1000)
            adoptServiceStatus()
        }
    }

    // Auto-advance: SUCCESS completes the wizard and shows the main screen, with no extra tap.
    // The flow's handler is once-only, so a re-composition (or a rotation) cannot re-run it.
    LaunchedEffect(restoreStatus?.state, boundService) {
        if (restoreStatus?.state == DbRestoreState.SUCCESS) onSucceeded()
    }

    val status = restoreStatus
    val kind = status?.let { RestoreProgressUi.statusKind(it) } ?: RestoreStatusKind.IDLE
    val done = status?.objectsDone
    val total = status?.objectCount

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Downloading crawler database", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = status?.let { RestoreProgressUi.statusText(it) }
                ?: "Restore not started — tap Retry to try again.",
            style = MaterialTheme.typography.bodyLarge,
            color = when (kind) {
                RestoreStatusKind.RUNNING -> MaterialTheme.colorScheme.onSurface
                RestoreStatusKind.SUCCESS -> TorrentPalette.color(TorrentPalette.OK)
                RestoreStatusKind.ERROR -> MaterialTheme.colorScheme.error
                RestoreStatusKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // The per-object counter is all the progress math needed — no ETA/rate logic. Both
        // counts must be known: a RUNNING restore that is still listing has no total yet.
        // The RAW values are rendered: `objectsDone` can regress on resume (the manager
        // restarts from max(0, marker-3)) and `objectCount` counts filter-skipped chunks.
        RestoreProgressText.format(done, total)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (kind == RestoreStatusKind.RUNNING) {
            RestoreProgressUi.progressFraction(done, total)?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        restoreError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        when (kind) {
            // Nothing to retry into and nothing to wait for: the attempt never reached the
            // service (process death drops the in-memory status) or it failed outright.
            RestoreStatusKind.ERROR, RestoreStatusKind.IDLE -> {
                Button(onClick = onRetry, enabled = boundService != null) { Text("Retry") }
                OutlinedButton(onClick = onFinishWithoutDownload, enabled = boundService != null) {
                    Text("Finish without downloading")
                }
            }
            // A restore in flight owns the screen; SUCCESS is already leaving via auto-advance.
            RestoreStatusKind.RUNNING, RestoreStatusKind.SUCCESS -> Unit
        }
    }
}
