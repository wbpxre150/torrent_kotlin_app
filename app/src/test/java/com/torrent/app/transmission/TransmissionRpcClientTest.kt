package com.torrent.app.transmission

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
}