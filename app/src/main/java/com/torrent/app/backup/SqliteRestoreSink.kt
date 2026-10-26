package com.torrent.app.backup

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The production [RestoreStatementSink] (plan 3, Stage 2): replays dump
 * statements into a FRESH SQLite database with the platform
 * `android.database.sqlite.SQLiteDatabase` (no Room, no `SQLiteOpenHelper`,
 * no new dependency) and is tuned to keep the import memory-bounded — the
 * reason the Kotlin importer exists at all.
 *
 * What bounds memory: the dump is streamed (one statement in heap at a time,
 * guarded at 8 MB by [SqlStatementSplitter]), the write-ahead log is used, and
 * `cache_size` is pinned to 64 MiB so SQLite's page cache cannot grow with the
 * ~36 GB replay.
 *
 * What makes it fast: [prepare] with `fresh = true` creates ONLY the five base
 * tables ([CrawlerDbSchema.tableStatements]), so each of the ~190 M
 * `INSERT OR IGNORE` statements appends to one rowid B-tree instead of
 * maintaining 5+ secondary ones, and [finishImport] builds the six `idx_*` in
 * one single pass over the finished rows. The bulk phase also runs with
 * [RestorePragmas.bulkPhase]: `wal_autocheckpoint=0` turns the WAL into a pure
 * append log, so a per-chunk COMMIT is a page-cache append instead of fighting
 * incremental checkpoints of a growing WAL back into the DB on slow flash
 * (measured on-device: 54 s per chunk, every thread asleep), and
 * `synchronous=OFF` skips the per-commit fsync. [finishImport] then folds the
 * whole WAL back in ONE sequential pass at `synchronous=FULL`, so the DB is
 * fully durable before the swap. A RESUMED temp DB created by the
 * pre-deferred-index build has its `idx_*` dropped ([dropLegacyIndexes]) so the
 * remaining chunks get the same rate. The WAL is also bounded DURING the bulk
 * phase (plan 3, Stage 11): [maybeCheckpoint] folds it back with
 * `wal_checkpoint(TRUNCATE)` whenever the `-wal` file reaches
 * [CHECKPOINT_TRIGGER_BYTES] (1 GiB), so it stays at ~1.2 GB instead of growing
 * to the measured 2.1 GB after only ~420 MB of imported SQL — under
 * `synchronous=OFF` that is page-cache work, no fsync. The page size stays the
 * platform default
 * (4096 on-device): `PRAGMA page_size` cannot change it, because
 * `openDatabase(CREATE_IF_NECESSARY)` has already initialised the header.
 *
 * What makes the import resumable: one chunk = one transaction
 * ([beginChunk]/[endChunk]). A chunk that commits survives a process kill; the
 * only work a retry repeats is the in-flight chunk, which is safe because every
 * dump statement is `INSERT OR IGNORE` (`DbRestoreManager` replays the chain in
 * id-major order, so the replay is first-writer-wins and deterministic).
 *
 * FTS5 is deliberately absent: the FTS tables, the triggers and the index
 * `rebuild` for `torrent_search` run in the native `--rebuild-fts` one-shot
 * against the temp DB with the crawler's bundled SQLite 3.49.1, so platform
 * SQLite never needs FTS5.
 *
 * Android-touching (opened on-device by the restore flow); the surrounding
 * logic in [DbRestoreManager] is plain-JUnit testable against a fake sink.
 */
