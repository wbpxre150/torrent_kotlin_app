package com.torrent.app.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torrent.app.backup.DbRestoreState
import com.torrent.app.backup.DbRestoreStatus
import com.torrent.app.backup.RestoreProgressText
import com.torrent.app.backup.WizardRestorePollPolicy
import com.torrent.app.config.CrawlerTaxonomy
import com.torrent.app.storage.SafStatus
import com.torrent.app.ui.theme.TorrentPalette

/**
 * The body of each first-run wizard step.
 *
 * Every field reads and writes the hoisted [WizardFormViewModel], so nothing here keeps
 * its own `remember` state: a rotation (or any configuration change) keeps the values, and
 * a step's validation messages are read straight from the ViewModel's per-step map. The
 * screens only render; the Next/Skip/Finish actions (validation + per-section persistence)
 * live in [WizardFlow], so a step composable stays a pure view of the form state.
 */

/** Display label for a `save_categories` name ("tv" reads better as "TV"). */
internal fun categoryLabel(name: String): String =
    if (name == "tv") "TV" else name.replaceFirstChar { it.uppercase() }

/** The step's validation messages, in the same place for every step. */
@Composable
private fun StepErrors(messages: List<String>) {
    if (messages.isEmpty()) return
    Column {
        messages.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** One switch row with its label, used by the FTP and crawler steps. */
@Composable
private fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** Step 1: the USB drive grant (required — Next stays disabled until it is granted). */
@Composable
fun StepUsb(
    safStatus: SafStatus?,
    bound: Boolean,
    onGrantUsb: () -> Unit,
) {
    val status = safStatus
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "The USB drive holds the torrents, the crawler database and everything the FTP " +
                "server serves. Grant access to it to continue.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = if (status?.granted == true) "USB: granted" else "USB: not granted",
            style = MaterialTheme.typography.bodyLarge,
            color = if (status?.granted == true) TorrentPalette.color(TorrentPalette.OK)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGrantUsb, enabled = bound) { Text("Grant USB drive") }
    }
}

/** Step 2: the Transmission RPC credentials and the shared data directory. */
@Composable
fun StepTransmission(form: WizardFormViewModel) {
    var txUser by form.txUser
    var txPass by form.txPass
    var peerPort by form.peerPort
    var dlSubpath by form.dlSubpath

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Transmission downloads the torrents you add. The same username and password " +
                "protect its RPC port.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(txUser, { txUser = it }, label = { Text("Username") })
        OutlinedTextField(txPass, { txPass = it }, label = { Text("Password (min 6)") })
        OutlinedTextField(peerPort, { peerPort = it }, label = { Text("Peer port") })
        OutlinedTextField(
            dlSubpath,
            { dlSubpath = it },
            label = { Text("Data directory (under USB root; blank = root)") },
        )
        StepErrors(form.errorsFor(WizardStep.TRANSMISSION))
    }
}

/** Step 3: the FTP server Kodi clients connect to (skippable). */
@Composable
fun StepFtp(form: WizardFormViewModel) {
    var ftpUser by form.ftpUser
    var ftpPass by form.ftpPass
    var ftpPort by form.ftpPort
    var ftpWifiOnly by form.ftpWifiOnly

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Media clients (Kodi, VLC) stream from the USB drive over FTP. Skip leaves the " +
                "stored FTP settings untouched.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(ftpUser, { ftpUser = it }, label = { Text("FTP username") })
        OutlinedTextField(ftpPass, { ftpPass = it }, label = { Text("FTP password (min 6)") })
        OutlinedTextField(ftpPort, { ftpPort = it }, label = { Text("FTP port") })
        SwitchRow(ftpWifiOnly, { ftpWifiOnly = it }, "WiFi only")
        StepErrors(form.errorsFor(WizardStep.FTP))
    }
}

/** Step 4: the DHT crawler toggle, adult handling and the categories to keep (skippable). */
@Composable
fun StepCrawler(form: WizardFormViewModel) {
    var crawlerEnabled by form.crawlerEnabled
    var crawlerAdultDrop by form.crawlerAdultDrop
    var saveCategories by form.saveCategories

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "The crawler walks the DHT and builds a searchable index. Skip leaves the stored " +
                "crawler settings untouched.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SwitchRow(crawlerEnabled, { crawlerEnabled = it }, "Enable DHT crawler (default on)")
        SwitchRow(crawlerAdultDrop, { crawlerAdultDrop = it }, "Drop adult content (default: tag only)")
        Text("Categories to save (none checked = save all)", style = MaterialTheme.typography.bodyMedium)
        // Chips stay enabled even when the crawler is off: the selection is stored
        // either way and only takes effect once the crawler starts.
        CrawlerTaxonomy.CATEGORIES.chunked(3).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chunk.forEach { cat ->
                    FilterChip(
                        selected = cat in saveCategories,
                        onClick = {
                            saveCategories =
                                if (cat in saveCategories) saveCategories - cat
                                else saveCategories + cat
                        },
                        label = { Text(categoryLabel(cat)) },
                    )
                }
            }
        }
        StepErrors(form.errorsFor(WizardStep.CRAWLER))
    }
}

/**
 * Step 5, interim (Stage 4 replaces it with the download form and the dedicated progress
 * screen): the existing backup/bootstrap fields, the "restore the crawler DB from R2"
 * card and the "Finish setup" button, all gated exactly as the old single-form wizard
 * gated them.
 */
