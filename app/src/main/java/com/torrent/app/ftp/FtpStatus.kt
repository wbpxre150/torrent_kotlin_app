package com.torrent.app.ftp

/** Snapshot of the FTP server state for the control UI / JSON endpoint. */
data class FtpStatus(
    val running: Boolean,
    val state: String,            // "RUNNING" | "STOPPED" | "STARTING"
    val ip: String?,
    val port: Int,
    val username: String,
    val password: String?,        // shown in control UI so the user can set up Kodi
    val wifiUp: Boolean
)