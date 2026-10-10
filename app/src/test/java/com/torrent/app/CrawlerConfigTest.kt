package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

class CrawlerConfigTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- applyPinnedKeys ---

    @Test fun applyPinnedKeys_forcesEnabledToOneToZero() {
        val ini = CrawlerConfig.parseIni(
            CrawlerConfig.applyPinnedKeys(
                "backup_enabled = 1\nssh_backup_enabled = 1\nrclone_backup_enabled = 1\n"
            )
        )
        assertEquals("0", ini["backup_enabled"])
        assertEquals("0", ini["ssh_backup_enabled"])
        assertEquals("0", ini["rclone_backup_enabled"])
    }

    @Test fun applyPinnedKeys_handlesNoSpacesAndLeadingWhitespace() {
        val out = CrawlerConfig.applyPinnedKeys("  backup_enabled=1\n\trclone_backup_enabled = 1")
        val ini = CrawlerConfig.parseIni(out)
        assertEquals("0", ini["backup_enabled"])
        assertEquals("0", ini["rclone_backup_enabled"])
        assertTrue(out.contains("backup_enabled = 0"))
        assertTrue(out.contains("rclone_backup_enabled = 0"))
    }

    @Test fun applyPinnedKeys_appendsMissingKeysAtEnd() {
        val out = CrawlerConfig.applyPinnedKeys("num_trees = 16\n\n# trailing comment")
        val lines = out.split("\n")
        assertEquals("num_trees = 16", lines[0])
        assertEquals("", lines[1])
        assertEquals("# trailing comment", lines[2])
        assertEquals("backup_enabled = 0", lines[3])
        assertEquals("ssh_backup_enabled = 0", lines[4])
        assertEquals("rclone_backup_enabled = 0", lines[5])
    }

    @Test fun applyPinnedKeys_preservesOtherLinesAndComments() {
        val input =
            "# DHT Crawler Configuration File\n" +
                "dht_port=6881\n" +
                "\n" +
                "# ==== Daily Backup ====\n" +
                "backup_enabled = 1\n" +
                "db_path=data/torrents.db\n"
        val out = CrawlerConfig.applyPinnedKeys(input)
        val lines = out.split("\n")
        assertEquals("# DHT Crawler Configuration File", lines[0])
        assertEquals("dht_port=6881", lines[1])
        assertEquals("", lines[2])
        assertEquals("# ==== Daily Backup ====", lines[3])
        assertEquals("backup_enabled = 0", lines[4])
        assertEquals("db_path=data/torrents.db", lines[5])
    }

    @Test fun applyPinnedKeys_doesNotTouchSamePrefixKeys() {
        val input =
            "backup_path=/data/local/torrent.db.bak\n" +
                "ssh_host=10.0.0.1\n" +
                "ssh_user=someuser\n" +
                "ssh_backup_enabled=1\n" +
                "rclone_dest_path=dht-backup/backup\n"
        val out = CrawlerConfig.applyPinnedKeys(input)
        val ini = CrawlerConfig.parseIni(out)
        assertEquals("/data/local/torrent.db.bak", ini["backup_path"])
        assertEquals("10.0.0.1", ini["ssh_host"])
        assertEquals("someuser", ini["ssh_user"])
        assertEquals("dht-backup/backup", ini["rclone_dest_path"])
        assertEquals("0", ini["ssh_backup_enabled"])
    }

    // --- writeAtomically ---

    @Test fun writeAtomically_writesContentAndLeavesNoTempFile() {
        val dir = tmp.newFolder("atomic")
        val target = File(dir, "config.ini")
        CrawlerConfig.writeAtomically(target, "a = 1\nb = 2\n")
        assertEquals("a = 1\nb = 2\n", target.readText())
        val leftovers = dir.listFiles { f -> f.name.endsWith(".tmp") }
        assertEquals("no temp files may remain", 0, leftovers?.size ?: 0)
    }

    @Test fun writeAtomically_replacesExistingContent() {
        val dir = tmp.newFolder("atomic-overwrite")
        val target = File(dir, "config.ini").apply { writeText("old = 1\n") }
        CrawlerConfig.writeAtomically(target, "new = 2\n")
        assertEquals("new = 2\n", target.readText())
    }

    // --- parseIni ---

    @Test fun parseIni_ignoresCommentsAndTrims() {
        val ini = CrawlerConfig.parseIni(
            "# comment\n" +
                "; semicolon comment\n" +
                "  key = value with spaces  \n" +
                "noequals\n" +
                "other=2\n"
        )
        assertEquals(mapOf("key" to "value with spaces", "other" to "2"), ini)
    }

    // --- real checked-in asset ---

    @Test fun realConfigIniDefault_isAndroidTunedAndHasNoDesktopPaths() {
        val asset = File("src/main/assets/dht_crawler/config.ini.default")
        assertTrue("expected checked-in $asset (unit test working dir is the app module)", asset.exists())
        val ini = CrawlerConfig.parseIni(asset.readText())
        assertEquals("0", ini["backup_enabled"])
        assertEquals("0", ini["ssh_backup_enabled"])
        assertEquals("0", ini["rclone_backup_enabled"])
        assertEquals("10", ini["nice_level"])
        assertEquals("16", ini["num_trees"])
        assertEquals("30", ini["tree_get_peers_workers"])
        assertEquals("50", ini["tree_metadata_workers"])
        assertEquals("8080", ini["http_port"])
        assertEquals("0.0.0.0", ini["http_bind"])
        assertNull(ini["backup_path"])
        assertNull(ini["ssh_host"])
        assertNull(ini["ssh_user"])
        assertNull(ini["ssh_dest_path"])
        assertNull(ini["ssh_key_path"])
        for ((key, value) in ini) {
            assertFalse("value for $key must not contain a desktop path: $value", value.contains("/home"))
        }
    }

    // --- Robolectric: full seeding ---

    @RunWith(RobolectricTestRunner::class)
    class EnsureLayoutTest {

        @get:Rule val tmp = TemporaryFolder()

        @Test fun ensureLayout_seedsConfigIniAndCopiesKeywordAssets() {
            val context = RuntimeEnvironment.getApplication()
            // Robolectric cannot see the generated assets (AGP 8.8 does not wire
            // test_config.properties into unit tests), so back the asset source with
            // fixture files mirroring the `assets/dht_crawler/` layout. The default
            // config is the real checked-in asset, so the pins are verified end to end.
            val fixture = File(tmp.root, "assets/dht_crawler").apply { mkdirs() }
            File(fixture, "porn_filter_keywords.txt").writeText("keyword-a\nkeyword-b\n")
            File(fixture, "torrent_search_keywords.txt").writeText("strip-a\n")
            val defaultAsset = File("src/main/assets/dht_crawler/config.ini.default")
            assertTrue("checked-in default asset must exist", defaultAsset.exists())
            File(fixture, "config.ini.default").writeText(defaultAsset.readText())
            val cfg = CrawlerConfig(context) { path -> File(tmp.root, "assets/$path").inputStream() }
            cfg.ensureLayout()

            assertTrue(cfg.workDir.isDirectory)
            assertTrue(cfg.dataDir.isDirectory)
            assertTrue(cfg.configFile.exists())

            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("0", ini["backup_enabled"])
            assertEquals("0", ini["ssh_backup_enabled"])
            assertEquals("0", ini["rclone_backup_enabled"])
            assertEquals("10", ini["nice_level"])
            assertEquals("16", ini["num_trees"])

            for (name in CrawlerConfig.KEYWORD_FILES) {
                val f = File(cfg.workDir, name)
                assertTrue("$name should be copied from assets", f.exists() && f.length() > 0)
            }
            assertTrue("version stamp should be written", File(cfg.workDir, CrawlerConfig.STAMP_FILE).exists())

            // Second run is a no-op: files unchanged, no failure.
            cfg.ensureLayout()
            assertTrue(cfg.configFile.exists())
        }
    }
}
