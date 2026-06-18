package com.torrent.app.storage

/** Pure helpers for the gateway's relative-path space. No Android types. */
object SafPaths {
    /**
     * Normalize a daemon-supplied relative path under the USB tree.
     * - empty string "" -> "" (the tree root)
     * - strips leading/trailing '/'
     * - collapses repeated '/'
     * - rejects '..' segments (no escaping the tree root)
     * - rejects absolute paths and backslashes
     * @throws IllegalArgumentException on '..' segments, absolute, or backslash
     */
    fun normalize(relPath: String): String {
        if (relPath.isEmpty()) return ""
        require(!relPath.contains('\\')) { "backslash not allowed: $relPath" }
        val parts = relPath.split('/')
            .filter { it.isNotEmpty() }
            .onEach { seg ->
                require(seg != "." && seg != "..") { "segment '.'/'..' not allowed: $relPath" }
            }
        return parts.joinToString("/")
    }

    /** Split a normalized relPath into parent path + leaf name. Root ("") -> null parent. */
    fun splitParent(relPath: String): Pair<String, String>? {
        val n = normalize(relPath)
        if (n.isEmpty()) return null
        val idx = n.lastIndexOf('/')
        return if (idx < 0) "" to n else n.substring(0, idx) to n.substring(idx + 1)
    }

    /** Map a daemon open mode string to a ContentResolver.openFileDescriptor mode. */
    fun mapMode(mode: String): String = when (mode) {
        "r", "ro" -> "r"
        "rw" -> "rw"
        "rwt" -> "rwt"   // truncate on open
        else -> throw IllegalArgumentException("unsupported mode: $mode")
    }
}