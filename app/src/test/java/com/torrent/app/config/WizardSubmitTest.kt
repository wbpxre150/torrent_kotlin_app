package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardSubmitTest {
    private fun validForm() = WizardSubmit.Form(
        txUsername = "torrentapp", txPassword = "secret123", peerPort = 51413, dataSubpath = "Downloads",
        ftpUsername = "kodi", ftpPassword = "kodiallday", ftpPort = 2121, ftpWifiOnly = true,
    )

    @Test fun validForm_producesSettings() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        assertTrue(o.errors.isEmpty())
        assertNotNull(o.transmission)
        assertNotNull(o.ftp)
        assertEquals("torrentapp", o.transmission!!.username)
        assertEquals(2121, o.ftp!!.port)
        assertEquals(0, o.ftp!!.maxDownloadKiBps)
    }

    @Test fun aggregatesTransmissionAndFtpErrors() {
        val f = validForm().copy(txPassword = "x", ftpPort = 0)
        val o = WizardSubmit.submit(f)
        assertFalse(o.ok)
        assertNull(o.transmission)
        assertNull(o.ftp)
        assertTrue(o.errors.any { it.contains("password", true) })
        assertTrue(o.errors.any { it.contains("port", true) })
    }

    @Test fun blankDataDir_passes() {
        assertTrue(WizardSubmit.submit(validForm().copy(dataSubpath = "")).ok)
    }

    @Test fun crawlerEnabledDefaultsToOff() {
        assertFalse(WizardSubmit.submit(validForm()).crawlerEnabled)
    }

    @Test fun crawlerEnabledTruePassedThrough() {
        assertTrue(WizardSubmit.submit(validForm().copy(crawlerEnabled = true)).crawlerEnabled)
    }

    @Test fun crawlerAdultModeDefaultsToTag() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        assertEquals("tag", o.crawlerAdultMode)
    }

    @Test fun crawlerAdultModeDropIsPassedThroughNormalised() {
        val o = WizardSubmit.submit(validForm().copy(crawlerAdultMode = " DROP "))
        assertTrue(o.ok)
        assertEquals("drop", o.crawlerAdultMode)
    }

    @Test fun crawlerAdultModeInvalidFailsValidation() {
        val o = WizardSubmit.submit(validForm().copy(crawlerAdultMode = "hide"))
        assertFalse(o.ok)
        assertNull(o.transmission)
        assertTrue(o.errors.any { it.contains("adult mode", true) })
    }

    @Test fun zeroPortFailsValidation() {
        val o = WizardSubmit.submit(validForm().copy(ftpPort = 0))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("port", true) })
    }

    @Test fun dotDotDataDirFailsValidation() {
        val o = WizardSubmit.submit(validForm().copy(dataSubpath = "../etc"))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("data directory", true) })
    }

    @Test fun backupFieldsMappedIntoOutcome() {
        val o = WizardSubmit.submit(validForm().copy(
            r2Endpoint = "https://acme.r2.cloudflarestorage.com",
            r2Bucket = "cache",
            r2ObjectKey = "bep51/k.dat.gz",
            r2AccessKeyId = "AKID",
            r2SecretAccessKey = "s3cr3t",
            uploadEnabled = true,
            bootstrapEnabled = true,
            bootstrapUrl = "https://example.com/cache.dat.gz",
        ))
        assertTrue(o.ok)
        val b = o.backup!!
        assertEquals("https://acme.r2.cloudflarestorage.com", b.r2Endpoint)
        assertEquals("cache", b.r2Bucket)
        assertEquals("bep51/k.dat.gz", b.r2ObjectKey)
        assertEquals("AKID", b.r2AccessKeyId)
        assertEquals("s3cr3t", b.r2SecretAccessKey)
        assertTrue(b.uploadEnabled)
        assertTrue(b.bootstrapEnabled)
        assertEquals("https://example.com/cache.dat.gz", b.bootstrapUrl)
    }

    @Test fun backupDefaultsDisabled() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        val b = o.backup!!
        assertFalse(b.uploadEnabled)
        assertFalse(b.bootstrapEnabled)
        assertEquals("bep51/bep51_cache.dat.gz", b.r2ObjectKey)
    }

    @Test fun backupUploadEnabledMissingFieldsFails() {
        val o = WizardSubmit.submit(validForm().copy(uploadEnabled = true))
        assertFalse(o.ok)
        assertNull(o.backup)
        assertTrue(o.errors.any { it.contains("R2", true) })
    }

    @Test fun bootstrapOnWithDbBackupOff_passesWithoutR2Credentials() {
        // Regression for the wizard arg-misalignment bug: bootstrapEnabled on with
        // dbBackupEnabled off and no R2 credentials must not trip DB-backup validation.
        val o = WizardSubmit.submit(validForm().copy(
            bootstrapEnabled = true,
            bootstrapUrl = "https://unjumble.au/bep51/bep51_cache.dat.gz",
        ))
        assertTrue(o.ok)
        val b = o.backup!!
        assertFalse(b.dbBackupEnabled)
        assertFalse(b.uploadEnabled)
        assertTrue(b.bootstrapEnabled)
    }

    @Test fun backupBootstrapHttpUrlFails() {
        val o = WizardSubmit.submit(validForm().copy(bootstrapEnabled = true, bootstrapUrl = "http://example.com/x"))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("bootstrap", true) })
    }

    @Test fun dbBackupFieldsMappedIntoOutcome() {
        val o = WizardSubmit.submit(validForm().copy(
            r2Endpoint = "https://acme.r2.cloudflarestorage.com",
            r2AccessKeyId = "AKID",
            r2SecretAccessKey = "s3cr3t",
            dbBackupEnabled = true,
            r2DbBucket = "my-dht-backup",
            r2DbObjectPrefix = "custom",
        ))
        assertTrue(o.ok)
        val b = o.backup!!
        assertTrue(b.dbBackupEnabled)
        assertEquals("my-dht-backup", b.r2DbBucket)
        assertEquals("custom", b.r2DbObjectPrefix)
    }

    @Test fun dbBackupDefaults() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        val b = o.backup!!
        assertFalse(b.dbBackupEnabled)
        assertEquals("dht-backup", b.r2DbBucket)
        assertEquals("db", b.r2DbObjectPrefix)
    }

    @Test fun dbBackupEnabledMissingFieldsFails() {
        val o = WizardSubmit.submit(validForm().copy(dbBackupEnabled = true))
        assertFalse(o.ok)
        assertNull(o.backup)
        assertTrue(o.errors.any { it.contains("R2", true) })
    }

    @Test fun dbBackupEnabledSlashPrefixFails() {
        val o = WizardSubmit.submit(validForm().copy(
            r2Endpoint = "https://acme.r2.cloudflarestorage.com",
            r2AccessKeyId = "AKID",
            r2SecretAccessKey = "s3cr3t",
            dbBackupEnabled = true,
            r2DbObjectPrefix = "a/b",
        ))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("prefix", true) })
    }

    @Test fun backupSettings_mapsAllBackupFields() {
        val f = validForm().copy(
            r2Endpoint = "https://acme.r2.cloudflarestorage.com",
            r2Bucket = "cache",
            r2ObjectKey = "bep51/other.dat.gz",
            r2AccessKeyId = "AKID",
            r2SecretAccessKey = "s3cr3t",
            uploadEnabled = true,
            dbBackupEnabled = true,
            r2DbBucket = "my-dht-backup",
            r2DbObjectPrefix = "custom",
            bootstrapEnabled = true,
            bootstrapUrl = "https://example.com/cache.dat.gz",
        )
        val b = WizardSubmit.backupSettings(f)
        assertEquals("https://acme.r2.cloudflarestorage.com", b.r2Endpoint)
        assertEquals("cache", b.r2Bucket)
        assertEquals("bep51/other.dat.gz", b.r2ObjectKey)
        assertEquals("AKID", b.r2AccessKeyId)
        assertEquals("s3cr3t", b.r2SecretAccessKey)
        assertTrue(b.uploadEnabled)
        assertTrue(b.dbBackupEnabled)
        assertEquals("my-dht-backup", b.r2DbBucket)
        assertEquals("custom", b.r2DbObjectPrefix)
        assertTrue(b.bootstrapEnabled)
        assertEquals("https://example.com/cache.dat.gz", b.bootstrapUrl)
    }

    @Test fun backupSettings_matchesSubmitOutcome() {
        val f = validForm().copy(
            r2Endpoint = "https://acme.r2.cloudflarestorage.com",
            r2AccessKeyId = "AKID",
            r2SecretAccessKey = "s3cr3t",
            dbBackupEnabled = true,
        )
        assertEquals(WizardSubmit.backupSettings(f), WizardSubmit.submit(f).backup)
    }

    @Test fun crawlerSaveCategoriesDefaultsToMediaSet() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        assertEquals(listOf("movie", "tv", "anime", "music"), o.crawlerSaveCategories)
    }

    @Test fun crawlerSaveCategoriesUnknownNameFailsValidation() {
        val o = WizardSubmit.submit(validForm().copy(
            crawlerSaveCategories = listOf("Anime", "movie", "nosuch"),
        ))
        assertFalse(o.ok)
        assertTrue(o.errors.any { it.contains("nosuch") })
        assertNull(o.transmission)
    }

    @Test fun crawlerSaveCategoriesNormalisedToCanonicalOrder() {
        val o = WizardSubmit.submit(validForm().copy(
            crawlerSaveCategories = listOf(" Music ", "anime"),
        ))
        assertTrue(o.ok)
        assertEquals(listOf("anime", "music"), o.crawlerSaveCategories)
    }

    @Test fun normalizeSaveCategories_dropsUnknownsAndReorders() {
        val (errors, selected) = WizardSubmit.normalizeSaveCategories(
            listOf(" TV", "music", "Nosuch", "Anime", "movie")
        )
        assertEquals(listOf("movie", "tv", "anime", "music"), selected)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("nosuch"))
    }

    @Test fun crawlerSaveCategoriesEmptyMeansSaveAll() {
        val o = WizardSubmit.submit(validForm().copy(crawlerSaveCategories = emptyList()))
        assertTrue(o.ok)
        assertTrue(o.crawlerSaveCategories.isEmpty())
    }

    @Test fun crawlerSaveCategoriesDoNotDisturbOtherOutcomeFields() {
        val o = WizardSubmit.submit(validForm())
        assertTrue(o.ok)
        assertTrue(o.errors.isEmpty())
        assertNotNull(o.transmission)
        assertNotNull(o.ftp)
        assertNotNull(o.backup)
        assertFalse(o.crawlerEnabled)
        assertEquals("tag", o.crawlerAdultMode)
    }
}
