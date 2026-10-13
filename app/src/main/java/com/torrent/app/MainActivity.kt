package com.torrent.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.torrent.app.storage.SafStatus
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.torrent.app.config.ConfigDefaults
import com.torrent.app.config.WizardSubmit
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var wifiIp = MutableStateFlow("")
    private val safStatus = MutableStateFlow<SafStatus?>(null)
    private val wizardCompleted = MutableStateFlow(true)
    private val serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    private var authToken = MutableStateFlow("")
    // Result/note for the Termux migration folder picker (plan 10 §6.2).
    private val crawlerNoteFlow = MutableStateFlow("")

    private var boundService: DaemonService.LocalBinder? = null


    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startOrRestartService()
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as DaemonService.LocalBinder
            boundService = b
            // Collect binder state into our flows
            lifecycleScope.launch {
                b.serviceState.collect { serviceState.value = it }
            }
            authToken.value = b.getAuthToken()
            wifiIp.value = b.getWifiIpAddress() ?: ""
            safStatus.value = b.getSafStatus()
            wizardCompleted.value = b.loadConfig().wizardCompleted
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            serviceState.value = ServiceState.Stopped
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind to the service (creates it if not running)
        val bindIntent = Intent(this, DaemonService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Start foreground service (no-op if already running)
        val fgIntent = Intent(this, DaemonService::class.java)
        ContextCompat.startForegroundService(this, fgIntent)

        // Periodic WiFi IP refresh
        lifecycleScope.launch {
            while (isActive) {
                delay(3000)
                boundService?.let { svc ->
                    wifiIp.value = svc.getWifiIpAddress() ?: ""
                    authToken.value = svc.getAuthToken()
                    safStatus.value = svc.getSafStatus()
                    wizardCompleted.value = svc.loadConfig().wizardCompleted
                }
            }
        }

        setContent {
            val state by serviceState.collectAsState()
            val token by authToken.collectAsState()
            val ip by wifiIp.collectAsState()
            val crawlerNote by crawlerNoteFlow.collectAsState()

            // Auto-start when service is stopped
            LaunchedEffect(serviceState.value) {
                if (serviceState.value is ServiceState.Stopped) {
                    startOrRestartService()
                }
            }
            val usbTreeLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri: Uri? ->
                uri?.let { boundService?.setUsbTree(it) }
            }
            // Termux migration folder picker (plan 10 §6.2): OpenDocumentTree
            // opened at the Termux dht_crawler home via EXTRA_INITIAL_URI.
            val crawlerTreeLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri: Uri? ->
                if (uri == null) {
                    crawlerNoteFlow.value =
                        "No folder selected — the migration needs the Termux dht_crawler folder."
                } else {
                    crawlerNoteFlow.value = boundService?.setCrawlerImportTree(uri)
                        ?: "Service not bound — reopen the app and try again."
                }
            }
            val onChooseCrawlerTree = {
                // OpenDocumentTree's input Uri is set as EXTRA_INITIAL_URI, so
                // the picker opens at the Termux dht_crawler home.
                val initial = android.provider.DocumentsContract.buildDocumentUri(
                    com.torrent.app.migration.TermuxMigration.TERMUX_AUTHORITY,
                    com.torrent.app.migration.TermuxMigration.TERMUX_INITIAL_DOC_ID)
                try {
                    crawlerTreeLauncher.launch(initial)
                } catch (e: Exception) {
                    // No activity for the intent (e.g. Termux not installed).
                    crawlerNoteFlow.value =
                        "Could not open the folder picker — is Termux installed? (${e.message})"
                }
            }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val wizardDone by wizardCompleted.collectAsState()
                    if (!wizardDone) {
                        WizardScreen(
                            safStatus = safStatus.collectAsState().value,
                            boundService = boundService,
                            onGrantUsb = { usbTreeLauncher.launch(null) },
                            onFinished = { wizardCompleted.value = true },
                        )
                    } else {
                        MainScreen(
                            serviceState = state,
                            authToken = token,
                            wifiIp = ip,
                            safStatus = safStatus.collectAsState().value,
                            onGrantUsb = { usbTreeLauncher.launch(null) },
                            crawlerNote = crawlerNote,
                            onChooseCrawlerTree = onChooseCrawlerTree,
                        )
                    }
                }
            }
        }
    }

    private fun startOrRestartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val intent = Intent(this, DaemonService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }


    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }
}

