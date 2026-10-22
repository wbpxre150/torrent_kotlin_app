package com.torrent.app.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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

/** One switch row with its label, used by the FTP, crawler and DB-download steps. */
@Composable
internal fun SwitchRow(
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
