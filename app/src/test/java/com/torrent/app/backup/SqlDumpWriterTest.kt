package com.torrent.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the [SqlDumpWriter] core: literal rendering, statement
 * shape (crawler-compatible), table order, the empty-range skip, the
 * first-run object key, and the gzip round-trip. A fake
 * [CrawlerDbSource] stands in for the database.
 */
class SqlDumpWriterTest {

    private class FakeSource(private val snapshot: DbSnapshot) : CrawlerDbSource {
        override fun snapshot(bookmark: DbBookmark): DbSnapshot = snapshot
    }

    private val fixedClock: () -> Long =
        { java.time.Instant.parse("2026-01-02T03:04:05Z").toEpochMilli() }

    private fun fixtureSnapshot() = DbSnapshot(
        maxTorrentId = 3,
        maxPrefixId = 2,
        pathPrefixes = listOf(
            listOf(SqlValue.Integer(1), SqlValue.Text("linux")),
            listOf(SqlValue.Integer(2), SqlValue.Text("bsd")),
        ),
        torrents = listOf(
            listOf(
                SqlValue.Integer(1),
                SqlValue.Blob(ByteArray(20) { i -> i.toByte() }),
                SqlValue.Text("O'Brien"),
                SqlValue.Integer(1234),
                SqlValue.Integer(0),
                SqlValue.Integer(1000),
            ),
            listOf(
                SqlValue.Integer(2),
                SqlValue.Blob(ByteArray(20) { i -> (255 - i).toByte() }),
                SqlValue.Text("line1\nline2 \"quoted\""),
                SqlValue.Integer(56),
                SqlValue.Integer(7),
                SqlValue.Integer(2000),
            ),
            listOf(
                SqlValue.Integer(3),
                SqlValue.Blob(byteArrayOf(0xFF.toByte(), 0x00, 0x10)),
                SqlValue.Text("Ünïcödé 日本語"),
                SqlValue.Integer(1),
                SqlValue.Integer(0),
                SqlValue.Integer(3000),
            ),
        ),
        torrentFiles = listOf(
            listOf(SqlValue.Integer(1), SqlValue.Integer(1), SqlValue.Integer(1), SqlValue.Text("a.mkv"), SqlValue.Integer(100), SqlValue.Integer(0)),
            listOf(SqlValue.Integer(2), SqlValue.Integer(1), SqlValue.Null, SqlValue.Text("b.mkv"), SqlValue.Integer(200), SqlValue.Integer(1)),
            listOf(SqlValue.Integer(3), SqlValue.Integer(2), SqlValue.Integer(2), SqlValue.Text("c.mkv"), SqlValue.Integer(300), SqlValue.Integer(0)),
        ),
    )

    // --- literal rendering -------------------------------------------------

    @Test fun literalNullIntTextBlob() {
        assertEquals("NULL", renderSqlLiteral(SqlValue.Null))
        assertEquals("0", renderSqlLiteral(SqlValue.Integer(0)))
        assertEquals("-42", renderSqlLiteral(SqlValue.Integer(-42)))
        assertEquals("9223372036854775807", renderSqlLiteral(SqlValue.Integer(Long.MAX_VALUE)))
        assertEquals("'plain'", renderSqlLiteral(SqlValue.Text("plain")))
        assertEquals("'O''Brien'", renderSqlLiteral(SqlValue.Text("O'Brien")))
        assertEquals("'a ''b'' c'", renderSqlLiteral(SqlValue.Text("a 'b' c")))
        // Newlines and double quotes pass through unescaped (standard SQL).
        assertEquals("'line1\nline2 \"q\"'", renderSqlLiteral(SqlValue.Text("line1\nline2 \"q\"")))
        assertEquals("'Ünïcödé 日本語'", renderSqlLiteral(SqlValue.Text("Ünïcödé 日本語")))
        assertEquals(
            "x'000102030405060708090a0b0c0d0e0f1011'",
            renderSqlLiteral(SqlValue.Blob(ByteArray(18) { i -> i.toByte() })),
        )
        // Lowercase hex, incl. 0xFF.
        assertEquals("x'ff0010'", renderSqlLiteral(SqlValue.Blob(byteArrayOf(0xFF.toByte(), 0x00, 0x10))))
    }

    @Test fun blobEqualsIsByContent() {
        assertEquals(
            SqlValue.Blob(byteArrayOf(1, 2)),
            SqlValue.Blob(byteArrayOf(1, 2)),
        )
        assertEquals(SqlValue.Blob(byteArrayOf(1, 2)).hashCode(),
            SqlValue.Blob(byteArrayOf(1, 2)).hashCode())
    }

    // --- statement shape + table order (crawler-compat spot-check) ---------

    @Test fun statementShapeIsCrawlerCompatible() {
        val row = listOf(
            SqlValue.Integer(1),
            SqlValue.Blob(byteArrayOf(0xAB.toByte(), 0xCD.toByte())),
            SqlValue.Text("O'Brien"),
        )
        assertEquals(
            "INSERT OR IGNORE INTO torrents VALUES(1, x'abcd', 'O''Brien');",
            renderStatement("torrents", row),
        )
    }

