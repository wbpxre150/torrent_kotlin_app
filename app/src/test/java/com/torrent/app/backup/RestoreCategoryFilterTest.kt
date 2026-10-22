package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pure tests for the restore-side category filter primitives:
 * [parseClassIndex] (the `.idx.gz` sidecar parser) and
 * [RestoreCategoryFilter] (the statement-level keep/drop decision).
 *
 * The fixtures are real dump statements — the exact shape
 * [SqlDumpWriter.renderStatement] emits: `INSERT OR IGNORE INTO <table>
 * VALUES(...)`, no column list, declared column order.
 */
class RestoreCategoryFilterTest {

    // ---- table dispatch -------------------------------------------------

    /** `torrents` = (id, info_hash, name, size_bytes, total_peers, added_timestamp). */
    private fun torrentsStatement(id: String) =
        "INSERT OR IGNORE INTO torrents VALUES($id, x'00112233445566778899aabbccddeeff00112233', " +
            "'some name', 1024, 7, 1700000000)"

    /** `torrent_files` = (id, torrent_id, prefix_id, filename, size_bytes, file_index). */
    private fun torrentFilesStatement(torrentId: String) =
        "INSERT OR IGNORE INTO torrent_files VALUES(99, $torrentId, 3, 'file.mkv', 1024, 0)"

    /** `torrent_class` = (torrent_id, category, tv_kind, adult, ...). */
    private fun torrentClassStatement(torrentId: String, category: String) =
        "INSERT OR IGNORE INTO torrent_class VALUES($torrentId, $category, 0, 0, 1.0, 1, 1, " +
            "1, 1, 2, 0.9, 3)"

    private fun filter(selection: Set<Int>, index: Map<Long, Int>) =
        RestoreCategoryFilter(selection, index)

    @Test fun torrentsRowKeptWhenCategorySelected() {
        val f = filter(setOf(2), mapOf(11L to 2))
        assertTrue(f.keep(torrentsStatement("11")))
    }

    @Test fun torrentsRowDroppedWhenCategoryNotSelected() {
        val f = filter(setOf(2), mapOf(11L to 3))
        assertFalse(f.keep(torrentsStatement("11")))
    }

    @Test fun torrentFilesKeyedOnValueOneNotValueZero() {
        // torrent_id is column #1; column #0 (the file id) must be ignored.
        val f = filter(setOf(2), mapOf(11L to 2, 99L to 5))
        assertTrue(f.keep(torrentFilesStatement("11")))
        assertFalse(f.keep(torrentFilesStatement("12")))
    }

    @Test fun torrentClassKeyedOnValueZero() {
        val f = filter(setOf(2), mapOf(11L to 2))
        // The decision comes from the range's class index, NOT from the
        // statement's own category column: the index is authoritative.
        assertTrue(f.keep(torrentClassStatement("11", "2")))
        assertTrue(f.keep(torrentClassStatement("11", "3")))
        // Keyed on value #0 (torrent_id): an unselected id is dropped.
        assertFalse(f.keep(torrentClassStatement("12", "2")))
    }

    @Test fun pathPrefixesAlwaysKept() {
        val f = filter(emptySet(), emptyMap())
        assertTrue(f.keep("INSERT OR IGNORE INTO path_prefixes VALUES(4, '/media/movies')"))
        assertTrue(f.keep("INSERT OR IGNORE INTO path_prefixes VALUES(5, NULL)"))
    }

    @Test fun unknownTableAlwaysKept() {
        val f = filter(emptySet(), emptyMap())
        assertTrue(f.keep("INSERT OR IGNORE INTO classifier_meta VALUES('clf_ver', '3')"))
        assertTrue(f.keep("INSERT OR IGNORE INTO something_new VALUES(1, 2, 3)"))
    }

    @Test fun nonInsertStatementKept() {
        val f = filter(emptySet(), emptyMap())
        assertTrue(f.keep("CREATE INDEX IF NOT EXISTS idx_files_torrent ON torrent_files(torrent_id)"))
        assertTrue(f.keep("DELETE FROM torrents WHERE id = 1"))
        assertTrue(f.keep(""))
    }