@Composable
fun StepDbDownload(
    form: WizardFormViewModel,
    bound: Boolean,
    restoreEnabled: Boolean,
    onRestoreEnabledChange: (Boolean) -> Unit,
    restoreStatus: DbRestoreStatus?,
    restoreError: String?,
    onEditRestoreInputs: () -> Unit,
    onRestoreNow: () -> Unit,
    onFinish: () -> Unit,
    canFinish: Boolean,
) {
    var r2Endpoint by form.r2Endpoint
    var r2Bucket by form.r2Bucket
    var r2ObjectKey by form.r2ObjectKey
    var r2AccessKeyId by form.r2AccessKeyId
    var r2SecretAccessKey by form.r2SecretAccessKey
    var uploadEnabled by form.uploadEnabled
    var dbBackupEnabled by form.dbBackupEnabled
    var r2DbBucket by form.r2DbBucket
    var r2DbObjectPrefix by form.r2DbObjectPrefix
    var bootstrapEnabled by form.bootstrapEnabled
    var bootstrapUrl by form.bootstrapUrl

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Optionally seed this install from a crawler DB dump in R2, or keep the empty " +
                "database and start crawling from scratch.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Backup & Bootstrap (optional)", style = MaterialTheme.typography.titleMedium)
        SwitchRow(uploadEnabled, { uploadEnabled = it }, "Enable R2 upload")
        OutlinedTextField(
            r2Endpoint,
            { r2Endpoint = it; onEditRestoreInputs() },
            label = { Text("R2 endpoint (https://<accountid>.r2.cloudflarestorage.com)") },
        )
        OutlinedTextField(r2Bucket, { r2Bucket = it }, label = { Text("R2 bucket") })
        OutlinedTextField(r2ObjectKey, { r2ObjectKey = it }, label = { Text("R2 object key") })
        OutlinedTextField(
            r2AccessKeyId,
            { r2AccessKeyId = it; onEditRestoreInputs() },
            label = { Text("R2 access key ID") },
        )
        OutlinedTextField(
            r2SecretAccessKey,
            { r2SecretAccessKey = it; onEditRestoreInputs() },
            label = { Text("R2 secret access key") },
        )
        SwitchRow(
            dbBackupEnabled,
            { dbBackupEnabled = it },
            "Enable daily DB dump upload (uses the R2 credentials above)",
        )
        OutlinedTextField(
            r2DbBucket,
            { r2DbBucket = it; onEditRestoreInputs() },
            label = { Text("R2 DB bucket") },
        )
        OutlinedTextField(
            r2DbObjectPrefix,
            { r2DbObjectPrefix = it; onEditRestoreInputs() },
            label = { Text("R2 DB object prefix") },
        )
        SwitchRow(bootstrapEnabled, { bootstrapEnabled = it }, "Enable bootstrap download (fresh installs)")
        OutlinedTextField(bootstrapUrl, { bootstrapUrl = it }, label = { Text("Bootstrap URL (https://…)") })
        Text(
            "If set, the cache is downloaded before the crawler first starts.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Restore crawler DB from R2 (optional)", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            checked = restoreEnabled,
            onCheckedChange = onRestoreEnabledChange,
            label = "Restore the crawler DB from the R2 dump chain (uses the R2 credentials above)",
            // A restore in flight owns the flag: it is re-attached ON from the service
            // snapshot and must not be switched off (that would unlock "Finish setup"
            // mid-restore). FAILED/IDLE leave it editable again.
            enabled = WizardRestorePollPolicy.restoreSwitchEnabled(restoreStatus),
        )
        Button(
            onClick = onRestoreNow,
            enabled = WizardRestorePollPolicy.restoreNowEnabled(bound, restoreEnabled, restoreStatus),
        ) { Text("Restore now") }

        // Prefix by state so a refused/not-configured attempt can never read as success.
        // A RUNNING status is checked first: a mid-restore double-tap answers
        // REFUSED_RUNNING with running=true and must render as running, not "failed".
        val st = restoreStatus
        if (st != null) {
            Text(
                text = if (st.running) "Restore running — ${st.message}"
                else when (st.state) {
                    DbRestoreState.SUCCESS -> "Restore succeeded — ${st.message}"
                    DbRestoreState.FAILED,
                    DbRestoreState.NOT_CONFIGURED,
                    DbRestoreState.REFUSED_BUSY,
                    DbRestoreState.REFUSED_RUNNING -> "Restore failed — ${st.message}"
                    else -> "Restore running — ${st.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (st.running) MaterialTheme.colorScheme.onSurface
                else when (st.state) {
                    DbRestoreState.SUCCESS -> TorrentPalette.color(TorrentPalette.OK)
                    DbRestoreState.FAILED,
                    DbRestoreState.NOT_CONFIGURED,
                    DbRestoreState.REFUSED_BUSY,
                    DbRestoreState.REFUSED_RUNNING -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            // The per-object counter is all the progress math needed — no ETA/rate logic.
            // Both counts must be known: a RUNNING restore that is still listing has no M.
            val done = st.objectsDone
            val total = st.objectCount
            RestoreProgressText.format(done, total)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (st.running && done != null && total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        restoreError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        StepErrors(form.errorsFor(WizardStep.DB_DOWNLOAD))

        Spacer(Modifier.height(4.dp))
        // canFinish already folds in "bound" plus the USB grant and the SUCCESS-only gate.
        Button(onClick = onFinish, enabled = canFinish) { Text("Finish setup") }
    }
}
