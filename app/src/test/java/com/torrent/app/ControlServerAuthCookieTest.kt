package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlServerAuthCookieTest {

    private val valid: (String) -> Boolean = { it == "GOOD" }

    @Test fun cookieToken_authenticatesAndDoesNotSetCookie() {
        val (token, setCookie) = ControlServer.resolveAuthToken(
            "control_token=GOOD", null, null, valid)
        assertEquals("GOOD", token)
        assertFalse(setCookie)
    }

    @Test fun queryToken_authenticatesAndSetsCookie() {
        val (token, setCookie) = ControlServer.resolveAuthToken(
            null, "GOOD", null, valid)
        assertEquals("GOOD", token)
        assertTrue(setCookie)
    }

    @Test fun bearerToken_authenticatesAndDoesNotSetCookie() {
        val (token, setCookie) = ControlServer.resolveAuthToken(
            null, null, "Bearer GOOD", valid)
        assertEquals("GOOD", token)
        assertFalse(setCookie)
    }

    @Test fun invalidCookie_fallsThroughToQueryToken_andSetsCookie() {
        val (token, setCookie) = ControlServer.resolveAuthToken(
            "control_token=BAD", "GOOD", null, valid)
        assertEquals("GOOD", token)
        assertTrue(setCookie)
    }

    @Test fun noToken_returnsNullAndNoCookie() {
        val (token, setCookie) = ControlServer.resolveAuthToken(
            null, null, null, valid)
        assertNull(token)
        assertFalse(setCookie)
    }

    @Test fun invalidEverywhere_returnsNullAndNoCookie() {
        val (token, setCookie) = ControlServer.resolveAuthToken(
            "control_token=BAD", "BAD", "Bearer BAD", valid)
        assertNull(token)
        assertFalse(setCookie)
    }

    @Test fun cookiePrecedence_beatsQueryToken() {
        val (token, setCookie) = ControlServer.resolveAuthToken(
            "control_token=GOOD", "OTHER", null, valid)
        assertEquals("GOOD", token)
        assertFalse(setCookie)
    }

    @Test fun basicToken_authenticatesAndDoesNotSetCookie() {
        val basic = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("user:GOOD".toByteArray())
        val (token, setCookie) = ControlServer.resolveAuthToken(null, null, basic, valid)
        assertEquals("GOOD", token)
        assertFalse(setCookie)
    }

    @Test fun basicEmptyUsername_stillAuthenticates() {
        val basic = "Basic " + java.util.Base64.getEncoder()
            .encodeToString(":GOOD".toByteArray())
        val (token, _) = ControlServer.resolveAuthToken(null, null, basic, valid)
        assertEquals("GOOD", token)
    }

    @Test fun basicInvalidBase64_returnsNull() {
        val (token, setCookie) = ControlServer.resolveAuthToken(
            null, null, "Basic !!!not-base64!!!", valid)
        assertNull(token)
        assertFalse(setCookie)
    }

    @Test fun basicWithoutColon_returnsNull() {
        val basic = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("justuser".toByteArray())
        val (token, _) = ControlServer.resolveAuthToken(null, null, basic, valid)
        assertNull(token)
    }

    @Test fun bearerPrecedence_beatsBasic() {
        val header = "Bearer GOOD"
        val (token, setCookie) = ControlServer.resolveAuthToken(null, null, header, valid)
        assertEquals("GOOD", token)
        assertFalse(setCookie)
    }
}