    @Test fun tableAndKeywordCaseIsIgnored() {
        val keep = filter(setOf(2), mapOf(11L to 2))
        val drop = filter(setOf(3), mapOf(11L to 2))
        val lower = "insert or ignore into TORRENTS values(11, x'00', 'n', 1, 0, 1700000000)"
        assertTrue(keep.keep(lower))
        assertFalse(drop.keep(lower))
        assertTrue(keep.keep(torrentsStatement("11").replace("VALUES(", "values (")))
    }

    // ---- value extraction ----------------------------------------------

    @Test fun textValueWithCommasParensSemicolonsAndDoubledQuotes() {
        val f = filter(setOf(2), mapOf(11L to 2))
        val statement = "INSERT OR IGNORE INTO torrents VALUES(11, x'00ff', " +
            "'a,b(c);d ''quoted'' ),(', 1024, 7, 1700000000)"
        assertTrue(f.keep(statement))
        assertFalse(filter(setOf(3), mapOf(11L to 2)).keep(statement))
    }

    @Test fun blobValueBeforeTheKeyColumnIsNotSplitOnHexCommas() {
        // A 20-byte info_hash blob sits between the id and the name.
        val f = filter(setOf(2), mapOf(11L to 2))
        val statement = "INSERT OR IGNORE INTO torrents VALUES(11, " +
            "x'00112233445566778899aabbccddeeff00112233', 'x', 1, 0, 1)"
        assertTrue(f.keep(statement))
    }

    @Test fun torrentFilesKeySurvivesComplexFilename() {
        val f = filter(setOf(2), mapOf(11L to 2))
        val statement = "INSERT OR IGNORE INTO torrent_files VALUES(99, 11, NULL, " +
            "'dir/a,b;c(d) ''e''.mkv', 5, 3)"
        assertTrue(f.keep(statement))
        assertFalse(filter(setOf(2), mapOf(12L to 2)).keep(statement))
    }

    @Test fun negativeAndLargeIdsAreParsed() {
        val f = filter(setOf(2), mapOf(-5L to 2, Long.MAX_VALUE to 3, Long.MIN_VALUE to 4))
        assertTrue(f.keep(torrentsStatement("-5")))
        assertFalse(f.keep(torrentsStatement(Long.MAX_VALUE.toString())))
        assertFalse(f.keep(torrentsStatement(Long.MIN_VALUE.toString())))
        assertTrue(filter(setOf(4), mapOf(Long.MIN_VALUE to 4)).keep(torrentsStatement("-9223372036854775808")))
    }

    // ---- unknown semantics ---------------------------------------------

    @Test fun idMissingFromIndexIsUnknownCategoryZero() {
        val statement = torrentsStatement("11")
        // 0 selected → the unclassified torrent is restored.
        assertTrue(filter(setOf(0), emptyMap()).keep(statement))
        // 0 not selected → it is filtered out like any other unselected bucket.
        assertFalse(filter(setOf(1, 2), emptyMap()).keep(statement))
        // An index that mentions OTHER ids only does not classify this one.
        assertTrue(filter(setOf(0), mapOf(12L to 5)).keep(statement))
    }

    @Test fun explicitUnknownEntryBehavesLikeMissingEntry() {
        val statement = torrentsStatement("11")
        assertTrue(filter(setOf(0), mapOf(11L to 0)).keep(statement))
        assertFalse(filter(setOf(2), mapOf(11L to 0)).keep(statement))
    }

    @Test fun emptySelectionDropsEveryClassifiedRow() {
        val f = filter(emptySet(), mapOf(11L to 2))
        assertFalse(f.keep(torrentsStatement("11")))
        assertFalse(f.keep(torrentFilesStatement("11")))
        assertFalse(f.keep(torrentClassStatement("11", "2")))
        // ...but keeps the shared lookup table.
        assertTrue(f.keep("INSERT OR IGNORE INTO path_prefixes VALUES(4, '/x')"))
    }

