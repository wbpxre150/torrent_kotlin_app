package com.torrent.app.migration

import com.torrent.app.config.CrawlerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure tests for the migration helpers (plan 10 step 6): source-file
 * classification, free-space math, the path layout, the tuning-key filter,
 * the config diff and applying the diff to settings.
 */
class MigrationHelpersTest {

    // --- classification ---

    @Test fun classify_allowsOnlyTheListedDataFiles() {
        assertEquals(FileRole.IMPORT, classifySourceFile("torrents.db"))
        assertEquals(FileRole.IMPORT, classifySourceFile("torrents.db-wal"))
        assertEquals(FileRole.IMPORT, classifySourceFile("bloom.dat"))
        assertEquals(FileRole.IMPORT, classifySourceFile("failure_bloom.dat"))
        assertEquals(FileRole.IMPORT, classifySourceFile("bep51_cache.dat"))
    }

    @Test fun classify_skipsShmBackupBookmarksAndUnknownFiles() {
        assertEquals(FileRole.SKIP, classifySourceFile("torrents.db-shm"))
        assertEquals(FileRole.SKIP, classifySourceFile("last_backup_date.txt"))
        assertEquals(FileRole.SKIP, classifySourceFile("torrents_backup_ids.txt"))
        assertEquals(FileRole.SKIP, classifySourceFile("whatever.tmp"))
    }

    @Test fun classify_rejectsDirectoriesAndSymlinks() {
        assertEquals(FileRole.REJECT, classifySourceFile("torrents.db", isDirectory = true))
        assertEquals(FileRole.REJECT, classifySourceFile("data", isDirectory = true))
        assertEquals(FileRole.REJECT, classifySourceFile("torrents.db", isSymlink = true))
        assertEquals(FileRole.REJECT, classifySourceFile("bloom.dat", isSymlink = true))
    }

    @Test fun importFiles_listsDatabaseFirstAndIsRequiredOnlyTorrentsDb() {
        assertEquals("torrents.db", IMPORT_FILES.first())
        assertTrue(IMPORT_FILES.containsAll(
            listOf("torrents.db-wal", "bloom.dat", "failure_bloom.dat", "bep51_cache.dat")))
        assertTrue(isRequiredSourceFile("torrents.db"))
        assertFalse(isRequiredSourceFile("torrents.db-wal"))
        assertFalse(isRequiredSourceFile("bloom.dat"))
    }

    // --- free space ---

    @Test fun requiredFreeSpace_isTotalPlusTenPercent() {
        assertEquals(1_100L, requiredFreeSpace(1_000L))
        assertEquals(0L, requiredFreeSpace(0L))
        assertEquals(11_000_000_000L, requiredFreeSpace(10_000_000_000L))
        // Integer division: 10% of 5 is 0.
        assertEquals(5L, requiredFreeSpace(5L))
    }

    @Test fun isSpaceOk_countsTheReclaimableImportBytes() {
        // requiredFreeSpace(1_000) = 1_100.
        assertFalse(isSpaceOk(1_000L, 0L, 1_000L))
        assertTrue(isSpaceOk(1_100L, 0L, 1_000L))
        assertTrue(isSpaceOk(600L, 500L, 1_000L))
        assertFalse(isSpaceOk(599L, 500L, 1_000L))
        assertTrue(isSpaceOk(0L, 1_100L, 1_000L))
    }

    // --- import completeness ---

    private fun scanned(vararg namesAndSizes: Pair<String, Long>): List<ScannedFile> =
        namesAndSizes.map { (name, size) -> ScannedFile(name, "doc-$name", size, 0L, FileRole.IMPORT) }

    @Test fun isImportComplete_trueWhenEveryScannedFileMatchesAndNoExtras() {
        val scannedFiles = scanned("torrents.db" to 100L, "bloom.dat" to 50L)
        assertTrue(isImportComplete(
            scannedFiles,
            mapOf("torrents.db" to 100L, "bloom.dat" to 50L)))
    }

    @Test fun isImportComplete_walMustMatchWhenListed() {
        val scannedFiles = scanned("torrents.db" to 100L, "torrents.db-wal" to 7L)
        assertTrue(isImportComplete(
            scannedFiles, mapOf("torrents.db" to 100L, "torrents.db-wal" to 7L)))
        // A different WAL size (the source grew after the copy) is not complete.
        assertFalse(isImportComplete(
            scannedFiles, mapOf("torrents.db" to 100L, "torrents.db-wal" to 8L)))
        // A WAL listed in the scan that the import dir lacks is not complete.
        assertFalse(isImportComplete(scannedFiles, mapOf("torrents.db" to 100L)))
    }

    @Test fun isImportComplete_falseWhenAFileIsMissingOrResized() {
        val scannedFiles = scanned("torrents.db" to 100L, "bloom.dat" to 50L)
        assertFalse(isImportComplete(scannedFiles, mapOf("torrents.db" to 100L)))
        assertFalse(isImportComplete(
            scannedFiles, mapOf("torrents.db" to 99L, "bloom.dat" to 50L)))
    }

    @Test fun isImportComplete_falseWhenTheImportDirHasExtraFiles() {
        val scannedFiles = scanned("torrents.db" to 100L)
        assertFalse(isImportComplete(
            scannedFiles, mapOf("torrents.db" to 100L, "stray.dat" to 1L)))
    }

    @Test fun isImportComplete_falseWithoutTorrentsDb() {
        // torrents.db is required on both sides.
        assertFalse(isImportComplete(
            scanned("bloom.dat" to 50L), mapOf("bloom.dat" to 50L)))
        assertFalse(isImportComplete(emptyList(), emptyMap()))
    }

