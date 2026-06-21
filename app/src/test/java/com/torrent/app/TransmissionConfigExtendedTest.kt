package com.torrent.app

import org.junit.Assert.assertTrue
import org.junit.Test

class TransmissionConfigExtendedTest {
    @Test fun buildSettingsJson_includesExtendedFields() {
        val json = TransmissionConfig.buildSettingsJson(
            "u", "hashed", "/saf/usb/Downloads",
            peerPort = 51413, speedLimitDown = 100, speedLimitUp = 50,
            altSpeedDown = 10, altSpeedUp = 5,
        )
        assertTrue(json.contains("\"peer-port\": 51413"))
        assertTrue(json.contains("\"speed-limit-down\": 100"))
        assertTrue(json.contains("\"speed-limit-up\": 50"))
        assertTrue(json.contains("\"alt-speed-down\": 10"))
        assertTrue(json.contains("\"alt-speed-up\": 5"))
        assertTrue(json.contains("\"download-dir\": \"/saf/usb/Downloads\""))
    }
    @Test fun buildSettingsJson_defaultsPreserveOldFields() {
        val json = TransmissionConfig.buildSettingsJson("u", "p", "/saf/usb")
        assertTrue(json.contains("\"rpc-port\": 9091"))
        assertTrue(json.contains("\"download-dir\": \"/saf/usb\""))
    }
}