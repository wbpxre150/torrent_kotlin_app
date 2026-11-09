package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.s3.S3DeleteResult
import com.torrent.app.s3.S3ListResult
import com.torrent.app.s3.S3ListedObject
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
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plain-JUnit tests for [DbBackupManager]: the gate order
 * (configured → busy → wifi → daily → DB file → dump → PUT), the
 * upload-first bookmark ordering (success advances the bookmark; every
 * PUT failure leaves it unchanged so the same range is retried), the
 * calendar-day gate (fake clock), the manual bypass (daily gate only),
 * the single-flight refusal, the bounded chunk drain, and the fast follow-up
 * chain that drains a large backlog near-continuously (a capped periodic
 * check chains ONE one-shot follow-up on the same executor).
 * [DbBackupManager.runCheck] is invoked directly (synchronously) so the tests
 * are deterministic; a no-op [ScheduledExecutorService] stands in for the real
 * scheduler and records the follow-ups instead of running them.
 */
// One scripted dump outcome. The class-index sidecar is derived from
// `objectKey` (`.sql.gz` → `.idx.gz`) and carries `sidecarText`, so a test can
// assert both objects of a chunk without spelling out two keys.
private fun testDumpOutcome(
    cursor: DbBookmark = DbBookmark(10, 2),
    objectKey: String = "db/incremental_20231114_0-10.sql.gz",
    sidecarText: String = "1 3\n2 0\n",
): DbDumpOutcome.Dump = DbDumpOutcome.Dump(
    sqlBytes = gzipBytes("INSERT OR IGNORE INTO torrents VALUES(1);".encodeToByteArray()),
    statementCount = 3,
    newCursor = cursor,
    objectKey = objectKey,
    newTorrentCount = 10,
    sidecarBytes = gzipBytes(sidecarText.encodeToByteArray()),
    sidecarObjectKey = sidecarKeyOf(objectKey),
)

/** The class-index sidecar key of a dump key (`.sql.gz` → `.idx.gz`). */
private fun sidecarKeyOf(objectKey: String): String =
    objectKey.removeSuffix(".sql.gz") + ".idx.gz"

class DbBackupManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Day 1: 2023-11-14 in UTC. */
    private val DAY1 = 1_700_000_000_000L

    /**
     * Records every PUT of a check. The two objects of a chunk are told apart
     * by their key suffix: the SQL artifact ends in `.sql.gz`, its class-index
     * sidecar in `.idx.gz` — the sidecar has its own scripted [sidecarResult],
     * so a test can fail either one independently.
     */
    private class FakeUploader : S3DbUploader {
        class Put(val objectKey: String, val contentType: String, val body: ByteArray)

        /** Scripted result for SQL-artifact PUTs. */
        var result: S3PutResult = S3PutResult.Success(200, "etag")
        /** Optional per-call results (drain tests): consumed before [result]. */
        val results = mutableListOf<S3PutResult>()
        /** Scripted result for class-index sidecar PUTs. */
        var sidecarResult: S3PutResult = S3PutResult.Success(200, "etag")

        /** Every PUT in call order (SQL artifacts and sidecars interleaved). */
        val puts = mutableListOf<Put>()
        /** Invoked with each PUT's object key, right after it was recorded. */
        var onPut: ((String) -> Unit)? = null

        /** Number of SQL-artifact PUTs (= chunks uploaded). */
        val calls: Int get() = puts.count { it.objectKey.endsWith(".sql.gz") }
        /** Number of class-index sidecar PUTs. */
        val sidecarCalls: Int get() = puts.count { it.objectKey.endsWith(".idx.gz") }
        private val lastSqlPut: Put? get() = puts.lastOrNull { it.objectKey.endsWith(".sql.gz") }
        val lastKey: String? get() = lastSqlPut?.objectKey
        val lastContentType: String? get() = lastSqlPut?.contentType
        val lastBody: ByteArray? get() = lastSqlPut?.body

        override fun put(objectKey: String, contentType: String, body: ByteArray): S3PutResult {
            puts += Put(objectKey, contentType, body)
            onPut?.invoke(objectKey)
            return if (objectKey.endsWith(".idx.gz")) {
                sidecarResult
            } else {
                if (results.isNotEmpty()) results.removeAt(0) else result
            }
        }
    }

    /**
     * Fake [S3DbPurger] for the reset-from-zero tests: records every LIST
     * prefix and every DELETE attempt, and scripts both sides (a queue of
     * per-call LIST results, per-key and per-call DELETE overrides).
     */
    private class FakePurger(
        var listResult: S3ListResult = S3ListResult.Success(200, emptyList()),
        private val listResults: MutableList<S3ListResult> = mutableListOf(),
    ) : S3DbPurger {
        /** Every prefix passed to [list], in call order. */
        val listPrefixes = mutableListOf<String>()
        /** Every key [delete] was called with, in call order. */
        val deleted = mutableListOf<String>()
        /** Per-key scripted DELETE results (an unscripted key answers Success). */
        val deleteResults = mutableMapOf<String, S3DeleteResult>()
        /** Optional per-call DELETE results, consumed before [deleteResults]. */
        val deleteQueue = mutableListOf<S3DeleteResult>()

        override fun list(prefix: String): S3ListResult {
            listPrefixes += prefix
            return if (listResults.isNotEmpty()) listResults.removeAt(0) else listResult
        }

        override fun delete(objectKey: String): S3DeleteResult {
            deleted += objectKey
            if (deleteQueue.isNotEmpty()) return deleteQueue.removeAt(0)
            return deleteResults[objectKey] ?: S3DeleteResult.Success(204)
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

    /** Minimal never-completing [ScheduledFuture] stand-in for recorded one-shots. */
    private object NeverFuture : ScheduledFuture<Any?> {
        override fun getDelay(unit: TimeUnit): Long = 0L
        override fun compareTo(other: Delayed): Int = 0
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = false
        override fun get(): Any? = throw UnsupportedOperationException("never completes")
        override fun get(timeout: Long, unit: TimeUnit): Any? =
            throw UnsupportedOperationException("never completes")
    }

    /**
     * Captures submitted runnables without running them (post-shutdown:
     * rejects). One-shot [schedule] calls (the fast backlog follow-up) are
     * recorded with their delay instead of being handed to a real timer, so a
     * test can assert the chain and run each link by hand.
     */
    private class NoopScheduledExecutorService :
        ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor(ThreadFactory {
            Thread(it, "noop-sched-test").apply { isDaemon = true }
        }) {
        /** One recorded one-shot task: its delay in milliseconds and the task. */
        class OneShot(val delayMs: Long, val task: Runnable)

        val submitted = AtomicInteger(0)
        @Volatile var last: Runnable? = null
        /** One-shots recorded by [schedule], in call order. */
        val oneShots = mutableListOf<OneShot>()
        @Volatile private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("shut down")
            submitted.incrementAndGet()
            last = command
        }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (shutdown) throw RejectedExecutionException("shut down")
            oneShots += OneShot(unit.toMillis(delay), command)
            return NeverFuture
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
        dumper: DbDumper,
        uploader: FakeUploader,
        settings: BackupSettings = settings(),
        busy: Boolean = false,
        wifi: Boolean = true,
        clock: () -> Long = { DAY1 },
        executor: ScheduledExecutorService = NoopScheduledExecutorService(),
        fastFollowUpDelayMs: Long = DbBackupManager.FAST_FOLLOW_DELAY_MS,
        bookmarkFile: File = File(tmp.root, "db_bookmark.txt"),
        lastSuccessDate: () -> String? = { null },
        writeLastSuccessDate: (String) -> Unit = {},
        purger: S3DbPurger = FakePurger(),
        markerFile: File = File(tmp.root, "db_reset_pending"),
        clearLastSuccessDate: () -> Unit = {},
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
        fastFollowUpDelayMs = fastFollowUpDelayMs,
        readLastSuccessDate = lastSuccessDate,
        writeLastSuccessDate = writeLastSuccessDate,
        purgerFactory = { purger },
        resetMarkerFile = { markerFile },
        clearLastSuccessDate = clearLastSuccessDate,
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

    @Test fun stopThenStartAcceptsANewUpload() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val m = newManager(dbFile(), FakeDumper(), up, executor = noop)
        // Accepted and queued, but never run: stop()'s shutdownNow() drops it,
        // so the wrapper's finally never releases the in-flight guard. Only
        // stop() itself can, or the manager stays wedged for the rest of the
        // process lifetime (the service instance survives a graceful stop).
        m.uploadNow(manual = true)
        m.stop()
        m.start()
        val st = m.uploadNow(manual = true)
        assertFalse(st.message, st.message.contains("already running"))
        assertFalse(st.message, st.message.contains("shutting down"))
        // Not asserted as "db backup queued": start() rebuilt a real executor,
        // so the check may already have written its outcome.
        m.stop()
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

    // --- class-index sidecar (Stage 1) --------------------------------------

    /**
     * One chunk PUTs its SQL artifact, then its class-index sidecar, and only
     * then advances the bookmark: upload-first covers BOTH objects.
     */
    @Test fun sidecarIsUploadedAfterTheSqlAndBeforeTheBookmark() {
        val up = FakeUploader()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark(0, 0))
        val bookmarkAtPut = mutableListOf<DbBookmark?>()
        up.onPut = { bookmarkAtPut += DbBookmark.readOrNull(bookmarkFile) }
        val m = newManager(dbFile(), FakeDumper(outcome = testDumpOutcome()), up,
            bookmarkFile = bookmarkFile)

        val st = m.runCheck(manual = true)

        assertEquals(DbBackupState.SUCCESS, st.state)
        // SQL artifact first, its sidecar second — same range, same prefix.
        assertEquals(
            listOf(
                "db/incremental_20231114_0-10.sql.gz",
                "db/incremental_20231114_0-10.idx.gz",
            ),
            up.puts.map { it.objectKey },
        )
        assertEquals(1, up.calls)
        assertEquals(1, up.sidecarCalls)
        assertEquals(
            listOf("application/gzip", "application/gzip"),
            up.puts.map { it.contentType },
        )
        // The sidecar body is the gzipped class index of the range.
        assertEquals("1 3\n2 0\n", gunzipBytes(up.puts[1].body).decodeToString())
        // Neither PUT observed the advanced bookmark; it moved only afterwards.
        assertEquals(listOf(DbBookmark(0, 0), DbBookmark(0, 0)), bookmarkAtPut)
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
    }

    /**
     * A sidecar PUT failure fails the check and leaves the bookmark untouched,
     * so the next run re-dumps and re-uploads BOTH objects (idempotent).
     */
    @Test fun sidecarPutFailureLeavesBookmarkUnchanged() {
        val up = FakeUploader().apply {
            sidecarResult = S3PutResult.ClientError(403, "Forbidden")
        }
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark(0, 0))
        val m = newManager(dbFile(), FakeDumper(outcome = testDumpOutcome()), up,
            bookmarkFile = bookmarkFile)

        val st = m.runCheck(manual = true)

        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("R2 rejected the upload"))
        // The SQL artifact landed, the sidecar did not.
        assertEquals(1, up.calls)
        assertEquals(1, up.sidecarCalls)
        assertEquals(DbBookmark(0, 0), DbBookmark.read(bookmarkFile))
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
        // A small, per-chunk class index: one entry per chunk.
        sidecarBytes = gzipBytes("${cursor.lastTorrentId} 1\n".encodeToByteArray()),
        sidecarObjectKey = sidecarKeyOf(objectKey),
    )

    @Test fun cappedSuccessDoesNotWriteDailyDateAndDrainsNextCheck() {
        val up = FakeUploader()
        val budget = DbBackupManager.MAX_CHUNKS_PER_CHECK
        // The drain loop only ends capped when its per-check budget is used
        // up, so this check needs a full budget of capped chunks; the next
        // check then gets the uncapped tail.
        val outcomes = MutableList<DbDumpOutcome>(budget) { i ->
            chunkDump(
                DbBookmark(4L * (i + 1), 2),
                "db/incremental_20231114_chunk$i.sql.gz",
                capped = true)
        }
        outcomes += chunkDump(DbBookmark(4L * (budget + 1), 2),
            "db/incremental_20231114_tail.sql.gz", capped = false)
        val d = FakeDumper(outcomes = outcomes)
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
        // backlog is still capped): the drain continues from the advanced
        // bookmark instead of waiting for tomorrow.
        assertEquals(budget, up.calls)
        assertEquals(DbBookmark(4L * budget, 2), DbBookmark.read(bookmarkFile))
        assertNull(stored)
        assertTrue(first.message, first.message.contains("backlog continues"))
        val second = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, second.state)
        assertEquals(budget + 1, up.calls)
        // Continued from the last uploaded chunk's cursor, not from scratch.
        assertEquals(DbBookmark(4L * budget, 2), d.lastBookmark)
        assertEquals(DbBookmark(4L * (budget + 1), 2), DbBookmark.read(bookmarkFile))
        assertEquals("2023-11-14", stored)
    }

    @Test fun drainLoopUploadsUpToMaxChunksPerCheck() {
        val up = FakeUploader()
        val budget = DbBackupManager.MAX_CHUNKS_PER_CHECK
        val d = FakeDumper(outcomes = MutableList<DbDumpOutcome>(budget + 1) { i ->
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
        // The budget caps one check (2,000 torrents per chunk at the dumper's
        // id cap); the extra scripted chunk is never asked for.
        assertEquals(budget, up.calls)
        assertEquals(budget, d.calls)
        assertEquals(DbBookmark(4L * budget, 2), DbBookmark.read(bookmarkFile))
        assertNull(stored)
        val totalStatements = (0 until budget).sumOf { 3 + it }
        assertTrue(st.message, st.message.contains("uploaded $budget chunks"))
        assertTrue(st.message, st.message.contains("($totalStatements statements)"))
        assertTrue(st.message, st.message.contains(
            "backlog continues (capped at torrent id ${4L * budget})"))
        // Status fields describe the LAST successful PUT (DbBackupStatus KDoc).
        assertEquals("db/incremental_20231114_chunk${budget - 1}.sql.gz", st.objectKey)
        assertEquals(3 + budget - 1, st.statementCount)
        assertEquals(up.lastBody!!.size.toLong(), st.sqlBytes)
        assertEquals(up.lastBody!!.size.toLong(), st.uploadedBytes)
        assertEquals(DAY1, st.lastSuccess)
    }

    @Test fun publishesRunningAndAdvancingChunkCounters() {
        val up = FakeUploader()
        val d = FakeDumper(outcomes = mutableListOf(
            chunkDump(DbBookmark(4, 2), "db/incremental_20231114_1-4.sql.gz",
                capped = true, statements = 3),
            chunkDump(DbBookmark(8, 2), "db/incremental_20231114_5-8.sql.gz",
                capped = true, statements = 5),
            chunkDump(DbBookmark(10, 2), "db/incremental_20231114_9-10.sql.gz",
                capped = false, statements = 2),
        ))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile)
        // Read the live status at the start of every chunk's SQL PUT: the
        // counters banked by the previous chunks must already be published
        // (Stage 6), so a 1 Hz observer sees them advance between two reads.
        val midRun = mutableListOf<DbBackupStatus>()
        up.onPut = { key -> if (key.endsWith(".sql.gz")) midRun += m.status }
        val st = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertEquals(3, midRun.size)
        // First chunk: RUNNING published, counters still zero, no percentage.
        assertEquals(DbBackupState.RUNNING, midRun[0].state)
        assertTrue(midRun[0].running)
        assertEquals(DAY1, midRun[0].startedAt!!)
        assertEquals(0, midRun[0].chunks!!)
        assertEquals(0L, midRun[0].totalBytes!!)
        // The second and third reads see the earlier chunks' counters.
        assertEquals(1, midRun[1].chunks!!)
        assertEquals(3, midRun[1].totalStatements!!)
        assertTrue(midRun[1].totalBytes!! > 0L)
        assertEquals(2, midRun[2].chunks!!)
        assertEquals(8, midRun[2].totalStatements!!)
        assertTrue(midRun[2].totalBytes!! > midRun[1].totalBytes!!)
        // Terminal: the live flag drops, the counters and start stamp stay.
        assertFalse(st.running)
        assertEquals(3, st.chunks!!)
        assertEquals(10, st.totalStatements!!)
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

    // --- fast follow-up chain (near-continuous backlog drain) ---

    /**
     * Advances the cursor four torrents per chunk and always reports more rows
     * beyond it, i.e. a backlog that never catches up inside one check.
     */
    private fun alwaysCappedDumper(seen: MutableList<DbBookmark> = mutableListOf()) =
        object : DbDumper {
            override fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome {
                seen += bookmark
                val next = DbBookmark(bookmark.lastTorrentId + 4, 2)
                return chunkDump(
                    next,
                    "db/incremental_20231114_${next.lastTorrentId}.sql.gz",
                    capped = true)
            }
        }

    @Test fun cappedPeriodicCheckChainsFastFollowUpThatKeepsDraining() {
        val up = FakeUploader()
        val seen = mutableListOf<DbBookmark>()
        val noop = NoopScheduledExecutorService()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        var stored: String? = null
        val m = newManager(
            dbFile(), alwaysCappedDumper(seen), up,
            executor = noop,
            fastFollowUpDelayMs = 250,
            bookmarkFile = bookmarkFile,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        m.uploadNow(manual = false)
        noop.last!!.run()
        val budget = DbBackupManager.MAX_CHUNKS_PER_CHECK
        assertEquals(DbBackupState.SUCCESS, m.status.state)
        assertEquals(budget, up.calls)
        // Still capped: the daily date is NOT written.
        assertNull(stored)
        // Exactly one fast follow-up was chained — after the injected delay,
        // not on the 15-minute periodic tick.
        assertEquals(1, noop.oneShots.size)
        assertEquals(250L, noop.oneShots[0].delayMs)
        val persisted = DbBookmark.read(bookmarkFile)
        // Running it issues another check ...
        noop.oneShots[0].task.run()
        assertEquals(2, noop.submitted.get())
        noop.last!!.run()
        assertEquals(2 * budget, up.calls)
        // ... which resumes from the bookmark the capped check persisted.
        assertEquals(persisted, seen[budget])
        // Still capped: the follow-up chains the next one (near-continuous).
        assertEquals(2, noop.oneShots.size)
        assertEquals(250L, noop.oneShots[1].delayMs)
    }

    @Test fun uncappedPeriodicCheckSchedulesNoFollowUpAndWritesDailyDate() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        var stored: String? = null
        val m = newManager(
            dbFile(), FakeDumper(), up,
            executor = noop,
            fastFollowUpDelayMs = 250,
            bookmarkFile = bookmarkFile,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        m.uploadNow(manual = false)
        noop.last!!.run()
        assertEquals(DbBackupState.SUCCESS, m.status.state)
        // Caught up: no fast follow-up, and the daily date is written (steady
        // state: one upload per day on the 15-minute cadence).
        assertEquals(0, noop.oneShots.size)
        assertEquals("2023-11-14", stored)
        // The next periodic tick the same day is skipped by the daily gate.
        m.uploadNow(manual = false)
        noop.last!!.run()
        assertTrue(m.status.message, m.status.message.contains("already uploaded today"))
        assertEquals(1, up.calls)
        assertEquals(0, noop.oneShots.size)
    }

    @Test fun failedCheckSchedulesNoFollowUp() {
        val up = FakeUploader().apply {
            results += S3PutResult.Success(200, "etag")
            results += S3PutResult.ClientError(403, "Forbidden")
        }
        val noop = NoopScheduledExecutorService()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark.FIRST_RUN)
        val m = newManager(
            dbFile(), alwaysCappedDumper(), up,
            executor = noop,
            fastFollowUpDelayMs = 250,
            bookmarkFile = bookmarkFile,
        )
        m.uploadNow(manual = false)
        noop.last!!.run()
        assertEquals(DbBackupState.FAILED, m.status.state)
        assertEquals(2, up.calls)
        // No chain on failure: the 15-minute periodic tick is the retry path.
        assertEquals(0, noop.oneShots.size)
        // Upload-first still holds: the failed chunk's cursor is not persisted.
        assertEquals(DbBookmark(3, 2), DbBookmark.read(bookmarkFile))
    }

    @Test fun secondCappedCheckDoesNotStackASecondFollowUp() {
        val up = FakeUploader()
        val noop = NoopScheduledExecutorService()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val m = newManager(
            dbFile(), alwaysCappedDumper(), up,
            executor = noop,
            fastFollowUpDelayMs = 250,
            bookmarkFile = bookmarkFile,
        )
        m.uploadNow(manual = false)
        noop.last!!.run()
        val budget = DbBackupManager.MAX_CHUNKS_PER_CHECK
        assertEquals(1, noop.oneShots.size)
        // A periodic check lands while the follow-up is still pending: it ends
        // capped too, but must not stack a second follow-up.
        m.uploadNow(manual = false)
        noop.last!!.run()
        assertEquals(2 * budget, up.calls)
        assertEquals(DbBackupState.SUCCESS, m.status.state)
        assertEquals(1, noop.oneShots.size)
        // Once the pending follow-up starts (clearing the guard) the chain
        // resumes and the still-capped check schedules the next link.
        noop.oneShots[0].task.run()
        noop.last!!.run()
        assertEquals(3 * budget, up.calls)
        assertEquals(2, noop.oneShots.size)
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

    // --- reset from zero (Stage 1) -------------------------------------------

    @Test fun resetPurgesPrefixObjectsAndRestartsFromZero() {
        val up = FakeUploader()
        val d = FakeDumper()
        val purger = FakePurger(listResult = S3ListResult.Success(200, listOf(
            S3ListedObject("db/a.sql.gz", 10L),
            S3ListedObject("other/x", 20L),
            S3ListedObject("db/a.idx.gz", 30L),
        )))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark(10, 2))
        val markerFile = File(tmp.root, "db_reset_pending")
        var cleared = 0
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
            purger = purger,
            markerFile = markerFile,
            clearLastSuccessDate = { cleared++ },
        )
        val st = m.runCheck(manual = false, reset = true)
        assertEquals(DbBackupState.SUCCESS, st.state)
        // One uncapped LIST round, one prefix, prefix-only deletes.
        assertEquals(listOf("db/"), purger.listPrefixes)
        assertEquals(listOf("db/a.sql.gz", "db/a.idx.gz"), purger.deleted)
        assertEquals(1, cleared)
        assertFalse(markerFile.exists())
        assertFalse(st.resetPending)
        // The local cursor was dropped BEFORE the drain, so the dump restarts
        // from the very beginning of the database.
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        assertEquals(1, up.calls)
        assertTrue(st.message, st.message.startsWith("reset: deleted 2 objects; "))
    }

    @Test fun resetDeleteFailureFailsWithMarkerAndNoUpload() {
        val up = FakeUploader()
        val d = FakeDumper()
        val purger = FakePurger(listResult = S3ListResult.Success(200, listOf(
            S3ListedObject("db/a.sql.gz", 10L),
            S3ListedObject("db/a.idx.gz", 20L),
        )))
        purger.deleteQueue += S3DeleteResult.Success(204)
        purger.deleteQueue += S3DeleteResult.ServerError(503, "unavailable", attempts = 3)
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark(10, 2))
        val markerFile = File(tmp.root, "db_reset_pending")
        var cleared = 0
        val m = newManager(
            dbFile(), d, up,
            bookmarkFile = bookmarkFile,
            purger = purger,
            markerFile = markerFile,
            clearLastSuccessDate = { cleared++ },
        )
        val st = m.runCheck(manual = false, reset = true)
        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.startsWith("reset pending:"))
        assertTrue(st.message, st.message.contains("db/a.idx.gz"))
        // The marker survives, so every later upload stays blocked.
        assertTrue(markerFile.exists())
        assertTrue(st.resetPending)
        // The local cursor and the daily date are untouched: nothing re-drains
        // onto the half-deleted prefix.
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
        assertEquals(0, cleared)
        assertEquals(0, d.calls)
        assertEquals(0, up.calls)
    }

    @Test fun pendingMarkerCompletesOnNextPeriodicCheck() {
        val up = FakeUploader()
        val d = FakeDumper()
        val purger = FakePurger(listResult = S3ListResult.Success(200,
            listOf(S3ListedObject("db/a.sql.gz", 10L))))
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        DbBookmark.write(bookmarkFile, DbBookmark(10, 2))
        val markerFile = File(tmp.root, "db_reset_pending").apply { writeText("") }
        val m = newManager(dbFile(), d, up, bookmarkFile = bookmarkFile,
            purger = purger, markerFile = markerFile)
        // No reset flag: the leftover marker alone drives the retry.
        val st = m.runCheck(manual = false)
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertEquals(listOf("db/a.sql.gz"), purger.deleted)
        assertFalse(markerFile.exists())
        assertEquals(DbBookmark.FIRST_RUN, d.lastBookmark)
        assertEquals(1, up.calls)
    }

    @Test fun pendingPurgeRunsDespiteDailyGate() {
        val up = FakeUploader()
        val purger = FakePurger(listResult = S3ListResult.Success(200,
            listOf(S3ListedObject("db/a.sql.gz", 10L))))
        val markerFile = File(tmp.root, "db_reset_pending").apply { writeText("") }
        // Already uploaded today, per the fake clock (DAY1 = 2023-11-14).
        var stored: String? = "2023-11-14"
        val m = newManager(
            dbFile(), FakeDumper(), up,
            purger = purger,
            markerFile = markerFile,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
            clearLastSuccessDate = { stored = null },
        )
        val st = m.runCheck(manual = false)
        // The purge ran BEFORE the daily gate was consulted, and clearing the
        // daily date let the re-drain through in the same check.
        assertEquals(listOf("db/a.sql.gz"), purger.deleted)
        assertFalse(markerFile.exists())
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertEquals(1, up.calls)
        assertTrue(st.message, st.message.startsWith("reset: deleted 1 objects; "))
    }

    @Test fun resetWhenDisabledIsNotConfigured() {
        val purger = FakePurger()
        val markerFile = File(tmp.root, "db_reset_pending")
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(),
            settings(dbBackupEnabled = false), purger = purger, markerFile = markerFile)
        val st = m.runCheck(manual = false, reset = true)
        assertEquals(DbBackupState.NOT_CONFIGURED, st.state)
        assertEquals(0, purger.listPrefixes.size)
        assertEquals(0, purger.deleted.size)
        // A failing gate must never write the marker (it would block uploads).
        assertFalse(markerFile.exists())
        assertFalse(st.resetPending)
    }

    @Test fun resetWhenBusySkipsWithoutMarker() {
        val purger = FakePurger()
        val markerFile = File(tmp.root, "db_reset_pending")
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(), busy = true,
            purger = purger, markerFile = markerFile)
        val st = m.runCheck(manual = false, reset = true)
        assertEquals(DbBackupState.SKIPPED_BUSY, st.state)
        assertEquals(0, purger.listPrefixes.size)
        assertFalse(markerFile.exists())
    }

    @Test fun resetWithoutWifiSkipsWithoutMarker() {
        val purger = FakePurger()
        val markerFile = File(tmp.root, "db_reset_pending")
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(), wifi = false,
            purger = purger, markerFile = markerFile)
        val st = m.runCheck(manual = false, reset = true)
        assertEquals(DbBackupState.SKIPPED_NO_WIFI, st.state)
        assertEquals(0, purger.listPrefixes.size)
        assertFalse(markerFile.exists())
    }

    @Test fun resetHandlesCappedListingsInRounds() {
        val purger = FakePurger(listResults = mutableListOf(
            S3ListResult.Capped(200, listOf(
                S3ListedObject("db/a.sql.gz", 1L),
                S3ListedObject("db/b.sql.gz", 1L),
            ), "token-1"),
            S3ListResult.Success(200, listOf(S3ListedObject("db/c.sql.gz", 1L))),
        ))
        val markerFile = File(tmp.root, "db_reset_pending")
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(),
            purger = purger, markerFile = markerFile)
        val st = m.runCheck(manual = false, reset = true)
        assertEquals(DbBackupState.SUCCESS, st.state)
        // A Capped listing deletes its page and lists again.
        assertEquals(2, purger.listPrefixes.size)
        assertEquals(listOf("db/a.sql.gz", "db/b.sql.gz", "db/c.sql.gz"), purger.deleted)
        assertTrue(st.message, st.message.startsWith("reset: deleted 3 objects; "))
        assertFalse(markerFile.exists())
    }

    @Test fun resetListFailureKeepsMarker() {
        val purger = FakePurger(listResult = S3ListResult.ClientError(403, "Forbidden"))
        val markerFile = File(tmp.root, "db_reset_pending")
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(),
            purger = purger, markerFile = markerFile)
        val st = m.runCheck(manual = false, reset = true)
        assertEquals(DbBackupState.FAILED, st.state)
        assertTrue(st.message, st.message.startsWith("reset pending: list failed"))
        assertTrue(st.message, st.message.contains("403"))
        assertTrue(markerFile.exists())
        assertTrue(st.resetPending)
        assertEquals(0, purger.deleted.size)
    }

    @Test fun resetTreatsDelete404AsDeleted() {
        val purger = FakePurger(listResult = S3ListResult.Success(200,
            listOf(S3ListedObject("db/a.sql.gz", 1L))))
        // A retried purge after a partial one: the object is already gone.
        purger.deleteResults["db/a.sql.gz"] = S3DeleteResult.ClientError(404, "NoSuchKey")
        val markerFile = File(tmp.root, "db_reset_pending")
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(),
            purger = purger, markerFile = markerFile)
        val st = m.runCheck(manual = false, reset = true)
        assertEquals(DbBackupState.SUCCESS, st.state)
        assertTrue(st.message, st.message.startsWith("reset: deleted 1 objects; "))
        assertFalse(markerFile.exists())
    }

    @Test fun resetNowRefusedWhileInFlight() {
        val noop = NoopScheduledExecutorService()
        val m = newManager(dbFile(), FakeDumper(), FakeUploader(), executor = noop)
        m.uploadNow(manual = true)
        val refused = m.resetNow()
        // A reset is operator-initiated, so the refusal is always surfaced.
        assertTrue(refused.message,
            refused.message.contains("refused: an upload is already running"))
        assertEquals(1, noop.submitted.get())
    }

    @Test fun cappedResetDrainChainsFastFollowUp() {
        val up = FakeUploader()
        val purger = FakePurger(listResult = S3ListResult.Success(200,
            listOf(S3ListedObject("db/a.sql.gz", 1L))))
        val noop = NoopScheduledExecutorService()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        var stored: String? = null
        val m = newManager(
            dbFile(), alwaysCappedDumper(), up,
            executor = noop,
            fastFollowUpDelayMs = 250,
            bookmarkFile = bookmarkFile,
            purger = purger,
            lastSuccessDate = { stored },
            writeLastSuccessDate = { stored = it },
        )
        val queued = m.resetNow()
        assertEquals("db backup reset queued", queued.message)
        noop.last!!.run()
        val budget = DbBackupManager.MAX_CHUNKS_PER_CHECK
        assertEquals(DbBackupState.SUCCESS, m.status.state)
        assertEquals(budget, up.calls)
        assertEquals(1, purger.listPrefixes.size)
        assertTrue(m.status.message, m.status.message.startsWith("reset: deleted 1 objects; "))
        // Still capped: the daily date is not written and the reset (a
        // non-manual check) chains the fast follow-up that keeps draining.
        assertNull(stored)
        assertEquals(1, noop.oneShots.size)
        assertEquals(250L, noop.oneShots[0].delayMs)
        noop.oneShots[0].task.run()
        noop.last!!.run()
        assertEquals(2 * budget, up.calls)
        assertEquals(2, noop.oneShots.size)
    }
}
