package com.torrent.app.ftp

interface FtpFileSource {
    data class Entry(val name: String, val isDir: Boolean, val size: Long)
    fun list(relPath: String): List<Entry>
    fun stat(relPath: String): Entry?
}