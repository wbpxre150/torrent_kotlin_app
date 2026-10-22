package com.torrent.app.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torrent.app.backup.DbRestoreStatus
import com.torrent.app.config.ConfigValidator
import com.torrent.app.config.WizardSubmit
import com.torrent.app.ui.theme.TorrentPalette

/**
 * Step 5: download the crawler database from the R2 dump chain (skippable).
 *
 * The download block is the screen's primary action — endpoint, credentials, DB bucket and
 * DB object prefix are exactly what `validateDbRestore` needs, so filling them in is enough
 * to start. "Start download" hands off to [WizardFlow]'s handler, which persists these
 * values, then switches to the dedicated progress screen ([RestoreProgressScreen]).
 *
 * "Advanced (optional)" keeps the backup-upload and bootstrap controls that used to live on
 * this step: they share the same R2 credentials, nothing about them is needed to download a
 * database, and dropping them would silently remove the upload/bootstrap capability.
 *
 * "Finish without downloading" is this step's Skip affordance: steps 2-4 were validated and
 * persisted at their own Next taps, so finishing here is just `completeWizard()`.
 */
@Composable
fun StepDbDownload(
    form: WizardFormViewModel,
    bound: Boolean,
    storedSecret: String,
    restoreStatus: DbRestoreStatus?,
    restoreError: String?,
    onEditRestoreInputs: () -> Unit,
    onStartDownload: () -> Unit,
    onFinishWithoutDownload: () -> Unit,
) {
    var r2Endpoint by form.r2Endpoint
    var r2AccessKeyId by form.r2AccessKeyId
    var r2SecretAccessKey by form.r2SecretAccessKey
    var r2DbBucket by form.r2DbBucket
    var r2DbObjectPrefix by form.r2DbObjectPrefix
    var uploadEnabled by form.uploadEnabled
    var r2Bucket by form.r2Bucket
    var r2ObjectKey by form.r2ObjectKey
    var dbBackupEnabled by form.dbBackupEnabled
    var bootstrapEnabled by form.bootstrapEnabled
    var bootstrapUrl by form.bootstrapUrl

    // Gated on the same validator the service runs inside saveBackupForRestore, so a tap can
    // only be refused for something this screen could have shown. The stored secret counts
    // (blank field keeps it), exactly like the value the handler saves.
    val canStart = bound &&
        restoreStatus?.running != true &&
        ConfigValidator.validateDbRestore(
            WizardSubmit.backupSettings(submitFormFrom(form, storedSecret))
        ).errors.isEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Seed this install from a crawler DB dump in R2, or keep the empty database and " +
                "start crawling from scratch. The download runs in the background — this " +
                "screen follows it and moves on when it succeeds.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("R2 database download", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            r2Endpoint,
            { r2Endpoint = it; onEditRestoreInputs() },
            label = { Text("R2 endpoint (https://<accountid>.r2.cloudflarestorage.com)") },
        )
        OutlinedTextField(
            r2AccessKeyId,
            { r2AccessKeyId = it; onEditRestoreInputs() },
            label = { Text("R2 access key ID") },
        )
        OutlinedTextField(
            r2SecretAccessKey,
            { r2SecretAccessKey = it; onEditRestoreInputs() },
            label = { Text("R2 secret access key (blank keeps the stored one)") },
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

        Button(onClick = onStartDownload, enabled = canStart) { Text("Start download") }

        // A refusal (busy / shutting down) never leaves this screen, so its reason is shown
        // here rather than only on the progress screen.
        val st = restoreStatus
        if (st != null && !st.running) {
            Text(
                text = RestoreProgressUi.statusText(st),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    RestoreProgressUi.statusKind(st) == RestoreStatusKind.SUCCESS ->
                        TorrentPalette.color(TorrentPalette.OK)
                    // A refusal is not a failure: amber, not the error red (same rule as the
                    // dedicated progress screen).
                    restoreStatusIsWarning(st) -> TorrentPalette.color(TorrentPalette.WARN)
                    RestoreProgressUi.statusKind(st) == RestoreStatusKind.ERROR ->
                        MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        restoreError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onFinishWithoutDownload, enabled = bound) {
            Text("Finish without downloading")
        }

        Spacer(Modifier.height(4.dp))
        Text("Advanced (optional)", style = MaterialTheme.typography.titleMedium)
        SwitchRow(uploadEnabled, { uploadEnabled = it }, "Enable R2 upload")
        OutlinedTextField(r2Bucket, { r2Bucket = it }, label = { Text("R2 bucket") })
        OutlinedTextField(r2ObjectKey, { r2ObjectKey = it }, label = { Text("R2 object key") })
        SwitchRow(
            dbBackupEnabled,
            { dbBackupEnabled = it },
            "Enable daily DB dump upload (uses the R2 credentials above)",
        )
        SwitchRow(
            bootstrapEnabled,
            { bootstrapEnabled = it },
            "Enable bootstrap download (fresh installs)",
        )
        OutlinedTextField(bootstrapUrl, { bootstrapUrl = it }, label = { Text("Bootstrap URL (https://…)") })
        Text(
            "If set, the cache is downloaded before the crawler first starts.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
