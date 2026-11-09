package com.torrent.app

import com.torrent.app.backup.BackupState
import com.torrent.app.backup.BackupStatus
import com.torrent.app.backup.BootstrapState
import com.torrent.app.backup.BootstrapStatus
import com.torrent.app.backup.DbBackupState
import com.torrent.app.backup.DbBackupStatus
import com.torrent.app.backup.DbRestoreState
import com.torrent.app.backup.DbRestoreStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape tests for the /settings/crawler/ws frame (Stage 4). Plain JUnit4: the
 * builder is a pure function with no Android dependency, so the frame contract
 * can be pinned without a server, a socket or Robolectric.
 *
 * One frame is a FULL-STATE snapshot — `ts` plus the apply status, the
 * maintenance status/console and the four backup families — so a reconnecting
 * client needs no offset bookkeeping and Stage 6 can add backup fields to the
 * family sub-objects without changing this contract.
 */
class ControlServerSettingsFrameTest {

    private fun frame(
        apply: CrawlerApplyStatus = CrawlerApplyStatus(),
        maint: MaintenanceStatus = MaintenanceStatus(),
        bep51: BackupStatus = BackupStatus(),
        db: DbBackupStatus = DbBackupStatus(),
        dbInFlight: Boolean = false,
        restore: DbRestoreStatus = DbRestoreStatus(),
        bootstrap: BootstrapStatus? = null,
    ): String = ControlServer.crawlerSettingsFrameJson(
        now = 1_700_000_000_000L,
        apply = apply,
        maint = maint,
        bep51 = bep51,
        db = db,
        dbInFlight = dbInFlight,
        restore = restore,
        bootstrap = bootstrap,
    )

    @Test fun frame_alwaysCarriesTsAndEveryTopLevelFamily() {
        val json = frame()
        assertEquals('{', json.first())
        assertEquals('}', json.last())
        assertTrue("ts must lead the frame: $json", json.startsWith("{\"ts\":1700000000000,"))
        for (key in listOf(
            "\"apply\":{", "\"maint\":{", "\"bep51\":{",
            "\"db\":{", "\"restore\":{", "\"bootstrap\":{",
        )) {
            assertTrue("frame is missing $key: $json", json.contains(key))
        }
    }

    @Test fun apply_terminalStatusCarriesSeqTimestampsAndFlags() {
        val json = frame(
            apply = CrawlerApplyStatus(
                phase = CrawlerApplyPhase.OK,
                message = "applied; crawler running",
                logTail = listOf("line \"one\"", "line two"),
                seq = 7L,
                startedAt = 1_699_999_000_000L,
                finishedAt = 1_699_999_060_000L,
                terminal = true,
                pausedNoWifi = false,
            ),
        )
        assertTrue(json.contains("\"apply\":{\"phase\":\"OK\",\"seq\":7,"))
        assertTrue(json.contains("\"startedAt\":1699999000000"))
        assertTrue(json.contains("\"finishedAt\":1699999060000"))
        assertTrue(json.contains("\"terminal\":true"))
        assertTrue(json.contains("\"pausedNoWifi\":false"))
        // The log tail is JSON-escaped, never spliced raw.
        assertTrue("log tail must be escaped: $json", json.contains("line \\\"one\\\""))
    }

    @Test fun apply_runningStatusHasNoFinishStampAndIsNotTerminal() {
        val json = frame(
            apply = CrawlerApplyStatus(
                phase = CrawlerApplyPhase.WATCHING,
                message = "watching for early exit (30 s)",
                seq = 4L,
                startedAt = 1_699_999_000_000L,
                finishedAt = null,
                terminal = false,
                pausedNoWifi = true,
            ),
        )
        assertTrue(json.contains("\"phase\":\"WATCHING\""))
        assertTrue(json.contains("\"seq\":4"))
        assertTrue(json.contains("\"finishedAt\":null"))
        assertTrue(json.contains("\"terminal\":false"))
        assertTrue(json.contains("\"pausedNoWifi\":true"))
    }

    @Test fun maint_carriesParsedProgressAndTheFullConsole() {
        val json = frame(
            maint = MaintenanceStatus(
                action = MaintenanceAction.REBUILD_BLOOM_FILTER,
                running = true,
                startedAt = 111L,
                message = "rebuilding bloom filter",
                phase = "scan",
                percent = 42,
                done = 42L,
                total = 100L,
                lines = listOf(
                    "[MPROG] action=rebuild-bloom-filter phase=scan done=42 total=100 pct=42"),
            ),
        )
        assertTrue(
            "maint must lead with action/running: $json",
            json.contains("\"maint\":{\"action\":\"rebuild-bloom-filter\",\"running\":true,"))
        assertTrue(json.contains("\"phase\":\"scan\""))
        assertTrue(json.contains("\"percent\":42"))
        assertTrue(json.contains("\"done\":42"))
        assertTrue(json.contains("\"total\":100"))
        assertTrue(json.contains("\"totalLines\":1"))
        assertTrue(json.contains("[MPROG] action=rebuild-bloom-filter"))
    }

