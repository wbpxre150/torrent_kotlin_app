package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.ControlServer.Companion.CrawlerSettingsFormResult
import org.junit.Assert.assertFalse
// Robolectric (not for Context here, but for org.json): the mockable
// android.jar stubs org.json, so `crawlerBackfillLine` would see a JSONObject
// that answers every opt* with its default. Same reason
// TransmissionConfigTest / TransmissionRpcClientTest run under this runner.
@RunWith(RobolectricTestRunner::class)
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

    @Test fun selectionBodyTooLarge_boundsTheAddSelectedBodyBeforeItIsRead() {
        // The route refuses a declared body above 4 MiB without `receiveText()`, so the
        // handler's 200 000-path cap is not the first bound. The worst legitimate body
        // (27 598 paths) is ~1.4 MB, so the cap keeps headroom for every real request and
        // a missing/unparseable header keeps the pre-guard behaviour (read, then parse).
        assertFalse(ControlServer.selectionBodyTooLarge(null))
        assertFalse(ControlServer.selectionBodyTooLarge(""))
        assertFalse(ControlServer.selectionBodyTooLarge("not-a-number"))
        assertFalse(ControlServer.selectionBodyTooLarge("1024"))
        assertFalse(ControlServer.selectionBodyTooLarge("4194304"))
        assertTrue(ControlServer.selectionBodyTooLarge("4194305"))
        assertTrue(ControlServer.selectionBodyTooLarge("99999999999"))
    }
    @Test fun selectionPaths_readsTheUnselectedField() {
        // Both wire fields are read by the same helper: mode ALL sends the paths the
        // user UNCHECKED, mode NONE the ones they CHECKED. An absent/non-array field
        // still yields an empty list, so the handler stays the single rejecting place;
        // non-string members are read as `toString()` and a JSON null is skipped.
        val body = org.json.JSONObject(
            """{"unselected":["Season 1/E01.mkv","sample.nfo"],"timeoutMs":600000}""")
        assertEquals(
            listOf("Season 1/E01.mkv", "sample.nfo"),
            ControlServer.selectionPaths(body, "unselected"))
        assertEquals(emptyList<String>(), ControlServer.selectionPaths(body, "selected"))

        assertTrue(ControlServer.selectionPaths(org.json.JSONObject("{}"), "unselected").isEmpty())
        assertTrue(
            ControlServer.selectionPaths(
                org.json.JSONObject("""{"unselected":"not-an-array"}"""), "unselected").isEmpty())

        val mixed = org.json.JSONObject("""{"unselected":[7,null,"sample.nfo"]}""")
        assertEquals(listOf("7", "sample.nfo"), ControlServer.selectionPaths(mixed, "unselected"))
    }

    @Test fun selectionPaths_readsTheSelectedField() {
        val body = org.json.JSONObject("""{"selected":["sample.nfo","Season 1/E01.mkv"]}""")
        assertEquals(
            listOf("sample.nfo", "Season 1/E01.mkv"),
            ControlServer.selectionPaths(body, "selected"))
        assertEquals(emptyList<String>(), ControlServer.selectionPaths(body, "unselected"))

        assertTrue(ControlServer.selectionPaths(org.json.JSONObject("{}"), "selected").isEmpty())
        assertTrue(
            ControlServer.selectionPaths(
                org.json.JSONObject("""{"selected":{"a":1}}"""), "selected").isEmpty())

        val mixed = org.json.JSONObject("""{"selected":[null,"sample.nfo",12]}""")
        assertEquals(listOf("sample.nfo", "12"), ControlServer.selectionPaths(mixed, "selected"))
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
                + "&classifierEnabled=&adultMode=drop&adultHideThreshold=0.9"
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
            adultHideThreshold = 0.5)
        val s = parseSettings(
            mapOf(
                "saveCategories" to listOf("tv"),
                "adultMode" to listOf(""),
                "adultHideThreshold" to listOf("not-a-number"),
            ),
            current)
        assertFalse("checkbox absence means off, like the other toggles", s.classifierEnabled)
        assertEquals("drop", s.adultMode)
        assertEquals(0.5, s.adultHideThreshold, 0.0)
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
                + "\"bytes\":null,\"uploadEnabled\":false,\"configured\":false,"
                + "\"running\":false,\"startedAt\":null}",
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
                + "\"uploadEnabled\":true,\"configured\":true,"
                + "\"running\":false,\"startedAt\":null}",
            json)
    }

    @Test fun dbBackupStatusJson_idleStatus() {
        val json = ControlServer.dbBackupStatusJson(com.torrent.app.backup.DbBackupStatus())
        assertEquals(
            "{\"state\":\"IDLE\",\"message\":\"\",\"lastAttempt\":null,\"lastSuccess\":null,"
                + "\"bytes\":null,\"sqlBytes\":null,\"statementCount\":null,\"objectKey\":null,"
                + "\"uploadEnabled\":false,\"resetPending\":false,\"configured\":false,"
                + "\"running\":false,\"startedAt\":null,\"chunks\":null,"
                + "\"totalBytes\":null,\"totalStatements\":null}",
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
                + "\"uploadEnabled\":true,\"resetPending\":false,\"configured\":true,"
                + "\"running\":false,\"startedAt\":null,\"chunks\":null,"
                + "\"totalBytes\":null,\"totalStatements\":null}",
            json)
    }

    @Test fun restoreStatusJson_reportsDownloadProgressAndDropsTheKeyList() {
        val st = com.torrent.app.backup.DbRestoreStatus(
            state = com.torrent.app.backup.DbRestoreState.RUNNING,
            running = true,
            objectCount = 3,
            objectsDone = 2,
            objectKeys = listOf("a", "b", "c"),
        )
        val json = ControlServer.restoreStatusJson(st)
        assertTrue(json, json.contains("\"objectsDone\":2"))
        assertTrue(json, json.contains("\"objectCount\":3"))
        // The per-object key list stays in memory only: 17k keys would flood
        // every 5 s poll response during a real restore.
        assertFalse(json, json.contains("\"objectKeys\""))
        assertFalse(json, json.contains("\"a\""))
    }

    @Test fun restoreStatusJson_idleStatusHasNullProgress() {
        val json = ControlServer.restoreStatusJson(com.torrent.app.backup.DbRestoreStatus())
        assertTrue(json, json.contains("\"objectsDone\":null"))
        assertTrue(json, json.contains("\"objectCount\":null"))
    }

    @Test fun esc_escapesHtmlAndQuotes() {
        assertEquals("a&quot;b &amp; c&lt;d&gt;", ControlServer.esc("a\"b & c<d>"))
        assertEquals("plain", ControlServer.esc("plain"))
    }

    /**
     * Stage 4: the crawler card's `classifier_backfill` line, formatted from the
     * daemon's real /stats object (wire names `rows_processed` / `rows_total`,
     * not the design doc's `rows_done`) with no thousands separators, matching
     * the existing `%d` style of `crawlerStatsLine`. `last_error` is appended
     * only while the backfill is failed, and a body without the object (or one
     * that does not parse) yields no line at all.
     */
    @Test fun crawlerBackfillLine_formatsTheDaemonsBackfillObject() {
        val running = """{"torrents_indexed":286705,"classifier_backfill":{
            "state":"running","target_clf_ver":4,"rows_left":133634,
            "rows_processed":51234,"rows_total":184868,"started_at":1761650000,
            "finished_at":0,"last_error":""}}"""
        assertEquals(
            "Backfill: running 51234 / 184868 → v4",
            ControlServer.crawlerBackfillLine(running),
        )

        val idle = """{"classifier_backfill":{"state":"idle","target_clf_ver":4,
            "rows_left":0,"rows_processed":0,"rows_total":0,"started_at":0,
            "finished_at":0,"last_error":""}}"""
        assertEquals("Backfill: idle 0 / 0 → v4", ControlServer.crawlerBackfillLine(idle))

        val failed = """{"classifier_backfill":{"state":"failed","target_clf_ver":4,
            "rows_processed":12,"rows_total":184868,"last_error":"database disk image is malformed"}}"""
        val failedLine = ControlServer.crawlerBackfillLine(failed)
        assertNotNull("a failed backfill must still render its counters", failedLine)
        assertTrue(failedLine!!, failedLine.startsWith("Backfill: failed 12 / 184868 → v4"))
        assertTrue(
            failedLine,
            failedLine.endsWith("database disk image is malformed"),
        )

        // No classifier_backfill object (older daemon) and an unparseable body:
        // no line, so the card keeps whatever it last showed.
        assertNull(ControlServer.crawlerBackfillLine("""{"torrents_indexed":1}"""))
        assertNull(ControlServer.crawlerBackfillLine("not json"))
    }

    /**
     * The adult-backfill sibling of [crawlerBackfillLine]: same /stats body, the
     * `adult_backfill` object's wire names (`rows_updated` / `rows_total` /
     * `target_adult_clf_ver`), `last_error` appended only while failed, and no line
     * at all when the object is absent or the body does not parse.
     */
    @Test fun crawlerAdultBackfillLine_formatsTheDaemonsAdultBackfillObject() {
        val running = """{"torrents_indexed":286705,"adult_backfill":{
            "state":"running","target_adult_clf_ver":11,"rows_total":12000,
            "rows_gated_out":150000,"rows_scored":4000,"rows_updated":3900,
            "rows_failed":0,"last_id":880,"started_at":1761650000,
            "finished_at":0,"last_error":""}}"""
        assertEquals(
            "Adult backfill: running 3900 / 12000 → v11",
            ControlServer.crawlerAdultBackfillLine(running),
        )

        // Mode `off` publishes idle with a resolved target of 0 and zero rows.
        val idle = """{"adult_backfill":{"state":"idle","target_adult_clf_ver":0,
            "rows_total":0,"rows_gated_out":0,"rows_scored":0,"rows_updated":0,
            "rows_failed":0,"last_id":0,"started_at":0,"finished_at":0,"last_error":""}}"""
        assertEquals("Adult backfill: idle 0 / 0 → v0", ControlServer.crawlerAdultBackfillLine(idle))

        val failed = """{"adult_backfill":{"state":"failed","target_adult_clf_ver":11,
            "rows_total":12000,"rows_updated":12,"last_error":"database disk image is malformed"}}"""
        val failedLine = ControlServer.crawlerAdultBackfillLine(failed)
        assertNotNull("a failed adult backfill must still render its counters", failedLine)
        assertTrue(failedLine!!, failedLine.startsWith("Adult backfill: failed 12 / 12000 → v11"))
        assertTrue(failedLine, failedLine.endsWith("database disk image is malformed"))

        assertNull(ControlServer.crawlerAdultBackfillLine("""{"torrents_indexed":1}"""))
        assertNull(ControlServer.crawlerAdultBackfillLine("not json"))
    }

    /**
     * Stage 3 of the CRAM v15 rollout: the dashboard's new CRAM line, formatted from
     * the daemon's real /stats object — `classifier.cram_sha256` for the container sha
     * (first 12 hex chars) and `classifier.cram` for the engine generation (the model
     * version 15, deliberately not the manifest container's `generation: 2`) plus its
     * prediction / load-failure counters. No line at all when the object is absent,
     * the engine is disabled, or the body does not parse, so the card keeps whatever
     * it last showed — the same contract as [crawlerAdultBackfillLine].
     */
    @Test fun cramStatsLine_formatsTheDaemonsCramEngine() {
        val stats = """{"torrents_indexed":286705,"classifier":{
            "enabled":true,"clf_ver":15,"adult_hide_threshold":0.375,
            "cram_sha256":"1cad6d9f9ddfd4afd72338288d12dda6855dceda4cb531d5417b9ee22406fff2",
            "cram_vocab_sha256":"6e24c4b27e3bd546a7dad0cb9a929dc7f354e664139604f0bb75f3ca1cf21cee",
            "total_classified":12345,"model_load_failures":0,
            "cram":{"enabled":true,"generation":15,"temperature":0.762984,
                "rows_predicted":12345,"load_failures":0,"kind_head_used":900,
                "file_cap":50}}}"""
        assertEquals(
            "CRAM v15 (sha 1cad6d9f9ddf): 12345 predicted, 0 load failures",
            ControlServer.cramStatsLine(stats),
        )

        // A degraded engine still renders: the load-failure counter is the point of the line.
        val degraded = """{"classifier":{"cram_sha256":"834159b642d6",
            "cram":{"enabled":true,"generation":15,"rows_predicted":7,"load_failures":3}}}"""
        assertEquals(
            "CRAM v15 (sha 834159b642d6): 7 predicted, 3 load failures",
            ControlServer.cramStatsLine(degraded),
        )

        // No cram object (an older daemon), a disabled engine, an unparseable body.
        assertNull(ControlServer.cramStatsLine("""{"torrents_indexed":1}"""))
        assertNull(ControlServer.cramStatsLine("""{"classifier":{"cram_sha256":"ab"}}"""))
        assertNull(ControlServer.cramStatsLine("""{"classifier":{"cram":{"enabled":false}}}"""))
        assertNull(ControlServer.cramStatsLine("not json"))
    }

    /**
     * Webui corrective stage 8: RFC 8259 forbids raw U+0000–U+001F inside a
     * JSON string, so `jsonStr` must escape every control character. One ANSI
     * ESC in a daemon log line used to make the dashboard client's
     * `JSON.parse` throw, freezing the live feed under a green `#live` chip.
     * The escapes are also asserted to be single-pass (no double escaping) and
     * to leave legal characters — printable ASCII, `/`, `>`, `&`, non-ASCII —
     * raw so the payloads stay compact and exact-substring assertions hold.
     */
    @Test fun jsonStr_escapesControlCharacters() {
        // Kotlin has no \f escape, so the form feed is written \u000C.
        val out = ControlServer.jsonStr("\u0000\u001b\t\r\n\b\u000C\"\\")
        // Exactly one pass of escapes: backslash-quote, then the short forms,
        // then \uXXXX for the two controls without a short form.
        assertEquals("\"\\u0000\\u001b\\t\\r\\n\\b\\f\\\"\\\\\"", out)
        assertTrue(out, out.contains("\\u0000"))
        assertTrue(out, out.contains("\\u001b"))
        assertTrue(out, out.contains("\\t"))
        assertTrue(out, out.contains("\\r"))
        assertTrue(out, out.contains("\\n"))
        assertTrue(out, out.contains("\\b"))
        assertTrue(out, out.contains("\\f"))
        assertTrue(out, out.contains("\\\""))
        assertTrue(out, out.contains("\\\\"))
        // No raw control byte survives anywhere inside the literal.
        val rawControl = Regex("[\\x00-\\x1f]")
        assertFalse("raw control byte in: $out", rawControl.containsMatchIn(out))
        // Compact: legal characters stay raw, and every control byte of the
        // range is escaped (a sweep, not just the sample above).
        assertEquals(
            "\"a/b>c&d \u00e9\u4e2d\"",
            ControlServer.jsonStr("a/b>c&d \u00e9\u4e2d"))
        // The five controls with a short form use it; the rest are \uXXXX.
        val short = mapOf(8 to "\\b", 9 to "\\t", 10 to "\\n", 12 to "\\f", 13 to "\\r")
        for (code in 0..0x1f) {
            val escaped = ControlServer.jsonStr(code.toChar().toString())
            assertFalse("raw control byte $code in: $escaped", rawControl.containsMatchIn(escaped))
            assertEquals(
                short[code] ?: ("\\u" + code.toString(16).padStart(4, '0')),
                escaped.substring(1, escaped.length - 1))
        }
        assertEquals("null", ControlServer.jsonStr(null))
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
        val on = ControlServer.backupSettingsFromForm(
            mapOf("uploadEnabled" to ""), current)
        assertTrue(on.uploadEnabled)
    }

    @Test fun backupSettingsFromForm_preservesBootstrapWhenFieldsAbsent() {
        // The Settings form no longer carries the bootstrap fields, so a save from the
        // smaller form must never silently disable the boot-time download.
        val current = backupSettingsForFormTest()
        val s = ControlServer.backupSettingsFromForm(emptyMap(), current)
        assertTrue(s.bootstrapEnabled)
        assertEquals("https://example.com/c.gz", s.bootstrapUrl)
        // Even a stray stale key must not flip it: the form no longer controls bootstrap.
        assertTrue(
            ControlServer.backupSettingsFromForm(mapOf("bootstrapEnabled" to ""), current)
                .bootstrapEnabled
        )
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

    /**
     * The reset deletes the whole remote DB dump chain, so only the exact word
     * typed into the prompt may confirm it — and the prompt's JS string literal
     * must survive a prefix containing a quote or a backslash.
     */
    @Test fun dbResetConfirmed_acceptsOnlyExactWord() {
        assertTrue(ControlServer.dbResetConfirmed("RESET"))
        assertFalse("a missing field must refuse", ControlServer.dbResetConfirmed(null))
        assertFalse("an empty field must refuse", ControlServer.dbResetConfirmed(""))
        assertFalse("lower case must refuse", ControlServer.dbResetConfirmed("reset"))
        assertFalse("leading space must refuse", ControlServer.dbResetConfirmed(" RESET"))
        assertFalse("trailing space must refuse", ControlServer.dbResetConfirmed("RESET "))
        assertEquals("\\'", ControlServer.jsQuoteEsc("'"))
        assertEquals("o\\'brien", ControlServer.jsQuoteEsc("o'brien"))
        assertEquals("a\\\\b", ControlServer.jsQuoteEsc("a\\b"))
    }

    @Test fun dbBackupStatusJson_resetPending() {
        val pending = ControlServer.dbBackupStatusJson(
            com.torrent.app.backup.DbBackupStatus(resetPending = true))
        assertTrue(pending, pending.contains("\"resetPending\":true"))
        val idle = ControlServer.dbBackupStatusJson(com.torrent.app.backup.DbBackupStatus())
        assertTrue(idle, idle.contains("\"resetPending\":false"))
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