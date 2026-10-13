package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.s3.S3PutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plain-JUnit tests for [DbBackupManager]: the gate order
 * (configured → busy → wifi → daily → DB file → dump → PUT), the
 * upload-first bookmark ordering (success advances the bookmark; every
 * PUT failure leaves it unchanged so the same range is retried), the
 * calendar-day gate (fake clock), the manual bypass (daily gate only),
 * and the single-flight refusal. [DbBackupManager.runCheck] is invoked
 * directly (synchronously) so the tests are deterministic; a no-op
 * [ScheduledExecutorService] stands in for the real scheduler.
 */
private fun testDumpOutcome(
    cursor: DbBookmark = DbBookmark(10, 2),
    objectKey: String = "db/incremental_20231114_0-10.sql.gz",
): DbDumpOutcome.Dump = DbDumpOutcome.Dump(
    sqlBytes = gzipBytes("INSERT OR IGNORE INTO torrents VALUES(1);".encodeToByteArray()),
    statementCount = 3,
    newCursor = cursor,
    objectKey = objectKey,
    newTorrentCount = 10,
)

class DbBackupManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Day 1: 2023-11-14 in UTC. */
    private val DAY1 = 1_700_000_000_000L

    private class FakeUploader : S3DbUploader {
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

    /** Scripted [DbDumper]; records the bookmark it was given. */
    private class FakeDumper(var outcome: DbDumpOutcome = testDumpOutcome()) : DbDumper {
        var calls = 0
        var lastBookmark: DbBookmark? = null

        override fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome {
            calls++
            lastBookmark = bookmark
            return outcome
        }
    }

    /** Captures submitted runnables without running them (post-shutdown: rejects). */
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
        dbBackupEnabled: Boolean = true,
        prefix: String = "db",
    ) = BackupSettings(
        r2Endpoint = "https://acct.r2.cloudflarestorage.com",
        r2Bucket = "cache",
        r2AccessKeyId = "keyid",
        r2SecretAccessKey = "secret",
        dbBackupEnabled = dbBackupEnabled,
        r2DbBucket = "dht_backup",
        r2DbObjectPrefix = prefix,
    )

    private fun newManager(
        dbFile: File,
        dumper: FakeDumper,
        uploader: FakeUploader,
        settings: BackupSettings = settings(),
        busy: Boolean = false,
        wifi: Boolean = true,
        clock: () -> Long = { DAY1 },
        executor: ScheduledExecutorService = NoopScheduledExecutorService(),
        bookmarkFile: File = File(tmp.root, "db_bookmark.txt"),
        lastSuccessDate: () -> String? = { null },
        writeLastSuccessDate: (String) -> Unit = {},
    ) = DbBackupManager(
        dbFile = { dbFile },
        dumperFactory = { dumper },
        loadSettings = { settings },
        isBusy = { busy },
        isWifi = { wifi },
        clientFactory = { uploader },
        bookmarkFile = { bookmarkFile },
        clock = clock,
        zone = ZoneOffset.UTC,
        executor = executor,
        readLastSuccessDate = lastSuccessDate,
        writeLastSuccessDate = writeLastSuccessDate,
    )

    private fun dbFile(): File = File(tmp.root, "torrents.db").apply { writeBytes(ByteArray(16)) }

    @Test fun busySkipsAndDoesNotDump() {
        val up = FakeUploader()
        val d = FakeDumper()
        val m = newManager(dbFile(), d, up, busy = true)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.SKIPPED_BUSY, st.state)
        assertEquals(0, d.calls)
        assertEquals(0, up.calls)
    }

    @Test fun noWifiSkipsAndDoesNotDump() {
        val up = FakeUploader()
        val d = FakeDumper()
        val m = newManager(dbFile(), d, up, wifi = false)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.SKIPPED_NO_WIFI, st.state)
        assertEquals(0, d.calls)
        assertEquals(0, up.calls)
    }

    @Test fun disabledDbBackupIsNotConfigured() {
        val up = FakeUploader()
        val d = FakeDumper()
        val m = newManager(dbFile(), d, up, settings(dbBackupEnabled = false))
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.NOT_CONFIGURED, st.state)
        assertTrue(!st.uploadEnabled)
        assertTrue(!st.configured)
        assertEquals(0, d.calls)
        assertEquals(0, up.calls)
    }

    @Test fun validatorErrorsAreNotConfigured() {
        val up = FakeUploader()
        val d = FakeDumper()
        val m = newManager(
            dbFile(), d, up,
            settings().copy(r2Endpoint = "", r2DbBucket = "", r2AccessKeyId = "", r2SecretAccessKey = ""))
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.NOT_CONFIGURED, st.state)
        assertTrue(st.message.contains("not configured"))
        assertTrue(!st.configured)
        assertEquals(0, d.calls)
        assertEquals(0, up.calls)
    }

    @Test fun missingDbSkips() {
        val up = FakeUploader()
        val d = FakeDumper()
        val m = newManager(File(tmp.root, "absent.db"), d, up)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.SKIPPED_NO_DB, st.state)
        assertEquals(0, d.calls)
        assertEquals(0, up.calls)
    }

    @Test fun emptyRangeSkipsWithoutUpload() {
        val up = FakeUploader()
        val d = FakeDumper(
            outcome = DbDumpOutcome.Skipped(maxTorrentId = 7, maxPrefixId = 2, bookmark = DbBookmark(7, 2)))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark(7, 2))
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.SKIPPED_EMPTY, st.state)
        assertEquals(1, d.calls)
        assertEquals(0, up.calls)
        // The bookmark is untouched on a skip.
        assertEquals(DbBookmark(7, 2), DbBookmark.read(bookmarkFile))
    }

    @Test fun dumpFailureFailsWithoutUpload() {
        val up = FakeUploader()
        // A dumper that throws (e.g. the DB is locked) must fail the check.
        val throwing = object : DbDumper {
            override fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome =
                throw IOException("database is locked")
        }
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val m = DbBackupManager(
            dbFile = { dbFile() },
            dumperFactory = { throwing },
            loadSettings = { settings() },
            isBusy = { false },
            isWifi = { true },
            clientFactory = { up },
            bookmarkFile = { bookmarkFile },
            clock = { DAY1 },
            zone = ZoneOffset.UTC,
        )
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("dump failed"))
        assertEquals(0, up.calls)
    }

    @Test fun successAdvancesBookmarkAfterPut() {
        val up = FakeUploader()
        val d = FakeDumper()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        var stored: String? = null
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertEquals(1, d.calls)
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        assertEquals(1, up.calls)
        assertEquals("db/incremental_20231114_0-10.sql.gz", up.lastKey)
        assertEquals("application/gzip", up.lastContentType)
        assertNotNull(up.lastBody)
        // The bookmark advanced to the dump's new cursor, and the daily
        // date was persisted.
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
        assertEquals("2023-11-14", stored)
        assertEquals(DAY1, st.lastSuccess)
        assertEquals(up.lastBody!!.size.toLong(), st.uploadedBytes)
        assertEquals(up.lastBody!!.size.toLong(), st.sqlBytes)
        assertEquals(3, st.statementCount)
        assertEquals("db/incremental_20231114_0-10.sql.gz", st.objectKey)
        assertTrue(st.uploadEnabled)
        assertTrue(st.configured)
    }

    @Test fun clientErrorKeepsBookmarkUnchanged() {
        val up = FakeUploader().apply { result = S3PutResult.ClientError(403, "Forbidden") }
        val d = FakeDumper()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("403"))
        assertNull(st.lastSuccess)
        // Critical ordering: the failed PUT must not advance the bookmark.
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(bookmarkFile))
    }

    @Test fun serverErrorKeepsBookmarkUnchanged() {
        val up = FakeUploader().apply {
            result = S3PutResult.ServerError(503, "unavailable", attempts = 3)
        }
        val d = FakeDumper()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("503"))
        assertEquals(DbBookmark(5, 1), DbBookmark.read(bookmarkFile))
    }

    @Test fun transportFailureKeepsBookmarkUnchanged() {
        val up = FakeUploader().apply {
            result = S3PutResult.Failure(IOException("connection reset"))
        }
        val d = FakeDumper()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("upload failed"))
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(bookmarkFile))
    }

    @Test fun failedUploadRetriesSameRangeNextRun() {
        val up = FakeUploader().apply { result = S3PutResult.Failure(IOException("no route to host")) }
        val d = FakeDumper()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
            lastSuccessDate = { "2023-11-13" },
        )
        val first = m.runCheck(manual = true)
        assertEquals(DbBackupState.FAILED, first.state)
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        // Next run: the SAME range is dumped again (bookmark never moved).
        up.result = S3PutResult.Success(200, "etag")
        val second = m.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, second.state)
        assertEquals(2, d.calls)
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
    }

    @Test fun dailyGateBlocksSecondCheckSameDayAndUploadsNextDay() {
        val up = FakeUploader()
        val d = FakeDumper()
        var stored: String? = null
        var now = DAY1
        val m = newManager(
            dbFile(), d, up,
            clock = { now },
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val first = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, first.state)
        assertEquals(1, up.calls)
        val second = m.runCheck(manual = false)
        assertEquals(1, up.calls)
        assertTrue(second.message.contains("already uploaded today"))
        now += 24L * 3600 * 1000
        val third = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, third.state)
        assertEquals(2, up.calls)
        assertEquals("2023-11-15", stored)
    }

    @Test fun manualTriggerBypassesDailyGate() {
        val up = FakeUploader()
        val d = FakeDumper()
        var stored: String? = null
        val m = newManager(
            dbFile(), d, up,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        m.runCheck(manual = false)
        assertEquals(1, up.calls)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertEquals(2, up.calls)
    }

    @Test fun manualTriggerStillHonorsBusyAndWifiGates() {
        val up = FakeUploader()
        val d = FakeDumper()
        val m = newManager(dbFile(), d, up, busy = true)
        assertEquals(DbBackupState.SKIPPED_BUSY, m.runCheck(manual = true).state)
        val m2 = newManager(dbFile(), d, up, wifi = false)
        assertEquals(DbBackupState.SKIPPED_NO_WIFI, m2.runCheck(manual = true).state)
        assertEquals(0, up.calls)
    }

    @Test fun uploadNowQueuesThenRunsCheck() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(dbFile(), FakeDumper(), up, executor = noop)
        val first = m.uploadNow(manual = true)
        // Acceptance no longer flips the state to RUNNING: it only carries a
        // "queued" message until the check writes a real outcome.
        assertEquals(DbBackupState.IDLE, first.state)
        assertEquals("db backup queued", first.message)
        assertEquals(1, noop.submitted.get())
        noop.last!!.run()
        assertEquals(DbBackupState.SUCCESS, m.status.state)
        assertEquals(1, up.calls)
    }

    @Test fun uploadNowRefusesWhileOneIsRunning() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(dbFile(), FakeDumper(), up, executor = noop)
        m.uploadNow(manual = true)
        val second = m.uploadNow(manual = true)
        assertTrue(second.message, second.message.contains("already running"))
        assertEquals(1, noop.submitted.get())
        assertEquals(0, up.calls)
    }

    @Test fun periodicTriggerWhileRunningKeepsStatusMessage() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(dbFile(), FakeDumper(), up, executor = noop)
        m.uploadNow(manual = true)
        assertEquals("db backup queued", m.status.message)
        // A periodic tick while the manual upload is in flight is refused,
        // but must not clobber the in-flight status message.
        val tick = m.uploadNow(manual = false)
        assertEquals(DbBackupState.IDLE, tick.state)
        assertEquals(DbBackupState.IDLE, m.status.state)
        assertEquals("db backup queued", m.status.message)
        assertEquals(1, noop.submitted.get())
        assertEquals(0, up.calls)
    }

    @Test fun stopRefusesFurtherUploads() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(dbFile(), FakeDumper(), up, executor = noop)
        m.stop()
        val st = m.uploadNow(manual = true)
        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("shutting down"))
        assertEquals(0, up.calls)
    }

    @Test fun dailyGateSkipUpdatesLastAttemptAndKeepsPriorState() {
        val up = FakeUploader()
        val d = FakeDumper()
        var stored: String? = null
        var now = DAY1
        val m = newManager(
            dbFile(), d, up,
            clock = { now },
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val first = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, first.state)
        assertEquals(DAY1, first.lastAttempt)
        now += 60_000
        val second = m.runCheck(manual = false)
        assertTrue(second.message.contains("already uploaded today"))
        assertEquals(DbBackupState.SUCCESS, second.state)
        assertEquals(DAY1 + 60_000, second.lastAttempt)
        assertEquals(1, up.calls)
        // The last successful upload's details survive a daily-gate skip.
        assertEquals("db/incremental_20231114_0-10.sql.gz", second.objectKey)
        assertEquals(3, second.statementCount)
    }
}
