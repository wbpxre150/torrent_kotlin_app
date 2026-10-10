package com.torrent.app.migration

import com.torrent.app.config.CrawlerKeys
import com.torrent.app.config.CrawlerSettings
import java.io.File

/**
 * Phases of the Termux migration (plan 10 §6). The phase is persisted to a
 * small state file so it survives a service restart. [isActive] marks the
 * phases in which a background job is in flight (used for mutual exclusion
 * with the apply and maintenance jobs).
 */
enum class MigrationPhase {
    IDLE,       // no tree granted / nothing in progress
    READY,      // tree granted, a scan can run
    SCANNING,
    SCANNED,
    COPYING,
    COPIED,
    VERIFYING,
    VERIFIED,
    SWAPPING,
    SWAPPED,    // data imported and in place; crawler stopped; config diff offered
    STARTED,    // crawler running on the imported data; awaiting confirmation
    CONFIRMED,  // migration finished
    REVERTED,   // rolled back to the pre-migration state
    FAILED,
    CANCELLED;

    /** True while a migration background job is in flight. */
    val isActive: Boolean get() = this in setOf(SCANNING, COPYING, VERIFYING, SWAPPING)
}

/**
 * Classification of a file in the Termux crawler's `data/` directory
 * (plan 10 §6.4): [FileRole.IMPORT] files are copied, [FileRole.SKIP] files
 * are listed as skipped, [FileRole.REJECT] entries must not be copied.
 */
enum class FileRole { IMPORT, SKIP, REJECT }

/** One entry of the `data/` scan, as listed by the DocumentsContract. */
data class ScannedFile(
    val name: String,
    val docId: String,
    val size: Long,
    val mtime: Long,
    val role: FileRole,
)

/**
 * Files imported from the Termux `data/` directory, in copy order
 * (database first, then its WAL, then the rest). `torrents.db` is required.
 */
val IMPORT_FILES: List<String> = listOf(
    "torrents.db",
    "torrents.db-wal",
    "bloom.dat",
    "failure_bloom.dat",
    "bep51_cache.dat",
)

/**
 * Classifies a source entry of the Termux crawler's `data/` directory
 * (plan 10 §6.4). Directories and symlinks are rejected; the allow-listed
 * database/bloom/cache files are imported; SQLite's `torrents.db-shm`
 * (rebuildable), leftover backup bookmarks and anything unknown are skipped.
 */
fun classifySourceFile(
    name: String,
    isDirectory: Boolean = false,
    isSymlink: Boolean = false,
): FileRole = when {
    isDirectory || isSymlink -> FileRole.REJECT
    name in IMPORT_FILES -> FileRole.IMPORT
    else -> FileRole.SKIP
}

/** True for the one file the migration refuses to run without. */
fun isRequiredSourceFile(name: String): Boolean = name == "torrents.db"

/**
 * Free internal space required to import [totalBytes]: the total plus a 10%
 * headroom (plan 10 §6.3). The swap keeps the old `data/` until it is moved
 * aside, so both copies must fit.
 */
fun requiredFreeSpace(totalBytes: Long): Long = totalBytes + totalBytes / 10

/**
 * The migration's file layout under the crawler work dir
 * (`filesDir/crawler/`): the live [data] dir, the import staging dir
 * [import], the pre-migration [old] dir, the [failed] dir (created by a
 * revert) and the [verify] dir holding the one-shot's config.ini.
 */
data class MigrationDirs(
    val data: File,
    val import: File,
    val old: File,
    val failed: File,
    val verify: File,
) {
    companion object {
        fun forWorkDir(workDir: File): MigrationDirs = MigrationDirs(
            data = File(workDir, "data"),
            import = File(workDir, "data.import"),
            old = File(workDir, "data.old"),
            failed = File(workDir, "data.failed"),
            verify = File(workDir, "verify"),
        )
    }
}

/**
 * Bloom tuning keys adopted from Termux's config.ini (plan 10 §6.7). The
 * `tree_rate_*`/`tree_min_lifetime_*`/`tree_require_empty_queue` rate keys
 * are covered by the `tree_` prefix rule below.
 */
private val BLOOM_TUNING_KEYS = setOf(
    "bloom_capacity", "failure_bloom_capacity", "bloom_error_rate",
    "bloom_enabled", "failure_strike_count", "bloom_persist",
)

/** Filter tuning keys. */
private val FILTER_TUNING_KEYS = setOf(
    "porn_filter_enabled", "language_filter_non_latin_threshold",
)

