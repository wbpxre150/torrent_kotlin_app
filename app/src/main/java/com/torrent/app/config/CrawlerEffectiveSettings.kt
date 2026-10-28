package com.torrent.app.config

/**
 * The settings a crawler apply actually persists and renders, given whether the classifier
 * migration just fired.
 *
 * A stored adult threshold was calibrated against the previous model generation's score
 * distribution, so it is invalid rather than merely stale after a generation bump: when [migrated]
 * the current generation's default wins over whatever the user submitted. When nothing migrated the
 * submitted settings pass through untouched.
 *
 * Pure, so the "the reset actually reaches the render" half of the ordering invariant
 * ([CrawlerStartOrder]) is testable without a service.
 */
fun effectiveCrawlerSettingsAfterMigration(
    settings: CrawlerSettings,
    migrated: Boolean,
): CrawlerSettings =
    if (migrated) {
        settings.copy(adultHideThreshold = ConfigDefaults.CRAWLER.adultHideThreshold)
    } else {
        settings
    }
