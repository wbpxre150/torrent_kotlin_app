package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun crawlerStatsJson_fallbackWhenBodyMissing() {
        assertEquals("{\"running\":false}", ControlServer.crawlerStatsJson(null))
        assertEquals("{\"running\":false}", ControlServer.crawlerStatsJson("  "))
    }

    @Test fun crawlerStatsJson_passesThroughCrawlerBody() {
        val body = """{"torrents_indexed":123,"supervisor":{"active_trees":16}}"""
        assertEquals(body, ControlServer.crawlerStatsJson(body))
    }

    @Test fun crawlerSettingsFromForm_parsesAllFields() {
        val m = ControlServer.parseForm(
            "enabled=&wifiOnly=&skipIntegrityCheck=&lanDirect=&logLevel=WARN&dhtPort=6882&niceLevel=-5"
                + "&numTrees=8&treeFindNodeWorkers=2&treeBep51Workers=5&treeGetPeersWorkers=12&treeMetadataWorkers=40"
                + "&treeInfohashQueueCapacity=100&treePeersQueueCapacity=200&pornFilterEnabled="
                + "&languageFilterNonLatinThreshold=50&bloomCapacity=1000&failureBloomCapacity=2000"
                + "&bloomErrorRate=0.005&advancedOverrides=bep51_cache_capacity=77&proxyPort=8090&crawlerHttpPort=8091")
        val s = ControlServer.crawlerSettingsFromForm(m, CrawlerSettings())
        assertTrue(s.enabled)
        assertTrue(s.wifiOnly)
        assertTrue(s.skipIntegrityCheck)
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
        assertTrue(s.pornFilterEnabled)
        assertEquals(50, s.languageFilterNonLatinThreshold)
        assertEquals(1000, s.bloomCapacity)
        assertEquals(2000, s.failureBloomCapacity)
        assertEquals(0.005, s.bloomErrorRate, 0.0)
        assertEquals("bep51_cache_capacity=77", s.advancedOverrides)
        assertEquals(8090, s.proxyPort)
        assertEquals(8091, s.crawlerHttpPort)
    }

    @Test fun crawlerSettingsFromForm_keepsCurrentWhenBlankOrMissing() {
        val current = CrawlerSettings(
            dhtPort = 6881, logLevel = "DEBUG", advancedOverrides = "x=1", bloomErrorRate = 0.002)
        val s = ControlServer.crawlerSettingsFromForm(mapOf("numTrees" to "4", "bloomErrorRate" to ""), current)
        assertEquals(4, s.numTrees)
        assertEquals(6881, s.dhtPort)
        assertEquals("DEBUG", s.logLevel)
        assertEquals(0.002, s.bloomErrorRate, 0.0)
        assertEquals("x=1", s.advancedOverrides)
        assertFalse(s.enabled)
        assertFalse(s.pornFilterEnabled)
    }
}