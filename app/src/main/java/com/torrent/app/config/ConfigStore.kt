package com.torrent.app.config

import android.content.Context
import android.content.SharedPreferences

class ConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppConfig = AppConfig(
        wizardCompleted = prefs.getBoolean(KEY_WIZARD, false),
        transmission = TransmissionSettings(
            username = prefs.getString(KEY_TX_USER, ConfigDefaults.TRANSMISSION.username)!!,
            password = prefs.getString(KEY_TX_PASS, ConfigDefaults.TRANSMISSION.password)!!,
            peerPort = prefs.getInt(KEY_TX_PEER_PORT, ConfigDefaults.TRANSMISSION.peerPort),
            speedLimitDown = prefs.getInt(KEY_TX_SPEED_DOWN, ConfigDefaults.TRANSMISSION.speedLimitDown),
            speedLimitUp = prefs.getInt(KEY_TX_SPEED_UP, ConfigDefaults.TRANSMISSION.speedLimitUp),
            altSpeedDown = prefs.getInt(KEY_TX_ALT_DOWN, ConfigDefaults.TRANSMISSION.altSpeedDown),
            altSpeedUp = prefs.getInt(KEY_TX_ALT_UP, ConfigDefaults.TRANSMISSION.altSpeedUp),
        ),
        ftp = FtpSettings(
            username = prefs.getString(KEY_FTP_USER, ConfigDefaults.FTP.username)!!,
            password = prefs.getString(KEY_FTP_PASS, ConfigDefaults.FTP.password)!!,
            port = prefs.getInt(KEY_FTP_PORT, ConfigDefaults.FTP.port),
            wifiOnly = prefs.getBoolean(KEY_FTP_WIFI_ONLY, ConfigDefaults.FTP.wifiOnly),
            maxDownloadKiBps = prefs.getInt(KEY_FTP_MAX_DL_KIBPS, ConfigDefaults.FTP.maxDownloadKiBps),
        ),
        crawler = CrawlerSettings(
            enabled = prefs.getBoolean(KEY_CRAWLER_ENABLED, ConfigDefaults.CRAWLER.enabled),
            wifiOnly = prefs.getBoolean(KEY_CRAWLER_WIFI_ONLY, ConfigDefaults.CRAWLER.wifiOnly),
            logLevel = prefs.getString(KEY_CRAWLER_LOG_LEVEL, ConfigDefaults.CRAWLER.logLevel)!!,
            dhtPort = prefs.getInt(KEY_CRAWLER_DHT_PORT, ConfigDefaults.CRAWLER.dhtPort),
            niceLevel = prefs.getInt(KEY_CRAWLER_NICE_LEVEL, ConfigDefaults.CRAWLER.niceLevel),
            numTrees = prefs.getInt(KEY_CRAWLER_NUM_TREES, ConfigDefaults.CRAWLER.numTrees),
            treeFindNodeWorkers = prefs.getInt(KEY_CRAWLER_FIND_NODE_WORKERS, ConfigDefaults.CRAWLER.treeFindNodeWorkers),
            treeBep51Workers = prefs.getInt(KEY_CRAWLER_BEP51_WORKERS, ConfigDefaults.CRAWLER.treeBep51Workers),
            treeGetPeersWorkers = prefs.getInt(KEY_CRAWLER_GET_PEERS_WORKERS, ConfigDefaults.CRAWLER.treeGetPeersWorkers),
            treeMetadataWorkers = prefs.getInt(KEY_CRAWLER_METADATA_WORKERS, ConfigDefaults.CRAWLER.treeMetadataWorkers),
            treeInfohashQueueCapacity = prefs.getInt(KEY_CRAWLER_INFOHASH_QUEUE, ConfigDefaults.CRAWLER.treeInfohashQueueCapacity),
            treePeersQueueCapacity = prefs.getInt(KEY_CRAWLER_PEERS_QUEUE, ConfigDefaults.CRAWLER.treePeersQueueCapacity),
            // Persisted as a StringSet; sorted on load so the order is deterministic
            // (an empty list = "save everything").
            saveCategories = prefs.getStringSet(KEY_CRAWLER_SAVE_CATEGORIES, emptySet()).orEmpty().sorted(),
            saveTvKinds = prefs.getStringSet(KEY_CRAWLER_SAVE_TV_KINDS, emptySet()).orEmpty().sorted(),
            languageFilterNonLatinThreshold = prefs.getInt(KEY_CRAWLER_LANG_THRESHOLD, ConfigDefaults.CRAWLER.languageFilterNonLatinThreshold),
            classifierEnabled = prefs.getBoolean(KEY_CRAWLER_CLASSIFIER_ENABLED, ConfigDefaults.CRAWLER.classifierEnabled),
            adultMode = prefs.getString(KEY_CRAWLER_ADULT_MODE, ConfigDefaults.CRAWLER.adultMode)!!,
            // Thresholds are stored as text so the calibrated decimals survive the
            // round trip exactly (a Float would render as 0.8974170088768005 in the form).
            adultHideThreshold = prefs.getString(KEY_CRAWLER_ADULT_HIDE_THRESHOLD, null)?.toDoubleOrNull()
                ?: ConfigDefaults.CRAWLER.adultHideThreshold,
            adultDropThreshold = prefs.getString(KEY_CRAWLER_ADULT_DROP_THRESHOLD, null)?.toDoubleOrNull()
                ?: ConfigDefaults.CRAWLER.adultDropThreshold,
            bloomCapacity = prefs.getInt(KEY_CRAWLER_BLOOM_CAPACITY, ConfigDefaults.CRAWLER.bloomCapacity),
            failureBloomCapacity = prefs.getInt(KEY_CRAWLER_FAILURE_BLOOM_CAPACITY, ConfigDefaults.CRAWLER.failureBloomCapacity),
            bloomErrorRate = prefs.getFloat(KEY_CRAWLER_BLOOM_ERROR_RATE, ConfigDefaults.CRAWLER.bloomErrorRate.toFloat()).toDouble(),
            advancedOverrides = prefs.getString(KEY_CRAWLER_ADVANCED, ConfigDefaults.CRAWLER.advancedOverrides)!!,
            proxyPort = prefs.getInt(KEY_CRAWLER_PROXY_PORT, ConfigDefaults.CRAWLER.proxyPort),
            crawlerHttpPort = prefs.getInt(KEY_CRAWLER_HTTP_PORT, ConfigDefaults.CRAWLER.crawlerHttpPort),
            lanDirect = prefs.getBoolean(KEY_CRAWLER_LAN_DIRECT, ConfigDefaults.CRAWLER.lanDirect),
        ),
        backup = BackupSettings(
            r2Endpoint = prefs.getString(KEY_BACKUP_R2_ENDPOINT, ConfigDefaults.BACKUP.r2Endpoint)!!,
            r2Bucket = prefs.getString(KEY_BACKUP_R2_BUCKET, ConfigDefaults.BACKUP.r2Bucket)!!,
            r2ObjectKey = prefs.getString(KEY_BACKUP_R2_OBJECT_KEY, ConfigDefaults.BACKUP.r2ObjectKey)!!,
            r2AccessKeyId = prefs.getString(KEY_BACKUP_R2_ACCESS_KEY_ID, ConfigDefaults.BACKUP.r2AccessKeyId)!!,
            r2SecretAccessKey = prefs.getString(KEY_BACKUP_R2_SECRET, ConfigDefaults.BACKUP.r2SecretAccessKey)!!,
            uploadEnabled = prefs.getBoolean(KEY_BACKUP_UPLOAD_ENABLED, ConfigDefaults.BACKUP.uploadEnabled),
            dbBackupEnabled = prefs.getBoolean(KEY_BACKUP_DB_ENABLED, ConfigDefaults.BACKUP.dbBackupEnabled),
            r2DbBucket = prefs.getString(KEY_BACKUP_R2_DB_BUCKET, ConfigDefaults.BACKUP.r2DbBucket)!!,
            r2DbObjectPrefix = prefs.getString(KEY_BACKUP_R2_DB_OBJECT_PREFIX, ConfigDefaults.BACKUP.r2DbObjectPrefix)!!,
            bootstrapEnabled = prefs.getBoolean(KEY_BACKUP_BOOTSTRAP_ENABLED, ConfigDefaults.BACKUP.bootstrapEnabled),
            bootstrapUrl = prefs.getString(KEY_BACKUP_BOOTSTRAP_URL, ConfigDefaults.BACKUP.bootstrapUrl)!!,
        ),
        dataSubpath = prefs.getString(KEY_DATA_SUBPATH, null)
            ?: prefs.getString(KEY_TX_SUBPATH, null)
            ?: prefs.getString(KEY_FTP_ROOT, null)
            ?: ConfigDefaults.DATA_SUBPATH,
    )

    /** Persist the whole config; includes the shared data directory key (Stage 11). */
    fun save(s: AppConfig) {
        saveTransmission(s.transmission)
        saveFtp(s.ftp)
        saveCrawler(s.crawler)
        saveBackup(s.backup)
        prefs.edit().putString(KEY_DATA_SUBPATH, s.dataSubpath).apply()
    }

    fun saveTransmission(s: TransmissionSettings) {
        prefs.edit()
            .putString(KEY_TX_USER, s.username)
            .putString(KEY_TX_PASS, s.password)
            .putInt(KEY_TX_PEER_PORT, s.peerPort)
            .putInt(KEY_TX_SPEED_DOWN, s.speedLimitDown)
            .putInt(KEY_TX_SPEED_UP, s.speedLimitUp)
            .putInt(KEY_TX_ALT_DOWN, s.altSpeedDown)
            .putInt(KEY_TX_ALT_UP, s.altSpeedUp)
            .apply()
    }

    fun saveFtp(s: FtpSettings) {
        prefs.edit()
            .putString(KEY_FTP_USER, s.username)
            .putString(KEY_FTP_PASS, s.password)
            .putInt(KEY_FTP_PORT, s.port)
            .putBoolean(KEY_FTP_WIFI_ONLY, s.wifiOnly)
            .putInt(KEY_FTP_MAX_DL_KIBPS, s.maxDownloadKiBps)
            .apply()
    }

    fun saveCrawler(s: CrawlerSettings) {
        prefs.edit()
            .putBoolean(KEY_CRAWLER_ENABLED, s.enabled)
            .putBoolean(KEY_CRAWLER_WIFI_ONLY, s.wifiOnly)
            .putString(KEY_CRAWLER_LOG_LEVEL, s.logLevel)
            .putInt(KEY_CRAWLER_DHT_PORT, s.dhtPort)
            .putInt(KEY_CRAWLER_NICE_LEVEL, s.niceLevel)
            .putInt(KEY_CRAWLER_NUM_TREES, s.numTrees)
            .putInt(KEY_CRAWLER_FIND_NODE_WORKERS, s.treeFindNodeWorkers)
            .putInt(KEY_CRAWLER_BEP51_WORKERS, s.treeBep51Workers)
            .putInt(KEY_CRAWLER_GET_PEERS_WORKERS, s.treeGetPeersWorkers)
            .putInt(KEY_CRAWLER_METADATA_WORKERS, s.treeMetadataWorkers)
            .putInt(KEY_CRAWLER_INFOHASH_QUEUE, s.treeInfohashQueueCapacity)
            .putInt(KEY_CRAWLER_PEERS_QUEUE, s.treePeersQueueCapacity)
            .putStringSet(KEY_CRAWLER_SAVE_CATEGORIES, s.saveCategories.toSet())
            .putStringSet(KEY_CRAWLER_SAVE_TV_KINDS, s.saveTvKinds.toSet())
            .putInt(KEY_CRAWLER_LANG_THRESHOLD, s.languageFilterNonLatinThreshold)
            .putBoolean(KEY_CRAWLER_CLASSIFIER_ENABLED, s.classifierEnabled)
            .putString(KEY_CRAWLER_ADULT_MODE, s.adultMode)
            .putString(KEY_CRAWLER_ADULT_HIDE_THRESHOLD, s.adultHideThreshold.toString())
            .putString(KEY_CRAWLER_ADULT_DROP_THRESHOLD, s.adultDropThreshold.toString())
            .putInt(KEY_CRAWLER_BLOOM_CAPACITY, s.bloomCapacity)
            .putInt(KEY_CRAWLER_FAILURE_BLOOM_CAPACITY, s.failureBloomCapacity)
            .putFloat(KEY_CRAWLER_BLOOM_ERROR_RATE, s.bloomErrorRate.toFloat())
            .putString(KEY_CRAWLER_ADVANCED, s.advancedOverrides)
            .putInt(KEY_CRAWLER_PROXY_PORT, s.proxyPort)
            .putInt(KEY_CRAWLER_HTTP_PORT, s.crawlerHttpPort)
            .putBoolean(KEY_CRAWLER_LAN_DIRECT, s.lanDirect)
            .apply()
    }

    fun saveBackup(s: BackupSettings) {
        prefs.edit()
            .putString(KEY_BACKUP_R2_ENDPOINT, s.r2Endpoint)
            .putString(KEY_BACKUP_R2_BUCKET, s.r2Bucket)
            .putString(KEY_BACKUP_R2_OBJECT_KEY, s.r2ObjectKey)
            .putString(KEY_BACKUP_R2_ACCESS_KEY_ID, s.r2AccessKeyId)
            .putString(KEY_BACKUP_R2_SECRET, s.r2SecretAccessKey)
            .putBoolean(KEY_BACKUP_UPLOAD_ENABLED, s.uploadEnabled)
            .putBoolean(KEY_BACKUP_DB_ENABLED, s.dbBackupEnabled)
            .putString(KEY_BACKUP_R2_DB_BUCKET, s.r2DbBucket)
            .putString(KEY_BACKUP_R2_DB_OBJECT_PREFIX, s.r2DbObjectPrefix)
            .putBoolean(KEY_BACKUP_BOOTSTRAP_ENABLED, s.bootstrapEnabled)
            .putString(KEY_BACKUP_BOOTSTRAP_URL, s.bootstrapUrl)
            .apply()
    }

    /** Local date (`yyyy-MM-dd`) of the last successful BEP51 cache upload (Stage 4). */
    fun lastBackupSuccessDate(): String? = prefs.getString(KEY_BACKUP_LAST_SUCCESS_DATE, null)

    /** Local date (`yyyy-MM-dd`) of the last successful DB-dump upload (plan 2, Stage 3). */
    fun lastDbBackupSuccessDate(): String? = prefs.getString(KEY_BACKUP_DB_LAST_SUCCESS_DATE, null)

    fun setLastBackupSuccessDate(date: String) {
        prefs.edit().putString(KEY_BACKUP_LAST_SUCCESS_DATE, date).apply()
    }

    fun setDbBackupSuccessDate(date: String) {
        prefs.edit().putString(KEY_BACKUP_DB_LAST_SUCCESS_DATE, date).apply()
    }

    /**
     * Model dir (`models/vN`) the classifier thresholds were last migrated for; the
     * sentinel of [ClassifierModelVersionMigration] (Stage 12). `null` means "never
     * migrated", so the first daemon start after this shipped performs the reset.
     */
    fun classifierModelVersion(): String? = prefs.getString(KEY_CLASSIFIER_MODEL_VERSION, null)

    fun setClassifierModelVersion(value: String) {
        prefs.edit().putString(KEY_CLASSIFIER_MODEL_VERSION, value).apply()
    }

    /**
     * Overwrites the adult thresholds with the current model generation's calibrated
     * defaults, in both the live keys and the last-known-good snapshot — otherwise a
     * rollback would restore thresholds calibrated against the previous model. Written
     * in a single edit so the two views never disagree. Only the two threshold LKG keys
     * are touched; the rest of the snapshot is left alone.
     */
    fun resetClassifierThresholds(hide: Double, drop: Double) {
        prefs.edit()
            .putString(KEY_CRAWLER_ADULT_HIDE_THRESHOLD, hide.toString())
            .putString(KEY_CRAWLER_ADULT_DROP_THRESHOLD, drop.toString())
            .putString(KEY_LKG_CRAWLER_ADULT_HIDE_THRESHOLD, hide.toString())
            .putString(KEY_LKG_CRAWLER_ADULT_DROP_THRESHOLD, drop.toString())
            .apply()
    }

    fun setWizardCompleted() { prefs.edit().putBoolean(KEY_WIZARD, true).apply() }

    /** FTP password falls back to the built-in default + persists on first access;
     *  a random password is only generated when the default is blank (Stage 06 behavior). */
    fun ftpPassword(): String {
        prefs.getString(KEY_FTP_PASS, null)?.let { if (it.isNotEmpty()) return it }
        val gen = ConfigDefaults.FTP.password.ifEmpty { ConfigDefaults.randomPassword() }
        prefs.edit().putString(KEY_FTP_PASS, gen).apply()
        return gen
    }

    // last-known-good rollback snapshot (transmission only; FTP is non-fatal to rebind)
    fun snapshotLastKnownGood(s: TransmissionSettings) {
        prefs.edit()
            .putString(KEY_LKG_TX_USER, s.username)
            .putString(KEY_LKG_TX_PASS, s.password)
            .putInt(KEY_LKG_TX_PEER_PORT, s.peerPort)
            .putInt(KEY_LKG_TX_SPEED_DOWN, s.speedLimitDown)
            .putInt(KEY_LKG_TX_SPEED_UP, s.speedLimitUp)
            .putInt(KEY_LKG_TX_ALT_DOWN, s.altSpeedDown)
            .putInt(KEY_LKG_TX_ALT_UP, s.altSpeedUp)
            .putBoolean(KEY_LKG_PRESENT, true)
            .apply()
    }

    fun restoreLastKnownGoodTransmission(): TransmissionSettings? {
        if (!prefs.getBoolean(KEY_LKG_PRESENT, false)) return null
        return TransmissionSettings(
            username = prefs.getString(KEY_LKG_TX_USER, ConfigDefaults.TRANSMISSION.username)!!,
            password = prefs.getString(KEY_LKG_TX_PASS, ConfigDefaults.TRANSMISSION.password)!!,
            peerPort = prefs.getInt(KEY_LKG_TX_PEER_PORT, ConfigDefaults.TRANSMISSION.peerPort),
            speedLimitDown = prefs.getInt(KEY_LKG_TX_SPEED_DOWN, ConfigDefaults.TRANSMISSION.speedLimitDown),
            speedLimitUp = prefs.getInt(KEY_LKG_TX_SPEED_UP, ConfigDefaults.TRANSMISSION.speedLimitUp),
            altSpeedDown = prefs.getInt(KEY_LKG_TX_ALT_DOWN, ConfigDefaults.TRANSMISSION.altSpeedDown),
            altSpeedUp = prefs.getInt(KEY_LKG_TX_ALT_UP, ConfigDefaults.TRANSMISSION.altSpeedUp),
        )
    }

    fun clearLastKnownGood() { prefs.edit().remove(KEY_LKG_PRESENT).apply() }

    // Crawler last-known-good snapshot (Stage 10 apply/rollback)

    fun snapshotCrawlerLastKnownGood(s: CrawlerSettings) {
        prefs.edit()
            .putBoolean(KEY_LKG_CRAWLER_ENABLED, s.enabled)
            .putBoolean(KEY_LKG_CRAWLER_WIFI_ONLY, s.wifiOnly)
            .putString(KEY_LKG_CRAWLER_LOG_LEVEL, s.logLevel)
            .putInt(KEY_LKG_CRAWLER_DHT_PORT, s.dhtPort)
            .putInt(KEY_LKG_CRAWLER_NICE_LEVEL, s.niceLevel)
            .putInt(KEY_LKG_CRAWLER_NUM_TREES, s.numTrees)
            .putInt(KEY_LKG_CRAWLER_FIND_NODE_WORKERS, s.treeFindNodeWorkers)
            .putInt(KEY_LKG_CRAWLER_BEP51_WORKERS, s.treeBep51Workers)
            .putInt(KEY_LKG_CRAWLER_GET_PEERS_WORKERS, s.treeGetPeersWorkers)
            .putInt(KEY_LKG_CRAWLER_METADATA_WORKERS, s.treeMetadataWorkers)
            .putInt(KEY_LKG_CRAWLER_INFOHASH_QUEUE, s.treeInfohashQueueCapacity)
            .putInt(KEY_LKG_CRAWLER_PEERS_QUEUE, s.treePeersQueueCapacity)
            .putStringSet(KEY_LKG_CRAWLER_SAVE_CATEGORIES, s.saveCategories.toSet())
            .putStringSet(KEY_LKG_CRAWLER_SAVE_TV_KINDS, s.saveTvKinds.toSet())
            .putInt(KEY_LKG_CRAWLER_LANG_THRESHOLD, s.languageFilterNonLatinThreshold)
            .putBoolean(KEY_LKG_CRAWLER_CLASSIFIER_ENABLED, s.classifierEnabled)
            .putString(KEY_LKG_CRAWLER_ADULT_MODE, s.adultMode)
            .putString(KEY_LKG_CRAWLER_ADULT_HIDE_THRESHOLD, s.adultHideThreshold.toString())
            .putString(KEY_LKG_CRAWLER_ADULT_DROP_THRESHOLD, s.adultDropThreshold.toString())
            .putInt(KEY_LKG_CRAWLER_BLOOM_CAPACITY, s.bloomCapacity)
            .putInt(KEY_LKG_CRAWLER_FAILURE_BLOOM_CAPACITY, s.failureBloomCapacity)
            .putFloat(KEY_LKG_CRAWLER_BLOOM_ERROR_RATE, s.bloomErrorRate.toFloat())
            .putString(KEY_LKG_CRAWLER_ADVANCED, s.advancedOverrides)
            .putInt(KEY_LKG_CRAWLER_PROXY_PORT, s.proxyPort)
            .putInt(KEY_LKG_CRAWLER_HTTP_PORT, s.crawlerHttpPort)
            .putBoolean(KEY_LKG_CRAWLER_LAN_DIRECT, s.lanDirect)
            .putBoolean(KEY_LKG_CRAWLER_PRESENT, true)
            .apply()
    }

    fun restoreCrawlerLastKnownGood(): CrawlerSettings? {
        if (!prefs.getBoolean(KEY_LKG_CRAWLER_PRESENT, false)) return null
        return CrawlerSettings(
            enabled = prefs.getBoolean(KEY_LKG_CRAWLER_ENABLED, ConfigDefaults.CRAWLER.enabled),
            wifiOnly = prefs.getBoolean(KEY_LKG_CRAWLER_WIFI_ONLY, ConfigDefaults.CRAWLER.wifiOnly),
            logLevel = prefs.getString(KEY_LKG_CRAWLER_LOG_LEVEL, ConfigDefaults.CRAWLER.logLevel)!!,
            dhtPort = prefs.getInt(KEY_LKG_CRAWLER_DHT_PORT, ConfigDefaults.CRAWLER.dhtPort),
            niceLevel = prefs.getInt(KEY_LKG_CRAWLER_NICE_LEVEL, ConfigDefaults.CRAWLER.niceLevel),
            numTrees = prefs.getInt(KEY_LKG_CRAWLER_NUM_TREES, ConfigDefaults.CRAWLER.numTrees),
            treeFindNodeWorkers = prefs.getInt(KEY_LKG_CRAWLER_FIND_NODE_WORKERS, ConfigDefaults.CRAWLER.treeFindNodeWorkers),
            treeBep51Workers = prefs.getInt(KEY_LKG_CRAWLER_BEP51_WORKERS, ConfigDefaults.CRAWLER.treeBep51Workers),
            treeGetPeersWorkers = prefs.getInt(KEY_LKG_CRAWLER_GET_PEERS_WORKERS, ConfigDefaults.CRAWLER.treeGetPeersWorkers),
            treeMetadataWorkers = prefs.getInt(KEY_LKG_CRAWLER_METADATA_WORKERS, ConfigDefaults.CRAWLER.treeMetadataWorkers),
            treeInfohashQueueCapacity = prefs.getInt(KEY_LKG_CRAWLER_INFOHASH_QUEUE, ConfigDefaults.CRAWLER.treeInfohashQueueCapacity),
            treePeersQueueCapacity = prefs.getInt(KEY_LKG_CRAWLER_PEERS_QUEUE, ConfigDefaults.CRAWLER.treePeersQueueCapacity),
            saveCategories = prefs.getStringSet(KEY_LKG_CRAWLER_SAVE_CATEGORIES, emptySet()).orEmpty().sorted(),
            saveTvKinds = prefs.getStringSet(KEY_LKG_CRAWLER_SAVE_TV_KINDS, emptySet()).orEmpty().sorted(),
            languageFilterNonLatinThreshold = prefs.getInt(KEY_LKG_CRAWLER_LANG_THRESHOLD, ConfigDefaults.CRAWLER.languageFilterNonLatinThreshold),
            classifierEnabled = prefs.getBoolean(KEY_LKG_CRAWLER_CLASSIFIER_ENABLED, ConfigDefaults.CRAWLER.classifierEnabled),
            adultMode = prefs.getString(KEY_LKG_CRAWLER_ADULT_MODE, ConfigDefaults.CRAWLER.adultMode)!!,
            adultHideThreshold = prefs.getString(KEY_LKG_CRAWLER_ADULT_HIDE_THRESHOLD, null)?.toDoubleOrNull()
                ?: ConfigDefaults.CRAWLER.adultHideThreshold,
            adultDropThreshold = prefs.getString(KEY_LKG_CRAWLER_ADULT_DROP_THRESHOLD, null)?.toDoubleOrNull()
                ?: ConfigDefaults.CRAWLER.adultDropThreshold,
            bloomCapacity = prefs.getInt(KEY_LKG_CRAWLER_BLOOM_CAPACITY, ConfigDefaults.CRAWLER.bloomCapacity),
            failureBloomCapacity = prefs.getInt(KEY_LKG_CRAWLER_FAILURE_BLOOM_CAPACITY, ConfigDefaults.CRAWLER.failureBloomCapacity),
            bloomErrorRate = prefs.getFloat(KEY_LKG_CRAWLER_BLOOM_ERROR_RATE, ConfigDefaults.CRAWLER.bloomErrorRate.toFloat()).toDouble(),
            advancedOverrides = prefs.getString(KEY_LKG_CRAWLER_ADVANCED, ConfigDefaults.CRAWLER.advancedOverrides)!!,
            proxyPort = prefs.getInt(KEY_LKG_CRAWLER_PROXY_PORT, ConfigDefaults.CRAWLER.proxyPort),
            crawlerHttpPort = prefs.getInt(KEY_LKG_CRAWLER_HTTP_PORT, ConfigDefaults.CRAWLER.crawlerHttpPort),
            lanDirect = prefs.getBoolean(KEY_LKG_CRAWLER_LAN_DIRECT, ConfigDefaults.CRAWLER.lanDirect),
        )
    }

    fun clearCrawlerLastKnownGood() { prefs.edit().remove(KEY_LKG_CRAWLER_PRESENT).apply() }

    companion object {
        private const val PREFS = "app_config"
        private const val KEY_WIZARD = "wizard_completed"
        private const val KEY_DATA_SUBPATH = "data_subpath"
        private const val KEY_TX_USER = "tx_username"
        private const val KEY_TX_PASS = "tx_password"
        private const val KEY_TX_PEER_PORT = "tx_peer_port"
        private const val KEY_TX_SPEED_DOWN = "tx_speed_limit_down"
        private const val KEY_TX_SPEED_UP = "tx_speed_limit_up"
        private const val KEY_TX_ALT_DOWN = "tx_alt_speed_down"
        private const val KEY_TX_ALT_UP = "tx_alt_speed_up"
        private const val KEY_TX_SUBPATH = "tx_download_subpath"
        private const val KEY_FTP_USER = "ftp_username"
        private const val KEY_FTP_PASS = "ftp_password"
        private const val KEY_FTP_PORT = "ftp_port"
        private const val KEY_FTP_WIFI_ONLY = "ftp_wifi_only"
        private const val KEY_FTP_ROOT = "ftp_exposed_root"
        private const val KEY_FTP_MAX_DL_KIBPS = "ftp_max_download_kibps"
        private const val KEY_CRAWLER_ENABLED = "crawler_enabled"
        private const val KEY_CRAWLER_WIFI_ONLY = "crawler_wifi_only"
        private const val KEY_CRAWLER_LOG_LEVEL = "crawler_log_level"
        private const val KEY_CRAWLER_DHT_PORT = "crawler_dht_port"
        private const val KEY_CRAWLER_NICE_LEVEL = "crawler_nice_level"
        private const val KEY_CRAWLER_NUM_TREES = "crawler_num_trees"
        private const val KEY_CRAWLER_FIND_NODE_WORKERS = "crawler_tree_find_node_workers"
        private const val KEY_CRAWLER_BEP51_WORKERS = "crawler_tree_bep51_workers"
        private const val KEY_CRAWLER_GET_PEERS_WORKERS = "crawler_tree_get_peers_workers"
        private const val KEY_CRAWLER_METADATA_WORKERS = "crawler_tree_metadata_workers"
        private const val KEY_CRAWLER_INFOHASH_QUEUE = "crawler_tree_infohash_queue_capacity"
        private const val KEY_CRAWLER_PEERS_QUEUE = "crawler_tree_peers_queue_capacity"
        private const val KEY_CRAWLER_SAVE_CATEGORIES = "crawler_save_categories"
        private const val KEY_CRAWLER_SAVE_TV_KINDS = "crawler_save_tv_kinds"
        private const val KEY_CRAWLER_LANG_THRESHOLD = "crawler_language_filter_non_latin_threshold"
        private const val KEY_CRAWLER_CLASSIFIER_ENABLED = "crawler_classifier_enabled"
        private const val KEY_CRAWLER_ADULT_MODE = "crawler_adult_mode"
        private const val KEY_CRAWLER_ADULT_HIDE_THRESHOLD = "crawler_adult_hide_threshold"
        private const val KEY_CRAWLER_ADULT_DROP_THRESHOLD = "crawler_adult_drop_threshold"
        /** Sentinel: the model dir the classifier thresholds were last migrated for. */
        private const val KEY_CLASSIFIER_MODEL_VERSION = "classifier_model_version"
        private const val KEY_CRAWLER_BLOOM_CAPACITY = "crawler_bloom_capacity"
        private const val KEY_CRAWLER_FAILURE_BLOOM_CAPACITY = "crawler_failure_bloom_capacity"
        private const val KEY_CRAWLER_BLOOM_ERROR_RATE = "crawler_bloom_error_rate"
        private const val KEY_CRAWLER_ADVANCED = "crawler_advanced_overrides"
        private const val KEY_CRAWLER_PROXY_PORT = "crawler_proxy_port"
        private const val KEY_CRAWLER_HTTP_PORT = "crawler_crawler_http_port"
        private const val KEY_CRAWLER_LAN_DIRECT = "crawler_lan_direct"
        private const val KEY_BACKUP_R2_ENDPOINT = "backup_r2_endpoint"
        private const val KEY_BACKUP_R2_BUCKET = "backup_r2_bucket"
        private const val KEY_BACKUP_R2_OBJECT_KEY = "backup_r2_object_key"
        private const val KEY_BACKUP_R2_ACCESS_KEY_ID = "backup_r2_access_key_id"
        private const val KEY_BACKUP_R2_SECRET = "backup_r2_secret_access_key"
        private const val KEY_BACKUP_UPLOAD_ENABLED = "backup_upload_enabled"
        private const val KEY_BACKUP_BOOTSTRAP_ENABLED = "backup_bootstrap_enabled"
        private const val KEY_BACKUP_BOOTSTRAP_URL = "backup_bootstrap_url"
        private const val KEY_BACKUP_LAST_SUCCESS_DATE = "backup_last_success_date"
        private const val KEY_BACKUP_DB_ENABLED = "backup_db_enabled"
        private const val KEY_BACKUP_R2_DB_BUCKET = "backup_r2_db_bucket"
        private const val KEY_BACKUP_R2_DB_OBJECT_PREFIX = "backup_r2_db_object_prefix"
        private const val KEY_BACKUP_DB_LAST_SUCCESS_DATE = "backup_db_last_success_date"
        private const val KEY_LKG_CRAWLER_PRESENT = "lkg_crawler_present"
        private const val KEY_LKG_CRAWLER_ENABLED = "lkg_crawler_enabled"
        private const val KEY_LKG_CRAWLER_WIFI_ONLY = "lkg_crawler_wifi_only"
        private const val KEY_LKG_CRAWLER_LOG_LEVEL = "lkg_crawler_log_level"
        private const val KEY_LKG_CRAWLER_DHT_PORT = "lkg_crawler_dht_port"
        private const val KEY_LKG_CRAWLER_NICE_LEVEL = "lkg_crawler_nice_level"
        private const val KEY_LKG_CRAWLER_NUM_TREES = "lkg_crawler_num_trees"
        private const val KEY_LKG_CRAWLER_FIND_NODE_WORKERS = "lkg_crawler_tree_find_node_workers"
        private const val KEY_LKG_CRAWLER_BEP51_WORKERS = "lkg_crawler_tree_bep51_workers"
        private const val KEY_LKG_CRAWLER_GET_PEERS_WORKERS = "lkg_crawler_tree_get_peers_workers"
        private const val KEY_LKG_CRAWLER_METADATA_WORKERS = "lkg_crawler_tree_metadata_workers"
        private const val KEY_LKG_CRAWLER_INFOHASH_QUEUE = "lkg_crawler_tree_infohash_queue_capacity"
        private const val KEY_LKG_CRAWLER_PEERS_QUEUE = "lkg_crawler_tree_peers_queue_capacity"
        private const val KEY_LKG_CRAWLER_SAVE_CATEGORIES = "lkg_crawler_save_categories"
        private const val KEY_LKG_CRAWLER_SAVE_TV_KINDS = "lkg_crawler_save_tv_kinds"
        private const val KEY_LKG_CRAWLER_LANG_THRESHOLD = "lkg_crawler_language_filter_non_latin_threshold"
        private const val KEY_LKG_CRAWLER_CLASSIFIER_ENABLED = "lkg_crawler_classifier_enabled"
        private const val KEY_LKG_CRAWLER_ADULT_MODE = "lkg_crawler_adult_mode"
        private const val KEY_LKG_CRAWLER_ADULT_HIDE_THRESHOLD = "lkg_crawler_adult_hide_threshold"
        private const val KEY_LKG_CRAWLER_ADULT_DROP_THRESHOLD = "lkg_crawler_adult_drop_threshold"
        private const val KEY_LKG_CRAWLER_BLOOM_CAPACITY = "lkg_crawler_bloom_capacity"
        private const val KEY_LKG_CRAWLER_FAILURE_BLOOM_CAPACITY = "lkg_crawler_failure_bloom_capacity"
        private const val KEY_LKG_CRAWLER_BLOOM_ERROR_RATE = "lkg_crawler_bloom_error_rate"
        private const val KEY_LKG_CRAWLER_ADVANCED = "lkg_crawler_advanced_overrides"
        private const val KEY_LKG_CRAWLER_PROXY_PORT = "lkg_crawler_proxy_port"
        private const val KEY_LKG_CRAWLER_HTTP_PORT = "lkg_crawler_crawler_http_port"
        private const val KEY_LKG_CRAWLER_LAN_DIRECT = "lkg_crawler_lan_direct"
        private const val KEY_LKG_PRESENT = "lkg_present"
        private const val KEY_LKG_TX_USER = "lkg_tx_username"
        private const val KEY_LKG_TX_PASS = "lkg_tx_password"
        private const val KEY_LKG_TX_PEER_PORT = "lkg_tx_peer_port"
        private const val KEY_LKG_TX_SPEED_DOWN = "lkg_tx_speed_limit_down"
        private const val KEY_LKG_TX_SPEED_UP = "lkg_tx_speed_limit_up"
        private const val KEY_LKG_TX_ALT_DOWN = "lkg_tx_alt_speed_down"
        private const val KEY_LKG_TX_ALT_UP = "lkg_tx_alt_speed_up"
    }
}