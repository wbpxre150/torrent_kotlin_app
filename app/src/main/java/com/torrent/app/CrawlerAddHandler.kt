package com.torrent.app

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.torrent.app.config.ConfigStore
import com.torrent.app.storage.SafStorageGateway
import com.torrent.app.transmission.SelectionPlanner
import com.torrent.app.transmission.TorrentAdder
import com.torrent.app.transmission.TorrentAddResult
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
 * Hands a freshly added torrent's pending file selection to the watcher
 * (plan 12 stage 3). [CrawlerAddHandler.addSelected] only schedules it: the
 * magnet is the sole add mechanism, so `files-unwanted` can only be sent once
 * transmission has the torrent's metadata, and the watcher pauses the torrent
 * instead when that never happens.
 *
 * Implementations must not throw — the add has already succeeded. A registrar
 * failure is logged and the request still reports [SelectedAddOutcome.Added].
 */
fun interface SelectionRegistrar {
    fun register(hashHex: String, name: String, unselectedPaths: Set<String>, timeoutMs: Long)
}

/**
 * Outcome of one `/api/transmission/add-selected` request (plan 12 stage 3).
 *
 * Same style as [CrawlerAddOutcome] — plain data + hand-rolled JSON, no Ktor —
 * but a separate type, so the existing `/api/transmission/add` contract (which
 * the crawler's C UI parses verbatim) stays byte-identical.
 */
sealed class SelectedAddOutcome {
    abstract val httpStatus: Int
    abstract fun toJson(): String

    /** 400 — hash is not exactly 40 lowercase hex chars. */
    object BadHash : SelectedAddOutcome() {
        override val httpStatus = 400
        override fun toJson() = """{"ok":false,"error":"bad_hash"}"""
    }

    /** 503 — the crawler database file is missing or unreadable. */
    object DbUnavailable : SelectedAddOutcome() {
        override val httpStatus = 503
        override fun toJson() = """{"ok":false,"error":"db_unavailable"}"""
    }

    /** 404 — no row (or no file rows) for the info-hash in the crawler database. */
    object NotFound : SelectedAddOutcome() {
        override val httpStatus = 404
        override fun toJson() = """{"ok":false,"error":"not_found"}"""
    }

    /** 503 — no SAF gateway (USB tree not granted). */
    object NoStorage : SelectedAddOutcome() {
        override val httpStatus = 503
        override fun toJson() = """{"ok":false,"error":"no_storage"}"""
    }

    /** 502 — transmission did not accept the torrent (daemon down / RPC error). */
    object RpcFailed : SelectedAddOutcome() {
        override val httpStatus = 502
        override fun toJson() = """{"ok":false,"error":"rpc_failed"}"""
    }

    /**
     * 400 — the deselection list itself is unusable. [detail] is one of `empty`
     * (no/blank list), `too_many` (more than [CrawlerAddHandler.MAX_UNSELECTED_PATHS]
     * paths), `no_files_selected` (the deselection covers every file) or
     * `unknown_path:<path>` (the first path absent from the DB's file list).
     */
    data class BadSelection(val detail: String) : SelectedAddOutcome() {
        override val httpStatus = 400
        override fun toJson() =
            """{"ok":false,"error":"bad_selection","detail":"${jsonEscape(detail)}"}"""
    }

    /**
     * 507 — the *selected* subset is bigger than the drive's free space. Same
     * JSON shape as [CrawlerAddOutcome.InsufficientSpace] so the page's existing
     * `insufficient_space` message renders it unchanged, which is why `sizeBytes`
     * here is [selectedBytes] and not the whole torrent.
     */
    data class InsufficientSpace(val selectedBytes: Long, val freeBytes: Long) :
        SelectedAddOutcome() {
        override val httpStatus = 507
        override fun toJson() =
            """{"ok":false,"error":"insufficient_space","sizeBytes":$selectedBytes,"freeBytes":$freeBytes}"""
    }

    /** 200 — added; the file selection is scheduled, hence `"selection":"pending"`. */
    data class Added(
        val duplicate: Boolean,
        val name: String,
        val checked: Boolean,
        val selectedBytes: Long,
        val unselectedCount: Int,
    ) : SelectedAddOutcome() {
        override val httpStatus = 200
        override fun toJson(): String =
            """{"ok":true,"added":true,"duplicate":$duplicate,"name":"${jsonEscape(name)}","checked":$checked,"selectedBytes":$selectedBytes,"unselectedCount":$unselectedCount,"selection":"pending"}"""
    }

    /** Minimal JSON string escaper, byte-identical to [CrawlerAddOutcome.jsonEscape]. */
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
    /** Scheduler for a subset add's `files-unwanted`; null keeps the whole-torrent path only. */
    private val selectionRegistrar: SelectionRegistrar? = null,
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

