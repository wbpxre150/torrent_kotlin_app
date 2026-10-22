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
 * `cache_size` is pinned to 8 MiB so SQLite's page cache cannot grow with the
 * ~36 GB replay.
 *
 * What makes the import resumable: one chunk = one transaction
 * ([beginChunk]/[endChunk]). A chunk that commits survives a process kill; the
 * only work a retry repeats is the in-flight chunk, which is safe because every
 * dump statement is `INSERT OR IGNORE` (`DbRestoreManager` replays the chain in
 * id-major order, so the replay is first-writer-wins and deterministic).
 *
 * FTS5 is deliberately absent: the FTS tables, the triggers and the index
 * `rebuild` for both search indexes run in the native `--rebuild-fts` one-shot
 * against the temp DB with the crawler's bundled SQLite 3.49.1, so platform
 * SQLite never needs FTS5 or the trigram tokenizer.
 *
 * Android-touching (opened on-device by the restore flow); the surrounding
 * logic in [DbRestoreManager] is plain-JUnit testable against a fake sink.
 */
class SqliteRestoreSink(private val dbFile: File) : RestoreStatementSink {

    /** The open database, `null` before [prepare] and after close. */
    private var db: SQLiteDatabase? = null

    /** `sqlite_version()` of the platform SQLite, recorded by [prepare]. */
    private var platformSqliteVersion: String = UNKNOWN_VERSION

    /** `journal_mode` the platform SQLite actually granted, for the summary. */
    private var journalMode: String = UNKNOWN_VERSION

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
        if (fresh) {
            journalMode = readJournalMode(opened, "WAL")
            // foreign_keys stays OFF for the whole import: the dump inserts
            // torrents, prefixes and files in chain order, so a row can
            // reference an id that a LATER object adds.
            opened.execSQL("PRAGMA foreign_keys=OFF")
            // synchronous=NORMAL is the WAL-appropriate setting (a kill can
            // lose at most the in-flight chunk, which a retry replays).
            opened.execSQL("PRAGMA synchronous=NORMAL")
            // 8 MiB page cache: the bound on SQLite's own heap use.
            opened.execSQL("PRAGMA cache_size=-8000")
            for (sql in CrawlerDbSchema.baseStatements) opened.execSQL(sql)
            Log.i(TAG, "prepared ${dbFile.path}: journal_mode=$journalMode " +
                "sqlite=$platformSqliteVersion baseStatements=" +
                CrawlerDbSchema.baseStatements.size)
        } else {
            journalMode = readJournalMode(opened, null)
            Log.i(TAG, "resumed ${dbFile.path}: journal_mode=$journalMode " +
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

    override fun finishImport(): List<String> {
        val d = requireDb()
        var checkpoint = "not run"
        try {
            d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
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
        val lines = listOf(
            "sqlite_version=$platformSqliteVersion (platform SQLite)",
            "journal_mode=$journalMode",
            "wal_checkpoint(TRUNCATE): $checkpoint",
            "import complete; FTS5 tables, triggers and the torrent_search/" +
                "file_search rebuild are delegated to the native --rebuild-fts",
        )
        for (line in lines) Log.i(TAG, line)
        return lines
    }

    override fun abandon() {
        closeQuietly()
        dbFile.delete()
        RestoreFileOps.deleteDbSidecars(dbFile)
        Log.i(TAG, "abandoned import: ${dbFile.path} and its sidecars deleted")
    }

    /** The open database, or an [IllegalStateException] when [prepare] was skipped. */
    private fun requireDb(): SQLiteDatabase =
        db ?: throw IllegalStateException("prepare() has not been called")

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
    }
}
