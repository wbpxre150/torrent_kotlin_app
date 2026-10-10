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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import org.json.JSONObject
import com.torrent.app.config.CrawlerGate
import com.torrent.app.migration.CrawlerTreeGrant
import com.torrent.app.migration.TermuxMigration

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
    // Authenticated reverse proxy for the crawler search UI (plan 10 §3);
    // null when lanDirect is set. Mutated from the main thread and the
    // crawler-control executor only (see syncCrawlerProxy).
    @Volatile private var crawlerProxy: CrawlerProxyServer? = null
    private lateinit var tokenManager: TokenManager
    // Mutated from the main thread, the crawler-control thread and ControlServer threads.
    private val supervisors = java.util.concurrent.CopyOnWriteArrayList<ProcessSupervisor>()
    private lateinit var usbPermissionManager: UsbPermissionManager
    @Volatile private var gateway: SafStorageGateway? = null
    @Volatile private var ftpController: FtpServerController? = null
    private lateinit var wifiGateMonitor: WifiGateMonitor
    private var ftpObservingJob: Job? = null
    // Plan 07 §2.6: FTP start/stop/rebind (socket bind, MINA dispose) run on a
    // dedicated single-thread executor, never on the service main thread.
    private val ftpControlExecutor = Executors.newSingleThreadExecutor(ThreadFactory {
        Thread(it, "ftp-control").apply { isDaemon = true }
    })

    private lateinit var safSocketFile: java.io.File
    private lateinit var safIpcServer: SafIpcServer
    private lateinit var transmissionConfig: TransmissionConfig
    private lateinit var configStore: com.torrent.app.config.ConfigStore
    @Volatile var lastApplyRolledBack: Boolean = false
    private var rollbackJob: Job? = null

    // Crawler apply job (plan 10 step 2): the job runs on the crawler-control
    // executor; these are its observable status + the wifi-pause flag.
    @Volatile var crawlerApplyStatus = CrawlerApplyStatus()
    @Volatile var crawlerPausedNoWifi = false
    private var crawlerObservingJob: Job? = null
    // One-shot maintenance runner + its coordinator (plan 10 step 5); both are
    // created in onCreate() once applicationInfo is available.
    private lateinit var oneShotRunner: OneShotRunner
    private lateinit var crawlerMaintenance: CrawlerMaintenance
    // Termux migration backend (plan 10 step 6): background jobs on the
    // crawler-control executor; the SAF tree grant is persisted separately so
    // a long copy survives a service restart.
    private lateinit var crawlerTreeGrant: CrawlerTreeGrant
    private lateinit var crawlerMigration: TermuxMigration

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        configStore = com.torrent.app.config.ConfigStore(this)
        tokenManager = TokenManager(this)
        usbPermissionManager = UsbPermissionManager(this)
        usbPermissionManager.treeUri()?.let { uri ->
            gateway = SafStorageGateway(this, uri)
            ftpController = newFtpController(gateway!!)
        }
        safSocketFile = java.io.File(filesDir, "saf-bridge.sock")
        safIpcServer = SafIpcServer(safSocketFile, SafIpcRequestHandler { gateway })
        // One-shot maintenance runner + coordinator (plan 10 step 5). The
        // runner execs the same libdhtcrawler.so the supervisor supervises;
        // the coordinator serializes actions on the crawler-control executor.
        oneShotRunner = OneShotRunner(
            binaryName = "libdhtcrawler.so",
            nativeLibraryDir = applicationInfo.nativeLibraryDir,
            extraEnv = mapOf("TMPDIR" to File(filesDir, "tmp").apply { mkdirs() }.absolutePath),
        )
        crawlerMaintenance = CrawlerMaintenance(
            oneShotRunner = oneShotRunner,
            workDir = File(filesDir, "crawler"),
            executor = crawlerControlExecutor,
            dbSize = { crawlerDbSize() ?: 0L },
            freeSpace = ::internalFreeSpace,
            stopCrawler = { stopCrawlerSupervisors() },
            startCrawlerIfEnabled = ::startCrawlerIfEnabled,
            busy = { crawlerApplyStatus.phase.isActive || crawlerMigration.isBusy() },
        )
        // Termux migration backend (plan 10 step 6): scan/copy/verify/swap/
        // start/confirm/revert as background jobs, mutually exclusive with
        // the apply and maintenance jobs (both consult isBusy).
        crawlerTreeGrant = CrawlerTreeGrant(this)
        crawlerMigration = TermuxMigration(
            context = this,
            oneShotRunner = oneShotRunner,
            executor = crawlerControlExecutor,
            crawlerConfig = CrawlerConfig(this),
            grant = crawlerTreeGrant,
            freeSpace = ::internalFreeSpace,
            stopCrawler = { stopCrawlerSupervisors() },
            startCrawlerIfEnabled = ::startCrawlerIfEnabled,
            applyCrawler = ::applyCrawlerSettings,
            currentSettings = { configStore.load().crawler },
            crawlerRunning = {
                supervisors.any { s ->
                    s.status.value.label == CRAWLER_LABEL && s.status.value.state == ProcessState.RUNNING
                }
            },
            statsReachable = ::crawlerStatsReachable,
        )
        // A tree granted before the last service start is still persisted
        // (persistable permission): hand it back to the coordinator.
        crawlerTreeGrant.treeUri()?.let { crawlerMigration.setTree(it) }
        controlServer = ControlServer(
            tokenManager = tokenManager,
            serviceState = _state,
            daemonStatuses = ::getDaemonStatuses,
            safStatus = ::getSafStatus,
            wifiIp = ::getWifiIpAddress,
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
            applyCrawler = ::applyCrawlerSettings,
            crawlerApplyStatus = { crawlerApplyStatus },
            crawlerPausedNoWifi = { crawlerPausedNoWifi },
            crawlerHttpPort = ::crawlerHttpPort,
            crawlerDbSize = ::crawlerDbSize,
            internalFreeSpace = ::internalFreeSpace,
            crawlerMaintenanceStart = ::startCrawlerMaintenance,
            crawlerMaintenanceStatus = { crawlerMaintenance.status },
            crawlerMigrationStart = { crawlerMigration.action(it) },
            crawlerMigrationStatus = { crawlerMigration.status },
)
        wifiGateMonitor = WifiGateMonitor(this)
    }

    private fun ensureUsbGateway() {
        if (gateway != null) return
        usbPermissionManager.treeUri()?.let { uri ->
            val g = SafStorageGateway(this, uri)
            gateway = g
            val old = ftpController
            runOnFtpControl { old?.stop() }
            ftpController = newFtpController(g)
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
        fun loadConfig(): com.torrent.app.config.AppConfig = this@DaemonService.loadConfig()
        fun applyTransmissionSettings(s: com.torrent.app.config.TransmissionSettings, liveOnly: Boolean) =
            this@DaemonService.applyTransmissionSettings(s, liveOnly)
        fun applyFtpSettings(s: com.torrent.app.config.FtpSettings) =
            this@DaemonService.applyFtpSettings(s)
        fun restartDaemon(label: String) = this@DaemonService.restartDaemon(label)
        fun stopDaemon(label: String) = this@DaemonService.stopDaemon(label)
        fun startDaemon(label: String) = this@DaemonService.startDaemon(label)
        fun transmissionRpcSummary(): com.torrent.app.transmission.TransmissionSummary? = this@DaemonService.transmissionRpcSummary()
        fun usbFreeSpace(): Long? = this@DaemonService.usbFreeSpace()
        fun completeWizard() = this@DaemonService.completeWizard()
        fun lastApplyRolledBack(): Boolean = this@DaemonService.lastApplyRolledBack
        fun setCrawlerImportTree(uri: Uri): String = this@DaemonService.setCrawlerImportTree(uri)
        fun setCrawlerEnabled(enabled: Boolean) = this@DaemonService.setCrawlerEnabled(enabled)
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _state.value = ServiceState.Starting

        ensureUsbGateway()
        acquireLocks()
        startForegroundWithNotification()

        _state.value = ServiceState.Running
        controlServer.start()
        syncCrawlerProxy(configStore.load().crawler)
        safIpcServer.start()
        wifiGateMonitor.start()
        observeWifiForFtp()
        observeWifiForCrawler()
        startSupervisors()
        return START_STICKY
    }

    override fun onDestroy() {
        rollbackJob?.cancel()
        controlServer.stop()
        crawlerProxy?.stop()
        crawlerProxy = null
        ftpObservingJob?.cancel()
        crawlerObservingJob?.cancel()
        ftpControlExecutor.shutdownNow()
        // Stop FTP before the transmission supervisors and the gateway shutdown
        // (plan 07 §2.6); no IPC locks are held here.
        ftpController?.stop()
        stopSupervisors()
        safIpcServer.stop()
        gateway?.shutdown()
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
        val old = ftpController
        runOnFtpControl { old?.stop() }
        ftpController = newFtpController(g)
        maybeStartFtp()
    }

    fun clearUsbTree() {
        usbPermissionManager.clear()
        val old = ftpController
        runOnFtpControl { old?.stop() }
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

    // ── FTP (plan 07) ───────────────────────────────────────────────────────

    private fun newFtpController(g: SafStorageGateway): FtpServerController {
        val ftp = configStore.load().ftp
        return FtpServerController(
            g, FtpCredentials(configStore),
            port = ftp.port, rootRelPath = ftp.exposedRoot,
            maxDownloadKiBps = ftp.maxDownloadKiBps,
        )
    }

    private fun observeWifiForFtp() {
        ftpObservingJob?.cancel()
        ftpObservingJob = MainScope().launch {
            wifiGateMonitor.state.collect { ws ->
                val ftp = configStore.load().ftp
                if (ws.wifi && ws.ip != null) {
                    runOnFtpControl { startFtpOnIo(ws.ip) }
                } else if (ftp.wifiOnly) {
                    runOnFtpControl { ftpController?.stop() }
                }
            }
        }
    }

    private fun maybeStartFtp(ip: String? = null) {
        runOnFtpControl { startFtpOnIo(ip) }
    }

    /** Runs on the dedicated ftp-control thread; never on the service main thread. */
    private fun startFtpOnIo(ip: String?) {
        if (!configStore.load().wizardCompleted) return
        val ctrl = ftpController ?: return
        val ws = wifiGateMonitor.state.value
        val useIp = ip ?: ws.ip
        if (ws.wifi && useIp != null) {
            try { ctrl.rebind(useIp) }
            catch (e: Exception) { android.util.Log.w("DaemonService", "ftp start failed: ${e.message}") }
        }
    }

    private fun runOnFtpControl(block: () -> Unit) {
        try { ftpControlExecutor.execute { block() } }
        catch (e: RejectedExecutionException) { /* service shutting down */ }
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

    fun applyFtpSettings(s: com.torrent.app.config.FtpSettings): com.torrent.app.config.ApplyResult {
        val vr = com.torrent.app.config.ConfigValidator.validateFtp(s)
        if (!vr.ok) return com.torrent.app.config.ApplyResult(false, vr.errors.joinToString("; "), false)
        configStore.saveFtp(s)
        val old = ftpController
        runOnFtpControl { old?.stop() }
        val g = gateway
        if (g != null) {
            ftpController = FtpServerController(
                g, FtpCredentials(configStore),
                port = s.port, rootRelPath = s.exposedRoot,
                maxDownloadKiBps = s.maxDownloadKiBps,
            )
            maybeStartFtp()
        }
        return com.torrent.app.config.ApplyResult(
            true,
            if (g != null) "applied" else "applied; FTP starts when USB granted",
            false)
    }

    // ── Crawler (plan 10 step 2) ───────────────────────────────────────────

    /**
     * Validates and queues the crawler settings-apply job, returning
     * immediately as "started". The job (save → LKG snapshots → stop with the
     * 60 s grace → write config.ini → start if the wifi gate allows → watch
     * for a crash loop) runs on the crawler-control executor, serialized with
     * every other crawler stop/start.
     */
    fun applyCrawlerSettings(s: com.torrent.app.config.CrawlerSettings): com.torrent.app.config.ApplyResult {
        // Mutual exclusion (plan 10 step 6): never apply settings while a
        // migration job is in flight.
        if (crawlerMigration.isBusy()) {
            return com.torrent.app.config.ApplyResult(
                false, "a migration job is running; try again when it finishes", false)
        }
        val errors = com.torrent.app.config.ConfigValidator.validateCrawler(s)
        if (errors.isNotEmpty()) {
            return com.torrent.app.config.ApplyResult(false, errors.joinToString("; "), false)
        }
        try {
            crawlerControlExecutor.execute {
                try {
                    runCrawlerApply(s)
                } catch (e: Exception) {
                    android.util.Log.e("DaemonService", "crawler apply failed", e)
                    setCrawlerApplyStatus(CrawlerApplyPhase.FAILED, "apply failed: ${e.message}")
                }
            }
        } catch (e: RejectedExecutionException) {
            return com.torrent.app.config.ApplyResult(false, "service is shutting down", false)
        }
        setCrawlerApplyStatus(CrawlerApplyPhase.STOPPING, "apply started")
        return com.torrent.app.config.ApplyResult(true, "apply started", false)
    }

    /** The port the crawler's HTTP UI listens on (the proxy port when lanDirect). */
    fun crawlerHttpPort(): Int {
        val c = configStore.load().crawler
        return if (c.lanDirect) c.proxyPort else c.crawlerHttpPort
    }

    /**
     * Keeps the authenticated reverse proxy in sync with the crawler's access
     * settings (plan 10 §3): it listens on [com.torrent.app.config.CrawlerSettings.proxyPort]
     * whenever the crawler is not lanDirect, and is stopped when it is. The
     * proxy's target port is read live per request, so only the listen port
     * (or lanDirect) needs a stop/start here. Called from the main thread at
     * service start and from the crawler-control executor after an apply —
     * both only bind/close a socket, never long work.
     */
    @Synchronized
    private fun syncCrawlerProxy(s: com.torrent.app.config.CrawlerSettings) {
        if (s.lanDirect) {
            crawlerProxy?.stop()
            crawlerProxy = null
            return
        }
        val existing = crawlerProxy
        if (existing != null && existing.port == s.proxyPort) return
        existing?.stop()
        val proxy = CrawlerProxyServer(
            tokenManager = tokenManager,
            crawlerHttpPort = ::crawlerHttpPort,
            port = s.proxyPort,
        )
        crawlerProxy = proxy
        proxy.start()
    }

    /** Combined size of the crawler's `torrents.db` + WAL, null when no DB exists. */
    fun crawlerDbSize(): Long? {
        val data = File(filesDir, "crawler/data")
        val db = File(data, "torrents.db")
        val wal = File(data, "torrents.db-wal")
        if (!db.exists() && !wal.exists()) return null
        return (if (db.exists()) db.length() else 0L) + (if (wal.exists()) wal.length() else 0L)
    }

    /** Free internal storage in bytes (the crawler DB lives on internal storage). */
    fun internalFreeSpace(): Long = filesDir.usableSpace

    /**
     * The apply job body; runs on the crawler-control executor (see
     * [applyCrawlerSettings]). The supervisor is recreated with the
     * settings-derived args, so an arg change (e.g. `--dont-check-database`)
     * is always picked up.
     */
    private fun runCrawlerApply(s: com.torrent.app.config.CrawlerSettings) {
        val previous = configStore.load().crawler
        val crawlerConfig = CrawlerConfig(this)
        crawlerConfig.ensureLayout()

        // Save the new settings, snapshotting the previous ones (settings and
        // config.ini) for the rollback below.
        configStore.snapshotCrawlerLastKnownGood(previous)
        configStore.saveCrawler(s)
        crawlerConfig.snapshotLastKnownGood()

        setCrawlerApplyStatus(CrawlerApplyPhase.STOPPING, "stopping crawler (60 s grace)")
        stopCrawlerSupervisors()

        setCrawlerApplyStatus(CrawlerApplyPhase.WRITING, "writing config.ini")
        crawlerConfig.applySettings(s)
        // Keep the authenticated proxy in sync (lanDirect / proxyPort change).
        syncCrawlerProxy(s)

        val wifiUp = wifiGateMonitor.state.value.wifi
        if (!CrawlerGate.shouldRun(s.enabled, s.wifiOnly, wifiUp)) {
            crawlerPausedNoWifi = s.enabled && s.wifiOnly && !wifiUp
            setCrawlerApplyStatus(
                CrawlerApplyPhase.OK,
                if (!s.enabled) "applied; crawler disabled" else "applied; paused (no wifi)")
            return
        }

        setCrawlerApplyStatus(CrawlerApplyPhase.STARTING, "starting crawler")
        val crawler = buildCrawlerSupervisor(CrawlerGate.crawlerArgs(s), crawlerConfig)
        crawler.start()
        supervisors.add(crawler)

        // Watch the restarted crawler for the whole window (plan 10 §1): the
        // supervisor reports RUNNING as soon as the process is launched, so an
        // early RUNNING proves nothing. Any exit within the window (restart
        // count grew) or a crawler not running at the end triggers rollback.
        setCrawlerApplyStatus(CrawlerApplyPhase.WATCHING, "watching for early exit (30 s)")
        val statusAtApply = crawler.status.value
        val deadline = System.currentTimeMillis() + CRAWLER_APPLY_WATCH_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(CRAWLER_APPLY_POLL_MS)
            val cur = crawler.status.value
            if (cur.state == ProcessState.STOPPED || cur.state == ProcessState.STOPPING) {
                // Stopped externally (user / wifi gate) — nothing to judge.
                setCrawlerApplyStatus(CrawlerApplyPhase.OK, "applied; crawler stopped during watch")
                return
            }
            if (CrawlerGate.exitedDuringWatch(statusAtApply.restartCount, cur.restartCount)) {
                rollBackCrawlerApply(crawlerConfig, previous)
                return
            }
        }
        if (crawler.status.value.state != ProcessState.RUNNING) {
            rollBackCrawlerApply(crawlerConfig, previous)
            return
        }
        setCrawlerApplyStatus(CrawlerApplyPhase.OK, "applied; crawler running")
    }

    /**
     * Restores the last-known-good state after a post-apply crash loop: the
     * previous settings, the LKG config.ini, and a supervisor recreated with
     * the previous args. Runs on the crawler-control executor.
     */
    private fun rollBackCrawlerApply(
        crawlerConfig: CrawlerConfig,
        previous: com.torrent.app.config.CrawlerSettings,
    ) {
        val tail = supervisors.firstOrNull { it.status.value.label == CRAWLER_LABEL }
            ?.status?.value?.lastLogLines ?: emptyList()
        android.util.Log.w("DaemonService", "crawler crash-loop after apply; rolling back")
        stopCrawlerSupervisors()
        configStore.saveCrawler(previous)
        crawlerConfig.restoreLastKnownGood()
        val restored = buildCrawlerSupervisor(CrawlerGate.crawlerArgs(previous), crawlerConfig)
        restored.start()
        supervisors.add(restored)
        setCrawlerApplyStatus(
            CrawlerApplyPhase.ROLLED_BACK,
            "crawler crash-looped after apply; rolled back to last known good settings",
            tail)
    }

    private fun setCrawlerApplyStatus(
        phase: CrawlerApplyPhase,
        message: String,
        logTail: List<String> = emptyList(),
    ) {
        crawlerApplyStatus = CrawlerApplyStatus(phase, message, logTail)
    }

    /**
     * Drives crawler start/stop from the wifi gate (plan 10 §5), the same way
     * FTP is gated: wifi down + wifiOnly → stop and mark paused; wifi back +
     * enabled → start. Both run on the crawler-control executor.
     */
    private fun observeWifiForCrawler() {
        crawlerObservingJob?.cancel()
        crawlerObservingJob = MainScope().launch {
            wifiGateMonitor.state.collect { ws ->
                val crawler = configStore.load().crawler
                if (CrawlerGate.shouldRun(crawler.enabled, crawler.wifiOnly, ws.wifi)) {
                    crawlerPausedNoWifi = false
                    runOnCrawlerControl { startCrawlerSupervisor() }
                } else {
                    crawlerPausedNoWifi = crawler.enabled && crawler.wifiOnly && !ws.wifi
                    runOnCrawlerControl { stopCrawlerSupervisors() }
                }
            }
        }
    }

    private fun runOnCrawlerControl(block: () -> Unit) {
        try { crawlerControlExecutor.execute { block() } }
        catch (e: RejectedExecutionException) { /* service shutting down */ }
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
        val sup = supervisors.firstOrNull { it.status.value.label == label }
        if (sup != null) { sup.start(); return true }
        // Manual start from the dashboard: lazily register+start the crawler
        // (auto-start happens only when enabled), but the wifi gate always
        // applies — a manual start while paused is refused (plan 10 §5).
        if (label == CRAWLER_LABEL) {
            val c = configStore.load().crawler
            if (CrawlerGate.shouldRun(c.enabled, c.wifiOnly, wifiGateMonitor.state.value.wifi)) {
                startCrawlerSupervisor()
                return true
            }
            return false
        }
        return false
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
        transmissionConfig = TransmissionConfig(this, configStore)
        if (configStore.load().wizardCompleted) startTransmissionSupervisor()
        // The crawler does not need the wizard/SAF: start it whenever it is
        // enabled in config (plan 09 §5), independent of transmission. The
        // initial start also respects the wifi gate (plan 10 §5); the gate
        // collector starts it as soon as wifi is up.
        val crawler = configStore.load().crawler
        if (CrawlerGate.shouldRun(crawler.enabled, crawler.wifiOnly, wifiGateMonitor.state.value.wifi)) {
            startCrawlerSupervisor()
        }
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

    /**
     * Registers and starts the dht_crawler supervisor. The work runs on the
     * crawler-control executor, which also hosts the crawler's stop(): a pending
     * stop from a previous service instance is serialized ahead of this block, so
     * a new child is never launched while the old one is still shutting down.
     * Failures are logged, never thrown — a broken crawler must not crash the service.
     */
    private fun startCrawlerSupervisor() {
        if (supervisors.any { it.status.value.label == CRAWLER_LABEL }) return
        crawlerControlExecutor.execute {
            if (supervisors.any { it.status.value.label == CRAWLER_LABEL }) return@execute
            try {
                val crawlerConfig = CrawlerConfig(this)
                crawlerConfig.ensureLayout()
                val crawler =
                    buildCrawlerSupervisor(CrawlerGate.crawlerArgs(configStore.load().crawler), crawlerConfig)
                crawler.start()
                supervisors.add(crawler)
            } catch (e: Exception) {
                android.util.Log.e("DaemonService", "failed to start crawler supervisor", e)
            }
        }
    }

    /**
     * Starts the crawler if it is enabled and the wifi gate allows (plan 10 §5).
     * Used after a maintenance action to restore the crawler. The actual start
     * is queued on the crawler-control executor by [startCrawlerSupervisor].
     */
    private fun startCrawlerIfEnabled() {
        val c = configStore.load().crawler
        if (CrawlerGate.shouldRun(c.enabled, c.wifiOnly, wifiGateMonitor.state.value.wifi)) {
            startCrawlerSupervisor()
        }
    }

    /**
     * Queues a crawler maintenance action (plan 10 §4) by its route name,
     * returning a human message (the resulting [CrawlerMaintenance] status
     * message). The `POST /crawler/maintenance/{action}` route redirects back
     * with this text.
     */
    fun startCrawlerMaintenance(action: String): String {
        val a = MaintenanceAction.fromRoute(action)
            ?: return "unknown maintenance action: $action"
        return crawlerMaintenance.start(a).message
    }

    /**
     * Persists the Termux migration tree picked in MainActivity
     * (plan 10 §6.2) and hands it to the migration backend. Refuses the USB
     * tree URI — the crawler data never touches USB.
     */
    fun setCrawlerImportTree(uri: Uri): String {
        val usb = usbPermissionManager.treeUri()
        if (usb != null && usb == uri) {
            return "refused: that is the USB storage tree — pick the Termux dht_crawler folder"
        }
        crawlerTreeGrant.grant(uri)
        return crawlerMigration.setTree(uri)
    }

    /**
     * Persists the first-run wizard's crawler toggle (plan 10 step 7): flips
     * `crawler.enabled` without a full apply job and starts the crawler when
     * the wifi gate allows.
     */
    fun setCrawlerEnabled(enabled: Boolean) {
        configStore.saveCrawler(configStore.load().crawler.copy(enabled = enabled))
        if (enabled) startCrawlerIfEnabled()
    }

    /** True when the crawler's /stats answers on loopback (~2 s timeout). */
    private fun crawlerStatsReachable(): Boolean {
        return try {
            val conn = (java.net.URL("http://127.0.0.1:${crawlerHttpPort()}/stats")
                .openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                instanceFollowRedirects = false
            }
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** Builds (does not start) the dht_crawler supervisor for [args]. */
    private fun buildCrawlerSupervisor(args: List<String>, crawlerConfig: CrawlerConfig): ProcessSupervisor {
        val logDir = File(filesDir, "logs")
        return ProcessSupervisor(
            label = CRAWLER_LABEL, binaryName = "libdhtcrawler.so",
            args = args,
            nativeLibraryDir = applicationInfo.nativeLibraryDir,
            workDir = crawlerConfig.workDir, logDir = logDir,
            selfLogging = false,
            graceMs = CRAWLER_GRACE_MS,
            extraEnv = mapOf("TMPDIR" to File(filesDir, "tmp").apply { mkdirs() }.absolutePath))
    }

    private fun stopSupervisors() {
        // The crawler has a long (60 s) SIGTERM grace and never uses SAF, so it
        // stops on the crawler-control executor and is NOT awaited here — the
        // SAF gateway shutdown in onDestroy must not block on it. All other
        // supervisors (transmission uses SAF) stop synchronously, as before.
        // The crawler stop is queued first so it runs in parallel with transmission's.
        // It looks the crawler up when it runs (not now), so a start that is still
        // queued on the executor is registered first and then stopped here too.
        crawlerControlExecutor.execute { stopCrawlerSupervisors() }
        val others = supervisors.filter { it.status.value.label != CRAWLER_LABEL }
        for (s in others) s.stop()
        supervisors.removeAll(others.toSet())
    }

    /**
     * Stops and unregisters every crawler supervisor. Must run on the
     * crawler-control executor: a pending stop from a previous service
     * instance is then serialized ahead of any new launch.
     */
    private fun stopCrawlerSupervisors() {
        val crawlers = supervisors.filter { it.status.value.label == CRAWLER_LABEL }
        supervisors.removeAll(crawlers.toSet())
        for (c in crawlers) {
            try {
                c.stop()
            } catch (e: Exception) {
                android.util.Log.w("DaemonService", "crawler stop failed: ${e.message}")
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "daemon_service"
        const val NOTIFICATION_ID = 1
        const val CRAWLER_LABEL = "dht_crawler"
        const val CRAWLER_GRACE_MS = 60_000L

        // The apply job watches the restarted crawler for a crash loop for
        // this long (plan 10 §1), polling at this interval.
        private const val CRAWLER_APPLY_WATCH_MS = 30_000L
        private const val CRAWLER_APPLY_POLL_MS = 2_000L

        // The dht_crawler supervisor gets a long (60 s) SIGTERM grace (plan 09 §5),
        // so its stop/start are serialized on one process-wide single-thread executor
        // (never shut down): onDestroy never blocks on the crawler, and a pending stop
        // from a previous service instance always finishes before a new child is
        // launched. The thread is a daemon and dies with the process.
        private val crawlerControlExecutor = Executors.newSingleThreadExecutor(ThreadFactory {
            Thread(it, "crawler-control").apply { isDaemon = true }
        })
    }
}