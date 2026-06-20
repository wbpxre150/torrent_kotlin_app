package com.torrent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import android.net.Uri
import com.torrent.app.storage.SafStatus
import com.torrent.app.storage.SafStorageGateway
import com.torrent.app.storage.SafValidationResult
import com.torrent.app.storage.SafValidator
import com.torrent.app.storage.SafIpcRequestHandler
import com.torrent.app.storage.SafIpcServer
import com.torrent.app.storage.UsbPermissionManager

sealed class ServiceState {
    data object Starting : ServiceState()
    data object Running : ServiceState()
    data object Stopping : ServiceState()
    data object Stopped : ServiceState()
}

class DaemonService : Service() {

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val state: StateFlow<ServiceState> = _state

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var controlServer: ControlServer
    private lateinit var tokenManager: TokenManager
    private val supervisors = mutableListOf<ProcessSupervisor>()
    private lateinit var usbPermissionManager: UsbPermissionManager
    private var gateway: SafStorageGateway? = null
    private lateinit var safSocketFile: java.io.File
    private lateinit var safIpcServer: SafIpcServer
    private lateinit var transmissionConfig: TransmissionConfig

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tokenManager = TokenManager(this)
        usbPermissionManager = UsbPermissionManager(this)
        usbPermissionManager.treeUri()?.let { uri ->
            gateway = SafStorageGateway(this, uri)
        }
        safSocketFile = java.io.File(filesDir, "saf-bridge.sock")
        safIpcServer = SafIpcServer(safSocketFile, SafIpcRequestHandler { gateway })
        controlServer = ControlServer(
            tokenManager = tokenManager,
            serviceState = _state,
            daemonStatuses = ::getDaemonStatuses,
            safStatus = ::getSafStatus,
            runSafValidation = ::runSafValidation,
        )
    }

    inner class LocalBinder : android.os.Binder() {
        val serviceState: StateFlow<ServiceState> = this@DaemonService.state
        fun getAuthToken(): String = this@DaemonService.getAuthToken()
        fun getWifiIpAddress(): String? = this@DaemonService.getWifiIpAddress()
        fun getDaemonStatuses(): List<ProcessStatus> = this@DaemonService.getDaemonStatuses()
        fun setUsbTree(uri: Uri) = this@DaemonService.setUsbTree(uri)
        fun clearUsbTree() = this@DaemonService.clearUsbTree()
        fun getSafStatus(): SafStatus = this@DaemonService.getSafStatus()
        suspend fun runSafValidation(): SafValidationResult = this@DaemonService.runSafValidation()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _state.value = ServiceState.Starting

        acquireLocks()
        startForegroundWithNotification()

        _state.value = ServiceState.Running
        controlServer.start()
        safIpcServer.start()
        startSupervisors()
        return START_STICKY
    }

    override fun onDestroy() {
        controlServer.stop()
        stopSupervisors()
        safIpcServer.stop()
        _state.value = ServiceState.Stopping
        releaseLocks()
        _state.value = ServiceState.Stopped
        super.onDestroy()
    }

    fun getAuthToken(): String = tokenManager.getToken()

    fun getWifiIpAddress(): String? {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    fun getDaemonStatuses(): List<ProcessStatus> = supervisors.map { it.status.value }

    fun setUsbTree(uri: Uri) {
        usbPermissionManager.grant(uri)
        gateway = SafStorageGateway(this, uri)
    }

    fun clearUsbTree() {
        usbPermissionManager.clear()
        gateway = null
    }

    fun getSafStatus(): SafStatus {
        val uri = usbPermissionManager.treeUri()
        return SafStatus(
            granted = uri != null,
            treeUri = uri?.toString(),
            rootSummary = if (uri != null) "USB (SAF tree)" else null,
        )
    }

    suspend fun runSafValidation(): SafValidationResult {
        val g = gateway ?: return SafValidationResult(
            ok = false, message = "USB tree not granted",
            throughputMBs = null)
        return SafValidator(g).run()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "torrent_app:wakelock"
        ).apply {
            acquire()
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "torrent_app:wifilock"
        ).apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val wifiIp = getWifiIpAddress()
        val token = tokenManager.getToken()
        val contentText = if (wifiIp != null) {
            "http://$wifiIp:8090/?token=$token"
        } else {
            "Services active — tap to open"
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Torrent App running")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startSupervisors() {
        if (supervisors.isNotEmpty()) return  // guard against double-start from START_STICKY
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val workDir = File(filesDir, "daemons")
        val logDir = File(filesDir, "logs")

        // probe — minimal test binary (replaced by transmission in Stage 04)
        val probe = ProcessSupervisor(
            label = "probe",
            binaryName = "libprobe.so",
            nativeLibraryDir = nativeLibDir,
            workDir = workDir,
            logDir = logDir,
        )
        probe.start()
        supervisors.add(probe)

        // transmission-daemon (Stage 04): internal storage only, RPC on :9091 with auth.
        transmissionConfig = TransmissionConfig(this)
        transmissionConfig.ensureSettingsJson()
        val transmission = ProcessSupervisor(
            label = "transmission",
            binaryName = "libtransmissiond.so",
            args = listOf("--foreground", "--config-dir", transmissionConfig.configDir.absolutePath),
            nativeLibraryDir = nativeLibDir,
            workDir = workDir,
            logDir = logDir,
            extraEnv = mapOf("TORRENT_SAF_SOCKET" to safSocketFile.absolutePath),
        )
        transmission.start()
        supervisors.add(transmission)
    }

    private fun stopSupervisors() {
        for (s in supervisors) {
            s.stop()
        }
        supervisors.clear()
    }

    companion object {
        const val CHANNEL_ID = "daemon_service"
        const val NOTIFICATION_ID = 1
    }
}
