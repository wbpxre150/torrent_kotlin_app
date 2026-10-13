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
        for (key in listOf("db_path", "bloom_path", "bep51_cache_path", "porn_filter_keyword_file")) {
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
        for (key in listOf("http_port", "http_bind", "num_trees", "bloom_capacity", "log_level")) {
            val r = CrawlerKeys.parseAdvanced("$key=1")
            assertFalse("$key must be rejected", r.ok)
            assertTrue(r.errors.any { it.contains(key) && it.contains("form", true) })
        }
    }

    @Test fun parseAdvanced_allowsLanguageFilterAlias() {
        val r = CrawlerKeys.parseAdvanced("porn_filter_non_latin_threshold=40")
        assertTrue(r.ok)
        assertEquals("40", r.entries["porn_filter_non_latin_threshold"])
    }

    @Test fun parseAdvanced_laterDuplicatesWin() {
        val r = CrawlerKeys.parseAdvanced("batch_size=100\nbatch_size=200\n")
        assertTrue(r.ok)
        assertEquals("200", r.entries["batch_size"])
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

    @Test fun estimateThreads_sixteenTreeConfig() {
        val s = CrawlerSettings() // 16 trees, 3+10+30+50 per-tree workers
        assertEquals(16 * (3 + 10 + 30 + 50) + CrawlerKeys.THREAD_OVERHEAD, CrawlerKeys.estimateThreads(s))
        assertEquals(1552, CrawlerKeys.estimateThreads(s))
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
