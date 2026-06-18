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
import com.torrent.app.storage.SafValidationResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    private val safValidation = MutableStateFlow<SafValidationResult?>(null)
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
                }
            }
        }

        setContent {
            val state by serviceState.collectAsState()
            val token by authToken.collectAsState()
            val ip by wifiIp.collectAsState()

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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        serviceState = state,
                        authToken = token,
                        wifiIp = ip,
                        safStatus = safStatus.collectAsState().value,
                        safValidation = safValidation.collectAsState().value,
                        onGrantUsb = { usbTreeLauncher.launch(null) },
                        onValidateUsb = {
                            lifecycleScope.launch {
                                val svc = boundService
                                safValidation.value = SafValidationResult(
                                    ok = false, message = "running…",
                                    throughputMBs = null, offsetBytes = 0, fat32Warning = false)
                                safValidation.value = svc?.runSafValidation()
                                    ?: SafValidationResult(
                                        ok = false, message = "service not bound",
                                        throughputMBs = null, offsetBytes = 0, fat32Warning = false)
                            }
                        },
                    )
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
    safValidation: SafValidationResult?,
    onGrantUsb: () -> Unit,
    onValidateUsb: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Torrent App",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Connection info (shown when network is available)
        if (wifiIp.isNotEmpty() && authToken.isNotEmpty()) {
            val url = "http://$wifiIp:8090/?token=$authToken"
            Text(
                text = "Open this URL on another device:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            QrCode(url = url)
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
        Row {
            Button(onClick = onGrantUsb) { Text("Grant USB") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onValidateUsb,
                enabled = status?.granted == true,
            ) { Text("Validate SAF fd") }
        }
        safValidation?.let { v ->
            Spacer(modifier = Modifier.height(12.dp))
            SelectionContainer {
                Column {
                    Text(
                        text = if (v.ok) "VALIDATION: PASS" else "VALIDATION: FAIL",
                        color = if (v.ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                    Text(text = v.message, style = MaterialTheme.typography.bodySmall)
                    v.throughputMBs?.let { mbps ->
                        Text(text = "throughput: %.1f MB/s".format(mbps),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (v.fat32Warning) {
                        Text(
                            text = "FAT32 suspected (4 GiB cap). Reformat the drive as exFAT.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
