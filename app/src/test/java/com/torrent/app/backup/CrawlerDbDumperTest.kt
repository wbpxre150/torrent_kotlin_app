package com.torrent.app.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [CrawlerDbDumper] against a real (Robolectric) SQLite fixture DB with
 * the crawler schema: full-dump round-trip (replayed into a fresh DB
 * and compared row-for-row, incl. a 20-byte blob, quoted/newline/unicode
 * names and a NULL prefix_id), the unchanged-DB skip, and the
 * bookmark-gated incremental run (torrent_files gated on torrent_id).
 */
@RunWith(RobolectricTestRunner::class)
class CrawlerDbDumperTest {

    private companion object {
        val HASH1 = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13,
        )
        val HASH2 = byteArrayOf(
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55,
            0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        val HASH3 = byteArrayOf(
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(),
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(),
            0xFF.toByte(), 0x00, 0x01, 0x02, 0x03, 0x04,
        )
        val HASH4 = byteArrayOf(
            0xF0.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(),
            0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(),
            0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(), 0xFB.toByte(),
            0xFC.toByte(), 0xFD.toByte(), 0xFE.toByte(), 0xFF.toByte(),
            0x00, 0x01, 0x02, 0x03,
        )
    }

    private lateinit var dbFile: File
    private lateinit var dumper: CrawlerDbDumper

    @Before
    fun setUp() {
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        dbFile = File(dir, "dumper-test.db")
        dbFile.delete()
        createDb(dbFile).use { sdb ->
            sdb.insert("path_prefixes", null, ContentValues().apply { put("prefix", "linux") })
            sdb.insert("path_prefixes", null, ContentValues().apply { put("prefix", "bsd") })
            sdb.insert("torrents", null, ContentValues().apply {
                put("info_hash", HASH1)
                put("name", "O'Brien")
                put("size_bytes", 1234L)
                put("total_peers", 0)
                put("added_timestamp", 1000L)
            })
            sdb.insert("torrents", null, ContentValues().apply {
                put("info_hash", HASH2)
                put("name", "line1\nline2 \"quoted\"")
                put("size_bytes", 56L)
                put("total_peers", 7)
                put("added_timestamp", 2000L)
            })
            sdb.insert("torrents", null, ContentValues().apply {
                put("info_hash", HASH3)
                put("name", "Ünïcödé 日本語")
                put("size_bytes", 1L)
                put("total_peers", 0)
                put("added_timestamp", 3000L)
            })
            sdb.insert("torrent_files", null, ContentValues().apply {
                put("torrent_id", 1); put("prefix_id", 1)
                put("filename", "a.mkv"); put("size_bytes", 100L); put("file_index", 0)
            })
            sdb.insert("torrent_files", null, ContentValues().apply {
                put("torrent_id", 1) // prefix_id intentionally NULL
                put("filename", "b.mkv"); put("size_bytes", 200L); put("file_index", 1)
            })
            sdb.insert("torrent_files", null, ContentValues().apply {
                put("torrent_id", 2); put("prefix_id", 2)
                put("filename", "c.mkv"); put("size_bytes", 300L); put("file_index", 0)
            })
            sdb.insert("torrent_files", null, ContentValues().apply {
                put("torrent_id", 3); put("prefix_id", 1)
                put("filename", "d.mkv"); put("size_bytes", 400L); put("file_index", 0)
            })
        }
        dumper = CrawlerDbDumper()
    }

