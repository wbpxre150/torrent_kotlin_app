package com.torrent.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrawlerKeysTest {

    @Test fun parseAdvanced_parsesValidEntriesWithComments() {
        val r = CrawlerKeys.parseAdvanced(
            "# tuning\n" +
                "batch_size = 750\n" +
                "; another comment\n" +
                "\n" +
                "use_thread_trees=1\n" +
                "bep51_node_cooldown_sec=5\n"
        )
        assertTrue(r.ok)
        assertEquals(
            mapOf(
                "batch_size" to "750",
                "use_thread_trees" to "1",
                "bep51_node_cooldown_sec" to "5",
            ),
            r.entries
        )
    }

    @Test fun parseAdvanced_emptyTextIsOk() {
        val r = CrawlerKeys.parseAdvanced("")
        assertTrue(r.ok)
        assertTrue(r.entries.isEmpty())
    }

    @Test fun parseAdvanced_rejectsMalformedLines() {
        val r = CrawlerKeys.parseAdvanced("batch_size\n")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("malformed") })
    }

    @Test fun parseAdvanced_rejectsUnknownKey() {
        val r = CrawlerKeys.parseAdvanced("not_a_key=1")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("unknown key") && it.contains("not_a_key") })
    }

    @Test fun parseAdvanced_rejectsPathKeys() {
        // (backup_path is also a backup key and is covered by the backup test)
        for (key in listOf(
            "db_path", "bloom_path", "bep51_cache_path",
            "classifier_model_dir", "hard_block_file"
        )) {
            val r = CrawlerKeys.parseAdvanced("$key=/tmp/x")
            assertFalse("$key must be rejected", r.ok)
            assertTrue(r.errors.any { it.contains(key) && it.contains("path key") })
        }
    }

    @Test fun parseAdvanced_rejectsBackupKeys() {
        for (key in listOf("backup_enabled", "backup_path", "ssh_backup_enabled", "ssh_host", "rclone_backup_enabled", "rclone_remote")) {
            val r = CrawlerKeys.parseAdvanced("$key=1")
            assertFalse("$key must be rejected", r.ok)
            assertTrue(r.errors.any { it.contains(key) && it.contains("backup") })
        }
    }

    @Test fun parseAdvanced_rejectsFormManagedKeysWithFormHint() {
        for (key in listOf(
            "http_port", "http_bind", "num_trees", "bloom_capacity", "log_level",
            "classifier_enabled", "adult_mode", "adult_hide_threshold"
        )) {
            val r = CrawlerKeys.parseAdvanced("$key=1")
            assertFalse("$key must be rejected", r.ok)
            assertTrue(r.errors.any { it.contains(key) && it.contains("form", true) })
        }
    }

    @Test fun knownKeys_carryTheCategorySelectionAndDroppedPornFilterKeys() {
        assertTrue("save_categories must be known", "save_categories" in CrawlerKeys.KNOWN_KEYS)
        assertTrue("save_tv_kinds must be known", "save_tv_kinds" in CrawlerKeys.KNOWN_KEYS)
        // The crawler no longer parses these (remove-porn-filter patch): a key it
        // would ignore must not be offered as known or overridable.
        for (key in listOf("porn_filter_enabled", "porn_filter_keyword_file")) {
            assertFalse("$key must no longer be known", key in CrawlerKeys.KNOWN_KEYS)
            assertFalse("$key must not be path-managed", key in CrawlerKeys.PATH_KEYS)
            assertFalse("$key must not be form-managed", key in CrawlerKeys.FORM_MANAGED_KEYS)
            assertFalse("$key must not be advanced-overridable", key in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        }
        // The language-filter alias survives the porn-filter removal.
        assertTrue("porn_filter_non_latin_threshold", "porn_filter_non_latin_threshold" in CrawlerKeys.KNOWN_KEYS)
    }

    @Test fun categorySelectionKeys_areFormManaged() {
        for (key in listOf("save_categories", "save_tv_kinds")) {
            assertTrue("$key must be form-managed", key in CrawlerKeys.FORM_MANAGED_KEYS)
            assertFalse("$key must not be path-managed", key in CrawlerKeys.PATH_KEYS)
            assertFalse("$key must not be backup-managed", key in CrawlerKeys.BACKUP_KEYS)
            assertFalse("$key must not be advanced-overridable", key in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        }
        val r = CrawlerKeys.parseAdvanced("save_categories=tv")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("save_categories") && it.contains("form", true) })
    }

    @Test fun taxonomy_matchesTheCrawlerTables() {
        assertEquals(
            listOf(
                "unknown", "movie", "tv", "anime", "music", "audiobook",
                "ebook", "comic", "software", "game", "image", "other"
            ),
            CrawlerTaxonomy.CATEGORIES
        )
        assertEquals(
            listOf(
                "none", "episode", "multi_episode", "season_pack", "multi_season",
                "complete_series", "daily"
            ),
            CrawlerTaxonomy.TV_KINDS
        )
        assertEquals("category names must be unique", 12, CrawlerTaxonomy.CATEGORIES.toSet().size)
        assertEquals("tv-kind names must be unique", 7, CrawlerTaxonomy.TV_KINDS.toSet().size)
    }

    @Test fun parseAdvanced_allowsLanguageFilterAlias() {
        val r = CrawlerKeys.parseAdvanced("porn_filter_non_latin_threshold=40")
        assertTrue(r.ok)
        assertEquals("40", r.entries["porn_filter_non_latin_threshold"])
    }
    @Test fun parseAdvanced_allowsUdpRateLimitKey() {
        val r = CrawlerKeys.parseAdvanced("udp_rate_limit_pps=800")
        assertTrue(r.ok)
        assertEquals("800", r.entries["udp_rate_limit_pps"])
        assertTrue("udp_rate_limit_pps must be advanced-overridable", "udp_rate_limit_pps" in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        assertFalse("udp_rate_limit_pps must not be form-managed", "udp_rate_limit_pps" in CrawlerKeys.FORM_MANAGED_KEYS)
    }
    @Test fun parseAdvanced_allowsTreeMetadataFreshPeerRetriesKey() {
        val r = CrawlerKeys.parseAdvanced("tree_metadata_fresh_peer_retries=2")
        assertTrue(r.ok)
        assertEquals("2", r.entries["tree_metadata_fresh_peer_retries"])
        assertTrue("tree_metadata_fresh_peer_retries must be advanced-overridable", "tree_metadata_fresh_peer_retries" in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        assertFalse("tree_metadata_fresh_peer_retries must not be form-managed", "tree_metadata_fresh_peer_retries" in CrawlerKeys.FORM_MANAGED_KEYS)
    }
    @Test fun parseAdvanced_allowsTreeMetadataTimeoutMsKey() {
        val r = CrawlerKeys.parseAdvanced("tree_metadata_timeout_ms=5000")
        assertTrue(r.ok)
        assertEquals("5000", r.entries["tree_metadata_timeout_ms"])
        assertTrue("tree_metadata_timeout_ms must be known", "tree_metadata_timeout_ms" in CrawlerKeys.KNOWN_KEYS)
        assertTrue("tree_metadata_timeout_ms must be advanced-overridable", "tree_metadata_timeout_ms" in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        assertFalse("tree_metadata_timeout_ms must not be form-managed", "tree_metadata_timeout_ms" in CrawlerKeys.FORM_MANAGED_KEYS)
    }

    @Test fun parseAdvanced_laterDuplicatesWin() {
        val r = CrawlerKeys.parseAdvanced("batch_size=100\nbatch_size=200\n")
        assertTrue(r.ok)
        assertEquals("200", r.entries["batch_size"])
    }

    @Test fun knownKeys_includeStageSixClassifierAndAdultKeys() {
        // Every key the C crawler's config_load_file accepts must be known here,
        // else parseAdvanced would report it as an unknown key.
        for (key in listOf(
            "classifier_enabled", "classifier_model_dir", "adult_mode",
            "adult_hide_threshold", "hard_block_file"
        )) {
            assertTrue("$key must be known", key in CrawlerKeys.KNOWN_KEYS)
        }
    }

    @Test fun classifierAndAdultKeys_areNotAdvancedOverridable() {
        // classifier_model_dir / hard_block_file are app-owned paths, the rest are
        // rendered by the settings form — none may be set from the textarea.
        for (key in listOf(
            "classifier_enabled", "classifier_model_dir", "adult_mode",
            "adult_hide_threshold", "hard_block_file"
        )) {
            assertFalse("$key must not be advanced-overridable", key in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        }
        assertEquals("tag", CrawlerKeys.ADULT_MODES.first())
        assertEquals(listOf("tag", "drop"), CrawlerKeys.ADULT_MODES)
    }

    @Test fun adultDropThresholdKey_isGoneWithTheDropAllSemantics() {
        // The C crawler no longer parses adult_drop_threshold (drop mode discards
        // every adult-tagged row at adult_hide_threshold). A key it would silently
        // skip must not be known, form-managed or overridable here either.
        val key = "adult_drop_threshold"
        assertFalse("$key must no longer be known", key in CrawlerKeys.KNOWN_KEYS)
        assertFalse("$key must not be form-managed", key in CrawlerKeys.FORM_MANAGED_KEYS)
        assertFalse("$key must not be path-managed", key in CrawlerKeys.PATH_KEYS)
        assertFalse("$key must not be advanced-overridable", key in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        val r = CrawlerKeys.parseAdvanced("$key=0.9")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("unknown key") })
    }

    @Test fun allowedAdvancedKeys_excludePathBackupAndFormKeys() {
        assertTrue(CrawlerKeys.ALLOWED_ADVANCED_KEYS.isNotEmpty())
        val excluded = CrawlerKeys.PATH_KEYS + CrawlerKeys.BACKUP_KEYS + CrawlerKeys.FORM_MANAGED_KEYS
        assertTrue(excluded.none { it in CrawlerKeys.ALLOWED_ADVANCED_KEYS })
        assertTrue("batch_size" in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        assertTrue("use_thread_trees" in CrawlerKeys.ALLOWED_ADVANCED_KEYS)
        assertEquals(
            CrawlerKeys.KNOWN_KEYS - CrawlerKeys.PATH_KEYS - CrawlerKeys.BACKUP_KEYS - CrawlerKeys.FORM_MANAGED_KEYS,
            CrawlerKeys.ALLOWED_ADVANCED_KEYS
        )
    }

    @Test fun estimateThreads_defaultConfig() {
        val s = CrawlerSettings() // 32 trees, 3+3+18+12 per-tree workers
        assertEquals(32 * (3 + 3 + 18 + 12) + CrawlerKeys.THREAD_OVERHEAD, CrawlerKeys.estimateThreads(s))
        assertEquals(1216, CrawlerKeys.estimateThreads(s))
        assertTrue(CrawlerKeys.estimateThreads(s) < CrawlerKeys.THREAD_WARN_THRESHOLD)
    }

    @Test fun estimateThreads_maxedConfigExceedsThreshold() {
        val s = CrawlerSettings(
            numTrees = 64,
            treeFindNodeWorkers = 100,
            treeBep51Workers = 100,
            treeGetPeersWorkers = 1000,
            treeMetadataWorkers = 1000,
        )
        assertTrue(CrawlerKeys.estimateThreads(s) > CrawlerKeys.THREAD_WARN_THRESHOLD)
    }
}
