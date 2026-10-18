package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.ControlServer.Companion.CrawlerSettingsFormResult
import org.junit.Assert.assertFalse
class ControlServerHelpersTest {
    @Test fun parseForm_decodesPairs() {
        val m = ControlServer.parseForm("token=abc123&label=transmission")
        assertEquals("abc123", m["token"])
        assertEquals("transmission", m["label"])
    }
    @Test fun parseForm_decodesUrlEncoding() {
        val m = ControlServer.parseForm("name=a%20b%2Fc&pw=p%40ss")
        assertEquals("a b/c", m["name"])
        assertEquals("p@ss", m["pw"])
    }
    @Test fun parseForm_emptyBodyReturnsEmpty() {
        assertTrue(ControlServer.parseForm("").isEmpty())
    }
    @Test fun parseForm_ignoresPairWithoutEquals() {
        val m = ControlServer.parseForm("token=ok&badpair")
        assertEquals("ok", m["token"])
        assertNull(m["badpair"])
    }
    @Test fun parseFormMulti_keepsEveryValueOfARepeatedKey() {
        val m = ControlServer.parseFormMulti(
            "saveCategories=tv&saveCategories=anime&saveTvKinds=daily&numTrees=8")
        assertEquals(listOf("tv", "anime"), m["saveCategories"])
        assertEquals(listOf("daily"), m["saveTvKinds"])
        assertEquals(listOf("8"), m["numTrees"])
    }

    @Test fun parseFormMulti_emptyBodyReturnsEmpty() {
        assertTrue(ControlServer.parseFormMulti("").isEmpty())
    }

    @Test fun parseForm_collapsesRepeatedKeysToTheLastValue() {
        assertEquals("anime", ControlServer.parseForm("saveCategories=tv&saveCategories=anime")["saveCategories"])
    }
    @Test fun parseCookieToken_findsControlToken() {
        assertEquals("xyz", ControlServer.parseCookieToken("control_token=xyz; other=1"))
    }
    @Test fun parseCookieToken_returnsNullWhenAbsent() {
        assertNull(ControlServer.parseCookieToken("other=1"))
        assertNull(ControlServer.parseCookieToken(null))
    }
    @Test fun parseCookieToken_handlesWhitespace() {
        assertEquals("abc", ControlServer.parseCookieToken("foo=1; control_token=abc"))
    }

    @Test fun crawlerStatsJson_fallbackCarriesProcessState() {
        assertEquals("{\"running\":false,\"state\":\"STOPPED\"}", ControlServer.crawlerStatsJson(null))
        assertEquals(
            "{\"running\":false,\"state\":\"RUNNING\"}",
            ControlServer.crawlerStatsJson("  ", ProcessState.RUNNING))
        assertEquals(
            "{\"running\":false,\"state\":\"STARTING\"}",
            ControlServer.crawlerStatsJson(null, ProcessState.STARTING))
    }

    @Test fun crawlerStatsJson_passesThroughCrawlerBody() {
        val body = """{"torrents_indexed":123,"supervisor":{"active_trees":16}}"""
        assertEquals(body, ControlServer.crawlerStatsJson(body))
    }

    private fun parseSettings(
        form: Map<String, List<String>>,
        current: CrawlerSettings = CrawlerSettings(),
    ): CrawlerSettings =
        (ControlServer.crawlerSettingsFromForm(form, current)
            as CrawlerSettingsFormResult.Ok).settings

    private fun parseError(
        form: Map<String, List<String>>,
        current: CrawlerSettings = CrawlerSettings(),
    ): String =
        (ControlServer.crawlerSettingsFromForm(form, current)
            as CrawlerSettingsFormResult.Bad).message

