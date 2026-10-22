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
import com.torrent.app.config.CrawlerTaxonomy
import com.torrent.app.config.ApplyResult
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.torrent.app.backup.WizardRestorePollPolicy
import com.torrent.app.ui.theme.TorrentPalette
import com.torrent.app.ui.theme.torrentBackground
import com.torrent.app.ui.theme.torrentColorScheme
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

        // Start foreground service (no-op if already running). Only on a FRESH launch:
        // a recreated activity (rotation) leaves the decision to the auto-start effect in
        // setContent below, so a user-requested shutdown is not undone by rotating.
        if (savedInstanceState == null) {
            val fgIntent = Intent(this, DaemonService::class.java)
            ContextCompat.startForegroundService(this, fgIntent)
        }

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

            // Auto-start when service is stopped, unless the user asked for a shutdown.
            // The flag is saveable: a rotation keeps the suppression, a fresh launch clears it.
            var shutdownRequested by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(serviceState.value, shutdownRequested) {
                if (shouldAutoStartService(serviceState.value, shutdownRequested)) {
                    startOrRestartService()
                }
            }
            val usbTreeLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri: Uri? ->
                uri?.let { boundService?.setUsbTree(it) }
            }
            // App-wide blue scheme + WebUI gradient: the gradient lives behind a
            // transparent Surface so the wizard and the QR main screen share it.
            MaterialTheme(colorScheme = torrentColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize().torrentBackground(),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
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
                            shutdownRequested = shutdownRequested,
                            onShutdown = {
                                // Arms the auto-start suppression before asking the service,
                                // so the Stopped state that ends the teardown cannot relaunch it.
                                shutdownRequested = true
                                // Stops every daemon and then closes the app: the service
                                // kills the process from its teardown completion hook.
                                boundService?.requestShutdownAndExit()
                            },
                            onStart = {
                                // Disarms the suppression: the LaunchedEffect above then sees
                                // `Stopped && !requested` and relaunches the daemons. Only this
                                // explicit tap clears the flag (a binder/poll clear would
                                // restart the daemons right after the user stopped them).
                                shutdownRequested = false
                            },
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
    shutdownRequested: Boolean,
    onShutdown: () -> Unit,
    onStart: () -> Unit,
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

        // Shutdown sits at the vertical centre: one weight spacer above it and the
        // existing one below (before the QR) keep the top content and the QR on the edges.
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = { if (showsStartAffordance(serviceState, shutdownRequested)) onStart() else onShutdown() },
            enabled = shutdownButtonEnabled(serviceState, shutdownRequested),
        ) { Text(shutdownButtonLabel(serviceState, shutdownRequested)) }

        // QR code pinned to the bottom of the screen
        Spacer(modifier = Modifier.weight(1f))
        if (showConnection) {
            QrCode(url = url)
        }
    }
}

/** Fresh-install pre-check of the wizard's categories step: media only. */
private val WizardDefaultSaveCategories = listOf("movie", "tv", "anime", "music")

/** Display label for a `save_categories` name ("tv" reads better as "TV"). */
private fun categoryLabel(name: String): String =
    if (name == "tv") "TV" else name.replaceFirstChar { it.uppercase() }

/**
 * Auto-start decision for [MainActivity]'s service effect: relaunch a stopped service
 * unless the user explicitly asked for a shutdown (which ends in `Stopped` too).
 */
internal fun shouldAutoStartService(state: ServiceState, shutdownRequested: Boolean): Boolean =
    !shutdownRequested && state is ServiceState.Stopped

/**
 * Once a graceful teardown has COMPLETED (state `Stopped` while the suppression is still
 * armed) the button stops being a Shutdown button and becomes the explicit way back: the
 * daemons only restart on this tap, never by a poll/binder callback (that would loop
 * straight back into a shutdown, or start them again right after the user stopped them).
 */
internal fun showsStartAffordance(state: ServiceState, shutdownRequested: Boolean): Boolean =
    shutdownRequested && state is ServiceState.Stopped

/**
 * The button is inert once a teardown is in flight (or already requested), so repeat taps
 * during the up-to-~78 s teardown cannot queue a second shutdown. The one exception is the
 * Start affordance after a completed teardown, which is enabled on purpose.
 */
