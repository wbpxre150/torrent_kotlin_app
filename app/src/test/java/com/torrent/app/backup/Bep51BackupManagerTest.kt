package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.s3.S3PutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * Plain-JUnit tests for [Bep51BackupManager]: the gate order
 * (configured → busy → wifi → daily → file → validation), the gzip PUT, the
 * calendar-day gate (fake clock), the manual bypass, and the single-flight
 * refusal. [Bep51BackupManager.runCheck] is invoked directly (synchronously)
 * so the tests are deterministic; a no-op [ScheduledExecutorService] stands
 * in for the real scheduler.
 */
class Bep51BackupManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Day 1: 2023-11-14 in UTC. */
    private val DAY1 = 1_700_000_000_000L

    /** Fakes [S3Uploader]; records every PUT. */
    private class FakeUploader : S3Uploader {
        var result: S3PutResult = S3PutResult.Success(200, "etag")
        var calls = 0
        var lastKey: String? = null
        var lastContentType: String? = null
        var lastBody: ByteArray? = null

        override fun put(objectKey: String, contentType: String, body: ByteArray): S3PutResult {
            calls++
            lastKey = objectKey
            lastContentType = contentType
            lastBody = body
            return result
        }
    }

    /**
     * Captures submitted runnables without running them (post-shutdown:
     * rejects). Everything else delegates to a never-used real executor.
     */
    private class NoopScheduledExecutorService :
        ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor(ThreadFactory {
            Thread(it, "noop-sched-test").apply { isDaemon = true }
        }) {
        val submitted = AtomicInteger(0)
        @Volatile var last: Runnable? = null
        @Volatile private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("shut down")
            submitted.incrementAndGet()
            last = command
        }

        override fun shutdownNow(): List<Runnable> {
            shutdown = true
            return emptyList()
        }
    }

    private fun settings(
        uploadEnabled: Boolean = true,
        objectKey: String = "bep51/bep51_cache.dat.gz",
    ) = BackupSettings(
        r2Endpoint = "https://acct.r2.cloudflarestorage.com",
        r2Bucket = "bucket",
        r2ObjectKey = objectKey,
        r2AccessKeyId = "keyid",
        r2SecretAccessKey = "secret",
        uploadEnabled = uploadEnabled,
    )

    private fun newManager(
        file: File,
        uploader: FakeUploader,
        settings: BackupSettings = settings(),
        busy: Boolean = false,
        wifi: Boolean = true,
        clock: () -> Long = { DAY1 },
        executor: ScheduledExecutorService = NoopScheduledExecutorService(),
        lastSuccessDate: () -> String? = { null },
        writeLastSuccessDate: (String) -> Unit = {},
    ) = Bep51BackupManager(
        cacheFile = { file },
        loadSettings = { settings },
        isBusy = { busy },
        isWifi = { wifi },
        clientFactory = { uploader },
        clock = clock,
        zone = ZoneOffset.UTC,
        executor = executor,
        readLastSuccessDate = lastSuccessDate,
        writeLastSuccessDate = writeLastSuccessDate,
    )

    private fun validCacheFile(n: Int = 4): File {
        val file = File(tmp.root, "bep51_cache.dat")
        file.writeBytes(buildBep51Cache(nodeCount = n, withTrailer = true))
        return file
    }

    @Test fun busySkipsAndDoesNotUpload() {
        val up = FakeUploader()
        val m = newManager(validCacheFile(), up, busy = true)
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.SKIPPED_BUSY, st.state)
        assertEquals(0, up.calls)
    }

    @Test fun noWifiSkipsAndDoesNotUpload() {
        val up = FakeUploader()
        val m = newManager(validCacheFile(), up, wifi = false)
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.SKIPPED_NO_WIFI, st.state)
        assertEquals(0, up.calls)
    }

    @Test fun disabledUploadIsNotConfigured() {
        val up = FakeUploader()
        val m = newManager(validCacheFile(), up, settings(uploadEnabled = false))
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.NOT_CONFIGURED, st.state)
        assertTrue(!st.uploadEnabled)
        assertTrue(!st.configured)
        assertEquals(0, up.calls)
    }

    @Test fun validatorErrorsAreNotConfigured() {
        val up = FakeUploader()
        val m = newManager(
            validCacheFile(), up,
            settings().copy(r2Endpoint = "", r2Bucket = "", r2AccessKeyId = "", r2SecretAccessKey = ""))
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.NOT_CONFIGURED, st.state)
        assertTrue(st.message.contains("not configured"))
        assertTrue(!st.configured)
        assertEquals(0, up.calls)
    }

    @Test fun missingFileSkips() {
        val up = FakeUploader()
        val m = newManager(File(tmp.root, "absent.dat"), up)
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.SKIPPED_NO_FILE, st.state)
        assertEquals(0, up.calls)
    }

    @Test fun invalidFileFailsWithoutUpload() {
        val file = File(tmp.root, "bep51_cache.dat")
        file.writeBytes(ByteArray(64) { it.toByte() })
        val up = FakeUploader()
        val m = newManager(file, up)
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.FAILED, st.state)
        assertTrue(st.message.startsWith("invalid cache"))
        assertEquals(0, up.calls)
    }

    @Test fun emptyCacheSkips() {
        val file = File(tmp.root, "bep51_cache.dat")
        file.writeBytes(buildBep51Cache(nodeCount = 0))
        val up = FakeUploader()
        val m = newManager(file, up)
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.SKIPPED_EMPTY, st.state)
        assertEquals(0, up.calls)
    }

    @Test fun successUploadsGzipToConfiguredKey() {
        val original = buildBep51Cache(nodeCount = 4, withTrailer = true)
        val file = File(tmp.root, "bep51_cache.dat")
        file.writeBytes(original)
        val up = FakeUploader()
        var stored: String? = null
        val m = newManager(
            file, up,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.SUCCESS, st.state)
        assertEquals(1, up.calls)
        assertEquals("bep51/bep51_cache.dat.gz", up.lastKey)
        assertEquals("application/gzip", up.lastContentType)
        val restored = GZIPInputStream(ByteArrayInputStream(up.lastBody!!)).use { it.readBytes() }
        assertArrayEquals(original, restored)
        assertEquals(DAY1, st.lastSuccess)
        assertEquals(up.lastBody!!.size.toLong(), st.uploadedBytes)
        assertEquals("2023-11-14", stored)
        assertTrue(st.uploadEnabled)
        assertTrue(st.configured)
    }

    @Test fun dailyGateBlocksSecondCheckSameDayAndUploadsNextDay() {
        val up = FakeUploader()
        var stored: String? = null
        var now = DAY1
        val m = newManager(
            validCacheFile(), up,
            clock = { now },
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val first = m.runCheck(manual = false)
        assertEquals(BackupState.SUCCESS, first.state)
        assertEquals(1, up.calls)
        val second = m.runCheck(manual = false)
        assertEquals(1, up.calls)
        assertTrue(second.message.contains("already uploaded today"))
        now += 24L * 3600 * 1000
        val third = m.runCheck(manual = false)
        assertEquals(BackupState.SUCCESS, third.state)
        assertEquals(2, up.calls)
        assertEquals("2023-11-15", stored)
    }

    @Test fun manualTriggerBypassesDailyGate() {
        val up = FakeUploader()
        var stored: String? = null
        val m = newManager(
            validCacheFile(), up,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        m.runCheck(manual = false)
        assertEquals(1, up.calls)
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.SUCCESS, st.state)
        assertEquals(2, up.calls)
    }

    @Test fun manualTriggerStillHonorsBusyAndWifiGates() {
        val up = FakeUploader()
        val m = newManager(validCacheFile(), up, busy = true)
        assertEquals(BackupState.SKIPPED_BUSY, m.runCheck(manual = true).state)
        val m2 = newManager(validCacheFile(), up, wifi = false)
        assertEquals(BackupState.SKIPPED_NO_WIFI, m2.runCheck(manual = true).state)
        assertEquals(0, up.calls)
    }

    @Test fun clientErrorFailsWithStatusCode() {
        val up = FakeUploader().apply { result = S3PutResult.ClientError(403, "Forbidden") }
        val m = newManager(validCacheFile(), up)
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("403"))
        assertNull(st.lastSuccess)
    }

    @Test fun serverErrorFailsWithStatusCode() {
        val up = FakeUploader().apply {
            result = S3PutResult.ServerError(503, "unavailable", attempts = 3)
        }
        val m = newManager(validCacheFile(), up)
        val st = m.runCheck(manual = true)
        assertEquals(BackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("503"))
    }

    @Test fun uploadNowQueuesThenRunsCheck() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(validCacheFile(), up, executor = noop)
        val first = m.uploadNow(manual = true)
        assertEquals(BackupState.RUNNING, first.state)
        assertEquals(1, noop.submitted.get())
        noop.last!!.run()
        assertEquals(BackupState.SUCCESS, m.status.state)
        assertEquals(1, up.calls)
    }

    @Test fun uploadNowRefusesWhileOneIsRunning() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(validCacheFile(), up, executor = noop)
        m.uploadNow(manual = true)
        val second = m.uploadNow(manual = true)
        assertTrue(second.message, second.message.contains("already running"))
        assertEquals(1, noop.submitted.get())
        assertEquals(0, up.calls)
    }

    @Test fun stopRefusesFurtherUploads() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(validCacheFile(), up, executor = noop)
        m.stop()
        val st = m.uploadNow(manual = true)
        assertEquals(BackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("shutting down"))
        assertEquals(0, up.calls)
    }

    @Test fun dailyGateSkipUpdatesLastAttemptAndKeepsPriorState() {
        val up = FakeUploader()
        var stored: String? = null
        var now = DAY1
        val m = newManager(
            validCacheFile(), up,
            clock = { now },
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val first = m.runCheck(manual = false)
        assertEquals(BackupState.SUCCESS, first.state)
        assertEquals(DAY1, first.lastAttempt)
        now += 60_000
        val second = m.runCheck(manual = false)
        assertTrue(second.message.contains("already uploaded today"))
        assertEquals(BackupState.SUCCESS, second.state)
        assertEquals(DAY1 + 60_000, second.lastAttempt)
        assertEquals(1, up.calls)
    }

    @Test fun periodicTriggerWhileRunningKeepsStatusMessage() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(validCacheFile(), up, executor = noop)
        m.uploadNow(manual = true)
        assertEquals(BackupState.RUNNING, m.status.state)
        // A periodic tick while the manual upload is in flight is refused,
        // but must not clobber the in-flight status message.
        val tick = m.uploadNow(manual = false)
        assertEquals(BackupState.RUNNING, tick.state)
        assertEquals(BackupState.RUNNING, m.status.state)
        assertEquals("upload queued", m.status.message)
        assertEquals(1, noop.submitted.get())
        assertEquals(0, up.calls)
    }
}
