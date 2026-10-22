package com.torrent.app.wizard

import com.torrent.app.config.AppConfig
import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigDefaults
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.FtpSettings
import com.torrent.app.config.TransmissionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's form state is hoisted into an activity-scoped ViewModel so rotation keeps
 * every value. These tests pin the seeding rules (which are exactly the old `remember`
 * initialisers) and the blank-secret fallback, without Android or a service.
 */
class WizardFormViewModelTest {

    // --- Seeding -------------------------------------------------------------------------

    @Test fun noConfig_usesTheSameFallbacksAsTheOldRememberInitialisers() {
        // The service binder is not connected yet on the first composition.
        val vm = WizardFormViewModel()

        assertEquals(ConfigDefaults.TRANSMISSION_USERNAME, vm.txUser.value)
        assertEquals("", vm.txPass.value)
        assertEquals(ConfigDefaults.TRANSMISSION_PEER_PORT.toString(), vm.peerPort.value)
        assertEquals("", vm.dlSubpath.value)
        assertEquals(ConfigDefaults.FTP_USERNAME, vm.ftpUser.value)
        assertEquals("", vm.ftpPass.value)
        assertEquals(ConfigDefaults.FTP_PORT.toString(), vm.ftpPort.value)
        assertFalse(vm.ftpWifiOnly.value)
        assertFalse(vm.crawlerEnabled.value)
        assertFalse(vm.crawlerAdultDrop.value)
        assertEquals(WizardDefaultSaveCategories.toSet(), vm.saveCategories.value)
        assertEquals("", vm.r2Endpoint.value)
        assertEquals("", vm.r2Bucket.value)
        assertEquals(ConfigDefaults.BACKUP_OBJECT_KEY, vm.r2ObjectKey.value)
        assertEquals("", vm.r2AccessKeyId.value)
        assertEquals("", vm.r2SecretAccessKey.value)
        assertFalse(vm.uploadEnabled.value)
        assertFalse(vm.dbBackupEnabled.value)
        assertEquals(ConfigDefaults.DB_BACKUP_BUCKET, vm.r2DbBucket.value)
        assertEquals(ConfigDefaults.DB_BACKUP_OBJECT_PREFIX, vm.r2DbObjectPrefix.value)
        assertFalse(vm.bootstrapEnabled.value)
        assertEquals("", vm.bootstrapUrl.value)
    }

    @Test fun initialConfig_seedsEveryFieldFromTheStore() {
        val vm = WizardFormViewModel(ConfigDefaults.INITIAL)

        assertEquals(ConfigDefaults.INITIAL.transmission.username, vm.txUser.value)
        assertEquals(ConfigDefaults.INITIAL.transmission.password, vm.txPass.value)
        assertEquals(ConfigDefaults.INITIAL.transmission.peerPort.toString(), vm.peerPort.value)
        assertEquals(ConfigDefaults.INITIAL.dataSubpath, vm.dlSubpath.value)
        assertEquals(ConfigDefaults.INITIAL.ftp.username, vm.ftpUser.value)
        assertEquals(ConfigDefaults.INITIAL.ftp.password, vm.ftpPass.value)
        assertEquals(ConfigDefaults.INITIAL.ftp.port.toString(), vm.ftpPort.value)
        assertEquals(ConfigDefaults.INITIAL.ftp.wifiOnly, vm.ftpWifiOnly.value)
        assertEquals(ConfigDefaults.INITIAL.crawler.enabled, vm.crawlerEnabled.value)
        assertFalse(vm.crawlerAdultDrop.value)
        assertEquals(ConfigDefaults.INITIAL.backup.r2ObjectKey, vm.r2ObjectKey.value)
        assertEquals(ConfigDefaults.INITIAL.backup.r2DbBucket, vm.r2DbBucket.value)
        assertEquals(ConfigDefaults.INITIAL.backup.r2DbObjectPrefix, vm.r2DbObjectPrefix.value)
        assertEquals(ConfigDefaults.INITIAL.backup.bootstrapUrl, vm.bootstrapUrl.value)
        // The secret is never seeded from the store — only what the user types is written.
        assertEquals("", vm.r2SecretAccessKey.value)
    }

    @Test fun storedValues_winOverTheFallbacks() {
        val cfg = ConfigDefaults.INITIAL.copy(
            transmission = TransmissionSettings(
                username = "wtest", password = "secret1", peerPort = 51999,
                speedLimitDown = 0, speedLimitUp = 0, altSpeedDown = 0, altSpeedUp = 0,
            ),
            ftp = FtpSettings(username = "ftest", password = "secret2", port = 2323, wifiOnly = true),
            crawler = CrawlerSettings(enabled = true, adultMode = "drop", saveCategories = listOf("anime")),
            backup = BackupSettings(
                r2Endpoint = "https://r2.example", r2Bucket = "bucket", r2ObjectKey = "obj",
                r2AccessKeyId = "AKIA", r2SecretAccessKey = "stored-secret", uploadEnabled = true,
                dbBackupEnabled = true, r2DbBucket = "db-bucket", r2DbObjectPrefix = "dbtest-1",
                bootstrapEnabled = true, bootstrapUrl = "https://example/cache.gz",
            ),
            dataSubpath = "media",
        )
        val vm = WizardFormViewModel(cfg)

        assertEquals("wtest", vm.txUser.value)
        assertEquals("secret1", vm.txPass.value)
        assertEquals("51999", vm.peerPort.value)
        assertEquals("media", vm.dlSubpath.value)
        assertEquals("ftest", vm.ftpUser.value)
        assertEquals("secret2", vm.ftpPass.value)
        assertEquals("2323", vm.ftpPort.value)
        assertTrue(vm.ftpWifiOnly.value)
        assertTrue(vm.crawlerEnabled.value)
        assertTrue(vm.crawlerAdultDrop.value)
        assertEquals(setOf("anime"), vm.saveCategories.value)
        assertEquals("https://r2.example", vm.r2Endpoint.value)
        assertEquals("bucket", vm.r2Bucket.value)
        assertEquals("obj", vm.r2ObjectKey.value)
        assertEquals("AKIA", vm.r2AccessKeyId.value)
        assertTrue(vm.uploadEnabled.value)
        assertTrue(vm.dbBackupEnabled.value)
        assertEquals("db-bucket", vm.r2DbBucket.value)
        assertEquals("dbtest-1", vm.r2DbObjectPrefix.value)
        assertTrue(vm.bootstrapEnabled.value)
        assertEquals("https://example/cache.gz", vm.bootstrapUrl.value)
    }

