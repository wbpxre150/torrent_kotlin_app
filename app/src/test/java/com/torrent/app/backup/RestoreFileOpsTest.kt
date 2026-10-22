package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure tests for [RestoreFileOps]: the temp-DB swap (content replaced, sidecars
 * of both the target and the temp DB removed, rename failure surfaced) and the
 * stale `restore-*.sql` dump reaping (matches only that exact shape, leaves
 * everything else alone).
 */
class RestoreFileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, text: String? = null): File {
        val f = File(tmp.root, name)
        if (text != null) f.writeText(text)
        return f
    }

    private fun sidecars(db: File): List<File> =
        listOf(File(db.path + "-wal"), File(db.path + "-shm"))

    @Test fun swapInReplacesTargetContentAndRemovesTemp() {
        val tmpDb = file("torrents.db.restore.tmp", "new database bytes")
        val target = file("torrents.db", "old database bytes")

        RestoreFileOps.swapIn(tmpDb, target)

        assertEquals("new database bytes", target.readText())
        assertFalse(tmpDb.exists())
    }

    @Test fun swapInCreatesTargetWhenMissing() {
        val tmpDb = file("torrents.db.restore.tmp", "fresh")
        val target = File(tmp.root, "torrents.db")
        assertFalse(target.exists())

        RestoreFileOps.swapIn(tmpDb, target)

        assertTrue(target.isFile)
        assertEquals("fresh", target.readText())
    }

    @Test fun swapInDeletesTargetSidecars() {
        val tmpDb = file("torrents.db.restore.tmp", "new")
        val target = file("torrents.db", "old")
        for (s in sidecars(target)) s.writeText("stale")

        RestoreFileOps.swapIn(tmpDb, target)

        for (s in sidecars(target)) assertFalse("${s.name} survived", s.exists())
    }

    @Test fun swapInDeletesTempSidecarsDefensively() {
        val tmpDb = file("torrents.db.restore.tmp", "new")
        val target = file("torrents.db", "old")
        for (s in sidecars(tmpDb)) s.writeText("leftover")

        RestoreFileOps.swapIn(tmpDb, target)

        for (s in sidecars(tmpDb)) assertFalse("${s.name} survived", s.exists())
        assertEquals("new", target.readText())
    }

    @Test fun swapInRejectsMissingTempDb() {
        val target = file("torrents.db", "old")
        try {
            RestoreFileOps.swapIn(File(tmp.root, "nope.tmp"), target)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("old", target.readText())
        }
    }

    @Test fun swapInRejectsTempPathThatIsNotAFile() {
        // A directory where the temp DB should be cannot be renamed into place:
        // the swap must fail loudly instead of reporting a successful restore.
        val tmpDir = tmp.newFolder("vanishing")
        val tmpDb = File(tmpDir, "torrents.db.restore.tmp")
        tmpDb.writeText("new")
        val target = file("torrents.db", "old")
        assertTrue(tmpDb.delete())
        tmpDb.mkdir() // a DIRECTORY is not a regular file
        try {
            RestoreFileOps.swapIn(tmpDb, target)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("old", target.readText())
        }
    }

    @Test fun reapDeletesRestoreSqlDumps() {
        val a = file("restore-1700000000000.sql", "dump a")
        val b = file("restore-1700000000001.sql", "dump b")

        val reaped = RestoreFileOps.reapStaleSqlDumps(tmp.root)

        assertEquals(2, reaped)
        assertFalse(a.exists())
        assertFalse(b.exists())
    }

    @Test fun reapLeavesUnrelatedFilesAlone() {
        val keep = listOf(
            file("torrents.db", "db"),
            file("other.sql", "sql"),
            file("restore-1.sql.tmp", "partial"),
            file("restore-progress.txt", "marker"),
            file("notes.txt", ""),
        )
        val subdir = File(tmp.root, "restore-2.sql")
        assertTrue(subdir.mkdir())
        file("restore-3.sql", "dump")

        val reaped = RestoreFileOps.reapStaleSqlDumps(tmp.root)

        assertEquals(1, reaped)
        for (f in keep) assertTrue("${f.name} was reaped", f.exists())
        assertTrue(subdir.isDirectory)
    }

    @Test fun reapMissingDirectoryReapsNothing() {
        assertEquals(0, RestoreFileOps.reapStaleSqlDumps(File(tmp.root, "no-such-dir")))
    }

    @Test fun reapEmptyDirectoryReapsNothing() {
        assertEquals(0, RestoreFileOps.reapStaleSqlDumps(tmp.root))
    }

    @Test fun deleteDbSidecarsDeletesOnlySidecars() {
        val db = file("torrents.db", "db")
        for (s in sidecars(db)) s.writeText("side")

        RestoreFileOps.deleteDbSidecars(db)

        for (s in sidecars(db)) assertFalse("${s.name} survived", s.exists())
        assertTrue(db.exists())
    }

    @Test fun deleteDbSidecarsToleratesMissingFiles() {
        val db = File(tmp.root, "absent.db")
        RestoreFileOps.deleteDbSidecars(db)
        assertNull(tmp.root.listFiles()?.firstOrNull { it.name.startsWith("absent.db") })
    }

    @Test fun targetDbExistsNeedsANonEmptyRegularFile() {
        assertFalse(RestoreFileOps.targetDbExists(File(tmp.root, "missing.db")))
        assertFalse(RestoreFileOps.targetDbExists(file("empty.db", "")))
        assertTrue(RestoreFileOps.targetDbExists(file("real.db", "x")))
        assertFalse(RestoreFileOps.targetDbExists(tmp.newFolder("a-directory")))
    }
}