/** Rate-based-respawn tuning keys (not all of them are `tree_*` keys). */
private val RATE_RESPAWN_KEYS = setOf(
    "min_metadata_rate", "dynamic_rate_margin",
    "respawn_spawn_threshold", "respawn_drain_timeout_sec", "max_draining_trees",
)

/**
 * True for the keys that may be adopted from Termux's config.ini
 * (plan 10 §6.7): `num_trees`, every `tree_*` key, the bloom sizes, the
 * filters and the rate/respawn settings. Paths, `http_*`, `nice_level`,
 * `dht_port`, `log_level` and all backup keys are never imported.
 */
fun isTuningKey(key: String): Boolean =
    key == "num_trees" || key.startsWith("tree_") ||
        key in BLOOM_TUNING_KEYS || key in FILTER_TUNING_KEYS ||
        key in RATE_RESPAWN_KEYS

/** One differing tuning key, offered to the user for adoption. */
data class ConfigDiffEntry(
    val key: String,
    val termuxValue: String,
    val currentValue: String,
)

/**
 * The tuning keys that differ between Termux's config.ini ([termux]) and the
 * app's current config.ini ([current]). Pure: both maps come from
 * `CrawlerConfig.parseIni`. Keys absent from [current] are offered with a
 * `(not set)` marker.
 */
fun computeConfigDiff(
    termux: Map<String, String>,
    current: Map<String, String>,
): List<ConfigDiffEntry> {
    val out = mutableListOf<ConfigDiffEntry>()
    for ((key, termuxValue) in termux) {
        if (!isTuningKey(key)) continue
        val currentValue = current[key]
        if (currentValue == null || currentValue != termuxValue) {
            out.add(ConfigDiffEntry(key, termuxValue, currentValue ?: "(not set)"))
        }
    }
    return out
}

/**
 * Applies [diff] to [s]: form-managed tuning keys become the corresponding
 * [CrawlerSettings] fields; the rest (rate/respawn tuning) are merged into
 * the advanced-overrides text (existing valid entries are kept, matching
 * keys replaced). Values that do not parse are dropped — the apply job's
 * validator is the final gate.
 */
fun applyTuningDiff(s: CrawlerSettings, diff: List<ConfigDiffEntry>): CrawlerSettings {
    var out = s
    val advanced = LinkedHashMap<String, String>()
    for (e in diff) {
        when (e.key) {
            "num_trees" -> e.termuxValue.toIntOrNull()?.let { out = out.copy(numTrees = it) }
            "tree_find_node_workers" ->
                e.termuxValue.toIntOrNull()?.let { out = out.copy(treeFindNodeWorkers = it) }
            "tree_bep51_workers" ->
                e.termuxValue.toIntOrNull()?.let { out = out.copy(treeBep51Workers = it) }
            "tree_get_peers_workers" ->
                e.termuxValue.toIntOrNull()?.let { out = out.copy(treeGetPeersWorkers = it) }
            "tree_metadata_workers" ->
                e.termuxValue.toIntOrNull()?.let { out = out.copy(treeMetadataWorkers = it) }
            "tree_infohash_queue_capacity" ->
                e.termuxValue.toIntOrNull()?.let { out = out.copy(treeInfohashQueueCapacity = it) }
            "tree_peers_queue_capacity" ->
                e.termuxValue.toIntOrNull()?.let { out = out.copy(treePeersQueueCapacity = it) }
            "porn_filter_enabled" -> out = out.copy(pornFilterEnabled = e.termuxValue == "1")
            "language_filter_non_latin_threshold" ->
                e.termuxValue.toIntOrNull()?.let { out = out.copy(languageFilterNonLatinThreshold = it) }
            "bloom_capacity" -> e.termuxValue.toIntOrNull()?.let { out = out.copy(bloomCapacity = it) }
            "failure_bloom_capacity" ->
                e.termuxValue.toIntOrNull()?.let { out = out.copy(failureBloomCapacity = it) }
            "bloom_error_rate" ->
                e.termuxValue.toDoubleOrNull()?.let { out = out.copy(bloomErrorRate = it) }
            else -> advanced[e.key] = e.termuxValue
        }
    }
    if (advanced.isNotEmpty()) {
        val existing = CrawlerKeys.parseAdvanced(s.advancedOverrides).entries.toMutableMap()
        existing.putAll(advanced)
        out = out.copy(
            advancedOverrides = existing.entries.joinToString("\n") { "${it.key} = ${it.value}" })
    }
    return out
}
