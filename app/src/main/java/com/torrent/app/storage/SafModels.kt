package com.torrent.app.storage

/** Snapshot of USB grant state shown in the UI / control endpoint. */
data class SafStatus(
    val granted: Boolean,
    val treeUri: String?,
    val rootSummary: String?,   // e.g. "USB (128 GB)" when discoverable, else null
)

/** Result of the native SAF fd validation harness (Stage 3 runs it). */
data class SafValidationResult(
    val ok: Boolean,
    val message: String,
    val throughputMBs: Double?,   // null if not measured / failed
    val offsetBytes: Long,        // the >4GB offset tested
    val fat32Warning: Boolean,    // true if failure looked like the 4GB FAT32 cap
)