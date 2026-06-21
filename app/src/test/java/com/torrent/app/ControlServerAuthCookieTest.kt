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
}