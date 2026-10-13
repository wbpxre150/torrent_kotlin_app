package com.torrent.app.backup

import com.torrent.app.config.BackupSettings
import com.torrent.app.s3.S3GetResult
import com.torrent.app.s3.S3ListResult
import com.torrent.app.s3.S3ListedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plain-JUnit tests for [DbRestoreManager]: the stop-first flow
 * (settings → stop → LIST → download/gunzip/concat → one-shot), the
 * chain ordering (sorted by date, then start id; malformed and
 * zero-size keys skipped), the bookmark ordering (reset to
 * [DbBookmark.FIRST_RUN] ONLY on a zero exit — a failed restore
 * leaves it untouched), the crawler restart on every stopped outcome,
 * the temp-file cleanup on success AND failure, and the
 * busy/in-flight/shutdown refusals. [DbRestoreManager.runRestore] is
 * invoked directly (synchronously) so the tests are deterministic; a
 * capturing [Executor] verifies the queueing path of [restoreNow].
 */
class DbRestoreManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val T0 = 1_700_000_000_000L

    /** Scripted [S3DbRestorer]; bodies are the GZIP bytes served per key. */
    private class FakeRestorer : S3DbRestorer {
        var listResult: S3ListResult = S3ListResult.Success(200, emptyList())
        val bodies = mutableMapOf<String, ByteArray>()
        var listCalls = 0
        val getOrder = mutableListOf<String>()

        override fun list(prefix: String): S3ListResult {
            listCalls++
            return listResult
        }

        override fun get(objectKey: String): S3GetResult {
            getOrder.add(objectKey)
            val body = bodies[objectKey]
                ?: return S3GetResult.ClientError(404, "missing $objectKey")
            return S3GetResult.Success(200, body, "etag")
        }
    }

    /** Scripted one-shot; captures the temp file's bytes at call time. */
    private class FakeRunner(var exitCode: Int = 0) {
        var calls = 0
        var lastArgs: List<String>? = null
        var lastDir: File? = null
        var capturedFileBytes: ByteArray? = null
        val emittedLines = mutableListOf<String>()

        fun run(args: List<String>, dir: File, onLine: (String) -> Unit): Int {
            calls++
            lastArgs = args
            lastDir = dir
            File(args[1]).let { capturedFileBytes = it.readBytes() }
            onLine("restored")
            emittedLines.add("restored")
            return exitCode
        }
    }

    /** Captures submitted runnables without running them. */
    private class CapturingExecutor : Executor {
        val submitted = AtomicInteger(0)
        @Volatile var last: Runnable? = null
        override fun execute(command: Runnable) {
            submitted.incrementAndGet()
            last = command
        }
    }

    private fun settings(
        endpoint: String = "https://acct.r2.cloudflarestorage.com",
        prefix: String = "db",
        bucket: String = "dht_backup",
        dbBackupEnabled: Boolean = false,
    ) = BackupSettings(
        r2Endpoint = endpoint,
        r2Bucket = "cache",
        r2AccessKeyId = "keyid",
        r2SecretAccessKey = "secret",
        dbBackupEnabled = dbBackupEnabled,
        r2DbBucket = bucket,
        r2DbObjectPrefix = prefix,
    )

    private fun newManager(
        restorer: FakeRestorer,
        runner: FakeRunner,
        settings: BackupSettings = settings(),
        busy: Boolean = false,
        clock: () -> Long = { T0 },
        bookmarkFile: File = File(tmp.root, "db_bookmark.txt"),
        objects: List<Pair<String, String>> = emptyList(),
    ): Triple<DbRestoreManager, FakeFlags, File> {
        val flags = FakeFlags()
        val m = DbRestoreManager(
            loadSettings = { settings },
            isBusy = { busy },
            clientFactory = { restorer },
            oneShot = { args, dir, onLine -> runner.run(args, dir, onLine) },
            workDir = { File(tmp.root, "crawler-work") },
            tmpDir = { File(tmp.root, "tmp") },
            bookmarkFile = { bookmarkFile },
            stopCrawler = { flags.stopCount++ },
            startCrawlerIfEnabled = { flags.startCount++ },
            executor = CapturingExecutor(),
            clock = clock,
        )
        for ((key, sql) in objects) restorer.bodies[key] = gzipBytes(sql.encodeToByteArray())
        // The default listing is empty; derive one from [objects] so tests
        // that only pass bodies get a matching LIST result. Tests that set
        // [restorer.listResult] themselves (e.g. zero-size entries) win.
        if (objects.isNotEmpty() && restorer.listResult is S3ListResult.Success &&
            (restorer.listResult as S3ListResult.Success).objects.isEmpty()) {
            restorer.listResult = S3ListResult.Success(
                200, objects.map { (key, sql) ->
                    S3ListedObject(key, gzipBytes(sql.encodeToByteArray()).size.toLong())
                })
        }
        return Triple(m, flags, bookmarkFile)
    }

    /** Counts the crawler stop/restart calls (the 60 s stop seam). */
    private class FakeFlags {
        var stopCount = 0
        var startCount = 0
    }

    private fun tempFiles(root: File): List<File> =
        root.listFiles()?.filter { it.name.startsWith("restore-") && it.name.endsWith(".sql") }
            ?: emptyList()

    @Test fun successPath_downloadsChainInOrderAndResetsBookmark() {
        val restorer = FakeRestorer()
        val runner = FakeRunner(exitCode = 0)
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            // Deliberately out of chain order in the listing.
            objects = listOf(
                "db/incremental_20231115_11-20.sql.gz" to "INSERT OR IGNORE INTO torrents VALUES(2);",
                "db/incremental_20231114_0-10.sql.gz" to "INSERT OR IGNORE INTO path_prefixes VALUES(1);",
            ),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(10, 2))

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertFalse(st.running)
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
        assertEquals(1, restorer.listCalls)
        // Chain order: earlier date first.
        assertEquals(
            listOf("db/incremental_20231114_0-10.sql.gz", "db/incremental_20231115_11-20.sql.gz"),
            restorer.getOrder)
        // One temp file, both objects concatenated in chain order.
        assertEquals(1, runner.calls)
        val args = runner.lastArgs!!
        assertEquals(2, args.size)
        assertEquals("--restore-database", args[0])
        assertTrue(args[1], args[1].endsWith(".sql"))
        assertEquals(
            "INSERT OR IGNORE INTO path_prefixes VALUES(1);" +
                "INSERT OR IGNORE INTO torrents VALUES(2);",
            String(runner.capturedFileBytes!!))
        assertEquals(File(tmp.root, "crawler-work"), runner.lastDir)
        // The temp file is deleted afterwards.
        assertTrue(tempFiles(File(tmp.root, "tmp")).isEmpty())
        // Bookmark reset to first run ONLY after the zero exit.
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(bookmarkFile))
        // Status carries the chain description and the exit code.
        assertEquals(2, st.objectCount)
        assertEquals(
            listOf("db/incremental_20231114_0-10.sql.gz", "db/incremental_20231115_11-20.sql.gz"),
            st.objectKeys)
        assertEquals(0, st.exitCode)
        assertEquals(T0, st.lastSuccess)
        assertEquals(T0, st.lastAttempt)
        assertTrue(st.configured)
    }

    @Test fun chainSortsByStartIdWithinADate() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf(
                "db/incremental_20231114_11-20.sql.gz" to "B",
                "db/incremental_20231114_0-10.sql.gz" to "A",
            ),
        )
        val st = m.runRestore()
        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(
            listOf("db/incremental_20231114_0-10.sql.gz", "db/incremental_20231114_11-20.sql.gz"),
            restorer.getOrder)
    }

    @Test fun runnerNonZero_leavesBookmarkUnchangedAndRestartsCrawler() {
        val restorer = FakeRestorer()
        val runner = FakeRunner(exitCode = 3)
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to "INSERT OR IGNORE INTO torrents VALUES(1);",
            ),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertEquals(3, st.exitCode)
        assertTrue(st.message, st.message.contains("exited with code 3"))
        // Critical ordering: the failed restore must not touch the bookmark.
        assertEquals(DbBookmark(5, 1), DbBookmark.read(bookmarkFile))
        // The crawler is restarted after a failure, too.
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
        assertNull(st.lastSuccess)
        assertTrue(tempFiles(File(tmp.root, "tmp")).isEmpty())
    }

    @Test fun listClientError_failsAndRestartsCrawler() {
        val restorer = FakeRestorer().apply {
            listResult = S3ListResult.ClientError(403, "Forbidden")
        }
        val runner = FakeRunner()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            objects = listOf("db/incremental_20231114_0-10.sql.gz" to "X"),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("403"))
        assertEquals(0, restorer.getOrder.size)
        assertEquals(0, runner.calls)
        assertEquals(DbBookmark(5, 1), DbBookmark.read(bookmarkFile))
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
    }

    @Test fun emptyListing_failsNothingToRestore() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(restorer, runner, bookmarkFile = bookmarkFile)
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("nothing to restore"))
        assertEquals(0, runner.calls)
        assertEquals(DbBookmark(5, 1), DbBookmark.read(bookmarkFile))
        assertEquals(1, flags.startCount)
    }

    @Test fun malformedKeysAreSkipped() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to "GOOD",
                "db/bogus.sql.gz" to "SHOULD NOT BE DOWNLOADED",
                "db/incremental_20231114_0-10.sql" to "NO GZIP SUFFIX",
                "other/incremental_20231114_0-10.sql.gz" to "DIFFERENT PREFIX",
            ),
        )

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(listOf("db/incremental_20231114_0-10.sql.gz"), restorer.getOrder)
        assertEquals(1, st.objectCount)
    }

    @Test fun allKeysMalformed_failsNothingToRestore() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf("db/bogus.sql.gz" to "X"),
        )
        val st = m.runRestore()
        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("nothing to restore"))
        assertEquals(0, runner.calls)
    }

    @Test fun zeroSizeObjectsAreSkipped() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf("db/incremental_20231114_0-10.sql.gz" to "GOOD"),
        )
        restorer.listResult = S3ListResult.Success(200, listOf(
            S3ListedObject("db/incremental_20231114_0-10.sql.gz", 42L),
            S3ListedObject("db/incremental_20231115_11-20.sql.gz", 0L),
        ))

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(listOf("db/incremental_20231114_0-10.sql.gz"), restorer.getOrder)
        assertEquals(1, st.objectCount)
    }

    @Test fun corruptGzip_failsAndDeletesTempFile() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, flags, _) = newManager(restorer, runner)
        restorer.bodies["db/incremental_20231114_0-10.sql.gz"] =
            byteArrayOf(1, 2, 3) // not gzip
        restorer.listResult = S3ListResult.Success(200, listOf(
            S3ListedObject("db/incremental_20231114_0-10.sql.gz", 3L)))

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("restore failed"))
        assertEquals(0, runner.calls)
        assertEquals(1, flags.startCount)
        assertTrue(tempFiles(File(tmp.root, "tmp")).isEmpty())
    }


    @Test fun missingEndpointIsNotConfigured() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, flags, _) = newManager(
            restorer, runner,
            settings(endpoint = ""))

        val st = m.runRestore()

        assertEquals(DbRestoreState.NOT_CONFIGURED, st.state)
        assertTrue(st.message, st.message.contains("not configured"))
        assertFalse(st.configured)
        assertEquals(0, flags.stopCount)
        assertEquals(0, restorer.listCalls)
    }

    @Test fun missingDbBucketIsNotConfiguredEvenWhenUploadDisabled() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, flags, _) = newManager(
            restorer, runner,
            settings(bucket = ""))

        val st = m.runRestore()

        assertEquals(DbRestoreState.NOT_CONFIGURED, st.state)
        assertEquals(0, flags.stopCount)
    }

    @Test fun restoreNow_queuesOnExecutorAndReportsRefusals() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, flags, _) = newManager(restorer, runner)

        // Not running restoreNow here (it uses the capturing executor):
        // a second trigger while the first is queued is refused.
        val first = m.restoreNow()
        assertEquals(DbRestoreState.RUNNING, first.state)
        assertTrue(first.running)
        val second = m.restoreNow()
        assertEquals(DbRestoreState.REFUSED_RUNNING, second.state)
        assertTrue(second.message, second.message.contains("already running"))
        // The job has not run yet: the crawler is untouched.
        assertEquals(0, flags.stopCount)
    }

    @Test fun restoreNowBusyRefusal() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, flags, _) = newManager(restorer, runner, busy = true)

        val st = m.restoreNow()

        assertEquals(DbRestoreState.REFUSED_BUSY, st.state)
        assertTrue(st.message, st.message.contains("refused"))
        assertEquals(0, flags.stopCount)
    }

    @Test fun restoreNowAfterStopRefuses() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, flags, _) = newManager(restorer, runner)
        m.stop()

        val st = m.restoreNow()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("shutting down"))
        assertEquals(0, flags.stopCount)
    }

    @Test fun outputLinesAreStreamedIntoTheStatusTail() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf("db/incremental_20231114_0-10.sql.gz" to "X"),
        )
        val st = m.runRestore()
        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(listOf("restored"), st.lines)
    }
}
