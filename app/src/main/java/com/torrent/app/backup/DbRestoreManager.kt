package com.torrent.app.backup

import android.util.Log

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigValidator
import com.torrent.app.s3.S3GetResult
import com.torrent.app.s3.S3ListResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.zip.GZIPInputStream

/**
 * One listing + download of the incremental DB-dump chain (plan 2,
 * Stage 4 restore). A thin seam over [com.torrent.app.s3.S3Client]
 * (`list` + `get`) so unit tests can fake the cloud; adapt a real
 * client with a lambda (the client itself is unchanged).
 */
interface S3DbRestorer {
    fun list(prefix: String): S3ListResult
    fun get(objectKey: String): S3GetResult
}

/** Outcome states of the last DB restore attempt. */
enum class DbRestoreState {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
    REFUSED_BUSY,
    REFUSED_RUNNING,
    NOT_CONFIGURED,
}

/**
 * Thread-safe snapshot of the DB restore manager for the dashboard
 * (`GET /api/crawler/restore`). [objectCount]/[objectKeys] describe
 * the last restore's chain (kept visible after later failures);
 * [lines] is the tail of the sink's summary plus the `--rebuild-fts`
 * one-shot's output (a ring buffer capped at
 * [DbRestoreManager.MAX_LINES]); [exitCode] is the one-shot's exit code
 * (-1 when it never ran).
 */
