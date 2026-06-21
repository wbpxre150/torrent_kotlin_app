package com.torrent.app.ftp

import android.os.ParcelFileDescriptor
import com.torrent.app.storage.SafStorageGateway

class SafFtpFileSource(private val gateway: SafStorageGateway) : FtpFileSource {

    override fun list(relPath: String): List<FtpFileSource.Entry> {
        if (gateway.stat(relPath)?.isDir != true) return emptyList()
        return gateway.list(relPath).map { name ->
            val childRel = if (relPath.isEmpty()) name else "$relPath/$name"
            val st = gateway.stat(childRel)
            FtpFileSource.Entry(name, st?.isDir ?: false, st?.size ?: 0L, st?.lastModified ?: 0L)
        }
    }

    override fun stat(relPath: String): FtpFileSource.Entry? {
        val st = gateway.stat(relPath) ?: return null
        val leaf = if (relPath.isEmpty()) "/" else relPath.substringAfterLast('/')
        return FtpFileSource.Entry(leaf, st.isDir, st.size, st.lastModified)
    }

    fun openReadPfd(relPath: String): ParcelFileDescriptor? =
        gateway.openReadPfd(relPath)

    override fun delete(relPath: String): Boolean = gateway.removeDoc(relPath)
}