package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Pure tests for the [RestoreProgress] resume marker: exact file format,
 * write/read round-trip, atomic write (no `.tmp` left behind, previous marker
 * intact when the write cannot complete), and the corrupt/inconsistent-file
 * fallback to `null`.
 */
class DbRestoreProgressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String): File = File(tmp.root, name)

    private val keys = listOf("incremental_20240101_1-500.sql.gz", "incremental_20240102_501-900.sql.gz")

    @Test fun missingFileIsNull() {
        assertNull(RestoreProgress.read(file("nope.txt")))
    }

    @Test fun writeThenReadRoundTrips() {
        val f = file("progress.txt")
        RestoreProgress.write(f, RestoreProgress(keys, 1))
        assertEquals(RestoreProgress(keys, 1), RestoreProgress.read(f))
    }

    @Test fun writeProducesHeaderThenOneKeyPerLine() {
        val f = file("progress.txt")
        RestoreProgress.write(f, RestoreProgress(keys, 0))
        assertEquals(
            "nextIndex=0\nincremental_20240101_1-500.sql.gz\nincremental_20240102_501-900.sql.gz\n",
            f.readText(),
        )
    }

    @Test fun completedChainReadsBack() {
        val f = file("progress.txt")
        RestoreProgress.write(f, RestoreProgress(keys, keys.size))
        assertEquals(RestoreProgress(keys, 2), RestoreProgress.read(f))
    }

    @Test fun emptyKeyListRoundTrips() {
        val f = file("progress.txt")
        RestoreProgress.write(f, RestoreProgress(emptyList(), 0))
        assertEquals("nextIndex=0\n", f.readText())
        assertEquals(RestoreProgress(emptyList(), 0), RestoreProgress.read(f))
    }

    @Test fun writeCreatesParentDirectories() {
        val f = File(tmp.root, "nested/dir/progress.txt")
        RestoreProgress.write(f, RestoreProgress(keys, 2))
        assertEquals(RestoreProgress(keys, 2), RestoreProgress.read(f))
    }

    @Test fun atomicWriteLeavesNoTmpBehind() {
        val f = file("progress.txt")
        RestoreProgress.write(f, RestoreProgress(keys, 1))
        assertFalse(File(f.path + ".tmp").exists())
        assertTrue(f.exists())
    }

    @Test fun failedTmpWriteLeavesPreviousMarkerIntact() {
        val f = file("progress.txt")
        RestoreProgress.write(f, RestoreProgress(keys, 1))
        // Simulated failure: the temp path is a non-empty directory, so the
        // temp write throws before any rename can happen.
        val blocker = File(f.path + ".tmp")
        assertTrue(blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())
        assertThrows(IOException::class.java) {
            RestoreProgress.write(f, RestoreProgress(keys, 2))
        }
        assertEquals(RestoreProgress(keys, 1), RestoreProgress.read(f))
    }

    @Test fun failedRenameDeletesTmpAndThrows() {
        // A directory at the target path makes renameTo fail after the temp
        // file was written.
        val f = file("progress.txt")
        assertTrue(f.mkdirs())
        assertThrows(IOException::class.java) {
            RestoreProgress.write(f, RestoreProgress(keys, 1))
        }
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test fun emptyFileIsNull() {
        val f = file("progress.txt")
        f.writeText("")
        assertNull(RestoreProgress.read(f))
    }

    @Test fun garbageFileIsNull() {
        val f = file("progress.txt")
        f.writeText("hello world\nsome/key.sql.gz\n")
        assertNull(RestoreProgress.read(f))
    }

    @Test fun nonIntegerIndexIsNull() {
        val f = file("progress.txt")
        f.writeText("nextIndex=abc\nkey.sql.gz\n")
        assertNull(RestoreProgress.read(f))
    }

    @Test fun trailingJunkOnIndexIsNull() {
        val f = file("progress.txt")
        f.writeText("nextIndex=1x\nkey.sql.gz\n")
        assertNull(RestoreProgress.read(f))
    }

    @Test fun negativeIndexIsNull() {
        val f = file("progress.txt")
        f.writeText("nextIndex=-1\nkey.sql.gz\n")
        assertNull(RestoreProgress.read(f))
    }

    @Test fun indexBeyondKeyCountIsNull() {
        val f = file("progress.txt")
        f.writeText("nextIndex=3\nkey1.sql.gz\nkey2.sql.gz\n")
        assertNull(RestoreProgress.read(f))
    }

    @Test fun blankLinesAfterHeaderAreIgnored() {
        val f = file("progress.txt")
        f.writeText("nextIndex=1\nkey1.sql.gz\n\nkey2.sql.gz\n\n")
        assertEquals(RestoreProgress(listOf("key1.sql.gz", "key2.sql.gz"), 1), RestoreProgress.read(f))
    }

    @Test fun writeRejectsNegativeIndex() {
        assertThrows(IllegalArgumentException::class.java) {
            RestoreProgress.write(file("progress.txt"), RestoreProgress(keys, -1))
        }
    }

    @Test fun writeRejectsIndexBeyondKeyCount() {
        assertThrows(IllegalArgumentException::class.java) {
            RestoreProgress.write(file("progress.txt"), RestoreProgress(keys, 3))
        }
    }

    @Test fun overwriteReplacesPreviousMarker() {
        val f = file("progress.txt")
        RestoreProgress.write(f, RestoreProgress(keys, 0))
        RestoreProgress.write(f, RestoreProgress(keys, 2))
        assertEquals(RestoreProgress(keys, 2), RestoreProgress.read(f))
    }
}
