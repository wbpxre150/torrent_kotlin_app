package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {
    private fun validTx() = TransmissionSettings(
        username = "torrentapp", password = "secret123",
        peerPort = 51413,
        speedLimitDown = 0, speedLimitUp = 0,
        altSpeedDown = 50, altSpeedUp = 10,
    )
    private fun validFtp() = FtpSettings(
        username = "kodi", password = "kodiallday",
        port = 2121, wifiOnly = true,
    )
    @Test fun validTransmission_passes() {
        assertTrue(ConfigValidator.validateTransmission(validTx()).ok)
    }
    @Test fun emptyUsername_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(username = ""))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("username", true) })
    }
    @Test fun shortPassword_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(password = "ab"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("password", true) })
    }
    @Test fun outOfRangePeerPort_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(peerPort = 70000))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("peer", true) })
    }
    @Test fun negativeSpeedLimit_fails() {
        val r = ConfigValidator.validateTransmission(validTx().copy(speedLimitDown = -1))
        assertFalse(r.ok)
    }
    @Test fun validFtp_passes() {
        assertTrue(ConfigValidator.validateFtp(validFtp()).ok)
    }
    @Test fun ftpShortPassword_fails() {
        assertFalse(ConfigValidator.validateFtp(validFtp().copy(password = "x")).ok)
    }
    @Test fun ftpBadPort_fails() {
        assertFalse(ConfigValidator.validateFtp(validFtp().copy(port = 0)).ok)
    }
    @Test fun ftpNegativeMaxDownload_fails() {
        assertFalse(ConfigValidator.validateFtp(validFtp().copy(maxDownloadKiBps = -1)).ok)
    }
    @Test fun ftpZeroMaxDownload_passes() {
        assertTrue(ConfigValidator.validateFtp(validFtp().copy(maxDownloadKiBps = 0)).ok)
    }

    private fun validApp() = AppConfig(
        wizardCompleted = true,
        transmission = validTx(),
        ftp = validFtp(),
        crawler = CrawlerSettings(),
        dataSubpath = "torrent_data",
    )

    @Test fun emptyDataDir_passes() {
        assertTrue(ConfigValidator.validateDataDir(validApp().copy(dataSubpath = "")).ok)
    }

    @Test fun validDataDir_passes() {
        assertTrue(ConfigValidator.validateDataDir(validApp()).ok)
        assertTrue(ConfigValidator.validateDataDir(validApp().copy(dataSubpath = "a/b")).ok)
    }

    @Test fun dataDirLeadingSlash_fails() {
        val r = ConfigValidator.validateDataDir(validApp().copy(dataSubpath = "/abs"))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("data directory must be relative", true) })
    }

    @Test fun dataDirDotDot_fails() {
        val r = ConfigValidator.validateDataDir(validApp().copy(dataSubpath = "../x"))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("data directory must be relative", true) })
    }

    @Test fun dataDirBadChars_fails() {
        val r = ConfigValidator.validateDataDir(validApp().copy(dataSubpath = "bad\$chars"))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("data directory must be relative", true) })
    }

    private fun validCrawler() = CrawlerSettings()

    @Test fun validCrawler_passes() {
        assertTrue(ConfigValidator.validateCrawler(validCrawler()).isEmpty())
    }

    @Test fun crawlerBadDhtPort_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(dhtPort = 70000)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(dhtPort = 0)).isEmpty())
    }

    @Test fun crawlerPortClash_fails() {
        val errors = ConfigValidator.validateCrawler(validCrawler().copy(crawlerHttpPort = 8080))
        assertTrue(errors.any { it.contains("differ") })
    }

    @Test fun crawlerBadProxyPort_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(proxyPort = 0)).isEmpty())
    }

    @Test fun crawlerNumTreesOutOfRange_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(numTrees = 0)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(numTrees = 65)).isEmpty())
    }

    @Test fun crawlerWorkersOutOfRange_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(treeMetadataWorkers = 0)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(treeFindNodeWorkers = 101)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(treeGetPeersWorkers = 1001)).isEmpty())
    }

    @Test fun crawlerNiceLevelOutOfRange_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(niceLevel = 20)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(niceLevel = -21)).isEmpty())
    }

    @Test fun crawlerBadLogLevel_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(logLevel = "TRACE")).isEmpty())
    }

    @Test fun crawlerThresholdOutOfRange_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(languageFilterNonLatinThreshold = 101)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(languageFilterNonLatinThreshold = -1)).isEmpty())
    }

    @Test fun crawlerBloomErrorRateOutOfRange_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(bloomErrorRate = 0.0)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(bloomErrorRate = 1.0)).isEmpty())
    }

    @Test fun crawlerZeroCapacity_fails() {
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(bloomCapacity = 0)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(failureBloomCapacity = 0)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(treeInfohashQueueCapacity = 0)).isEmpty())
        assertFalse(ConfigValidator.validateCrawler(validCrawler().copy(treePeersQueueCapacity = 0)).isEmpty())
    }

    @Test fun crawlerAdvancedWithBackupKey_fails() {
        val errors = ConfigValidator.validateCrawler(validCrawler().copy(advancedOverrides = "rclone_backup_enabled=1"))
        assertTrue(errors.any { it.contains("backup") })
    }

    @Test fun crawlerAdvancedValid_passes() {
        assertTrue(ConfigValidator.validateCrawler(validCrawler().copy(advancedOverrides = "batch_size=750")).isEmpty())
    }
}