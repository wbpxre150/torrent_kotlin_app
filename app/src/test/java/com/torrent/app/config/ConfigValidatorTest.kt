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
        backup = BackupSettings(),
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

    @Test fun crawlerAdultModeValidCaseInsensitively_passes() {
        for (mode in listOf("tag", "TAG", "Tag", "drop", "DROP", " Drop ")) {
            assertTrue(
                "\"$mode\" must be accepted",
                ConfigValidator.validateCrawler(validCrawler().copy(adultMode = mode)).isEmpty()
            )
        }
    }

    @Test fun crawlerAdultModeInvalid_failsListingValidValues() {
        val errors = ConfigValidator.validateCrawler(validCrawler().copy(adultMode = "hide"))
        assertTrue(errors.any { it.contains("adult mode") && it.contains(" tag") && it.contains("drop") })
    }

    @Test fun validateAdultMode_rejectsBlank() {
        assertFalse(ConfigValidator.validateAdultMode("").ok)
        assertFalse(ConfigValidator.validateAdultMode("   ").ok)
    }

    @Test fun validateFraction_acceptsOpenClosedUnitInterval() {
        assertTrue(ConfigValidator.validateFraction("x", 1.0).isEmpty())
        assertTrue(ConfigValidator.validateFraction("x", 0.5).isEmpty())
        assertTrue(ConfigValidator.validateFraction("x", 0.897417).isEmpty())
        assertTrue(ConfigValidator.validateFraction("x", 0.993899).isEmpty())
    }

    @Test fun validateFraction_rejectsZeroAboveOneAndNegative() {
        for (v in listOf(0.0, -0.1, 1.000001, 2.0)) {
            assertFalse("$v must be rejected", ConfigValidator.validateFraction("x", v).isEmpty())
        }
        assertEquals(listOf("x must be > 0 and <= 1"), ConfigValidator.validateFraction("x", 0.0))
    }

    @Test fun crawlerAdultThresholdsOutOfRange_fail() {
        // 0 is the crawler's hidden "use the manifest threshold" escape hatch: never written by the form.
        val hide = ConfigValidator.validateCrawler(validCrawler().copy(adultHideThreshold = 0.0))
        assertTrue(hide.any { it.contains("adult hide threshold") })
    }

    @Test fun crawlerDefaults_includeAdultHandling() {
        assertTrue(validCrawler().classifierEnabled)
        assertEquals("tag", validCrawler().adultMode)
        assertEquals(0.891912, validCrawler().adultHideThreshold, 0.0)
    }

    @Test fun crawlerAdvancedWithBackupKey_fails() {
        val errors = ConfigValidator.validateCrawler(validCrawler().copy(advancedOverrides = "rclone_backup_enabled=1"))
        assertTrue(errors.any { it.contains("backup") })
    }

    @Test fun crawlerAdvancedValid_passes() {
        assertTrue(ConfigValidator.validateCrawler(validCrawler().copy(advancedOverrides = "batch_size=750")).isEmpty())
    }
    @Test fun crawlerAdvancedUdpRateLimit_passes() {
        assertTrue(ConfigValidator.validateCrawler(validCrawler().copy(advancedOverrides = "udp_rate_limit_pps=800")).isEmpty())
    }
    @Test fun crawlerAdvancedTreeMetadataFreshPeerRetries_passes() {
        assertTrue(ConfigValidator.validateCrawler(validCrawler().copy(advancedOverrides = "tree_metadata_fresh_peer_retries=2")).isEmpty())
    }

    private fun validBackup() = BackupSettings(
        r2Endpoint = "https://acme.r2.cloudflarestorage.com",
        r2Bucket = "cache",
        r2AccessKeyId = "AKID",
        r2SecretAccessKey = "s3cr3t",
        uploadEnabled = true,
        bootstrapEnabled = true,
        bootstrapUrl = "https://example.com/cache.dat.gz",
    )

    @Test fun validBackup_passes() {
        assertTrue(ConfigValidator.validateBackup(validBackup()).ok)
    }

    @Test fun backupDisabled_emptyValues_pass() {
        assertTrue(ConfigValidator.validateBackup(BackupSettings()).ok)
    }

    @Test fun backupUploadHttpEndpoint_fails() {
        val r = ConfigValidator.validateBackup(validBackup().copy(r2Endpoint = "http://acme.r2.cloudflarestorage.com"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("R2 endpoint", true) })
    }

    @Test fun backupUploadEmptyHost_fails() {
        val r = ConfigValidator.validateBackup(validBackup().copy(r2Endpoint = "https://"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("R2 endpoint", true) })
    }

    @Test fun backupUploadMissingFields_fails() {
        val r = ConfigValidator.validateBackup(validBackup().copy(r2Bucket = "", r2AccessKeyId = "", r2SecretAccessKey = ""))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("bucket", true) })
        assertTrue(r.errors.any { it.contains("access key ID", true) })
        assertTrue(r.errors.any { it.contains("secret access key", true) })
    }

    @Test fun backupBootstrapHttpUrl_fails() {
        val r = ConfigValidator.validateBackup(validBackup().copy(bootstrapUrl = "http://example.com/cache.dat.gz"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("bootstrap", true) })
    }

    @Test fun backupBootstrapEmptyUrl_fails() {
        assertFalse(ConfigValidator.validateBackup(validBackup().copy(bootstrapUrl = "")).ok)
    }

    @Test fun backupBootstrapDisabled_emptyUrl_passes() {
        assertTrue(ConfigValidator.validateBackup(BackupSettings(bootstrapEnabled = false, bootstrapUrl = "")).ok)
    }

    @Test fun validDbBackup_passes() {
        assertTrue(ConfigValidator.validateDbBackup(validBackup().copy(dbBackupEnabled = true)).ok)
    }

    @Test fun dbBackupDisabled_emptyValues_pass() {
        assertTrue(ConfigValidator.validateDbBackup(BackupSettings()).ok)
    }

    @Test fun dbBackupHttpEndpoint_fails() {
        val r = ConfigValidator.validateDbBackup(
            validBackup().copy(dbBackupEnabled = true, r2Endpoint = "http://acme.r2.cloudflarestorage.com"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("R2 endpoint", true) })
    }

    @Test fun dbBackupMissingFields_fails() {
        val r = ConfigValidator.validateDbBackup(
            validBackup().copy(dbBackupEnabled = true, r2DbBucket = "", r2AccessKeyId = "", r2SecretAccessKey = ""))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("DB bucket", true) })
        assertTrue(r.errors.any { it.contains("access key ID", true) })
        assertTrue(r.errors.any { it.contains("secret access key", true) })
    }

    @Test fun dbBackupBlankPrefix_fails() {
        assertFalse(ConfigValidator.validateDbBackup(
            validBackup().copy(dbBackupEnabled = true, r2DbObjectPrefix = "")).ok)
    }

    @Test fun dbBackupPrefixWithSlash_fails() {
        assertFalse(ConfigValidator.validateDbBackup(
            validBackup().copy(dbBackupEnabled = true, r2DbObjectPrefix = "a/b")).ok)
    }

    @Test fun dbRestoreValidConfig_passes() {
        assertTrue(ConfigValidator.validateDbRestore(validBackup()).ok)
    }

    @Test fun dbRestoreBlankEndpoint_fails() {
        val r = ConfigValidator.validateDbRestore(validBackup().copy(r2Endpoint = ""))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("R2 endpoint", true) })
    }

    @Test fun dbRestoreHttpEndpoint_fails() {
        val r = ConfigValidator.validateDbRestore(validBackup().copy(r2Endpoint = "http://acme.r2.cloudflarestorage.com"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("R2 endpoint", true) })
    }

    @Test fun dbRestoreBlankDbBucketAndKeys_fails() {
        val r = ConfigValidator.validateDbRestore(
            validBackup().copy(r2DbBucket = "", r2AccessKeyId = "", r2SecretAccessKey = ""))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("DB bucket", true) })
        assertTrue(r.errors.any { it.contains("access key ID", true) })
        assertTrue(r.errors.any { it.contains("secret access key", true) })
    }

    @Test fun dbRestoreBlankPrefixFailsEvenWithDbBackupOff() {
        val r = ConfigValidator.validateDbRestore(
            validBackup().copy(dbBackupEnabled = false, r2DbObjectPrefix = ""))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("prefix", true) })
    }

    @Test fun dbRestoreSlashPrefixFailsEvenWithDbBackupOff() {
        val r = ConfigValidator.validateDbRestore(
            validBackup().copy(dbBackupEnabled = false, r2DbObjectPrefix = "a/b"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("prefix", true) })
    }

    @Test fun dbRestoreHttpBootstrapUrl_fails() {
        val r = ConfigValidator.validateDbRestore(
            validBackup().copy(bootstrapUrl = "http://example.com/cache.dat.gz"))
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("bootstrap", true) })
    }
}