    @Test fun crawlerSettingsFromForm_parsesAllFields() {
        val m = ControlServer.parseFormMulti(
            "enabled=&wifiOnly=&lanDirect=&logLevel=WARN&dhtPort=6882&niceLevel=-5"
                + "&numTrees=8&treeFindNodeWorkers=2&treeBep51Workers=5&treeGetPeersWorkers=12&treeMetadataWorkers=40"
                + "&treeInfohashQueueCapacity=100&treePeersQueueCapacity=200"
                + "&languageFilterNonLatinThreshold=50&bloomCapacity=1000&failureBloomCapacity=2000"
                + "&bloomErrorRate=0.005&advancedOverrides=bep51_cache_capacity=77&proxyPort=8090&crawlerHttpPort=8091"
                + "&classifierEnabled=&adultMode=drop&adultHideThreshold=0.9&adultDropThreshold=0.995"
                + "&saveCategories=tv&saveCategories=movie&saveTvKinds=episode&saveTvKinds=daily")
        val s = parseSettings(m)
        assertTrue(s.enabled)
        assertTrue(s.wifiOnly)
        assertTrue(s.lanDirect)
        assertEquals("WARN", s.logLevel)
        assertEquals(6882, s.dhtPort)
        assertEquals(-5, s.niceLevel)
        assertEquals(8, s.numTrees)
        assertEquals(2, s.treeFindNodeWorkers)
        assertEquals(5, s.treeBep51Workers)
        assertEquals(12, s.treeGetPeersWorkers)
        assertEquals(40, s.treeMetadataWorkers)
        assertEquals(100, s.treeInfohashQueueCapacity)
        assertEquals(200, s.treePeersQueueCapacity)
        assertEquals(50, s.languageFilterNonLatinThreshold)
        assertEquals(1000, s.bloomCapacity)
        assertEquals(2000, s.failureBloomCapacity)
        assertEquals(0.005, s.bloomErrorRate, 0.0)
        assertEquals("bep51_cache_capacity=77", s.advancedOverrides)
        assertEquals(8090, s.proxyPort)
        assertEquals(8091, s.crawlerHttpPort)
        assertTrue(s.classifierEnabled)
        assertEquals("drop", s.adultMode)
        assertEquals(0.9, s.adultHideThreshold, 0.0)
        assertEquals(0.995, s.adultDropThreshold, 0.0)
        // Taxonomy order, not post order: ConfigStore sorts on load anyway, and
        // the config.ini line then follows the crawler's own index order.
        assertEquals(listOf("movie", "tv"), s.saveCategories)
        assertEquals(listOf("episode", "daily"), s.saveTvKinds)
    }

    @Test fun crawlerSettingsFromForm_duplicateCategoryValuesAreDeduped() {
        val s = parseSettings(mapOf(
            "saveCategories" to listOf("tv", "TV", " movie ", "tv")))
        assertEquals(listOf("movie", "tv"), s.saveCategories)
    }

    @Test fun crawlerSettingsFromForm_rejectsUnknownCategory() {
        val msg = parseError(mapOf("saveCategories" to listOf("movie", "porn")))
        assertTrue(msg, msg.contains("unknown category"))
        assertTrue(msg, msg.contains("porn"))
    }

    @Test fun crawlerSettingsFromForm_rejectsUnknownTvKind() {
        val msg = parseError(mapOf(
            "saveCategories" to listOf("tv"), "saveTvKinds" to listOf("episodes")))
        assertTrue(msg, msg.contains("unknown TV kind"))
    }

    @Test fun crawlerSettingsFromForm_rejectsEmptyCategorySelection() {
        val current = CrawlerSettings(saveCategories = listOf("tv"), saveTvKinds = listOf("daily"))
        // Plain checkboxes post nothing when every box is unticked; the fieldset
        // is always rendered, so an absent key means "nothing selected".
        val msg = parseError(mapOf("numTrees" to listOf("4")), current)
        assertTrue(msg, msg.contains("tick at least one category"))
        assertTrue(parseError(mapOf("saveCategories" to listOf("", ""))).contains("tick at least one category"))
    }

    @Test fun crawlerSettingsFromForm_emptyTvKindsMeansAll() {
        val current = CrawlerSettings(saveCategories = listOf("tv"), saveTvKinds = listOf("daily"))
        // Clearing the row is allowed: an empty list omits save_tv_kinds, which
        // the crawler reads as "every kind".
        val s = parseSettings(mapOf("saveCategories" to listOf("tv")), current)
        assertEquals(listOf("tv"), s.saveCategories)
        assertTrue(s.saveTvKinds.isEmpty())
    }

