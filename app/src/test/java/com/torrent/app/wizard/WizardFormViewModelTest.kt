package com.torrent.app.wizard

import com.torrent.app.config.AppConfig
import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigDefaults
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.FtpSettings
import com.torrent.app.config.TransmissionSettings
import com.torrent.app.config.WizardDraft
import com.torrent.app.config.WizardDraftStore
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

    // --- Late seeding: the binder connects after the wizard is already on screen ----------

    @Test fun lateSeed_fillsUntouchedFieldsFromTheStore() {
        // The activity now reads `wizardCompleted` synchronously, so a first-run wizard is
        // composed before the service binder delivers a config: the form starts from the
        // fallbacks and is seeded once the store arrives.
        val vm = WizardFormViewModel()

        assertTrue(vm.seedIfNeeded(lateSeedConfig))

        assertEquals("wtest", vm.txUser.value)
        assertEquals("secret1", vm.txPass.value)
        assertEquals("51999", vm.peerPort.value)
        assertEquals("media", vm.dlSubpath.value)
        assertEquals("ftest", vm.ftpUser.value)
        assertTrue(vm.ftpWifiOnly.value)
        assertTrue(vm.crawlerEnabled.value)
        assertTrue(vm.crawlerAdultDrop.value)
        assertEquals(setOf("anime"), vm.saveCategories.value)
        assertEquals("db-bucket", vm.r2DbBucket.value)
        assertEquals("dbtest-1", vm.r2DbObjectPrefix.value)
        // Never seeded, early or late: only a secret the user typed is ever written.
        assertEquals("", vm.r2SecretAccessKey.value)
    }

    @Test fun lateSeed_keepsWhatTheUserAlreadyTyped() {
        val vm = WizardFormViewModel()
        vm.txUser.value = "typed-user"
        vm.peerPort.value = "51000"

        vm.seedIfNeeded(lateSeedConfig)

        // Fields the user moved off their fallback are left exactly as typed.
        assertEquals("typed-user", vm.txUser.value)
        assertEquals("51000", vm.peerPort.value)
        // Everything untouched is still seeded from the store.
        assertEquals("secret1", vm.txPass.value)
        assertEquals("ftest", vm.ftpUser.value)
        assertEquals(setOf("anime"), vm.saveCategories.value)
    }

    @Test fun lateSeed_happensOnlyOnce() {
        val vm = WizardFormViewModel()

        assertTrue(vm.seedIfNeeded(lateSeedConfig))
        assertFalse(vm.seedIfNeeded(ConfigDefaults.INITIAL))

        assertEquals("wtest", vm.txUser.value)
    }

    @Test fun lateSeed_waitsForAConfigAndThenStillSeeds() {
        val vm = WizardFormViewModel()

        assertFalse(vm.seedIfNeeded(null))
        assertEquals(ConfigDefaults.TRANSMISSION_USERNAME, vm.txUser.value)

        assertTrue(vm.seedIfNeeded(lateSeedConfig))
        assertEquals("wtest", vm.txUser.value)
    }

    @Test fun constructorSeed_wins_andALateSeedIsANoOp() {
        val vm = WizardFormViewModel(lateSeedConfig)

        assertFalse(vm.seedIfNeeded(ConfigDefaults.INITIAL))

        assertEquals("wtest", vm.txUser.value)
    }

    // --- Draft persistence: leaving the app mid-setup must not restart the wizard ---------

    @Test fun draft_restoresEveryFieldAndTheStepItWasShowing() {
        // The reported bug: switching to another app to copy the R2 credentials restarted the
        // wizard on step 1 with an empty form, because the form lived only in the ViewModel and
        // the activity/process was reclaimed while it was stopped.
        val vm = WizardFormViewModel(draftStore = FakeDraftStore(draftWithEveryField))

        assertEquals(WizardStep.DB_DOWNLOAD.kind, vm.screen.value)
        assertEquals("draft-tx", vm.txUser.value)
        assertEquals("draft-txpass", vm.txPass.value)
        assertEquals("51000", vm.peerPort.value)
        assertEquals("media", vm.dlSubpath.value)
        assertEquals("draft-ftp", vm.ftpUser.value)
        assertEquals("draft-ftppass", vm.ftpPass.value)
        assertEquals("2323", vm.ftpPort.value)
        assertTrue(vm.ftpWifiOnly.value)
        assertTrue(vm.crawlerEnabled.value)
        assertTrue(vm.crawlerAdultDrop.value)
        assertEquals(setOf("anime"), vm.saveCategories.value)
        assertEquals("https://r2.draft", vm.r2Endpoint.value)
        assertEquals("draft-bucket", vm.r2Bucket.value)
        assertEquals("draft-obj", vm.r2ObjectKey.value)
        assertEquals("DRAFTKEY", vm.r2AccessKeyId.value)
        // The pasted-but-not-yet-committed secret is the whole point of the draft.
        assertEquals("draft-secret", vm.r2SecretAccessKey.value)
        assertTrue(vm.uploadEnabled.value)
        assertTrue(vm.dbBackupEnabled.value)
        assertEquals("draft-db-bucket", vm.r2DbBucket.value)
        assertEquals("draft-dbtest", vm.r2DbObjectPrefix.value)
        assertTrue(vm.bootstrapEnabled.value)
        assertEquals("https://draft/cache.gz", vm.bootstrapUrl.value)
    }

    @Test fun draft_winsOverTheStoredConfig() {
        // The draft is a complete snapshot of a form the user was already editing: re-seeding it
        // from the store would undo what they typed.
        val vm = WizardFormViewModel(lateSeedConfig, FakeDraftStore(draftWithEveryField))

        assertEquals("draft-tx", vm.txUser.value)
        assertEquals("draft-secret", vm.r2SecretAccessKey.value)
        assertEquals(WizardStep.DB_DOWNLOAD.kind, vm.screen.value)
    }

    @Test fun restoredDraft_marksTheFormSeeded_soALateSeedCannotClobberIt() {
        val vm = WizardFormViewModel(draftStore = FakeDraftStore(draftWithEveryField))

        assertFalse(vm.seedIfNeeded(lateSeedConfig))

        assertEquals("draft-tx", vm.txUser.value)
        assertEquals("draft-txpass", vm.txPass.value)
    }

    @Test fun draft_isTheWholeForm_andRoundTripsThroughTheStore() {
        val store = FakeDraftStore()
        val first = WizardFormViewModel(draftStore = store)
        first.txUser.value = "typed-user"
        first.r2SecretAccessKey.value = "typed-secret"
        first.saveCategories.value = setOf("anime", "music")
        first.screen.value = WizardNav.next(WizardStep.TRANSMISSION.kind)

        first.persistDraft()

        // A new ViewModel over the same store is exactly what an activity/process recreation
        // produces.
        val resumed = WizardFormViewModel(draftStore = store)
        assertEquals(first.draft(), resumed.draft())
        assertEquals(WizardStep.FTP.kind, resumed.screen.value)
        assertEquals("typed-user", resumed.txUser.value)
        assertEquals("typed-secret", resumed.r2SecretAccessKey.value)
        assertEquals(setOf("anime", "music"), resumed.saveCategories.value)
    }

    @Test fun draft_keepsTheRestoreProgressScreen() {
        val store = FakeDraftStore()
        val vm = WizardFormViewModel(draftStore = store)
        vm.screen.value = WizardScreenKind.Progress

        vm.persistDraft()

        assertEquals(WizardScreenKind.Progress, WizardFormViewModel(draftStore = store).screen.value)
    }

    @Test fun clearDraft_forgetsTheStoredForm() {
        val store = FakeDraftStore(draftWithEveryField)
        val vm = WizardFormViewModel(draftStore = store)

        vm.clearDraft()

        assertNull(store.loadWizardDraft())
        // A later launch starts a fresh wizard instead of resuming a finished one.
        assertEquals(WizardStep.USB.kind, WizardFormViewModel(draftStore = store).screen.value)
    }

    /** A store that keeps the draft in memory; the real one is [ConfigStore]'s prefs file. */
    private class FakeDraftStore(private var draft: WizardDraft? = null) : WizardDraftStore {
        override fun loadWizardDraft(): WizardDraft? = draft
        override fun saveWizardDraft(draft: WizardDraft) { this.draft = draft }
        override fun clearWizardDraft() { draft = null }
    }

    /** A draft that differs from every fallback and from [lateSeedConfig]. */
    private val draftWithEveryField = WizardDraft(
        screenIndex = WizardNav.screenIndex(WizardStep.DB_DOWNLOAD.kind),
        txUser = "draft-tx",
        txPass = "draft-txpass",
        peerPort = "51000",
        dlSubpath = "media",
        ftpUser = "draft-ftp",
        ftpPass = "draft-ftppass",
        ftpPort = "2323",
        ftpWifiOnly = true,
        crawlerEnabled = true,
        crawlerAdultDrop = true,
        saveCategories = setOf("anime"),
        r2Endpoint = "https://r2.draft",
        r2Bucket = "draft-bucket",
        r2ObjectKey = "draft-obj",
        r2AccessKeyId = "DRAFTKEY",
        r2SecretAccessKey = "draft-secret",
        uploadEnabled = true,
        dbBackupEnabled = true,
        r2DbBucket = "draft-db-bucket",
        r2DbObjectPrefix = "draft-dbtest",
        bootstrapEnabled = true,
        bootstrapUrl = "https://draft/cache.gz",
    )

    /** A store whose every section differs from the fallbacks (used by the late-seed tests). */
    private val lateSeedConfig: AppConfig = ConfigDefaults.INITIAL.copy(
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
}
