package com.torrent.app.storage

/** Snapshot of USB grant state shown in the UI / control endpoint. */
data class SafStatus(
    val granted: Boolean,
    val gatewayPresent: Boolean,   // true when the in-process SafStorageGateway is live
    val treeUri: String?,
    val rootSummary: String?,   // e.g. "USB (128 GB)" when discoverable, else null
)