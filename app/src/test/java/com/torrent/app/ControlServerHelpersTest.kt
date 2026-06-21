package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}