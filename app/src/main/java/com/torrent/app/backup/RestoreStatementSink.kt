package com.torrent.app.backup

/**
 * Where a replayed dump statement goes (plan 3, Stage 2).
 *
 * The Kotlin-side restore streams one dump object at a time through
 * [SqlStatementSplitter] and hands every statement to a sink. The sink owns the
 * destination database (a FRESH temp DB — never the live one) and the
 * transaction boundaries that make the import resumable: one chunk = one
 * committed transaction, so a process kill mid-chain leaves every already
 * committed chunk on disk and only the in-flight chunk is replayed on retry
 * (safe because every dump statement is `INSERT OR IGNORE`).
 *
 * The interface is deliberately free of Android types so
 * [DbRestoreManager]'s unit tests can drive a fake sink; the production
 * implementation is [SqliteRestoreSink].
 *
 * FTS5 is NOT part of this contract: the FTS tables, the triggers and the
 * index `rebuild` for both search indexes run in the native `--rebuild-fts`
 * one-shot against the temp DB after the import, with the crawler's bundled
 * SQLite (platform SQLite never needs FTS5 or the trigram tokenizer).
 */
interface RestoreStatementSink {

    /**
     * Opens the destination database and makes it ready for replay.
     *
     * `fresh = true` throws away whatever the destination holds and creates the
     * base schema from [CrawlerDbSchema.baseStatements]; `fresh = false`
     * resumes an existing import and only opens the database (the schema
     * statements are all `IF NOT EXISTS`, so there is nothing to create).
     *
     * Must be called exactly once, before any [beginChunk].
     */
    fun prepare(fresh: Boolean)

    /** Opens one transaction; the matching [endChunk] commits it. */
    fun beginChunk()

    /**
     * Executes one replayed statement ([SqlStatementSplitter] emits it trimmed
     * and without the terminating `;`, which is what `execSQL` expects).
     */
    fun statement(sql: String)

    /**
     * Closes the transaction opened by [beginChunk]: commits it when every
     * statement of the chunk succeeded, rolls it back when one of them threw.
     */
    fun endChunk()

    /**
     * Completes the import: checkpoints the write-ahead log into the database
     * file and closes the database. Returns human-readable summary lines
     * (`sqlite_version()`, checkpoint outcome, the FTS delegation note) for the
     * restore status.
     */
    fun finishImport(): List<String>

    /**
     * Aborts the import: closes the database and deletes it together with its
     * `-wal`/`-shm` sidecars. Safe to call when [prepare] never ran.
     */
    fun abandon()

    /**
     * Suspends the import: closes the destination database but deletes NOTHING
     * — unlike [abandon], which destroys the temp DB and therefore must never
     * run on a failure the resume is supposed to survive. [DbRestoreManager]
     * calls this on every failed import so no handle stays open on the temp DB:
     * a same-process retry would otherwise open a second handle on the same
     * file, and a handle left mid-transaction (a throwing [endChunk]) can hold
     * a write lock (SQLITE_BUSY) until GC finalizes it.
     *
     * Idempotent and safe to call when [prepare] never ran.
     */
    fun suspendImport()
}
