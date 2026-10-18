package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.s3.S3PutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        /** Optional per-call results (drain tests): consumed before [result]. */
        val results = mutableListOf<S3PutResult>()
        var calls = 0
        var lastKey: String? = null
        var lastContentType: String? = null
        var lastBody: ByteArray? = null

        override fun put(objectKey: String, contentType: String, body: ByteArray): S3PutResult {
            calls++
            lastKey = objectKey
            lastContentType = contentType
            lastBody = body
            return if (results.isNotEmpty()) results.removeAt(0) else result
        }
    }

    /** Scripted [DbDumper]; records the bookmark it was given. */
    private class FakeDumper(
        var outcome: DbDumpOutcome = testDumpOutcome(),
        /**
         * Optional scripted outcomes (the bounded-drain tests): one per dump
         * call, consumed in order. An exhausted queue falls back to [outcome].
         */
        private val outcomes: MutableList<DbDumpOutcome> = mutableListOf(),
    ) : DbDumper {
        var calls = 0
        var lastBookmark: DbBookmark? = null
        /** Every cursor a dump was asked for, in call order. */
        val bookmarks = mutableListOf<DbBookmark>()

        override fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome {
            calls++
            lastBookmark = bookmark
            bookmarks += bookmark
            return if (outcomes.isNotEmpty()) outcomes.removeAt(0) else outcome
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
        r2DbBucket = "dht-backup",
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

    // --- refreshConfigStatus (apply-path snapshot refresh) ---

    @Test fun refreshConfigStatus_updatesStaleSnapshotAfterApply() {
        val up = FakeUploader()
        val d = FakeDumper()
        var s = settings(dbBackupEnabled = false)
        val m = DbBackupManager(
            dbFile = { dbFile() },
            dumperFactory = { d },
            loadSettings = { s },
            isBusy = { false },
            isWifi = { true },
            clientFactory = { up },
            bookmarkFile = { File(tmp.root, "db_bookmark.txt") },
            clock = { DAY1 },
            zone = ZoneOffset.UTC,
            executor = NoopScheduledExecutorService(),
        )
        m.runCheck(manual = true)
        assertEquals(DbBackupState.NOT_CONFIGURED, m.status.state)
        assertTrue(!m.status.uploadEnabled)
        // The user ticks "Enable daily DB dump upload" and applies: the
        // manager's status snapshot must refresh immediately, not at the
        // next periodic tick.
        s = settings(dbBackupEnabled = true)
        m.refreshConfigStatus()
        assertTrue(m.status.uploadEnabled)
        assertTrue(m.status.configured)
        assertEquals(DbBackupState.IDLE, m.status.state)
        assertEquals(0, up.calls)
    }

    @Test fun refreshConfigStatus_invalidSettingsStayNotConfigured() {
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(),
            settings().copy(r2Endpoint = ""))
        m.refreshConfigStatus()
        assertEquals(DbBackupState.NOT_CONFIGURED, m.status.state)
        assertTrue(m.status.uploadEnabled)
        assertTrue(!m.status.configured)
        assertTrue(m.status.message.contains("not configured"))
    }

    @Test fun refreshConfigStatus_preservesRealOutcomeState() {
        val up = FakeUploader()
        val d = FakeDumper()
        val bookmarkFile = File(tmp.root, "outcome_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        // A completed check (SUCCESS here) must not be clobbered by a later
        // config refresh.
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        m.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, m.status.state)
        m.refreshConfigStatus()
        assertEquals(DbBackupState.SUCCESS, m.status.state)
        assertTrue(m.status.uploadEnabled)
        assertTrue(m.status.configured)
        assertEquals(1, up.calls)
    }

    @Test fun refreshConfigStatus_disabledMarksNotConfigured() {
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(),
            settings(dbBackupEnabled = false))
        m.refreshConfigStatus()
        assertEquals(DbBackupState.NOT_CONFIGURED, m.status.state)
        assertTrue(!m.status.uploadEnabled)
        assertTrue(!m.status.configured)
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
        // The check takes the dump path (the throwing dumper) whether or not a
        // bookmark file exists; one is written to model the steady state.
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
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
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
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
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
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
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
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
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
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
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        val m = newManager(
            dbFile(), d, up,
            clock = { now },
            bookmarkFile = bookmarkFile,
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
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
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
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        val m = newManager(dbFile(), FakeDumper(), up, bookmarkFile = bookmarkFile, executor = noop)
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
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        val m = newManager(
            dbFile(), d, up,
            clock = { now },
            bookmarkFile = bookmarkFile,
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

    @Test fun inFlightTrueFromAcceptanceUntilCheckCompletes() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        // The check takes the dump path (where [duringDump] is sampled); a
        // bookmark file is optional, one is written to model the steady state.
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        lateinit var m: DbBackupManager
        var duringDump = false
        val d = object : DbDumper {
            override fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome {
                duringDump = m.inFlight
                return testDumpOutcome()
            }
        }
        m = DbBackupManager(
            dbFile = { dbFile() },
            dumperFactory = { d },
            loadSettings = { settings() },
            isBusy = { false },
            isWifi = { true },
            clientFactory = { up },
            bookmarkFile = { bookmarkFile },
            clock = { DAY1 },
            zone = ZoneOffset.UTC,
            executor = noop,
        )
        // No check accepted yet.
        assertFalse(m.inFlight)
        m.uploadNow(manual = true)
        // Accepted and queued: in flight, while the state stays IDLE
        // (the deliberate no-RUNNING-state design).
        assertTrue(m.inFlight)
        assertEquals(DbBackupState.IDLE, m.status.state)
        noop.last!!.run()
        // In flight while the check ran; cleared once it completed.
        assertTrue(duringDump)
        assertFalse(m.inFlight)
    }

    @Test fun missingBookmarkDrainsFromDatabaseStart() {
        val up = FakeUploader()
        val d = FakeDumper(
            outcome = testDumpOutcome(
                cursor = DbBookmark(10, 2),
                objectKey = "db/incremental_20231114_0-10.sql.gz"))
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
        // First-ever check with no bookmark file: the existing history is
        // drained from the very beginning of the DB, not skipped by seeding a
        // baseline at the current max ids.
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        assertEquals(1, d.calls)
        // The chunk was uploaded and only then followed by the bookmark write.
        assertEquals(1, up.calls)
        assertEquals("db/incremental_20231114_0-10.sql.gz", up.lastKey)
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
        assertNotNull(st.lastSuccess)
        assertNotNull(st.lastAttempt)
        // The backlog was drained by this check (the chunk was not capped), so
        // the daily date is persisted as usual.
        assertEquals("2023-11-14", stored)
    }

    @Test fun missingBookmarkThenNextRunUsesIncrementalPath() {
        val up = FakeUploader()
        val d = FakeDumper(
            outcome = testDumpOutcome(
                cursor = DbBookmark(101, 6),
                objectKey = "db/incremental_20231114_101-101.sql.gz"))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        // First run: no bookmark -> drains from the start of the DB and
        // uploads (no baseline seed that uploads nothing).
        val first = m.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, first.state)
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        assertEquals(1, up.calls)
        assertEquals(DbBookmark(101, 6), DbBookmark.read(bookmarkFile))
        // Second run: continues from the cursor the first run persisted.
        val second = m.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, second.state)
        assertEquals(2, d.calls)
        assertEquals(DbBookmark(101, 6), d.lastBookmark)
        assertEquals(2, up.calls)
    }

    @Test fun missingBookmarkEmptyDbSkipsAndWritesNoBookmark() {
        val up = FakeUploader()
        // Empty tables -> nothing to dump -> the dumper reports Skipped.
        val d = FakeDumper(outcome = DbDumpOutcome.Skipped(
            maxTorrentId = 0, maxPrefixId = 0, bookmark = DbBookmark.FIRST_RUN))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.SKIPPED_EMPTY, st.state)
        // The drain asked for the very start of the DB.
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        assertEquals(0, up.calls)
        // Nothing was persisted: the bookmark file stays absent (no FIRST_RUN
        // sentinel is written), so the next check retries from the start.
        assertFalse(bookmarkFile.exists())
        // Once rows exist, the next check dumps them from FIRST_RUN.
        d.outcome = testDumpOutcome()
        val second = m.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, second.state)
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        assertEquals(1, up.calls)
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
    }

    @Test fun missingBookmarkCheckResumesFromPersistedCursorAfterRestart() {
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        // First manager: no bookmark -> drains from the start of the DB and
        // persists the uploaded chunk's cursor.
        val up1 = FakeUploader()
        val d1 = FakeDumper(outcome = testDumpOutcome(cursor = DbBookmark(10, 2)))
        newManager(dbFile(), d1, up1, bookmarkFile = bookmarkFile).runCheck(manual = true)
        assertEquals(DbBookmark.FIRST_RUN, d1.lastBookmark)
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
        // "Process restart": a fresh manager instance on the same bookmark path
        // resumes from the cursor on disk instead of re-dumping from the start.
        val up2 = FakeUploader()
        val d2 = FakeDumper(outcome = testDumpOutcome(
            cursor = DbBookmark(20, 3),
            objectKey = "db/incremental_20231114_11-20.sql.gz"))
        val restarted = newManager(dbFile(), d2, up2, bookmarkFile = bookmarkFile)
        val st = restarted.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertEquals(DbBookmark(10, 2), d2.lastBookmark)
        assertEquals(1, up2.calls)
        assertEquals(DbBookmark(20, 3), DbBookmark.read(bookmarkFile))
    }
    @Test fun existingBookmarkDoesNotSeed() {
        val up = FakeUploader()
        // A valid (FIRST_RUN) bookmark file exists -> the normal dump path.
        val d = FakeDumper()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        val st = m.runCheck(manual = true)
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertEquals(1, d.calls)
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
    }

    // --- bounded chunk drain (Stage 2) ---

    /**
     * One scripted drain chunk: [capped] mirrors
     * `DbDumpOutcome.Dump.capped` (`true` = more rows exist beyond [cursor]).
     */
    private fun chunkDump(
        cursor: DbBookmark,
        objectKey: String,
        capped: Boolean,
        statements: Int = 3,
    ): DbDumpOutcome.Dump = DbDumpOutcome.Dump(
        sqlBytes = gzipBytes(
            "INSERT OR IGNORE INTO torrents VALUES(${cursor.lastTorrentId});".encodeToByteArray()),
        statementCount = statements,
        newCursor = cursor,
        objectKey = objectKey,
        newTorrentCount = 10,
        capped = capped,
    )

    @Test fun cappedSuccessDoesNotWriteDailyDateAndDrainsNextCheck() {
        val up = FakeUploader()
        // The drain loop only ends capped when its per-check budget is used
        // up, so this check needs a full budget of capped chunks; the next
        // check then gets the uncapped tail.
        val d = FakeDumper(outcomes = mutableListOf(
            chunkDump(DbBookmark(4, 2), "db/incremental_20231114_1-4.sql.gz", capped = true),
            chunkDump(DbBookmark(8, 2), "db/incremental_20231114_5-8.sql.gz", capped = true),
            chunkDump(DbBookmark(12, 2), "db/incremental_20231114_9-12.sql.gz", capped = true),
            chunkDump(DbBookmark(16, 2), "db/incremental_20231114_13-16.sql.gz", capped = true),
            chunkDump(DbBookmark(20, 2), "db/incremental_20231114_17-20.sql.gz", capped = false),
        ))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        var stored: String? = null
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val first = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, first.state)
        // Every budgeted chunk was uploaded, each followed by its own bookmark
        // write, but the daily date was deliberately NOT persisted (the
        // backlog is still capped): the next tick the same day continues the
        // drain instead of waiting for tomorrow.
        assertEquals(DbBackupManager.MAX_CHUNKS_PER_CHECK, up.calls)
        assertEquals(DbBookmark(16, 2), DbBookmark.read(bookmarkFile))
        assertNull(stored)
        assertTrue(first.message, first.message.contains("backlog continues next check"))
        val second = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, second.state)
        assertEquals(DbBackupManager.MAX_CHUNKS_PER_CHECK + 1, up.calls)
        // Continued from the last uploaded chunk's cursor, not from scratch.
        assertEquals(DbBookmark(16, 2), d.lastBookmark)
        assertEquals(DbBookmark(20, 2), DbBookmark.read(bookmarkFile))
        assertEquals("2023-11-14", stored)
    }

    @Test fun drainLoopUploadsUpToMaxChunksPerCheck() {
        val up = FakeUploader()
        val d = FakeDumper(outcomes = MutableList(DbBackupManager.MAX_CHUNKS_PER_CHECK + 1) { i ->
            chunkDump(
                cursor = DbBookmark(4L * (i + 1), 2),
                objectKey = "db/incremental_20231114_chunk$i.sql.gz",
                capped = true,
                statements = 3 + i,
            )
        })
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        var stored: String? = null
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val st = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, st.state)
        // The budget caps one check: 4 chunks (8,000 torrents at the dumper's
        // 2,000-id cap), and the 5th scripted chunk is never asked for.
        assertEquals(DbBackupManager.MAX_CHUNKS_PER_CHECK, up.calls)
        assertEquals(DbBackupManager.MAX_CHUNKS_PER_CHECK, d.calls)
        assertEquals(DbBookmark(16, 2), DbBookmark.read(bookmarkFile))
        assertNull(stored)
        assertTrue(st.message, st.message.contains("uploaded 4 chunks"))
        assertTrue(st.message, st.message.contains("(18 statements)"))
        assertTrue(st.message, st.message.contains(
            "backlog continues next check (capped at torrent id 16)"))
        // Status fields describe the LAST successful PUT (DbBackupStatus KDoc).
        assertEquals("db/incremental_20231114_chunk3.sql.gz", st.objectKey)
        assertEquals(6, st.statementCount)
        assertEquals(up.lastBody!!.size.toLong(), st.sqlBytes)
        assertEquals(up.lastBody!!.size.toLong(), st.uploadedBytes)
        assertEquals(DAY1, st.lastSuccess)
    }

    @Test fun finalUncappedChunkWritesDailyDateWithinSameCheck() {
        val up = FakeUploader()
        val d = FakeDumper(outcomes = mutableListOf(
            chunkDump(DbBookmark(4, 2), "db/incremental_20231114_1-4.sql.gz", capped = true),
            chunkDump(DbBookmark(8, 2), "db/incremental_20231114_5-8.sql.gz", capped = true),
            chunkDump(DbBookmark(10, 2), "db/incremental_20231114_9-10.sql.gz", capped = false),
        ))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        var stored: String? = null
        var dateWrites = 0
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it; dateWrites++ },
        )
        val st = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertEquals(3, up.calls)
        assertEquals(3, d.calls)
        // The backlog was drained inside this one check, so the daily date is
        // written exactly once (by the final, uncapped chunk).
        assertEquals(1, dateWrites)
        assertEquals("2023-11-14", stored)
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
        assertEquals(DbBookmark(8, 2), d.lastBookmark)
        assertFalse(st.message, st.message.contains("backlog continues"))
    }

    @Test fun putFailureMidDrainKeepsLastSuccessfulCursor() {
        val up = FakeUploader().apply {
            results += S3PutResult.Success(200, "etag")
            results += S3PutResult.ClientError(403, "Forbidden")
        }
        val d = FakeDumper(outcomes = mutableListOf(
            chunkDump(DbBookmark(4, 2), "db/incremental_20231114_1-4.sql.gz", capped = true),
            chunkDump(DbBookmark(8, 2), "db/incremental_20231114_5-8.sql.gz", capped = true),
        ))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        var stored: String? = null
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val st = m.runCheck(manual = false)
        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("403"))
        assertEquals(2, up.calls)
        // Upload-first holds per chunk: the first chunk's cursor is persisted,
        // the failed chunk's is NOT, so the next check re-dumps exactly that
        // chunk (and nothing before it).
        assertEquals(DbBookmark(4, 2), DbBookmark.read(bookmarkFile))
        assertNull(stored)
    }

    @Test fun oomInCheckMarksFailedAndReleasesInFlight() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        // A valid bookmark file makes the check take the dump path below.
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        val oom = object : DbDumper {
            override fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome =
                throw OutOfMemoryError("boom")
        }
        val m = DbBackupManager(
            dbFile = { dbFile() },
            dumperFactory = { oom },
            loadSettings = { settings() },
            isBusy = { false },
            isWifi = { true },
            clientFactory = { up },
            bookmarkFile = { bookmarkFile },
            clock = { DAY1 },
            zone = ZoneOffset.UTC,
            executor = noop,
        )
        m.uploadNow(manual = true)
        assertTrue(m.inFlight)
        // The executor wrapper catches Throwable: an OOM inside the check must
        // mark it FAILED and release inFlight (unwinding the check frees its
        // memory) instead of escaping and killing the process.
        noop.last!!.run()
        assertEquals(DbBackupState.FAILED, m.status.state)
        assertTrue(m.status.message, m.status.message.contains("upload failed"))
        assertTrue(m.status.message, m.status.message.contains("boom"))
        assertFalse(m.inFlight)
        assertEquals(0, up.calls)
    }
}
