package com.torrent.app.ftp

interface FtpFileSource {
    data class Entry(val name: String, val isDir: Boolean, val size: Long, val lastModified: Long = 0L)
    fun list(relPath: String): List<Entry>
    fun stat(relPath: String): Entry?
}