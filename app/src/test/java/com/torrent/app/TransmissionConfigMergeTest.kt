package com.torrent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransmissionConfigMergeTest {

    private val managed = TransmissionConfig.buildSettingsJson(
        "torrentapp", "secret123", "/data/user/0/com.torrent.app/files/transmission/downloads"
    )

    @Test fun merge_preservesUnmanagedKeys() {
        val existing = """
            {
              "alt-speed-enabled": true,
              "download-queue-size": 8,
              "ratio-limit": 1.5
            }
        """.trimIndent()
        val json = JSONObject(TransmissionConfig.mergeSettingsJson(existing, managed))
        assertTrue(json.getBoolean("alt-speed-enabled"))
        assertEquals(8, json.getInt("download-queue-size"))
        assertEquals(1.5, json.getDouble("ratio-limit"), 0.0)
    }

    @Test fun merge_managedKeysOverwriteExisting() {
        val managed777 = TransmissionConfig.buildSettingsJson(
            "torrentapp", "secret123", "/dir", speedLimitUp = 777
        )
        val existing = """
            {
              "speed-limit-up": 999,
              "alt-speed-enabled": true
            }
        """.trimIndent()
        val json = JSONObject(TransmissionConfig.mergeSettingsJson(existing, managed777))
        // Managed value wins over the pre-existing (daemon) value.
        assertEquals(777, json.getInt("speed-limit-up"))
        // Unmanaged key is still preserved.
        assertTrue(json.getBoolean("alt-speed-enabled"))
    }

    @Test fun merge_corruptExistingFallsBackToManaged() {
        val merged = TransmissionConfig.mergeSettingsJson("{not json", managed)
        assertEquals(managed, merged)
    }

    @Test fun merge_nullExistingReturnsManagedUnchanged() {
        val merged = TransmissionConfig.mergeSettingsJson(null, managed)
        assertEquals(managed, merged)
    }

    @Test fun merge_blankExistingReturnsManagedUnchanged() {
        val merged = TransmissionConfig.mergeSettingsJson("   \n\t ", managed)
        assertEquals(managed, merged)
    }

    @Test fun merge_outputParsesAndContainsManagedRpcKeys() {
        val existing = """
            {
              "download-queue-size": 8
            }
        """.trimIndent()
        val json = JSONObject(TransmissionConfig.mergeSettingsJson(existing, managed))
        assertTrue(json.getBoolean("rpc-enabled"))
        assertTrue(json.getBoolean("rpc-authentication-required"))
        assertEquals("torrentapp", json.getString("rpc-username"))
        assertEquals("secret123", json.getString("rpc-password"))
        assertEquals(9091, json.getInt("rpc-port"))
        assertEquals(1, json.getInt("preallocation"))
        assertEquals("/data/user/0/com.torrent.app/files/transmission/downloads", json.getString("download-dir"))
        // Unmanaged key survives alongside the managed keys.
        assertEquals(8, json.getInt("download-queue-size"))
    }

    @Test fun merge_preservesDaemonOwnedAltSpeedLimitsWhenAppValueIsZero() {
        // App alt-speed value 0 = unmanaged: the daemon's own turtle limits must survive verbatim.
        val managedWithoutAlt = TransmissionConfig.buildSettingsJson(
            "torrentapp", "secret123", "/dir", altSpeedDown = 0, altSpeedUp = 0
        )
        val existing = """
            {
              "alt-speed-down": 50,
              "alt-speed-up": 10,
              "alt-speed-enabled": true
            }
        """.trimIndent()
        val json = JSONObject(TransmissionConfig.mergeSettingsJson(existing, managedWithoutAlt))
        assertEquals(50, json.getInt("alt-speed-down"))
        assertEquals(10, json.getInt("alt-speed-up"))
        assertTrue(json.getBoolean("alt-speed-enabled"))
    }

    @Test fun merge_nonZeroAltSpeedLimitsOverwriteDaemonValues() {
        val managedAlt = TransmissionConfig.buildSettingsJson(
            "torrentapp", "secret123", "/dir", altSpeedDown = 25, altSpeedUp = 5
        )
        val existing = """
            {
              "alt-speed-down": 50,
              "alt-speed-up": 10
            }
        """.trimIndent()
        val json = JSONObject(TransmissionConfig.mergeSettingsJson(existing, managedAlt))
        assertEquals(25, json.getInt("alt-speed-down"))
        assertEquals(5, json.getInt("alt-speed-up"))
    }
}
