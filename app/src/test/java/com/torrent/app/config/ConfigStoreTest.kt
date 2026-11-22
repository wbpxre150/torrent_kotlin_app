package com.torrent.app.config

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigStoreTest {
    private fun store() = ConfigStore(ApplicationProvider.getApplicationContext())

    @Test fun load_returnsDefaultsWhenEmpty() {
        val c = store().load()
        assertFalse(c.wizardCompleted)
        assertEquals(ConfigDefaults.TRANSMISSION.username, c.transmission.username)
        assertEquals("testtest", c.transmission.password)
        assertEquals(51413, c.transmission.peerPort)
        assertEquals(2121, c.ftp.port)
        assertFalse(c.ftp.wifiOnly)
        assertEquals(0, c.ftp.maxDownloadKiBps)
        assertTrue("crawler must be enabled by default", c.crawler.enabled)
    }

    private fun prefs() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("app_config", Context.MODE_PRIVATE)

    @Test fun save_dataSubpath_roundTrips() {
        val s = store()
        s.save(s.load().copy(dataSubpath = "torrent_data"))
        assertEquals("torrent_data", s.load().dataSubpath)
    }

    @Test fun load_dataSubpath_newKeyWinsOverLegacyKeys() {
        prefs().edit().clear()
            .putString("data_subpath", "New")
            .putString("tx_download_subpath", "LegacyTx")
            .putString("ftp_exposed_root", "LegacyFtp")
            .apply()
        assertEquals("New", store().load().dataSubpath)
    }

    @Test fun load_dataSubpath_fallsBackToLegacyTxSubpath() {
        prefs().edit().clear().putString("tx_download_subpath", "LegacyTx").apply()
        assertEquals("LegacyTx", store().load().dataSubpath)
    }

    @Test fun load_dataSubpath_fallsBackToLegacyFtpRoot() {
        prefs().edit().clear().putString("ftp_exposed_root", "LegacyFtp").apply()
        assertEquals("LegacyFtp", store().load().dataSubpath)
    }

    @Test fun load_dataSubpath_defaultsToTorrentDataWhenNoKeyPresent() {
        prefs().edit().clear().apply()
        assertEquals("torrent_data", store().load().dataSubpath)
    }

    @Test fun saveTransmission_persistsAndReloads() {
        val s = store()
        val tx = TransmissionSettings("user1", "pass1234", 51414, 100, 50, 10, 5)
        s.saveTransmission(tx)
        val loaded = s.load().transmission
        assertEquals("user1", loaded.username)
        assertEquals("pass1234", loaded.password)
        assertEquals(51414, loaded.peerPort)
        assertEquals(100, loaded.speedLimitDown)
    }

    @Test fun saveFtp_persistsAndReloads() {
        val s = store()
        s.saveFtp(FtpSettings("u", "password1", 2200, false, 512))
        val loaded = s.load().ftp
        assertEquals("u", loaded.username)
        assertEquals("password1", loaded.password)
        assertEquals(2200, loaded.port)
        assertFalse(loaded.wifiOnly)
        assertEquals(512, loaded.maxDownloadKiBps)
    }

    @Test fun saveCrawler_persistsAndReloads() {
        val s = store()
        s.saveCrawler(CrawlerSettings(enabled = true))
        assertTrue(s.load().crawler.enabled)
        s.saveCrawler(CrawlerSettings(enabled = false))
        assertFalse(s.load().crawler.enabled)
    }

    @Test fun ftpPassword_fallsBackToDefaultWhenEmpty_thenIdempotent() {
        val s = store()
        val p = s.ftpPassword()
        assertEquals(ConfigDefaults.FTP.password, p)
        assertEquals(p, s.ftpPassword())
    }

    @Test fun saveBackup_roundTripsThroughNewStore() {
        store().saveBackup(
            BackupSettings(
                r2Endpoint = "https://acme.r2.cloudflarestorage.com",
                r2Bucket = "cache",
                r2ObjectKey = "bep51/x.dat.gz",
                r2AccessKeyId = "AKID",
                r2SecretAccessKey = "s3cr3t",
                uploadEnabled = true,
                bootstrapEnabled = true,
                bootstrapUrl = "https://example.com/cache.dat.gz",
            )
        )
        val b = store().load().backup
        assertEquals("https://acme.r2.cloudflarestorage.com", b.r2Endpoint)
        assertEquals("cache", b.r2Bucket)
        assertEquals("bep51/x.dat.gz", b.r2ObjectKey)
        assertEquals("AKID", b.r2AccessKeyId)
        assertEquals("s3cr3t", b.r2SecretAccessKey)
        assertTrue(b.uploadEnabled)
        assertTrue(b.bootstrapEnabled)
        assertEquals("https://example.com/cache.dat.gz", b.bootstrapUrl)
    }

    @Test fun backupDefaults_matchConfigDefaults() {
        val b = store().load().backup
        assertEquals("", b.r2Endpoint)
        assertEquals("", b.r2Bucket)
        assertEquals("bep51/bep51_cache.dat.gz", b.r2ObjectKey)
        assertEquals("", b.r2AccessKeyId)
        assertEquals("", b.r2SecretAccessKey)
        assertFalse(b.uploadEnabled)
        assertTrue(b.bootstrapEnabled)
        assertEquals("https://unjumble.au/bep51/bep51_cache.dat.gz", b.bootstrapUrl)
    }

    @Test fun saveBackup_dbFieldsRoundTrip() {
        store().saveBackup(
            BackupSettings(
                r2Endpoint = "https://acme.r2.cloudflarestorage.com",
                r2Bucket = "cache",
                r2AccessKeyId = "AKID",
                r2SecretAccessKey = "s3cr3t",
                dbBackupEnabled = true,
                r2DbBucket = "my-dht-backup",
                r2DbObjectPrefix = "custom",
            )
        )
        val b = store().load().backup
        assertTrue(b.dbBackupEnabled)
        assertEquals("my-dht-backup", b.r2DbBucket)
        assertEquals("custom", b.r2DbObjectPrefix)
    }

    @Test fun backupDbDefaults_disabledWithDhtBackupBucket() {
        val b = store().load().backup
        assertFalse(b.dbBackupEnabled)
        assertEquals("dht-backup", b.r2DbBucket)
        assertEquals("db", b.r2DbObjectPrefix)
    }

    @Test fun dbBackupSuccessDate_roundTrips() {
        val s = store()
        assertNull(s.lastDbBackupSuccessDate())
        s.setDbBackupSuccessDate("2025-01-02")
        assertEquals("2025-01-02", s.lastDbBackupSuccessDate())
        // Independent of the BEP51 cache upload date.
        s.setLastBackupSuccessDate("2025-01-03")
        assertEquals("2025-01-02", s.lastDbBackupSuccessDate())
        assertEquals("2025-01-03", s.lastBackupSuccessDate())
    }

    @Test fun clearDbBackupSuccessDate_clearsKey() {
        val s = store()
        s.setDbBackupSuccessDate("2025-01-02")
        assertEquals("2025-01-02", s.lastDbBackupSuccessDate())
        // A "reset backup from zero" drops the daily gate so the whole DB is
        // drained again in the same day.
        s.clearDbBackupSuccessDate()
        assertNull(s.lastDbBackupSuccessDate())
    }

    @Test fun setWizardCompleted_persists() {
        val s = store()
        assertFalse(s.load().wizardCompleted)
        s.setWizardCompleted()
        assertTrue(s.load().wizardCompleted)
    }

    @Test fun snapshotAndRestoreLastKnownGoodTransmission() {
        val s = store()
        assertNull(s.restoreLastKnownGoodTransmission())
        s.snapshotLastKnownGood(TransmissionSettings("u", "p", 51413, 0, 0, 0, 0))
        val restored = s.restoreLastKnownGoodTransmission()
        assertNotNull(restored)
        assertEquals("u", restored!!.username)
        assertEquals("p", restored.password)
    }

    @Test fun clearLastKnownGood_removesSnapshot() {
        val s = store()
        s.snapshotLastKnownGood(TransmissionSettings("u", "p", 51413, 0, 0, 0, 0))
        s.clearLastKnownGood()
        assertNull(s.restoreLastKnownGoodTransmission())
    }

    @Test fun saveCrawler_persistsAllFieldsAndReloads() {
        val s = store()
        s.saveCrawler(
            CrawlerSettings(
                enabled = true,
                wifiOnly = false,
                logLevel = "DEBUG",
                dhtPort = 6882,
                niceLevel = 5,
                numTrees = 8,
                treeFindNodeWorkers = 4,
                treeBep51Workers = 12,
                treeGetPeersWorkers = 25,
                treeMetadataWorkers = 40,
                treeInfohashQueueCapacity = 2500,
                treePeersQueueCapacity = 2500,
                saveCategories = listOf("tv", "anime"),
                saveTvKinds = listOf("episode", "daily"),
                languageFilterNonLatinThreshold = 50,
                classifierEnabled = false,
                adultMode = "drop",
                adultHideThreshold = 0.75,
                bloomCapacity = 12_000_000,
                failureBloomCapacity = 12_000_000,
                bloomErrorRate = 0.002,
                advancedOverrides = "batch_size=750",
                proxyPort = 8090,
                crawlerHttpPort = 8091,
                lanDirect = true,
            )
        )
        val c = s.load().crawler
        assertTrue(c.enabled)
        assertFalse(c.wifiOnly)
        assertEquals("DEBUG", c.logLevel)
        assertEquals(6882, c.dhtPort)
        assertEquals(5, c.niceLevel)
        assertEquals(8, c.numTrees)
        assertEquals(4, c.treeFindNodeWorkers)
        assertEquals(12, c.treeBep51Workers)
        assertEquals(25, c.treeGetPeersWorkers)
        assertEquals(40, c.treeMetadataWorkers)
        assertEquals(2500, c.treeInfohashQueueCapacity)
        assertEquals(2500, c.treePeersQueueCapacity)
        assertEquals(listOf("anime", "tv"), c.saveCategories) // stored as a set, sorted on load
        assertEquals(listOf("daily", "episode"), c.saveTvKinds)
        assertEquals(50, c.languageFilterNonLatinThreshold)
        assertFalse(c.classifierEnabled)
        assertEquals("drop", c.adultMode)
        assertEquals(0.75, c.adultHideThreshold, 1e-6)
        assertEquals(12_000_000, c.bloomCapacity)
        assertEquals(12_000_000, c.failureBloomCapacity)
        assertEquals(0.002, c.bloomErrorRate, 1e-6)
        assertEquals("batch_size=750", c.advancedOverrides)
        assertEquals(8090, c.proxyPort)
        assertEquals(8091, c.crawlerHttpPort)
        assertTrue(c.lanDirect)
    }

    @Test fun crawlerDefaults_matchConfigIniDefault() {
        val c = store().load().crawler
        assertTrue(c.enabled)
        assertFalse(c.wifiOnly)
        assertEquals("INFO", c.logLevel)
        assertEquals(6881, c.dhtPort)
        assertEquals(10, c.niceLevel)
        assertEquals(64, c.numTrees)
        assertEquals(3, c.treeFindNodeWorkers)
        assertEquals(5, c.treeBep51Workers)
        assertEquals(22, c.treeGetPeersWorkers)
        assertEquals(12, c.treeMetadataWorkers)
        assertEquals(500, c.treeInfohashQueueCapacity)
        assertEquals(500, c.treePeersQueueCapacity)
        // Empty selection = save everything (both keys are omitted from config.ini).
        assertTrue(c.saveCategories.isEmpty())
        assertTrue(c.saveTvKinds.isEmpty())
        assertEquals(33, c.languageFilterNonLatinThreshold)
        assertTrue(c.classifierEnabled)
        assertEquals("tag", c.adultMode)
        assertEquals(0.375, c.adultHideThreshold, 1e-6)
        assertEquals(30_000_000, c.bloomCapacity)
        assertEquals(30_000_000, c.failureBloomCapacity)
        assertEquals(0.001, c.bloomErrorRate, 1e-6)
        assertEquals(
            "tree_metadata_timeout_ms = 60000\nudp_rate_limit_pps = 3700\n" +
                "tree_metadata_peer_blacklist_ttl_sec = 600",
            c.advancedOverrides)
        assertEquals(8080, c.proxyPort)
        assertEquals(8081, c.crawlerHttpPort)
        assertFalse(c.lanDirect)
    }

    @Test fun snapshotAndRestoreCrawlerLastKnownGood() {
        val s = store()
        assertNull(s.restoreCrawlerLastKnownGood())
        s.snapshotCrawlerLastKnownGood(
            CrawlerSettings(
                enabled = true, numTrees = 32, logLevel = "WARN",
                classifierEnabled = false, adultMode = "drop",
                adultHideThreshold = 0.8,
                saveCategories = listOf("movie", "tv"), saveTvKinds = listOf("season_pack"),
            )
        )
        val restored = s.restoreCrawlerLastKnownGood()
        assertNotNull(restored)
        assertTrue(restored!!.enabled)
        assertEquals(32, restored.numTrees)
        assertEquals("WARN", restored.logLevel)
        assertFalse(restored.classifierEnabled)
        assertEquals("drop", restored.adultMode)
        assertEquals(0.8, restored.adultHideThreshold, 1e-6)
        assertEquals(listOf("movie", "tv"), restored.saveCategories)
        assertEquals(listOf("season_pack"), restored.saveTvKinds)
    }

    @Test fun clearCrawlerLastKnownGood_removesSnapshot() {
        val s = store()
        s.snapshotCrawlerLastKnownGood(CrawlerSettings(enabled = true))
        s.clearCrawlerLastKnownGood()
        assertNull(s.restoreCrawlerLastKnownGood())
    }

    @Test fun classifierModelVersion_roundTrips() {
        val s = store()
        assertNull("a fresh install has never migrated", s.classifierModelVersion())
        s.setClassifierModelVersion("models/v2")
        assertEquals("models/v2", s.classifierModelVersion())
    }

    @Test fun resetClassifierThresholds_overwritesLiveAndLastKnownGoodValues() {
        val s = store()
        // A user-customized value plus a snapshot taken with it: the v1-tuned numbers
        // must be gone from BOTH views, or a rollback would restore them.
        s.saveCrawler(CrawlerSettings(adultHideThreshold = 0.5))
        s.snapshotCrawlerLastKnownGood(
            CrawlerSettings(
                adultHideThreshold = 0.5,
                classifierEnabled = false, numTrees = 16,
            )
        )

        s.resetClassifierThresholds(0.897417)

        val live = s.load().crawler
        assertEquals(0.897417, live.adultHideThreshold, 1e-6)
        assertEquals("0.897417", prefs().getString("lkg_crawler_adult_hide_threshold", null))
        // The removed drop-threshold key is never persisted again.
        assertNull(prefs().getString("lkg_crawler_adult_drop_threshold", null))
        val lkg = s.restoreCrawlerLastKnownGood()
        assertNotNull(lkg)
        assertEquals(0.897417, lkg!!.adultHideThreshold, 1e-6)
        // The rest of the snapshot is untouched by the threshold reset.
        assertFalse(lkg.classifierEnabled)
        assertEquals(16, lkg.numTrees)
    }

    /**
     * Regression (device `b93dac74`): `POST /settings/crawler` queues the async
     * `applyCrawler(s)` — which persists `crawler.saveCategories` — and then
     * synchronously saves the backup section. A full-section write here
     * (`save(load().copy(backup = s))`) reused a snapshot that predated the queued
     * crawler save and reverted the freshly applied selection while `config.ini` kept
     * it; the restore filter reads that persisted selection, so it must be un-losable.
     */
    @Test fun saveBackup_isSectionTargeted() {
        val s = store()
        s.saveTransmission(TransmissionSettings("txuser", "txpass123", 51515, 111, 222, 33, 44))
        s.saveFtp(FtpSettings("ftpuser", "ftppass123", 2323, true, 777))
        s.saveCrawler(CrawlerSettings(logLevel = "DEBUG", saveCategories = listOf("movie")))
        s.save(s.load().copy(dataSubpath = "keep_me"))

        s.saveBackup(
            BackupSettings(
                r2Endpoint = "https://new.r2.cloudflarestorage.com",
                r2Bucket = "new-bucket",
                r2ObjectKey = "new/x.dat.gz",
                r2AccessKeyId = "NEWAKID",
                r2SecretAccessKey = "newsecret",
                uploadEnabled = true,
                dbBackupEnabled = true,
                r2DbBucket = "new-db-bucket",
                r2DbObjectPrefix = "newprefix",
                bootstrapEnabled = false,
                bootstrapUrl = "https://example.com/new.dat.gz",
            )
        )

        val c = s.load()
        // The backup keys took the new values...
        assertEquals("https://new.r2.cloudflarestorage.com", c.backup.r2Endpoint)
        assertEquals("new-bucket", c.backup.r2Bucket)
        assertEquals("new/x.dat.gz", c.backup.r2ObjectKey)
        assertEquals("NEWAKID", c.backup.r2AccessKeyId)
        assertEquals("newsecret", c.backup.r2SecretAccessKey)
        assertTrue(c.backup.uploadEnabled)
        assertTrue(c.backup.dbBackupEnabled)
        assertEquals("new-db-bucket", c.backup.r2DbBucket)
        assertEquals("newprefix", c.backup.r2DbObjectPrefix)
        assertFalse(c.backup.bootstrapEnabled)
        assertEquals("https://example.com/new.dat.gz", c.backup.bootstrapUrl)

        // ...and no other section moved: the crawler selection (read by the restore
        // filter) survives a racing/concurrent backup save untouched.
        assertEquals(listOf("movie"), c.crawler.saveCategories)
        assertEquals(setOf("movie"), prefs().getStringSet("crawler_save_categories", emptySet()))
        assertEquals("DEBUG", c.crawler.logLevel)
        assertEquals("txuser", c.transmission.username)
        assertEquals("txpass123", c.transmission.password)
        assertEquals(51515, c.transmission.peerPort)
        assertEquals(111, c.transmission.speedLimitDown)
        assertEquals("ftpuser", c.ftp.username)
        assertEquals("ftppass123", c.ftp.password)
        assertEquals(2323, c.ftp.port)
        assertTrue(c.ftp.wifiOnly)
        assertEquals(777, c.ftp.maxDownloadKiBps)
        assertEquals("keep_me", c.dataSubpath)
    }
}