package com.torrent.app

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.torrent.app.config.ConfigStore
import com.torrent.app.storage.SafStorageGateway
import com.torrent.app.transmission.TorrentAdder
import com.torrent.app.transmission.TransmissionRpcClient
import java.io.File

/**
 * Outcome of one `/api/transmission/add` request (plan 11 D2).
 *
 * Plain data + JSON, no Ktor: the proxy route turns [httpStatus]/[toJson]
 * into the HTTP response. The crawler's C UI (plan 11 D3) parses [toJson]
 * verbatim, so the JSON shapes and key order here are a contract.
 */
sealed class CrawlerAddOutcome {
    abstract val httpStatus: Int
    abstract fun toJson(): String

    /** 400 — hash is not exactly 40 lowercase hex chars. */
    object BadHash : CrawlerAddOutcome() {
        override val httpStatus = 400
        override fun toJson() = """{"ok":false,"error":"bad_hash"}"""
    }

    /** 503 — the crawler database file is missing or unreadable. */
    object DbUnavailable : CrawlerAddOutcome() {
        override val httpStatus = 503
        override fun toJson() = """{"ok":false,"error":"db_unavailable"}"""
    }

    /** 404 — no row for the info-hash in the crawler database. */
    object NotFound : CrawlerAddOutcome() {
        override val httpStatus = 404
        override fun toJson() = """{"ok":false,"error":"not_found"}"""
    }

    /** 503 — no SAF gateway (USB tree not granted). */
    object NoStorage : CrawlerAddOutcome() {
        override val httpStatus = 503
        override fun toJson() = """{"ok":false,"error":"no_storage"}"""
    }

    /** 507 — the torrent is bigger than the drive's free space. */
    data class InsufficientSpace(val sizeBytes: Long, val freeBytes: Long) : CrawlerAddOutcome() {
        override val httpStatus = 507
        override fun toJson() =
            """{"ok":false,"error":"insufficient_space","sizeBytes":$sizeBytes,"freeBytes":$freeBytes}"""
    }

    /** 502 — transmission did not accept the torrent (daemon down / RPC error). */
    object RpcFailed : CrawlerAddOutcome() {
        override val httpStatus = 502
        override fun toJson() = """{"ok":false,"error":"rpc_failed"}"""
    }

    /** 200 — added to transmission (or already present, when [duplicate]). */
    data class Added(val duplicate: Boolean, val name: String, val checked: Boolean) :
        CrawlerAddOutcome() {
        override val httpStatus = 200
        override fun toJson(): String =
            """{"ok":true,"added":true,"duplicate":$duplicate,"name":"${jsonEscape(name)}","checked":$checked}"""
    }

    /** Minimal JSON string escaper (keeps [toJson] free of Android/JSON deps). */
    internal fun jsonEscape(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
            '\u000C' -> append("\\f")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}

/**
 * Adds a crawler-DB torrent to the transmission daemon (plan 11 D2).
 *
 * Plain Kotlin (no Ktor) so it is unit-testable: the proxy route is a thin
 * adapter over [add]. Steps: validate the 40-hex hash → read the torrent's
 * name/size from the crawler DB (READ-ONLY, WAL-friendly) → free-space check
 * via the SAF gateway → build the magnet → `torrent-add` RPC into the root
 * of the shared data directory (stage 11 D1) at normal priority.
 */
class CrawlerAddHandler(
    private val store: ConfigStore,
    private val gateway: () -> SafStorageGateway?,
    private val crawlerDbFile: () -> File?,
    private val rpcFactory: (String, String) -> TorrentAdder =
        { user, pass -> TransmissionRpcClient(user, pass) },
) {
    fun add(hashHex: String): CrawlerAddOutcome {
        if (!HASH_REX.matches(hashHex)) return CrawlerAddOutcome.BadHash

        val dbFile = crawlerDbFile()
        if (dbFile == null || !dbFile.exists()) return CrawlerAddOutcome.DbUnavailable

        val row: Pair<String, Long>? = try {
            queryTorrent(dbFile, hashHex)
        } catch (e: Exception) {
            Log.w(TAG, "crawler db read failed: ${e.message}")
            null
        }
        if (row == null) return CrawlerAddOutcome.NotFound
        val (name, sizeBytes) = row

        val g = gateway() ?: return CrawlerAddOutcome.NoStorage
        var checked = false
        val free = g.availableBytes()
        if (free != Long.MAX_VALUE) {
            // Long.MAX_VALUE means "never probed": skip the check rather than
            // brick the button when the provider stops reporting free space.
            checked = true
            if (sizeBytes > free) return CrawlerAddOutcome.InsufficientSpace(sizeBytes, free)
        }

        // Byte-parity with the crawler's C url_encode (http_api.c): alnum +
        // '-._~' literal, everything else percent-encoded per UTF-8 byte —
        // so the add-button magnet matches the crawler's own magnet links.
        val magnet = "$MAGNET_PREFIX$hashHex&dn=" + magnetDn(name)
        val cfg = store.load()
        val downloadDir = if (cfg.dataSubpath.isEmpty()) TransmissionConfig.USB_DOWNLOAD_DIR
                          else "${TransmissionConfig.USB_DOWNLOAD_DIR}/${cfg.dataSubpath}"
        val r = try {
            rpcFactory(cfg.transmission.username, cfg.transmission.password)
                .torrentAdd(magnet, downloadDir)
        } catch (e: Exception) {
            Log.w(TAG, "torrent-add failed: ${e.message}")
            return CrawlerAddOutcome.RpcFailed
        }
        return if (r.ok) CrawlerAddOutcome.Added(r.duplicate, name, checked)
        else CrawlerAddOutcome.RpcFailed
    }

    /** `name` + `size_bytes` for the 20-byte info-hash blob, or null (never writes). */
    private fun queryTorrent(dbFile: File, hashHex: String): Pair<String, Long>? {
        // The validated 40-hex chars are inlined as a SQLite hex blob literal
        // (X'...'): a constant the UNIQUE info_hash index can use, and no
        // blob binding is needed (rawQuery args are strings only, and the
        // SDK stub exposes no SELECT-executing statement API).
        val sql = "SELECT name, size_bytes FROM torrents WHERE info_hash = X'$hashHex'"
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sdb ->
            sdb.rawQuery(sql, null).use { c ->
                return if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
            }
        }
    }

    companion object {
        private const val TAG = "CrawlerAddHandler"

        /** Exactly 40 lowercase hex chars (the Bencode info-hash hex). */
        private val HASH_REX = Regex("[0-9a-f]{40}")

        private const val MAGNET_PREFIX = "magnet:?xt=urn:btih:"

        /** dn= encoding with byte parity to the crawler C url_encode. */
        internal fun magnetDn(name: String): String = buildString {
            for (b in name.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val safe = (c in 0x30..0x39) || (c in 0x41..0x5A) || (c in 0x61..0x7A) ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
                if (safe) append(c.toChar()) else append("%%%02X".format(c))
            }
        }
    }
}
