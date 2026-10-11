package com.torrent.app.transmission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransmissionRpcClientTest {
    @Test fun buildRequestBody_containsMethodAndArguments() {
        val args = JSONObject().put("speed-limit-down", 100)
        val body = TransmissionRpcClient.buildRequestBody("session-set", args)
        assertTrue(body.contains("\"method\":\"session-set\""))
        assertTrue(body.contains("\"speed-limit-down\":100"))
    }
    @Test fun buildRequestBody_omitsArgumentsWhenNull() {
        val body = TransmissionRpcClient.buildRequestBody("session-get", null)
        assertTrue(body.contains("\"method\":\"session-get\""))
        assertTrue(!body.contains("arguments"))
    }

    @Test fun buildTorrentAddArgs_hasMagnetFilenameDataDirAndNormalPriority() {
        val args = TransmissionRpcClient.buildTorrentAddArgs(
            "magnet:?xt=urn:btih:abc", "/saf/usb/torrent_data")
        val o = JSONObject(TransmissionRpcClient.buildRequestBody("torrent-add", args))
        assertEquals("torrent-add", o.getString("method"))
        val a = o.getJSONObject("arguments")
        assertEquals("magnet:?xt=urn:btih:abc", a.getString("filename"))
        assertEquals("/saf/usb/torrent_data", a.getString("download-dir"))
        assertEquals(0, a.getInt("bandwidthPriority"))
    }
}