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

/** Result of a `torrent-add` RPC (plan 11 D2). [name] is null: the handler uses the crawler DB name. */
data class TorrentAddResult(val ok: Boolean, val duplicate: Boolean, val name: String?)

/**
 * Narrow RPC surface the add handler needs; lets unit tests inject a fake
 * instead of a live transmission daemon.
 */
interface TorrentAdder {
    fun torrentAdd(filename: String, downloadDir: String): TorrentAddResult
}

class TransmissionRpcClient(
    private val username: String,
    private val password: String,
    private val host: String = "127.0.0.1",
    private val port: Int = 9091,
    private val timeoutMs: Int = 3000,
) : TorrentAdder {
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

    /**
     * Adds a magnet (or .torrent path) to the daemon at normal priority
     * (plan 11 D2). transmission 4.1.2 answers `result: "success"` for BOTH
     * new and duplicate adds; a duplicate is signalled by a `torrent-duplicate`
     * argument object (verified in the 4.1.2 rpcimpl.cc the fork builds from).
     * Null response (daemon down / RPC error) or any non-success result is a
     * failure.
     */
    override fun torrentAdd(filename: String, downloadDir: String): TorrentAddResult {
        val resp = call("torrent-add", buildTorrentAddArgs(filename, downloadDir))
            ?: return TorrentAddResult(false, false, null)
        if (resp.optString("result") != "success") return TorrentAddResult(false, false, null)
        val duplicate = resp.optJSONObject("arguments")?.has("torrent-duplicate") == true
        return TorrentAddResult(true, duplicate, null)
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

        /**
         * Pure argument builder for `torrent-add` (plan 11 D2): the magnet as
         * `filename`, the data-dir root as `download-dir`, and explicit normal
         * bandwidth priority (TR_PRI_NORMAL = 0, also the daemon default).
         */
        fun buildTorrentAddArgs(filename: String, downloadDir: String): JSONObject =
            JSONObject()
                .put("filename", filename)
                .put("download-dir", downloadDir)
                .put("bandwidthPriority", 0)

        /**
         * Pure argument builder for `session-set` of the app's speed form. The `-enabled`
         * flags are derived from the limit values: non-zero enables enforcement, 0 means
         * unlimited (disabled), matching the form legend "KB/s, 0 = unlimited".
         */
        fun buildSessionSetSpeedArgs(
            speedLimitDown: Int, speedLimitUp: Int,
            altSpeedDown: Int, altSpeedUp: Int,
        ): JSONObject =
            JSONObject()
                .put("speed-limit-down", speedLimitDown)
                .put("speed-limit-up", speedLimitUp)
                .put("alt-speed-down", altSpeedDown)
                .put("alt-speed-up", altSpeedUp)
                .put("speed-limit-down-enabled", speedLimitDown > 0)
                .put("speed-limit-up-enabled", speedLimitUp > 0)
        fun buildRequestBody(method: String, arguments: JSONObject?): String {
            val o = JSONObject().put("method", method)
            if (arguments != null) o.put("arguments", arguments)
            return o.toString()
        }
    }
}