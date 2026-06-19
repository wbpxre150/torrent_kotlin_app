package com.torrent.app.storage

/** Snapshot of USB grant state shown in the UI / control endpoint. */
data class SafStatus(
    val granted: Boolean,
    val treeUri: String?,
    val rootSummary: String?,   // e.g. "USB (128 GB)" when discoverable, else null
)

/** Result of the SAF fd throughput validation harness. */
data class SafValidationResult(
    val ok: Boolean,
    val message: String,
    val throughputMBs: Double?,   // null if not measured / failed
)