package com.torrent.app.transmission

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class TorrentSummary(
    val name: String,
    val percentDone: Double,
    val rateDownload: Long,   // B/s
    val rateUpload: Long,     // B/s
    val status: Int,
    val sizeWhenDone: Long,
)

data class TransmissionSummary(
    val downloadDir: String,
    val speedLimitDown: Int,
    val speedLimitUp: Int,
    val torrents: List<TorrentSummary>,
)

class TransmissionRpcClient(
    private val username: String,
    private val password: String,
    private val host: String = "127.0.0.1",
    private val port: Int = 9091,
    private val timeoutMs: Int = 3000,
) {
    @Volatile private var sessionId: String? = null

    fun sessionGet(): JSONObject? = call("session-get", null)?.optJSONObject("arguments")

    fun sessionSet(fields: JSONObject): Boolean =
        call("session-set", fields)?.optString("result") == "success"

    fun torrentGetSummary(): List<TorrentSummary> {
        val args = JSONObject().put("fields", JSONArray(listOf(
            "name", "percentDone", "rateDownload", "rateUpload", "status", "sizeWhenDone")))
        val resp = call("torrent-get", args) ?: return emptyList()
        val arr = resp.optJSONObject("arguments")?.optJSONArray("torrents") ?: return emptyList()
        val out = mutableListOf<TorrentSummary>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            out.add(TorrentSummary(
                name = t.optString("name"),
                percentDone = t.optDouble("percentDone", 0.0),
                rateDownload = t.optLong("rateDownload", 0),
                rateUpload = t.optLong("rateUpload", 0),
                status = t.optInt("status", 0),
                sizeWhenDone = t.optLong("sizeWhenDone", 0),
            ))
        }
        return out
    }

    private fun call(method: String, arguments: JSONObject?): JSONObject? = try {
        callOnce(method, arguments, retry = true)
    } catch (e: Exception) { Log.w(TAG, "rpc $method failed: ${e.message}"); null }

    private fun callOnce(method: String, arguments: JSONObject?, retry: Boolean): JSONObject? {
        val url = URL("http://$host:$port/transmission/rpc")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            val auth = Base64.encodeToString(
                "$username:$password".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            setRequestProperty("Authorization", "Basic $auth")
            sessionId?.let { setRequestProperty("X-Transmission-Session-Id", it) }
        }
        conn.outputStream.use { it.write(buildRequestBody(method, arguments).toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        if (code == 409) {
            sessionId = conn.getHeaderField("X-Transmission-Session-Id")
            conn.disconnect()
            return if (retry) callOnce(method, arguments, retry = false) else null
        }
        if (code != 200) { conn.disconnect(); return null }
        val text = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        conn.disconnect()
        return JSONObject(text)
    }

    companion object {
        private const val TAG = "TransmissionRpcClient"
        fun buildRequestBody(method: String, arguments: JSONObject?): String {
            val o = JSONObject().put("method", method)
            if (arguments != null) o.put("arguments", arguments)
            return o.toString()
        }
    }
}