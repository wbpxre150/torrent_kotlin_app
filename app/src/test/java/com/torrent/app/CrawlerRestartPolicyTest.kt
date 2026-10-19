package com.torrent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the "restart a running crawler after a config write" decision
 * ([CrawlerRestartPolicy]).
 *
 * Regression: the first-run wizard's `adult_mode` / `save_categories` writes
 * landed in `config.ini` while the crawler was already running, but nothing
 * relaunched it — the crawler reads both keys only at startup, so it stayed in
 * `tag` mode and kept saving adult torrents.
 *
 * Restart must happen ONLY for a crawler that is up or coming up: a STOPPED
 * crawler (first-run, before `setCrawlerEnabled`) must not be started by a
 * config write, and a STOPPING crawler must be allowed to finish its stop.
 */
class CrawlerRestartPolicyTest {

    @Test fun noSupervisor_noRestart() {
        assertFalse(CrawlerRestartPolicy.shouldRestart(null))
    }

    @Test fun stopped_noRestart() {
        // First run: the write lands before setCrawlerEnabled starts the crawler.
        assertFalse(CrawlerRestartPolicy.shouldRestart(ProcessState.STOPPED))
    }

    @Test fun starting_restarts() {
        assertTrue(CrawlerRestartPolicy.shouldRestart(ProcessState.STARTING))
    }

    @Test fun running_restarts() {
        assertTrue(CrawlerRestartPolicy.shouldRestart(ProcessState.RUNNING))
    }

    @Test fun stopping_noRestart() {
        // Let the in-flight stop finish; the wifi-gate observer restarts it.
        assertFalse(CrawlerRestartPolicy.shouldRestart(ProcessState.STOPPING))
    }
}
