package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [SqlStatementSplitter]: `;` only terminates outside a string
 * literal, `''` is the doubled-quote escape, raw newlines inside literals are
 * ordinary content, `x'..'` blobs pass through, UTF-8 survives, blank
 * statements are skipped, an unterminated final statement is still emitted,
 * embedded U+0000 is dropped and counted, and the runaway guard fires.
 */
class SqlStatementSplitterTest {

    private fun splitStats(text: String): Pair<List<String>, SplitStats> {
        val out = mutableListOf<String>()
        val stats = SqlStatementSplitter.split(text.byteInputStream(Charsets.UTF_8)) { out.add(it) }
        return out to stats
    }

    private fun split(text: String): List<String> = splitStats(text).first

    @Test fun splitsOnSemicolonsOutsideQuotes() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            split("SELECT 1; SELECT 2;"),
        )
    }

    @Test fun trimsSurroundingWhitespaceAndDropsTheTerminator() {
        assertEquals(listOf("SELECT 1"), split("\n  SELECT 1 ;\n\n"))
    }

    @Test fun semicolonInsideQuotedLiteralDoesNotSplit() {
        assertEquals(
            listOf("INSERT OR IGNORE INTO torrents VALUES(1, 'a;b')"),
            split("INSERT OR IGNORE INTO torrents VALUES(1, 'a;b');"),
        )
    }

    @Test fun rawNewlineInsideQuotedLiteralDoesNotSplit() {
        val sql = "INSERT OR IGNORE INTO torrents VALUES(1, 'line one\nline two');"
        assertEquals(
            listOf("INSERT OR IGNORE INTO torrents VALUES(1, 'line one\nline two')"),
            split(sql),
        )
    }

    @Test fun rawCarriageReturnInsideQuotedLiteralDoesNotSplit() {
        assertEquals(
            listOf("INSERT INTO t VALUES('a\r\nb')"),
            split("INSERT INTO t VALUES('a\r\nb');"),
        )
    }

    @Test fun apostropheInNameUsesDoubledQuoteEscape() {
        assertEquals(
            listOf("INSERT OR IGNORE INTO torrents VALUES('O''Brien')"),
            split("INSERT OR IGNORE INTO torrents VALUES('O''Brien');"),
        )
    }

    @Test fun doubledQuoteFollowedByMoreTextStaysInsideLiteral() {
        assertEquals(
            listOf("INSERT INTO t VALUES('it''s; fine')"),
            split("INSERT INTO t VALUES('it''s; fine');"),
        )
    }

    @Test fun literalThatIsOnlyAnApostrophe() {
        assertEquals(
            listOf("INSERT INTO t VALUES('''')"),
            split("INSERT INTO t VALUES('''');"),
        )
    }

    @Test fun consecutiveLiteralsWithNoSeparator() {
        assertEquals(
            listOf("INSERT INTO t VALUES('a', 'b')"),
            split("INSERT INTO t VALUES('a', 'b');"),
        )
    }

    @Test fun emptyLiteralIsAStatementOnItsOwn() {
        assertEquals(listOf("SELECT ''"), split("SELECT '';"))
    }

    @Test fun blobLiteralPassesThroughUntouched() {
        assertEquals(
            listOf("INSERT OR IGNORE INTO torrents VALUES(x'0a0b', 'name')"),
            split("INSERT OR IGNORE INTO torrents VALUES(x'0a0b', 'name');"),
        )
    }

    @Test fun utf8MultibyteTextSurvives() {
        val text = "héllo—日本語 🎬"
        assertEquals(
            listOf("INSERT INTO t VALUES('$text')"),
            split("INSERT INTO t VALUES('$text');"),
        )
    }

    @Test fun unterminatedFinalStatementAtEofIsEmitted() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            split("SELECT 1;\nSELECT 2"),
        )
    }

    @Test fun emptyInputEmitsNothing() {
        assertEquals(emptyList<String>(), split(""))
    }

    @Test fun blankStatementsAreSkipped() {
        assertEquals(emptyList<String>(), split(";;\n  ;\n\t;"))
        assertEquals(listOf("SELECT 1", "SELECT 2"), split("SELECT 1;\n\n;\nSELECT 2;"))
    }

    @Test fun statementsOfARealDumpRoundTrip() {
        val rows = listOf(
            renderStatement(
                "torrents",
                listOf(
                    SqlValue.Integer(1L),
                    SqlValue.Blob(byteArrayOf(0x0a, 0x0b)),
                    SqlValue.Text("O'Brien; the movie"),
                    SqlValue.Integer(1234L),
                    SqlValue.Null,
                    SqlValue.Text("multi\nline"),
                ),
            ),
            renderStatement("torrent_files", listOf(SqlValue.Text("héllo—日本語"))),
        )
        val statements = split(rows.joinToString(""))
        assertEquals(2, statements.size)
        assertEquals(rows[0].removeSuffix(";"), statements[0])
        assertEquals(rows[1].removeSuffix(";"), statements[1])
    }

    @Test fun runawayStatementThrows() {
        val huge = "x".repeat(SqlStatementSplitter.MAX_STATEMENT_CHARS + 1)
        val e = assertThrows(IllegalStateException::class.java) { split(huge) }
        assertTrue(e.message!!.contains(SqlStatementSplitter.MAX_STATEMENT_CHARS.toString()))
    }

    @Test fun runawayGuardAppliesPerStatementNotToTheWholeStream() {
        // Many statements, each well under the cap: no exception, all emitted.
        val sql = "SELECT 1;".repeat(200_000)
        val statements = split(sql)
        assertEquals(200_000, statements.size)
        assertEquals("SELECT 1", statements.last())
    }

    // ---- embedded U+0000 (execSql C-string truncation guard) ----

    @Test fun nulInTheMiddleOfALiteralIsDropped() {
        val (statements, stats) = splitStats("INSERT INTO t VALUES(1, 'ab\u0000cd');")
        assertEquals(listOf("INSERT INTO t VALUES(1, 'abcd')"), statements)
        assertEquals(1, stats.statementCount)
        assertEquals(1, stats.nulDropped)
    }

    @Test fun nulAtTheEndOfALiteralKeepsTheStatementTerminated() {
        // The real chunk-1 shape: GBK bytes (valid UTF-8 by coincidence) then a
        // NUL, then the closing quote and `);`. execSql would truncate at the
        // NUL and see an unterminated literal, so the splitter must drop it and
        // still emit one complete statement.
        val (statements, stats) = splitStats(
            "INSERT OR IGNORE INTO path_prefixes VALUES(107059, '\u037c\u01ac\u04bb\u0000');")
        assertEquals(1, statements.size)
        assertEquals(
            "INSERT OR IGNORE INTO path_prefixes VALUES(107059, '\u037c\u01ac\u04bb')",
            statements[0],
        )
        assertTrue(statements[0].endsWith("')"))
        assertEquals(1, stats.statementCount)
        assertEquals(1, stats.nulDropped)
    }

    @Test fun nulInANonFinalColumnLeavesLaterColumnsUntouched() {
        val (statements, stats) = splitStats("INSERT INTO t VALUES(1, 'a\u0000b', 'c');")
        assertEquals(listOf("INSERT INTO t VALUES(1, 'ab', 'c')"), statements)
        assertEquals(1, stats.nulDropped)
    }

    @Test fun nulOutsideALiteralIsDroppedToo() {
        val (statements, stats) = splitStats("SELECT 1\u0000;SELECT 2;")
        assertEquals(listOf("SELECT 1", "SELECT 2"), statements)
        assertEquals(2, stats.statementCount)
        assertEquals(1, stats.nulDropped)
    }

    @Test fun nulInOneStatementOfAStreamDoesNotDisturbItsNeighbours() {
        val before = "INSERT OR IGNORE INTO torrents VALUES(1, 'one');"
        val affected = "INSERT OR IGNORE INTO path_prefixes VALUES(2, 'x\u0000y');"
        val after = "INSERT OR IGNORE INTO torrents VALUES(3, 'three');"
        val (statements, stats) = splitStats(before + affected + after)
        assertEquals(
            listOf(
                before.removeSuffix(";"),
                affected.removeSuffix(";").replace("\u0000", ""),
                after.removeSuffix(";"),
            ),
            statements,
        )
        assertEquals(3, stats.statementCount)
        assertEquals(1, stats.nulDropped)
    }

    @Test fun cleanChunkReportsZeroNulDropped() {
        val (statements, stats) = splitStats(
            "INSERT OR IGNORE INTO torrents VALUES(1, 'a;b');SELECT 2;")
        assertEquals(2, statements.size)
        assertEquals(2, stats.statementCount)
        assertEquals(0, stats.nulDropped)
    }
}
