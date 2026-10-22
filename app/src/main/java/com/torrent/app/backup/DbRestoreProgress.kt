package com.torrent.app.backup

import java.io.File
import java.io.IOException

/**
 * Per-chunk resume marker for the Kotlin-side crawler DB restore (plan 3,
 * Stage 1). One small file next to the temp DB records how far the import got,
 * so a restore killed mid-chain (or mid-object) resumes at the next chunk
 * instead of re-importing from the start.
 *
 * [keys] is the ordered chain of dump object keys the restore intends to
 * import; [nextIndex] is the index of the first key that has NOT been imported
 * yet. `nextIndex == keys.size` means the chain is fully imported and only the
 * FTS rebuild / swap remain.
 *
 * File format — first line is the cursor, then one key per line:

 * ```
 * nextIndex=<int>
 * <object key 1>
 * <object key 2>
 * ```
 *
 * Written temp-file-then-rename (mirrors [DbBookmark.write]), so a crash can
 * never leave a half-written marker; [read] returns `null` for a missing,
 * unreadable or inconsistent file so the caller can fall back to "start the
 * chain over" (the import is idempotent per statement — see
 * [SqlDumpWriter.renderStatement]'s `INSERT OR IGNORE`).
 */
data class RestoreProgress(val keys: List<String>, val nextIndex: Int) {

    companion object {
        private const val NEXT_INDEX_KEY = "nextIndex="

        /**
         * Reads the marker from [file]. Returns `null` when the file is
         * missing, unreadable, lacks a parseable `nextIndex=` first line, or
         * holds an index outside `0..keys.size` (a file that disagrees with
         * itself). Blank lines after the header are ignored.
         */
        fun read(file: File): RestoreProgress? {
            if (!file.isFile) return null
            val text = try {
                file.readText()
            } catch (e: IOException) {
                return null
            }
            val lines = text.lineSequence().toList()
            val header = (lines.firstOrNull() ?: return null).trim()
            if (!header.startsWith(NEXT_INDEX_KEY)) return null
            val nextIndex = header.removePrefix(NEXT_INDEX_KEY).toIntOrNull() ?: return null
            val keys = lines.drop(1).filter { it.isNotBlank() }
            if (nextIndex < 0 || nextIndex > keys.size) return null
            return RestoreProgress(keys, nextIndex)
        }

        /**
         * Writes the marker atomically: to `<path>.tmp` first, then rename over
         * [file]. A crash mid-write leaves the previous marker intact; a
         * successful write leaves no `.tmp` behind.
         *
         * @throws IllegalArgumentException when [progress] is inconsistent
         *   (`nextIndex` outside `0..keys.size`) — such a marker could never be
         *   read back.
         * @throws IOException when the rename fails (the `.tmp` is then
         *   deleted).
         */
        fun write(file: File, progress: RestoreProgress) {
            require(progress.nextIndex >= 0) {
                "nextIndex must not be negative: ${progress.nextIndex}"
            }
            require(progress.nextIndex <= progress.keys.size) {
                "nextIndex ${progress.nextIndex} beyond ${progress.keys.size} keys"
            }
            val tmp = File(file.path + ".tmp")
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append(NEXT_INDEX_KEY).append(progress.nextIndex).append('\n')
            for (key in progress.keys) sb.append(key).append('\n')
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("restore progress rename failed: ${tmp.path} -> ${file.path}")
            }
        }
    }
}
