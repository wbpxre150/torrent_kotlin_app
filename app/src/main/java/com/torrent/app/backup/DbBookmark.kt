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
        fun read(file: File): DbBookmark {
            if (!file.isFile) return FIRST_RUN
            val text = try {
                file.readText()
            } catch (e: IOException) {
                return FIRST_RUN
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
            val t = torrentId ?: return FIRST_RUN
            val p = prefixId ?: return FIRST_RUN
            if (t < 0L || p < 0L) return FIRST_RUN
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
