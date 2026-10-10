package com.torrent.app.config

data class TransmissionSettings(
    val username: String,
    val password: String,
    val peerPort: Int,
    val speedLimitDown: Int,
    val speedLimitUp: Int,
    val altSpeedDown: Int,
    val altSpeedUp: Int,
    val downloadSubpath: String,
)

data class FtpSettings(
    val username: String,
    val password: String,
    val port: Int,
    val wifiOnly: Boolean,
    val exposedRoot: String,
    val maxDownloadKiBps: Int = 0,   // 0 = unlimited
)

data class CrawlerSettings(
    val enabled: Boolean = false,
)

data class AppConfig(
    val wizardCompleted: Boolean,
    val transmission: TransmissionSettings,
    val ftp: FtpSettings,
    val crawler: CrawlerSettings,
)

object ConfigDefaults {
    const val TRANSMISSION_USERNAME = "torrentapp"
    const val FTP_USERNAME = "kodi"
    const val TRANSMISSION_PEER_PORT = 51413
    const val FTP_PORT = 2121
    const val PASSWORD_MIN_LEN = 6
    const val FTP_PASSWORD_LEN = 24
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
        downloadSubpath = "",
    )
    val FTP = FtpSettings(
        username = FTP_USERNAME,
        password = "",
        port = FTP_PORT,
        wifiOnly = true,
        exposedRoot = "",
    )
    val CRAWLER = CrawlerSettings(
        enabled = false,
    )
    val INITIAL = AppConfig(wizardCompleted = false, transmission = TRANSMISSION, ftp = FTP, crawler = CRAWLER)

    fun randomPassword(len: Int = FTP_PASSWORD_LEN): String {
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(len)
        repeat(len) { sb.append(FTP_PASSWORD_CHARS[rnd.nextInt(FTP_PASSWORD_CHARS.size)]) }
        return sb.toString()
    }
}