class SqliteRestoreSink(
    private val dbFile: File,
    /**
     * Fold the WAL back once its `-wal` file reaches this size. Production
     * always uses [CHECKPOINT_TRIGGER_BYTES]; it is a constructor parameter only
     * so a Robolectric test can force a real `wal_checkpoint(TRUNCATE)` on a
     * database far too small to reach 1 GiB.
     */
    private val checkpointTriggerBytes: Long = CHECKPOINT_TRIGGER_BYTES,
) : RestoreStatementSink {

    /** The open database, `null` before [prepare] and after close. */
    private var db: SQLiteDatabase? = null

    /** `sqlite_version()` of the platform SQLite, recorded by [prepare]. */
    private var platformSqliteVersion: String = UNKNOWN_VERSION

    /** `journal_mode` the platform SQLite actually granted, for the summary. */
    internal var journalMode: String = UNKNOWN_VERSION
        private set

    /** `page_size` the temp DB actually has (the platform default), for the summary. */
    internal var pageSize: String = UNKNOWN_VERSION
        private set

    /**
     * `PRAGMA synchronous` read back after the bulk-phase pragmas (0 = OFF):
     * the value SQLite actually granted, not the one we asked for. Exposed to
     * tests and reported in the summary.
     */
    internal var bulkSynchronous: String = UNKNOWN_VERSION
        private set

    /** `PRAGMA wal_autocheckpoint` read back after the bulk-phase pragmas (0). */
    internal var walAutocheckpoint: String = UNKNOWN_VERSION
        private set

    /**
     * `PRAGMA synchronous` read back after [finishImport]'s `synchronous=FULL`,
     * i.e. immediately before the single checkpoint (2 = FULL).
     */
    internal var checkpointSynchronous: String = UNKNOWN_VERSION
        private set

    /**
     * Summary lines collected by [prepare] that must reach the restore status but
     * have no earlier channel than [finishImport]'s return value (the sink
     * interface is deliberately one-way). Currently the legacy-index drop.
     */
    private val prepareLines = mutableListOf<String>()

    /** Set when a statement of the current chunk threw, so [endChunk] rolls back. */
    private var chunkFailed = false

    override fun prepare(fresh: Boolean) {
        check(db == null) { "prepare() called twice" }
        if (fresh) {
            // A fresh import must not inherit a half-imported DB or its WAL.
            dbFile.delete()
            RestoreFileOps.deleteDbSidecars(dbFile)
            Log.i(TAG, "fresh import into ${dbFile.path} (previous db/sidecars removed)")
        } else {
            Log.i(TAG, "resuming import into ${dbFile.path}")
        }
        dbFile.parentFile?.mkdirs()
        val opened = SQLiteDatabase.openDatabase(
            dbFile.path,
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE,
        )
        db = opened
        platformSqliteVersion = readSqliteVersion(opened)
        // Bulk-phase pragmas, hoisted ABOVE the fresh/resume split (the Stage 6
        // fix): they are per-connection settings that are not stored in the
        // file, so a resumed import must get exactly the same session as a
        // fresh one — otherwise a retry would fall back to the platform's
        // foreign_keys default, fsync every commit again (synchronous=NORMAL)
        // and lose the bounded page cache. See [RestorePragmas.bulkPhase] and
        // [applyPragma] (a row-returning pragma must never go through execSQL).
        for (sql in RestorePragmas.bulkPhase) applyPragma(opened, sql)
        // Read them back from the live connection: the log and the restore
        // status then carry what SQLite granted, not what was requested.
        bulkSynchronous = readPragma(opened, "PRAGMA synchronous")
        walAutocheckpoint = readPragma(opened, "PRAGMA wal_autocheckpoint")
        if (fresh) {
            journalMode = readJournalMode(opened, "WAL")
            // ONLY the base tables: the six `idx_*` are created by
            // [finishImport] once every chunk is in, so the ~190 M replayed
            // inserts do not maintain 5+ secondary B-trees each.
            for (sql in CrawlerDbSchema.tableStatements) opened.execSQL(sql)
            pageSize = readPageSize(opened)
            Log.i(TAG, "prepared ${dbFile.path}: journal_mode=$journalMode " +
                "page_size=$pageSize (platform default; not settable via PRAGMA) " +
                "cache_size=${RestorePragmas.CACHE_SIZE_KIB}KiB " +
                "synchronous=$bulkSynchronous wal_autocheckpoint=$walAutocheckpoint " +
                "sqlite=$platformSqliteVersion tables=" +
                CrawlerDbSchema.tableStatements.size + " (deferred indexes=" +
                CrawlerDbSchema.indexStatements.size + ")")
        } else {
            journalMode = readJournalMode(opened, null)
            // A temp DB created by the pre-deferred-index build carries all six
            // `idx_*`: drop them so this attempt gets the fast index-free rate
            // too (they are recreated by [finishImport] in one pass).
            dropLegacyIndexes(opened)
            pageSize = readPageSize(opened)
            Log.i(TAG, "resumed ${dbFile.path}: journal_mode=$journalMode " +
                "page_size=$pageSize cache_size=${RestorePragmas.CACHE_SIZE_KIB}KiB " +
                "synchronous=$bulkSynchronous wal_autocheckpoint=$walAutocheckpoint " +
                "sqlite=$platformSqliteVersion")
        }
    }

    override fun beginChunk() {
        val d = requireDb()
        check(!d.inTransaction()) { "beginChunk() while a chunk is already open" }
        chunkFailed = false
        d.beginTransaction()
    }

    override fun statement(sql: String) {
        val d = requireDb()
        try {
            d.execSQL(sql)
        } catch (e: Exception) {
            // Remember the failure so endChunk() rolls the chunk back instead of
            // committing a partially applied one (the resume marker stays at
            // this chunk, so the retry replays it whole).
            chunkFailed = true
            throw e
        }
    }

    override fun endChunk() {
        val d = requireDb()
        if (!d.inTransaction()) return
        if (!chunkFailed) d.setTransactionSuccessful()
        d.endTransaction()
        if (chunkFailed) {
            chunkFailed = false
            Log.i(TAG, "chunk rolled back after a failed statement")
        }
    }

    /**
     * Bounds the bulk-phase WAL: `PRAGMA wal_checkpoint(TRUNCATE)` once the
     * `-wal` file has grown to [checkpointTriggerBytes] (1 GiB in production).
     *
     * `wal_autocheckpoint=0` keeps every per-chunk COMMIT a cheap page-cache
     * append, but it also means nothing folds the WAL back until
     * [finishImport] — measured on-device the WAL reached 2.1 GB after ~420 MB
     * of imported SQL. Folding it here, after the chunk's COMMIT and its
     * resume-marker write, bounds it at trigger + one chunk (~1.2 GB) and keeps
     * the final checkpoint from having to fold the whole chain at peak
     * `WAL + DB` occupancy. Under `synchronous=OFF` a checkpoint is pure
     * page-cache work (no fsync), so the measured ~0.22 ms/statement rate is
     * unchanged.
     *
     * Never throws: this is an optimisation, not a durability step (that is
     * [finishImport]'s `synchronous=FULL` + checkpoint before the swap). A
     * busy WAL is reported as a skipped line and simply retried by the next
     * chunk, and any SQLite error is reported the same way instead of failing a
     * multi-hour import.
     */
    override fun maybeCheckpoint(): String? {
        val d = requireDb()
        val walFile = File(dbFile.path + WAL_SUFFIX)
        val walBytes = if (walFile.isFile) walFile.length() else 0L
        if (!shouldCheckpoint(walBytes, checkpointTriggerBytes)) return null
        val startedAt = System.nanoTime()
        val busy = try {
            var result = -1
            d.rawQuery(RestorePragmas.FINAL_CHECKPOINT, null).use { c ->
                if (c.moveToFirst()) result = c.getInt(0)
            }
            result
        } catch (e: Exception) {
            val line = "checkpoint: skipped, ${e.javaClass.simpleName} " +
                "(WAL kept; retried next chunk)"
            Log.i(TAG, line)
            return line
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val line = if (busy == 0) {
            "checkpoint: folded WAL (${walBytes / MIB} MB, $elapsedMs ms)"
        } else {
            "checkpoint: skipped, busy=$busy (WAL kept; retried next chunk)"
        }
        Log.i(TAG, line)
        return line
    }

    override fun finishImport(): List<String> {
        val d = requireDb()
        val lines = prepareLines.toMutableList()
        var checkpoint = "not run"
        try {
            // One single pass over the finished rows per index, with the 64 MiB
            // cache still warm — the whole reason the bulk phase ran without
            // them. Idempotent (`IF NOT EXISTS`), so a retry that re-enters
            // finishImport after a kill completes a partial build.
            lines += createDeferredIndexes(d)
            // The bulk phase ran at synchronous=OFF (no fsync per chunk commit):
            // switch to FULL for the ONE final checkpoint below, so the whole DB
            // — the WAL fold-back included — is on stable storage before the
            // temp DB is renamed over the live one. This one-time fsync is
            // exactly the cost the bulk phase deferred. Read the granted value
            // back (2 = FULL) so the summary proves it took effect.
            applyPragma(d, RestorePragmas.FINAL_DURABILITY)
            checkpointSynchronous = readPragma(d, "PRAGMA synchronous")
            d.rawQuery(RestorePragmas.FINAL_CHECKPOINT, null).use { c ->
                if (c.moveToFirst()) {
                    val busy = c.getInt(0)
                    checkpoint = "busy=$busy log=${c.getInt(1)} checkpointed=${c.getInt(2)}"
                    if (busy != 0) {
                        throw IOException("wal_checkpoint(TRUNCATE) is busy: $checkpoint")
                    }
                }
            }
        } finally {
            closeQuietly()
        }
        lines += listOf(
            "sqlite_version=$platformSqliteVersion (platform SQLite)",
            "journal_mode=$journalMode",
            "page_size=$pageSize",
            "synchronous=$checkpointSynchronous for the final checkpoint " +
                "(bulk phase ran at $bulkSynchronous)",
            "wal_checkpoint(TRUNCATE): $checkpoint",
            "import complete; FTS5 tables, triggers and the torrent_search " +
                "rebuild are delegated to the native --rebuild-fts",
        )
        for (line in lines) Log.i(TAG, line)
        return lines
    }

    /**
     * Creates the six deferred `idx_*` indexes ([CrawlerDbSchema.indexStatements]),
     * timing each one into the summary. Runs before the WAL checkpoint and before
     * the native `--rebuild-fts` one-shot, so the two heavy single-pass steps
     * never interleave with the per-row insert path.
     */
    private fun createDeferredIndexes(d: SQLiteDatabase): List<String> {
        val lines = mutableListOf<String>()
        for (sql in CrawlerDbSchema.indexStatements) {
            val name = sql.removePrefix("CREATE INDEX IF NOT EXISTS ").substringBefore(' ')
            val startedAt = System.nanoTime()
            d.execSQL(sql)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            lines += "created index $name in ${elapsedMs}ms (deferred past the bulk import)"
        }
        return lines
    }

    /**
     * Drops every `idx_*` index a RESUMED temp DB already carries. Only the
     * pre-deferred-index build created them up front, so this is the legacy-resume
     * path (the device's in-flight temp DB came from that build); the indexes come
     * back from [finishImport] once the remaining chunks are in. Surfaced as one
     * summary line, and never fatal: the probe/drop is an optimisation, not a
     * correctness requirement (the DDL is `IF NOT EXISTS`).
     */
    private fun dropLegacyIndexes(d: SQLiteDatabase) {
        val names = mutableListOf<String>()
        try {
            d.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'",
                null,
            ).use { c ->
                while (c.moveToNext()) c.getString(0)?.let { names += it }
            }
            for (name in names) d.execSQL("DROP INDEX IF EXISTS $name")
        } catch (e: Exception) {
            Log.i(TAG, "legacy index drop failed: ${e.javaClass.simpleName}")
            return
        }
        if (names.isEmpty()) return
        val line = "legacy temp DB: dropped ${names.size} idx_* secondary indexes " +
            "(recreated after the import)"
        prepareLines += line
        Log.i(TAG, line)
    }

    override fun abandon() {
        closeQuietly()
        dbFile.delete()
        RestoreFileOps.deleteDbSidecars(dbFile)
        Log.i(TAG, "abandoned import: ${dbFile.path} and its sidecars deleted")
    }

    /**
     * Closes the database WITHOUT deleting the temp DB (the difference from
     * [abandon]): a failed import keeps its temp DB and resume marker, so the
     * retry continues where it stopped — but no handle may stay open on that
     * file, or the retry opens a second one and a handle left mid-transaction
     * can hold a write lock (SQLITE_BUSY) until GC finalizes it. Idempotent:
     * a no-op when nothing is open.
     */
    override fun suspendImport() {
        if (db == null) return
        closeQuietly()
        Log.i(TAG, "import suspended: ${dbFile.path} closed, kept for the resume")
    }

    /** The open database, or an [IllegalStateException] when [prepare] was skipped. */
    private fun requireDb(): SQLiteDatabase =
        db ?: throw IllegalStateException("prepare() has not been called")

    /**
     * Applies one PRAGMA on [d], discarding any result row.
     *
     * A PRAGMA must NEVER go through [SQLiteDatabase.execSQL]: the framework
     * rejects every statement that produces a result row with
     * `SQLiteException: unknown error (code 0 SQLITE_OK): Queries can be
     * performed using SQLiteDatabase query or rawQuery methods only.` — and the
     * assignment form of several pragmas DOES return its new value. Audited
     * against a real `SQLiteDatabase` (see
     * [SqliteRestoreSinkRobolectricTest]): `wal_autocheckpoint=0` and
     * `journal_mode=WAL` return a row (both used to fail — the Stage 10
     * `wal_autocheckpoint=0` broke every restore, fresh and resumed, right
     * after `prepare()` opened the temp DB), while `foreign_keys=OFF`,
     * `synchronous=OFF|FULL` and `cache_size=-64000` return none on SQLite 3.5x.
     * That split is not contractual across platform versions, so EVERY pragma is
     * executed as a query: [SQLiteDatabase.rawQuery] works for both shapes (a
     * row-less pragma just yields an empty cursor, `moveToFirst()` = false) and
     * the setting takes effect either way — verified by the read-backs in
     * [prepare] and [finishImport].
     */
    private fun applyPragma(d: SQLiteDatabase, sql: String) {
        d.rawQuery(sql, null).use { it.moveToFirst() }
    }

    /**
     * Reads a single-value PRAGMA (`PRAGMA synchronous`, `PRAGMA
     * wal_autocheckpoint`, …) off the live connection — the value SQLite
     * actually granted, for the log and the restore status.
     */
    private fun readPragma(d: SQLiteDatabase, sql: String): String = try {
        d.rawQuery(sql, null).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: UNKNOWN_VERSION else UNKNOWN_VERSION
        }
    } catch (e: Exception) {
        Log.i(TAG, "$sql failed: ${e.javaClass.simpleName}")
        UNKNOWN_VERSION
    }

    /** Closes the database, ignoring an already-closed/null connection. */
    private fun closeQuietly() {
        val d = db ?: return
        db = null
        try {
            d.close()
        } catch (e: Exception) {
            Log.i(TAG, "close of ${dbFile.path} failed: ${e.javaClass.simpleName}")
        }
    }

    private fun readSqliteVersion(d: SQLiteDatabase): String = try {
        d.rawQuery("SELECT sqlite_version()", null).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: UNKNOWN_VERSION else UNKNOWN_VERSION
        }
    } catch (e: Exception) {
        Log.i(TAG, "sqlite_version() query failed: ${e.javaClass.simpleName}")
        UNKNOWN_VERSION
    }

    /** Reads `PRAGMA page_size` of the open temp DB, for the summary. */
    private fun readPageSize(d: SQLiteDatabase): String = readPragma(d, "PRAGMA page_size")

    /**
     * Reads `PRAGMA journal_mode` and, when [want] is given, asks SQLite to
     * switch to it first (a journal-mode change returns the mode in effect).
     */
    private fun readJournalMode(d: SQLiteDatabase, want: String?): String {
        val pragma = if (want == null) "PRAGMA journal_mode" else "PRAGMA journal_mode=$want"
        return try {
            d.rawQuery(pragma, null).use { c ->
                if (c.moveToFirst()) c.getString(0) ?: UNKNOWN_VERSION else UNKNOWN_VERSION
            }
        } catch (e: Exception) {
            Log.i(TAG, "$pragma failed: ${e.javaClass.simpleName}")
            UNKNOWN_VERSION
        }
    }

    companion object {
        private const val TAG = "DbRestore"

        /** Placeholder when a PRAGMA/version query cannot be read. */
        private const val UNKNOWN_VERSION = "unknown"

        /** SQLite's sidecar suffix for the write-ahead log of a database file. */
        private const val WAL_SUFFIX = "-wal"

        private const val MIB = 1024L * 1024

        /**
         * The bulk-phase checkpoint trigger (plan 3, Stage 11): fold the WAL
         * back once its `-wal` file has grown to 1 GiB. That bounds the WAL at
         * ~1.2 GB (trigger + one chunk's growth) instead of the measured 2.1 GB
         * after ~420 MB of SQL, and leaves the final checkpoint a single small
         * fold. At the measured ~5× SQL amplification a 36.7 GB chain triggers
         * ~20–40 checkpoints — a few minutes in total, negligible against the
         * ~22 h bulk phase.
         */
        internal const val CHECKPOINT_TRIGGER_BYTES: Long = 1L shl 30

        /**
         * The trigger rule as a pure predicate, so the boundary (1 GiB − 1 → no
         * checkpoint, exactly 1 GiB → checkpoint) is pinned by a JVM test
         * instead of needing a 1 GiB WAL. A missing `-wal` file reads as 0 and
         * never triggers.
         */
        internal fun shouldCheckpoint(
            walBytes: Long,
            triggerBytes: Long = CHECKPOINT_TRIGGER_BYTES,
        ): Boolean = walBytes >= triggerBytes
    }
}