@Composable
fun QrCode(url: String, modifier: Modifier = Modifier) {
    val bitmap = remember(url) {
        try {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                url,
                com.google.zxing.BarcodeFormat.QR_CODE,
                512,
                512
            )
            val size = matrix.width
            val bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bm.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bm
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code for connection URL",
            modifier = modifier.size(256.dp)
        )
    }
}

@Composable
fun MainScreen(
    serviceState: ServiceState,
    authToken: String,
    wifiIp: String,
    safStatus: SafStatus?,
    onGrantUsb: () -> Unit,
    crawlerNote: String = "",
    onChooseCrawlerTree: () -> Unit = {},
) {
    val context = LocalContext.current
    val showConnection = wifiIp.isNotEmpty() && authToken.isNotEmpty()
    val url = "http://$wifiIp:8090/?token=$authToken"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Torrent App",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Connection info (shown when network is available)
        if (showConnection) {
            Text(
                text = "Open this URL in your browser:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Waiting for network…",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "USB Storage",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        val status = safStatus
        Text(
            text = if (status?.granted == true) "USB: connected (${status.treeUri?.takeLast(24) ?: ""})"
                   else "USB: not granted",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onGrantUsb) { Text("Grant USB") }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Termux crawler migration",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Migrate the existing dht_crawler database from Termux into the app " +
                "(the control panel drives the scan/copy/verify steps).",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onChooseCrawlerTree) { Text("Choose Termux dht_crawler folder") }
        if (crawlerNote.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = crawlerNote, style = MaterialTheme.typography.bodySmall)
        }

        // QR code pinned to the bottom of the screen
        Spacer(modifier = Modifier.weight(1f))
        if (showConnection) {
            QrCode(url = url)
        }
    }
}