internal fun shutdownButtonEnabled(state: ServiceState, shutdownRequested: Boolean): Boolean =
    if (shutdownRequested) showsStartAffordance(state, shutdownRequested)
    else state !is ServiceState.Stopping && state !is ServiceState.Stopped

/** Label for the button; shows the in-flight teardown state and the Start affordance. */
internal fun shutdownButtonLabel(state: ServiceState, shutdownRequested: Boolean): String =
    when {
        showsStartAffordance(state, shutdownRequested) -> "Start daemons"
        shutdownButtonEnabled(state, shutdownRequested) -> "Shutdown"
        else -> "Stopping…"
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
    var crawlerAdultDrop by remember { mutableStateOf((cfg?.crawler?.adultMode ?: "tag") == "drop") }
    // Non-empty store value wins; an empty list means "save all", in which case the
    // wizard shows (and therefore writes) the media-only fresh-install default.
    var saveCategories by remember {
        mutableStateOf(
            (cfg?.crawler?.saveCategories?.takeIf { it.isNotEmpty() }
                ?: WizardDefaultSaveCategories).toSet()
        )
    }
    var r2Endpoint by remember { mutableStateOf(cfg?.backup?.r2Endpoint ?: "") }
    var r2Bucket by remember { mutableStateOf(cfg?.backup?.r2Bucket ?: "") }
    var r2ObjectKey by remember { mutableStateOf(cfg?.backup?.r2ObjectKey ?: ConfigDefaults.BACKUP_OBJECT_KEY) }
    var r2AccessKeyId by remember { mutableStateOf(cfg?.backup?.r2AccessKeyId ?: "") }
    var r2SecretAccessKey by remember { mutableStateOf("") }
    var uploadEnabled by remember { mutableStateOf(cfg?.backup?.uploadEnabled ?: false) }
    var dbBackupEnabled by remember { mutableStateOf(cfg?.backup?.dbBackupEnabled ?: false) }
    var r2DbBucket by remember { mutableStateOf(cfg?.backup?.r2DbBucket ?: ConfigDefaults.DB_BACKUP_BUCKET) }
    var r2DbObjectPrefix by remember { mutableStateOf(cfg?.backup?.r2DbObjectPrefix ?: ConfigDefaults.DB_BACKUP_OBJECT_PREFIX) }
    var bootstrapEnabled by remember { mutableStateOf(cfg?.backup?.bootstrapEnabled ?: false) }
    var bootstrapUrl by remember { mutableStateOf(cfg?.backup?.bootstrapUrl ?: "") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    // Section 6 restore gate: session-only (NOT rememberSaveable, never persisted) —
    // a re-shown wizard must not inherit a stale "restored" verdict, and
    // "Finish setup" is unlocked only by a real SUCCESS from this session. It does
    // however have to survive a configuration change (rotation), otherwise the gate
    // silently re-locks while the restore keeps running in the service — hence the
    // ViewModel (activity ViewModelStore scope, dies with the process).
    val restoreVm = viewModel<WizardRestoreViewModel>()
    var restoreEnabled by restoreVm.restoreEnabled
    var restoreStatus by restoreVm.restoreStatus
    var restoreError by restoreVm.restoreError

    // Editing any field the restore consumes invalidates a stale verdict (a SUCCESS would
    // otherwise keep "Finish setup" unlocked for values that were never restored from).
    // A live RUNNING status is never hidden: the polling effect below stays attached, and the
    // service-anchored re-attach below must not resurrect the dropped verdict.
    fun invalidateRestore() {
        restoreVm.invalidate()
    }

    // One form builder shared by "Restore now" and "Finish setup" so both see
    // exactly the same mapping (including blank-secret-keeps-current below).
    val buildForm = {
        WizardSubmit.Form(
            txUsername = txUser,
            txPassword = txPass,
            peerPort = peerPort.toIntOrNull() ?: 0,
            dataSubpath = dlSubpath,
            ftpUsername = ftpUser,
            ftpPassword = ftpPass,
            ftpPort = ftpPort.toIntOrNull() ?: 0,
            ftpWifiOnly = ftpWifiOnly,
            crawlerEnabled = crawlerEnabled,
            crawlerAdultMode = if (crawlerAdultDrop) "drop" else "tag",
            crawlerSaveCategories =
                CrawlerTaxonomy.CATEGORIES.filter { it in saveCategories },
            r2Endpoint = r2Endpoint,
            r2Bucket = r2Bucket,
            r2ObjectKey = r2ObjectKey,
            r2AccessKeyId = r2AccessKeyId,
            // Blank secret keeps the current value (same as the dashboard form),
            // so re-showing the wizard with upload enabled can't fail on it.
            r2SecretAccessKey =
                if (r2SecretAccessKey.isBlank()) (boundService?.loadConfig()?.backup?.r2SecretAccessKey ?: "")
                else r2SecretAccessKey,
            uploadEnabled = uploadEnabled,
            dbBackupEnabled = dbBackupEnabled,
            r2DbBucket = r2DbBucket,
            r2DbObjectPrefix = r2DbObjectPrefix,
            bootstrapEnabled = bootstrapEnabled,
            bootstrapUrl = bootstrapUrl,
        )
    }

    // Persists the restore inputs, then starts the one-shot. The chip selection is
    // saved FIRST: the restore's category filter reads the persisted selection, so the
    // chips must reach the store before startDbRestore queues the restore — otherwise
    // wizard display, persisted config and the filter could disagree.
    // A rejected save is a form error, not a restore attempt: it clears any previous
    // verdict so a stale SUCCESS can't keep "Finish setup" unlocked — but a restore
    // that is still RUNNING stays visible, or its polling effect would detach mid-restore.
    fun doRestoreNow() {
        val svc = boundService ?: return
        // The form only emits known names, so normalisation can only reorder/drop — the
        // error branch is unreachable in practice and aborts rather than restoring from
        // a store that disagrees with the chips.
        val (categoryErrors, saveCategories) =
            WizardSubmit.normalizeSaveCategories(buildForm().crawlerSaveCategories)
        val r = if (categoryErrors.isNotEmpty()) {
            ApplyResult(false, categoryErrors.joinToString("; "), false)
        } else {
            val saved = svc.saveCrawlerSaveCategoriesForRestore(saveCategories)
            if (!saved.ok) saved else svc.saveBackupForRestore(WizardSubmit.backupSettings(buildForm()))
        }
        if (!r.ok) {
            restoreError = r.message
            // Dropped verdict is remembered by the ViewModel, so the service-anchored
            // re-attach below cannot resurrect it on the next resume.
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

    // Follows RUNNING → SUCCESS/FAILED without blocking the main thread: the
    // restore itself runs on the service's crawlerControlExecutor, and these
    // status reads are cheap volatile/isBusy snapshots. Keyed on the ADOPTED snapshot,
    // so the re-attach above is what starts the loop; every tick adopts through the same
    // policy and always reads the latest binder.
    LaunchedEffect(restoreStatus?.running) {
        while (isActive && WizardRestorePollPolicy.shouldPoll(restoreStatus)) {
            delay(1000)
            adoptServiceStatus()
        }
    }

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = crawlerAdultDrop, onCheckedChange = { crawlerAdultDrop = it })
            Spacer(Modifier.width(8.dp)); Text("Drop adult content (default: tag only)")
        }
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

        Text("5. Backup & Bootstrap (optional)", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = uploadEnabled, onCheckedChange = { uploadEnabled = it })
            Spacer(Modifier.width(8.dp)); Text("Enable R2 upload")
        }
        OutlinedTextField(r2Endpoint, { r2Endpoint = it; invalidateRestore() }, label = { Text("R2 endpoint (https://<accountid>.r2.cloudflarestorage.com)") })
        OutlinedTextField(r2Bucket, { r2Bucket = it }, label = { Text("R2 bucket") })
        OutlinedTextField(r2ObjectKey, { r2ObjectKey = it }, label = { Text("R2 object key") })
        OutlinedTextField(r2AccessKeyId, { r2AccessKeyId = it; invalidateRestore() }, label = { Text("R2 access key ID") })
        OutlinedTextField(r2SecretAccessKey, { r2SecretAccessKey = it; invalidateRestore() }, label = { Text("R2 secret access key") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = dbBackupEnabled, onCheckedChange = { dbBackupEnabled = it })
            Spacer(Modifier.width(8.dp)); Text("Enable daily DB dump upload (uses the R2 credentials above)")
        }
        OutlinedTextField(r2DbBucket, { r2DbBucket = it; invalidateRestore() }, label = { Text("R2 DB bucket") })
        OutlinedTextField(r2DbObjectPrefix, { r2DbObjectPrefix = it; invalidateRestore() }, label = { Text("R2 DB object prefix") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = bootstrapEnabled, onCheckedChange = { bootstrapEnabled = it })
            Spacer(Modifier.width(8.dp)); Text("Enable bootstrap download (fresh installs)")
        }
        OutlinedTextField(bootstrapUrl, { bootstrapUrl = it }, label = { Text("Bootstrap URL (https://…)") })
        Text("If set, the cache is downloaded before the crawler first starts.",
             style = MaterialTheme.typography.bodyMedium)

        Text("6. Restore crawler DB from R2 (optional)", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = restoreEnabled,
                onCheckedChange = { restoreEnabled = it },
                // A restore in flight owns the flag: it is re-attached ON from the service
                // snapshot and must not be switched off (that would unlock "Finish setup"
                // mid-restore). FAILED/IDLE leave it editable again.
                enabled = WizardRestorePollPolicy.restoreSwitchEnabled(restoreStatus),
            )
            Spacer(Modifier.width(8.dp))
            Text("Restore the crawler DB from the R2 dump chain (uses the R2 credentials above)")
        }
        Button(
            onClick = { doRestoreNow() },
            enabled = WizardRestorePollPolicy.restoreNowEnabled(
                boundService != null, restoreEnabled, restoreStatus),
        ) { Text("Restore now") }
        // Prefix by state so a refused/not-configured attempt can never read as success.
        // A RUNNING status is checked first: a mid-restore double-tap answers
        // REFUSED_RUNNING with running=true and must render as running, not "failed".
        val st = restoreStatus
        if (st != null) {
            Text(
                text = if (st.running) "Restore running — ${st.message}"
                else when (st.state) {
                    com.torrent.app.backup.DbRestoreState.SUCCESS -> "Restore succeeded — ${st.message}"
                    com.torrent.app.backup.DbRestoreState.FAILED,
                    com.torrent.app.backup.DbRestoreState.NOT_CONFIGURED,
                    com.torrent.app.backup.DbRestoreState.REFUSED_BUSY,
                    com.torrent.app.backup.DbRestoreState.REFUSED_RUNNING -> "Restore failed — ${st.message}"
                    else -> "Restore running — ${st.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (st.running) MaterialTheme.colorScheme.onSurface
                else when (st.state) {
                    com.torrent.app.backup.DbRestoreState.SUCCESS ->
                        TorrentPalette.color(TorrentPalette.OK)
                    com.torrent.app.backup.DbRestoreState.FAILED,
                    com.torrent.app.backup.DbRestoreState.NOT_CONFIGURED,
                    com.torrent.app.backup.DbRestoreState.REFUSED_BUSY,
                    com.torrent.app.backup.DbRestoreState.REFUSED_RUNNING -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            // The per-object counter is all the progress math needed — no ETA/rate logic.
            // Both counts must be known: a RUNNING restore that is still listing has no M.
            val done = st.objectsDone
            val total = st.objectCount
            com.torrent.app.backup.RestoreProgressText.format(done, total)?.let {
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
        if (errors.isNotEmpty()) {
            Column { errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) } }
        }

        Button(
            onClick = {
                val svc = boundService ?: return@Button
                val form = buildForm()
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
                    // Written before the start below (same serialized executor), so the
                    // first crawler launch already sees the chosen adult_mode. The
                    // categories write is queued ahead of it for the same reason: the
                    // crawler reads save_categories only at startup. Both are stored
                    // even when the crawler is started disabled.
                    svc.setCrawlerSaveCategories(outcome.crawlerSaveCategories)
                    svc.setCrawlerAdultMode(outcome.crawlerAdultMode)
                    if (outcome.crawlerEnabled) svc.setCrawlerEnabled(true)
                    svc.completeWizard(); onFinished()
                }
                else { errors = listOf(ftpR.message, txR.message).filter { it.isNotEmpty() } }
            },
            enabled = boundService != null && safStatus?.granted == true &&
                com.torrent.app.backup.WizardRestoreGate.canFinish(restoreEnabled, restoreStatus?.state),
        ) { Text("Finish setup") }
    }
}
