package com.torrent.app.backup

import android.util.Log

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.ConfigValidator
import com.torrent.app.config.CrawlerTaxonomy
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
 * Two guards keep a multi-hour run safe on a shared volume (plan 3, Stage 11):
 * after every chunk's COMMIT and marker write the sink gets a
 * [RestoreStatementSink.maybeCheckpoint] call, which folds the WAL back once it
 * reaches 1 GiB (so it is bounded at ~1.2 GB instead of the measured 2.1 GB
 * after 420 MB of SQL), and [RestoreDiskGuard] decides the free-space budget
 * from the LIST's compressed sizes before anything is downloaded and again at
 * every checkpoint — insufficient space fails cleanly with the temp DB and the
 * marker kept, so a retry after freeing space resumes.
 *
 * Resume: a FAILED restore keeps the temp DB and the marker, so the retry
 * continues where it stopped (a kill mid-chunk re-replays that one chunk,
 * which is safe and deterministic: every dump statement is `INSERT OR
 * IGNORE` and the replay is id-major first-writer-wins). A resumed run
 * REWINDS its start by [RESUME_REWIND_CHUNKS] chunks ([resumeStartIndex])
 * so the marker can never point past a rolled-back WAL tail: the bulk phase
 * runs at `synchronous=OFF` with `wal_autocheckpoint=0` (see
 * [RestorePragmas.bulkPhase]), so after a power loss SQLite's WAL recovery
 * can roll back frames the marker already counted — and re-importing
 * already-committed chunks is a deterministic no-op (all rows exist, so
 * `INSERT OR IGNORE` ignores them), while the rewind re-covers the realistic
 * loss window (kernel writeback flushes WAL writes within ~30 s, i.e. 1-2
 * chunks). A marker whose chain is not a prefix of the freshly listed chain
 * is discarded and the import starts over. A FAILED import closes the sink's
 * handle without deleting anything ([RestoreStatementSink.suspendImport]),
 * so the retry never races a stale handle left by the failed attempt.
 *
 * Category filter (the wizard's "categories to save"): the selection is read
 * ONCE per run from the PERSISTED config ([loadSaveCategories]). An EMPTY
 * selection means "save everything" and is the unfiltered import, byte for
 * byte — no class-index downloads and no statement inspection at all. When it
 * is non-empty, every dump object that has a `.idx.gz` class-index sidecar in
 * the listing gets that sidecar downloaded BEFORE its chunk's transaction
 * opens, parsed ([parseClassIndex]) and applied statement by statement
 * ([RestoreCategoryFilter]): a `torrents`/`torrent_files`/`torrent_class` row
 * whose torrent's category is not selected never reaches the sink, while
 * `path_prefixes` and anything unrecognised is always kept. A chain WITHOUT
 * sidecars (dumps older than the sidecar) simply imports unfiltered — one
 * informational status line, never a refusal. A sidecar that IS listed but
 * fails to download or parse fails the restore like a corrupt chunk (temp DB
 * and marker kept, so the retry resumes). The per-chunk decision depends only
 * on the selection read at the start of the run plus that chunk's sidecar, so
 * a kill/replay re-decides identically and the `INSERT OR IGNORE` rewind
 * replay stays idempotent.
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
    /**
     * The PERSISTED wizard "categories to save" selection
     * (`config.crawler.saveCategories`), read ONCE per run right after the
     * settings validation. An empty list means "save everything" and turns the
     * whole filter off: no class-index downloads, no statement parsing, exactly
     * the unfiltered import. Injected so the decision is plain-JUnit testable
     * and so the restore always honours what the crawler is configured to keep
     * (not an in-session wizard draft, not the last-known-good copy).
     */
    private val loadSaveCategories: () -> List<String>,
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
    /**
     * Free bytes on the volume that holds the temp DB (production:
     * `StatFs(filesDir).availableBytes`). Consulted by [RestoreDiskGuard]
     * before any download and again at every WAL checkpoint; injected so the
     * budget decision is plain-JUnit testable.
     */
    private val freeSpaceBytes: () -> Long,
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
        // The category selection of this run (the plan's D2/D4): read once,
        // here, from the PERSISTED config. A failure to read it is a real
        // failure — importing unfiltered on a transient store error would
        // resurrect torrents the operator chose not to keep.
        val selectedNames = try {
            loadSaveCategories()
        } catch (e: Exception) {
            return finish(DbRestoreState.FAILED,
                "failed to load save categories: ${e.message ?: e.javaClass.simpleName}")
        }
        // Names map 1:1 onto the crawler's category codes by INDEX (no offset:
        // `taxonomy.h:13-25` is the same order as [CrawlerTaxonomy.CATEGORIES]).
        val selectedCodes = CrawlerTaxonomy.CATEGORIES.withIndex()
            .filter { it.value in selectedNames }
            .map { it.index }
            .toSet()
        // D4: an empty selection is the crawler's own "save everything" and
        // must behave byte-for-byte like the unfiltered import — nothing below
        // runs (no sidecar GET, no statement parsing).
        val filterActive = selectedNames.isNotEmpty()
        if (filterActive) {
            val note = "category filter active: importing only " +
                selectedNames.joinToString(", ")
            pushLine(lines, note)
            Log.i(TAG, note)
        }
        // The sink is hoisted out of the try so a failed import can close its
        // handle: the temp DB and the resume marker stay (a retry continues),
        // but a stale handle must not survive into that retry.
        var sink: RestoreStatementSink? = null
        try {
            status = status.copy(message = "stopping crawler (up to 20 s)")
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
            // The raw listing keyed by object key: a range's class-index
            // sidecar PRESENCE is decided by key existence (a zero-entry index
            // is a valid tiny gzip, so size is never the signal), and the
            // sidecar's listed size feeds the disk-space guard below.
            val listedByKey: Map<String, Long?> = listed.associate { it.key to it.size }
            // Chain order: ID-MAJOR — sort by (start id, then date) so an
            // overlapping re-dump of the same range replays oldest-first;
            // skip keys that do not match the dump convention exactly, keys
            // outside the configured prefix, and zero-size objects (which
            // would restore an EMPTY database).
            val chain = listed.mapNotNull { o ->
                if (o.size == 0L) return@mapNotNull null
                val k = DbObjectKey.parse(o.key) ?: return@mapNotNull null
                if (k.prefix != settings.r2DbObjectPrefix) return@mapNotNull null
                // Carry the listed compressed size: the disk-space guard
                // estimates the remaining SQL volume from it (plan 3,
                // Stage 11) and the LIST has already paid for it.
                ChainEntry(o.key, k, o.size, k.sidecarKey, listedByKey[k.sidecarKey])
            }.sortedWith(compareBy({ it.meta.start }, { it.meta.date }))
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
            val first = chain.first().meta
            var runningMaxEnd = Long.MIN_VALUE
            var runningMaxEndKey: String? = null
            for (e in chain) {
                val key = e.key
                val obj = e.meta
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
            val keys = chain.map { it.key }
            // Compressed sizes aligned with [keys]: what the disk-space guard
            // sums over the objects that are still to be imported.
            // Sidecars are tiny, but they are downloaded too: counting them
            // keeps the estimate on the safe side ([RestoreDiskGuard] only
            // consumes this aligned list).
            val sizes = chain.map { entry ->
                entry.sizeBytes?.let { it + (entry.sidecarSizeBytes ?: 0L) }
            }

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
            val startIndex = resumeAt?.let { resumeStartIndex(it) } ?: 0
            // Triage-visible phase lines: `logcat -s DbRestore` must show where a
            // retry picks up without needing the dashboard or the marker file.
            Log.i(TAG, if (resumeAt == null)
                "fresh import of ${keys.size} chunk(s)"
            else
                "resuming at chunk ${startIndex + 1}/${keys.size} (marker $resumeAt, " +
                    "rewound ${resumeAt - startIndex} chunk(s) for WAL-durability safety)")

            // Disk budget BEFORE anything is downloaded or written (plan 3,
            // Stage 11): the import needs a whole second copy of the database on
            // a volume shared with transmission and FTP, and running out halfway
            // through a ~22 h replay would surface as SQLITE_FULL deep inside a
            // chunk. Estimated from the LIST's compressed sizes, from the
            // REWOUND start index (conservative: it counts the replayed chunks
            // again). Failing here keeps the temp DB and the marker, so a retry
            // after freeing space resumes instead of starting over.
            requireFreeSpace(sizes, startIndex)

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
            // Category-filter accounting for the two post-loop status lines.
            var skippedStatements = 0
            var missingIndexObjects = 0
            for (i in startIndex until keys.size) {
                val key = keys[i]
                val entry = chain[i]
                status = status.copy(message = "importing ${i + 1} of ${keys.size} object(s)")
                when (val g = client.get(key)) {
                    is S3GetResult.Success -> {
                        // Stream this chunk: decompress on the fly and hand one
                        // statement at a time to the sink, inside ONE transaction
                        // per chunk. endChunk() commits (or rolls the chunk back
                        // when a statement threw) — it must run on every path, so
                        // the connection is never left mid-transaction.
                        // Category filter: the range's class index is fetched
                        // BEFORE the chunk's transaction opens, so a sidecar
                        // download/parse failure never leaves a chunk
                        // half-applied (FAILED keeps the temp DB + marker, and
                        // the retry resumes).
                        val filter = if (filterActive && entry.sidecarKey in listedByKey) {
                            RestoreCategoryFilter(
                                selectedCodes,
                                parseSidecar(downloadSidecar(client, entry.sidecarKey),
                                    entry.sidecarKey))
                        } else {
                            // D5: a chain without sidecars imports unfiltered —
                            // informational only, never a refusal state.
                            if (filterActive) missingIndexObjects++
                            null
                        }
                        opened.beginChunk()
                        var nulDropped = 0
                        try {
                            try {
                                nulDropped = SqlStatementSplitter.split(
                                    GZIPInputStream(ByteArrayInputStream(g.body))) { sql ->
                                    // A skipped statement never reaches the sink,
                                    // so the chunk still commits as one
                                    // transaction (chunkFailed stays false).
                                    if (filter == null || filter.keep(sql)) {
                                        opened.statement(sql)
                                    } else {
                                        skippedStatements++
                                    }
                                }.nulDropped
                            } catch (e: IOException) {
                                throw IllegalStateException("corrupt gzip in chunk $key: " +
                                    (e.message ?: e.javaClass.simpleName))
                            }
                        } finally {
                            opened.endChunk()
                        }
                        // Embedded U+0000 is stripped by the splitter (execSQL
                        // would truncate the statement at it): make the count
                        // visible in both the status lines and logcat.
                        if (nulDropped > 0) {
                            val note = "chunk ${i + 1}/${keys.size}: dropped $nulDropped " +
                                "embedded NUL character(s) (C-string truncation guard)"
                            pushLine(lines, note)
                            Log.i(TAG, note)
                        }
                        // The chunk is committed: advance the resume marker
                        // BEFORE the next download, so a kill here re-imports at
                        // most this chunk's successor.
                        RestoreProgress.write(progressFile(), RestoreProgress(keys, i + 1))
                        // Copy-on-write progress: single writer (this
                        // executor thread), like pushLine below.
                        status = status.copy(objectsDone = i + 1)
                        Log.i(TAG, "chunk ${i + 1}/${keys.size} imported")
                        // Bounded WAL (plan 3, Stage 11): fold it back once it
                        // reaches 1 GiB. Strictly AFTER the commit and AFTER the
                        // marker write — a kill mid-checkpoint loses nothing
                        // (WAL frames persist and SQLite replays them; the
                        // marker never points past committed data). Only a
                        // non-null line means a checkpoint ran, and only then is
                        // the disk budget re-decided: the run lasts hours, so
                        // other writers on the volume can eat the headroom the
                        // pre-flight check saw.
                        val checkpointLine = opened.maybeCheckpoint()
                        if (checkpointLine != null) {
                            pushLine(lines, checkpointLine)
                            Log.i(TAG, checkpointLine)
                            requireFreeSpace(sizes, i + 1)
                        }
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

            if (filterActive) {
                // D5: exactly ONE informational line when some object had no
                // sidecar — no refusal state, no change to the outcome.
                if (missingIndexObjects > 0) {
                    val note = "class index: missing for $missingIndexObjects of " +
                        "${keys.size} object(s); imported unfiltered"
                    pushLine(lines, note)
                    Log.i(TAG, note)
                }
                val summary = "category filter: skipped $skippedStatements " +
                    "statement(s) for unselected categories"
                pushLine(lines, summary)
                Log.i(TAG, summary)
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

    /**
     * Downloads one range's class-index sidecar with the same `S3GetResult`
     * handling (and therefore the same FAILED semantics) as the SQL object
     * itself: a transport or R2 error here is a real restore failure, never a
     * silent "import unfiltered".
     */
    private fun downloadSidecar(client: S3DbRestorer, sidecarKey: String): ByteArray =
        when (val g = client.get(sidecarKey)) {
            is S3GetResult.Success -> g.body
            is S3GetResult.ClientError ->
                throw IllegalStateException("R2 rejected the download of $sidecarKey " +
                    "(HTTP ${g.statusCode})")
            is S3GetResult.ServerError ->
                throw IllegalStateException("R2 server error downloading $sidecarKey " +
                    "(HTTP ${g.statusCode} after ${g.attempts} attempts)")
            is S3GetResult.Failure ->
                throw IllegalStateException("download of $sidecarKey failed: " +
                    "${g.cause.message ?: g.cause.javaClass.simpleName}")
        }

    /**
     * Parses a downloaded class index, mapping the parser's two failure modes
     * onto the restore's existing `IllegalStateException` → FAILED path: a
     * malformed line and a corrupt gzip are both transport/corruption
     * failures, not new refusal states.
     */
    private fun parseSidecar(gzBytes: ByteArray, sidecarKey: String): Map<Long, Int> =
        try {
            parseClassIndex(gzBytes)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("malformed class index in $sidecarKey: " +
                (e.message ?: e.javaClass.simpleName))
        } catch (e: IOException) {
            throw IllegalStateException("corrupt gzip in class index $sidecarKey: " +
                (e.message ?: e.javaClass.simpleName))
        }

    /**
     * Enforces the disk-space budget ([RestoreDiskGuard]) for the objects from
     * [fromIndex] on: logs the budget when the rest of the chain fits and throws
     * a status-message exception when it does not. The caller's `catch` turns
     * that into a clean [DbRestoreState.FAILED] that keeps the temp DB and the
     * resume marker, so freeing space and retrying continues the import.
     */
    private fun requireFreeSpace(sizes: List<Long?>, fromIndex: Int) {
        val decision = RestoreDiskGuard.check(
            remainingCompressedBytes = RestoreDiskGuard.remainingCompressedBytes(sizes, fromIndex),
            freeSpaceBytes = freeSpaceBytes(),
        )
        if (decision.sufficient) {
            Log.i(TAG, "disk budget: need ~" +
                "${RestoreDiskGuard.gigabytes(decision.requiredBytes)}, have " +
                "${RestoreDiskGuard.gigabytes(decision.freeBytes)} free for " +
                "${maxOf(0, sizes.size - fromIndex)} remaining object(s)")
            return
        }
        val message = RestoreDiskGuard.failureMessage(decision)
        Log.i(TAG, message)
        throw IllegalStateException(message)
    }

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

    /**
     * One object of the restore chain: its key, the parsed key metadata and the
     * compressed size the LIST reported. `null` (R2 omitted Content-Length)
     * counts as 0 for the disk-space guard, the same way the zero-size filter
     * above already treats it.
     */
    /**
     * One object of the restore chain: its key, the parsed key metadata, the
     * compressed size the LIST reported and the class-index sidecar of the same
     * range. `null` (R2 omitted Content-Length) counts as 0 for the disk-space
     * guard, the same way the zero-size filter above already treats it.
     *
     * [sidecarKey] is always derived from the key metadata (never parsed out of
     * the listing), and [sidecarSizeBytes] is `null` when the sidecar is absent
     * from the listing — absence is decided by KEY, never by size (a zero-entry
     * index is a valid tiny gzip).
     */
    private data class ChainEntry(
        val key: String,
        val meta: DbObjectKey,
        val sizeBytes: Long?,
        val sidecarKey: String,
        val sidecarSizeBytes: Long?,
    )

    companion object {
        /** Ring-buffer cap for the one-shot output tail. */
        internal const val MAX_LINES = 2000
        /** logcat tag of the restore flow — the sink logs under the same tag. */
        private const val TAG = "DbRestore"

        /**
         * How many already-imported chunks a RESUMED run replays before
         * continuing at the marker: the bulk phase's `synchronous=OFF` +
         * `wal_autocheckpoint=0` means a power loss can leave the marker ahead
         * of WAL frames SQLite rolls back at the next open, and 3 chunks covers
         * the realistic window (writeback flushes within ~30 s, i.e. 1-2
         * chunks). Replaying them is a no-op — every dump statement is
         * `INSERT OR IGNORE` and the replay order is unchanged.
         */
        internal const val RESUME_REWIND_CHUNKS = 3

        /** The chunk index a resumed run starts at: the marker, rewound and clamped at 0. */
        internal fun resumeStartIndex(markerNextIndex: Int): Int =
            maxOf(0, markerNextIndex - RESUME_REWIND_CHUNKS)
    }
}
