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
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import java.io.File
import android.net.Uri
import com.torrent.app.storage.SafStatus
import com.torrent.app.storage.SafStorageGateway
import com.torrent.app.storage.FtpSafIpcRequestHandler
import com.torrent.app.storage.SafIpcRequestHandler
import com.torrent.app.storage.SafIpcServer
import com.torrent.app.storage.UsbPermissionManager
import com.torrent.app.ftp.FtpCredentials
import com.torrent.app.ftp.FtpStatus
import com.torrent.app.net.FtpHangWatchdog
import com.torrent.app.net.WifiGateMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import com.torrent.app.config.CrawlerGate
import com.torrent.app.config.ClassifierModelVersionMigration
import com.torrent.app.config.ConfigDefaults
import com.torrent.app.backup.Bep51BackupManager
import com.torrent.app.backup.Bep51BootstrapDownloader
import com.torrent.app.backup.BootstrapResult
import com.torrent.app.backup.CrawlerDbDumper
import com.torrent.app.backup.DbBackupManager
import com.torrent.app.backup.DbRestoreManager
import com.torrent.app.backup.S3DbRestorer
import com.torrent.app.backup.SqliteRestoreSink

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
    // The single dht_crawler supervisor (plan 10 §4): registered in onCreate
    // for the whole service lifetime so dht_crawler is ALWAYS listed in the
    // daemons table — and therefore startable/restartable from the dashboard —
    // even while stopped, disabled, or wifi-paused. It is replaced only when a
    // settings apply changes its CLI args; see [registerCrawlerSupervisor].
    @Volatile private var crawlerSupervisor: ProcessSupervisor? = null
    // The CLI args [crawlerSupervisor] was built with.
    @Volatile private var crawlerSupervisorArgs: List<String>? = null
    // Serializes [registerCrawlerSupervisor] between the main thread (onCreate)
    // and the crawler-control executor (apply/rollback/dashboard starts).
    private val crawlerRegistrationLock = Any()
    private lateinit var usbPermissionManager: UsbPermissionManager
    @Volatile private var gateway: SafStorageGateway? = null
    // The single ftp_server supervisor (stage 3): registered in onCreate so
    // ftp_server is ALWAYS listed in the daemons table — and therefore
    // startable/restartable from the dashboard — even while the wifi gate is
    // closed. It is replaced (stop old + build new) whenever the bind IP or
    // settings change its CLI args; see [registerFtpSupervisor].
    @Volatile private var ftpSupervisor: ProcessSupervisor? = null
    // The bind IP [ftpSupervisor] was built with (supervisor args are immutable).
    @Volatile private var ftpBindIp: String? = null
    // Serializes [registerFtpSupervisor] between the main thread (onCreate)
    // and the ftp-control executor (gate/apply/dashboard lifecycle ops).
    private val ftpRegistrationLock = Any()
    private lateinit var wifiGateMonitor: WifiGateMonitor
    private var ftpObservingJob: Job? = null
    // Stage 4: TCP hang watchdog for the ftp daemon (plain-JVM class in
    // com.torrent.app.net): probes the control port for the daemon's 220
    // greeting and triggers the same serialized rebuild path as the gate and
    // apply ops when it stops answering. Created in onCreate, started in
    // onStartCommand, stopped in onDestroy BEFORE the supervisor stops so a
    // restart never races the destroy.
    private lateinit var ftpHangWatchdog: FtpHangWatchdog

    // Graceful shutdown (Stage 1): the ordered teardown runs on a dedicated
    // single-thread executor (see [shutdownExecutor]), never on the main thread,
    // because its crawler step AWAITS the SIGTERM grace (up to
    // CRAWLER_GRACE_MS + 2 s) so dht_crawler can flush its SQLite batch, save its
    // bloom filter and run the final WAL checkpoint. The executor is process-wide
    // and NEVER shut down: the service instance survives a graceful stop (the
    // activity stays bound, so stopSelf() does not destroy it), so a second
    // shutdown cycle must serialize behind the first instead of being rejected.
    // Built lazily (it closes over collaborators assigned in onCreate) and
    // shared with onDestroy, so the ordered teardown is serialized (one cycle at
    // a time, re-runnable once the previous cycle completed).
    private val shutdownPlanner: GracefulShutdownPlanner by lazy { buildShutdownPlanner() }
    // Set while a graceful teardown is claimed/in flight; reset when the cycle
    // completes so a later cycle (daemons started again) is accepted.
    private val shutdownRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    // Thread id of the graceful-shutdown run. Only that run AWAITS the crawler's
    // SIGTERM grace; onDestroy falls back to the historical fire-and-forget
    // crawler stop, since a ~60 s block on the main thread would ANR.
    @Volatile private var shutdownThreadId: Long? = null
    // Stage 1 of "stop everything, then close the app": [requestShutdownAndExit]
    // records the exit intent here FIRST and only then asks for the teardown, so
    // an already in-flight cycle's completion hook (the only place the process is
    // ever killed) sees it and exits. onDestroy never consults it: a
    // system-initiated destroy must not kill the process.
    private val shutdownExitGate = ShutdownExitGate {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private lateinit var safSocketFile: java.io.File
    private lateinit var safIpcServer: SafIpcServer
    // Stage 2: second IPC server for the native FTP daemon's read-only SAF
    // backend (filesDir/ftp-saf.sock). Shares the lifecycle of [safIpcServer].
    private lateinit var ftpSafSocketFile: java.io.File
    private lateinit var ftpSafIpcServer: SafIpcServer
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
    // BEP51 cache backup (Stage 4): uploads the crawler's bootstrap cache to
    // R2 daily (and on manual dashboard trigger) on its own single-thread
    // scheduler; the busy/wifi predicates mirror the crawler lifecycle ops.
    private lateinit var bep51Backup: Bep51BackupManager
    // DB dump backup (plan 2, Stage 3): uploads the crawler's incremental
    // SQLite dump to the R2 DB bucket daily (and on manual dashboard
    // trigger) on its own single-thread scheduler; the busy/wifi predicates
    // mirror the crawler lifecycle ops. The bookmark file (filesDir/backup)
    // advances only after a successful upload (upload-first ordering).
    private lateinit var dbBackup: DbBackupManager
    // DB restore (plan 2, Stage 4; plan 3, Stage 3): an explicit, dashboard/
    // wizard-triggered one-shot that streams the R2 dump chain into a fresh
    // torrents.db (stops the crawler, imports one dump object per chunk into a
    // temp DB — one transaction per chunk, resumable), rebuilds the FTS indexes
    // with the crawler's own SQLite via `--rebuild-fts`, swaps the temp DB in
    // and resets the bookmark only on success. Runs on the crawler-control
    // executor; the busy predicates of maintenance/backup include its state.
    private lateinit var dbRestore: DbRestoreManager
    // Stage 5: in-memory rate limit for failed BEP51 bootstrap attempts —
    // a failure is not retried (on a later start) for at least one hour.
    @Volatile private var bep51BootstrapFailureAt: Long? = null
    // Search-dashboard stage 2: one add handler shared by the :8090
    // /api/transmission/add route and the crawler-proxy route; built once in
    // onCreate (needs configStore + gateway).
    @Volatile private var crawlerAddHandler: CrawlerAddHandler? = null

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
        ftpSafSocketFile = java.io.File(filesDir, "ftp-saf.sock")
        ftpSafIpcServer = SafIpcServer(ftpSafSocketFile, FtpSafIpcRequestHandler(
            stat = { gateway?.readOnlyStat(it) },
            list = { gateway?.readOnlyListDetailed(it) ?: emptyList() },
            openRead = { gateway?.readOnlyOpenRead(it) },
            remove = { gateway?.removeDoc(it) ?: false },
            gatewayAvailable = { gateway != null },
        ))
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
            busy = {
                crawlerApplyStatus.phase.isActive ||
                    dbRestore.status.running ||
                    dbBackup.inFlight
            },
        )
        // BEP51 cache backup (Stage 4): the cache file lives in the crawler's
        // data dir; a running maintenance job counts as busy (its status is
        // observable via crawlerMaintenance). Prefs-backed last-success date
        // keeps the calendar-day gate across restarts.
        val crawlerConfig = CrawlerConfig(this)
        bep51Backup = Bep51BackupManager(
            cacheFile = { File(crawlerConfig.dataDir, "bep51_cache.dat") },
            loadSettings = { configStore.load().backup },
            isBusy = {
                crawlerApplyStatus.phase.isActive ||
                    crawlerMaintenance.status.running ||
                    dbRestore.status.running
                // Deliberately NOT dbBackup.inFlight: the BEP51 cache PUT is
                // small (~2 MB) and fully independent of the crawler DB (a
                // different bucket/object), so the two checks may overlap —
                // counting a running DB drain as busy starved BEP51 of a
                // clean status while the (now bounded) backlog drained.
            },
            isWifi = { wifiGateMonitor.state.value.wifi },
            clientFactory = { s ->
                val client = com.torrent.app.s3.S3Client(
                    endpoint = s.r2Endpoint,
                    bucket = s.r2Bucket,
                    accessKeyId = s.r2AccessKeyId,
                    secretAccessKey = s.r2SecretAccessKey,
                )
                com.torrent.app.backup.S3Uploader { key, contentType, body ->
                    client.put(key, contentType, body)
                }
            },
            readLastSuccessDate = { configStore.lastBackupSuccessDate() },
            writeLastSuccessDate = { configStore.setLastBackupSuccessDate(it) },
        )
        // DB dump backup (plan 2, Stage 3): the crawler DB lives in the same
        // data dir as the BEP51 cache; the dumper is built per-check so a
        // changed object-prefix setting takes effect without a restart.
        dbBackup = DbBackupManager(
            dbFile = { File(crawlerConfig.dataDir, "torrents.db") },
            dumperFactory = { s -> CrawlerDbDumper(keyPrefix = s.r2DbObjectPrefix) },
            loadSettings = { configStore.load().backup },
            isBusy = {
                crawlerApplyStatus.phase.isActive ||
                    crawlerMaintenance.status.running ||
                    dbRestore.status.running
            },
            isWifi = { wifiGateMonitor.state.value.wifi },
            clientFactory = { s ->
                val client = com.torrent.app.s3.S3Client(
                    endpoint = s.r2Endpoint,
                    bucket = s.r2DbBucket,
                    accessKeyId = s.r2AccessKeyId,
                    secretAccessKey = s.r2SecretAccessKey,
                )
                com.torrent.app.backup.S3DbUploader { key, contentType, body ->
                    client.put(key, contentType, body)
                }
            },
            bookmarkFile = { File(filesDir, "backup/db_bookmark.txt") },
            readLastSuccessDate = { configStore.lastDbBackupSuccessDate() },
            writeLastSuccessDate = { configStore.setDbBackupSuccessDate(it) },
        )
        // DB restore (plan 2, Stage 4): one-shot, no scheduler. Reuses the
        // crawler-control executor (the 60 s stop + the one-shot must not run
        // on a request thread) and the same bookmark file as the backup.
        dbRestore = DbRestoreManager(
            loadSettings = { configStore.load().backup },
            // Plan 3, Stage 3: the category filter reads the PERSISTED wizard
            // selection (not the in-session draft, not the last-known-good
            // copy) once per restore run; an empty list means "save everything"
            // and restores byte-for-byte as before.
            loadSaveCategories = { configStore.load().crawler.saveCategories },
            isBusy = {
                crawlerApplyStatus.phase.isActive ||
                    crawlerMaintenance.status.running ||
                    bep51Backup.status.state == com.torrent.app.backup.BackupState.RUNNING ||
                    dbBackup.inFlight
            },
            clientFactory = { s ->
                val client = com.torrent.app.s3.S3Client(
                    endpoint = s.r2Endpoint,
                    bucket = s.r2DbBucket,
                    accessKeyId = s.r2AccessKeyId,
                    secretAccessKey = s.r2SecretAccessKey,
                )
                object : S3DbRestorer {
                    override fun list(prefix: String): com.torrent.app.s3.S3ListResult = client.list(prefix)
                    override fun get(objectKey: String): com.torrent.app.s3.S3GetResult = client.get(objectKey)
                }
            },
            oneShot = { args, dir, onLine -> oneShotRunner.run(args, dir, onLine) },
            // Plan 3, Stage 3: the dump replay itself is Kotlin (streamed into a
            // temp DB, one transaction per chunk); the oneShot above now only
            // runs `--rebuild-fts` against that temp DB.
            sinkFactory = { SqliteRestoreSink(it) },
            progressFile = { File(filesDir, "backup/db_restore_progress.txt") },
            // Plan 3, Stage 11: the disk-space guard's view of the volume that
            // holds the temp DB (filesDir — the same volume as transmission's
            // downloads and the FTP root). `availableBytes` is what an
            // unprivileged writer can actually use, which is what the import is.
            freeSpaceBytes = { StatFs(filesDir.absolutePath).availableBytes },
            // The temp DB lives at <workDir>/data/torrents.db.restore.tmp and
            // both the Kotlin import (sink prepare) and the `--rebuild-fts`
            // one-shot open it there; sqlite3_open does NOT create parent
            // directories, so on a fresh install (crawler never started)
            // filesDir/crawler/data may not exist — create it before either runs.
            workDir = { File(filesDir, "crawler").apply { File(this, "data").mkdirs() } },
            tmpDir = { File(filesDir, "tmp") },
            bookmarkFile = { File(filesDir, "backup/db_bookmark.txt") },
            stopCrawler = { stopCrawlerSupervisors() },
            startCrawlerIfEnabled = ::startCrawlerIfEnabled,
            executor = crawlerControlExecutor,
        )
        // Plan 10 §4: register the crawler supervisor up-front so dht_crawler
        // is always listed in the daemons table (STOPPED until something starts
        // it), even when it is disabled or wifi-paused. Registration is pure —
        // no layout work happens until a start is requested.
        registerCrawlerSupervisor(emptyList())
        // Stage 3: register the ftp_server supervisor up-front (bound to
        // loopback as a placeholder) so it is always listed in the daemons
        // table; the wifi gate rebinds it to the real IP before any start.
        registerFtpSupervisor("127.0.0.1")
        crawlerAddHandler = CrawlerAddHandler(
            store = configStore,
            gateway = { gateway },
            crawlerDbFile = { File(filesDir, "crawler/data/torrents.db") },
        )
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
            saveBackup = ::saveBackupSettings,
            crawlerApplyStatus = { crawlerApplyStatus },
            crawlerPausedNoWifi = { crawlerPausedNoWifi },
            crawlerHttpPort = ::crawlerHttpPort,
            crawlerDbSize = ::crawlerDbSize,
            internalFreeSpace = ::internalFreeSpace,
            crawlerMaintenanceStart = ::startCrawlerMaintenance,
            crawlerMaintenanceStatus = { crawlerMaintenance.status },
            crawlerBackupUpload = { bep51Backup.uploadNow(manual = true).message },
            crawlerBackupStatus = { bep51Backup.status },
            crawlerBackupDbUpload = { dbBackup.uploadNow(manual = true).message },
            crawlerBackupDbStatus = { dbBackup.status },
            crawlerRestoreDb = { dbRestore.restoreNow().message },
            addHandler = crawlerAddHandler,
            crawlerRestoreDbStatus = { dbRestore.status },
)
        wifiGateMonitor = WifiGateMonitor(this)
        ftpHangWatchdog = FtpHangWatchdog(
            scope = MainScope(),
            hostProvider = { ftpBindIp },
            portProvider = { configStore.load().ftp.port },
            isRunningProvider = { ftpSupervisor?.status?.value?.state == ProcessState.RUNNING },
            // rebuildFtpSupervisor() already serializes on the ftp-control
            // executor (runOnFtpControl), the same path as gate/apply ops.
            restartAction = { reason -> rebuildFtpSupervisor(reason) },
        )
    }

    private fun ensureUsbGateway() {
        if (gateway != null) return
        usbPermissionManager.treeUri()?.let { uri ->
            gateway = SafStorageGateway(this, uri)
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
        fun applyTransmissionSettings(cfg: com.torrent.app.config.AppConfig, liveOnly: Boolean) =
            this@DaemonService.applyTransmissionSettings(cfg, liveOnly)
        fun applyFtpSettings(s: com.torrent.app.config.FtpSettings) =
            this@DaemonService.applyFtpSettings(s)
        fun restartDaemon(label: String) = this@DaemonService.restartDaemon(label)
        fun stopDaemon(label: String) = this@DaemonService.stopDaemon(label)
        fun startDaemon(label: String) = this@DaemonService.startDaemon(label)
        fun transmissionRpcSummary(): com.torrent.app.transmission.TransmissionSummary? = this@DaemonService.transmissionRpcSummary()
        fun usbFreeSpace(): Long? = this@DaemonService.usbFreeSpace()
        fun completeWizard() = this@DaemonService.completeWizard()
        fun lastApplyRolledBack(): Boolean = this@DaemonService.lastApplyRolledBack
        fun setCrawlerEnabled(enabled: Boolean) = this@DaemonService.setCrawlerEnabled(enabled)
        fun setCrawlerAdultMode(mode: String) = this@DaemonService.setCrawlerAdultMode(mode)
        fun setCrawlerSaveCategories(categories: List<String>) =
            this@DaemonService.setCrawlerSaveCategories(categories)
        fun requestGracefulShutdown(): Boolean = this@DaemonService.requestGracefulShutdown()
        fun requestShutdownAndExit() = this@DaemonService.requestShutdownAndExit()
        fun isShutdownRequested(): Boolean = this@DaemonService.isShutdownRequested()
        // DB restore surface for the wizard's restore section (plan 2, Stage 2):
        // save the section-5 values with restore validation, trigger the
        // one-shot, and poll its in-memory status.
        fun dbRestoreStatus(): com.torrent.app.backup.DbRestoreStatus = this@DaemonService.dbRestore.status
        fun startDbRestore(): com.torrent.app.backup.DbRestoreStatus = this@DaemonService.dbRestore.restoreNow()
        fun saveBackupForRestore(s: com.torrent.app.config.BackupSettings) =
            this@DaemonService.saveBackupForRestore(s)
        // Stage 4: persist the wizard's in-session chip selection before the restore
        // starts, so the restore's category filter sees exactly what the chips show.
        fun saveCrawlerSaveCategoriesForRestore(categories: List<String>) =
            this@DaemonService.saveCrawlerSaveCategoriesForRestore(categories)
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN_AND_EXIT) {
            // Shutdown control (Stage 2 adds the notification action): stop every
            // daemon and then close the app. Only an explicit action counts — a
            // null intent here is a sticky redelivery, never a shutdown.
            // Returns at once; the process dies from the teardown's completion
            // hook minutes-to-seconds later, so START_NOT_STICKY keeps the system
            // from resurrecting a service the user asked to close.
            if (!shutdownExitGate.isExitRequested) {
                // Keeps the foreground/notification contract of a start command
                // (startForegroundService must foreground within 5 s) without
                // restarting any server: the teardown stops them all anyway.
                startForegroundWithNotification()
            }
            requestShutdownAndExit()
            return START_NOT_STICKY
        }

        _state.value = ServiceState.Starting

        ensureUsbGateway()
        acquireLocks()
        startForegroundWithNotification()

        _state.value = ServiceState.Running
        // Nothing may listen before the first-run wizard is finished: the dashboard
        // (0.0.0.0:8090) and the crawler proxy (0.0.0.0:8080) are deferred until then;
        // completeWizard() starts both once setup is done.
        if (configStore.load().wizardCompleted) {
            controlServer.start()
            syncCrawlerProxy(configStore.load().crawler)
        }
        safIpcServer.start()
        ftpSafIpcServer.start()
        ftpHangWatchdog.start()
        wifiGateMonitor.start()
        bep51Backup.start()
        dbBackup.start()
        // Re-arms the restore manager stopped by the graceful teardown: this
        // service instance survives the stop, so without this Start would leave
        // every restore refused with "service is shutting down".
        dbRestore.start()
        observeWifiForFtp()
        observeWifiForCrawler()
        startSupervisors()
        return START_STICKY
    }

    override fun onDestroy() {
        // Both teardown entry points share ONE serialized planner, so a cycle in
        // flight is never run twice (Ktor stop(), supervisor.stop(), executor
        // shutdownNow, lock release): whichever path gets here while it runs no-ops.
        // When requestGracefulShutdown() won, its thread performed the ordered
        // teardown and this call is reached from its stopSelf() (still in flight,
        // so it no-ops); when the system/service destroy came first, the same
        // ordered teardown runs inline here (the crawler stop stays
        // fire-and-forget on this thread — blocking the main thread for its 60 s
        // SIGTERM grace would ANR).
        // This inline run is also the last teardown the instance gets, so it starts
        // a fresh cycle even if a previous one had already completed.
        shutdownPlanner.run()
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
        maybeStartFtp()
    }

    fun clearUsbTree() {
        usbPermissionManager.clear()
        // The FTP daemon keeps running (its storage calls answer EAGAIN until
        // a tree is granted again).
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

    fun applyTransmissionSettings(cfg: com.torrent.app.config.AppConfig, liveOnly: Boolean): com.torrent.app.config.ApplyResult {
        val s = cfg.transmission
        val errors = com.torrent.app.config.ConfigValidator.validateTransmission(s).errors +
                     com.torrent.app.config.ConfigValidator.validateDataDir(cfg).errors
        if (errors.isNotEmpty()) return com.torrent.app.config.ApplyResult(false, errors.joinToString("; "), false)
        val before = configStore.load()
        configStore.snapshotLastKnownGood(before.transmission)
        configStore.save(cfg)
        lastApplyRolledBack = false
        if (liveOnly) {
            val rpc = com.torrent.app.transmission.TransmissionRpcClient(before.transmission.username, before.transmission.password)
            val args = com.torrent.app.transmission.TransmissionRpcClient.buildSessionSetSpeedArgs(
                s.speedLimitDown, s.speedLimitUp, s.altSpeedDown, s.altSpeedUp)
            val ok = rpc.sessionSet(args)
            return com.torrent.app.config.ApplyResult(ok, if (ok) "applied via RPC" else "daemon not reachable; restart required", false)
        }
        val sup = supervisors.firstOrNull { it.status.value.label == "transmission" }
        sup?.stop()
        transmissionConfig.ensureSettingsJson()
        sup?.start()
        val statusAtApply = sup?.status?.value ?: ProcessStatus("transmission", ProcessState.STOPPED)
        launchRollbackWatcher(sup, statusAtApply, before)
        // Shared data dir (Stage 11): the FTP root follows dataSubpath, so a data-dir
        // change rebuilds/restarts the FTP daemon exactly like applyFtpSettings does.
        if (before.dataSubpath != cfg.dataSubpath) {
            rebuildFtpSupervisor()
        }
        return com.torrent.app.config.ApplyResult(true, "applied; restarting transmission", false)
    }

    // ── FTP (plan 07) ───────────────────────────────────────────────────────

    /** Builds (does not start) the ftp_server supervisor bound to [ip]. */
    private fun buildFtpSupervisor(ip: String): ProcessSupervisor {
        val cfg = configStore.load()
        val creds = FtpCredentials(configStore)
        return ProcessSupervisor(
            label = FTP_LABEL, binaryName = "libftpserver.so",
            args = listOf(
                "--bind", ip,
                "--port", cfg.ftp.port.toString(),
                "--root", cfg.dataSubpath,
                "--pasv", "30000-30009",
                "--max-kibps", cfg.ftp.maxDownloadKiBps.toString(),
                "--saf-socket", ftpSafSocketFile.absolutePath),
            nativeLibraryDir = applicationInfo.nativeLibraryDir,
            workDir = File(filesDir, "ftp").apply { mkdirs() },
            logDir = File(filesDir, "logs"),
            extraEnv = mapOf(
                "FTP_USERNAME" to creds.username(),
                "FTP_PASSWORD" to creds.password(),
                "TMPDIR" to File(filesDir, "tmp").apply { mkdirs() }.absolutePath),
            selfLogging = false,
            graceMs = 5000L,
            // Readiness: the daemon is only RUNNING once its FTP greeting answers
            // on the bind IP — the very probe the hang watchdog uses, so a slow
            // start and a hung daemon are judged identically. The port is
            // re-read each poll (a settings apply rebuilds this supervisor).
            readinessProbe = {
                com.torrent.app.net.FtpHangWatchdog.probeGreeting(
                    host = ip, port = configStore.load().ftp.port, connectTimeoutMs = 2000) == null
            })
    }

    /**
     * Registers (without starting) the single ftp_server supervisor for bind
     * [ip] and returns it. The supervisor stays registered for the whole
     * service lifetime — listed in the daemons table (STOPPED) even while the
     * gate is closed — and is replaced when the bind IP differs or [force] is
     * set (a settings apply changed its CLI args, e.g. port/root/rate limit/
     * credentials while the wifi IP stayed the same): supervisor args are
     * immutable, so a new supervisor is built and the old one is stopped
     * first (never two at once). The stop blocks for the SIGTERM grace, so
     * this must run on the ftp-control executor, except the no-op onCreate
     * registration.
     */
    private fun registerFtpSupervisor(ip: String, force: Boolean = false): ProcessSupervisor {
        synchronized(ftpRegistrationLock) {
            val previous = ftpSupervisor
            if (previous != null && ftpBindIp == ip && !force) return previous
            val sup = buildFtpSupervisor(ip)
            if (previous != null) {
                try {
                    previous.stop()
                } catch (e: Exception) {
                    android.util.Log.w("DaemonService", "ftp stop before rebuild failed: ${e.message}")
                }
                supervisors.remove(previous)
            }
            ftpSupervisor = sup
            ftpBindIp = ip
            supervisors.add(sup)
            return sup
        }
    }

    /**
     * Runs on the ftp-control thread; starts the daemon bound to [ip].
     * [force] rebuilds the supervisor from the current settings even when
     * the bind IP is unchanged (settings apply); gate/maybe-start callers
     * keep the default false so an unchanged IP reuses the existing
     * supervisor.
     */
    private fun startFtpSupervisorOnIo(ip: String, force: Boolean = false) {
        if (!configStore.load().wizardCompleted) return
        killStaleFtpProcesses()
        try {
            registerFtpSupervisor(ip, force).start()
        } catch (e: Exception) {
            android.util.Log.w("DaemonService", "ftp start failed: ${e.message}")
        }
    }

    /**
     * Stops the running FTP supervisor and, when the wifi gate allows, starts
     * a new one built from the current settings; when the gate is closed it
     * swaps in a fresh stopped loopback placeholder so the registered
     * supervisor keeps the current settings (a later same-IP wifi-up start
     * then rebuilds from them instead of launching stale args). The work runs
     * on the
     * ftp-control executor, which serializes it with the gate/dashboard
     * lifecycle ops. Used by applyFtpSettings, the data-dir change in
     * applyTransmissionSettings, and the hang watchdog ([FtpHangWatchdog],
     * which passes the probe failure reason for logging).
     */
    private fun rebuildFtpSupervisor(reason: String = "", force: Boolean = true) {
        if (reason.isNotEmpty()) android.util.Log.i("DaemonService", "rebuilding ftp supervisor: $reason")
        val ws = wifiGateMonitor.state.value
        val gateIp = if (configStore.load().wizardCompleted && ws.wifi) ws.ip else null
        runOnFtpControl {
            val old = ftpSupervisor
            if (old != null) {
                try {
                    old.stop()
                } catch (e: Exception) {
                    android.util.Log.w("DaemonService", "ftp stop failed: ${e.message}")
                }
            }
            if (gateIp != null) {
                startFtpSupervisorOnIo(gateIp, force)
            } else {
                // Gate closed: the just-stopped supervisor is still registered
                // with its pre-rebuild CLI args/env. Swap in a fresh stopped
                // loopback placeholder (same pattern as the onCreate
                // registration) so the registered supervisor always holds the
                // current settings. When wifi returns, the real bind IP
                // differs from the placeholder's 127.0.0.1, so
                // registerFtpSupervisor's IP mismatch forces a rebuild from
                // current settings even with force=false — a stale-args start
                // is impossible.
                registerFtpSupervisor("127.0.0.1", force = true)
            }
        }
    }

    /**
     * Drives FTP start/stop from the wifi gate (plan 07 §2.6): wifi up + IP
     * known → ensure the supervisor is running bound to that IP (rebuild on
     * IP change); wifi down + wifiOnly → stop. Both run on the ftp-control
     * executor.
     */
    private fun observeWifiForFtp() {
        ftpObservingJob?.cancel()
        ftpObservingJob = MainScope().launch {
            wifiGateMonitor.state.collect { ws ->
                val ftp = configStore.load().ftp
                if (ws.wifi && ws.ip != null) {
                    val ip = ws.ip
                    runOnFtpControl { startFtpSupervisorOnIo(ip) }
                } else if (ftp.wifiOnly) {
                    runOnFtpControl {
                        ftpSupervisor?.let { sup ->
                            try {
                                sup.stop()
                            } catch (e: Exception) {
                                android.util.Log.w("DaemonService", "ftp stop failed: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun maybeStartFtp() {
        val ws = wifiGateMonitor.state.value
        if (!configStore.load().wizardCompleted || !ws.wifi || ws.ip == null) return
        val ip = ws.ip
        runOnFtpControl { startFtpSupervisorOnIo(ip) }
    }

    /** Runs on the dedicated ftp-control thread; never on the service main thread. */
    private fun runOnFtpControl(block: () -> Unit) {
        try { ftpControlExecutor.execute { block() } }
        // Defensive only: the companion ftp-control executor is never shut
        // down (it survives a graceful stop so a restarted service reuses it),
        // so this rejection is unreachable today.
        catch (e: RejectedExecutionException) { /* not reachable; see above */ }
    }

    /**
     * Best-effort kill of leftover libftpserver.so daemons from a previous
     * service instance: same-UID processes with the binary in
     * /proc/\<pid\>/cmdline get SIGKILL'd. The daemon's getppid orphan guard
     * normally exits it when the app process dies, but a wedged instance
     * would keep the port bound. Skipped entirely while the supervised child
     * is alive — a redundant start (USB re-grant, wizard completion,
     * dashboard start click) must never SIGKILL the running daemon — and
     * the child pid is excluded from the sweep in addition to our own.
     */
    private fun killStaleFtpProcesses() {
        val liveChildPid = ftpSupervisor?.status?.value?.pid
        if (liveChildPid != null) return
        val myPid = android.os.Process.myPid()
        val myUid = android.os.Process.myUid().toString()
        val entries = File("/proc").list() ?: return
        for (pidName in entries) {
            if (!pidName.all { it.isDigit() }) continue
            val pid = pidName.toIntOrNull() ?: continue
            if (pid == myPid || pid == liveChildPid) continue
            try {
                val cmdline = File("/proc/$pidName/cmdline").readText()
                if (!cmdline.contains("libftpserver.so")) continue
                val uidLine = File("/proc/$pidName/status").readText()
                    .lineSequence().firstOrNull { it.startsWith("Uid:") } ?: continue
                if (uidLine.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull() != myUid)
                    continue
                Runtime.getRuntime().exec(arrayOf("kill", "-9", pidName)).waitFor()
                android.util.Log.w("DaemonService", "killed stale ftp daemon pid $pidName")
            } catch (_: Exception) { /* process vanished — fine */ }
        }
    }

    fun getFtpStatus(): FtpStatus {
        val st = ftpSupervisor?.status?.value
        val ws = wifiGateMonitor.state.value
        val creds = FtpCredentials(configStore)
        val running = st?.state == ProcessState.RUNNING
        return FtpStatus(
            running = running,
            state = st?.state?.name ?: ProcessState.STOPPED.name,
            pid = st?.pid,
            restartCount = st?.restartCount ?: 0,
            ip = if (running) ftpBindIp else null,
            port = configStore.load().ftp.port,
            username = creds.username(),
            password = if (running) creds.password() else null,
            wifiUp = ws.wifi,
            logLines = st?.lastLogLines ?: emptyList(),
        )
    }

    fun applyFtpSettings(s: com.torrent.app.config.FtpSettings): com.torrent.app.config.ApplyResult {
        val vr = com.torrent.app.config.ConfigValidator.validateFtp(s)
        if (!vr.ok) return com.torrent.app.config.ApplyResult(false, vr.errors.joinToString("; "), false)
        configStore.saveFtp(s)
        val ws = wifiGateMonitor.state.value
        val gateAllows = configStore.load().wizardCompleted && ws.wifi && ws.ip != null
        rebuildFtpSupervisor()
        return com.torrent.app.config.ApplyResult(
            true,
            when {
                gateAllows && gateway != null -> "applied"
                gateway == null -> "applied; FTP starts when USB granted"
                else -> "applied; waiting for wifi"
            },
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
        setCrawlerApplyStatus(CrawlerApplyPhase.STOPPING, "apply started; stopping crawler first (up to 60 s)")
        return com.torrent.app.config.ApplyResult(true, "apply started", false)
    }

    /**
     * Persists the app-side backup/bootstrap settings (Stage 2): a plain
     * store write — no daemon is restarted and the crawler is untouched.
     */
    fun saveBackupSettings(s: com.torrent.app.config.BackupSettings): com.torrent.app.config.ApplyResult {
        val errors = com.torrent.app.config.ConfigValidator.validateBackup(s).errors
        if (errors.isNotEmpty()) {
            return com.torrent.app.config.ApplyResult(false, errors.joinToString("; "), false)
        }
        configStore.saveBackup(s)
        // Refresh both managers' config-gate snapshots so the panel's
        // "disabled" notes reflect the just-saved settings immediately
        // (otherwise they linger until the next periodic check).
        bep51Backup.refreshConfigStatus()
        dbBackup.refreshConfigStatus()
        return com.torrent.app.config.ApplyResult(true, "backup settings saved", false)
    }

    /**
     * Persists the section-5 backup settings on behalf of the wizard's restore
     * section (plan 2, Stage 2): same store write as [saveBackupSettings], but
     * gated on the stricter restore validation so the values the restore needs
     * (endpoint, creds, DB bucket, DB prefix) are all present before they are
     * saved. Restore itself reads them back through `dbRestore.loadSettings`.
     */
    fun saveBackupForRestore(s: com.torrent.app.config.BackupSettings): com.torrent.app.config.ApplyResult {
        val errors = com.torrent.app.config.ConfigValidator.validateDbRestore(s).errors
        if (errors.isNotEmpty()) {
            return com.torrent.app.config.ApplyResult(false, errors.joinToString("; "), false)
        }
        return saveBackupSettings(s)
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
        // Plan 11 D2: magnet add endpoint. The handler is shared with the
        // :8090 ControlServer route and built once in onCreate. The DB file is
        // the same one [crawlerDbSize] reads (filesDir/crawler/data).
        val addHandler = crawlerAddHandler ?: return
        val proxy = CrawlerProxyServer(
            tokenManager = tokenManager,
            crawlerHttpPort = ::crawlerHttpPort,
            port = s.proxyPort,
            addHandler = addHandler,
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
     * [applyCrawlerSettings]).
     */
    private fun runCrawlerApply(s: com.torrent.app.config.CrawlerSettings) {
        val crawlerConfig = CrawlerConfig(this)
        crawlerConfig.ensureLayout()
        // Stage 12: a classifier model-dir bump invalidates every stored threshold, so
        // migrate BEFORE the previous-settings snapshot (a later rollback must not
        // resurrect v2 thresholds) and before anything can start the crawler.
        val migrated = migrateClassifierVersion(crawlerConfig)
        // The reset is unconditional: it wins over the thresholds the user submitted.
        val effective = if (migrated) {
            s.copy(
                adultHideThreshold = ConfigDefaults.CRAWLER.adultHideThreshold,
            )
        } else {
            s
        }
        // Captured after the migration, so the LKG snapshot holds the reset thresholds.
        val previous = configStore.load().crawler

        // Save the new settings, snapshotting the previous ones (settings and
        // config.ini) for the rollback below.
        configStore.snapshotCrawlerLastKnownGood(previous)
        configStore.saveCrawler(effective)
        crawlerConfig.snapshotLastKnownGood()

        setCrawlerApplyStatus(CrawlerApplyPhase.STOPPING, "stopping crawler (60 s grace)")
        stopCrawlerSupervisors()

        setCrawlerApplyStatus(CrawlerApplyPhase.WRITING, "writing config.ini")
        crawlerConfig.applySettings(effective)
        // Keep the authenticated proxy in sync (lanDirect / proxyPort change).
        syncCrawlerProxy(effective)

        val wifiUp = wifiGateMonitor.state.value.wifi
        if (!CrawlerGate.shouldRun(effective.enabled, effective.wifiOnly, wifiUp)) {
            crawlerPausedNoWifi = effective.enabled && effective.wifiOnly && !wifiUp
            setCrawlerApplyStatus(
                CrawlerApplyPhase.OK,
                if (!effective.enabled) "applied; crawler disabled" else "applied; paused (no wifi)")
            return
        }

        setCrawlerApplyStatus(CrawlerApplyPhase.STARTING, "starting crawler")
        // The supervisor stays listed in the daemons table even when the
        // apply leaves it stopped.
        val crawler = registerCrawlerSupervisor(emptyList())
        crawler.start()

        // Watch the restarted crawler for the whole window (plan 10 §1). The
        // supervisor publishes the child's pid at fork and bumps restartCount
        // on every (re)launch, so the watch ARMS at the first poll that sees a
        // launched process (pid set, whether STARTING or RUNNING) and uses
        // that launch's restartCount as the baseline. Snapshotting immediately
        // after start() (as this used to do) captured the pre-launch count, so
        // the launch's own bump looked like an early exit and every apply that
        // started the crawler was rolled back (restoring enabled=false, which
        // also stopped the crawler from auto-loading with the app). A slow
        // start is fine: with a readiness probe the crawler can stay STARTING
        // for the whole window while it loads its large DB and its HTTP/RPC
        // API comes up, and restartCount only moves when a process actually
        // exits — so a slow RPC-readiness start is not an early exit.
        val watch = CrawlerApplyWatch()
        setCrawlerApplyStatus(CrawlerApplyPhase.WATCHING, "watching for early exit (30 s)")
        val deadline = System.currentTimeMillis() + CRAWLER_APPLY_WATCH_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(CRAWLER_APPLY_POLL_MS)
            when (watch.onPoll(crawler.status.value)) {
                CrawlerWatchVerdict.STOPPED_EXTERNALLY -> {
                    // Stopped externally (user / wifi gate) — nothing to judge.
                    setCrawlerApplyStatus(CrawlerApplyPhase.OK, "applied; crawler stopped during watch")
                    return
                }
                CrawlerWatchVerdict.EARLY_EXIT -> {
                    rollBackCrawlerApply(crawlerConfig, previous)
                    return
                }
                else -> {}
            }
        }
        when (watch.onDeadline(crawler.status.value)) {
            CrawlerWatchVerdict.EARLY_EXIT -> rollBackCrawlerApply(crawlerConfig, previous)
            CrawlerWatchVerdict.STOPPED_EXTERNALLY ->
                setCrawlerApplyStatus(CrawlerApplyPhase.OK, "applied; crawler stopped during watch")
            else -> setCrawlerApplyStatus(CrawlerApplyPhase.OK, "applied; crawler running")
        }
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
        // (Re)register — see registerCrawlerSupervisor — so the crawler stays
        // listed in the daemons table.
        val restored = registerCrawlerSupervisor(emptyList())
        restored.start()
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
                if (CrawlerGate.mayStart(configStore.load().wizardCompleted, crawler.enabled, crawler.wifiOnly, ws.wifi)) {
                    crawlerPausedNoWifi = false
                    runOnCrawlerControl { startCrawlerSupervisor() }
                } else {
                    crawlerPausedNoWifi = crawler.enabled && crawler.wifiOnly && !ws.wifi
                    runOnCrawlerControl { stopCrawlerSupervisors() }
                }
            }
        }
    }

    private fun runOnCrawlerControl(block: () -> Unit): Boolean =
        try {
            crawlerControlExecutor.execute { block() }
            true
        } catch (e: RejectedExecutionException) {
            false // service shutting down
        }

    /**
     * Dashboard start/stop/restart actions. A stop blocks for the daemon's
     * SIGTERM grace (60 s for the crawler), so these never run on the caller's
     * thread (a Ktor request handler or the main thread): they are queued on a
     * per-label single-thread executor, which keeps one daemon's actions in
     * click order without a slow crawler stop delaying a transmission action.
     * The dashboard observes the outcome via the STOPPING/STOPPED status.
     */
    fun restartDaemon(label: String): Boolean {
        if (label == CRAWLER_LABEL) {
            // The crawler is always registered (see registerCrawlerSupervisor),
            // but a restart is gated like a manual start — restarting while
            // paused (wifi-only, no wifi) is refused (plan 10 §5) — and the
            // stop+start is serialized on the crawler-control executor with
            // every other crawler lifecycle op (apply/gate), not
            // on the per-label daemon-action executor.
            val c = configStore.load().crawler
            if (!CrawlerGate.mayStart(configStore.load().wizardCompleted, c.enabled, c.wifiOnly, wifiGateMonitor.state.value.wifi)) {
                return false
            }
            return runOnCrawlerControl {
                val crawler = registerCrawlerSupervisor(emptyList())
                try {
                    crawler.restart()
                } catch (e: Exception) {
                    android.util.Log.e("DaemonService", "restart $label failed", e)
                }
            }
        }
        if (label == FTP_LABEL) {
            // The FTP daemon is always registered (see registerFtpSupervisor),
            // but a restart is gated like a manual start — restarting while
            // the wifi gate is closed is refused — and the stop+start is
            // serialized on the ftp-control executor with every other FTP
            // lifecycle op (apply/gate), not the per-label daemon-action executor.
            val ws = wifiGateMonitor.state.value
            if (!configStore.load().wizardCompleted || !ws.wifi || ws.ip == null) {
                return false
            }
            val ip = ws.ip
            return try {
                ftpControlExecutor.execute {
                    try {
                        ftpSupervisor?.stop()
                        registerFtpSupervisor(ip).start()
                    } catch (e: Exception) {
                        android.util.Log.e("DaemonService", "restart $label failed", e)
                    }
                }
                true
            } catch (e: RejectedExecutionException) {
                false
            }
        }
        val sup = supervisors.firstOrNull { it.status.value.label == label } ?: return false
        return runDaemonAction(label, "restart") { sup.restart() }
    }
    fun stopDaemon(label: String): Boolean {
        val sup = supervisors.firstOrNull { it.status.value.label == label } ?: return false
        return runDaemonAction(label, "stop") { sup.stop() }
    }
    fun startDaemon(label: String): Boolean {
        // The crawler is always registered (see registerCrawlerSupervisor),
        // but the wifi gate always applies — a manual start while paused is
        // refused (plan 10 §5) — and the start is serialized on the
        // crawler-control executor with every other crawler lifecycle op.
        if (label == CRAWLER_LABEL) {
            val c = configStore.load().crawler
            if (!CrawlerGate.mayStart(configStore.load().wizardCompleted, c.enabled, c.wifiOnly, wifiGateMonitor.state.value.wifi)) {
                return false
            }
            startCrawlerSupervisor()
            return true
        }
        if (label == FTP_LABEL) {
            // A manual start is gated on the wifi gate (wizard completed, wifi
            // up, IP known) — the daemon binds to the gate's IP; a stop is
            // always allowed (generic branch below).
            val ws = wifiGateMonitor.state.value
            if (!configStore.load().wizardCompleted || !ws.wifi || ws.ip == null) {
                return false
            }
            val ip = ws.ip
            runOnFtpControl { startFtpSupervisorOnIo(ip) }
            return true
        }
        val sup = supervisors.firstOrNull { it.status.value.label == label } ?: return false
        return runDaemonAction(label, "start") { sup.start() }
    }

    private fun runDaemonAction(label: String, action: String, block: () -> Unit): Boolean {
        val executor = daemonActionExecutors.computeIfAbsent(label) {
            Executors.newSingleThreadExecutor(ThreadFactory { r ->
                Thread(r, "daemon-action-$label").apply { isDaemon = true }
            })
        }
        return try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    android.util.Log.e("DaemonService", "$action $label failed", e)
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            false
        }
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
    /**
     * Finishes the first-run wizard: records completion, then starts everything
     * [onStartCommand] and [startSupervisors] deferred until setup was done — the
     * dashboard (0.0.0.0:8090), the crawler proxy (0.0.0.0:8080), the crawler and
     * the transmission/FTP daemons. All of these are idempotent, and the crawler's
     * mayStart gate only opens once setWizardCompleted() has run, so the order matters.
     */
    fun completeWizard() {
        configStore.setWizardCompleted()
        controlServer.start()
        syncCrawlerProxy(configStore.load().crawler)
        startCrawlerIfEnabled()
        startConfiguredDaemons()
    }

    private fun launchRollbackWatcher(sup: ProcessSupervisor?, statusAtApply: ProcessStatus, previous: com.torrent.app.config.AppConfig) {
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
                    configStore.save(previous)
                    transmissionConfig.ensureSettingsJson()
                    // stop() blocks for the SIGTERM grace: keep it off the main thread.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        sup.stop(); delay(200); sup.start()
                    }
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

        val shutdownIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DaemonService::class.java).setAction(ACTION_SHUTDOWN_AND_EXIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Torrent App running")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_lock_power_off, "Shutdown", shutdownIntent)
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
        // No blanket supervisor guard here: the crawler and ftp_server
        // supervisors are registered in onCreate (so they are always listed),
        // and each start below is idempotent on its own label — repeated
        // onStartCommand deliveries are harmless.
        transmissionConfig = TransmissionConfig(this, configStore)
        if (configStore.load().wizardCompleted) startTransmissionSupervisor()
        // The crawler does not need the wizard/SAF (plan 09 §5): its supervisor
        // was registered in onCreate, so dht_crawler is always listed in the
        // daemons table — start it here only when the wifi gate allows
        // (plan 10 §5); the gate collector starts it as soon as wifi is up.
        val crawler = configStore.load().crawler
        if (CrawlerGate.mayStart(configStore.load().wizardCompleted, crawler.enabled, crawler.wifiOnly, wifiGateMonitor.state.value.wifi)) {
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
            label = TRANSMISSION_LABEL, binaryName = "libtransmissiond.so",
            args = listOf("--foreground", "--config-dir", transmissionConfig.configDir.absolutePath,
                "--logfile", File(logDir, "transmission.log").absolutePath, "--log-level=info"),
            nativeLibraryDir = nativeLibDir, workDir = workDir, logDir = logDir,
            extraEnv = mapOf("TORRENT_SAF_SOCKET" to safSocketFile.absolutePath,
                "TMPDIR" to File(filesDir, "tmp").apply { mkdirs() }.absolutePath),
            selfLogging = true,
            // Readiness: transmission is only RUNNING once its RPC answers a
            // session-get on 127.0.0.1:9091 (Basic auth; the 409 session-id
            // handshake happens inside the client, ~3 s timeout). Credentials
            // are re-read on every poll so a password change during the launch
            // cannot wedge readiness.
            readinessProbe = {
                val tx = configStore.load().transmission
                val rpc = com.torrent.app.transmission.TransmissionRpcClient(tx.username, tx.password)
                val ready = rpc.sessionGet() != null
                if (ready) {
                    // Force turtle/alt-speed off the instant the daemon answers: settings.json is
                    // already false (persisted), but re-asserting here clears any in-memory/stale
                    // schedule so alt-speed can never re-enable on this run.
                    rpc.sessionSet(
                        com.torrent.app.transmission.TransmissionRpcClient.buildAltSpeedDisableArgs())
                }
                ready
            })
        transmission.start(); supervisors.add(transmission)
    }

    /**
     * Registers (without starting) the single dht_crawler supervisor and
     * returns it. The crawler supervisor stays registered for the whole
     * service lifetime — listed in the daemons table (STOPPED) even while the
     * crawler is disabled or paused, so the dashboard can always start it
     * again — and is replaced only when a settings apply changes its CLI args
     * ([crawlerSupervisorArgs]). Registration is pure (no layout work happens
     * until a start is requested), so it cannot fail.
     */
    private fun registerCrawlerSupervisor(args: List<String>): ProcessSupervisor {
        synchronized(crawlerRegistrationLock) {
            val previous = crawlerSupervisor
            if (previous != null && crawlerSupervisorArgs == args) return previous
            val crawler = buildCrawlerSupervisor(args, CrawlerConfig(this))
            crawlerSupervisor = crawler
            crawlerSupervisorArgs = args
            if (previous != null) supervisors.remove(previous)
            supervisors.add(crawler)
            return crawler
        }
    }

    /**
     * Starts the (already registered, see [registerCrawlerSupervisor])
     * dht_crawler supervisor. The work runs on the crawler-control executor,
     * which also hosts the crawler's stop(): a pending stop from a previous
     * service instance is serialized ahead of this block, so a new child is
     * never launched while the old one is still shutting down. start() itself
     * is idempotent (a RUNNING/STARTING crawler ignores it), so repeated
     * gate/dashboard triggers are harmless. Failures are logged, never thrown
     * — a broken crawler must not crash the service.
     */
    private fun startCrawlerSupervisor() {
        crawlerControlExecutor.execute {
            try {
                val crawlerConfig = CrawlerConfig(this)
                crawlerConfig.ensureLayout()
                // Stage 12: a classifier model-dir bump invalidates every stored
                // threshold, so migrate BEFORE anything can start the crawler.
                migrateClassifierVersion(crawlerConfig)
                // Stage 5: before the crawler's (re)launch, warm-start it from
                // a downloaded BEP51 bootstrap cache when configured. Bounded
                // (15 s connect / 30 s read), and every failure falls through
                // to the cold start below.
                maybeBootstrapBep51Cache(crawlerConfig)
                val crawler =
                    registerCrawlerSupervisor(emptyList())
                crawler.start()
            } catch (e: Exception) {
                android.util.Log.e("DaemonService", "failed to start crawler supervisor", e)
            }
        }
    }

    /**
     * Stage 12: resets the adult thresholds to the current classifier generation's
     * calibrated defaults the first time this build starts the crawler against a new
     * [CrawlerConfig.MODELS_SUBDIR], then re-renders `config.ini` so the pinned
     * `classifier_model_dir` and the new thresholds reach the daemon before it runs.
     *
     * The reset is unconditional (a value tuned against `models/v2` score distributions
     * is invalid under `models/v3`, not merely stale) and idempotent via the persisted
     * model-dir sentinel. Runs on the crawler-control executor inside the calling
     * block's try/catch (both crawler start paths: [runCrawlerApply] and
     * [startCrawlerSupervisor]); a migration failure aborts that start/apply attempt
     * — it is logged by the caller and the apply path surfaces FAILED — without
     * crash-looping the service.
     *
     * @return true when the thresholds were migrated (the sentinel did not match),
     *   false when it already matched this build's model generation.
     */
    private fun migrateClassifierVersion(crawlerConfig: CrawlerConfig): Boolean {
        val previous = configStore.load().crawler
        val newHide = ConfigDefaults.CRAWLER.adultHideThreshold
        val migration = ClassifierModelVersionMigration(
            readStoredModelDir = { configStore.classifierModelVersion() },
            resetThresholds = {
                android.util.Log.i(
                    "DaemonService",
                    "classifier model generation changed to ${CrawlerConfig.MODELS_SUBDIR}: " +
                        "resetting adult hide threshold ${previous.adultHideThreshold} -> $newHide"
                )
                configStore.resetClassifierThresholds(newHide)
            },
            writeStoredModelDir = { configStore.setClassifierModelVersion(it) },
        )
        return if (migration.run(CrawlerConfig.MODELS_SUBDIR)) {
            // Re-render so the daemon starts with the reset thresholds, not the old ones.
            crawlerConfig.applySettings(configStore.load().crawler)
            true
        } else {
            false
        }
    }

    /**
     * Stage 5: when the BEP51 bootstrap is configured, the cache file is
     * missing, downloads the cache
     * from the configured HTTPS URL so the crawler warm-starts. Runs on the
     * crawler-control executor (called from [startCrawlerSupervisor], the
     * only explicit start path — the supervisor's own crash-restart loop
     * never re-enters it, so auto-restarts do not re-download). Every
     * failure is logged and the start proceeds: the crawler cold-bootstraps
     * as before. Rate-limited in memory: a failed attempt is not retried
     * for [Bep51BootstrapDownloader.FAILURE_RETRY_INTERVAL_MS]. Only the URL
     * host is logged (the URL may carry a token).
     */
    private fun maybeBootstrapBep51Cache(crawlerConfig: CrawlerConfig) {
        val settings = configStore.load().backup
        val cacheFile = File(crawlerConfig.dataDir, "bep51_cache.dat")
        if (!Bep51BootstrapDownloader.shouldBootstrap(
            settings = settings,
            cacheExists = cacheFile.exists(),
            lastFailureAt = bep51BootstrapFailureAt,
            now = System.currentTimeMillis(),
        )) return
        val result = Bep51BootstrapDownloader().bootstrap(
            url = settings.bootstrapUrl,
            dest = cacheFile,
            tempDir = crawlerConfig.dataDir,
        )
        when (result) {
            is BootstrapResult.Placed ->
                android.util.Log.i(
                    "DaemonService",
                    "BEP51 bootstrap: placed ${result.bytes} bytes (${result.nodeCount} nodes) " +
                        "from ${hostOf(settings.bootstrapUrl)}")
            is BootstrapResult.SkippedExists ->
                android.util.Log.i("DaemonService", "BEP51 bootstrap: cache file already present")
            is BootstrapResult.Failed -> {
                bep51BootstrapFailureAt = System.currentTimeMillis()
                android.util.Log.w(
                    "DaemonService",
                    "BEP51 bootstrap from ${hostOf(settings.bootstrapUrl)} failed; " +
                        "crawler will cold-start: ${result.reason}")
            }
        }
    }

    /** Host part of a URL for logging (never the full URL — it may carry a token). */
    private fun hostOf(url: String): String =
        try { java.net.URI(url.trim()).host ?: "<no host>" }
        catch (e: Exception) { "<unparsable URL>" }

    /**
     * Starts the crawler if it is enabled and the wifi gate allows (plan 10 §5).
     * Used after a maintenance action to restore the crawler. The actual start
     * is queued on the crawler-control executor by [startCrawlerSupervisor].
     */
    private fun startCrawlerIfEnabled() {
        val c = configStore.load().crawler
        if (CrawlerGate.mayStart(configStore.load().wizardCompleted, c.enabled, c.wifiOnly, wifiGateMonitor.state.value.wifi)) {
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
     * Persists the first-run wizard's crawler toggle (plan 10 step 7): flips
     * `crawler.enabled` without a full apply job and starts the crawler when
     * the wifi gate allows.
     */
    fun setCrawlerEnabled(enabled: Boolean) {
        configStore.saveCrawler(configStore.load().crawler.copy(enabled = enabled))
        if (enabled) startCrawlerIfEnabled()
    }

    /**
     * Persists the first-run wizard's adult-mode choice (Stage 6) and writes it
     * into `config.ini`. The write is queued on the crawler-control executor, so
     * a [setCrawlerEnabled] start queued right after it (same serialized
     * executor) already launches the crawler with the chosen mode — the crawler
     * reads `adult_mode` only at startup.
     *
     * Because the daemon reads that key ONLY at startup, the write also restarts
     * a crawler that is already STARTING/RUNNING (see [restartCrawlerIfRunning])
     * so it re-reads `config.ini`: on-device the running crawler stayed in `tag`
     * mode while `config.ini` said `adult_mode=drop`, and it kept saving adult
     * torrents. A STOPPED crawler is not started here (first-run: the write must
     * land before the later start) and a STOPPING one is left to finish.
     */
    fun setCrawlerAdultMode(mode: String) {
        val next = configStore.load().crawler.copy(adultMode = mode)
        configStore.saveCrawler(next)
        try {
            crawlerControlExecutor.execute {
                try {
                    val crawlerConfig = CrawlerConfig(this)
                    crawlerConfig.ensureLayout()
                    crawlerConfig.applySettings(next)
                    restartCrawlerIfRunning()
                } catch (e: Exception) {
                    android.util.Log.e("DaemonService", "crawler adult-mode write failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Service shutting down: the store write above still lands and the
            // next apply renders it into config.ini.
        }
    }

    /**
     * Persists the first-run wizard's "categories to save" step and renders it
     * into `config.ini` as `save_categories`. Queued on the crawler-control
     * executor for the same reason as [setCrawlerAdultMode]: the write lands
     * before a [setCrawlerEnabled] start queued after it on the same serialized
     * executor.
     *
     * The crawler reads `save_categories` only at startup, so like
     * [setCrawlerAdultMode] the write restarts a crawler that is already
     * STARTING/RUNNING (see [restartCrawlerIfRunning]) — a write needs the
     * daemon to re-read `config.ini` to take effect; an apply/restart used to be
     * the only thing that did that. A STOPPED crawler is not started here and a
     * STOPPING one is left to finish. An empty list means "save all categories"
     * — the config writer omits the key in that case.
     */
    fun setCrawlerSaveCategories(categories: List<String>) {
        val next = configStore.load().crawler.copy(saveCategories = categories)
        configStore.saveCrawler(next)
        try {
            crawlerControlExecutor.execute {
                try {
                    val crawlerConfig = CrawlerConfig(this)
                    crawlerConfig.ensureLayout()
                    crawlerConfig.applySettings(next)
                    restartCrawlerIfRunning()
                } catch (e: Exception) {
                    android.util.Log.e("DaemonService", "crawler save-categories write failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Service shutting down: the store write above still lands and the
            // next apply renders it into config.ini.
        }
    }

    /**
     * Persists the wizard's in-session `save_categories` chip selection on behalf of
     * the restore section, BEFORE the restore starts: the DB restore's category filter
     * reads the persisted selection, so the chips must reach the store first (otherwise
     * wizard display, persisted config and the filter could disagree).
     *
     * Unlike [setCrawlerSaveCategories] this is a store write ONLY — no `config.ini`
     * rewrite, no [CrawlerConfig.applySettings], no crawler restart — because the
     * restore reads [ConfigStore], not the daemon's config file. An empty list
     * persists the empty set, which is the documented "save all categories".
     */
    fun saveCrawlerSaveCategoriesForRestore(
        categories: List<String>,
    ): com.torrent.app.config.ApplyResult {
        configStore.saveCrawler(configStore.load().crawler.copy(saveCategories = categories))
        return com.torrent.app.config.ApplyResult(true, "save categories saved", false)
    }

    /**
     * Restarts a STARTING/RUNNING crawler so it re-reads `config.ini` after a
     * settings write. Must run on the crawler-control executor, AFTER the write
     * landed: the crawler reads `adult_mode` / `save_categories` only at startup,
     * so without this a write to a running crawler is invisible until something
     * else restarts it.
     *
     * Never starts a STOPPED crawler (first-run: [setCrawlerAdultMode] /
     * [setCrawlerSaveCategories] are called before [setCrawlerEnabled] /
     * [startCrawlerSupervisor], which launch it against the fresh config) and
     * never touches a STOPPING one — its stop is in flight and the wifi-gate
     * observer owns the decision to start again. The restart itself is gated like
     * a manual one (see [restartDaemon]): while paused (`crawler.enabled=false`,
     * or wifi-only with wifi down) refuse it and let the gate observer manage the
     * crawler, so the two cannot fight.
     */
    private fun restartCrawlerIfRunning() {
        val state = crawlerSupervisor?.status?.value?.state
        if (!CrawlerRestartPolicy.shouldRestart(state)) return
        val c = configStore.load().crawler
        if (!CrawlerGate.shouldRun(c.enabled, c.wifiOnly, wifiGateMonitor.state.value.wifi)) {
            return
        }
        try {
            // Same registration/restart pair as restartDaemon(CRAWLER_LABEL).
            registerCrawlerSupervisor(emptyList()).restart()
        } catch (e: Exception) {
            // The write already landed: a failed relaunch is logged, not thrown.
            android.util.Log.e("DaemonService", "crawler restart after settings write failed", e)
        }
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
            extraEnv = mapOf("TMPDIR" to File(filesDir, "tmp").apply { mkdirs() }.absolutePath),
            // Readiness: the crawler is only RUNNING once its own HTTP API
            // answers GET /stats with 200. crawlerHttpPort() is the port the
            // crawler itself binds (http_port): the loopback crawlerHttpPort
            // normally, and proxyPort in lanDirect mode — where CrawlerConfig
            // writes http_bind=0.0.0.0, which still covers 127.0.0.1. Probing
            // loopback never goes through CrawlerProxyServer.
            readinessProbe = { crawlerStatsReachable() })
    }

    /**
     * Requests an ordered, GRACEFUL teardown. Unlike a
     * system-initiated destroy it AWAITS the crawler's SIGTERM grace (up to
     * [CRAWLER_GRACE_MS] + 2 s) so dht_crawler can flush its SQLite batch, save
     * its bloom filter and run the final WAL TRUNCATE checkpoint before the
     * process goes away. Runs on a process-wide single-thread executor (never the
     * main thread), so the caller never blocks. One cycle at a time: a request
     * made while a teardown is IN FLIGHT is a no-op, but once a cycle COMPLETED a
     * new request is accepted — the daemons may have been started again and the
     * service instance survives a graceful stop (the activity stays bound, so
     * stopSelf() does not destroy it).
     *
     * The teardown is sequential: worst case is roughly the crawler grace (62 s)
     * plus the FTP/transmission graces and the Ktor stop timeout — do not
     * promise callers a ~60 s completion.
     *
     * @return false when a shutdown is already in flight.
     */
    fun requestGracefulShutdown(): Boolean {
        if (!shutdownRequested.compareAndSet(false, true)) return false
        val planner = shutdownPlanner
        val teardown = Runnable {
            // Marks this run as the one allowed to block on the crawler's
            // SIGTERM grace (see stopCrawlerForShutdown).
            shutdownThreadId = Thread.currentThread().id
            try {
                planner.run()
            } finally {
                // Exit hook (Shutdown control: stop everything, then close the
                // app). Consulted BEFORE the claim is cleared and only on this
                // executor path — never in onDestroy's inline run, where a
                // system-initiated destroy must leave the process alive.
                // Ordering with requestShutdownAndExit(), which records the exit
                // intent before asking for the teardown: a request that arrived
                // while this cycle was in flight is seen here, so the cycle that
                // is already running is the one that closes the app.
                shutdownExitGate.onShutdownCycleCompleted()
                // The cycle is over: a later shutdown cycle must be accepted
                // again. Reached only on the shutdown executor, where cycles
                // are strictly serialized by the planner's in-flight guard, so
                // this reset never races a teardown that is still running.
                // (The RejectedExecutionException fallback below reuses this
                // same Runnable on a throwaway thread: it is unreachable while
                // the executor is never shut down, and if it ever did fire the
                // planner would still keep overlap out — but the shared
                // Runnable's head would clobber shutdownThreadId and this
                // finally would clear the claim mid-flight. Accepted as
                // unreachable rather than made safe.)
                shutdownRequested.set(false)
            }
        }
        try {
            shutdownExecutor.execute(teardown)
        } catch (e: RejectedExecutionException) {
            // Defensive only: [shutdownExecutor] is process-wide and never shut
            // down, so this cannot happen. Never run the (up to ~78 s) teardown
            // inline here — the caller is the binder/main thread, so that would
            // ANR. A throwaway daemon thread keeps the teardown off the caller.
            android.util.Log.w("DaemonService", "shutdown executor rejected: ${e.message}")
            Thread(teardown, "graceful-shutdown-fallback").apply { isDaemon = true }.start()
        }
        return true
    }

    /** True while a graceful shutdown is claimed or in flight (see [requestGracefulShutdown]). */
    fun isShutdownRequested(): Boolean = shutdownRequested.get()

    /**
     * Stops every daemon (the full ordered, graceful teardown) and then CLOSES
     * the app: the process is killed by [shutdownExitGate] from the teardown
     * executor once a shutdown cycle has completed (see [ShutdownExitGate]).
     *
     * Used by the in-app Shutdown control and, from Stage 2, the notification
     * action — both route through [ACTION_SHUTDOWN_AND_EXIT] / the binder.
     *
     * The exit intent is recorded BEFORE the teardown is requested, so a request
     * arriving while a cycle is already in flight still exits — from that
     * in-flight cycle's completion — instead of waiting for one that never starts.
     * Non-blocking: the caller (binder/main thread) returns at once and the
     * process dies seconds to ~78 s later, after the graces have run.
     */
    fun requestShutdownAndExit() {
        shutdownExitGate.requestExit()
        // Reuses the graceful path verbatim: it serializes the teardown on the
        // shutdown executor and its completion hook is what actually exits.
        // Returns false when a cycle is already in flight — that cycle's
        // completion sees the intent recorded above.
        requestGracefulShutdown()
    }

    /**
     * The ordered teardown, shared by [requestGracefulShutdown] and [onDestroy];
     * the planner runs one cycle at a time (a caller arriving mid-cycle no-ops,
     * a completed cycle can be re-run). Order matters: observers
     * that could restart a daemon stop first, the crawler is awaited before its
     * SAF-free stop, the FTP daemon stops before ftp-saf.sock goes away, the
     * dashboard outlives the daemons, and the locks/notification go last.
     */
    private fun buildShutdownPlanner() = GracefulShutdownPlanner(
        steps = listOf(
            GracefulShutdownPlanner.Step("state-stopping") {
                _state.value = ServiceState.Stopping
            },
            GracefulShutdownPlanner.Step("cancel-jobs") {
                rollbackJob?.cancel()
                ftpObservingJob?.cancel()
                crawlerObservingJob?.cancel()
            },
            GracefulShutdownPlanner.Step("stop-observers") {
                // Stop the hang watchdog and the wifi gate BEFORE the supervisors:
                // both can restart daemons (rebuildFtpSupervisor / gate collectors)
                // and must not fire mid-shutdown. Also stop the backup schedulers,
                // which would otherwise wake up and touch a dying crawler.
                ftpHangWatchdog.stop()
                wifiGateMonitor.stop()
                bep51Backup.stop()
                dbBackup.stop()
                dbRestore.stop()
            },
            GracefulShutdownPlanner.Step("stop-crawler") {
                stopCrawlerForShutdown()
            },
            GracefulShutdownPlanner.Step("stop-ftp") {
                stopSupervisorForShutdown(FTP_LABEL)
                // Also drop the cached registration: [stopSupervisorForShutdown]
                // unregisters the supervisor from [supervisors] but leaves
                // [ftpSupervisor]/[ftpBindIp] pointing at it, and a same-IP
                // restart then hits [registerFtpSupervisor]'s "same IP, not
                // forced" early return — the FTP daemon would run again without
                // ever being re-listed (no daemons-table row, no dashboard stop)
                // and a second graceful stop would leave it running. Cleared
                // under the registration lock so it cannot race a concurrent
                // gate/settings registration.
                synchronized(ftpRegistrationLock) {
                    ftpSupervisor = null
                    ftpBindIp = null
                }
            },
            GracefulShutdownPlanner.Step("stop-transmission") {
                stopSupervisorForShutdown(TRANSMISSION_LABEL)
            },
            GracefulShutdownPlanner.Step("stop-crawler-proxy") {
                crawlerProxy?.stop()
                crawlerProxy = null
            },
            // After the HTTP responses to the shutdown request are flushed.
            GracefulShutdownPlanner.Step("stop-control-server") {
                controlServer.stop()
            },
            GracefulShutdownPlanner.Step("stop-ipc") {
                safIpcServer.stop()
                ftpSafIpcServer.stop()
            },
            GracefulShutdownPlanner.Step("stop-gateway") {
                // Cleared, not just shut down: the service instance survives a
                // graceful stop, and ensureUsbGateway() only re-creates the
                // gateway when the field is null. A live restart must build a
                // fresh one from the persisted tree URI — the old gateway's
                // free-space executor is gone, so availableBytes() would throw.
                gateway?.shutdown()
                gateway = null
            },
            GracefulShutdownPlanner.Step("release-locks") {
                releaseLocks()
            },
            GracefulShutdownPlanner.Step("stop-foreground") {
                // stopSelf() is a no-op on the onDestroy path (already going away).
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            GracefulShutdownPlanner.Step("state-stopped") {
                _state.value = ServiceState.Stopped
            },
        ),
        onError = { step, e ->
            android.util.Log.w("DaemonService", "shutdown step '$step' failed: ${e.message}")
        },
    )

    /**
     * Stops (SIGTERM, awaited — so never call it on the main thread) and
     * unregisters every supervisor carrying [label].
     */
    private fun stopSupervisorForShutdown(label: String) {
        val targets = supervisors.filter { it.status.value.label == label }
        for (s in targets) {
            try {
                s.stop()
            } catch (e: Exception) {
                android.util.Log.w("DaemonService", "$label stop failed: ${e.message}")
            }
        }
        supervisors.removeAll(targets.toSet())
    }

    /**
     * Stops the crawler supervisor, AWAITING its SIGTERM grace when the teardown
     * runs on the shutdown thread (the point of the graceful path: the crawler
     * must get time to flush). The stop is always submitted to
     * [crawlerControlExecutor] — never called inline — so a pending stop from a
     * previous service instance stays serialized ahead of any new launch.
     * [onDestroy] runs the same planner inline on the main thread, where
     * blocking for the grace would ANR: there the stop stays fire-and-forget,
     * as it always was.
     */
    private fun stopCrawlerForShutdown() {
        val stop = Runnable { stopCrawlerSupervisors() }
        val onShutdownThread = shutdownThreadId == Thread.currentThread().id
        if (!onShutdownThread) {
            crawlerControlExecutor.execute(stop)
            return
        }
        val stopped = try {
            crawlerControlExecutor.submit(stop)
        } catch (e: RejectedExecutionException) {
            android.util.Log.w("DaemonService", "crawler stop rejected: ${e.message}")
            return
        }
        try {
            stopped.get(CRAWLER_GRACE_MS + 5_000L, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // Timeout/interrupt: stop() has already SIGKILLed the child by now;
            // never let the crawler delay the rest of the teardown.
            android.util.Log.w("DaemonService", "crawler shutdown wait ended: ${e.message}")
        }
    }

    /**
     * Stops the crawler supervisor but never unregisters it: dht_crawler
     * stays listed in the daemons table (state STOPPED), so it can always be
     * started/restarted from the dashboard. Must run on the crawler-control
     * executor: a pending stop from a previous service instance is then
     * serialized ahead of any new launch.
     */
    private fun stopCrawlerSupervisors() {
        val crawler = crawlerSupervisor ?: return
        try {
            crawler.stop()
        } catch (e: Exception) {
            android.util.Log.w("DaemonService", "crawler stop failed: ${e.message}")
        }
    }

    companion object {
        const val CHANNEL_ID = "daemon_service"
        const val NOTIFICATION_ID = 1
        const val CRAWLER_LABEL = "dht_crawler"
        const val FTP_LABEL = "ftp_server"
        const val TRANSMISSION_LABEL = "transmission"
        const val CRAWLER_GRACE_MS = 60_000L
        // Shutdown control: start-command action meaning "stop every daemon, then
        // close the app" (onStartCommand → requestShutdownAndExit). The in-app
        // button calls the binder method directly; Stage 2's notification action
        // will send this action instead.
        const val ACTION_SHUTDOWN_AND_EXIT = "com.torrent.app.action.SHUTDOWN_AND_EXIT"


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

        // Plan 07 §2.6: FTP supervisor stop/start/rebuild run on a dedicated
        // single-thread executor, never on the service main thread. Process-wide
        // and NEVER shut down (the same reason as crawlerControlExecutor): the
        // service instance outlives a graceful stop, so the FTP daemon can be
        // started again afterwards and a pending task from a previous service
        // instance must serialize ahead of any new instance's task instead of
        // being rejected.
        private val ftpControlExecutor = Executors.newSingleThreadExecutor(ThreadFactory {
            Thread(it, "ftp-control").apply { isDaemon = true }
        })

        // The graceful teardown runs on these threads (see requestGracefulShutdown).
        // Process-wide and NEVER shut down, for the same reason as
        // crawlerControlExecutor: the service instance outlives a graceful stop
        // (the activity stays bound, so stopSelf() does not destroy it), so a
        // second shutdown cycle must queue behind the first instead of being
        // rejected — and every cycle runs off the caller (binder/main) thread,
        // where the crawler's 60 s SIGTERM grace would ANR.
        private val shutdownExecutor = Executors.newSingleThreadExecutor(ThreadFactory {
            Thread(it, "graceful-shutdown").apply { isDaemon = true }
        })

        // Per-label executors for dashboard start/stop/restart (see
        // runDaemonAction). Process-wide and never shut down, like
        // crawlerControlExecutor; one daemon thread per supervised label.
        private val daemonActionExecutors =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ExecutorService>()
    }
}