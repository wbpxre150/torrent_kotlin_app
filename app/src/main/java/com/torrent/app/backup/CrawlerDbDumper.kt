package com.torrent.app.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Android adapter over the pure [SqlDumpWriter] (plan 2, Stage 1+2):
 * opens the crawler database (`filesDir/crawler/data/torrents.db`) on a
 * dedicated READ-ONLY connection — never shared with the crawler
 * process — and reads the max ids plus all bookmark-gated rows inside
 * ONE non-exclusive read transaction (a WAL snapshot), so the cursor and
 * the rows can never disagree.
 *
 * The result is the pure core's [DbDumpOutcome]: gzip-compressed SQL
 * bytes, statement count, next cursor values and the S3 object key.
 * Persisting the cursor is the upload stage's job (after a successful
 * upload only).
 */
class CrawlerDbDumper(
    private val keyPrefix: String = DbObjectKey.DEFAULT_PREFIX,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DbDumper {
    override fun dump(dbFile: File, bookmark: DbBookmark): DbDumpOutcome {
        require(dbFile.isFile) { "crawler DB not found: ${dbFile.path}" }
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.beginTransactionNonExclusive()
            try {
                return SqlDumpWriter(SQLiteDbSource(db), keyPrefix, clock).buildDump(bookmark)
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    /**
     * [DbDumper.maxCursors] implementation: opens the DB read-only and reads
     * `SELECT MAX(id)` from the two base tables inside ONE read transaction.
     * An empty table (MAX is NULL) maps to the [DbBookmark.FIRST_RUN] value
     * (`-1`). No row data is ever read, so this stays cheap on a huge DB.
     */
    override fun maxCursors(dbFile: File): DbBookmark {
        require(dbFile.isFile) { "crawler DB not found: ${dbFile.path}" }
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.beginTransactionNonExclusive()
            try {
                return DbBookmark(
                    maxIdOrNull(db, TABLE_TORRENTS),
                    maxIdOrNull(db, TABLE_PATH_PREFIXES))
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    /** `SELECT MAX(id)` — empty table (NULL) → -1 (the [DbBookmark.FIRST_RUN] sentinel). */
    private fun maxIdOrNull(db: SQLiteDatabase, table: String): Long {
        db.rawQuery("SELECT MAX(id) FROM $table", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }

    /**
     * [CrawlerDbSource] over one open [SQLiteDatabase]. The caller holds
     * the read transaction for the whole [snapshot] call.
     */
    private class SQLiteDbSource(private val db: SQLiteDatabase) : CrawlerDbSource {

        override fun snapshot(bookmark: DbBookmark): DbSnapshot {
            val maxTorrentId = maxId(TABLE_TORRENTS)
            val maxPrefixId = maxId(TABLE_PATH_PREFIXES)
            val hasNewTorrents = maxTorrentId > bookmark.lastTorrentId
            return DbSnapshot(
                maxTorrentId = maxTorrentId,
                maxPrefixId = maxPrefixId,
                pathPrefixes = if (maxPrefixId > bookmark.lastPrefixId) {
                    rows(TABLE_PATH_PREFIXES, "id", bookmark.lastPrefixId, maxPrefixId)
                } else {
                    emptyList()
                },
                torrents = if (hasNewTorrents) {
                    rows(TABLE_TORRENTS, "id", bookmark.lastTorrentId, maxTorrentId)
                } else {
                    emptyList()
                },
                torrentFiles = if (hasNewTorrents) {
                    // Gated on torrent_id (NOT the files' own id), mirroring
                    // the crawler's pipeline.
                    rows(TABLE_TORRENT_FILES, "torrent_id", bookmark.lastTorrentId, maxTorrentId)
                } else {
                    emptyList()
                },
            )
        }

        /** `SELECT COALESCE(MAX(id), 0)` — empty table → 0. */
        private fun maxId(table: String): Long {
            db.rawQuery("SELECT COALESCE(MAX(id), 0) FROM $table", null).use { c ->
                return if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }

        /** `SELECT *` rows in (exclusiveMin, inclusiveMax] of [gateColumn]. */
        private fun rows(
            table: String,
            gateColumn: String,
            exclusiveMin: Long,
            inclusiveMax: Long,
        ): List<List<SqlValue>> {
            val out = ArrayList<List<SqlValue>>()
            db.rawQuery(
                "SELECT * FROM $table WHERE $gateColumn > ? AND $gateColumn <= ?",
                arrayOf(exclusiveMin.toString(), inclusiveMax.toString()),
            ).use { c ->
                val columnCount = c.columnCount
                while (c.moveToNext()) {
                    val row = ArrayList<SqlValue>(columnCount)
                    for (i in 0 until columnCount) {
                        row += valueAt(c, i)
                    }
                    out += row
                }
            }
            return out
        }

        private fun valueAt(c: Cursor, i: Int): SqlValue = when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> SqlValue.Null
            Cursor.FIELD_TYPE_INTEGER -> SqlValue.Integer(c.getLong(i))
            Cursor.FIELD_TYPE_STRING -> SqlValue.Text(c.getString(i))
            Cursor.FIELD_TYPE_BLOB -> SqlValue.Blob(c.getBlob(i))
            else -> throw IllegalStateException(
                "unexpected column type ${c.getType(i)} at column $i"
            )
        }
    }
}