    /** Fresh (or existing) DB with the crawler's three base tables. */
    private fun createDb(path: File): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS path_prefixes(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, prefix TEXT NOT NULL UNIQUE)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS torrents(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, info_hash BLOB NOT NULL UNIQUE, " +
                "name TEXT NOT NULL, size_bytes INTEGER NOT NULL, " +
                "total_peers INTEGER DEFAULT 0, added_timestamp INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS torrent_files(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, torrent_id INTEGER NOT NULL, " +
                "prefix_id INTEGER, filename TEXT NOT NULL, size_bytes INTEGER NOT NULL, " +
                "file_index SMALLINT NOT NULL, " +
                "FOREIGN KEY (torrent_id) REFERENCES torrents(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (prefix_id) REFERENCES path_prefixes(id))"
        )
        return db
    }

    /** Serializes every row of [table] (ordered by id) for comparison. */
    private fun allRows(db: SQLiteDatabase, table: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        db.rawQuery("SELECT * FROM $table ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val row = ArrayList<String>(c.columnCount)
                for (i in 0 until c.columnCount) {
                    row += when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> "N"
                        Cursor.FIELD_TYPE_INTEGER -> "I:${c.getLong(i)}"
                        Cursor.FIELD_TYPE_STRING -> "S:${c.getString(i)}"
                        Cursor.FIELD_TYPE_BLOB ->
                            "B:" + c.getBlob(i).joinToString("") {
                                String.format("%02x", it)
                            }
                        else -> "?"
                    }
                }
                out += row
            }
        }
        return out
    }

    /**
     * Splits dump text into statements, respecting single-quoted strings
     * (text values may legally contain newlines; `''` is an escaped quote).
     */
    private fun splitStatements(sql: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inString = false
        var i = 0
        while (i < sql.length) {
            val ch = sql[i]
            sb.append(ch)
            when {
                inString && ch == '\'' -> {
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        sb.append(sql[i + 1])
                        i++
                    } else {
                        inString = false
                    }
                }
                !inString && ch == '\'' -> inString = true
                !inString && ch == ';' -> {
                    out += sb.toString().trim()
                    sb.clear()
                }
            }
            i++
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }

    private fun replaySql(db: SQLiteDatabase, sql: String) {
        for (stmt in splitStatements(sql)) {
            db.execSQL(stmt)
        }
    }

    /** Compares [replayed] with the live fixture DB row-for-row. */
    private fun assertSameRowsAsLive(replayed: SQLiteDatabase) {
        val live = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            for (table in listOf("path_prefixes", "torrents", "torrent_files")) {
                assertEquals(allRows(live, table), allRows(replayed, table))
            }
        } finally {
            live.close()
        }
    }

    @Test
    fun firstRunFullDumpReplaysToIdenticalRows() {
        val outcome = dumper.dump(dbFile, DbBookmark.FIRST_RUN)
        assertTrue(outcome is DbDumpOutcome.Dump)
        val dump = outcome as DbDumpOutcome.Dump

        // Object key: first run → START 0, END max (3).
        val key = dump.objectKey
        assertTrue("bad key: $key", key.matches(Regex("""^db/incremental_\d{8}_0-3\.sql\.gz$""")))
        val parsed = DbObjectKey.parse(key)!!
        assertEquals("db", parsed.prefix)
        assertEquals(0L, parsed.start)
        assertEquals(3L, parsed.end)

        // Cursor + counts.
        assertEquals(DbBookmark(3, 2), dump.newCursor)
        assertEquals(9, dump.statementCount) // 2 prefixes + 3 torrents + 4 files
        assertEquals(3L, dump.newTorrentCount)

        // Decompress and replay into a fresh DB.
        val sql = gunzipBytes(dump.sqlBytes).decodeToString()
        assertTrue(sql.startsWith("INSERT OR IGNORE INTO path_prefixes VALUES("))
        val fresh = createDb(File(dbFile.parentFile, "replay.db"))
        try {
            replaySql(fresh, sql)
            assertSameRowsAsLive(fresh)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun secondRunUnchangedDbIsSkippedWithNoArtifact() {
        val first = dumper.dump(dbFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        val second = dumper.dump(dbFile, first.newCursor)
        assertTrue(second is DbDumpOutcome.Skipped)
        val skipped = second as DbDumpOutcome.Skipped
        assertEquals(0L, skipped.newTorrentCount)
        assertEquals(3L, skipped.maxTorrentId)
        assertEquals(2L, skipped.maxPrefixId)
    }

    @Test
    fun emptyDbIsSkipped() {
        val emptyFile = File(dbFile.parentFile, "empty.db")
        emptyFile.delete()
        createDb(emptyFile).use { }
        val outcome = dumper.dump(emptyFile, DbBookmark.FIRST_RUN)
        assertTrue(outcome is DbDumpOutcome.Skipped)
        assertEquals(0L, (outcome as DbDumpOutcome.Skipped).newTorrentCount)
    }

    @Test
    fun thirdRunDumpsOnlyRowsPastBookmark() {
        val first = dumper.dump(dbFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        val cursor = first.newCursor

        // Insert one new prefix, one new torrent (id 4) and two files for it.
        createDb(dbFile).use { sdb ->
            sdb.insert("path_prefixes", null, ContentValues().apply { put("prefix", "windows") })
            sdb.insert("torrents", null, ContentValues().apply {
                put("info_hash", HASH4)
                put("name", "New Show")
                put("size_bytes", 999L)
                put("total_peers", 1)
                put("added_timestamp", 4000L)
            })
            sdb.insert("torrent_files", null, ContentValues().apply {
                put("torrent_id", 4); put("prefix_id", 3)
                put("filename", "e.mkv"); put("size_bytes", 111L); put("file_index", 0)
            })
            sdb.insert("torrent_files", null, ContentValues().apply {
                put("torrent_id", 4) // prefix_id NULL again
                put("filename", "f.mkv"); put("size_bytes", 222L); put("file_index", 1)
            })
        }

        val second = dumper.dump(dbFile, cursor)
        assertTrue(second is DbDumpOutcome.Dump)
        val dump = second as DbDumpOutcome.Dump
        val parsed = DbObjectKey.parse(dump.objectKey)!!
        assertEquals("db/incremental_${parsed.date}_4-4.sql.gz", dump.objectKey)
        assertEquals(4L, parsed.start)
        assertEquals(DbBookmark(4, 3), dump.newCursor)
        assertEquals(4, dump.statementCount) // 1 prefix + 1 torrent + 2 files
        assertEquals(1L, dump.newTorrentCount)

        val sql = gunzipBytes(dump.sqlBytes).decodeToString()
        val lines = sql.lineSequence().toList()
        // Only the new prefix, torrent and files — nothing from run 1.
        assertTrue(lines[0].startsWith("INSERT OR IGNORE INTO path_prefixes VALUES(3,"))
        assertTrue(lines[1].startsWith("INSERT OR IGNORE INTO torrents VALUES(4, x'f0f1f2f3"))
        assertTrue(lines[2].startsWith("INSERT OR IGNORE INTO torrent_files VALUES(5, 4, 3,"))
        assertTrue(lines[3].startsWith("INSERT OR IGNORE INTO torrent_files VALUES(6, 4, NULL,"))
        // No old rows leaked in.
        assertTrue(!sql.contains("O'Brien") && !sql.contains("a.mkv") && !sql.contains("x'000102"))

        // Full chain replay (dump 1 + dump 2) reproduces the live DB exactly.
        val fresh = createDb(File(dbFile.parentFile, "replay2.db"))
        try {
            replaySql(fresh, gunzipBytes(first.sqlBytes).decodeToString())
            replaySql(fresh, sql)
            assertSameRowsAsLive(fresh)
        } finally {
            fresh.close()
        }
    }
}
