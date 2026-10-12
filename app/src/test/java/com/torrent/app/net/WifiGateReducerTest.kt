package com.torrent.app.net

import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Test

class WifiGateReducerTest {

    @Test fun wifiTransportOnly_isWifi() {
        assertTrue(WifiGateReducer.isWifi(setOf(NetworkCapabilities.TRANSPORT_WIFI)))
    }

    @Test fun wifiAmongOthers_isWifi() {
        assertTrue(WifiGateReducer.isWifi(setOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_VPN)))
    }

    @Test fun cellularOnly_isNotWifi() {
        assertFalse(WifiGateReducer.isWifi(setOf(NetworkCapabilities.TRANSPORT_CELLULAR)))
    }

    @Test fun empty_isNotWifi() {
        assertFalse(WifiGateReducer.isWifi(emptySet()))
    }
}
