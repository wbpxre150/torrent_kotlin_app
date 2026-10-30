package com.torrent.app.config

import com.torrent.app.CrawlerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adult thresholds are probabilities calibrated per classifier model generation, so a
 * model-dir bump (`models/v5` -> `models/v6`) invalidates every stored value and must reset
 * them exactly once, before the crawler daemon starts.
 *
 * The fake below is a mutable in-memory store (not a captured constant) so that run->run
 * semantics are expressible: the sentinel is written last, which is what makes an
 * interrupted run safely repeatable and a second run a no-op.
 *
 * The old-generation fixtures (`models/v2`, `models/v3`) are deliberate: they model installs
 * upgraded from an earlier app build.
 */
class ClassifierModelVersionMigrationTest {

    private var stored: String? = null
    private var resets = 0
    private val writes = mutableListOf<String>()

    private fun migration(write: (String) -> Unit = { writes.add(it); stored = it }) =
        ClassifierModelVersionMigration(
            readStoredModelDir = { stored },
            resetThresholds = { resets++ },
            writeStoredModelDir = write,
        )

    @Test fun run_isNoOpWhenTheStoredModelDirAlreadyMatches() {
        stored = "models/v3"
        val ran = migration().run("models/v3")

        assertFalse("an already-migrated install must not reset again", ran)
        assertEquals("thresholds must not be reset", 0, resets)
        assertEquals("the sentinel must not be rewritten", emptyList<String>(), writes)
    }

    @Test fun run_resetsThresholdsAndWritesTheSentinelWhenTheModelDirChanged() {
        stored = "models/v2"
        val ran = migration().run("models/v3")

        assertTrue(ran)
        assertEquals(1, resets)
        assertEquals(listOf("models/v3"), writes)
    }

    @Test fun run_treatsAMissingSentinelAsANeverMigratedInstall() {
        stored = null
        val ran = migration().run("models/v3")

        assertTrue("a fresh install must migrate on the first daemon start", ran)
        assertEquals(1, resets)
        assertEquals(listOf("models/v3"), writes)
    }

    @Test fun run_v5ToV6BumpResetsThresholds() {
        stored = "models/v5"
        val ran = migration().run(CrawlerConfig.MODELS_SUBDIR)

        assertTrue("the shipped v5 -> v6 bump must migrate", ran)
        assertEquals("exactly one reset", 1, resets)
        assertEquals(listOf(CrawlerConfig.MODELS_SUBDIR), writes)
        assertEquals(
            "the persisted sentinel is this build's model generation",
            CrawlerConfig.MODELS_SUBDIR,
            stored,
        )
    }

    @Test fun run_secondCallIsANoOp() {
        stored = "models/v3"
        val underTest = migration()

        assertTrue(underTest.run(CrawlerConfig.MODELS_SUBDIR))
        val ranAgain = underTest.run(CrawlerConfig.MODELS_SUBDIR)

        assertFalse("the reset must happen exactly once per generation", ranAgain)
        assertEquals("thresholds must not be reset twice", 1, resets)
        assertEquals("the sentinel must not be rewritten", listOf(CrawlerConfig.MODELS_SUBDIR), writes)
    }

    @Test fun run_interruptedRunResetsAgain() {
        stored = "models/v3"
        assertThrows(IllegalStateException::class.java) {
            migration(write = { throw IllegalStateException("prefs write lost") })
                .run(CrawlerConfig.MODELS_SUBDIR)
        }
        assertEquals("the reset happened before the failing write", 1, resets)
        assertEquals(
            "the sentinel must not advance to the new generation",
            "models/v3",
            stored,
        )
        assertEquals("nothing was persisted", emptyList<String>(), writes)

        val ranRetry = migration().run(CrawlerConfig.MODELS_SUBDIR)

        assertTrue("a lost sentinel write makes the run safely repeatable", ranRetry)
        assertEquals("the retry resets again", 2, resets)
        assertEquals(listOf(CrawlerConfig.MODELS_SUBDIR), writes)
        assertEquals(CrawlerConfig.MODELS_SUBDIR, stored)
    }

    @Test fun run_hypotheticalV7BumpAlsoResets() {
        stored = "models/v6"
        val ran = migration().run("models/v7")

        assertTrue("a future generation bump must migrate with no logic change", ran)
        assertEquals(1, resets)
        assertEquals(listOf("models/v7"), writes)
        assertEquals("models/v7", stored)
    }

    @Test fun run_callbackSequenceIsExactlyReadResetWrite() {
        // This class's only collaborators are these three injected callbacks (see
        // ClassifierModelVersionMigration.kt:15-31 — the class has no other members), so an
        // exact interaction log is the regression test for "the migration can reach nothing
        // else": no classifier_meta/torrent_class write, no crawler DB delete or recreate.
        val events = mutableListOf<String>()
        fun recording(initialStored: String?) = ClassifierModelVersionMigration(
            readStoredModelDir = { events.add("READ"); initialStored },
            resetThresholds = { events.add("RESET") },
            writeStoredModelDir = { events.add("WRITE") },
        )

        assertTrue(recording("models/v5").run(CrawlerConfig.MODELS_SUBDIR))
        assertEquals("a generation change is read, reset, then write", listOf("READ", "RESET", "WRITE"), events)

        events.clear()
        assertFalse(recording(CrawlerConfig.MODELS_SUBDIR).run(CrawlerConfig.MODELS_SUBDIR))
        assertEquals("a matching sentinel must reach neither the reset nor the write", listOf("READ"), events)
    }
}
