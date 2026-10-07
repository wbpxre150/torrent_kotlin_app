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

data class AppConfig(
    val wizardCompleted: Boolean,
    val transmission: TransmissionSettings,
)

object ConfigDefaults {
    const val TRANSMISSION_USERNAME = "torrentapp"
    const val TRANSMISSION_PEER_PORT = 51413
    const val PASSWORD_MIN_LEN = 6

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
    val INITIAL = AppConfig(wizardCompleted = false, transmission = TRANSMISSION)
}