package com.torrent.app.backup

import java.io.File
import java.io.IOException

/**
 * The incremental dump cursor (plan 2, Stage 3 bookmark file). Two values
 * in one file, exactly the crawler's two-line format:
 *
 * ```
 * last_torrent_id=<int64>
 * last_prefix_id=<int64>
 * ```
 *
 * [FIRST_RUN] (`-1`, `-1`) means "dump everything". A negative value is
 * only meaningful as that first-run sentinel — any other negative (or a
 * missing/unparseable line, or a missing file) makes [read] fall back to
 * [FIRST_RUN], so a corrupt bookmark can never lose data (worst case: a
 * full re-dump, which `INSERT OR IGNORE` makes idempotent).
 */
data class DbBookmark(val lastTorrentId: Long, val lastPrefixId: Long) {

    companion object {
        /** First run: no cursor yet, dump the whole database. */
        val FIRST_RUN: DbBookmark = DbBookmark(-1L, -1L)

        private const val TORRENT_KEY = "last_torrent_id="
        private const val PREFIX_KEY = "last_prefix_id="

        /**
         * Reads the bookmark from [file]. Missing file, unreadable file,
         * missing line, non-integer value, or any value < 0 → [FIRST_RUN].
         */
        fun read(file: File): DbBookmark = readOrNull(file) ?: FIRST_RUN

        /**
         * Reads the bookmark from [file], returning `null` only when no valid
         * bookmark file exists (file missing, unreadable, missing a line, a
         * non-integer value, or a negative value other than the [FIRST_RUN]
         * sentinel). A valid file — including one that holds [FIRST_RUN]
         * (`-1`, `-1`) — returns its parsed [DbBookmark].
         *
         * Used by [DbBackupManager]: a null result is treated the same as
         * [FIRST_RUN] — the next check drains from the very start of the DB
         * (no baseline seeding), and the bookmark file only appears on disk
         * after the first chunk's upload succeeds.
         */
        fun readOrNull(file: File): DbBookmark? {
            if (!file.isFile) return null
            val text = try {
                file.readText()
            } catch (e: IOException) {
                return null
            }
            var torrentId: Long? = null
            var prefixId: Long? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith(TORRENT_KEY) ->
                        torrentId = line.removePrefix(TORRENT_KEY).toLongOrNull()

                    line.startsWith(PREFIX_KEY) ->
                        prefixId = line.removePrefix(PREFIX_KEY).toLongOrNull()
                }
            }
            val t = torrentId ?: return null
            val p = prefixId ?: return null
            if (t == -1L && p == -1L) return FIRST_RUN
            if (t < 0L || p < 0L) return null
            return DbBookmark(t, p)
        }

        /**
         * Writes the bookmark atomically: to `<path>.tmp` first, then
         * rename over [file] (mirrors the crawler's
         * `write_ssh_bookmark`). A crash mid-write leaves the previous
         * bookmark intact; a successful write leaves no `.tmp` behind.
         */
        fun write(file: File, bookmark: DbBookmark) {
            val tmp = File(file.path + ".tmp")
            file.parentFile?.mkdirs()
            tmp.writeText(
                "$TORRENT_KEY${bookmark.lastTorrentId}\n" +
                    "$PREFIX_KEY${bookmark.lastPrefixId}\n"
            )
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("bookmark rename failed: ${tmp.path} -> ${file.path}")
            }
        }
    }
}