        val r = addMagnet(hashHex, name) ?: return CrawlerAddOutcome.RpcFailed
        return if (r.ok) CrawlerAddOutcome.Added(r.duplicate, name, checked)
        else CrawlerAddOutcome.RpcFailed
    }

    /**
     * Adds a torrent with a subset of its files deselected (plan 12 stage 3).
     *
     * The magnet is the only add mechanism (no `.torrent` can be synthesised), so
     * the deselection cannot ride the add itself: [unselectedPaths] are the
     * crawler DB's root-relative paths, validated against the DB's own file list,
     * and the resulting `files-unwanted` is applied by the watcher once
     * transmission has the metadata (scheduled through [selectionRegistrar]).
     *
     * [selectedPaths] is the mode-NONE wire: the paths the user CHECKED, from
     * which the deselection is derived as its complement in the DB's file list
     * (a whole-torrent "select none" then costs three paths instead of 27 598).
     * A null value (the default) keeps the legacy [unselectedPaths] semantics,
     * so every pre-existing caller is unchanged. Selecting every file is a valid
     * selection: the complement is empty, so the magnet is added with no watch.
     *
     * The free-space gate uses the **selected** total, so a torrent whose whole
     * size exceeds the drive's free space is still addable when the picked subset
     * fits.
     */
    fun addSelected(
        hashHex: String,
        unselectedPaths: List<String>,
        timeoutMs: Long,
        selectedPaths: List<String>? = null,
    ): SelectedAddOutcome {
        if (!HASH_REX.matches(hashHex)) return SelectedAddOutcome.BadHash

        val dbFile = crawlerDbFile()
        if (dbFile == null || !dbFile.exists()) return SelectedAddOutcome.DbUnavailable

        // The name is needed for the magnet's dn= and for the watcher's
        // name-mismatch guard, so it is read exactly as [add] reads it.
        val name = (try {
            queryTorrent(dbFile, hashHex)?.first
        } catch (e: Exception) {
            Log.w(TAG, "crawler db read failed: ${e.message}")
            null
        }) ?: return SelectedAddOutcome.NotFound

        val allFiles: List<Pair<String, Long>>? = try {
            queryTorrentFiles(dbFile, hashHex)
        } catch (e: Exception) {
            Log.w(TAG, "crawler db file read failed: ${e.message}")
            null
        }
        if (allFiles.isNullOrEmpty()) return SelectedAddOutcome.NotFound

        val unselected: Set<String> = if (selectedPaths == null) {
            // Legacy mode-ALL wire: the body names what the user UNCHECKED, so it
            // must be a strict subset of the torrent's files.
            if (unselectedPaths.isEmpty() || unselectedPaths.all { it.isBlank() }) {
                return SelectedAddOutcome.BadSelection("empty")
            }
            if (unselectedPaths.size > MAX_UNSELECTED_PATHS) {
                return SelectedAddOutcome.BadSelection("too_many")
            }
            val set = unselectedPaths.toSet()
            if (allFiles.all { it.first in set }) {
                return SelectedAddOutcome.BadSelection("no_files_selected")
            }
            set
        } else {
            // Mode-NONE wire: the body names what the user CHECKED, so the
            // deselection is its complement in the DB's file list. Validating the
            // inclusion side is what makes a bad path fail by name instead of
            // vanishing into a 27 598-entry complement.
            if (selectedPaths.isEmpty() || selectedPaths.all { it.isBlank() }) {
                return SelectedAddOutcome.BadSelection("empty")
            }
            if (selectedPaths.size > MAX_UNSELECTED_PATHS) {
                return SelectedAddOutcome.BadSelection("too_many")
            }
            val wanted = selectedPaths.toSet()
            val allPaths = HashSet<String>(allFiles.size * 2)
            for (f in allFiles) allPaths.add(f.first)
            val unknown = selectedPaths.firstOrNull { it !in allPaths }
            if (unknown != null) return SelectedAddOutcome.BadSelection("unknown_path:$unknown")
            val complement = LinkedHashSet<String>()
            for (f in allFiles) if (f.first !in wanted) complement.add(f.first)
            complement // empty means "every file selected" == the whole torrent
        }
        // The selected total doubles as the path validation: selectedTotalBytes
        // returns null only for a path that is not in the DB's file list.
        val selectedBytes = SelectionPlanner.selectedTotalBytes(allFiles, unselected)
            ?: return SelectedAddOutcome.BadSelection(
                "unknown_path:" + firstUnknownPath(allFiles, unselected))

        val g = gateway() ?: return SelectedAddOutcome.NoStorage
        var checked = false
        val free = g.availableBytes()
        if (free != Long.MAX_VALUE) {
            // Long.MAX_VALUE means "never probed": skip the check, as [add] does.
            checked = true
            if (selectedBytes > free) return SelectedAddOutcome.InsufficientSpace(selectedBytes, free)
        }

        val r = addMagnet(hashHex, name) ?: return SelectedAddOutcome.RpcFailed
        if (!r.ok) return SelectedAddOutcome.RpcFailed

        // A registrar failure must not fail the add — the torrent is already in
        // transmission, and the watcher's own pause guard is the transmission-side
        // `wanted` check. An empty deselection (every file selected) has nothing
        // to apply, so no watch is scheduled and `selection-status` reports
        // `state:"none"` for it.
        if (unselected.isNotEmpty()) {
            try {
                selectionRegistrar?.register(
                    hashHex,
                    name,
                    unselected,
                    timeoutMs.coerceIn(MIN_SELECTION_TIMEOUT_MS, MAX_SELECTION_TIMEOUT_MS),
                )
            } catch (e: Exception) {
                Log.w(TAG, "selection register failed: ${e.message}")
            }
        }
        return SelectedAddOutcome.Added(r.duplicate, name, checked, selectedBytes, unselected.size)
    }

    /** Magnet build + `torrent-add` into the shared data dir; null when the RPC throws. */
    private fun addMagnet(hashHex: String, name: String): TorrentAddResult? {
        // Byte-parity with the crawler's C url_encode (http_api.c): alnum +
        // '-._~' literal, everything else percent-encoded per UTF-8 byte —
        // so the add-button magnet matches the crawler's own magnet links.
        val magnet = "$MAGNET_PREFIX$hashHex&dn=" + magnetDn(name)
        val cfg = store.load()
        val downloadDir = if (cfg.dataSubpath.isEmpty()) TransmissionConfig.USB_DOWNLOAD_DIR
                          else "${TransmissionConfig.USB_DOWNLOAD_DIR}/${cfg.dataSubpath}"
        return try {
            rpcFactory(cfg.transmission.username, cfg.transmission.password)
                .torrentAdd(magnet, downloadDir)
        } catch (e: Exception) {
            Log.w(TAG, "torrent-add failed: ${e.message}")
            null
        }
    }

    /** First deselected path absent from [allFiles] (only used to name a validation failure). */
    private fun firstUnknownPath(
        allFiles: List<Pair<String, Long>>,
        unselected: Set<String>,
    ): String {
        val known = HashSet<String>(allFiles.size * 2)
        allFiles.forEach { known.add(it.first) }
        return unselected.firstOrNull { it !in known } ?: ""
    }

    /**
     * `(root-relative path, size_bytes)` for every file of the info-hash, in
     * `file_index` order; null when the DB read fails.
     *
     * Mirrors the crawler's own page query (database.c:2281-2293) so the paths the
     * dashboard shows are exactly the paths validated here.
     */
    private fun queryTorrentFiles(dbFile: File, hashHex: String): List<Pair<String, Long>>? {
        // The validated 40-hex chars are inlined as a SQLite hex blob literal
        // (X'...'), exactly as in [queryTorrent].
        val sql = "SELECT COALESCE(pp.prefix || '/' || tf.filename, tf.filename) AS path, " +
            "tf.size_bytes FROM torrent_files tf " +
            "JOIN torrents t ON t.id = tf.torrent_id " +
            "LEFT JOIN path_prefixes pp ON tf.prefix_id = pp.id " +
            "WHERE t.info_hash = X'$hashHex' ORDER BY tf.file_index"
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sdb ->
            sdb.rawQuery(sql, null).use { c ->
                val files = ArrayList<Pair<String, Long>>(c.count)
                while (c.moveToNext()) files.add(c.getString(0) to c.getLong(1))
                return files
            }
        }
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

        /**
         * Upper bound on either path array of an `add-selected` body. Both are
         * whole-torrent-scoped now: a mode-ALL body carries the UNCHECKED paths
         * and a mode-NONE body the CHECKED ones, so neither is a loaded-row
         * subset any more. The route's 4 MiB `MAX_SELECTION_BODY_BYTES` binds
         * first for any realistic torrent — 27 598 paths is ~1.4 MB (3x
         * headroom) and ~75 000 paths would already be refused by the body cap —
         * so `too_many` is reachable only by a non-page client. The cap stays as
         * the loud backstop.
         */
        private const val MAX_UNSELECTED_PATHS = 200_000

        /** Selection-watch timeout bounds; the route applies the 600 s default. */
        private const val MIN_SELECTION_TIMEOUT_MS = 1_000L
        private const val MAX_SELECTION_TIMEOUT_MS = 900_000L

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
