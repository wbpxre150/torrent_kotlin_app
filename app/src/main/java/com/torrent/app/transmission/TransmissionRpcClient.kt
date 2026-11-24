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
    // Webui stage 3: the row key the slim /dashboard/ws payload is built on.
    val hashString: String = "",
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
 * One entry of a torrent's `files` array. [name] is transmission's own path for the file
 * (`"<torrentName>/<path>"` for a multi-file torrent, the bare filename for a single-file one);
 * the list position is the file index `torrent-set` addresses.
 */
data class TorrentFileEntry(val name: String, val length: Long, val bytesCompleted: Long)

/**
 * Per-torrent `torrent-get` snapshot of `name` + `files`. An empty [files] list is a LEGAL
 * pre-metadata state (the torrent is known to the daemon but has no file list yet) — it is the
 * `files.length > 0` test that means "metadata ready", not the presence of the snapshot.
 */
data class TorrentFilesSnapshot(val name: String, val files: List<TorrentFileEntry>)

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
            "name", "percentDone", "rateDownload", "rateUpload", "status", "sizeWhenDone",
            "hashString")))
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
                hashString = t.optString("hashString"),
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

    /**
     * Per-torrent `torrent-get` of `name` + `files` — the subset-add watcher's metadata-ready
     * signal. Null means the RPC failed (daemon down) or the torrent is not in the daemon's list;
     * a snapshot whose [TorrentFilesSnapshot.files] is empty is a legal pre-metadata answer.
     */
    fun torrentGetFiles(hashString: String): TorrentFilesSnapshot? =
        parseTorrentFilesSnapshot(call("torrent-get", buildTorrentGetFilesArgs(hashString)))

    /**
     * Marks [indices] (transmission file indices, i.e. positions in `files[]`) as unwanted and
     * returns the raw `result` string (`"success"`, or e.g. `"file index out of range"`), so the
     * caller can distinguish a rejected index list from an RPC failure. Null on RPC failure.
     */
    fun torrentSetFilesUnwanted(hashString: String, indices: List<Int>): String? {
        val resp = call("torrent-set", buildTorrentSetFilesUnwantedArgs(hashString, indices))
            ?: return null
        return resp.optString("result")
    }

    /**
     * Stops one torrent with the legacy `torrent-stop` method. `result == "success"`; false on
     * RPC failure or a non-success result.
     *
     * This is the only stop this app may use. On the shipped 4.1.2 build `torrent-set`
     * `{"paused":true}` (and `{"status":0}`) answer `result: "success"` and are silently
     * IGNORED — `rpcimpl.cc` wires `TR_KEY_paused` only into the torrent-add constructor, never
     * into torrent-set's key switch — so a caller that trusted that result reported a pause
     * while the whole torrent kept downloading. `torrent-stop` is measured to stop the same ids.
     * Its own result is still not proof of the effect: pair it with [torrentGetStatus].
     */
    fun torrentStop(hashString: String): Boolean {
        val resp = call("torrent-stop", buildTorrentStopArgs(hashString)) ?: return false
        return resp.optString("result") == "success"
    }

    /**
     * Scoped `torrent-get` of one torrent's `status` only — the read-back that confirms a
     * [torrentStop] actually reached TR_STATUS_STOPPED (0). Null means the RPC failed or the
     * torrent is not in the daemon's list; a present torrent answers its status integer, and 0
     * is a real answer, never "unknown".
     */
    fun torrentGetStatus(hashString: String): Int? =
        parseTorrentStatus(call("torrent-get", buildTorrentGetStatusArgs(hashString)))

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

        /** Pure argument builder for a per-torrent `torrent-get` of the file list. */
        fun buildTorrentGetFilesArgs(hash: String): JSONObject =
            JSONObject()
                .put("fields", JSONArray(listOf("name", "files")))
                .put("ids", JSONArray(listOf(hash)))

        /**
         * Pure argument builder for `torrent-set` with `files-unwanted`. An empty [indices] list
         * is still serialised as an empty array (`"files-unwanted":[]`), never omitted — the key
         * being present is what the daemon acts on.
         */
        fun buildTorrentSetFilesUnwantedArgs(hash: String, indices: List<Int>): JSONObject =
            JSONObject()
                .put("ids", JSONArray(listOf(hash)))
                .put("files-unwanted", JSONArray(indices))

        /**
         * Pure argument builder for `torrent-stop`: only `ids`. The method takes no other
         * argument, and it is the stop that actually works on the shipped 4.1.2 build.
         */
        fun buildTorrentStopArgs(hash: String): JSONObject =
            JSONObject()
                .put("ids", JSONArray(listOf(hash)))

        /** Pure argument builder for the scoped `torrent-get` that reads one torrent's `status`. */
        fun buildTorrentGetStatusArgs(hash: String): JSONObject =
            JSONObject()
                .put("fields", JSONArray(listOf("status")))
                .put("ids", JSONArray(listOf(hash)))

        /**
         * Pure parse of a `torrent-get` response carrying `name` + `files`. An empty
         * `arguments.torrents` array (torrent not in the daemon, or daemon down) yields null;
         * a torrent whose `files` array is empty is a legal pre-metadata snapshot and is returned
         * with an empty file list.
         */
        fun parseTorrentFilesSnapshot(resp: JSONObject?): TorrentFilesSnapshot? {
            val torrents = resp?.optJSONObject("arguments")?.optJSONArray("torrents") ?: return null
            if (torrents.length() == 0) return null
            val torrent = torrents.optJSONObject(0) ?: return null
            val files = mutableListOf<TorrentFileEntry>()
            val filesArr = torrent.optJSONArray("files")
            if (filesArr != null) {
                for (i in 0 until filesArr.length()) {
                    val f = filesArr.optJSONObject(i) ?: continue
                    files.add(TorrentFileEntry(
                        name = f.optString("name"),
                        length = f.optLong("length", 0),
                        bytesCompleted = f.optLong("bytesCompleted", 0),
                    ))
                }
            }
            return TorrentFilesSnapshot(name = torrent.optString("name"), files = files)
        }

        /**
         * Pure parse of a `torrent-get` response carrying `status`. An empty
         * `arguments.torrents` array (torrent not in the daemon, or daemon down) yields null, as
         * does a torrent object with no `status` key; a present `status` is returned as-is —
         * `0` is TR_STATUS_STOPPED, a real answer that must never be confused with "unknown".
         */
        fun parseTorrentStatus(resp: JSONObject?): Int? {
            val torrents = resp?.optJSONObject("arguments")?.optJSONArray("torrents") ?: return null
            if (torrents.length() == 0) return null
            val torrent = torrents.optJSONObject(0) ?: return null
            if (!torrent.has("status")) return null
            return torrent.optInt("status", -1)
        }

        /**
         * Pure argument builder for `session-set` of the app's speed form. The `speed-limit-*`
         * flags are derived from the limit values: non-zero enables enforcement, 0 means
         * unlimited (disabled), matching the form legend "KB/s, 0 = unlimited".
         *
         * Turtle (alt-speed) is always forced OFF: `alt-speed-enabled` (the live toggle) and
         * `alt-speed-time-enabled` (the clock schedule that re-arms it) are sent `false` on
         * every call, so a live speed apply also clears turtle mode. The numeric
         * `alt-speed-down`/`alt-speed-up` limits are different: transmission treats them as
         * absolute KB/s caps where 0 is a hard 0 KB/s cap, so 0 means unset/unmanaged here —
         * those keys are omitted from the request and the daemon keeps whatever limits it owns.
         * That prevents a 0 KB/s blackout. A non-zero value is an enforced cap and is sent
         * (harmless while the toggle above stays false).
         */
        fun buildSessionSetSpeedArgs(
            speedLimitDown: Int, speedLimitUp: Int,
            altSpeedDown: Int, altSpeedUp: Int,
        ): JSONObject {
            val args = JSONObject()
                .put("speed-limit-down", speedLimitDown)
                .put("speed-limit-up", speedLimitUp)
                .put("speed-limit-down-enabled", speedLimitDown > 0)
                .put("speed-limit-up-enabled", speedLimitUp > 0)
                .put("alt-speed-enabled", false)
                .put("alt-speed-time-enabled", false)
            if (altSpeedDown > 0) args.put("alt-speed-down", altSpeedDown)
            if (altSpeedUp > 0) args.put("alt-speed-up", altSpeedUp)
            return args
        }
        /**
         * Pure builder for the session-set that forces turtle/alt-speed OFF. This is the
         * "always disabled" invariant: alt-speed-enabled is the live toggle and
         * alt-speed-time-enabled is the clock schedule that re-arms it, so both are forced
         * false (the numeric caps alt-speed-down/up are NOT touched — they are harmless when
         * the toggle is off).
         */
        fun buildAltSpeedDisableArgs(): JSONObject =
            JSONObject()
                .put("alt-speed-enabled", false)
                .put("alt-speed-time-enabled", false)
        fun buildRequestBody(method: String, arguments: JSONObject?): String {
            val o = JSONObject().put("method", method)
            if (arguments != null) o.put("arguments", arguments)
            return o.toString()
        }
    }
}