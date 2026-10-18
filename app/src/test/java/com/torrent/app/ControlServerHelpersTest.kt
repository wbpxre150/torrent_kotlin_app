package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CrawlerSettings
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

    @Test fun crawlerSettingsFromForm_parsesAllFields() {
        val m = ControlServer.parseForm(
            "enabled=&wifiOnly=&lanDirect=&logLevel=WARN&dhtPort=6882&niceLevel=-5"
                + "&numTrees=8&treeFindNodeWorkers=2&treeBep51Workers=5&treeGetPeersWorkers=12&treeMetadataWorkers=40"
                + "&treeInfohashQueueCapacity=100&treePeersQueueCapacity=200"
                + "&languageFilterNonLatinThreshold=50&bloomCapacity=1000&failureBloomCapacity=2000"
                + "&bloomErrorRate=0.005&advancedOverrides=bep51_cache_capacity=77&proxyPort=8090&crawlerHttpPort=8091"
                + "&classifierEnabled=&adultMode=drop&adultHideThreshold=0.9&adultDropThreshold=0.995")
        val s = ControlServer.crawlerSettingsFromForm(m, CrawlerSettings())
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
    }

    @Test fun crawlerSettingsFromForm_adultFieldsFallBackToCurrent() {
        val current = CrawlerSettings(
            classifierEnabled = true, adultMode = "drop",
            adultHideThreshold = 0.5, adultDropThreshold = 0.6)
        val s = ControlServer.crawlerSettingsFromForm(
            mapOf(
                "adultMode" to "",
                "adultHideThreshold" to "not-a-number",
                "adultDropThreshold" to "",
            ),
            current)
        assertFalse("checkbox absence means off, like the other toggles", s.classifierEnabled)
        assertEquals("drop", s.adultMode)
        assertEquals(0.5, s.adultHideThreshold, 0.0)
        assertEquals(0.6, s.adultDropThreshold, 0.0)
    }

    @Test fun crawlerSettingsFromForm_adultModeIsNormalised() {
        val s = ControlServer.crawlerSettingsFromForm(mapOf("adultMode" to " DROP "), CrawlerSettings())
        assertEquals("drop", s.adultMode)
    }

    @Test fun crawlerSettingsFromForm_keepsCurrentWhenBlankOrMissing() {
        val current = CrawlerSettings(
            dhtPort = 6881, logLevel = "DEBUG", advancedOverrides = "x=1", bloomErrorRate = 0.002,
            saveCategories = listOf("tv", "anime"), saveTvKinds = listOf("daily"))
        val s = ControlServer.crawlerSettingsFromForm(mapOf("numTrees" to "4", "bloomErrorRate" to ""), current)
        assertEquals(4, s.numTrees)
        assertEquals(6881, s.dhtPort)
        assertEquals("DEBUG", s.logLevel)
        assertEquals(0.002, s.bloomErrorRate, 0.0)
        assertEquals("x=1", s.advancedOverrides)
        assertFalse(s.enabled)
        // The category selection is not in the form yet (Stage 4 adds the chips):
        // an apply must keep the stored selection instead of resetting it.
        assertEquals(listOf("tv", "anime"), s.saveCategories)
        assertEquals(listOf("daily"), s.saveTvKinds)
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