    // ---- over-inclusion (never silently drop) --------------------------

    @Test fun unparseableKeyTokenKeepsTheStatement() {
        val f = filter(setOf(2), mapOf(11L to 2))
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents VALUES(NULL, x'00', 'n', 1, 0, 1)"))
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents VALUES('11', x'00', 'n', 1, 0, 1)"))
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents VALUES(1e5, x'00', 'n', 1, 0, 1)"))
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents VALUES(+11, x'00', 'n', 1, 0, 1)"))
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents VALUES(99999999999999999999, 1, 2)"))
    }

    @Test fun missingOrTruncatedValuesListKeepsTheStatement() {
        val f = filter(emptySet(), emptyMap())
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents"))
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents VALUES"))
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents VALUES(11, 'unterminated)"))
        assertTrue(f.keep("INSERT OR IGNORE INTO torrents VALUES()"))
        assertTrue(f.keep("INSERT OR IGNORE INTO"))
    }

    @Test fun statementWithTrailingSemicolonAndPaddingStillParses() {
        val f = filter(setOf(2), mapOf(11L to 2))
        assertTrue(f.keep("  " + torrentsStatement("11") + ";"))
        assertFalse(filter(setOf(3), mapOf(11L to 2)).keep("  " + torrentsStatement("11") + ";"))
    }

    // ---- parseClassIndex -----------------------------------------------

    @Test fun parseClassIndexRoundTripsRenderClassIndex() {
        val entries = listOf(
            ClassEntry(torrentId = 1, category = 0),
            ClassEntry(torrentId = 11, category = 2),
            ClassEntry(torrentId = 12, category = 3),
            ClassEntry(torrentId = Long.MAX_VALUE, category = 65535),
        )
        val parsed = parseClassIndex(gzipBytes(renderClassIndex(entries).encodeToByteArray()))
        assertEquals(
            mapOf(1L to 0, 11L to 2, 12L to 3, Long.MAX_VALUE to 65535),
            parsed,
        )
    }

    @Test fun parseClassIndexOfEmptyTextIsEmpty() {
        assertTrue(parseClassIndex(gzipBytes(ByteArray(0))).isEmpty())
        assertTrue(parseClassIndex(gzipBytes("\n\n  \n".encodeToByteArray())).isEmpty())
    }

    @Test fun parseClassIndexToleratesExtraWhitespace() {
        val parsed = parseClassIndex(gzipBytes("  11   2  \n\n12\t3\n".encodeToByteArray()))
        assertEquals(mapOf(11L to 2, 12L to 3), parsed)
    }

    @Test fun parseClassIndexRejectsMalformedLinesQuotingThem() {
        val bad = listOf(
            "11",
            "11 2 3",
            "eleven 2",
            "11 two",
            "11 2.5",
            "11 99999999999999999999",
            "11 2147483648",
        )
        for (line in bad) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                parseClassIndex(gzipBytes("1 0\n$line\n".encodeToByteArray()))
            }
            assertTrue("message must quote '$line': ${e.message}", e.message!!.contains("\"$line\""))
        }
    }

    @Test fun parseClassIndexPropagatesCorruptGzipAsIoException() {
        assertThrows(IOException::class.java) {
            parseClassIndex("not gzip at all".encodeToByteArray())
        }
    }

    @Test fun filterWorksWithAParsedSidecar() {
        val index = parseClassIndex(
            gzipBytes(renderClassIndex(listOf(ClassEntry(11, 2), ClassEntry(12, 3))).encodeToByteArray()),
        )
        val f = filter(setOf(2), index)
        assertTrue(f.keep(torrentsStatement("11")))
        assertFalse(f.keep(torrentsStatement("12")))
        // 13 is not in the sidecar → unknown (0) → dropped when 0 is unselected.
        assertFalse(f.keep(torrentsStatement("13")))
    }
}
