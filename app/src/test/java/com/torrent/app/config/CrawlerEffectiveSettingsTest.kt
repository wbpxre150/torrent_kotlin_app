package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the apply-path half of the ordering invariant: when the classifier migration fires, the
 * generation's default threshold — never the value the user submitted — is what gets persisted and
 * rendered into `config.ini`.
 *
 * The expectation references [ConfigDefaults.CRAWLER] rather than a literal, so a future generation
 * bump needs no edit here.
 */
class CrawlerEffectiveSettingsTest {

    private val submitted = CrawlerSettings(adultHideThreshold = 0.5)

    @Test fun effective_migratedOverridesTheSubmittedThresholdWithTheGenerationDefault() {
        val effective = effectiveCrawlerSettingsAfterMigration(submitted, migrated = true)

        assertEquals(
            "a threshold tuned against the previous generation must not survive the migration",
            ConfigDefaults.CRAWLER.adultHideThreshold,
            effective.adultHideThreshold,
            0.0,
        )
    }

    @Test fun effective_notMigratedKeepsTheSubmittedThreshold() {
        val effective = effectiveCrawlerSettingsAfterMigration(submitted, migrated = false)

        assertEquals(
            "no migration means the user's value is still valid for this generation",
            0.5,
            effective.adultHideThreshold,
            0.0,
        )
        assertEquals("the rest of the settings must pass through untouched", submitted, effective)
    }
}
