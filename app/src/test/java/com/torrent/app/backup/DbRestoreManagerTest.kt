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
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plain-JUnit tests for [DbRestoreManager]: the resumable, chunk-by-chunk
 * importer (plan 3, Stage 3) — stop-first flow (settings → stop → reap →
 * LIST → per-chunk import into the temp DB → native `--rebuild-fts` → swap →
 * bookmark), the chain ordering (ID-MAJOR: sorted by start id, then date;
 * malformed and zero-size keys skipped), the chain coherence failures (a gap
 * against the running max end; overlap is legal), the per-chunk resume marker
 * (advance after every committed chunk; resume from it; discard a mismatched
 * one), the FTS-rebuild-before-swap ordering (exit != 0 → FAILED, no swap, the
 * temp DB and marker kept so a retry re-runs only the rebuild), the bookmark
 * ordering (reset to [DbBookmark.FIRST_RUN] ONLY on success), the crawler
 * restart on every stopped outcome (even a throwing stopCrawler), the
 * OOM-safety of the status (a [Throwable] from the sink lands FAILED with
 * `running = false`), and the busy/in-flight/shutdown refusals.
 *
 * [DbRestoreManager.runRestore] is invoked directly (synchronously) so the
 * tests are deterministic; a capturing [Executor] verifies the queueing path
 * of [DbRestoreManager.restoreNow].
 */
class DbRestoreManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val T0 = 1_700_000_000_000L

    /** Every sink the manager created, in run order. */
    private val sinks = mutableListOf<FakeSink>()

    /** The manager built by [newManager], for probes taken mid-run. */
    private lateinit var manager: DbRestoreManager

    /** Scripted [S3DbRestorer]; bodies are the GZIP bytes served per key. */
    private class FakeRestorer : S3DbRestorer {
        var listResult: S3ListResult = S3ListResult.Success(200, emptyList())
        val bodies = mutableMapOf<String, ByteArray>()
        var listCalls = 0
        val getOrder = mutableListOf<String>()
        /** 1-based [get] call number that fails with a transport error. */
        var failGetCall: Int? = null
        /** Optional probe invoked at the top of every [get], so a test can
         *  snapshot the manager's progress between downloads. */
        var statusProbe: (() -> DbRestoreStatus)? = null
        val getSnapshots = mutableListOf<DbRestoreStatus>()

        override fun list(prefix: String): S3ListResult {
            listCalls++
            return listResult
        }

        override fun get(objectKey: String): S3GetResult {
            statusProbe?.let { getSnapshots.add(it()) }
            getOrder.add(objectKey)
            if (failGetCall == getOrder.size) {
                return S3GetResult.Failure(IOException("network down"))
            }
            val body = bodies[objectKey]
                ?: return S3GetResult.ClientError(404, "missing $objectKey")
            return S3GetResult.Success(200, body, "etag")
        }
    }

    /**
     * Scripted [RestoreStatementSink]: records the replay it was handed and can
     * fail like the production sink does — including with an
     * [OutOfMemoryError], which is the whole point of catching `Throwable`.
     */
    private class FakeSink(val dbFile: File) : RestoreStatementSink {
        var prepareCalls = 0
        var fresh: Boolean? = null
        /** One entry per committed chunk, in replay order. */
        val chunks = mutableListOf<List<String>>()
        var finishCalls = 0
        var abandonCalls = 0
        /** Lines [finishImport] returns (they land in the restore status). */
        var summary: List<String> =
            listOf("sqlite_version=fake (platform SQLite)", "wal_checkpoint(TRUNCATE): busy=0")
        /** Throws [OutOfMemoryError] from the nth (1-based) [statement]. */
        var oomAtStatement: Int? = null
        /** Throws [OutOfMemoryError] from [prepare]. */
        var oomOnPrepare = false
        /** Supplies the manager status recorded by [finishImport]. */
        var statusProvider: (() -> DbRestoreStatus)? = null
        /** The manager's status as seen from [finishImport]. */
        var statusAtFinish: DbRestoreStatus? = null

        private var current: MutableList<String>? = null
        private var statementCount = 0

        val statements: List<String> get() = chunks.flatten()

        override fun prepare(fresh: Boolean) {
            prepareCalls++
            this.fresh = fresh
            if (oomOnPrepare) throw OutOfMemoryError("fake sink prepare OOM")
            // The manager treats the temp DB as present only when it is a real
            // non-empty file, and swapIn() requires one: materialise it.
            dbFile.parentFile?.mkdirs()
            dbFile.writeText("fake-db-$prepareCalls")
        }

        override fun beginChunk() {
            current = mutableListOf()
        }

        override fun statement(sql: String) {
            statementCount++
            if (oomAtStatement == statementCount) {
                throw OutOfMemoryError("fake sink statement OOM")
            }
            current!!.add(sql)
        }

        override fun endChunk() {
            current?.let { chunks.add(it) }
            current = null
        }

        override fun finishImport(): List<String> {
            finishCalls++
            statusAtFinish = statusProvider?.invoke()
            return summary
        }

        override fun abandon() {
            abandonCalls++
        }
    }

    /** Scripted one-shot: records the args, the dir and the DB bytes it saw. */
    private class FakeRunner(var exitCode: Int = 0) {
        var calls = 0
        var lastArgs: List<String>? = null
        var lastDir: File? = null
        var capturedDbBytes: ByteArray? = null
        /** Lines the one-shot pushes into the restore status. */
        var lines: List<String> = listOf("rebuild: torrent_search + file_search")
        /** Invoked at the top of [run], before anything is captured. */
        var probe: (() -> Unit)? = null
        val emittedLines = mutableListOf<String>()

        fun run(args: List<String>, dir: File, onLine: (String) -> Unit): Int {
            calls++
            lastArgs = args
            lastDir = dir
            probe?.invoke()
            val db = File(args[1])
            capturedDbBytes = if (db.isFile) db.readBytes() else null
            for (line in lines) {
                onLine(line)
                emittedLines.add(line)
            }
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
        bucket: String = "dht-backup",
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

    private fun workDir(): File = File(tmp.root, "crawler-work")

    private fun dataDir(): File = File(workDir(), "data")

    private fun tempDbFile(): File = File(dataDir(), "torrents.db.restore.tmp")

    private fun liveDbFile(): File = File(dataDir(), "torrents.db")

    private fun progressFile(): File = File(tmp.root, "backup/db_restore_progress.txt")

    private fun newManager(
        restorer: FakeRestorer,
        runner: FakeRunner,
        settings: BackupSettings = settings(),
        busy: Boolean = false,
        clock: () -> Long = { T0 },
        bookmarkFile: File = File(tmp.root, "db_bookmark.txt"),
        objects: List<Pair<String, String>> = emptyList(),
        executor: CapturingExecutor = CapturingExecutor(),
        stopThrows: Boolean = false,
        sinkTemplate: (FakeSink) -> Unit = {},
    ): Triple<DbRestoreManager, FakeFlags, File> {
        val flags = FakeFlags()
        sinks.clear()
        manager = DbRestoreManager(
            loadSettings = { settings },
            isBusy = { busy },
            clientFactory = { restorer },
            oneShot = { args, dir, onLine -> runner.run(args, dir, onLine) },
            sinkFactory = { file ->
                FakeSink(file).also {
                    it.statusProvider = { manager.status }
                    sinkTemplate(it)
                    sinks.add(it)
                }
            },
            workDir = { workDir() },
            tmpDir = { File(tmp.root, "tmp") },
            bookmarkFile = { bookmarkFile },
            progressFile = { progressFile() },
            stopCrawler = {
                flags.stopCount++
                if (stopThrows) throw IllegalStateException("stop failed")
            },
            startCrawlerIfEnabled = { flags.startCount++ },
            executor = executor,
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
        return Triple(manager, flags, bookmarkFile)
    }

    /** Counts the crawler stop/restart calls (the 60 s stop seam). */
    private class FakeFlags {
        var stopCount = 0
        var startCount = 0
    }

    private fun tempFiles(root: File): List<File> =
        root.listFiles()?.filter { it.name.startsWith("restore-") && it.name.endsWith(".sql") }
            ?: emptyList()

    private fun bodyFor(key: String): String = when (key) {
        "db/incremental_20231114_0-10.sql.gz" -> "INSERT OR IGNORE INTO path_prefixes VALUES(1);"
        "db/incremental_20231115_11-20.sql.gz" -> "INSERT OR IGNORE INTO torrents VALUES(2);"
        "db/incremental_20231116_21-30.sql.gz" -> "INSERT OR IGNORE INTO torrents VALUES(22);"
        else -> "INSERT OR IGNORE INTO torrents VALUES(1);"
    }

    @Test fun successPath_importsOneTransactionPerChunkThenRebuildsFtsThenSwaps() {
        val restorer = FakeRestorer()
        val runner = FakeRunner(exitCode = 0)
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            // Deliberately out of chain order in the listing.
            objects = listOf(
                "db/incremental_20231115_11-20.sql.gz" to bodyFor("db/incremental_20231115_11-20.sql.gz"),
                "db/incremental_20231114_0-10.sql.gz" to bodyFor("db/incremental_20231114_0-10.sql.gz"),
            ),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(10, 2))
        var liveDbExistedAtFtsCall: Boolean? = null
        runner.probe = { liveDbExistedAtFtsCall = liveDbFile().isFile }

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
        // One sink, created for the temp DB, fresh, one transaction per chunk,
        // statements split and trimmed (no terminating `;`).
        assertEquals(1, sinks.size)
        val sink = sinks.single()
        assertEquals(tempDbFile(), sink.dbFile)
        assertEquals(1, sink.prepareCalls)
        assertEquals(true, sink.fresh)
        assertEquals(
            listOf(
                listOf("INSERT OR IGNORE INTO path_prefixes VALUES(1)"),
                listOf("INSERT OR IGNORE INTO torrents VALUES(2)"),
            ),
            sink.chunks)
        assertEquals(1, sink.finishCalls)
        assertEquals(0, sink.abandonCalls)
        // The only native call is the FTS rebuild, on the TEMP DB, in the work dir.
        assertEquals(1, runner.calls)
        assertEquals(
            listOf("--rebuild-fts", tempDbFile().absolutePath),
            runner.lastArgs)
        assertEquals(workDir(), runner.lastDir)
        // It ran BEFORE the swap: the live DB did not exist yet, the temp DB did.
        assertEquals(false, liveDbExistedAtFtsCall)
        assertEquals("fake-db-1", String(runner.capturedDbBytes!!))
        // After the zero exit the temp DB replaced the live one.
        assertEquals("fake-db-1", liveDbFile().readText())
        assertFalse(tempDbFile().exists())
        // Progress marker is gone on success; the bookmark is reset.
        assertFalse(progressFile().exists())
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(bookmarkFile))
        // No concatenated dump file is ever produced.
        assertTrue(tempFiles(File(tmp.root, "tmp")).isEmpty())
        // Status carries the chain description, the exit code and both line sets.
        assertEquals(2, st.objectCount)
        assertEquals(
            listOf("db/incremental_20231114_0-10.sql.gz", "db/incremental_20231115_11-20.sql.gz"),
            st.objectKeys)
        assertEquals(2, st.objectsDone)
        assertEquals(0, st.exitCode)
        assertEquals(T0, st.lastSuccess)
        assertEquals(T0, st.lastAttempt)
        assertTrue(st.configured)
        assertTrue(st.message, st.message.contains("imported 2 object(s)"))
        assertTrue(st.message, st.message.contains("FTS rebuilt"))
        assertTrue(st.message, st.message.contains("chain starts at torrent id 0"))
        assertEquals(sink.summary + runner.lines, st.lines)
    }

    @Test fun ftsRebuildNonZero_failsBeforeTheSwapAndKeepsTempDbAndProgress() {
        val restorer = FakeRestorer()
        val runner = FakeRunner(exitCode = 3)
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to bodyFor("db/incremental_20231114_0-10.sql.gz"),
                "db/incremental_20231115_11-20.sql.gz" to bodyFor("db/incremental_20231115_11-20.sql.gz"),
            ),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertEquals(3, st.exitCode)
        assertTrue(st.message, st.message.contains("FTS rebuild exited with code 3"))
        // No swap: the live DB is untouched, the temp DB stays for the retry.
        assertFalse(liveDbFile().exists())
        assertEquals("fake-db-1", tempDbFile().readText())
        // Every chunk is imported and marked: a retry re-runs only the rebuild.
        assertEquals(RestoreProgress(st.objectKeys, 2), RestoreProgress.read(progressFile()))
        // Critical ordering: the failed restore must not touch the bookmark.
        assertEquals(DbBookmark(5, 1), DbBookmark.read(bookmarkFile))
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
        assertEquals(1, runner.calls)
        assertEquals(listOf("--rebuild-fts", tempDbFile().absolutePath), runner.lastArgs)
        assertNull(st.lastSuccess)
    }

    @Test fun chunkDownloadFailure_keepsTempDbAndProgressForTheResume() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to bodyFor("db/incremental_20231114_0-10.sql.gz"),
                "db/incremental_20231115_11-20.sql.gz" to bodyFor("db/incremental_20231115_11-20.sql.gz"),
                "db/incremental_20231116_21-30.sql.gz" to bodyFor("db/incremental_20231116_21-30.sql.gz"),
            ),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))
        restorer.failGetCall = 3

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("download of db/incremental_20231116_21-30.sql.gz"))
        assertTrue(st.message, st.message.contains("network down"))
        // Two chunks committed and marked; the third was never applied.
        assertEquals(2, sinks.single().chunks.size)
        assertEquals(
            RestoreProgress(
                listOf(
                    "db/incremental_20231114_0-10.sql.gz",
                    "db/incremental_20231115_11-20.sql.gz",
                    "db/incremental_20231116_21-30.sql.gz",
                ), 2),
            RestoreProgress.read(progressFile()))
        assertEquals("fake-db-1", tempDbFile().readText())
        assertEquals(0, runner.calls)
        assertEquals(DbBookmark(5, 1), DbBookmark.read(bookmarkFile))
        assertEquals(2, st.objectsDone)
        assertEquals(1, flags.startCount)
    }

    @Test fun retryResumesAtTheMarkerAndNeverReRequestsImportedChunks() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val keys = listOf(
            "db/incremental_20231114_0-10.sql.gz",
            "db/incremental_20231115_11-20.sql.gz",
            "db/incremental_20231116_21-30.sql.gz",
        )
        val (m, _, bookmarkFile) = newManager(
            restorer, runner,
            objects = keys.map { it to bodyFor(it) },
        )
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))
        restorer.statusProbe = { m.status }
        restorer.failGetCall = 3

        val first = m.runRestore()
        assertEquals(DbRestoreState.FAILED, first.state)
        assertEquals(listOf(0, 1, 2), restorer.getSnapshots.map { it.objectsDone })

        // Retry: the bucket is unchanged, so the marker's chain is a prefix.
        restorer.failGetCall = null
        restorer.getOrder.clear()
        val second = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, second.state)
        assertEquals(2, sinks.size)
        val resumed = sinks[1]
        assertEquals(1, resumed.prepareCalls)
        assertEquals(false, resumed.fresh)
        // Only the missing chunk is re-requested and replayed.
        assertEquals(listOf(keys[2]), restorer.getOrder)
        assertEquals(listOf(listOf("INSERT OR IGNORE INTO torrents VALUES(22)")), resumed.chunks)
        // Progress counting starts at the marker, not at zero.
        assertEquals(listOf(0, 1, 2, 2), restorer.getSnapshots.map { it.objectsDone })
        assertEquals("importing 3 of 3 object(s)", restorer.getSnapshots.last().message)
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(bookmarkFile))
        assertFalse(progressFile().exists())
    }

    @Test fun retryAfterAnFtsFailureRerunsOnlyTheRebuild() {
        val restorer = FakeRestorer()
        val runner = FakeRunner(exitCode = 1)
        val keys = listOf(
            "db/incremental_20231114_0-10.sql.gz",
            "db/incremental_20231115_11-20.sql.gz",
        )
        val (m, _, _) = newManager(restorer, runner, objects = keys.map { it to bodyFor(it) })

        val first = m.runRestore()
        assertEquals(DbRestoreState.FAILED, first.state)
        assertEquals(1, runner.calls)

        // Everything is imported and marked: the retry only rebuilds the FTS
        // indexes and swaps, it must not download a single chunk again.
        runner.exitCode = 0
        restorer.getOrder.clear()
        val second = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, second.state)
        assertEquals(0, restorer.getOrder.size)
        assertEquals(2, sinks.size)
        assertEquals(false, sinks[1].fresh)
        assertEquals(0, sinks[1].chunks.size)
        assertEquals(1, sinks[1].finishCalls)
        assertEquals(2, runner.calls)
        // The resume phase is visible in the status while the rebuild runs.
        assertEquals("all 2 object(s) imported; resuming at the FTS rebuild",
            sinks[1].statusAtFinish?.message)
        // The swapped-in DB is the one the resumed sink prepared (each FakeSink
        // counts its own prepare calls, so both runs write "fake-db-1").
        assertEquals("fake-db-1", liveDbFile().readText())
        assertFalse(progressFile().exists())
    }

    @Test fun progressWithADifferentChain_startsTheImportOver() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to bodyFor("db/incremental_20231114_0-10.sql.gz"),
                "db/incremental_20231115_11-20.sql.gz" to bodyFor("db/incremental_20231115_11-20.sql.gz"),
            ),
        )
        // A marker from a DIFFERENT chain plus a leftover temp DB.
        progressFile().parentFile?.mkdirs()
        RestoreProgress.write(progressFile(),
            RestoreProgress(listOf("db/incremental_20200101_0-10.sql.gz"), 1))
        dataDir().mkdirs()
        tempDbFile().writeText("stale-db")

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(1, sinks.size)
        assertEquals(true, sinks.single().fresh)
        assertEquals(2, sinks.single().chunks.size)
        assertEquals(
            listOf("db/incremental_20231114_0-10.sql.gz", "db/incremental_20231115_11-20.sql.gz"),
            restorer.getOrder)
    }

    @Test fun progressWithoutATempDb_startsTheImportOver() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val key = "db/incremental_20231114_0-10.sql.gz"
        val (m, _, _) = newManager(restorer, runner, objects = listOf(key to bodyFor(key)))
        progressFile().parentFile?.mkdirs()
        RestoreProgress.write(progressFile(), RestoreProgress(listOf(key), 1))

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(true, sinks.single().fresh)
        assertEquals(listOf(key), restorer.getOrder)
    }

    @Test fun progressFromAShorterChain_resumesTheLongerChainAtTheMarker() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val first = "db/incremental_20231114_0-10.sql.gz"
        val second = "db/incremental_20231115_11-20.sql.gz"
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf(first to bodyFor(first), second to bodyFor(second)),
        )
        // A marker written when the bucket held only the first object: backups
        // appended since make the chain LONGER, which still resumes.
        progressFile().parentFile?.mkdirs()
        RestoreProgress.write(progressFile(), RestoreProgress(listOf(first), 1))
        dataDir().mkdirs()
        tempDbFile().writeText("partial-db")

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        val sink = sinks.single()
        assertEquals(false, sink.fresh)
        // Only the chunk the marker had not reached is requested.
        assertEquals(listOf(second), restorer.getOrder)
        assertEquals(listOf(listOf("INSERT OR IGNORE INTO torrents VALUES(2)")), sink.chunks)
        assertEquals(DbBookmark.FIRST_RUN,
            DbBookmark.read(File(tmp.root, "db_bookmark.txt")))
    }

    @Test fun staleSqlDumpsAreReapedBeforeTheImport() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val key = "db/incremental_20231114_0-10.sql.gz"
        val (m, _, _) = newManager(restorer, runner, objects = listOf(key to bodyFor(key)))
        val tmpDir = File(tmp.root, "tmp")
        tmpDir.mkdirs()
        val stale = File(tmpDir, "restore-1700000000000.sql")
        stale.writeText("old dump")
        val unrelated = File(tmpDir, "keep.txt")
        unrelated.writeText("keep")

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun outOfMemoryErrorFromTheSink_landsFailedWithRunningFalse() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val exec = CapturingExecutor()
        val key = "db/incremental_20231114_0-10.sql.gz"
        val (m, flags, _) = newManager(
            restorer, runner,
            executor = exec,
            objects = listOf(key to bodyFor(key)),
            sinkTemplate = { it.oomAtStatement = 1 },
        )

        m.restoreNow()
        // The executor's runnable must not throw: the OOM is swallowed into a
        // FAILED status, otherwise running=true would wedge every crawler job.
        exec.last!!.run()

        val st = m.status
        assertEquals(DbRestoreState.FAILED, st.state)
        assertFalse(st.running)
        assertTrue(st.message, st.message.contains("restore failed: fake sink statement OOM"))
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
        assertEquals(0, runner.calls)
        // The wedged-RUNNING bug: a new restore must be accepted again.
        assertEquals(DbRestoreState.RUNNING, m.restoreNow().state)
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
        assertEquals(0, sinks.size)
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
        assertEquals(1, sinks.single().chunks.size)
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

    @Test fun corruptGzip_failsWithACorruptChunkMessageAndKeepsTheTempDb() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, flags, _) = newManager(restorer, runner)
        restorer.bodies["db/incremental_20231114_0-10.sql.gz"] =
            byteArrayOf(1, 2, 3) // not gzip
        restorer.listResult = S3ListResult.Success(200, listOf(
            S3ListedObject("db/incremental_20231114_0-10.sql.gz", 3L)))

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("corrupt gzip in chunk " +
            "db/incremental_20231114_0-10.sql.gz"))
        assertEquals(0, runner.calls)
        assertEquals(1, flags.startCount)
        // Nothing was committed, so the marker never advanced.
        assertFalse(progressFile().exists())
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

    @Test fun restoreNowAfterStopThenStartIsAccepted() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, flags, _) = newManager(restorer, runner)
        m.stop()
        // The restart path re-arms the manager: a restore requested after the
        // daemons are started again must be accepted, not refused forever.
        m.start()

        val st = m.restoreNow()

        assertEquals(DbRestoreState.RUNNING, st.state)
        assertTrue(st.message, st.message.contains("queued"))
        assertEquals(0, flags.stopCount)
    }

    @Test fun chainStartingAtNonZeroId_succeedsAndSurfacesChainStart() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val key = "db/incremental_20231114_5-10.sql.gz"
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            objects = listOf(key to "INSERT OR IGNORE INTO torrents VALUES(6);"),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))

        val st = m.runRestore()

        // With seeded baselines the chain legitimately starts at ANY id: a
        // single object starting at id 5 is a valid chain, so it restores.
        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertTrue(st.message, st.message.contains("chain starts at torrent id 5"))
        assertTrue(st.message, st.message.contains("pre-baseline history is not in the bucket"))
        assertEquals(listOf(key), restorer.getOrder)
        assertEquals(1, runner.calls)
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(bookmarkFile))
        assertEquals(listOf("INSERT OR IGNORE INTO torrents VALUES(6)"), sinks.single().statements)
    }

    @Test fun chainStartingAtNonZeroContiguous_succeeds() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf(
                "db/incremental_20231114_10-20.sql.gz" to "A",
                "db/incremental_20231115_21-30.sql.gz" to "B",
            ),
        )

        val st = m.runRestore()

        // Two contiguous objects starting at id 10 (10-20, 21-30): valid.
        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertTrue(st.message, st.message.contains("chain starts at torrent id 10"))
        assertEquals(
            listOf("db/incremental_20231114_10-20.sql.gz", "db/incremental_20231115_21-30.sql.gz"),
            restorer.getOrder)
        assertEquals(2, st.objectCount)
    }

    @Test fun chainWithGap_failsWithGapMessage() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to "A",
                "db/incremental_20231115_12-20.sql.gz" to "B",
            ),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(10, 2))

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("has a gap"))
        assertTrue(st.message, st.message.contains("db/incremental_20231114_0-10.sql.gz"))
        assertTrue(st.message, st.message.contains("ends at 10"))
        assertTrue(st.message, st.message.contains("db/incremental_20231115_12-20.sql.gz"))
        assertTrue(st.message, st.message.contains("starts at 12"))
        // No import, no one-shot, crawler restarted, bookmark untouched.
        assertEquals(0, restorer.getOrder.size)
        assertEquals(0, runner.calls)
        assertEquals(0, sinks.size)
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
        assertEquals(DbBookmark(10, 2), DbBookmark.read(bookmarkFile))
    }

    @Test fun overlappingChainAfterRebaseline_succeedsAndReplaysInSortedOrder() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            // The exact on-device scenario: after a restore reset the bookmark
            // to first run, the next backup re-dumped the whole restored DB
            // from 0, so the bucket holds an overlapping pair. Listing order
            // is deliberately the unsorted one.
            objects = listOf(
                "db/incremental_20261013_47-1390.sql.gz" to "INSERT OR IGNORE INTO torrents VALUES(48);",
                "db/incremental_20261013_0-1434.sql.gz" to "INSERT OR IGNORE INTO torrents VALUES(1);",
            ),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(10, 2))

        val st = m.runRestore()

        // Overlap is valid: the replay is idempotent (INSERT OR IGNORE), so
        // both objects are downloaded and replayed in sorted chain order.
        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(
            listOf("db/incremental_20261013_0-1434.sql.gz", "db/incremental_20261013_47-1390.sql.gz"),
            restorer.getOrder)
        assertEquals(2, st.objectCount)
        assertEquals(2, st.objectKeys.size)
        assertEquals(1, runner.calls)
        assertEquals(
            listOf(
                listOf("INSERT OR IGNORE INTO torrents VALUES(1)"),
                listOf("INSERT OR IGNORE INTO torrents VALUES(48)"),
            ),
            sinks.single().chunks)
        assertTrue(st.message, st.message.contains("chain starts at torrent id 0"))
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(bookmarkFile))
    }

    @Test fun mixedLegacyAndFromZeroChainSharingADate_hasNoFalseGap() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            // The real mixed bucket: a from-zero object and a legacy
            // (old seeded behavior) object share the SAME UTC date, with
            // the filler in between bridging the id range. Under the old
            // (date, start) adjacency check the shared date paired
            // [0-2000] with [34500594-...] and 34500594 > 2001 failed as a
            // FALSE gap, refusing a complete chain.
            objects = listOf(
                "db/incremental_20261013_34500594-34503000.sql.gz" to "C",
                "db/incremental_20261013_0-2000.sql.gz" to "A",
                "db/incremental_20261014_2001-34500593.sql.gz" to "B",
            ),
        )

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        // Id-major replay: 0 → 2001 → 34500594.
        assertEquals(
            listOf(
                "db/incremental_20261013_0-2000.sql.gz",
                "db/incremental_20261014_2001-34500593.sql.gz",
                "db/incremental_20261013_34500594-34503000.sql.gz",
            ),
            restorer.getOrder)
        assertEquals(3, st.objectCount)
        assertEquals(1, runner.calls)
        assertEquals(listOf("A", "B", "C"), sinks.single().statements)
        assertTrue(st.message, st.message.contains("chain starts at torrent id 0"))
    }

    @Test fun genuineGapAfterRunningMax_failsWithGapMessage() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            // 2001..4999 is covered by no object: a real gap, not overlap.
            objects = listOf(
                "db/incremental_20261013_0-2000.sql.gz" to "A",
                "db/incremental_20261014_5000-6000.sql.gz" to "B",
            ),
        )

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("has a gap"))
        assertTrue(st.message, st.message.contains("db/incremental_20261013_0-2000.sql.gz"))
        assertTrue(st.message, st.message.contains("ends at 2000"))
        assertTrue(st.message, st.message.contains("db/incremental_20261014_5000-6000.sql.gz"))
        assertTrue(st.message, st.message.contains("starts at 5000"))
        assertEquals(0, restorer.getOrder.size)
        assertEquals(0, runner.calls)
    }

    @Test fun redumpOfEarlierRangeOnALaterDate_replaysStartThenDateOrder() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            // [0-2000] was dumped twice (a later re-dump of the same range):
            // id-major order replays the OLDER dump first, so the
            // INSERT OR IGNORE replay is deterministically first-writer-wins.
            objects = listOf(
                "db/incremental_20261012_0-2000.sql.gz" to "B",
                "db/incremental_20261011_2001-4000.sql.gz" to "C",
                "db/incremental_20261010_0-2000.sql.gz" to "A",
            ),
        )

        val st = m.runRestore()

        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(
            listOf(
                "db/incremental_20261010_0-2000.sql.gz",
                "db/incremental_20261012_0-2000.sql.gz",
                "db/incremental_20261011_2001-4000.sql.gz",
            ),
            restorer.getOrder)
        assertEquals(3, st.objectCount)
        assertEquals(listOf("A", "B", "C"), sinks.single().statements)
        assertTrue(st.message, st.message.contains("chain starts at torrent id 0"))
    }

    @Test fun adjacentChain_succeedsWithoutOffByOne() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to "A",
                "db/incremental_20231115_11-20.sql.gz" to "B",
            ),
        )

        val st = m.runRestore()

        // Exactly adjacent (end 10, next start 11 == end + 1): a valid chain.
        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(
            listOf("db/incremental_20231114_0-10.sql.gz", "db/incremental_20231115_11-20.sql.gz"),
            restorer.getOrder)
        assertEquals(1, runner.calls)
    }

    @Test fun stopCrawlerThrows_failsAndStillRestartsCrawler() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val bookmarkFile = File(tmp.root, "db_bookmark.txt")
        val (m, flags, _) = newManager(
            restorer, runner,
            bookmarkFile = bookmarkFile,
            stopThrows = true,
            objects = listOf("db/incremental_20231114_0-10.sql.gz" to "X"),
        )
        DbBookmark.write(bookmarkFile, DbBookmark(5, 1))

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("restore failed"))
        assertTrue(st.message, st.message.contains("stop failed"))
        assertEquals(0, restorer.listCalls)
        assertEquals(0, runner.calls)
        assertEquals(0, sinks.size)
        // The stop may have partially completed: the crawler must be
        // restarted even though stopCrawler threw.
        assertEquals(1, flags.stopCount)
        assertEquals(1, flags.startCount)
        assertEquals(DbBookmark(5, 1), DbBookmark.read(bookmarkFile))
    }

    @Test fun outputLinesAreStreamedIntoTheStatusTail() {
        val restorer = FakeRestorer()
        val runner = FakeRunner()
        val key = "db/incremental_20231114_0-10.sql.gz"
        val (m, _, _) = newManager(restorer, runner, objects = listOf(key to "X"))
        val st = m.runRestore()
        assertEquals(DbRestoreState.SUCCESS, st.state)
        // The sink's summary first, then the --rebuild-fts output.
        assertEquals(
            listOf(
                "sqlite_version=fake (platform SQLite)",
                "wal_checkpoint(TRUNCATE): busy=0",
                "rebuild: torrent_search + file_search",
            ),
            st.lines)
    }

    @Test fun restoreCountsImportedChunks() {
        val restorer = FakeRestorer()
        val runner = FakeRunner(exitCode = 0)
        val exec = CapturingExecutor()
        val (m, _, _) = newManager(
            restorer, runner,
            executor = exec,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to "A",
                "db/incremental_20231115_11-20.sql.gz" to "B",
            ),
        )
        // Probe the manager between downloads; the runnable is driven on the
        // test thread so the RUNNING state set by restoreNow() is visible.
        restorer.statusProbe = { m.status }
        m.restoreNow()
        exec.last!!.run()
        val st = m.status

        assertEquals(DbRestoreState.SUCCESS, st.state)
        assertEquals(2, st.objectsDone)
        assertEquals(st.objectCount, st.objectsDone)
        // The probe runs at the TOP of each get(): the first chunk sees 0
        // imported, the second sees 1; the terminal status carries the final 2.
        assertEquals(listOf(0, 1), restorer.getSnapshots.map { it.objectsDone })
        assertEquals(listOf(0, 1, 2),
            restorer.getSnapshots.map { it.objectsDone } + st.objectsDone)
        for (snap in restorer.getSnapshots) {
            assertTrue(snap.running)
            assertEquals(DbRestoreState.RUNNING, snap.state)
            assertEquals(2, snap.objectCount)
            assertEquals(2, snap.objectKeys.size)
        }
        assertEquals(
            listOf("importing 1 of 2 object(s)", "importing 2 of 2 object(s)"),
            restorer.getSnapshots.map { it.message })
    }

    @Test fun restoreKeepsObjectsDoneOnFailedFtsRebuild() {
        val restorer = FakeRestorer()
        val runner = FakeRunner(exitCode = 3)
        val (m, _, _) = newManager(
            restorer, runner,
            objects = listOf(
                "db/incremental_20231114_0-10.sql.gz" to "A",
                "db/incremental_20231115_11-20.sql.gz" to "B",
            ),
        )
        restorer.statusProbe = { m.status }

        val st = m.runRestore()

        assertEquals(DbRestoreState.FAILED, st.state)
        assertTrue(st.message, st.message.contains("exited with code 3"))
        // The import completed before the rebuild failed: the terminal
        // status keeps the final count alongside the chain description.
        assertEquals(2, st.objectsDone)
        assertEquals(st.objectCount, st.objectsDone)
        assertEquals(listOf(0, 1), restorer.getSnapshots.map { it.objectsDone })
    }
}