data class DbRestoreStatus(
    val state: DbRestoreState = DbRestoreState.IDLE,
    val running: Boolean = false,
    val message: String = "",
    val lastAttempt: Long? = null,
    val lastSuccess: Long? = null,
    val objectCount: Int? = null,
    /** Objects of [objectKeys] imported so far (null before the count
     *  is published; monotonic 0..objectCount while RUNNING). */
    val objectsDone: Int? = null,
    val objectKeys: List<String> = emptyList(),
    val exitCode: Int? = null,
    val lines: List<String> = emptyList(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val configured: Boolean = false,
)

/**
 * Restores the dht_crawler's database from the incremental SQL-dump
 * chain in the R2 DB bucket (plan 2, Stage 4; rewritten by plan 3,
 * Stage 3 into a resumable, chunk-by-chunk Kotlin importer). An
 * explicit, dashboard-triggered one-shot — there is no schedule.
 *
 * Flow of [restoreNow] (stop-first, on [executor], never a request
 * thread): load settings (R2 endpoint + creds + DB bucket + prefix
 * must be present; [ConfigValidator.validateDbBackup] is run with the
 * enabled flag forced so the shared checks apply) →
 * [DbRestoreState.NOT_CONFIGURED]; [busy] (an apply/maintenance/
 * backup job is in flight) → [DbRestoreState.REFUSED_BUSY]; a
 * restore already in flight →
 * [DbRestoreState.REFUSED_RUNNING]; otherwise stop the crawler, reap
 * the stale `restore-*.sql` dumps the old concat-then-replay flow left
 * behind ([RestoreFileOps.reapStaleSqlDumps]), LIST the `db/` prefix,
 * build the chain in id-major order (sorted by start id, then date;
 * malformed keys are skipped; a gap fails), and then stream every object
 * into a FRESH temp DB (`<workDir>/data/torrents.db.restore.tmp`) ONE
 * CHUNK AT A TIME: each object's gzip body is decompressed on the fly
 * through [SqlStatementSplitter] into a [RestoreStatementSink] and
 * committed as its own transaction, with the resume marker
 * ([RestoreProgress] in [progressFile]) advanced after every commit.
 * Nothing is ever concatenated into a dump file and no whole chunk is
 * ever gunzipped into heap — those unbounded steps are what got the app
 * process killed (kernel-OOM) during the 36.7 GB replay.
 *
 * After the last chunk, `finishImport()` checkpoints and closes the temp
 * DB, and the FTS5 tables, the triggers and the index `rebuild` for both
 * search indexes run in the crawler's OWN native one-shot
 * (`--rebuild-fts <temp db>`, bundled SQLite 3.49.1) on the TEMP DB.
 * Only when that exits 0 is the temp DB swapped over the live one
 * ([RestoreFileOps.swapIn]). The native dump-replay tool is never
 * executed by the app any more; it stays in the binary as a manual
 * recovery tool.
 *
 * Resume: a FAILED restore keeps the temp DB and the marker, so the retry
 * imports only the missing chunks (a kill mid-chunk re-replays that one
 * chunk, which is safe and deterministic: every dump statement is
 * `INSERT OR IGNORE` and the replay is id-major first-writer-wins). A
 * marker whose chain is not a prefix of the freshly listed chain is
 * discarded and the import starts over. A FAILED import closes the sink's
 * handle without deleting anything ([RestoreStatementSink.suspendImport]),
 * so the retry never races a stale handle left by the failed attempt.
 *
 * Bookmark ordering (the critical invariant): the app-side [DbBookmark] is
 * reset to [DbBookmark.FIRST_RUN] ONLY after the swap succeeded — a failed
 * restore leaves the bookmark alone so the next upload continues the chain.
 * The crawler is restarted (when enabled) after EVERY outcome that attempted
 * to stop it, so a failed restore — even a throwing stopCrawler — never
 * leaves it dead.
 *
 * Both [restoreNow] and [runRestore] catch [Throwable], not just
 * [Exception]: an [OutOfMemoryError] must land in a FAILED status with
 * `running = false`, otherwise the wedged RUNNING flag blocks every crawler
 * job and the wizard's restore gate.
 *
 * All collaborators are constructor-injected (plain-JUnit
 * testable); [runRestore] is package-internal so tests can run it
 * synchronously on the test thread.
 */
class DbRestoreManager(
    private val loadSettings: () -> BackupSettings,
    private val isBusy: () -> Boolean,
    private val clientFactory: (BackupSettings) -> S3DbRestorer,
    /**
     * Runs one native one-shot in the crawler work dir. The restore uses it
     * solely for `--rebuild-fts <temp db>`; the dump replay itself is Kotlin.
     */
    private val oneShot: (List<String>, File, (String) -> Unit) -> Int,
    /** Creates the statement sink for the temp DB (production: [SqliteRestoreSink]). */
    private val sinkFactory: (File) -> RestoreStatementSink,
    private val workDir: () -> File,
    private val tmpDir: () -> File,
    private val bookmarkFile: () -> File,
    /** The per-chunk resume marker (production: `filesDir/backup/db_restore_progress.txt`). */
    private val progressFile: () -> File,
    private val stopCrawler: () -> Unit,
    private val startCrawlerIfEnabled: () -> Unit,
    private val executor: Executor,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    var status: DbRestoreStatus = DbRestoreStatus()
        private set

    private val lock = Any()
    @Volatile
    private var inFlight = false
    @Volatile
    private var stopped = false

    /**
     * Queues a restore on [executor] and returns the resulting
     * (optimistic) [status]. Refused when a restore is in flight,
     * [isBusy] is set, or [stop] has run. Poll [status] for the
     * outcome.
     */
    fun restoreNow(): DbRestoreStatus {
        val accepted = synchronized(lock) {
            if (stopped) {
                status = status.copy(state = DbRestoreState.FAILED,
                    message = "refused: service is shutting down")
                false
            } else if (inFlight) {
                status = status.copy(state = DbRestoreState.REFUSED_RUNNING,
                    message = "refused: a restore is already running")
                false
            } else if (isBusy()) {
                status = status.copy(state = DbRestoreState.REFUSED_BUSY,
                    message = "refused: an apply/maintenance/backup job is running")
                false
            } else {
                inFlight = true
                status = status.copy(state = DbRestoreState.RUNNING,
                    running = true, exitCode = null, lines = emptyList(),
                    startedAt = clock(), finishedAt = null,
                    message = "db restore queued")
                true
            }
        }
        if (!accepted) return status
        try {
            executor.execute {
                try {
                    runRestore()
                } catch (t: Throwable) {
                    // A restore must never take the control executor down, and
                    // an OutOfMemoryError must not wedge running = true: that
                    // flag blocks every crawler job and the wizard's gate.
                    finish(
                        DbRestoreState.FAILED,
                        "restore failed: ${t.message ?: t.javaClass.simpleName}",
                        exitCode = -1,
                    )
                } finally {
                    inFlight = false
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlight = false
            status = status.copy(state = DbRestoreState.FAILED, running = false,
                message = "refused: service is shutting down")
        }
        return status
    }

    /**
     * Runs one full restore synchronously (settings → stop → reap →
     * list → per-chunk import into the temp DB → native `--rebuild-fts`
     * → swap → bookmark → restart). Called on [executor] by [restoreNow];
     * package-internal so unit tests can run it on the test thread.
     */
    internal fun runRestore(): DbRestoreStatus {
        val lines = ArrayDeque<String>()
        val settings = try {
            loadSettings()
        } catch (e: Exception) {
            return finish(DbRestoreState.FAILED,
                "failed to load settings: ${e.message ?: e.javaClass.simpleName}",
                configured = false)
        }
        // Restore requires the R2 endpoint + creds + DB bucket + prefix
        // regardless of the upload toggle: run the shared validator with
        // the enabled flag forced so its checks all apply.
        val errors = ConfigValidator.validateDbBackup(
            settings.copy(dbBackupEnabled = true)).errors
        if (errors.isNotEmpty()) {
            return finish(DbRestoreState.NOT_CONFIGURED,
                "not configured: " + errors.joinToString("; "),
                configured = false)
        }
        // The sink is hoisted out of the try so a failed import can close its
        // handle: the temp DB and the resume marker stay (a retry continues),
        // but a stale handle must not survive into that retry.
        var sink: RestoreStatementSink? = null
        try {
            status = status.copy(message = "stopping crawler (up to 60 s)")
            stopCrawler()

            // Housekeeping before anything else: the previous flow concatenated
            // the whole chain into restore-*.sql files in tmpDir and never
            // reaped them (two ~33 GB files on-device). Nothing reads them now.
            RestoreFileOps.reapStaleSqlDumps(tmpDir())

            status = status.copy(message = "listing ${prefixDir(settings)} objects")
            val listed = when (val r = clientFactory(settings).list(prefixDir(settings))) {
                is S3ListResult.Success -> r.objects
                is S3ListResult.Capped ->
                    throw IllegalStateException("listing was capped at the page limit " +
                        "(${r.objects.size} objects so far)")
                is S3ListResult.ClientError ->
                    throw IllegalStateException("R2 rejected the listing (HTTP ${r.statusCode})")
                is S3ListResult.ServerError ->
                    throw IllegalStateException("R2 server error (HTTP ${r.statusCode} " +
                        "after ${r.attempts} attempts)")
                is S3ListResult.Failure ->
                    throw IllegalStateException("listing failed: " +
                        "${r.cause.message ?: r.cause.javaClass.simpleName}")
            }
            // Chain order: ID-MAJOR — sort by (start id, then date) so an
            // overlapping re-dump of the same range replays oldest-first;
            // skip keys that do not match the dump convention exactly, keys
            // outside the configured prefix, and zero-size objects (which
            // would restore an EMPTY database).
            val chain = listed.mapNotNull { o ->
                if (o.size == 0L) return@mapNotNull null
                val k = DbObjectKey.parse(o.key) ?: return@mapNotNull null
                if (k.prefix != settings.r2DbObjectPrefix) return@mapNotNull null
                o.key to k
            }.sortedWith(compareBy({ it.second.start }, { it.second.date }))
            if (chain.isEmpty()) {
                throw IllegalStateException("nothing to restore: no parseable dump objects " +
                    "under ${prefixDir(settings)}")
            }
            // Chain coherence: the chain legitimately starts at ANY id —
            // the first object's start defines the chain start (history
            // before it is deliberately not in the bucket) — so there is no
            // "missing baseline" failure.
            //
            // A real bucket can hold a MIXED chain of two shapes that share
            // a UTC date: legacy objects from the old seeded baseline
            // (starting at e.g. 34,500,594) and from-zero objects produced
            // after a restore reset the bookmark to first run (starting at
            // 0). Sorted id-major those two shapes interleave instead of
            // stacking by date — e.g. `[0-2000]` is followed by
            // `[34501812-34503000]` on a shared date — so the coherence
            // check is a RUNNING-MAX-END pass, never an adjacency check: an
            // object may start anywhere at or before `runningMaxEnd + 1`,
            // contiguous OR overlapping. Overlap is legal because every dump
            // statement is `INSERT OR IGNORE` and the id-major order makes
            // the replay first-writer-wins deterministically. A TRUE gap —
            // `start > runningMaxEnd + 1`, i.e. no earlier object covers the
            // ids right below it — means the bucket is missing data and
            // restoring it would silently drop rows; fail that case.
            val first = chain.first().second
            var runningMaxEnd = Long.MIN_VALUE
            var runningMaxEndKey: String? = null
            for ((key, obj) in chain) {
                if (runningMaxEnd != Long.MIN_VALUE && obj.start - runningMaxEnd > 1L) {
                    throw IllegalStateException(
                        "backup chain has a gap: object $runningMaxEndKey ends at " +
                            "$runningMaxEnd but next object $key starts at ${obj.start}")
                }
                if (obj.end > runningMaxEnd) {
                    runningMaxEnd = obj.end
                    runningMaxEndKey = key
                }
            }
            val keys = chain.map { it.first }

            // The import target: a temp DB next to the live one (same name the
            // native replay used), so the final swap is a rename in one dir.
            val work = workDir()
            val tmpDb = tempDb(work)
            // Resume only when the marker, the temp DB and the freshly listed
            // chain all agree: the marker's chain must be an exact PREFIX of the
            // new chain (a longer chain — backups appended since — is fine).
            // The marker must describe a PREFIX of the freshly listed chain: an
            // equal chain (a retry) and a longer chain (backups appended since)
            // both resume; anything else starts over.
            val progress = RestoreProgress.read(progressFile())
            val resumeAt = if (progress != null &&
                progress.keys.size <= keys.size &&
                keys.subList(0, progress.keys.size) == progress.keys &&
                RestoreFileOps.targetDbExists(tmpDb)
            ) progress.nextIndex else null
            val startIndex = resumeAt ?: 0
            // Triage-visible phase lines: `logcat -s DbRestore` must show where a
            // retry picks up without needing the dashboard or the marker file.
            Log.i(TAG, if (resumeAt == null)
                "fresh import of ${keys.size} chunk(s)"
            else
                "resuming at chunk ${startIndex + 1}/${keys.size} (${startIndex} imported)")

            val opened = sinkFactory(tmpDb).also { sink = it }
            if (resumeAt != null) {
                opened.prepare(fresh = false)
            } else {
                // A fresh import must not inherit a half-imported DB or its
                // WAL, and a stale marker must not outlive the DB it described.
                tmpDb.delete()
                RestoreFileOps.deleteDbSidecars(tmpDb)
                progressFile().delete()
                opened.prepare(fresh = true)
            }
            status = status.copy(objectCount = keys.size, objectKeys = keys,
                objectsDone = startIndex,
                message = when {
                    resumeAt == null -> "importing 1 of ${keys.size} object(s)"
                    resumeAt >= keys.size ->
                        "all ${keys.size} object(s) imported; resuming at the FTS rebuild"
                    else -> "resuming at chunk ${startIndex + 1} of ${keys.size}"
                })

            val client = clientFactory(settings)
            for (i in startIndex until keys.size) {
                val key = keys[i]
                status = status.copy(message = "importing ${i + 1} of ${keys.size} object(s)")
                when (val g = client.get(key)) {
                    is S3GetResult.Success -> {
                        // Stream this chunk: decompress on the fly and hand one
                        // statement at a time to the sink, inside ONE transaction
                        // per chunk. endChunk() commits (or rolls the chunk back
                        // when a statement threw) — it must run on every path, so
                        // the connection is never left mid-transaction.
                        opened.beginChunk()
                        try {
                            try {
                                SqlStatementSplitter.split(
                                    GZIPInputStream(ByteArrayInputStream(g.body))) { sql ->
                                    opened.statement(sql)
                                }
                            } catch (e: IOException) {
                                throw IllegalStateException("corrupt gzip in chunk $key: " +
                                    (e.message ?: e.javaClass.simpleName))
                            }
                        } finally {
                            opened.endChunk()
                        }
                        // The chunk is committed: advance the resume marker
                        // BEFORE the next download, so a kill here re-imports at
                        // most this chunk's successor.
                        RestoreProgress.write(progressFile(), RestoreProgress(keys, i + 1))
                        // Copy-on-write progress: single writer (this
                        // executor thread), like pushLine below.
                        status = status.copy(objectsDone = i + 1)
                        Log.i(TAG, "chunk ${i + 1}/${keys.size} imported")
                    }
                    is S3GetResult.ClientError ->
                        throw IllegalStateException("R2 rejected the download of $key " +
                            "(HTTP ${g.statusCode})")
                    is S3GetResult.ServerError ->
                        throw IllegalStateException("R2 server error downloading $key " +
                            "(HTTP ${g.statusCode} after ${g.attempts} attempts)")
                    is S3GetResult.Failure ->
                        throw IllegalStateException("download of $key failed: " +
                            "${g.cause.message ?: g.cause.javaClass.simpleName}")
                }
            }

            // Checkpoint + close the temp DB; its summary lines are part of the
            // restore status (the sink logs them under the DbRestore tag too).
            val summary = opened.finishImport()
            for (line in summary) pushLine(lines, line)

            // FTS5 tables, triggers and both index rebuilds run in the crawler's
            // own SQLite, on the TEMP DB, before it is swapped in.
            status = status.copy(message = "running --rebuild-fts")
            val ftsExit = oneShot(
                listOf("--rebuild-fts", tmpDb.absolutePath), work) { line ->
                pushLine(lines, line)
            }
            if (ftsExit != 0) {
                // No silent degradation: the FTS indexes are part of a usable
                // database. Everything is imported and marked, so a retry re-runs
                // only the rebuild; repeated identical failures mean the temp DB
                // is corrupt (manual recovery: clear app storage, or the
                // crawler's own dump-replay tool).
                return finish(
                    DbRestoreState.FAILED,
                    "FTS rebuild exited with code $ftsExit (bookmark unchanged)",
                    exitCode = ftsExit,
                )
            }
            RestoreFileOps.swapIn(tmpDb, liveDb(work))
            Log.i(TAG, "swapped restored db into ${liveDb(work).path}")
            // Only a successful restore resets the cursor: the next
            // upload then re-dumps the whole (restored) database.
            DbBookmark.write(bookmarkFile(), DbBookmark.FIRST_RUN)
            progressFile().delete()
            return finish(
                DbRestoreState.SUCCESS,
                "imported ${keys.size} object(s); chain starts at torrent id " +
                    "${first.start} (pre-baseline history is not in the " +
                    "bucket); FTS rebuilt; bookmark reset to first run",
                exitCode = ftsExit,
                lastSuccess = clock(),
            )
        } catch (t: Throwable) {
            // Throwable, not Exception: an OutOfMemoryError must still leave a
            // FAILED status (the temp DB + progress marker are kept so the retry
            // resumes; nothing is deleted here).
            //
            // Closing the handle is NOT deleting anything: suspendImport() leaves
            // the temp DB and the marker intact, so the retry resumes — it only
            // guarantees the retry never opens a second handle on the same file
            // while a stale one (possibly still inside a transaction, if
            // endChunk() threw) holds a write lock.
            sink?.suspendImport()
            return finish(DbRestoreState.FAILED,
                "restore failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            // Restart the crawler if it is enabled (and the wifi gate
            // allows) regardless of the outcome — the same ceremony as
            // CrawlerMaintenance.runAction. The try block begins with the
            // stopCrawler() attempt, so by here the crawler is stopped or
            // partially stopped (stopCrawler threw) and must be brought
            // back; the supervisor's start() is idempotent, so a redundant
            // call is harmless.
            startCrawlerIfEnabled()
        }
    }

    /**
     * Marks the manager shut down; further [restoreNow] calls are refused. The
     * flag is cleared again by [start] — the service instance survives a
     * graceful stop, and the refusal only has to fence the window between this
     * stop and the restart (a restore accepted mid-teardown would run against
     * the crawler being SIGTERMed).
     */
    fun stop() { synchronized(lock) { stopped = true } }

    /**
     * Re-arms the manager after a [stop]: called from DaemonService's restart
     * path (onStartCommand), so a restore requested after the daemons are
     * started again is accepted instead of being refused forever. This manager
     * owns no executor, so there is nothing else to rebuild.
     */
    fun start() { synchronized(lock) { stopped = false } }

    private fun prefixDir(settings: BackupSettings): String = settings.r2DbObjectPrefix + "/"

    /** The database the crawler serves from: `<workDir>/data/torrents.db`. */
    private fun liveDb(workDir: File): File = File(File(workDir, "data"), "torrents.db")

    /** The import target, mirroring the native path (`restore-database.patch:45`). */
    private fun tempDb(workDir: File): File =
        File(File(workDir, "data"), "torrents.db.restore.tmp")

    private fun pushLine(lines: ArrayDeque<String>, line: String) {
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            status = status.copy(lines = lines.toList())
        }
    }

    private fun finish(
        state: DbRestoreState,
        message: String,
        exitCode: Int? = null,
        lastSuccess: Long? = null,
        configured: Boolean = true,
    ): DbRestoreStatus {
        val st = DbRestoreStatus(
            state = state,
            running = false,
            message = message,
            lastAttempt = if (state != DbRestoreState.NOT_CONFIGURED) clock() else status.lastAttempt,
            lastSuccess = lastSuccess ?: status.lastSuccess,
            objectCount = status.objectCount,
            objectsDone = status.objectsDone,
            objectKeys = status.objectKeys,
            exitCode = exitCode ?: status.exitCode,
            lines = status.lines,
            startedAt = status.startedAt,
            finishedAt = clock(),
            configured = configured,
        )
        status = st
        return st
    }

    companion object {
        /** Ring-buffer cap for the one-shot output tail. */
        internal const val MAX_LINES = 2000
        /** logcat tag of the restore flow — the sink logs under the same tag. */
        private const val TAG = "DbRestore"
    }
}
