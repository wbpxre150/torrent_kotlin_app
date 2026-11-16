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
import com.torrent.app.config.ConfigStore
import com.torrent.app.net.CloudflaredTunnelState
import com.torrent.app.net.connectionUrl
import com.torrent.app.storage.SafStatus
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import com.torrent.app.wizard.WizardFlow
import com.torrent.app.ui.theme.torrentBackground
import com.torrent.app.ui.theme.torrentColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var wifiIp = MutableStateFlow("")
    private val safStatus = MutableStateFlow<SafStatus?>(null)
    // Read synchronously from the store on first use so the FIRST frame is already right: a
    // first-run install shows step 1 immediately and a returning user the QR main screen, with
    // no flash of the other screen while the binder connects (the binder callback and the 3 s
    // poll below then keep it in sync). ConfigStore is a plain SharedPreferences wrapper, so
    // this needs no service. `by lazy` rather than a plain initialiser: property initialisers
    // run before the activity is attached, and ConfigStore needs the context. The first read
    // is the `collectAsState` inside `setContent`, i.e. after `onCreate`.
    private val wizardCompleted by lazy {
        MutableStateFlow(ConfigStore(this).load().wizardCompleted)
    }
    private val serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    private var authToken = MutableStateFlow("")
    // Stage 4: the tunnel's connected state + auto-detected public hostname. Pushed
    // from the binder (see onServiceConnected) so the link and the QR flip without
    // waiting for the 3 s poll, and back to the LAN URL on disconnect.
    private val tunnel = MutableStateFlow(CloudflaredTunnelState())

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
            lifecycleScope.launch {
                b.tunnel.collect { tunnel.value = it }
            }
            authToken.value = b.getAuthToken()
            wifiIp.value = b.getWifiIpAddress() ?: ""
            safStatus.value = b.getSafStatus()
            wizardCompleted.value = b.loadConfig().wizardCompleted
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            serviceState.value = ServiceState.Stopped
            tunnel.value = CloudflaredTunnelState()
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
            val tunnelState by tunnel.collectAsState()

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
                        WizardFlow(
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
                            tunnelConnected = tunnelState.connected,
                            publicHostname = tunnelState.publicHostname,
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
    tunnelConnected: Boolean,
    publicHostname: String,
    safStatus: SafStatus?,
    onGrantUsb: () -> Unit,
    shutdownRequested: Boolean,
    onShutdown: () -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    // Stage 4: the tunnel's https URL works from anywhere, so it wins while the
    // tunnel is connected; the LAN URL stays the fallback. The QR bitmap is
    // `remember(url)`-keyed, so it re-encodes on the flip by itself.
    // Stage 7 (review P1): both branches are conditional now. With the tunnel
    // connected, its hostname undetected and no wifi IP — the cellular flagship
    // case — there is no usable URL at all, so the link and the QR are hidden
    // instead of rendering a dead `http://:8090/?token=…`. A blank token hides
    // it too: the `takeIf` folds that into `url`, so one null check covers
    // `url != null && authToken.isNotEmpty()` — `url` is the single gate the
    // link text, the click intent and the QR all read.
    val url = connectionUrl(tunnelConnected, publicHostname, wifiIp)
        ?.takeIf { authToken.isNotEmpty() }
        ?.let { "$it/?token=$authToken" }

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

        // Connection info (shown when a usable URL exists)
        if (url != null) {
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
        if (url != null) {
            QrCode(url = url)
        }
    }
}

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

