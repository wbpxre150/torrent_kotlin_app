package com.torrent.app

import com.torrent.app.config.CrawlerSettings
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
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
import java.security.MessageDigest

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

    // --- category selection (save_categories / save_tv_kinds) ---

    @Test fun taxonomyList_joinsLowercasedTrimmedNames() {
        assertEquals("tv,anime", CrawlerConfig.taxonomyList(listOf(" TV ", "Anime")))
        assertEquals("daily", CrawlerConfig.taxonomyList(listOf("daily", "")))
    }

    @Test fun taxonomyList_emptySelectionMeansSaveEverything() {
        assertNull(CrawlerConfig.taxonomyList(emptyList()))
        assertNull("blank names are not a selection", CrawlerConfig.taxonomyList(listOf("", "  ")))
    }

    @Test fun unsetKeys_removesOnlyTheNamedAssignments() {
        val ini = "# comment\nsave_categories = tv\nnum_trees = 8\nsave_tv_kinds=episode\n"
        val out = CrawlerConfig.unsetKeys(ini, listOf(CrawlerConfig.SAVE_CATEGORIES_KEY))
        assertEquals("# comment\nnum_trees = 8\nsave_tv_kinds=episode\n", out)
        assertEquals(ini, CrawlerConfig.unsetKeys(ini, emptyList()))
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
        assertEquals("32", ini["num_trees"])
        assertEquals("3", ini["tree_bep51_workers"])
        assertEquals("18", ini["tree_get_peers_workers"])
        assertEquals("12", ini["tree_metadata_workers"])
        assertEquals("500", ini["tree_infohash_queue_capacity"])
        assertEquals("500", ini["tree_peers_queue_capacity"])
        assertEquals("8081", ini["http_port"])
        assertEquals("127.0.0.1", ini["http_bind"])
        assertEquals("2500", ini["udp_rate_limit_pps"])
        assertEquals("1", ini["tree_metadata_fresh_peer_retries"])
        assertEquals("60000", ini["tree_metadata_timeout_ms"])
        // Stage 6 adult handling: classifier + calibrated thresholds + hard-block list
        assertEquals("1", ini["classifier_enabled"])
        assertEquals("models/v2", ini["classifier_model_dir"])
        assertEquals("tag", ini["adult_mode"])
        assertEquals("0.897417", ini["adult_hide_threshold"])
        // The drop threshold is gone with drop-all-adult-tagged semantics: the key
        // must not be rendered at all (the crawler ignores it as unknown).
        assertNull(ini["adult_drop_threshold"])
        assertEquals("hard_block.txt", ini["hard_block_file"])
        assertNull(ini["backup_path"])
        assertNull(ini["ssh_host"])
        assertNull(ini["ssh_user"])
        assertNull(ini["ssh_dest_path"])
        assertNull(ini["ssh_key_path"])
        // The keyword porn filter is gone from the crawler (remove-porn-filter patch) and
        // the category selection is absent by default: absent means "save everything".
        assertNull(ini["porn_filter_enabled"])
        assertNull(ini["porn_filter_keyword_file"])
        assertNull(ini["save_categories"])
        assertNull(ini["save_tv_kinds"])
        for ((key, value) in ini) {
            assertFalse("value for $key must not contain a desktop path: $value", value.contains("/home"))
        }
    }

    @Test fun checkedInCrawlerHardBlockList_isPresentForTheAssetCopy() {
        // copyDhtCrawlerAssets copies this file from the crawler source root into
        // assets/dht_crawler/hard_block.txt; a missing file silently disables the
        // hard-block list on device, so the source must be part of the checkout.
        val src = File("src/main/cpp/dht_crawler/hard_block.txt")
        assertTrue("expected the checked-in crawler hard-block list at $src", src.isFile)
        assertTrue("the hard-block list must not be empty", src.length() > 0)
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
            File(fixture, "torrent_search_keywords.txt").writeText("strip-a\n")
            File(fixture, "hard_block.txt").writeText("blocked-word\n")
            val defaultAsset = File("src/main/assets/dht_crawler/config.ini.default")
            assertTrue("checked-in default asset must exist", defaultAsset.exists())
            File(fixture, "config.ini.default").writeText(defaultAsset.readText())
            copyClassifierModelAssets(fixture)
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
            assertEquals("32", ini["num_trees"])

            assertEquals("500", ini["tree_infohash_queue_capacity"])
            assertEquals("500", ini["tree_peers_queue_capacity"])
            for (name in CrawlerConfig.KEYWORD_FILES) {
                val f = File(cfg.workDir, name)
                assertTrue("$name should be copied from assets", f.exists() && f.length() > 0)
            }
            assertEquals(
                "the hard-block list is staged verbatim next to config.ini",
                "blocked-word\n",
                File(cfg.workDir, CrawlerConfig.HARD_BLOCK_FILE).readText()
            )
            assertTrue("version stamp should be written", File(cfg.workDir, CrawlerConfig.STAMP_FILE).exists())

            // Second run is a no-op: files unchanged, no failure.
            cfg.ensureLayout()
            assertTrue(cfg.configFile.exists())
        }

        // --- Stage 5: classifier model staging into workDir/models/v2 ---

        @Test fun ensureLayout_stagesClassifierModelsByteIdenticalToAssets() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()

            val declared = JSONObject(File(MODEL_SRC_DIR, "manifest.json").readText())
                .getJSONObject("models")
            for (name in CrawlerConfig.MODEL_FILES) {
                val source = File(MODEL_SRC_DIR, name)
                val staged = File(cfg.modelsDir, name)
                assertTrue("$name must be staged into ${cfg.modelsDir}", staged.isFile)
                assertEquals("$name size must match the asset", source.length(), staged.length())
                assertArrayEquals(
                    "$name must be copied byte-for-byte", source.readBytes(), staged.readBytes()
                )

                val stagedHash = sha256Hex(staged)
                assertEquals("$name staged hash", sha256Hex(source), stagedHash)
                for (key in declared.keys()) {
                    val entry = declared.getJSONObject(key)
                    if (entry.getString("file") == name) {
                        assertEquals("manifest bytes for $name", entry.getLong("bytes"), staged.length())
                        assertEquals("manifest sha256 for $name", entry.getString("sha256"), stagedHash)
                    }
                }
            }

            // The legacy text staging path (readBytes().toString(UTF_8)) would inflate and
            // corrupt these files; assert that so the byte-for-byte copy is what is proven.
            val adult = File(MODEL_SRC_DIR, "adult.ftz").readBytes()
            assertFalse(
                "adult.ftz must not survive a UTF-8 round trip, else this test proves nothing",
                adult.contentEquals(adult.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8))
            )
            assertEquals(
                "no partial copies may remain",
                emptyList<String>(),
                cfg.modelsDir.listFiles()!!.map { it.name }.filter { it.endsWith(".tmp") }
            )
        }

        @Test fun ensureAssets_doesNotRewriteModelsWhenVersionStampMatches() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            val staged = File(cfg.modelsDir, "adult.ftz")
            val firstBytes = staged.readBytes()
            assertTrue(staged.setLastModified(0L))

            cfg.ensureAssets()

            assertEquals("staging must be a no-op when the stamp matches", 0L, staged.lastModified())
            assertArrayEquals("staged bytes must be unchanged", firstBytes, staged.readBytes())
        }

        @Test fun ensureAssets_stampCarriesTheModelDir() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()

            val stamp = File(cfg.workDir, CrawlerConfig.STAMP_FILE)
            assertEquals(
                "the stamp must pin the model dir, not just the version code",
                "${crawlerVersionCode()}|${CrawlerConfig.MODELS_SUBDIR}",
                stamp.readText().trim()
            )
        }

        @Test fun ensureAssets_v1ToV2UpgradeDeletesTheStaleModelDirAndRestages() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            // Simulate an install staged by the v1 build: stale sibling model dir plus a
            // stamp from before the model dir was part of it.
            val staleV1 = File(cfg.workDir, "models/v1").apply { mkdirs() }
            File(staleV1, "adult.ftz").writeBytes(byteArrayOf(9, 9, 9))
            val stamp = File(cfg.workDir, CrawlerConfig.STAMP_FILE)
            stamp.writeText(crawlerVersionCode().toString())
            cfg.modelsDir.deleteRecursively() // force a real re-stage

            cfg.ensureAssets()

            assertFalse("the v1 model dir must be reclaimed", staleV1.exists())
            assertFalse("no v1 models may ship", File(cfg.workDir, "models/v1").exists())
            assertTrue("the current model dir must survive", cfg.modelsDir.isDirectory)
            for (name in CrawlerConfig.MODEL_FILES) {
                val staged = File(cfg.modelsDir, name)
                assertTrue("$name must be re-staged into ${cfg.modelsDir}", staged.isFile)
                assertArrayEquals(
                    "$name must be copied byte-for-byte",
                    File(MODEL_SRC_DIR, name).readBytes(), staged.readBytes()
                )
            }
            assertEquals(
                "the stamp must be upgraded to the model-dir scheme",
                "${crawlerVersionCode()}|${CrawlerConfig.MODELS_SUBDIR}",
                stamp.readText().trim()
            )
        }

        @Test fun ensureAssets_stampChangeNeverDeletesTheCurrentModelDir() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            val current = File(cfg.modelsDir, "adult.ftz")
            assertTrue(current.isFile)
            val stale = File(cfg.workDir, "models/v1").apply { mkdirs() }
            File(stale, "adult.ftz").writeBytes(byteArrayOf(1, 2, 3))
            val unrelated = File(cfg.workDir, "models/notes.txt").apply { writeText("keep me") }
            File(cfg.workDir, CrawlerConfig.STAMP_FILE).writeText("0|models/v1")

            cfg.ensureAssets()

            assertFalse("the stale sibling dir is reclaimed", stale.exists())
            assertTrue("the current model dir is never deleted", cfg.modelsDir.isDirectory)
            assertTrue("staged model files survive the sweep", current.isFile)
            assertTrue("only dirs are swept: plain files stay", unrelated.isFile)
            assertEquals(
                "${crawlerVersionCode()}|${CrawlerConfig.MODELS_SUBDIR}",
                File(cfg.workDir, CrawlerConfig.STAMP_FILE).readText().trim()
            )
        }

        @Test fun ensureAssets_restagesModelsWhenVersionStampChanges() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            val staged = File(cfg.modelsDir, "adult.ftz")
            staged.writeBytes(byteArrayOf(1, 2, 3)) // simulate an interrupted copy
            val stamp = File(cfg.workDir, CrawlerConfig.STAMP_FILE)
            assertTrue(stamp.delete())

            cfg.ensureAssets()

            assertArrayEquals(
                "a version change must repair the staged model",
                File(MODEL_SRC_DIR, "adult.ftz").readBytes(), staged.readBytes()
            )
            assertTrue("the stamp is rewritten", stamp.exists())
        }

        private fun configWithRealDefaultAsset(): CrawlerConfig {
            val context = RuntimeEnvironment.getApplication()
            val fixture = File(tmp.root, "assets/dht_crawler").apply { mkdirs() }
            File(fixture, "torrent_search_keywords.txt").writeText("strip-a\n")
            File(fixture, "hard_block.txt").writeText("blocked-word\n")
            val defaultAsset = File("src/main/assets/dht_crawler/config.ini.default")
            assertTrue("checked-in default asset must exist", defaultAsset.exists())
            File(fixture, "config.ini.default").writeText(defaultAsset.readText())
            copyClassifierModelAssets(fixture)
            return CrawlerConfig(context) { path -> File(tmp.root, "assets/$path").inputStream() }
        }

        /** Checked-in model assets (read-only source of truth for the staging tests). */
        private val MODEL_SRC_DIR = File("src/main/cpp/dht_crawler-android/models/v2")

        /** Mirrors `CrawlerConfig.currentVersionCode()`, which is private to the class. */
        private fun crawlerVersionCode(): Long {
            val context = RuntimeEnvironment.getApplication()
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }

        /**
         * Mirrors `assets/dht_crawler/models/v2/` into the asset fixture with the real
         * checked-in model files, so byte fidelity is asserted against real binaries.
         */
        private fun copyClassifierModelAssets(assetDir: File) {
            assertTrue("checked-in model dir must exist: $MODEL_SRC_DIR", MODEL_SRC_DIR.isDirectory)
            val dst = File(assetDir, CrawlerConfig.MODELS_SUBDIR).apply { mkdirs() }
            MODEL_SRC_DIR.listFiles()!!.forEach { it.copyTo(File(dst, it.name), overwrite = true) }
        }

        private fun sha256Hex(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        @Test fun renderManaged_setsFormKeysInPlaceAndKeepsComments() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()

            cfg.applySettings(CrawlerSettings(numTrees = 8, dhtPort = 6882, advancedOverrides = "batch_size=750"))
            val text = cfg.configFile.readText()
            val ini = CrawlerConfig.parseIni(text)
            assertEquals("8", ini["num_trees"])
            assertEquals("6882", ini["dht_port"])
            assertEquals("750", ini["batch_size"])
            // HTTP access is managed: loopback + 8081 unless lanDirect
            assertEquals("8081", ini["http_port"])
            assertEquals("127.0.0.1", ini["http_bind"])
            // Comments and unrelated keys are preserved
            assertTrue(text.contains("# DHT Crawler Configuration File"))
            assertEquals("data/torrents.db", ini["db_path"])
        }

        @Test fun renderManaged_advancedOverrideLandsTreeMetadataTimeoutMs() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()

            cfg.applySettings(
                CrawlerSettings(advancedOverrides = "tree_metadata_timeout_ms=5000")
            )
            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("5000", ini["tree_metadata_timeout_ms"])
            // An unknown key is still rejected and never written through.
            cfg.applySettings(
                CrawlerSettings(advancedOverrides = "not_a_real_key=1")
            )
            val after = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertNull(after["not_a_real_key"])
            assertFalse(after.containsKey("not_a_real_key"))
        }

        @Test fun renderManaged_writesCategorySelectionAsCommaJoinedLowercase() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()

            cfg.applySettings(
                CrawlerSettings(
                    saveCategories = listOf("TV", "Anime"),
                    saveTvKinds = listOf("episode", "DAILY"),
                )
            )
            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("tv,anime", ini[CrawlerConfig.SAVE_CATEGORIES_KEY])
            assertEquals("episode,daily", ini[CrawlerConfig.SAVE_TV_KINDS_KEY])
            assertFalse(ini.containsKey("porn_filter_enabled"))
        }

        @Test fun renderManaged_omitsCategoryKeysWhenNothingIsSelected() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()

            cfg.applySettings(CrawlerSettings(numTrees = 4))
            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertFalse("an empty selection must not write the key", ini.containsKey(CrawlerConfig.SAVE_CATEGORIES_KEY))
            assertFalse(ini.containsKey(CrawlerConfig.SAVE_TV_KINDS_KEY))
        }

        @Test fun renderManaged_clearingTheSelectionRemovesTheStaleKeys() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            // An install that previously filtered, then cleared the selection: an
            // omitted key alone would leave the stale filter in place.
            cfg.configFile.writeText(
                cfg.configFile.readText() +
                    "save_categories = tv\nsave_tv_kinds = episode\n"
            )

            cfg.applySettings(CrawlerSettings())
            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertFalse(ini.containsKey(CrawlerConfig.SAVE_CATEGORIES_KEY))
            assertFalse(ini.containsKey(CrawlerConfig.SAVE_TV_KINDS_KEY))
        }

        @Test fun renderManaged_purgesTheVestigialAdultDropThresholdLine() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            // An install upgraded from the build that still managed the key: the stale
            // line sits in its config.ini and `setKeys` would otherwise preserve it.
            cfg.configFile.writeText(
                cfg.configFile.readText() +
                    "adult_drop_threshold = 0.993899\n" +
                    "tree_parallel_peers = 6\n"
            )
            assertTrue(
                "the fixture must start with the stale line",
                cfg.configFile.readText().contains("adult_drop_threshold = 0.993899")
            )

            cfg.applySettings(CrawlerSettings(adultMode = "DROP", adultHideThreshold = 0.75))
            val text = cfg.configFile.readText()
            val ini = CrawlerConfig.parseIni(text)

            assertFalse("the stale key must be purged", ini.containsKey("adult_drop_threshold"))
            assertFalse(text.contains("adult_drop_threshold"))
            // The managed adult keys and the unrelated neighbours of the purged line survive.
            assertEquals("drop", ini["adult_mode"])
            assertEquals("0.75", ini["adult_hide_threshold"])
            assertEquals("6", ini["tree_parallel_peers"])
            assertEquals("data/torrents.db", ini["db_path"])
            assertTrue(text.contains("# DHT Crawler Configuration File"))
        }

        @Test fun renderManaged_keepsACategorySelectionWhilePurgingTheVestigialKey() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            cfg.configFile.writeText(
                cfg.configFile.readText() + "adult_drop_threshold = 0.993899\n"
            )

            // A non-empty selection must keep `save_categories` (only the vestigial key
            // is unconditional), and a repeated apply must stay purged.
            cfg.applySettings(CrawlerSettings(saveCategories = listOf("tv")))
            val first = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("tv", first[CrawlerConfig.SAVE_CATEGORIES_KEY])
            assertFalse(first.containsKey("adult_drop_threshold"))

            cfg.applySettings(CrawlerSettings(saveCategories = listOf("tv")))
            val second = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("tv", second[CrawlerConfig.SAVE_CATEGORIES_KEY])
            assertFalse(second.containsKey("adult_drop_threshold"))
        }

        @Test fun renderManaged_lanDirectBindsAllInterfacesOnProxyPort() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()

            cfg.applySettings(CrawlerSettings(lanDirect = true, proxyPort = 9090))
            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("9090", ini["http_port"])
            assertEquals("0.0.0.0", ini["http_bind"])
        }

        @Test fun renderManaged_backupsStayZeroEvenWhenOverridesTry() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            cfg.configFile.writeText(
                "backup_enabled = 1\nssh_backup_enabled = 1\nrclone_backup_enabled = 1\n"
            )

            // The override text tries to re-enable rclone backups; the parser
            // rejects it and the pins force 0 either way.
            cfg.applySettings(CrawlerSettings(advancedOverrides = "rclone_backup_enabled=1"))
            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("0", ini["backup_enabled"])
            assertEquals("0", ini["ssh_backup_enabled"])
            assertEquals("0", ini["rclone_backup_enabled"])
        }

        @Test fun ensureLayout_seedsAdultHandlingDefaults() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()

            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("1", ini["classifier_enabled"])
            assertEquals(CrawlerConfig.MODELS_SUBDIR, ini["classifier_model_dir"])
            assertEquals("tag", ini["adult_mode"])
            assertEquals("0.897417", ini["adult_hide_threshold"])
            assertNull(ini["adult_drop_threshold"])
            assertEquals(CrawlerConfig.HARD_BLOCK_FILE, ini["hard_block_file"])
        }

        @Test fun renderManaged_writesAdultHandlingKeysAndKeepsForeignKeys() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            // A hand-added key the app does not manage must survive every rewrite.
            cfg.configFile.writeText(cfg.configFile.readText() + "\ntree_parallel_peers = 6\n")

            cfg.applySettings(
                CrawlerSettings(
                    classifierEnabled = false, adultMode = "DROP",
                    adultHideThreshold = 0.75,
                )
            )
            val text = cfg.configFile.readText()
            val ini = CrawlerConfig.parseIni(text)

            assertEquals("0", ini["classifier_enabled"])
            // The C crawler compares adult_mode case-sensitively: the writer normalises it.
            assertEquals("drop", ini["adult_mode"])
            assertEquals("0.75", ini["adult_hide_threshold"])
            // The vestigial drop threshold is never written back into config.ini.
            assertNull(ini["adult_drop_threshold"])
            assertEquals("6", ini["tree_parallel_peers"])
            // App-owned path keys are re-asserted on every write (existing installs
            // seeded before Stage 6 get them too).
            assertEquals(CrawlerConfig.MODELS_SUBDIR, ini["classifier_model_dir"])
            assertEquals(CrawlerConfig.HARD_BLOCK_FILE, ini["hard_block_file"])
        }

        @Test fun renderManaged_seedsFromAssetWhenFileMissing() {
            val cfg = configWithRealDefaultAsset()
            cfg.workDir.mkdirs()
            assertTrue(!cfg.configFile.exists())

            cfg.applySettings(CrawlerSettings(numTrees = 4))
            val ini = CrawlerConfig.parseIni(cfg.configFile.readText())
            assertEquals("4", ini["num_trees"])
            assertEquals("0", ini["backup_enabled"])
        }

        @Test fun readIni_returnsCurrentKeysAndEmptyWhenMissing() {
            val cfg = configWithRealDefaultAsset()
            assertTrue(cfg.readIni().isEmpty())
            cfg.ensureLayout()
            val ini = cfg.readIni()
            assertEquals("32", ini["num_trees"])
            assertEquals("127.0.0.1", ini["http_bind"])
        }

        @Test fun lkgSnapshotAndRestore_roundTrips() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            cfg.applySettings(CrawlerSettings(numTrees = 4))
            cfg.snapshotLastKnownGood()
            assertTrue(cfg.lkgConfigFile.exists())

            cfg.applySettings(CrawlerSettings(numTrees = 2))
            assertEquals("2", CrawlerConfig.parseIni(cfg.configFile.readText())["num_trees"])

            assertTrue(cfg.restoreLastKnownGood())
            assertEquals("4", CrawlerConfig.parseIni(cfg.configFile.readText())["num_trees"])
        }

        @Test fun lkgRestoreFalseWhenNoSnapshot() {
            val cfg = configWithRealDefaultAsset()
            cfg.ensureLayout()
            assertFalse(cfg.restoreLastKnownGood())
        }
    }
}
