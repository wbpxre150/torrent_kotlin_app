package com.torrent.app.migration

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.torrent.app.CrawlerConfig
import com.torrent.app.OneShotRunner
import com.torrent.app.config.CrawlerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the TermuxMigration coordinator's synchronous decisions (plan 10
 * step 6) with a capturing executor, so no job body ever runs (no real
 * SAF tree, no binary): tree authority checks, action dispatch, mutual
 * exclusion, cancel, and state restore after a service restart.
 */
@RunWith(RobolectricTestRunner::class)
class TermuxMigrationTest {

    /** Captures submitted runnables without running them. */
    private class CapturingExecutor : Executor {
        val submitted = AtomicInteger(0)
        @Volatile var last: Runnable? = null
        override fun execute(command: Runnable) {
            submitted.incrementAndGet()
            last = command
        }
    }

    private lateinit var context: Context
    private lateinit var executor: CapturingExecutor
    private lateinit var grant: CrawlerTreeGrant
    private lateinit var migration: TermuxMigration

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CrawlerTreeGrant.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Start from a clean crawler work dir (fresh state file).
        context.filesDir.resolve("crawler").deleteRecursively()
        executor = CapturingExecutor()
        grant = CrawlerTreeGrant(context)
        migration = TermuxMigration(
            context = context,
            oneShotRunner = OneShotRunner("libdhtcrawler.so", "/nonexistent"),
            executor = executor,
            crawlerConfig = CrawlerConfig(context),
            grant = grant,
            freeSpace = { Long.MAX_VALUE },
            stopCrawler = {},
            startCrawlerIfEnabled = {},
            applyCrawler = { _: CrawlerSettings ->
                com.torrent.app.config.ApplyResult(true, "applied", false)
            },
            currentSettings = { CrawlerSettings() },
            crawlerRunning = { false },
            statsReachable = { false },
        )
    }

    @Test fun setTree_termuxAuthority_hasNoWarning() {
        val uri = Uri.parse("content://com.termux.documents/tree/abc123")
        migration.setTree(uri)
        assertEquals(MigrationPhase.READY, migration.status.phase)
        assertFalse(migration.status.nonTermuxWarning)
        assertEquals("com.termux.documents", migration.status.treeAuthority)
        assertEquals(uri.toString(), migration.status.treeUri)
    }

    @Test fun setTree_otherAuthority_isAcceptedWithAWarning() {
        val uri = Uri.parse("content://com.example.files/tree/xyz")
        migration.setTree(uri)
        assertEquals(MigrationPhase.READY, migration.status.phase)
        assertTrue(migration.status.nonTermuxWarning)
    }

    @Test fun action_unknownName_returnsUnknownMessage() {
        assertTrue(
            migration.action("fly-to-the-moon").startsWith("unknown migration action"))
        assertEquals(0, executor.submitted.get())
    }

    @Test fun action_scan_withoutTree_failsWhenTheJobRuns() {
        assertEquals("started: scan", migration.action("scan"))
        assertEquals(1, executor.submitted.get())
        assertFalse(migration.isBusy().not()) // SCANNING is an active phase
        executor.last?.run()
        assertEquals(MigrationPhase.FAILED, migration.status.phase)
        assertTrue(migration.status.message, migration.status.message.startsWith("no tree granted"))
        assertFalse(migration.isBusy())
    }

    @Test fun action_secondAction_refusedWhileOneIsRunning() {
        migration.action("scan") // stays SCANNING: the executor never runs it
        assertEquals("refused: a migration job is already running", migration.action("copy"))
        assertEquals("refused: a migration job is already running", migration.action("verify"))
        assertEquals("only the scan job may be queued", 1, executor.submitted.get())
    }

    @Test fun action_cancel_withoutACopyReportsNoneInProgress() {
        assertEquals("cancel requested", migration.action("cancel"))
        assertEquals("no copy in progress", migration.action("cancel"))
    }

    @Test fun restoreState_activePhase_isMarkedFailed() {
        val workDir = context.filesDir.resolve("crawler")
        workDir.mkdirs()
        workDir.resolve(TermuxMigration.STATE_FILE)
            .writeText("""{"phase":"COPYING","torrentCount":42,"bloomRebuilt":false}""")
        val restored = TermuxMigration(
            context = context,
            oneShotRunner = OneShotRunner("libdhtcrawler.so", "/nonexistent"),
            executor = executor,
            crawlerConfig = CrawlerConfig(context),
            grant = grant,
            freeSpace = { Long.MAX_VALUE },
            stopCrawler = {},
            startCrawlerIfEnabled = {},
            applyCrawler = { _: CrawlerSettings ->
                com.torrent.app.config.ApplyResult(true, "applied", false)
            },
            currentSettings = { CrawlerSettings() },
            crawlerRunning = { false },
            statsReachable = { false },
        )
        assertEquals(MigrationPhase.FAILED, restored.status.phase)
        assertTrue(restored.status.message, restored.status.message.contains("service restarted"))
        assertEquals(42L, restored.status.torrentCount)
    }

    @Test fun treeGrant_persistsAndReleases() {
        val uri = Uri.parse("content://com.termux.documents/tree/abc123")
        grant.grant(uri)
        assertEquals(uri, grant.treeUri())
        grant.clear()
        assertEquals(null, grant.treeUri())
    }
}
