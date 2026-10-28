package com.torrent.app.config

/**
 * Stage 12: the crawler's adult thresholds are probabilities calibrated against one
 * specific classifier model generation, so they are meaningless after a model dir bump
 * (`v3` -> `v4`). This migration resets them to the new generation's
 * defaults exactly once, using the last-seen model dir as its sentinel.
 *
 * The reset is deliberately unconditional — a user-tuned value was tuned against the
 * old model's score distribution and is therefore invalid, not merely stale — and it
 * must happen before the crawler is (re)started so v4 never runs with v3 thresholds.
 *
 * Pure: all state access is injected, so the decision is unit-testable without Android.
 */
class ClassifierModelVersionMigration(
    private val readStoredModelDir: () -> String?,
    private val resetThresholds: () -> Unit,
    private val writeStoredModelDir: (String) -> Unit,
) {
    /**
     * Runs the migration for [currentModelDir]. Returns `true` when thresholds were
     * reset (and the caller must re-render the crawler config), `false` when the stored
     * sentinel already matches — which also makes a partially completed run safe to
     * repeat: the sentinel is written last, so an interrupted run simply resets again.
     */
    fun run(currentModelDir: String): Boolean {
        if (readStoredModelDir() == currentModelDir) return false
        resetThresholds()
        writeStoredModelDir(currentModelDir)
        return true
    }
}
