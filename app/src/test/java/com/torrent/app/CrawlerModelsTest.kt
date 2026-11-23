package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Host-JVM tests for the pure classifier-weights logic: what counts as a
 * present generation, the retry gate around the crawler's `--fetch-models`
 * one-shot, the `.assets_version` stamp, and the dashboard line. No Android
 * deps, no Robolectric.
 */
class CrawlerModelsTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- generation presence ---

    @Test fun missingGenerations_listsBothOnAFreshInstall() {
        val work = tmp.newFolder("crawler")
        assertEquals(listOf("v15", "v13"), CrawlerModels.missingGenerations(work))
        assertEquals(emptyList<String>(), CrawlerModels.presentGenerations(work))
        assertTrue(CrawlerModels.needsFetch(work))
    }

    @Test fun isGenerationPresent_requiresEveryPinnedFile() {
        val work = tmp.newFolder("crawler")
        val dir = File(work, CrawlerConfig.MODELS_SUBDIR).apply { mkdirs() }
        File(dir, "manifest.json").writeBytes(byteArrayOf(1))
        File(dir, "cram.bin").writeBytes(byteArrayOf(1))
        assertFalse(
            "a generation missing its vocab is not present",
            CrawlerModels.isGenerationPresent(work, CrawlerConfig.MODELS_SUBDIR)
        )

        File(dir, "cram_vocab.bin").writeBytes(byteArrayOf(1))
        assertTrue(CrawlerModels.isGenerationPresent(work, CrawlerConfig.MODELS_SUBDIR))
        assertEquals(listOf("v15"), CrawlerModels.presentGenerations(work))
        assertEquals(listOf("v13"), CrawlerModels.missingGenerations(work))
    }

    @Test fun isGenerationPresent_rejectsEmptyFilesAndDirectoriesInPlaceOfAFile() {
        val work = tmp.newFolder("crawler")
        val dir = File(work, CrawlerConfig.MOVIE_MODEL_SUBDIR).apply { mkdirs() }
        for (name in CrawlerModels.GENERATIONS.getValue(CrawlerConfig.MOVIE_MODEL_SUBDIR)) {
            File(dir, name).writeBytes(byteArrayOf(1))
        }
        File(dir, "movie_kind_head.bin").writeBytes(ByteArray(0))
        assertFalse(
            "an empty (interrupted) download is not a present file",
            CrawlerModels.isGenerationPresent(work, CrawlerConfig.MOVIE_MODEL_SUBDIR)
        )

        File(dir, "movie_kind_head.bin").delete()
        File(dir, "movie_kind_head.bin").mkdirs()
        assertFalse(
            "a directory named like the head is not the head",
            CrawlerModels.isGenerationPresent(work, CrawlerConfig.MOVIE_MODEL_SUBDIR)
        )
    }

    @Test fun isGenerationPresent_isFalseForAnUnknownSubdir() {
        assertFalse(CrawlerModels.isGenerationPresent(tmp.newFolder("crawler"), "models/v99"))
    }

    @Test fun needsFetch_isFalseOnceBothGenerationsAreComplete() {
        val work = tmp.newFolder("crawler")
        for ((subdir, files) in CrawlerModels.GENERATIONS) {
            val dir = File(work, subdir).apply { mkdirs() }
            files.forEach { File(dir, it).writeBytes(byteArrayOf(1, 2, 3)) }
        }
        assertFalse(CrawlerModels.needsFetch(work))
        assertEquals(listOf("v15", "v13"), CrawlerModels.presentGenerations(work))
        assertEquals(emptyList<String>(), CrawlerModels.missingGenerations(work))
    }

    // --- the fetch gate ---

    @Test fun shouldFetch_isTrueBeforeAnyFailure() {
        assertTrue(CrawlerModels.shouldFetch(null, 1_000L))
    }

    @Test fun shouldFetch_isGatedByTheFailureRetryInterval() {
        val failedAt = 1_000_000L
        assertFalse(
            "a recent failure must not be retried on every start",
            CrawlerModels.shouldFetch(failedAt, failedAt + 60_000L)
        )
        assertFalse(
            CrawlerModels.shouldFetch(
                failedAt, failedAt + CrawlerModels.FAILURE_RETRY_INTERVAL_MS - 1
            )
        )
        assertTrue(
            "the retry becomes due once the interval elapsed",
            CrawlerModels.shouldFetch(
                failedAt, failedAt + CrawlerModels.FAILURE_RETRY_INTERVAL_MS
            )
        )
    }

    @Test fun fetchFlagIsTheCrawlersOneShotMode() {
        assertEquals("--fetch-models", CrawlerModels.FETCH_FLAG)
    }

    // --- the .assets_version stamp ---

    @Test fun stampValue_carriesTheVersionCodeAndNoModelDir() {
        assertEquals("7|keywords", CrawlerConfig.stampValue(7L))
        assertFalse(CrawlerConfig.stampValue(7L).contains("models/"))
        assertEquals(CrawlerConfig.STAMP_SUFFIX, CrawlerConfig.stampValue(7L).split("|")[1])
    }

    // --- the dashboard line ---

    @Test fun statusLine_reportsPresenceWhenNothingWasAttempted() {
        val present = ModelFetchStatus(
            presentGenerations = listOf("v15", "v13"),
            missingGenerations = emptyList(),
        )
        assertEquals("v15 + v13 present", CrawlerModels.statusLine(present))
        assertEquals("not fetched yet", CrawlerModels.statusLine(ModelFetchStatus()))
        assertEquals(
            "missing v13 — fetched on the next crawler start",
            CrawlerModels.statusLine(
                ModelFetchStatus(presentGenerations = listOf("v15"), missingGenerations = listOf("v13"))
            )
        )
        assertEquals("not fetched yet", CrawlerModels.statusLine(null))
    }

    @Test fun statusLine_showsTheDownloadingGenerationAndTheCrawlersLastLine() {
        val running = ModelFetchStatus(
            state = ModelFetchState.RUNNING,
            running = true,
            lastLine = "models/v13: 128.0 MiB / 478.2 MiB (26%)",
            missingGenerations = listOf("v13"),
        )
        assertEquals(
            "downloading v13 — models/v13: 128.0 MiB / 478.2 MiB (26%)",
            CrawlerModels.statusLine(running)
        )
        assertEquals(
            "downloading v13 — fetching",
            CrawlerModels.statusLine(running.copy(lastLine = ""))
        )
    }

    @Test fun statusLine_reportsSuccessAndFailureHonestly() {
        assertEquals(
            "v15 + v13 present",
            CrawlerModels.statusLine(
                ModelFetchStatus(state = ModelFetchState.OK, presentGenerations = listOf("v15", "v13"))
            )
        )
        assertEquals(
            "incomplete (v13) — retry on next start",
            CrawlerModels.statusLine(
                ModelFetchStatus(
                    state = ModelFetchState.OK,
                    presentGenerations = listOf("v15"),
                    missingGenerations = listOf("v13"),
                )
            )
        )
        assertEquals(
            "download failed: exit 1 — engines inert, retry on next start",
            CrawlerModels.statusLine(ModelFetchStatus(state = ModelFetchState.FAILED, message = "exit 1"))
        )
    }

    // --- the status holder ---

    @Test fun holder_walksRunningProgressAndSuccess() {
        var now = 100L
        val holder = ModelFetchStatusHolder { now }
        assertEquals(ModelFetchState.IDLE, holder.status.state)

        holder.markRunning(listOf("v15"), listOf("v13"))
        assertEquals(ModelFetchState.RUNNING, holder.status.state)
        assertTrue(holder.status.running)
        assertEquals(100L, holder.status.startedAt)
        assertNull("a fresh attempt clears the previous exit code", holder.status.exitCode)

        now = 200L
        holder.markProgress("  50%  ")
        assertEquals("50%", holder.status.lastLine)

        now = 300L
        holder.markOk(listOf("v15", "v13"), emptyList())
        assertEquals(ModelFetchState.OK, holder.status.state)
        assertFalse(holder.status.running)
        assertEquals(300L, holder.status.finishedAt)
        assertNull("success does not arm the retry gate", holder.status.lastFailureAt)
    }

    @Test fun holder_failureStampsTheRetryGateAndKeepsProgressOutWhenIdle() {
        var now = 1_000L
        val holder = ModelFetchStatusHolder { now }
        holder.markProgress("ignored: no attempt is running")
        assertEquals("", holder.status.lastLine)

        holder.markRunning(emptyList(), listOf("v15", "v13"))
        now = 2_000L
        holder.markFailed("exit 1", emptyList(), listOf("v15", "v13"), exitCode = 1)
        assertEquals(ModelFetchState.FAILED, holder.status.state)
        assertEquals(2_000L, holder.status.lastFailureAt)
        assertEquals(1, holder.status.exitCode)
        assertFalse(holder.status.running)
        assertFalse("the gate is armed", CrawlerModels.shouldFetch(holder.status.lastFailureAt, 2_001L))
    }

    @Test fun holder_notePresenceDoesNotClaimAnAttempt() {
        val holder = ModelFetchStatusHolder { 0L }
        holder.notePresence(listOf("v15"), listOf("v13"))
        assertEquals(ModelFetchState.IDLE, holder.status.state)
        assertEquals(listOf("v13"), holder.status.missingGenerations)
        assertFalse(holder.status.running)
    }
}