    @Test fun crawlerSettingsFromForm_adultFieldsFallBackToCurrent() {
        val current = CrawlerSettings(
            classifierEnabled = true, adultMode = "drop",
            adultHideThreshold = 0.5, adultDropThreshold = 0.6)
        val s = parseSettings(
            mapOf(
                "saveCategories" to listOf("tv"),
                "adultMode" to listOf(""),
                "adultHideThreshold" to listOf("not-a-number"),
                "adultDropThreshold" to listOf(""),
            ),
            current)
        assertFalse("checkbox absence means off, like the other toggles", s.classifierEnabled)
        assertEquals("drop", s.adultMode)
        assertEquals(0.5, s.adultHideThreshold, 0.0)
        assertEquals(0.6, s.adultDropThreshold, 0.0)
    }

    @Test fun crawlerSettingsFromForm_adultModeIsNormalised() {
        val s = parseSettings(mapOf(
            "saveCategories" to listOf("movie"), "adultMode" to listOf(" DROP ")))
        assertEquals("drop", s.adultMode)
    }

    @Test fun crawlerSettingsFromForm_keepsCurrentWhenBlankOrMissing() {
        val current = CrawlerSettings(
            dhtPort = 6881, logLevel = "DEBUG", advancedOverrides = "x=1", bloomErrorRate = 0.002)
        val s = parseSettings(
            mapOf("numTrees" to listOf("4"), "bloomErrorRate" to listOf(""),
                "saveCategories" to listOf("anime")),
            current)
        assertEquals(4, s.numTrees)
        assertEquals(6881, s.dhtPort)
        assertEquals("DEBUG", s.logLevel)
        assertEquals(0.002, s.bloomErrorRate, 0.0)
        assertEquals("x=1", s.advancedOverrides)
        assertFalse(s.enabled)
        // Stage 4: the posted selection now wins over the stored one.
        assertEquals(listOf("anime"), s.saveCategories)
    }

    @Test fun backupStatusJson_idleStatus() {
        val json = ControlServer.backupStatusJson(com.torrent.app.backup.BackupStatus())
        assertEquals(
            "{\"state\":\"IDLE\",\"message\":\"\",\"lastAttempt\":null,\"lastSuccess\":null,"
                + "\"bytes\":null,\"uploadEnabled\":false,\"configured\":false}",
            json)
    }

    @Test fun backupStatusJson_fullStatusEscapesMessage() {
        val st = com.torrent.app.backup.BackupStatus(
            state = com.torrent.app.backup.BackupState.SUCCESS,
            message = "uploaded 123 bytes to \"bep51/bep51_cache.dat.gz\"",
            lastAttempt = 111L,
            lastSuccess = 222L,
            uploadedBytes = 123L,
            uploadEnabled = true,
            configured = true,
        )
        val json = ControlServer.backupStatusJson(st)
        assertEquals(
            "{\"state\":\"SUCCESS\","
                + "\"message\":\"uploaded 123 bytes to \\\"bep51/bep51_cache.dat.gz\\\"\","
                + "\"lastAttempt\":111,\"lastSuccess\":222,\"bytes\":123,"
                + "\"uploadEnabled\":true,\"configured\":true}",
            json)
    }

    @Test fun dbBackupStatusJson_idleStatus() {
        val json = ControlServer.dbBackupStatusJson(com.torrent.app.backup.DbBackupStatus())
        assertEquals(
            "{\"state\":\"IDLE\",\"message\":\"\",\"lastAttempt\":null,\"lastSuccess\":null,"
                + "\"bytes\":null,\"sqlBytes\":null,\"statementCount\":null,\"objectKey\":null,"
                + "\"uploadEnabled\":false,\"configured\":false}",
            json)
    }

