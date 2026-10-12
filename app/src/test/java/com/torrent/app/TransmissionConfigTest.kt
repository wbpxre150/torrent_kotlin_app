package com.torrent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransmissionConfigTest {

    @Test fun buildSettingsJson_hasRequiredRpcAndDownloadFields() {
        val json = TransmissionConfig.buildSettingsJson(
            "torrentapp", "secret123", "/data/user/0/com.torrent.app/files/transmission/downloads"
        )
        assertTrue(json.contains("\"rpc-enabled\": true"))
        assertTrue(json.contains("\"rpc-bind-address\": \"0.0.0.0\""))
        assertTrue(json.contains("\"rpc-port\": 9091"))
        assertTrue(json.contains("\"rpc-authentication-required\": true"))
        assertTrue(json.contains("\"rpc-username\": \"torrentapp\""))
        assertTrue(json.contains("\"rpc-password\": \"secret123\""))
        assertTrue(json.contains("\"rpc-whitelist-enabled\": false"))
        assertTrue(json.contains("\"download-dir\": \"/data/user/0/com.torrent.app/files/transmission/downloads\""))
        assertTrue(json.contains("\"incomplete-dir-enabled\": false"))
        assertTrue(json.contains("\"rename-partial-files\": false"))
        assertFalse(json.contains("\"incomplete-dir\""))
        assertTrue(json.contains("\"watch-dir-enabled\": false"))
        assertTrue(json.contains("\"preallocation\": 1"))
    }

    @Test fun buildSettingsJson_derivesEnabledFlagsFromNonZeroLimits() {
        val json = TransmissionConfig.buildSettingsJson(
            "u", "p", "/d", speedLimitDown = 666, speedLimitUp = 555)
        assertTrue(json.contains("\"speed-limit-down-enabled\": true"))
        assertTrue(json.contains("\"speed-limit-up-enabled\": true"))
    }

    @Test fun buildSettingsJson_disabledFlagsWhenLimitsAreZero() {
        val json = TransmissionConfig.buildSettingsJson("u", "p", "/d")
        assertTrue(json.contains("\"speed-limit-down-enabled\": false"))
        assertTrue(json.contains("\"speed-limit-up-enabled\": false"))
    }

    @Test fun buildSettingsJson_escapesQuotesAndBackslashes() {
        val json = TransmissionConfig.buildSettingsJson("user", "pa\"ss\\word", "/dir")
        // Escaped form must appear verbatim in the output.
        assertTrue(json.contains("\"rpc-password\": \"pa\\\"ss\\\\word\""))
    }

    @Test fun buildSettingsJson_isValidJsonObjectShape() {
        val json = TransmissionConfig.buildSettingsJson("u", "p", "/d")
        assertTrue(json.trimStart().startsWith("{"))
        assertTrue(json.trimEnd().endsWith("}"))
        assertTrue(json.contains("\"preallocation\": 1\n}"))
    }
    @Test fun usbDownloadDir_isSentinel() {
        assertEquals("/saf/usb", TransmissionConfig.USB_DOWNLOAD_DIR)
    }
    @Test fun buildSettingsJson_usesUsbSentinelForDownloadDir() {
        val json = TransmissionConfig.buildSettingsJson("u", "p", TransmissionConfig.USB_DOWNLOAD_DIR)
        assertTrue(json.contains("\"download-dir\": \"/saf/usb\""))
    }

    @Test fun buildSettingsJson_escapesControlCharacters() {
        val json = TransmissionConfig.buildSettingsJson("u\nx", "pa\tss", "/di\r\nr")
        // Raw text must contain literal escape sequences, not raw control characters.
        assertTrue(json.contains("\"rpc-username\": \"u\\nx\""))
        assertTrue(json.contains("\"rpc-password\": \"pa\\tss\""))
        assertTrue(json.contains("\"download-dir\": \"/di\\r\\nr\""))
    }

    @Test fun buildSettingsJson_unnamedControlCharsEscapeAsUnicode() {
        val json = TransmissionConfig.buildSettingsJson("u\u0001x", "p", "/d\u0001")
        assertTrue(json.contains("\"rpc-username\": \"u\\u0001x\""))
        assertTrue(json.contains("\"download-dir\": \"/d\\u0001\""))
    }

    @Test fun buildSettingsJson_controlCharFieldsRoundTripThroughJsonParser() {
        val username = "u\nx\t\"q\"\\y"
        val dir = "/d\ri\tf\u0008u"
        val json = TransmissionConfig.buildSettingsJson(username, "p", dir)
        val parsed = JSONObject(json)
        assertEquals(username, parsed.getString("rpc-username"))
        assertEquals(dir, parsed.getString("download-dir"))
    }
}