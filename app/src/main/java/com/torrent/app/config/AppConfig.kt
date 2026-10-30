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
    val enabled: Boolean = true,
    /** Start/stop only on wifi (default off: the crawler may run on any network). */
    val wifiOnly: Boolean = false,
    val logLevel: String = "INFO",
    val dhtPort: Int = 6881,
    val niceLevel: Int = 10,
    val numTrees: Int = 32,
    val treeFindNodeWorkers: Int = 3,
    val treeBep51Workers: Int = 3,
    val treeGetPeersWorkers: Int = 18,
    val treeMetadataWorkers: Int = 12,
    val treeInfohashQueueCapacity: Int = 500,
    val treePeersQueueCapacity: Int = 500,
    /**
     * `save_categories`: category names to keep ([CrawlerTaxonomy.CATEGORIES]);
     * empty = save everything (the key is omitted from `config.ini`).
     */
    val saveCategories: List<String> = emptyList(),
    /**
     * `save_tv_kinds`: tv kinds to keep within tv/anime ([CrawlerTaxonomy.TV_KINDS]);
     * empty = keep all kinds.
     */
    val saveTvKinds: List<String> = emptyList(),
    val languageFilterNonLatinThreshold: Int = 33,
    /** Ingest-time classifier (`classifier_enabled`; default on). */
    val classifierEnabled: Boolean = true,
    /** `adult_mode`: `"tag"` stores adult rows, `"drop"` discards them (C `config.c` default: tag). */
    val adultMode: String = "tag",
    /**
     * `adult_hide_threshold`: adult probability at or above which an adult-tagged row
     * is hidden (`adult_mode=tag`) or discarded (`adult_mode=drop`).
     * Calibrated default from `models/v6/manifest.json`; the crawler reads 0 as
     * "take the manifest threshold", which the form never writes.
     */
    val adultHideThreshold: Double = 0.891912,
    val bloomCapacity: Int = 30_000_000,
    val failureBloomCapacity: Int = 30_000_000,
    val bloomErrorRate: Double = 0.001,
    /** Raw `key=value` advanced overrides, checked against the CrawlerKeys allow-list. */
    val advancedOverrides: String = "tree_metadata_timeout_ms = 60000\nudp_rate_limit_pps = 2500",
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
    /** Whether the daily incremental DB-dump upload (plan 2) runs. */
    val dbBackupEnabled: Boolean = false,
    /** R2 bucket for the incremental DB dumps. */
    val r2DbBucket: String = ConfigDefaults.DB_BACKUP_BUCKET,
    /** Object-key prefix of the DB dumps inside [r2DbBucket] (no '/'). */
    val r2DbObjectPrefix: String = ConfigDefaults.DB_BACKUP_OBJECT_PREFIX,
    /** Wizard/app-start cache download toggle (default on: fresh installs seed the BEP51 cache). */
    val bootstrapEnabled: Boolean = true,
    /** HTTPS URL serving the cache file for fresh installs. */
    val bootstrapUrl: String = ConfigDefaults.BACKUP_BOOTSTRAP_URL,
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
    const val DATA_SUBPATH = "torrent_data"
    const val BACKUP_OBJECT_KEY = "bep51/bep51_cache.dat.gz"
    const val BACKUP_BOOTSTRAP_URL = "https://unjumble.au/bep51/bep51_cache.dat.gz"
    const val DB_BACKUP_BUCKET = "dht-backup"
    const val DB_BACKUP_OBJECT_PREFIX = "db"
    private val FTP_PASSWORD_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

    val TRANSMISSION = TransmissionSettings(
        username = TRANSMISSION_USERNAME,
        password = "testtest",
        peerPort = TRANSMISSION_PEER_PORT,
        speedLimitDown = 0,
        speedLimitUp = 0,
        altSpeedDown = 0,
        altSpeedUp = 0,
    )
    val FTP = FtpSettings(
        username = FTP_USERNAME,
        password = "testtest",
        port = FTP_PORT,
        wifiOnly = false,
    )
    val CRAWLER = CrawlerSettings(
        enabled = true,
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