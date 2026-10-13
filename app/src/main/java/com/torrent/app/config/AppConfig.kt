package com.torrent.app.config

data class TransmissionSettings(
    val username: String,
    val password: String,
    val peerPort: Int,
    val speedLimitDown: Int,
    val speedLimitUp: Int,
    val altSpeedDown: Int,
    val altSpeedUp: Int,
)

data class FtpSettings(
    val username: String,
    val password: String,
    val port: Int,
    val wifiOnly: Boolean,
    val maxDownloadKiBps: Int = 0,   // 0 = unlimited
)

data class CrawlerSettings(
    val enabled: Boolean = false,
    /** Start/stop only on wifi (default on: the crawler is bandwidth heavy). */
    val wifiOnly: Boolean = true,
    val logLevel: String = "INFO",
    val dhtPort: Int = 6881,
    val niceLevel: Int = 10,
    val numTrees: Int = 16,
    val treeFindNodeWorkers: Int = 3,
    val treeBep51Workers: Int = 10,
    val treeGetPeersWorkers: Int = 30,
    val treeMetadataWorkers: Int = 50,
    val treeInfohashQueueCapacity: Int = 3000,
    val treePeersQueueCapacity: Int = 3000,
    val pornFilterEnabled: Boolean = true,
    val languageFilterNonLatinThreshold: Int = 33,
    val bloomCapacity: Int = 30_000_000,
    val failureBloomCapacity: Int = 30_000_000,
    val bloomErrorRate: Double = 0.001,
    /** Raw `key=value` advanced overrides, checked against the CrawlerKeys allow-list. */
    val advancedOverrides: String = "",
    /** LAN port of the authenticated reverse proxy in front of the crawler UI. */
    val proxyPort: Int = 8080,
    /** Loopback HTTP port of the crawler itself. */
    val crawlerHttpPort: Int = 8081,
    /** Bind the crawler to 0.0.0.0 and skip the proxy (no auth, off by default). */
    val lanDirect: Boolean = false,
)

data class BackupSettings(
    /** Cloudflare R2 S3 endpoint, e.g. `https://<accountid>.r2.cloudflarestorage.com`. */
    val r2Endpoint: String = "",
    val r2Bucket: String = "",
    val r2ObjectKey: String = ConfigDefaults.BACKUP_OBJECT_KEY,
    val r2AccessKeyId: String = "",
    /** Plain SharedPreferences, same storage class as the control token (no encryption). */
    val r2SecretAccessKey: String = "",
    /** Whether the periodic/daily upload to R2 runs. */
    val uploadEnabled: Boolean = false,
    /** Wizard/app-start cache download toggle. */
    val bootstrapEnabled: Boolean = false,
    /** HTTPS URL serving the cache file for fresh installs. */
    val bootstrapUrl: String = "",
)

data class AppConfig(
    val wizardCompleted: Boolean,
    val transmission: TransmissionSettings,
    val ftp: FtpSettings,
    val crawler: CrawlerSettings,
    val backup: BackupSettings,
    val dataSubpath: String,
)

object ConfigDefaults {
    const val TRANSMISSION_USERNAME = "torrentapp"
    const val FTP_USERNAME = "kodi"
    const val TRANSMISSION_PEER_PORT = 51413
    const val FTP_PORT = 2121
    const val PASSWORD_MIN_LEN = 6
    const val FTP_PASSWORD_LEN = 24
    const val DATA_SUBPATH = ""
    const val BACKUP_OBJECT_KEY = "bep51/bep51_cache.dat.gz"
    private val FTP_PASSWORD_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

    val TRANSMISSION = TransmissionSettings(
        username = TRANSMISSION_USERNAME,
        password = "test",
        peerPort = TRANSMISSION_PEER_PORT,
        speedLimitDown = 0,
        speedLimitUp = 0,
        altSpeedDown = 0,
        altSpeedUp = 0,
    )
    val FTP = FtpSettings(
        username = FTP_USERNAME,
        password = "",
        port = FTP_PORT,
        wifiOnly = true,
    )
    val CRAWLER = CrawlerSettings(
        enabled = false,
    )
    val BACKUP = BackupSettings()
    val INITIAL = AppConfig(wizardCompleted = false, transmission = TRANSMISSION, ftp = FTP, crawler = CRAWLER, backup = BACKUP, dataSubpath = DATA_SUBPATH)

    fun randomPassword(len: Int = FTP_PASSWORD_LEN): String {
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(len)
        repeat(len) { sb.append(FTP_PASSWORD_CHARS[rnd.nextInt(FTP_PASSWORD_CHARS.size)]) }
        return sb.toString()
    }
}