    @Test fun maint_phaseOnlyActionKeepsPercentNull() {
        // check-database has no denominator: phase-only is the honest state.
        val json = frame(
            maint = MaintenanceStatus(
                action = MaintenanceAction.CHECK_DATABASE,
                running = true,
                phase = "integrity_check",
                lines = listOf("checking database integrity..."),
            ),
        )
        assertTrue(json.contains("\"phase\":\"integrity_check\""))
        assertTrue("no denominator must mean percent:null: $json", json.contains("\"percent\":null"))
        assertTrue(json.contains("\"done\":null"))
        assertTrue(json.contains("\"total\":null"))
    }

    @Test fun backupFamilies_keepTheirOwnShapes() {
        val json = frame(
            bep51 = BackupStatus(
                state = BackupState.SUCCESS,
                message = "uploaded",
                uploadedBytes = 12L,
                configured = true,
            ),
            db = DbBackupStatus(
                state = DbBackupState.RUNNING,
                objectKey = "db/000001.sql",
                configured = true,
            ),
            dbInFlight = true,
            restore = DbRestoreStatus(
                state = DbRestoreState.RUNNING,
                running = true,
                objectCount = 4,
                objectsDone = 2,
                startedAt = 5L,
            ),
            bootstrap = BootstrapStatus(
                state = BootstrapState.OK,
                message = "placed 10 bytes",
                startedAt = 1L,
                finishedAt = 2L,
            ),
        )
        assertTrue(json.contains("\"bep51\":{\"state\":\"SUCCESS\""))
        assertTrue(json.contains("\"bytes\":12"))
        assertTrue(json.contains("\"db\":{\"state\":\"RUNNING\""))
        assertTrue(json.contains("\"objectKey\":\"db/000001.sql\""))
        // Stage 4's only extra db field; Stage 6 adds the manager's own counters.
        assertTrue(json.contains("\"inFlight\":true"))
        assertTrue(json.contains("\"restore\":{\"state\":\"RUNNING\",\"running\":true"))
        assertTrue(json.contains("\"objectsDone\":2"))
        assertTrue(json.contains("\"bootstrap\":{\"state\":\"OK\",\"message\":\"placed 10 bytes\""))
    }

    @Test fun backupFamilies_carryTheLiveRunFields() {
        // Stage 6: the managers publish their own running/startedAt truth (plus
        // the DB drain's per-chunk counters and the bootstrap cache's size).
        // Phase-only families stay percentage-free by design.
        val json = frame(
            bep51 = BackupStatus(
                state = BackupState.RUNNING,
                message = "uploading",
                running = true,
                startedAt = 11L,
                configured = true,
            ),
            db = DbBackupStatus(
                state = DbBackupState.RUNNING,
                message = "uploading",
                running = true,
                startedAt = 12L,
                chunks = 2,
                totalBytes = 4_096L,
                totalStatements = 7,
                configured = true,
            ),
            dbInFlight = true,
            bootstrap = BootstrapStatus(
                state = BootstrapState.RUNNING,
                message = "downloading",
                startedAt = 13L,
                running = true,
                lastPlacedNodeCount = 9,
                lastPlacedBytes = 512L,
                lastFailureAt = 14L,
            ),
        )
        assertTrue(json.contains("\"bep51\":{\"state\":\"RUNNING\""))
        assertTrue(json.contains("\"running\":true,\"startedAt\":11}"))
        assertTrue(json.contains("\"db\":{\"state\":\"RUNNING\""))
        assertTrue(json.contains("\"chunks\":2"))
        assertTrue(json.contains("\"totalBytes\":4096"))
        assertTrue(json.contains("\"totalStatements\":7"))
        assertTrue(json.contains("\"inFlight\":true"))
        assertTrue(json.contains("\"bootstrap\":{\"state\":\"RUNNING\""))
        assertTrue(json.contains("\"nodeCount\":9"))
        assertTrue(json.contains("\"bytes\":512"))
        assertTrue(json.contains("\"lastFailureAt\":14}"))
        // No invented percentage in any backup family: only `maint` may carry
        // one, and only where its action has a denominator.
        assertTrue(!json.contains("\"db\":{\"state\":\"RUNNING\",\"running\""))
        assertTrue(!json.substringAfter("\"db\":").substringBefore("\"restore\":")
            .contains("percent"))
    }

    @Test fun bootstrap_isIdleWhenNothingIsRecorded() {
        // A null supplier (no download recorded yet) still renders as an honest
        // IDLE object carrying the Stage 6 live fields.
        val json = frame(bootstrap = null)
        assertTrue(json.contains("\"bootstrap\":{\"state\":\"IDLE\",\"message\":\"\","))
    }
}