    // --- path layout ---

    @Test fun migrationDirs_layoutsUnderTheCrawlerWorkDir() {
        val workDir = File("/data/user/0/com.torrent.app/files/crawler")
        val d = MigrationDirs.forWorkDir(workDir)
        assertEquals(File(workDir, "data"), d.data)
        assertEquals(File(workDir, "data.import"), d.import)
        assertEquals(File(workDir, "data.old"), d.old)
        assertEquals(File(workDir, "data.failed"), d.failed)
        assertEquals(File(workDir, "verify"), d.verify)
    }

    // --- tuning-key filter ---

    @Test fun isTuningKey_acceptsNumTreesTreePrefixBloomFiltersAndRateRespawn() {
        assertTrue(isTuningKey("num_trees"))
        assertTrue(isTuningKey("tree_find_node_workers"))
        assertTrue(isTuningKey("tree_rate_check_interval_sec"))
        assertTrue(isTuningKey("tree_min_lifetime_minutes"))
        assertTrue(isTuningKey("bloom_capacity"))
        assertTrue(isTuningKey("failure_bloom_capacity"))
        assertTrue(isTuningKey("bloom_error_rate"))
        assertTrue(isTuningKey("bloom_enabled"))
        assertTrue(isTuningKey("porn_filter_enabled"))
        assertTrue(isTuningKey("language_filter_non_latin_threshold"))
        assertTrue(isTuningKey("min_metadata_rate"))
        assertTrue(isTuningKey("dynamic_rate_margin"))
        assertTrue(isTuningKey("respawn_spawn_threshold"))
        assertTrue(isTuningKey("respawn_drain_timeout_sec"))
        assertTrue(isTuningKey("max_draining_trees"))
    }

    @Test fun isTuningKey_neverAcceptsPathsHttpNiceDhtLogOrBackupKeys() {
        assertFalse(isTuningKey("db_path"))
        assertFalse(isTuningKey("bloom_path"))
        assertFalse(isTuningKey("bep51_cache_path"))
        assertFalse(isTuningKey("http_port"))
        assertFalse(isTuningKey("http_bind"))
        assertFalse(isTuningKey("nice_level"))
        assertFalse(isTuningKey("dht_port"))
        assertFalse(isTuningKey("log_level"))
        assertFalse(isTuningKey("backup_enabled"))
        assertFalse(isTuningKey("ssh_backup_enabled"))
        assertFalse(isTuningKey("rclone_backup_enabled"))
        assertFalse(isTuningKey("batch_size"))
        assertFalse(isTuningKey("flush_interval"))
    }

    // --- config diff ---

    @Test fun computeConfigDiff_listsOnlyDifferingTuningKeys() {
        val termux = linkedMapOf(
            "num_trees" to "24",
            "tree_metadata_workers" to "60",
            "bloom_capacity" to "40000000",
            "respawn_spawn_threshold" to "5",
            "db_path" to "data/torrents.db",
            "backup_enabled" to "1",
            "http_port" to "8080",
        )
        val current = mapOf(
            "num_trees" to "16",
            "tree_metadata_workers" to "60", // same -> not offered
            "bloom_capacity" to "30000000",
        )
        val diff = computeConfigDiff(termux, current)
        assertEquals(
            listOf(
                ConfigDiffEntry("num_trees", "24", "16"),
                ConfigDiffEntry("bloom_capacity", "40000000", "30000000"),
                ConfigDiffEntry("respawn_spawn_threshold", "5", "(not set)"),
            ),
            diff)
    }

    @Test fun computeConfigDiff_isEmptyWhenNothingDiffers() {
        val m = mapOf("num_trees" to "16", "tree_bep51_workers" to "10")
        assertTrue(computeConfigDiff(m, m).isEmpty())
    }

    // --- applying the diff to settings ---

    @Test fun applyTuningDiff_setsFormManagedFields() {
        val s = CrawlerSettings()
        val out = applyTuningDiff(
            s,
            listOf(
                ConfigDiffEntry("num_trees", "24", "16"),
                ConfigDiffEntry("tree_metadata_workers", "60", "50"),
                ConfigDiffEntry("bloom_capacity", "40000000", "30000000"),
                ConfigDiffEntry("bloom_error_rate", "0.002", "0.001"),
                ConfigDiffEntry("porn_filter_enabled", "0", "1"),
            ))
        assertEquals(24, out.numTrees)
        assertEquals(60, out.treeMetadataWorkers)
        assertEquals(40_000_000, out.bloomCapacity)
        assertEquals(0.002, out.bloomErrorRate, 0.0)
        assertFalse(out.pornFilterEnabled)
        // Untouched fields keep their values.
        assertEquals(s.treeFindNodeWorkers, out.treeFindNodeWorkers)
    }

    @Test fun applyTuningDiff_mergesRateKeysIntoAdvancedOverrides() {
        val s = CrawlerSettings(advancedOverrides = "max_concurrent_connections = 500")
        val out = applyTuningDiff(
            s,
            listOf(
                ConfigDiffEntry("min_metadata_rate", "5", "(not set)"),
                ConfigDiffEntry("max_concurrent_connections", "600", "500"),
            ))
        val advanced = out.advancedOverrides
        assertTrue(advanced.contains("min_metadata_rate = 5"))
        assertTrue(advanced.contains("max_concurrent_connections = 600"))
        assertFalse(advanced.contains("500"))
    }

    @Test fun applyTuningDiff_dropsUnparseableValues() {
        val s = CrawlerSettings()
        val out = applyTuningDiff(s, listOf(ConfigDiffEntry("num_trees", "lots", "16")))
        assertEquals(s.numTrees, out.numTrees)
    }
}
