package com.torrent.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JUnit tests for the bulk-import PRAGMA sequence (plan 3, Stage 10).
 *
 * [SqliteRestoreSink] itself is an Android class exercised on-device; what is
 * pinned here is the pure data it executes ([RestorePragmas]): the order of the
 * bulk-phase pragmas, the absence of the removed no-op `page_size` pragma and
 * of `synchronous=NORMAL`, and the FULL-sync durability pragma that precedes
 * the single final checkpoint.
 */
class SqliteRestoreSinkTest {

    @Test fun bulkPhaseRunsInTheDocumentedOrder() {
        assertEquals(
            listOf(
                "PRAGMA foreign_keys=OFF",
                "PRAGMA synchronous=OFF",
                "PRAGMA cache_size=-64000",
                "PRAGMA wal_autocheckpoint=0",
            ),
            RestorePragmas.bulkPhase)
    }

    @Test fun bulkPhaseDisablesAutocheckpointLastSoTheWalIsAnAppendLog() {
        // wal_autocheckpoint=0 is the crawler's own database_init setting: it
        // keeps a per-chunk COMMIT a page-cache append instead of fighting
        // incremental checkpoints of a growing WAL back into the DB on slow
        // flash. Nothing may follow it that could re-enable checkpointing.
        assertTrue(RestorePragmas.bulkPhase.contains("PRAGMA wal_autocheckpoint=0"))
        assertEquals("PRAGMA wal_autocheckpoint=0", RestorePragmas.bulkPhase.last())
    }

    @Test fun bulkPhaseUsesSynchronousOffInsteadOfNormal() {
        assertTrue(RestorePragmas.bulkPhase.contains("PRAGMA synchronous=OFF"))
        assertFalse(
            RestorePragmas.bulkPhase.toString(),
            RestorePragmas.bulkPhase.any { it.contains("NORMAL") })
    }

    @Test fun bulkPhaseNoLongerSetsTheIneffectivePageSize() {
        // PRAGMA page_size is a no-op here: openDatabase(CREATE_IF_NECESSARY)
        // initialises the header before the pragma runs (measured on-device:
        // page_size stayed 4096), so the pragma was removed rather than chased.
        assertFalse(
            RestorePragmas.bulkPhase.toString(),
            RestorePragmas.bulkPhase.any { it.contains("page_size") })
    }

    @Test fun finalDurabilityIsFullAndPrecedesTheSingleCheckpoint() {
        // The one-time fsync of the whole DB is exactly the cost the bulk phase
        // deferred; it runs immediately before the single TRUNCATE checkpoint.
        assertEquals("PRAGMA synchronous=FULL", RestorePragmas.FINAL_DURABILITY)
        assertEquals("PRAGMA wal_checkpoint(TRUNCATE)", RestorePragmas.FINAL_CHECKPOINT)
        // The bulk phase keeps the crawler's 64 MiB page cache.
        assertEquals(-64000, RestorePragmas.CACHE_SIZE_KIB)
    }
}
