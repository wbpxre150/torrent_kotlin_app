package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the crawler start invariant: model staging, then the classifier migration (which re-renders
 * `config.ini` when it resets), and only then the daemon start. A recording fake captures the step
 * order, because the real ordering lives inside [com.torrent.app.DaemonService] where no unit test
 * can construct it.
 */
class CrawlerStartOrderTest {

    private enum class Step { PREPARE, MIGRATE, START }

    private val steps = mutableListOf<Step>()

    private fun order(migrated: Boolean = false) = CrawlerStartOrder(
        prepare = { steps.add(Step.PREPARE) },
        migrate = { steps.add(Step.MIGRATE); migrated },
        start = { steps.add(Step.START) },
    )

    @Test fun run_executesPrepareThenMigrateThenStart() {
        order().run()

        assertEquals(
            "the daemon must never start before the migration has re-rendered config.ini",
            listOf(Step.PREPARE, Step.MIGRATE, Step.START),
            steps,
        )
    }

    @Test fun run_startsEvenWhenNothingMigrated() {
        val migrated = order(migrated = false).run()

        assertFalse(migrated)
        assertEquals(
            "an already-migrated install still stages models and starts the daemon",
            listOf(Step.PREPARE, Step.MIGRATE, Step.START),
            steps,
        )
    }

    @Test fun run_propagatesTheMigrateResult() {
        assertTrue("a fired migration must be visible to the caller", order(migrated = true).run())
        assertEquals(listOf(Step.PREPARE, Step.MIGRATE, Step.START), steps)
    }
}
