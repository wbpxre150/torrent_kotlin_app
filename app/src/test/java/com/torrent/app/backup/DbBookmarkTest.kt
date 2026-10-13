package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure tests for the [DbBookmark] cursor file: first-run default,
 * atomic write (no leftover `.tmp`), exact two-line format, and the
 * corrupt/partial-file fallback to the first-run sentinel.
 */
class DbBookmarkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String): File = File(tmp.root, name)

    @Test fun missingFileIsFirstRun() {
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(file("nope.txt")))
        assertEquals(DbBookmark(-1, -1), DbBookmark.FIRST_RUN)
    }

    @Test fun writeThenReadRoundTrips() {
        val f = file("bookmark.txt")
        DbBookmark.write(f, DbBookmark(42, 7))
        assertEquals(DbBookmark(42, 7), DbBookmark.read(f))
    }

    @Test fun writeProducesExactlyTwoLines() {
        val f = file("bookmark.txt")
        DbBookmark.write(f, DbBookmark(123, 456))
        assertEquals("last_torrent_id=123\nlast_prefix_id=456\n", f.readText())
    }

    @Test fun atomicWriteLeavesNoTmpBehind() {
        val f = file("bookmark.txt")
        DbBookmark.write(f, DbBookmark(1, 2))
        assertFalse(File(f.path + ".tmp").exists())
        assertTrue(f.exists())
    }

    @Test fun writeCreatesParentDirectories() {
        val f = File(tmp.root, "nested/dir/bookmark.txt")
        DbBookmark.write(f, DbBookmark(5, 6))
        assertEquals(DbBookmark(5, 6), DbBookmark.read(f))
    }

    @Test fun firstRunSentinelFileReadsBack() {
        val f = file("bookmark.txt")
        DbBookmark.write(f, DbBookmark.FIRST_RUN)
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun partialFileMissingSecondLineIsFirstRun() {
        val f = file("bookmark.txt")
        f.writeText("last_torrent_id=42\n")
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun partialFileMissingFirstLineIsFirstRun() {
        val f = file("bookmark.txt")
        f.writeText("last_prefix_id=7\n")
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun emptyFileIsFirstRun() {
        val f = file("bookmark.txt")
        f.writeText("")
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun garbageFileIsFirstRun() {
        val f = file("bookmark.txt")
        f.writeText("hello world\nnonsense\n")
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun nonIntegerValueIsFirstRun() {
        val f = file("bookmark.txt")
        f.writeText("last_torrent_id=abc\nlast_prefix_id=7\n")
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun trailingJunkOnValueIsFirstRun() {
        val f = file("bookmark.txt")
        f.writeText("last_torrent_id=42x\nlast_prefix_id=7\n")
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun negativeValueOtherThanSentinelIsFirstRun() {
        val f = file("bookmark.txt")
        f.writeText("last_torrent_id=-5\nlast_prefix_id=7\n")
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun anyNegativePairIsFirstRun() {
        val f = file("bookmark.txt")
        f.writeText("last_torrent_id=42\nlast_prefix_id=-1\n")
        assertEquals(DbBookmark.FIRST_RUN, DbBookmark.read(f))
    }

    @Test fun largeValuesRoundTrip() {
        val f = file("bookmark.txt")
        val big = DbBookmark(Long.MAX_VALUE, 0)
        DbBookmark.write(f, big)
        assertEquals(big, DbBookmark.read(f))
    }
}
