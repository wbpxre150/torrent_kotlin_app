package com.torrent.app.config

import androidx.test.core.app.ApplicationProvider
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
        assertEquals("test", c.transmission.password)
        assertEquals(51413, c.transmission.peerPort)
        assertEquals(2121, c.ftp.port)
        assertTrue(c.ftp.wifiOnly)
        assertEquals(0, c.ftp.maxDownloadKiBps)
        assertFalse("crawler must be disabled by default", c.crawler.enabled)
    }

    @Test fun saveTransmission_persistsAndReloads() {
        val s = store()
        val tx = TransmissionSettings("user1", "pass1234", 51414, 100, 50, 10, 5, "Dl")
        s.saveTransmission(tx)
        val loaded = s.load().transmission
        assertEquals("user1", loaded.username)
        assertEquals("pass1234", loaded.password)
        assertEquals(51414, loaded.peerPort)
        assertEquals(100, loaded.speedLimitDown)
        assertEquals("Dl", loaded.downloadSubpath)
    }

    @Test fun saveFtp_persistsAndReloads() {
        val s = store()
        s.saveFtp(FtpSettings("u", "password1", 2200, false, "Media", 512))
        val loaded = s.load().ftp
        assertEquals("u", loaded.username)
        assertEquals("password1", loaded.password)
        assertEquals(2200, loaded.port)
        assertFalse(loaded.wifiOnly)
        assertEquals("Media", loaded.exposedRoot)
        assertEquals(512, loaded.maxDownloadKiBps)
    }

    @Test fun saveCrawler_persistsAndReloads() {
        val s = store()
        s.saveCrawler(CrawlerSettings(enabled = true))
        assertTrue(s.load().crawler.enabled)
        s.saveCrawler(CrawlerSettings(enabled = false))
        assertFalse(s.load().crawler.enabled)
    }

    @Test fun ftpPassword_autoGeneratesWhenEmpty_thenIdempotent() {
        val s = store()
        val p = s.ftpPassword()
        assertTrue(p.length >= 16)
        assertEquals(p, s.ftpPassword())
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
        s.snapshotLastKnownGood(TransmissionSettings("u", "p", 51413, 0, 0, 0, 0, ""))
        val restored = s.restoreLastKnownGoodTransmission()
        assertNotNull(restored)
        assertEquals("u", restored!!.username)
        assertEquals("p", restored.password)
    }

    @Test fun clearLastKnownGood_removesSnapshot() {
        val s = store()
        s.snapshotLastKnownGood(TransmissionSettings("u", "p", 51413, 0, 0, 0, 0, ""))
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
                skipIntegrityCheck = true,
                numTrees = 8,
                treeFindNodeWorkers = 4,
                treeBep51Workers = 12,
                treeGetPeersWorkers = 25,
                treeMetadataWorkers = 40,
                treeInfohashQueueCapacity = 2500,
                treePeersQueueCapacity = 2500,
                pornFilterEnabled = false,
                languageFilterNonLatinThreshold = 50,
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
        assertTrue(c.skipIntegrityCheck)
        assertEquals(8, c.numTrees)
        assertEquals(4, c.treeFindNodeWorkers)
        assertEquals(12, c.treeBep51Workers)
        assertEquals(25, c.treeGetPeersWorkers)
        assertEquals(40, c.treeMetadataWorkers)
        assertEquals(2500, c.treeInfohashQueueCapacity)
        assertEquals(2500, c.treePeersQueueCapacity)
        assertFalse(c.pornFilterEnabled)
        assertEquals(50, c.languageFilterNonLatinThreshold)
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
        assertFalse(c.enabled)
        assertTrue(c.wifiOnly)
        assertEquals("INFO", c.logLevel)
        assertEquals(6881, c.dhtPort)
        assertEquals(10, c.niceLevel)
        assertFalse(c.skipIntegrityCheck)
        assertEquals(16, c.numTrees)
        assertEquals(3, c.treeFindNodeWorkers)
        assertEquals(10, c.treeBep51Workers)
        assertEquals(30, c.treeGetPeersWorkers)
        assertEquals(50, c.treeMetadataWorkers)
        assertEquals(3000, c.treeInfohashQueueCapacity)
        assertEquals(3000, c.treePeersQueueCapacity)
        assertTrue(c.pornFilterEnabled)
        assertEquals(33, c.languageFilterNonLatinThreshold)
        assertEquals(30_000_000, c.bloomCapacity)
        assertEquals(30_000_000, c.failureBloomCapacity)
        assertEquals(0.001, c.bloomErrorRate, 1e-6)
        assertEquals("", c.advancedOverrides)
        assertEquals(8080, c.proxyPort)
        assertEquals(8081, c.crawlerHttpPort)
        assertFalse(c.lanDirect)
    }

    @Test fun snapshotAndRestoreCrawlerLastKnownGood() {
        val s = store()
        assertNull(s.restoreCrawlerLastKnownGood())
        s.snapshotCrawlerLastKnownGood(CrawlerSettings(enabled = true, numTrees = 32, logLevel = "WARN"))
        val restored = s.restoreCrawlerLastKnownGood()
        assertNotNull(restored)
        assertTrue(restored!!.enabled)
        assertEquals(32, restored.numTrees)
        assertEquals("WARN", restored.logLevel)
    }

    @Test fun clearCrawlerLastKnownGood_removesSnapshot() {
        val s = store()
        s.snapshotCrawlerLastKnownGood(CrawlerSettings(enabled = true))
        s.clearCrawlerLastKnownGood()
        assertNull(s.restoreCrawlerLastKnownGood())
    }
}