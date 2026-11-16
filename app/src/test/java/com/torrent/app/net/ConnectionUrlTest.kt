package com.torrent.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stage 7 (review P1) URL-gate tests. Plain JUnit4: [connectionUrl] is pure and
 * Android-free, so none of this needs Robolectric.
 *
 * The fifth case is the shipped defect: tunnel connected, hostname never
 * detected and no wifi IP (phone on cellular) used to render
 * `http://:8090/?token=…` as both the link text and the QR.
 */
class ConnectionUrlTest {

    @Test
    fun connectedWithHostname_yieldsTheHttpsUrl() {
        assertEquals(
            "https://abc.example.com",
            connectionUrl(tunnelConnected = true, publicHostname = "abc.example.com", wifiIp = "192.168.0.14"),
        )
    }

    @Test
    fun connectedWithHostname_beatsTheLanUrlEvenWithoutWifi() {
        assertEquals(
            "https://abc.example.com",
            connectionUrl(tunnelConnected = true, publicHostname = "abc.example.com", wifiIp = ""),
        )
    }

    @Test
    fun notConnected_yieldsTheLanUrl() {
        assertEquals(
            "http://192.168.0.14:8090",
            connectionUrl(tunnelConnected = false, publicHostname = "abc.example.com", wifiIp = "192.168.0.14"),
        )
    }

    @Test
    fun connectedButHostnameUnknown_fallsBackToTheLanUrlWhenWifiExists() {
        assertEquals(
            "http://192.168.0.14:8090",
            connectionUrl(tunnelConnected = true, publicHostname = "", wifiIp = "192.168.0.14"),
        )
    }

    @Test
    fun connectedButHostnameUnknownAndNoWifi_yieldsNoUrl() {
        // The P1 scenario: cellular, tunnel up, hostname detection never landed.
        assertNull(connectionUrl(tunnelConnected = true, publicHostname = "", wifiIp = ""))
    }

    @Test
    fun notConnectedAndNoWifi_yieldsNoUrl() {
        assertNull(connectionUrl(tunnelConnected = false, publicHostname = "", wifiIp = ""))
    }
}
