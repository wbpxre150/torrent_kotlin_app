package com.torrent.app.backup

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Regression tests for [SqliteRestoreSink] against a REAL
 * `android.database.sqlite.SQLiteDatabase` (Robolectric runs the framework's own
 * SQLite classes over the AOSP SQLite build, so the statement handling here is
 * the device's).
 *
 * The bug this pins (build `3f06bc5`, on-device): `prepare()` applied
 * `PRAGMA wal_autocheckpoint=0` with [SQLiteDatabase.execSQL], which the
 * framework rejects for any statement that returns a result row —
 * `SQLiteException: unknown error (code 0 SQLITE_OK): Queries can be performed
 * using SQLiteDatabase query or rawQuery methods only.` `wal_autocheckpoint`
 * returns its new value as one row, so EVERY restore (fresh and resumed) failed
 * immediately after the temp DB was opened, surfacing as
 * "restore failed: unknown error (code 0 SQLITE_OK) …". The pure-JUnit tests in
 * [SqliteRestoreSinkTest] could not see it because they never open a database;
 * these do.
 *
 * They also assert the pragmas actually TOOK EFFECT (read back off the live
 * connection / re-read from the finished file), not merely that no exception
 * was thrown.
 */
@RunWith(RobolectricTestRunner::class)
class SqliteRestoreSinkRobolectricTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun tempDb(): File = File(File(tmp.root, "crawler/data"), "torrents.db.restore.tmp")

    private fun insertTorrent(id: Int): String =
        "INSERT OR IGNORE INTO torrents (id, info_hash, name, size_bytes, total_peers, " +
            "added_timestamp) VALUES ($id, x'${"%040x".format(id)}', 'torrent-$id', 1024, 3, $id)"

    @Test fun freshChunkResumeAndFinishWorkOnARealDatabaseAndThePragmasTakeEffect() {
        val dbFile = tempDb()

        // ---- fresh import: prepare() used to throw here (wal_autocheckpoint) --
        val fresh = SqliteRestoreSink(dbFile)
        fresh.prepare(fresh = true)
        // journal_mode is what the sink asked for and read back; the other two
        // are per-connection settings read back off the sink's own connection.
        assertEquals("wal", fresh.journalMode)
        assertEquals("0", fresh.walAutocheckpoint) // 0 = autocheckpoint disabled
        assertEquals("0", fresh.bulkSynchronous) // 0 = synchronous OFF
        assertTrue("page_size=${fresh.pageSize}", (fresh.pageSize.toIntOrNull() ?: 0) > 0)

        fresh.beginChunk()
        fresh.statement(insertTorrent(1))
        fresh.endChunk()

        // A kill between chunks: the handle is closed, the temp DB is kept.
        fresh.suspendImport()

        // ---- resume: prepare(fresh = false) must apply the same session ------
        val resumed = SqliteRestoreSink(dbFile)
        resumed.prepare(fresh = false)
        // The two per-connection pragmas are the regression targets: they must be
        // re-applied on a resume (and must not throw on the way in).
        assertEquals("0", resumed.walAutocheckpoint)
        assertEquals("0", resumed.bulkSynchronous)
        // journal_mode is only READ on the resume path. Robolectric's SQLite
        // re-applies journal_mode=MEMORY on every new connection, so a reopened
        // WAL file reports "memory" here even though the on-disk header stays WAL
        // on a device — assert only that the read succeeded.
        assertNotEquals("unknown", resumed.journalMode)

        resumed.beginChunk()
        resumed.statement(insertTorrent(2))
        resumed.endChunk()

        // ---- finishImport: deferred indexes, synchronous=FULL, one checkpoint -
        val summary = resumed.finishImport()
        assertEquals("2", resumed.checkpointSynchronous) // 2 = FULL
        assertTrue(
            summary.toString(),
            summary.any { it.startsWith("wal_checkpoint(TRUNCATE): busy=0") })
        assertTrue(
            summary.toString(),
            summary.any {
                it.contains("synchronous=2 for the final checkpoint (bulk phase ran at 0)")
            })
        assertTrue(
            summary.toString(),
            summary.any { it.contains("created index idx_torrents_added in ") })

        // The imported rows are in the finished file (a fresh connection reads
        // them back), i.e. the chunks were really committed and checkpointed.
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT COUNT(*) FROM torrents", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(2, c.getInt(0))
            }
        }
    }

    @Test fun aRowReturningPragmaIsRejectedByExecSqlAndAcceptedByRawQuery() {
        // Pins the framework behaviour the fix rests on, so a future "simplify
        // back to execSQL" fails here with the device's exact error message.
        val dbFile = tempDb()
        val sink = SqliteRestoreSink(dbFile)
        sink.prepare(fresh = true)
        assertEquals("0", sink.walAutocheckpoint)
        assertEquals("0", sink.bulkSynchronous)

        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            // foreign_keys / synchronous / cache_size are row-less: execSQL is
            // fine for them (and rawQuery works too — the sink uses rawQuery).
            d.execSQL("PRAGMA foreign_keys=OFF")
            d.execSQL("PRAGMA synchronous=OFF")
            d.execSQL("PRAGMA cache_size=-64000")

            // wal_autocheckpoint returns a row: execSQL MUST fail, rawQuery works
            // and applies the setting.
            var rejected: Exception? = null
            try {
                d.execSQL("PRAGMA wal_autocheckpoint=0")
            } catch (e: Exception) {
                rejected = e
            }
            assertTrue("execSQL accepted a row-returning pragma", rejected != null)
            assertTrue(
                rejected!!.message ?: "",
                (rejected!!.message ?: "").contains(
                    "Queries can be performed using SQLiteDatabase query or rawQuery methods only"))

            d.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            d.rawQuery("PRAGMA wal_autocheckpoint", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            d.rawQuery("PRAGMA synchronous", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0)) // still OFF
            }
        }
        sink.suspendImport()
        assertTrue(dbFile.isFile)
        assertFalse(dbFile.length() == 0L)
    }
}
