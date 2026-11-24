package com.torrent.app.transmission

/**
 * Pure mapping between the crawler's file paths and transmission's file indices.
 *
 * The identity is the path string: the crawler's files API carries root-relative
 * paths (`Help File/Help_Guide.txt`) and no per-file index, while transmission's
 * `files[]` position *is* the index `torrent-set` addresses. The mapping rule is
 * the one verified on device: a multi-file torrent's `files[].name` is
 * `"<torrentName>/<crawlerPath>"`, and a single-file torrent's is the bare
 * filename. [planUnwanted] therefore tries the prefixed form first and falls back
 * to the bare path.
 *
 * Kept free of Android dependencies (and of any RPC) so it is unit-testable in
 * plain JUnit — the `CrawlerGate` precedent.
 */
object SelectionPlanner {

    /** Outcome of mapping a deselection set onto transmission file indices. */
    sealed class SelectionMatch {
        /** Every deselected path resolved; [unwantedIndices] is ascending and de-duplicated. */
        data class Matched(val unwantedIndices: List<Int>) : SelectionMatch()

        /** These deselected paths are absent from `files[]`; no indices are trustworthy. */
        data class Mismatch(val missingPaths: List<String>) : SelectionMatch()
    }

    /**
     * Maps the crawler paths the user deselected onto transmission file indices.
     *
     * Each path `p` resolves as `"$torrentName/$p"` first (multi-file torrent) and
     * as the bare `p` second (single-file torrent). A name that appears more than
     * once maps to its first index. Any unresolved path makes the whole selection a
     * [SelectionMatch.Mismatch] — a partial index list is never returned, because
     * applying it would silently download files the user deselected.
     */
    fun planUnwanted(
        torrentName: String,
        txFiles: List<TorrentFileEntry>,
        unselectedPaths: Set<String>,
    ): SelectionMatch {
        val indexByName = HashMap<String, Int>(txFiles.size * 2)
        txFiles.forEachIndexed { index, file ->
            if (!indexByName.containsKey(file.name)) indexByName[file.name] = index
        }
        val indices = LinkedHashSet<Int>()
        val missing = ArrayList<String>()
        for (path in unselectedPaths) {
            val index = indexByName["$torrentName/$path"] ?: indexByName[path]
            if (index == null) missing.add(path) else indices.add(index)
        }
        return if (missing.isEmpty()) {
            SelectionMatch.Matched(indices.sorted())
        } else {
            SelectionMatch.Mismatch(missing)
        }
    }

    /**
     * Total bytes of the *selected* subset of a torrent: the sum of every file's
     * size minus the deselected ones.
     *
     * [allFiles] is the crawler DB's `(path, sizeBytes)` list, so the paths here are
     * root-relative (no torrent-name prefix). Returns null when a deselected path is
     * not present in [allFiles] — the caller turns that into `bad_selection` rather
     * than gating free space on an unknown total.
     */
    fun selectedTotalBytes(allFiles: List<Pair<String, Long>>, unselectedPaths: Set<String>): Long? {
        val sizeByPath = HashMap<String, Long>(allFiles.size * 2)
        var totalBytes = 0L
        for ((path, size) in allFiles) {
            if (!sizeByPath.containsKey(path)) sizeByPath[path] = size
            totalBytes += size
        }
        var unselectedBytes = 0L
        for (path in unselectedPaths) {
            val size = sizeByPath[path] ?: return null
            unselectedBytes += size
        }
        return totalBytes - unselectedBytes
    }

    /**
     * Name-mismatch guard: the crawler `/torrent/info` name and the transmission
     * `name` are byte-identical for the same torrent (verified on device), so any
     * difference means the paths cannot be trusted.
     */
    fun namesMatch(crawlerName: String, txName: String): Boolean = crawlerName == txName
}
