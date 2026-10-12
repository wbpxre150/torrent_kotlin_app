package com.torrent.app.ftp

/** Snapshot of the supervised native FTP daemon (stage 3) for the control UI / JSON endpoint. */
data class FtpStatus(
    val running: Boolean,
    val state: String,            // ProcessState name: RUNNING | STARTING | STOPPING | STOPPED
    val pid: Int?,
    val restartCount: Int,
    val ip: String?,
    val port: Int,
    val username: String,
    val password: String?,        // shown in control UI so the user can set up Kodi
    val wifiUp: Boolean,
    val logLines: List<String> = emptyList(),
)