/**
 * The PRAGMA sequence of the bulk import, as pure data (no Android types), so
 * the ordering the importer depends on is JVM-testable — the sink itself needs
 * a device.
 *
 * [bulkPhase] is executed by [SqliteRestoreSink.prepare] for a FRESH and a
 * RESUMED import alike: every entry is a per-connection setting that is not
 * stored in the file, so a retry must re-apply it.
 *
 * How they are executed matters: the assignment form of some pragmas RETURNS A
 * ROW (`wal_autocheckpoint`, `journal_mode`, `wal_checkpoint`) while others
 * return nothing (`foreign_keys`, `synchronous`, `cache_size`), and
 * `SQLiteDatabase.execSQL` refuses row-returning statements — so every pragma
 * here goes through [SqliteRestoreSink]'s `applyPragma` (rawQuery, row
 * discarded). Do not "simplify" that back to `execSQL`; a single row-returning
 * pragma failing there aborts the whole restore.
 */
internal object RestorePragmas {

    /** Negative = KiB: 64 MiB of page cache, matching the crawler's tuning. */
    const val CACHE_SIZE_KIB = -64000

    /**
     * Bulk-phase pragmas, in execution order:
     *
     * 1. `foreign_keys=OFF` — the dump inserts torrents, prefixes and files in
     *    chain order, so a row may reference an id a LATER object adds.
     * 2. `synchronous=OFF` — no fsync per chunk commit during the replay. Safe
     *    because the temp DB is disposable and every chunk replay is idempotent
     *    (`INSERT OR IGNORE`, deterministic first-writer-wins), so a lost
     *    in-flight chunk is simply re-imported. A process-level kill (OOM
     *    SIGKILL, force-stop) never loses committed chunks: `commit()` and the
     *    resume-marker write both land in the kernel page cache, which survives
     *    process death, and SQLite's WAL recovery on the next open replays to
     *    the last valid commit frame. Only sudden power loss could drop
     *    unflushed frames, and the exposure is identical to `synchronous=NORMAL`
     *    under WAL (which also fsyncs only at a checkpoint — and
     *    `wal_autocheckpoint` is 0 below).
     * 3. `cache_size=-64000` — a hard bound on SQLite's heap, big enough that
     *    the index-free append and the deferred index build are not thrashing
     *    the page cache.
     * 4. `wal_autocheckpoint=0` — the crawler's own `database_init` setting
     *    (`database.c:125-131`): the WAL becomes a pure append log, so a
     *    per-chunk COMMIT is a page-cache append instead of fighting
     *    incremental checkpoints of a growing WAL back into the DB on slow
     *    flash. [SqliteRestoreSink.finishImport] folds the whole WAL back in ONE
     *    sequential pass at the end. A `journal_size_limit` is NOT needed: it
     *    only matters when a WAL is reset and reused repeatedly (the crawler's
     *    steady state); this import checkpoints exactly once and the sidecars
     *    are deleted at the swap.
     */
    val bulkPhase: List<String> = listOf(
        "PRAGMA foreign_keys=OFF",
        "PRAGMA synchronous=OFF",
        "PRAGMA cache_size=$CACHE_SIZE_KIB",
        "PRAGMA wal_autocheckpoint=0",
    )

    /**
     * Applied by [SqliteRestoreSink.finishImport] immediately BEFORE
     * [FINAL_CHECKPOINT]: the one-time fsync of the whole DB is exactly the cost
     * the bulk phase deferred, and it makes the swapped-in live DB fully durable
     * before the rename.
     */
    const val FINAL_DURABILITY = "PRAGMA synchronous=FULL"

    /** The single checkpoint that folds the append-only WAL back into the DB. */
    const val FINAL_CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"
}