@Composable
fun WizardScreen(
    safStatus: SafStatus?,
    boundService: DaemonService.LocalBinder?,
    onGrantUsb: () -> Unit,
    onFinished: () -> Unit,
) {
    val cfg = boundService?.loadConfig()
    var txUser by remember { mutableStateOf(cfg?.transmission?.username ?: "torrentapp") }
    var txPass by remember { mutableStateOf(cfg?.transmission?.password ?: "") }
    var peerPort by remember { mutableStateOf((cfg?.transmission?.peerPort ?: 51413).toString()) }
    var dlSubpath by remember { mutableStateOf(cfg?.dataSubpath ?: "") }
    var ftpUser by remember { mutableStateOf(cfg?.ftp?.username ?: "kodi") }
    var ftpPass by remember { mutableStateOf(cfg?.ftp?.password ?: "") }
    var ftpPort by remember { mutableStateOf((cfg?.ftp?.port ?: 2121).toString()) }
    var ftpWifiOnly by remember { mutableStateOf(cfg?.ftp?.wifiOnly ?: ConfigDefaults.FTP.wifiOnly) }
    var crawlerEnabled by remember { mutableStateOf(cfg?.crawler?.enabled ?: false) }
    var r2Endpoint by remember { mutableStateOf(cfg?.backup?.r2Endpoint ?: "") }
    var r2Bucket by remember { mutableStateOf(cfg?.backup?.r2Bucket ?: "") }
    var r2ObjectKey by remember { mutableStateOf(cfg?.backup?.r2ObjectKey ?: ConfigDefaults.BACKUP_OBJECT_KEY) }
    var r2AccessKeyId by remember { mutableStateOf(cfg?.backup?.r2AccessKeyId ?: "") }
    var r2SecretAccessKey by remember { mutableStateOf("") }
    var uploadEnabled by remember { mutableStateOf(cfg?.backup?.uploadEnabled ?: false) }
    var bootstrapEnabled by remember { mutableStateOf(cfg?.backup?.bootstrapEnabled ?: false) }
    var bootstrapUrl by remember { mutableStateOf(cfg?.backup?.bootstrapUrl ?: "") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("First-run setup", style = MaterialTheme.typography.headlineMedium)

        Text("1. USB drive", style = MaterialTheme.typography.titleMedium)
        Text(if (safStatus?.granted == true) "USB: granted" else "USB: not granted",
             style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onGrantUsb, enabled = boundService != null) { Text("Grant USB drive") }

        Text("2. Transmission RPC", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(txUser, { txUser = it }, label = { Text("Username") })
        OutlinedTextField(txPass, { txPass = it }, label = { Text("Password (min 6)") })
        OutlinedTextField(peerPort, { peerPort = it }, label = { Text("Peer port") })
        OutlinedTextField(dlSubpath, { dlSubpath = it }, label = { Text("Data directory (under USB root; blank = root)") })

        Text("3. FTP (Kodi)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(ftpUser, { ftpUser = it }, label = { Text("FTP username") })
        OutlinedTextField(ftpPass, { ftpPass = it }, label = { Text("FTP password (min 6)") })
        OutlinedTextField(ftpPort, { ftpPort = it }, label = { Text("FTP port") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ftpWifiOnly, onCheckedChange = { ftpWifiOnly = it })
            Spacer(Modifier.width(8.dp)); Text("WiFi only")
        }

        Text("4. DHT crawler (optional)", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = crawlerEnabled, onCheckedChange = { crawlerEnabled = it })
            Spacer(Modifier.width(8.dp)); Text("Enable DHT crawler (default on)")
        }

        Text("5. Backup & Bootstrap (optional)", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = uploadEnabled, onCheckedChange = { uploadEnabled = it })
            Spacer(Modifier.width(8.dp)); Text("Enable R2 upload")
        }
        OutlinedTextField(r2Endpoint, { r2Endpoint = it }, label = { Text("R2 endpoint (https://<accountid>.r2.cloudflarestorage.com)") })
        OutlinedTextField(r2Bucket, { r2Bucket = it }, label = { Text("R2 bucket") })
        OutlinedTextField(r2ObjectKey, { r2ObjectKey = it }, label = { Text("R2 object key") })
        OutlinedTextField(r2AccessKeyId, { r2AccessKeyId = it }, label = { Text("R2 access key ID") })
        OutlinedTextField(r2SecretAccessKey, { r2SecretAccessKey = it }, label = { Text("R2 secret access key") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = bootstrapEnabled, onCheckedChange = { bootstrapEnabled = it })
            Spacer(Modifier.width(8.dp)); Text("Enable bootstrap download (fresh installs)")
        }
        OutlinedTextField(bootstrapUrl, { bootstrapUrl = it }, label = { Text("Bootstrap URL (https://…)") })
        Text("If set, the cache is downloaded before the crawler first starts.",
             style = MaterialTheme.typography.bodyMedium)
        if (errors.isNotEmpty()) {
            Column { errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) } }
        }

        Button(
            onClick = {
                val svc = boundService ?: return@Button
                val form = WizardSubmit.Form(
                    txUser, txPass, peerPort.toIntOrNull() ?: 0, dlSubpath,
                    ftpUser, ftpPass, ftpPort.toIntOrNull() ?: 0, ftpWifiOnly,
                    crawlerEnabled,
                    r2Endpoint, r2Bucket, r2ObjectKey, r2AccessKeyId,
                    // Blank secret keeps the current value (same as the dashboard form),
                    // so re-showing the wizard with upload enabled can't fail on it.
                    if (r2SecretAccessKey.isBlank()) (svc.loadConfig()?.backup?.r2SecretAccessKey ?: "")
                    else r2SecretAccessKey,
                    uploadEnabled, bootstrapEnabled, bootstrapUrl,
                )
                val outcome = WizardSubmit.submit(form)
                if (!outcome.ok) { errors = outcome.errors; return@Button }
                // Transmission apply first: it persists the shared data dir, which the
                // FTP controller construction below reads from the store (Stage 11).
                val newCfg = svc.loadConfig().copy(
                    transmission = outcome.transmission!!,
                    ftp = outcome.ftp!!,
                    backup = outcome.backup!!,
                    dataSubpath = form.dataSubpath,
                )
                val txR = svc.applyTransmissionSettings(newCfg, liveOnly = false)
                val ftpR = svc.applyFtpSettings(newCfg.ftp)
                if (txR.ok && ftpR.ok) {
                    if (outcome.crawlerEnabled) svc.setCrawlerEnabled(true)
                    svc.completeWizard(); onFinished()
                }
                else { errors = listOf(ftpR.message, txR.message).filter { it.isNotEmpty() } }
            },
            enabled = boundService != null && safStatus?.granted == true,
        ) { Text("Finish setup") }
    }
}