    /** Splits dump text into statements, respecting single-quoted strings
     *  (a text value may legally contain newlines; `''` is an escaped quote). */
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

    @Test fun renderSqlEmitsTablesInOrderOneStatementPerLine() {
        val sql = renderSql(fixtureSnapshot())
        val stmts = splitStatements(sql)
        assertEquals(8, stmts.size) // 2 prefixes + 3 torrents + 3 files
        assertTrue(stmts[0].startsWith("INSERT OR IGNORE INTO path_prefixes VALUES("))
        assertTrue(stmts[1].startsWith("INSERT OR IGNORE INTO path_prefixes VALUES("))
        assertTrue(stmts[2].startsWith("INSERT OR IGNORE INTO torrents VALUES("))
        assertTrue(stmts[3].startsWith("INSERT OR IGNORE INTO torrents VALUES("))
        assertTrue(stmts[4].startsWith("INSERT OR IGNORE INTO torrents VALUES("))
        assertTrue(stmts[5].startsWith("INSERT OR IGNORE INTO torrent_files VALUES("))
        assertTrue(stmts[6].startsWith("INSERT OR IGNORE INTO torrent_files VALUES("))
        assertTrue(stmts[7].startsWith("INSERT OR IGNORE INTO torrent_files VALUES("))
        assertTrue(sql.endsWith(";\n"))
        // No column list, no DDL, no FTS.
        assertTrue(!sql.contains("CREATE") && !sql.contains("fts") && !sql.contains("(id,"))
        // NULL prefix_id renders as bare NULL.
        assertTrue(stmts[6].contains(", NULL, "))
    }

    // --- gating / skip ------------------------------------------------------

    @Test fun unchangedRangeIsSkippedWithNoArtifact() {
        val writer = SqlDumpWriter(FakeSource(fixtureSnapshot()), clock = fixedClock)
        val outcome = writer.buildDump(DbBookmark(3, 2)) // max == last
        assertTrue(outcome is DbDumpOutcome.Skipped)
        val skipped = outcome as DbDumpOutcome.Skipped
        assertEquals(0L, skipped.newTorrentCount)
        assertEquals(3L, skipped.maxTorrentId)
        assertEquals(2L, skipped.maxPrefixId)
        assertEquals(DbBookmark(3, 2), skipped.bookmark)
    }

    @Test fun emptyTableIsSkipped() {
        val empty = DbSnapshot(0, 0, emptyList(), emptyList(), emptyList())
        val outcome = SqlDumpWriter(FakeSource(empty), clock = fixedClock)
            .buildDump(DbBookmark.FIRST_RUN)
        assertTrue(outcome is DbDumpOutcome.Skipped)
    }

    // --- first-run dump: key, cursor, counts --------------------------------

    @Test fun firstRunDumpProducesFullArtifact() {
        val writer = SqlDumpWriter(FakeSource(fixtureSnapshot()), clock = fixedClock)
        val outcome = writer.buildDump(DbBookmark.FIRST_RUN)
        assertTrue(outcome is DbDumpOutcome.Dump)
        val dump = outcome as DbDumpOutcome.Dump
        // First run: START = last + 1 = 0, END = max.
        assertEquals("db/incremental_20260102_0-3.sql.gz", dump.objectKey)
        assertEquals(DbBookmark(3, 2), dump.newCursor)
        assertEquals(8, dump.statementCount)
        assertEquals(3L, dump.newTorrentCount)
        val sql = gunzipBytes(dump.sqlBytes).decodeToString()
        assertEquals(renderSql(fixtureSnapshot()), sql)
    }

    @Test fun incrementalRunKeyUsesBookmarkStart() {
        val writer = SqlDumpWriter(FakeSource(fixtureSnapshot()), clock = fixedClock)
        val outcome = writer.buildDump(DbBookmark(2, 2)) as DbDumpOutcome.Dump
        assertEquals("db/incremental_20260102_3-3.sql.gz", outcome.objectKey)
    }

    @Test fun customPrefixIsUsed() {
        val writer = SqlDumpWriter(
            FakeSource(fixtureSnapshot()), keyPrefix = "dht", clock = fixedClock
        )
        val outcome = writer.buildDump(DbBookmark.FIRST_RUN) as DbDumpOutcome.Dump
        assertEquals("dht/incremental_20260102_0-3.sql.gz", outcome.objectKey)
    }

    // --- gzip ----------------------------------------------------------------

    @Test fun gzipRoundTripIsByteIdentical() {
        val data = "INSERT OR IGNORE INTO torrents VALUES(1);".repeat(100).encodeToByteArray()
        val gz = gzipBytes(data)
        // gzip magic header.
        assertEquals(0x1F.toByte(), gz[0])
        assertEquals(0x8B.toByte(), gz[1])
        assertArrayEquals(data, gunzipBytes(gz))
    }

    @Test fun gzipEmptyInputRoundTrips() {
        assertArrayEquals(ByteArray(0), gunzipBytes(gzipBytes(ByteArray(0))))
    }
}
