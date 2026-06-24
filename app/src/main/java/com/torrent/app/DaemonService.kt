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
import kotlinx.coroutines.delay
import java.io.File
import android.net.Uri
import com.torrent.app.storage.SafStatus
import com.torrent.app.storage.SafStorageGateway
import com.torrent.app.storage.SafValidationResult
import com.torrent.app.storage.SafValidator
import com.torrent.app.storage.SafIpcRequestHandler
import com.torrent.app.storage.SafIpcServer
import com.torrent.app.storage.UsbPermissionManager
import com.torrent.app.ftp.FtpCredentials
import com.torrent.app.ftp.FtpServerController
import com.torrent.app.ftp.FtpState
import com.torrent.app.ftp.FtpStatus
import com.torrent.app.ftp.WifiGateMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    private var ftpController: FtpServerController? = null
    private lateinit var wifiGateMonitor: WifiGateMonitor
    private var ftpObservingJob: Job? = null
    private lateinit var safSocketFile: java.io.File
    private lateinit var safIpcServer: SafIpcServer
    private lateinit var transmissionConfig: TransmissionConfig
    private lateinit var configStore: com.torrent.app.config.ConfigStore
    @Volatile var lastApplyRolledBack: Boolean = false
    private var rollbackJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        configStore = com.torrent.app.config.ConfigStore(this)
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
            ftpStatus = ::getFtpStatus,
            loadConfig = ::loadConfig,
            wizardCompleted = { configStore.load().wizardCompleted },
            rpcSummary = ::transmissionRpcSummary,
            usbFreeSpace = ::usbFreeSpace,
            lastApplyRolledBack = { lastApplyRolledBack },
            restartDaemon = ::restartDaemon,
            stopDaemon = ::stopDaemon,
            startDaemon = ::startDaemon,
            applyTransmission = ::applyTransmissionSettings,
            applyFtp = ::applyFtpSettings,
)
        wifiGateMonitor = WifiGateMonitor(this)
        gateway?.let {
            val ftp = configStore.load().ftp
            ftpController = FtpServerController(it, FtpCredentials(configStore), port = ftp.port, rootRelPath = ftp.exposedRoot)
        }
    }

    private fun ensureUsbGateway() {
        if (gateway != null) return
        usbPermissionManager.treeUri()?.let { uri ->
            gateway = SafStorageGateway(this, uri)
            val ftp = configStore.load().ftp
            ftpController?.stop()
            ftpController = FtpServerController(gateway!!, FtpCredentials(configStore),
                port = ftp.port, rootRelPath = ftp.exposedRoot)
        }
    }

    inner class LocalBinder : android.os.Binder() {
        val serviceState: StateFlow<ServiceState> = this@DaemonService.state
        fun getAuthToken(): String = this@DaemonService.getAuthToken()
        fun getWifiIpAddress(): String? = this@DaemonService.getWifiIpAddress()
        fun getDaemonStatuses(): List<ProcessStatus> = this@DaemonService.getDaemonStatuses()
        fun setUsbTree(uri: Uri) = this@DaemonService.setUsbTree(uri)
        fun clearUsbTree() = this@DaemonService.clearUsbTree()
        fun getSafStatus(): SafStatus = this@DaemonService.getSafStatus()
        fun getFtpStatus(): FtpStatus = this@DaemonService.getFtpStatus()
        suspend fun runSafValidation(): SafValidationResult = this@DaemonService.runSafValidation()
        fun loadConfig(): com.torrent.app.config.AppConfig = this@DaemonService.loadConfig()
        fun applyTransmissionSettings(s: com.torrent.app.config.TransmissionSettings, liveOnly: Boolean) =
            this@DaemonService.applyTransmissionSettings(s, liveOnly)
        fun applyFtpSettings(s: com.torrent.app.config.FtpSettings) = this@DaemonService.applyFtpSettings(s)
        fun restartDaemon(label: String) = this@DaemonService.restartDaemon(label)
        fun stopDaemon(label: String) = this@DaemonService.stopDaemon(label)
        fun startDaemon(label: String) = this@DaemonService.startDaemon(label)
        fun transmissionRpcSummary(): com.torrent.app.transmission.TransmissionSummary? = this@DaemonService.transmissionRpcSummary()
        fun usbFreeSpace(): Long? = this@DaemonService.usbFreeSpace()
        fun completeWizard() = this@DaemonService.completeWizard()
        fun lastApplyRolledBack(): Boolean = this@DaemonService.lastApplyRolledBack
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _state.value = ServiceState.Starting

        ensureUsbGateway()
        acquireLocks()
        startForegroundWithNotification()

        _state.value = ServiceState.Running
        controlServer.start()
        safIpcServer.start()
        wifiGateMonitor.start()
        observeWifiForFtp()
        startSupervisors()
        return START_STICKY
    }

    override fun onDestroy() {
        rollbackJob?.cancel()
        controlServer.stop()
        stopSupervisors()
        safIpcServer.stop()
        ftpObservingJob?.cancel()
        ftpController?.stop()
        wifiGateMonitor.stop()
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
        val g = SafStorageGateway(this, uri)
        gateway = g
        val ftp = configStore.load().ftp
        ftpController?.stop()
        ftpController = FtpServerController(g, FtpCredentials(configStore), port = ftp.port, rootRelPath = ftp.exposedRoot)
        maybeStartFtp()
    }

    fun clearUsbTree() {
        usbPermissionManager.clear()
        ftpController?.stop()
        ftpController = null
        gateway = null
    }

    fun getSafStatus(): SafStatus {
        val uri = usbPermissionManager.treeUri()
        return SafStatus(
            granted = uri != null,
            gatewayPresent = gateway != null,
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

    private fun observeWifiForFtp() {
        ftpObservingJob?.cancel()
        ftpObservingJob = MainScope().launch {
            wifiGateMonitor.state.collect { ws ->
                val ftp = configStore.load().ftp
                if (ws.wifi && ws.ip != null) maybeStartFtp(ws.ip)
                else if (ftp.wifiOnly) ftpController?.stop()
            }
        }
    }

    private fun maybeStartFtp(ip: String? = null) {
        if (!configStore.load().wizardCompleted) return
        val ctrl = ftpController ?: return
        val ws = wifiGateMonitor.state.value
        val useIp = ip ?: ws.ip
        if (ws.wifi && useIp != null) {
            try { ctrl.rebind(useIp) }
            catch (e: Exception) { android.util.Log.w("DaemonService", "ftp start failed: ${e.message}") }
        }
    }

    fun getFtpStatus(): FtpStatus {
        val ctrl = ftpController
        val ws = wifiGateMonitor.state.value
        val creds = FtpCredentials(configStore)
        return FtpStatus(
            running = ctrl?.state == FtpState.RUNNING,
            state = (ctrl?.state ?: FtpState.STOPPED).name,
            ip = ctrl?.ip,
            port = configStore.load().ftp.port,
            username = creds.username(),
            password = if (ctrl?.state == FtpState.RUNNING) creds.password() else null,
            wifiUp = ws.wifi,
        )
    }

    fun startConfiguredDaemons() {
        startTransmissionSupervisor()
        maybeStartFtp()
    }

    fun applyTransmissionSettings(s: com.torrent.app.config.TransmissionSettings, liveOnly: Boolean): com.torrent.app.config.ApplyResult {
        val vr = com.torrent.app.config.ConfigValidator.validateTransmission(s)
        if (!vr.ok) return com.torrent.app.config.ApplyResult(false, vr.errors.joinToString("; "), false)
        val before = configStore.load().transmission
        configStore.snapshotLastKnownGood(before)
        configStore.saveTransmission(s)
        lastApplyRolledBack = false
        if (liveOnly) {
            val rpc = com.torrent.app.transmission.TransmissionRpcClient(before.username, before.password)
            val args = JSONObject()
                .put("speed-limit-down", s.speedLimitDown)
                .put("speed-limit-up", s.speedLimitUp)
                .put("alt-speed-down", s.altSpeedDown)
                .put("alt-speed-up", s.altSpeedUp)
            val ok = rpc.sessionSet(args)
            return com.torrent.app.config.ApplyResult(ok, if (ok) "applied via RPC" else "daemon not reachable; restart required", false)
        }
        val sup = supervisors.firstOrNull { it.status.value.label == "transmission" }
        sup?.stop()
        transmissionConfig.ensureSettingsJson()
        sup?.start()
        val statusAtApply = sup?.status?.value ?: ProcessStatus("transmission", ProcessState.STOPPED)
        launchRollbackWatcher(sup, statusAtApply, before)
        return com.torrent.app.config.ApplyResult(true, "applied; restarting transmission", false)
    }

    fun applyFtpSettings(s: com.torrent.app.config.FtpSettings): com.torrent.app.config.ApplyResult {
        val vr = com.torrent.app.config.ConfigValidator.validateFtp(s)
        if (!vr.ok) return com.torrent.app.config.ApplyResult(false, vr.errors.joinToString("; "), false)
        configStore.saveFtp(s)
        ftpController?.stop()
        val g = gateway
        if (g != null) {
            ftpController = FtpServerController(g, FtpCredentials(configStore), port = s.port, rootRelPath = s.exposedRoot)
            maybeStartFtp()
        }
        return com.torrent.app.config.ApplyResult(true, if (g != null) "applied" else "applied; FTP starts when USB granted", false)
    }

    fun restartDaemon(label: String): Boolean {
        val sup = supervisors.firstOrNull { it.status.value.label == label } ?: return false
        sup.restart(); return true
    }
    fun stopDaemon(label: String): Boolean {
        val sup = supervisors.firstOrNull { it.status.value.label == label } ?: return false
        sup.stop(); return true
    }
    fun startDaemon(label: String): Boolean {
        val sup = supervisors.firstOrNull { it.status.value.label == label } ?: return false
        sup.start(); return true
    }

    fun transmissionRpcSummary(): com.torrent.app.transmission.TransmissionSummary? {
        val tx = configStore.load().transmission
        val rpc = com.torrent.app.transmission.TransmissionRpcClient(tx.username, tx.password)
        val sess = rpc.sessionGet() ?: return null
        return com.torrent.app.transmission.TransmissionSummary(
            downloadDir = sess.optString("downloadDir"),
            speedLimitDown = sess.optInt("speed-limit-down", 0),
            speedLimitUp = sess.optInt("speed-limit-up", 0),
            torrents = rpc.torrentGetSummary())
    }

    fun usbFreeSpace(): Long? {
        val g = gateway ?: return null
        val b = g.availableBytes()
        return if (b == Long.MAX_VALUE) null else b
    }

    fun loadConfig(): com.torrent.app.config.AppConfig = configStore.load()
    fun completeWizard() { configStore.setWizardCompleted(); startConfiguredDaemons() }

    private fun launchRollbackWatcher(sup: ProcessSupervisor?, statusAtApply: ProcessStatus, previous: com.torrent.app.config.TransmissionSettings) {
        rollbackJob?.cancel()
        if (sup == null) return
        rollbackJob = MainScope().launch {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                delay(2000)
                val cur = sup.status.value
                if (cur.state == ProcessState.RUNNING) return@launch
                if (RollbackPolicy.shouldRollback(statusAtApply, cur, 3)) {
                    android.util.Log.w("DaemonService", "transmission crash-loop after apply; rolling back")
                    lastApplyRolledBack = true
                    configStore.saveTransmission(previous)
                    transmissionConfig.ensureSettingsJson()
                    sup.stop(); delay(200); sup.start()
                    return@launch
                }
            }
        }
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

        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
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
        if (supervisors.isNotEmpty()) return
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val workDir = File(filesDir, "daemons")
        val logDir = File(filesDir, "logs")
        val probe = ProcessSupervisor("probe", "libprobe.so", nativeLibraryDir = nativeLibDir,
            workDir = workDir, logDir = logDir)
        probe.start(); supervisors.add(probe)
        transmissionConfig = TransmissionConfig(this, configStore)
        if (configStore.load().wizardCompleted) startTransmissionSupervisor()
    }

    private fun startTransmissionSupervisor() {
        if (supervisors.any { it.status.value.label == "transmission" }) return
        if (gateway == null) {
            android.util.Log.w("DaemonService", "transmission supervisor requested but gateway is null; " +
                "daemon will see ENODEV (-19) until USB is granted")
        }
        transmissionConfig.ensureSettingsJson()
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val workDir = File(filesDir, "daemons"); val logDir = File(filesDir, "logs")
        val transmission = ProcessSupervisor(
            label = "transmission", binaryName = "libtransmissiond.so",
            args = listOf("--foreground", "--config-dir", transmissionConfig.configDir.absolutePath,
                "--logfile", File(logDir, "transmission.log").absolutePath, "--log-level=info"),
            nativeLibraryDir = nativeLibDir, workDir = workDir, logDir = logDir,
            extraEnv = mapOf("TORRENT_SAF_SOCKET" to safSocketFile.absolutePath,
                "TMPDIR" to File(filesDir, "tmp").apply { mkdirs() }.absolutePath),
            selfLogging = true)
        transmission.start(); supervisors.add(transmission)
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