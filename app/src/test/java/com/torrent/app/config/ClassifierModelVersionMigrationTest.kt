package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 12: the thresholds are calibrated per model generation, so a model-dir change
 * (models/v1 -> models/v2) invalidates every stored value and must reset them once.
 */
class ClassifierModelVersionMigrationTest {

    private var resets = 0
    private val writes = mutableListOf<String>()

    private fun migration(storedValue: String?) = ClassifierModelVersionMigration(
        readStoredModelDir = { storedValue },
        resetThresholds = { resets++ },
        writeStoredModelDir = { writes.add(it) },
    )

    @Test fun run_isNoOpWhenTheStoredModelDirAlreadyMatches() {
        val ran = migration("models/v2").run("models/v2")

        assertFalse("an already-migrated install must not reset again", ran)
        assertEquals("thresholds must not be reset", 0, resets)
        assertEquals("the sentinel must not be rewritten", emptyList<String>(), writes)
    }

    @Test fun run_resetsThresholdsAndWritesTheSentinelWhenTheModelDirChanged() {
        val ran = migration("models/v1").run("models/v2")

        assertTrue(ran)
        assertEquals(1, resets)
        assertEquals(listOf("models/v2"), writes)
    }

    @Test fun run_treatsAMissingSentinelAsANeverMigratedInstall() {
        val ran = migration(null).run("models/v2")

        assertTrue("a fresh install must migrate on the first daemon start", ran)
        assertEquals(1, resets)
        assertEquals(listOf("models/v2"), writes)
    }
}
