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
            downloadSubpath = prefs.getString(KEY_TX_SUBPATH, ConfigDefaults.TRANSMISSION.downloadSubpath)!!,
        ),
        ftp = FtpSettings(
            username = prefs.getString(KEY_FTP_USER, ConfigDefaults.FTP.username)!!,
            password = prefs.getString(KEY_FTP_PASS, ConfigDefaults.FTP.password)!!,
            port = prefs.getInt(KEY_FTP_PORT, ConfigDefaults.FTP.port),
            wifiOnly = prefs.getBoolean(KEY_FTP_WIFI_ONLY, ConfigDefaults.FTP.wifiOnly),
            exposedRoot = prefs.getString(KEY_FTP_ROOT, ConfigDefaults.FTP.exposedRoot)!!,
            maxDownloadKiBps = prefs.getInt(KEY_FTP_MAX_DL_KIBPS, ConfigDefaults.FTP.maxDownloadKiBps),
        ),
        crawler = CrawlerSettings(
            enabled = prefs.getBoolean(KEY_CRAWLER_ENABLED, ConfigDefaults.CRAWLER.enabled),
        ),
    )

    fun saveTransmission(s: TransmissionSettings) {
        prefs.edit()
            .putString(KEY_TX_USER, s.username)
            .putString(KEY_TX_PASS, s.password)
            .putInt(KEY_TX_PEER_PORT, s.peerPort)
            .putInt(KEY_TX_SPEED_DOWN, s.speedLimitDown)
            .putInt(KEY_TX_SPEED_UP, s.speedLimitUp)
            .putInt(KEY_TX_ALT_DOWN, s.altSpeedDown)
            .putInt(KEY_TX_ALT_UP, s.altSpeedUp)
            .putString(KEY_TX_SUBPATH, s.downloadSubpath)
            .apply()
    }

    fun saveFtp(s: FtpSettings) {
        prefs.edit()
            .putString(KEY_FTP_USER, s.username)
            .putString(KEY_FTP_PASS, s.password)
            .putInt(KEY_FTP_PORT, s.port)
            .putBoolean(KEY_FTP_WIFI_ONLY, s.wifiOnly)
            .putString(KEY_FTP_ROOT, s.exposedRoot)
            .putInt(KEY_FTP_MAX_DL_KIBPS, s.maxDownloadKiBps)
            .apply()
    }

    fun saveCrawler(s: CrawlerSettings) {
        prefs.edit()
            .putBoolean(KEY_CRAWLER_ENABLED, s.enabled)
            .apply()
    }

    fun setWizardCompleted() { prefs.edit().putBoolean(KEY_WIZARD, true).apply() }

    /** FTP password auto-generates + persists on first access (preserves Stage 06 behavior). */
    fun ftpPassword(): String {
        prefs.getString(KEY_FTP_PASS, null)?.let { if (it.isNotEmpty()) return it }
        val gen = ConfigDefaults.randomPassword()
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
            .putString(KEY_LKG_TX_SUBPATH, s.downloadSubpath)
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
            downloadSubpath = prefs.getString(KEY_LKG_TX_SUBPATH, ConfigDefaults.TRANSMISSION.downloadSubpath)!!,
        )
    }

    fun clearLastKnownGood() { prefs.edit().remove(KEY_LKG_PRESENT).apply() }

    companion object {
        private const val PREFS = "app_config"
        private const val KEY_WIZARD = "wizard_completed"
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
        private const val KEY_LKG_PRESENT = "lkg_present"
        private const val KEY_LKG_TX_USER = "lkg_tx_username"
        private const val KEY_LKG_TX_PASS = "lkg_tx_password"
        private const val KEY_LKG_TX_PEER_PORT = "lkg_tx_peer_port"
        private const val KEY_LKG_TX_SPEED_DOWN = "lkg_tx_speed_limit_down"
        private const val KEY_LKG_TX_SPEED_UP = "lkg_tx_speed_limit_up"
        private const val KEY_LKG_TX_ALT_DOWN = "lkg_tx_alt_speed_down"
        private const val KEY_LKG_TX_ALT_UP = "lkg_tx_alt_speed_up"
        private const val KEY_LKG_TX_SUBPATH = "lkg_tx_download_subpath"
    }
}