    @Test fun emptyStoredCategories_fallBackToTheMediaOnlyDefault() {
        // An empty save_categories means "save all"; the wizard shows (and writes) the
        // media-only fresh-install default instead.
        val cfg: AppConfig = ConfigDefaults.INITIAL.copy(crawler = CrawlerSettings(saveCategories = emptyList()))

        assertEquals(WizardDefaultSaveCategories.toSet(), WizardFormViewModel(cfg).saveCategories.value)
        assertEquals(setOf("movie", "tv", "anime", "music"), WizardDefaultSaveCategories.toSet())
    }

    // --- Mutation ------------------------------------------------------------------------

    @Test fun fieldWrites_goThroughTheSameState() {
        val vm = WizardFormViewModel()

        vm.txUser.value = "wtest"
        vm.peerPort.value = "51999"
        vm.crawlerAdultDrop.value = true
        vm.saveCategories.value = setOf("anime", "music")
        vm.r2DbObjectPrefix.value = "dbtest-1700000000"

        assertEquals("wtest", vm.txUser.value)
        assertEquals("51999", vm.peerPort.value)
        assertTrue(vm.crawlerAdultDrop.value)
        assertEquals(setOf("anime", "music"), vm.saveCategories.value)
        assertEquals("dbtest-1700000000", vm.r2DbObjectPrefix.value)
        // Untouched fields keep their seeded value.
        assertEquals(ConfigDefaults.FTP_USERNAME, vm.ftpUser.value)
    }

    @Test fun screen_startsOnTheUsbStep_andFollowsTheNavHelpers() {
        val vm = WizardFormViewModel()

        assertEquals(WizardStep.USB.kind, vm.screen.value)
        assertEquals("Step 1 of 5", WizardNav.stepIndicator(vm.screen.value))

        vm.screen.value = WizardNav.next(vm.screen.value)
        assertEquals(WizardStep.TRANSMISSION.kind, vm.screen.value)

        vm.screen.value = WizardNav.back(vm.screen.value)
        assertEquals(WizardStep.USB.kind, vm.screen.value)

        // Starting a download (stage 4) sets the progress screen directly.
        vm.screen.value = WizardScreenKind.Progress
        assertNull(vm.screen.value.stepOrNull)
        assertEquals("Step 5 of 5", WizardNav.stepIndicator(vm.screen.value))
    }

    // --- Blank-secret fallback ------------------------------------------------------------

    @Test fun effectiveSecret_blankInputKeepsTheStoredSecret() {
        assertEquals("stored", effectiveSecret("", "stored"))
        assertEquals("stored", effectiveSecret("   ", "stored"))
        assertEquals("typed", effectiveSecret("typed", "stored"))
        // Nothing stored and nothing typed stays empty (validateDbRestore then reports it).
        assertEquals("", effectiveSecret("", ""))
    }

    // --- Per-step errors ------------------------------------------------------------------

    @Test fun errors_areTrackedPerStep() {
        val vm = WizardFormViewModel()

        assertTrue(vm.errorsFor(WizardStep.TRANSMISSION).isEmpty())

        vm.setErrors(WizardStep.TRANSMISSION, listOf("password too short", "port out of range"))
        vm.setErrors(WizardStep.FTP, listOf("bad port"))

        assertEquals(listOf("password too short", "port out of range"), vm.errorsFor(WizardStep.TRANSMISSION))
        assertEquals(listOf("bad port"), vm.errorsFor(WizardStep.FTP))
        assertTrue(vm.errorsFor(WizardStep.USB).isEmpty())
        assertFalse(WizardNav.canProceed(WizardStep.TRANSMISSION, usbGranted = true, vm.errorsFor(WizardStep.TRANSMISSION)))
        assertTrue(WizardNav.canProceed(WizardStep.USB, usbGranted = true, vm.errorsFor(WizardStep.USB)))
    }

    @Test fun setErrors_withAnEmptyList_clearsOnlyThatStep() {
        val vm = WizardFormViewModel()
        vm.setErrors(WizardStep.TRANSMISSION, listOf("a"))
        vm.setErrors(WizardStep.FTP, listOf("b"))

        vm.setErrors(WizardStep.TRANSMISSION, emptyList())

        assertTrue(vm.errorsFor(WizardStep.TRANSMISSION).isEmpty())
        assertEquals(listOf("b"), vm.errorsFor(WizardStep.FTP))
    }

    @Test fun clearErrors_dropsEveryStep() {
        val vm = WizardFormViewModel()
        vm.setErrors(WizardStep.TRANSMISSION, listOf("a"))
        vm.setErrors(WizardStep.DB_DOWNLOAD, listOf("b"))

        vm.clearErrors()

        assertTrue(vm.errors.value.isEmpty())
        assertTrue(vm.errorsFor(WizardStep.TRANSMISSION).isEmpty())
        assertTrue(vm.errorsFor(WizardStep.DB_DOWNLOAD).isEmpty())
    }
}