    @Test fun dbBackupStatusJson_fullStatusEscapesMessage() {
        val st = com.torrent.app.backup.DbBackupStatus(
            state = com.torrent.app.backup.DbBackupState.SUCCESS,
            message = "uploaded 123 bytes to \"db/incremental_20231114_0-10.sql.gz\" (3 statements)",
            lastAttempt = 111L,
            lastSuccess = 222L,
            uploadedBytes = 123L,
            sqlBytes = 123L,
            statementCount = 3,
            objectKey = "db/incremental_20231114_0-10.sql.gz",
            uploadEnabled = true,
            configured = true,
        )
        val json = ControlServer.dbBackupStatusJson(st)
        assertEquals(
            "{\"state\":\"SUCCESS\","
                + "\"message\":\"uploaded 123 bytes to \\\"db/incremental_20231114_0-10.sql.gz\\\" (3 statements)\","
                + "\"lastAttempt\":111,\"lastSuccess\":222,\"bytes\":123,\"sqlBytes\":123,"
                + "\"statementCount\":3,\"objectKey\":\"db/incremental_20231114_0-10.sql.gz\","
                + "\"uploadEnabled\":true,\"configured\":true}",
            json)
    }

    @Test fun esc_escapesHtmlAndQuotes() {
        assertEquals("a&quot;b &amp; c&lt;d&gt;", ControlServer.esc("a\"b & c<d>"))
        assertEquals("plain", ControlServer.esc("plain"))
    }

    @Test fun backupSettingsFromForm_blankSecretKeepsCurrentValue() {
        val current = backupSettingsForFormTest()
        val s = ControlServer.backupSettingsFromForm(mapOf("r2SecretAccessKey" to ""), current)
        assertEquals("keep-me", s.r2SecretAccessKey)
        // Unrelated values fall back to the current settings.
        assertEquals("bucket", s.r2Bucket)
        assertEquals("https://example.com/c.gz", s.bootstrapUrl)
    }

    @Test fun backupSettingsFromForm_checkboxPresenceDrivesEnabledFlags() {
        val current = backupSettingsForFormTest()
        val off = ControlServer.backupSettingsFromForm(emptyMap(), current)
        assertFalse(off.uploadEnabled)
        assertFalse(off.bootstrapEnabled)
        val on = ControlServer.backupSettingsFromForm(
            mapOf("uploadEnabled" to "", "bootstrapEnabled" to ""), current)
        assertTrue(on.uploadEnabled)
        assertTrue(on.bootstrapEnabled)
    }

    @Test fun backupSettingsFromForm_blankObjectKeyKeepsCurrentValue() {
        val current = backupSettingsForFormTest()
        val s = ControlServer.backupSettingsFromForm(mapOf("r2ObjectKey" to ""), current)
        assertEquals("custom/key.gz", s.r2ObjectKey)
        val renamed = ControlServer.backupSettingsFromForm(
            mapOf("r2ObjectKey" to "other/key.gz"), current)
        assertEquals("other/key.gz", renamed.r2ObjectKey)
    }

    @Test fun backupSettingsFromForm_dbFields() {
        val current = backupSettingsForFormTest().copy(
            dbBackupEnabled = true, r2DbBucket = "cur-bucket", r2DbObjectPrefix = "cur-prefix")
        val off = ControlServer.backupSettingsFromForm(emptyMap(), current)
        assertFalse(off.dbBackupEnabled)
        // Missing values fall back to the current settings.
        assertEquals("cur-bucket", off.r2DbBucket)
        assertEquals("cur-prefix", off.r2DbObjectPrefix)
        val on = ControlServer.backupSettingsFromForm(
            mapOf("dbBackupEnabled" to "", "r2DbBucket" to "new-bucket", "r2DbObjectPrefix" to ""),
            current)
        assertTrue(on.dbBackupEnabled)
        assertEquals("new-bucket", on.r2DbBucket)
        // A blank prefix keeps the current value.
        assertEquals("cur-prefix", on.r2DbObjectPrefix)
    }

    private fun backupSettingsForFormTest() = BackupSettings(
        r2Endpoint = "https://acct.r2.cloudflarestorage.com",
        r2Bucket = "bucket",
        r2ObjectKey = "custom/key.gz",
        r2AccessKeyId = "keyid",
        r2SecretAccessKey = "keep-me",
        uploadEnabled = true,
        bootstrapEnabled = true,
        bootstrapUrl = "https://example.com/c.gz",
    )
}