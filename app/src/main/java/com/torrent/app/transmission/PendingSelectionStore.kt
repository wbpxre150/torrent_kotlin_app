package com.torrent.app.transmission

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One persisted subset-add intent: a magnet was added for this torrent and the user's
 * deselection still has to be applied once transmission reports a non-empty `files[]`.
 *
 * It is persisted so an app death mid-watch cannot silently leave a whole-torrent
 * download running. [addedAt] is the absolute wall-clock time of the add, preserved
 * across a restart, so the deadline `addedAt + timeoutMs` means the same thing after a
 * relaunch as it did before it.
 */
data class PendingSelection(
    val hashHex: String,
    val name: String,
    val unselectedPaths: List<String>,
    val addedAt: Long,
    val timeoutMs: Long,
)

/**
 * Persistence seam for [PendingSelection]s. [SharedPrefsPendingSelectionStore] is the
 * production implementation; the watcher's tests use an in-memory one.
 */
interface PendingSelectionStore {
    fun all(): List<PendingSelection>
    fun put(entry: PendingSelection)
    fun remove(hashHex: String)
}

/**
 * Stores every pending selection as ONE JSON array under [KEY], so a write is a single
 * atomic preference value rather than one preference key per torrent.
 *
 * Writes use [SharedPreferences.Editor.commit] deliberately: the intent must be on disk
 * *before* the watch starts, and `apply()` (which flushes asynchronously) would let a
 * hard kill lose it — exactly the case this store exists for. All callers are off the
 * main thread (the add route handler and the `selection-watch` thread).
 */
class SharedPrefsPendingSelectionStore(
    private val prefs: SharedPreferences,
) : PendingSelectionStore {

    override fun all(): List<PendingSelection> = decode(prefs.getString(KEY, null))

    /** Inserts or replaces the entry for the same hash (a re-add supersedes). */
    override fun put(entry: PendingSelection) {
        val entries = all().filterNot { it.hashHex == entry.hashHex } + entry
        write(entries)
    }

    override fun remove(hashHex: String) {
        val entries = all()
        if (entries.none { it.hashHex == hashHex }) return
        write(entries.filterNot { it.hashHex == hashHex })
    }

    private fun write(entries: List<PendingSelection>) {
        prefs.edit().putString(KEY, encode(entries)).commit()
    }

    companion object {
        private const val TAG = "PendingSelectionStore"

        /** The single preference key holding the whole array. */
        const val KEY = "pending_selections"

        /** Serialises [entries] to a JSON array; the inverse of [decode]. */
        fun encode(entries: List<PendingSelection>): String {
            val array = JSONArray()
            for (entry in entries) {
                array.put(
                    JSONObject()
                        .put("hash", entry.hashHex)
                        .put("name", entry.name)
                        .put("unselected", JSONArray(entry.unselectedPaths))
                        .put("addedAt", entry.addedAt)
                        .put("timeoutMs", entry.timeoutMs),
                )
            }
            return array.toString()
        }

        /**
         * Parses the stored array. Anything unreadable (missing, malformed, a truncated
         * value) yields an empty list: a corrupt store must not take the service down, and
         * "nothing to resume" is the safe reading — the torrent's own `wanted` array is the
         * second line of defence.
         */
        fun decode(raw: String?): List<PendingSelection> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                val out = ArrayList<PendingSelection>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val hash = obj.optString("hash", "")
                    if (hash.isEmpty()) continue
                    val paths = obj.optJSONArray("unselected") ?: JSONArray()
                    val unselected = ArrayList<String>(paths.length())
                    for (j in 0 until paths.length()) {
                        val path = paths.optString(j, "")
                        if (path.isNotEmpty()) unselected.add(path)
                    }
                    out.add(
                        PendingSelection(
                            hashHex = hash,
                            name = obj.optString("name", ""),
                            unselectedPaths = unselected,
                            addedAt = obj.optLong("addedAt", 0L),
                            timeoutMs = obj.optLong("timeoutMs", 0L),
                        ),
                    )
                }
                out
            } catch (e: Exception) {
                Log.w(TAG, "pending selections unreadable: ${e.message}")
                emptyList()
            }
        }
    }
}
