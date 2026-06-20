package com.torrent.app.storage

/**
 * Native-facing SAF fd bridge. This is the API the transmission fork (Stage 05) reaches
 * through JNI. Implementations MUST be synchronous and thread-safe (transmission's disk
 * thread calls these directly).
 *
 * fd ownership: [openFd] returns a raw int via ParcelFileDescriptor.detachFd(); the
 * native caller owns it and MUST close() it. All other methods open+close their own fds
 * internally and never leak.
 */
interface UsbFdBridge {
    /**
     * Open [relPath] for [mode] ("r"/"rw"/"rwt"). Ensures parent dirs exist and the
     * document is created if missing. Returns a raw POSIX fd (owned by caller), or -1
     * (errno logged) on failure.
     */
    fun openFd(relPath: String, mode: String): Int

    /** Grow/shrink the document to exactly [len] bytes. Returns false on failure. */
    fun truncate(relPath: String, len: Long): Boolean

    /** Current file size in bytes, or -1 on failure. */
    fun statSize(relPath: String): Long

    /** Rename the leaf of [relPath] to [newName] (same parent). Returns false on failure. */
    fun renameDoc(relPath: String, newName: String): Boolean

    /** Recursively remove [relPath]. Returns false on failure (or if absent). */
    fun removeDoc(relPath: String): Boolean

    /** Create [relPath] and all missing ancestors as directories. Returns false on failure. */
    fun mkdirs(relPath: String): Boolean

    /**
     * Available bytes on the USB volume, or Long.MAX_VALUE if the provider cannot report
     * it (Stage 05 then relies on write failures).
     */
    fun availableBytes(): Long

    // higher-level helpers (used by the UI / future FTP stage)
    fun exists(relPath: String): Boolean
    fun list(relPath: String): List<String>   // child names

    /** Combined type+size for a document, or null if it does not exist. */
    data class PathInfo(val isDir: Boolean, val size: Long)

    /**
     * Stat [relPath]: is it a directory, and its size in bytes. Returns null if the
     * document does not exist. Directories report size 0.
     */
    fun stat(relPath: String): PathInfo?
}