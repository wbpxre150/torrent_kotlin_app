package com.torrent.app.config

/**
 * The crawler's adult thresholds are probabilities calibrated against one specific classifier
 * model generation, so they are meaningless after a bump to a newer generation (this build ships
 * `models/v15`). This migration resets them to the new generation's defaults exactly once, using
 * the last-seen model dir as its sentinel.
 *
 * The reset is deliberately unconditional — a user-tuned value was tuned against the old model's
 * score distribution and is therefore invalid, not merely stale — and it must happen before the
 * crawler is (re)started so v6 never runs with v5 thresholds.
 *
 * It reaches app settings only: the sentinel, the two threshold keys, and the `config.ini`
 * re-render the caller performs when this returns `true`. It must never write the crawler DB
 * (`classifier_meta`/`torrent_class`) — the native backfill detects its work from
 * `classifier_meta.clf_ver` still being the old generation.
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
