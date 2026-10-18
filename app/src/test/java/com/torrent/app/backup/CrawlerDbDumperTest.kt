package com.torrent.app.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [CrawlerDbDumper] against a real (Robolectric) SQLite fixture DB with
 * the crawler schema: full-dump round-trip (replayed into a fresh DB
 * and compared row-for-row, incl. a 20-byte blob, quoted/newline/unicode
 * names and a NULL prefix_id), the unchanged-DB skip, the
 * bookmark-gated incremental run (torrent_files gated on torrent_id),
 * the Stage-1 chunk cap (`capped` flag, chunk boundary as the object
 * key END, backlog draining across successive dumps), the byte-budgeted
 * `torrent_files` sub-ranges that cut a chunk earlier (never inside one
 * torrent's files), and the SHARED byte budget that also cuts
 * `path_prefixes` at a prefix-id sub-range boundary (the prefix-heavy
 * follow-up: with 17M file rows the prefix table alone carried ~1M new
 * long-string rows since the bookmark, so the prefix read has to be bounded
 * too).
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

    /**
     * Fixture variant: [filesPerTorrent] file rows for EACH of 3 torrents
     * (ids 1..3) — a heavy-tailed `torrent_files` table. No path_prefixes.
     */
    private fun createHeavyDb(path: File, filesPerTorrent: Int): SQLiteDatabase {
        path.delete()
        val db = createDb(path)
        for (t in 1..3) {
            db.insert("torrents", null, ContentValues().apply {
                put("info_hash", ByteArray(20) { (t + it).toByte() })
                put("name", "Heavy $t")
                put("size_bytes", 1000L * t)
                put("total_peers", 0)
                put("added_timestamp", 1000L * t)
            })
            for (i in 0 until filesPerTorrent) {
                db.insert("torrent_files", null, ContentValues().apply {
                    put("torrent_id", t.toLong())
                    put("prefix_id", 1)
                    put("filename", String.format("f%02d.mkv", i))
                    put("size_bytes", 1000L + i)
                    put("file_index", i)
                })
            }
        }
        return db
    }

    /**
     * `CrawlerDbDumper`'s approximate byte cost of ONE heavy-fixture file row
     * (see its `estimatedBytes`): 16 base + 16 (id) + 16 (torrent_id) +
     * 16 (prefix_id) + (8 + 7 filename chars) + 16 (size_bytes) +
     * 16 (file_index) = 111.
     */
    private val heavyFileRowEstimate = 111L

    /**
     * `CrawlerDbDumper`'s approximate byte cost of ONE `path_prefixes` row of
     * the prefix fixture below (`p1`..`pN`, 2 chars): 16 base + 16 (id) +
     * (8 + 2 prefix chars) = 42.
     */
    private val prefixRowEstimate = 42L

    /**
     * Fixture variant: [prefixCount] `path_prefixes` rows (`p1`..`pN`, ids
     * 1..N) plus [torrentCount] torrents (ids 1..N) that each carry ONE file
     * row referencing the prefix of the same index — the prefix-heavy DB the
     * shared byte budget has to bound.
     */
    private fun createPrefixFixture(path: File, prefixCount: Int, torrentCount: Int): SQLiteDatabase {
        path.delete()
        val db = createDb(path)
        for (p in 1..prefixCount) {
            db.insert("path_prefixes", null, ContentValues().apply { put("prefix", "p$p") })
        }
        for (t in 1..torrentCount) {
            insertTorrentWithFile(db, id = t)
        }
        return db
    }

    /**
     * Inserts one torrent row (id [id], name `Torrent <id>`) plus ONE file row
     * for it, referencing prefix [prefixId] (defaults to the torrent index).
     */
    private fun insertTorrentWithFile(db: SQLiteDatabase, id: Int, prefixId: Int = id) {
        db.insert("torrents", null, ContentValues().apply {
            put("info_hash", ByteArray(20) { (id + it).toByte() })
            put("name", "Torrent $id")
            put("size_bytes", 1000L * id)
            put("total_peers", 0)
            put("added_timestamp", 1000L * id)
        })
        db.insert("torrent_files", null, ContentValues().apply {
            put("torrent_id", id.toLong())
            put("prefix_id", prefixId.toLong())
            put("filename", "f$id.mkv")
            put("size_bytes", 100L * id)
            put("file_index", 0)
        })
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

    /** Compares [replayed] with the live fixture DB ([dbFile]) row-for-row. */
    private fun assertSameRowsAsLive(replayed: SQLiteDatabase) {
        assertSameRowsAsLive(dbFile, replayed)
    }

    /** Compares [replayed] with [liveFile] row-for-row. */
    private fun assertSameRowsAsLive(liveFile: File, replayed: SQLiteDatabase) {
        val live = SQLiteDatabase.openDatabase(liveFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
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
        // The default cap is far above this fixture: nothing was truncated.
        assertTrue(!dump.capped)

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
        assertTrue(!dump.capped)

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

    // --- Stage 1: chunk cap -------------------------------------------------

    @Test
    fun cappedFirstChunkThenRemainderDrainsBacklog() {
        val chunked = CrawlerDbDumper(maxNewTorrentIdsPerDump = 2)

        val first = chunked.dump(dbFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        // Chunk 1 covers ONLY torrent ids 1..2: 2 prefixes + 2 torrents +
        // the 3 files of those torrents (the file of torrent 3 stays out).
        assertEquals(DbBookmark(2, 2), first.newCursor)
        assertEquals(7, first.statementCount)
        assertEquals(2L, first.newTorrentCount)
        assertTrue(first.capped)
        // The object key END is the chunk boundary, not the table's max (3).
        val firstKey = DbObjectKey.parse(first.objectKey)!!
        assertEquals(0L, firstKey.start)
        assertEquals(2L, firstKey.end)
        val firstSql = gunzipBytes(first.sqlBytes).decodeToString()
        val firstStmts = splitStatements(firstSql)
        assertEquals(7, firstStmts.size)
        assertTrue(firstStmts.last().startsWith("INSERT OR IGNORE INTO torrent_files VALUES(3, 2, 2,"))
        // Nothing past the chunk boundary leaked in (torrent 3, file d.mkv).
        assertTrue(!firstSql.contains("Ünïcödé") && !firstSql.contains("d.mkv"))

        // Chunk 2 from that cursor: the remainder, no new prefixes.
        val second = chunked.dump(dbFile, first.newCursor) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(3, 2), second.newCursor)
        assertEquals(2, second.statementCount) // 1 torrent + its 1 file
        assertEquals(1L, second.newTorrentCount)
        assertTrue(!second.capped) // chunkMax == trueMax here

        // Full chain replay (chunk 1 + chunk 2) reproduces the live DB exactly.
        val fresh = createDb(File(dbFile.parentFile, "replay-capped.db"))
        try {
            replaySql(fresh, firstSql)
            replaySql(fresh, gunzipBytes(second.sqlBytes).decodeToString())
            assertSameRowsAsLive(fresh)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun dumpFromFinalCursorIsSkipped() {
        val chunked = CrawlerDbDumper(maxNewTorrentIdsPerDump = 2)
        val first = chunked.dump(dbFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        val second = chunked.dump(dbFile, first.newCursor) as DbDumpOutcome.Dump
        assertTrue(!second.capped)

        val third = chunked.dump(dbFile, second.newCursor)
        assertTrue(third is DbDumpOutcome.Skipped)
        val skipped = third as DbDumpOutcome.Skipped
        assertEquals(3L, skipped.maxTorrentId)
        assertEquals(2L, skipped.maxPrefixId)
        assertEquals(DbBookmark(3, 2), skipped.bookmark)
        assertEquals(0L, skipped.newTorrentCount)
    }

    @Test
    fun rangeWithinCapIsNotCappedAndBehavesAsUncapped() {
        val chunked = CrawlerDbDumper(maxNewTorrentIdsPerDump = 10)
        val dump = chunked.dump(dbFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        assertTrue(!dump.capped)
        assertEquals(DbBookmark(3, 2), dump.newCursor)
        assertEquals(9, dump.statementCount) // 2 prefixes + 3 torrents + 4 files
        assertEquals(3L, dump.newTorrentCount)
        val parsed = DbObjectKey.parse(dump.objectKey)!!
        assertEquals(0L, parsed.start)
        assertEquals(3L, parsed.end)
    }

    // --- Stage 1 (follow-up): byte-budgeted torrent_files sub-ranges --------

    @Test
    fun fileBudgetCutsChunkAtTorrentBoundary() {
        val heavyFile = File(dbFile.parentFile, "heavy.db")
        val heavy = createHeavyDb(heavyFile, filesPerTorrent = 20)
        heavy.close()
        // Budget = 24 file rows' worth: torrent 1's 20 files (~2,220 B) fit,
        // a second sub-range (4,440 B) blows it — so the cut must land at the
        // torrent-2 boundary, NOT at the id cap (100).
        val budget = heavyFileRowEstimate * 24
        val chunked = CrawlerDbDumper(
            maxNewTorrentIdsPerDump = 100,
            maxChunkEstimatedBytes = budget,
            fileSubRangeTorrentIds = 1,
        )

        // Chunk 1: ONLY torrent 1 — its torrent row plus ALL 20 of its files.
        val first = chunked.dump(heavyFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(1, 0), first.newCursor)
        assertEquals(21, first.statementCount) // 1 torrent + 20 files
        assertEquals(1L, first.newTorrentCount)
        assertTrue("the id cap was not the cut: $first", first.capped)
        val firstKey = DbObjectKey.parse(first.objectKey)!!
        assertEquals(0L, firstKey.start)
        assertEquals(1L, firstKey.end)
        val firstSql = gunzipBytes(first.sqlBytes).decodeToString()
        assertEquals(21, splitStatements(firstSql).size)
        assertTrue(firstSql.contains("INSERT OR IGNORE INTO torrents VALUES(1,"))
        for (i in 0 until 20) assertTrue("missing f%02d.mkv".format(i), firstSql.contains("f%02d.mkv".format(i)))
        // The cut is at the torrent boundary: nothing of torrent 2 leaked in
        // (file rows 1..20 are torrent 1's; torrent 2's start at 21).
        assertTrue(!firstSql.contains("Heavy 2"))
        assertTrue(firstSql.contains("INSERT OR IGNORE INTO torrent_files VALUES(20, 1, 1,"))
        assertTrue(!firstSql.contains("INSERT OR IGNORE INTO torrent_files VALUES(21,"))

        // Chunk 2: torrent 2, again with its full file set.
        val second = chunked.dump(heavyFile, first.newCursor) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(2, 0), second.newCursor)
        assertEquals(21, second.statementCount)
        assertTrue(second.capped)
        val secondSql = gunzipBytes(second.sqlBytes).decodeToString()
        assertTrue(secondSql.contains("Heavy 2") && !secondSql.contains("Heavy 1"))

        // Chunk 3: torrent 3 — that exhausts the true max, so not capped.
        val third = chunked.dump(heavyFile, second.newCursor) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(3, 0), third.newCursor)
        assertEquals(21, third.statementCount)
        assertTrue(!third.capped)

        // Chain-replay ALL chunks into a fresh DB: exactly the live DB.
        val fresh = createDb(File(dbFile.parentFile, "replay-budget.db"))
        try {
            replaySql(fresh, firstSql)
            replaySql(fresh, secondSql)
            replaySql(fresh, gunzipBytes(third.sqlBytes).decodeToString())
            assertSameRowsAsLive(heavyFile, fresh)
        } finally {
            fresh.close()
        }

        // The drain ends: the last cursor has nothing left.
        assertTrue(chunked.dump(heavyFile, third.newCursor) is DbDumpOutcome.Skipped)
    }

    @Test
    fun firstSubRangeAlwaysIncludedEvenOverBudget() {
        val heavyFile = File(dbFile.parentFile, "heavy-over.db")
        createHeavyDb(heavyFile, filesPerTorrent = 20).close()
        val chunked = CrawlerDbDumper(maxChunkEstimatedBytes = 1, fileSubRangeTorrentIds = 1)

        // The budget is 1 byte, yet the first sub-range still lands whole —
        // otherwise a dump could never make progress.
        val first = chunked.dump(heavyFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(1, 0), first.newCursor)
        assertEquals(21, first.statementCount)
        assertEquals(1L, first.newTorrentCount)
        assertTrue(first.capped)
        val sql = gunzipBytes(first.sqlBytes).decodeToString()
        for (i in 0 until 20) assertTrue("missing f%02d.mkv".format(i), sql.contains("f%02d.mkv".format(i)))
        assertTrue(!sql.contains("Heavy 2"))
        // Forward progress across the rest of the backlog.
        val second = chunked.dump(heavyFile, first.newCursor) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(2, 0), second.newCursor)
    }

    @Test
    fun budgetNotReachedBehavesAsBefore() {
        val generous = CrawlerDbDumper(
            maxNewTorrentIdsPerDump = 100,
            maxChunkEstimatedBytes = 1L shl 20,
            fileSubRangeTorrentIds = 50,
        )
        val dump = generous.dump(dbFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        assertTrue(!dump.capped)
        assertEquals(DbBookmark(3, 2), dump.newCursor)
        assertEquals(9, dump.statementCount) // 2 prefixes + 3 torrents + 4 files
        assertEquals(3L, dump.newTorrentCount)
        val parsed = DbObjectKey.parse(dump.objectKey)!!
        assertEquals(0L, parsed.start)
        assertEquals(3L, parsed.end)

        val fresh = createDb(File(dbFile.parentFile, "replay-generous.db"))
        try {
            replaySql(fresh, gunzipBytes(dump.sqlBytes).decodeToString())
            assertSameRowsAsLive(fresh)
        } finally {
            fresh.close()
        }
        // The whole (small) range was covered in one shot: nothing left.
        assertTrue(generous.dump(dbFile, dump.newCursor) is DbDumpOutcome.Skipped)
    }

    // --- Stage 3 (follow-up): SHARED prefix/file byte budget -----------------

    @Test
    fun prefixBudgetCutsChunkAtPrefixBoundary() {
        val prefixFile = File(dbFile.parentFile, "prefix-cut.db")
        createPrefixFixture(prefixFile, prefixCount = 4, torrentCount = 4).close()
        // Budget = ONE prefix row (~42 B): the prefix read is cut at the first
        // prefix-id sub-range boundary and the file read (into the SAME `est`)
        // at the first torrent-id sub-range boundary.
        val chunked = CrawlerDbDumper(
            maxNewTorrentIdsPerDump = 100,
            maxChunkEstimatedBytes = prefixRowEstimate,
            fileSubRangeTorrentIds = 1,
            prefixSubRangeIds = 1,
        )

        // Chunk 1: the first prefix sub-range + the first file sub-range (which
        // also gates the torrents read, so exactly torrent 1 comes along).
        val first = chunked.dump(prefixFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(1, 1), first.newCursor)
        assertEquals(3, first.statementCount) // prefix 1 + torrent 1 + its file
        assertEquals(1L, first.newTorrentCount)
        assertTrue("the shared budget was not the cut: $first", first.capped)
        // The object key END is the chunk boundary (1), not the table's max (4).
        val firstKey = DbObjectKey.parse(first.objectKey)!!
        assertEquals(0L, firstKey.start)
        assertEquals(1L, firstKey.end)
        val firstSql = gunzipBytes(first.sqlBytes).decodeToString()
        assertTrue(firstSql.contains("INSERT OR IGNORE INTO path_prefixes VALUES(1, 'p1');"))
        assertTrue(firstSql.contains("INSERT OR IGNORE INTO torrents VALUES(1,"))
        assertTrue(firstSql.contains("f1.mkv"))
        assertTrue(!firstSql.contains("'p2'"))
        assertTrue(!firstSql.contains("Torrent 2") && !firstSql.contains("f2.mkv"))

        // Chunks 2..4: one prefix-id AND one torrent-id sub-range each, so the
        // cursor's two cuts drain the two tables together.
        val sqls = arrayListOf(firstSql)
        var cursor = first.newCursor
        for (i in 2..4) {
            val dump = chunked.dump(prefixFile, cursor) as DbDumpOutcome.Dump
            sqls += gunzipBytes(dump.sqlBytes).decodeToString()
            assertEquals("chunk $i cursor", DbBookmark(i.toLong(), i.toLong()), dump.newCursor)
            assertEquals("chunk $i statements", 3, dump.statementCount)
            assertEquals("chunk $i capped", i < 4, dump.capped)
            assertTrue(sqls.last().contains("INSERT OR IGNORE INTO path_prefixes VALUES($i, 'p$i');"))
            assertTrue(sqls.last().contains("INSERT OR IGNORE INTO torrents VALUES($i,"))
            assertTrue(sqls.last().contains("f$i.mkv"))
            // The cuts held: neither the NEXT prefix row nor the NEXT
            // torrent's rows leak into this chunk.
            if (i < 4) {
                assertTrue(!sqls.last().contains("'p${i + 1}'"))
                assertTrue(!sqls.last().contains("Torrent ${i + 1}") && !sqls.last().contains("f${i + 1}.mkv"))
            }
            cursor = dump.newCursor
        }

        // Full chain replay (all four chunks) reproduces the live DB exactly.
        val fresh = createDb(File(dbFile.parentFile, "replay-prefix-cut.db"))
        try {
            for (sql in sqls) replaySql(fresh, sql)
            assertSameRowsAsLive(prefixFile, fresh)
        } finally {
            fresh.close()
        }

        // Both tables are drained: the next dump from the final cursor is a skip.
        assertTrue(chunked.dump(prefixFile, cursor) is DbDumpOutcome.Skipped)
    }

    @Test
    fun sharedBudgetPrefixesConsumeItYetFilesStillProgress() {
        val prefixFile = File(dbFile.parentFile, "shared-budget.db")
        createPrefixFixture(prefixFile, prefixCount = 6, torrentCount = 2).close()
        // A 1-byte budget: the FIRST prefix batch still lands whole (forward
        // progress), which already blows the budget — yet the first FILE
        // sub-range is still included, so the chunk advances BOTH ids.
        val chunked = CrawlerDbDumper(
            maxNewTorrentIdsPerDump = 100,
            maxChunkEstimatedBytes = 1,
            fileSubRangeTorrentIds = 1,
            prefixSubRangeIds = 2,
        )

        val first = chunked.dump(prefixFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(1, 1), first.newCursor) // advanced on BOTH ids
        assertEquals(3, first.statementCount) // first prefix batch + torrent 1 + its file
        assertTrue(first.capped)
        val sql = gunzipBytes(first.sqlBytes).decodeToString()
        assertTrue(sql.contains("INSERT OR IGNORE INTO path_prefixes VALUES(1, 'p1');"))
        assertTrue(sql.contains("INSERT OR IGNORE INTO torrents VALUES(1,"))
        assertTrue(sql.contains("f1.mkv"))
        // The second prefix batch (~84 B) did not fit the 1-byte budget, and the
        // second file sub-range did not either.
        assertTrue(!sql.contains("'p2'") && !sql.contains("'p3'"))
        assertTrue(!sql.contains("Torrent 2") && !sql.contains("f2.mkv"))
    }

    @Test
    fun prefixOnlyBacklogWaitsForNewTorrents() {
        val prefixFile = File(dbFile.parentFile, "prefix-backlog.db")
        createPrefixFixture(prefixFile, prefixCount = 4, torrentCount = 2).close()
        // Budget = 2 prefix rows: chunk 1 fits both torrents (and their files)
        // but cuts the prefixes at id 2, so the torrent range is drained while
        // prefixes 3..4 stay pending.
        val chunked = CrawlerDbDumper(
            maxNewTorrentIdsPerDump = 100,
            maxChunkEstimatedBytes = prefixRowEstimate * 2,
            prefixSubRangeIds = 1,
        )

        val first = chunked.dump(prefixFile, DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(2, 2), first.newCursor)
        assertTrue(first.capped)
        val firstSql = gunzipBytes(first.sqlBytes).decodeToString()
        assertTrue(firstSql.contains("VALUES(1, 'p1')") && firstSql.contains("VALUES(2, 'p2')"))
        assertTrue(!firstSql.contains("'p3'"))

        // No NEW torrents but prefixes 3..4 pending: the chunk is still a skip
        // (the artifact is keyed by the torrent range), so the cursor stays put
        // and no prefix-only artifact is produced — the prefixes wait.
        val skipped = chunked.dump(prefixFile, first.newCursor)
        assertTrue("expected a skip, got $skipped", skipped is DbDumpOutcome.Skipped)
        assertEquals(DbBookmark(2, 2), (skipped as DbDumpOutcome.Skipped).bookmark)
        assertEquals(2L, skipped.maxTorrentId)

        // One new torrent arrives, its file referencing a NEW prefix (5): the
        // next chunk carries the pending prefixes 3 and 4 with it.
        createDb(prefixFile).use { db ->
            db.insert("path_prefixes", null, ContentValues().apply { put("prefix", "p5") })
            insertTorrentWithFile(db, id = 3, prefixId = 5)
        }
        val second = chunked.dump(prefixFile, first.newCursor) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(3, 4), second.newCursor)
        assertTrue("prefix 5 is still pending: $second", second.capped)
        val secondSql = gunzipBytes(second.sqlBytes).decodeToString()
        assertTrue(secondSql.contains("INSERT OR IGNORE INTO path_prefixes VALUES(3, 'p3');"))
        assertTrue(secondSql.contains("INSERT OR IGNORE INTO path_prefixes VALUES(4, 'p4');"))
        assertTrue(!secondSql.contains("'p5'"))
        assertTrue(secondSql.contains("INSERT OR IGNORE INTO torrents VALUES(3,"))
        assertTrue(secondSql.contains("f3.mkv"))

        // One more torrent drains the last prefix: the chunk is uncapped.
        createDb(prefixFile).use { db -> insertTorrentWithFile(db, id = 4, prefixId = 5) }
        val third = chunked.dump(prefixFile, second.newCursor) as DbDumpOutcome.Dump
        assertEquals(DbBookmark(4, 5), third.newCursor)
        assertTrue(!third.capped)
        val thirdSql = gunzipBytes(third.sqlBytes).decodeToString()
        assertTrue(thirdSql.contains("INSERT OR IGNORE INTO path_prefixes VALUES(5, 'p5');"))
        assertTrue(thirdSql.contains("INSERT OR IGNORE INTO torrents VALUES(4,"))

        // Every prefix id was dumped exactly once: the chain replay reproduces
        // the live DB.
        val fresh = createDb(File(dbFile.parentFile, "replay-prefix-backlog.db"))
        try {
            replaySql(fresh, firstSql)
            replaySql(fresh, secondSql)
            replaySql(fresh, thirdSql)
            assertSameRowsAsLive(prefixFile, fresh)
        } finally {
            fresh.close()
        }

        // Both tables drained: nothing left.
        assertTrue(chunked.dump(prefixFile, third.newCursor) is DbDumpOutcome.Skipped)
    }
    @Test
    fun zeroChunkCapIsRejected() {
        try {
            CrawlerDbDumper(maxNewTorrentIdsPerDump = 0)
            fail("expected IllegalArgumentException for a zero chunk cap")
        } catch (e: IllegalArgumentException) {
            assertTrue("unexpected message: ${e.message}", e.message!!.contains("maxNewTorrentIdsPerDump"))
        }
    }
}
