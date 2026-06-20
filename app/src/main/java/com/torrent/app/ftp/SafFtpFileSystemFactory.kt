package com.torrent.app.ftp

import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.User
import java.io.InputStream

class SafFtpFileSystemFactory(
    private val source: FtpFileSource,
    private val streamProvider: (relPath: String, offset: Long) -> InputStream,
) : FileSystemFactory {
    override fun createFileSystemView(user: User): FileSystemView =
        SafFtpFileSystemView(source, streamProvider)
}