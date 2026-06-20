package com.torrent.app.ftp

import org.apache.ftpserver.ftplet.FtpFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SafFtpFile(
    private val absolutePath: String,
    private val relPath: String,
    private val source: FtpFileSource,
    private val streamProvider: (relPath: String, offset: Long) -> InputStream,
) : FtpFile {

    private val entry: FtpFileSource.Entry? by lazy { source.stat(relPath) }

    override fun getAbsolutePath(): String = absolutePath
    override fun getName(): String {
        if (absolutePath == "/") return "/"
        val idx = absolutePath.lastIndexOf('/')
        return if (idx < 0) absolutePath else absolutePath.substring(idx + 1)
    }
    override fun isHidden(): Boolean = false
    override fun isDirectory(): Boolean = entry?.isDir ?: false
    override fun isFile(): Boolean = entry?.let { !it.isDir } ?: false
    override fun doesExist(): Boolean = entry != null
    override fun isReadable(): Boolean = true
    override fun isWritable(): Boolean = false
    override fun isRemovable(): Boolean = false
    override fun getOwnerName(): String = "torrent"
    override fun getGroupName(): String = "torrent"
    override fun getLinkCount(): Int = 1
    override fun getLastModified(): Long = 0L
    override fun setLastModified(time: Long): Boolean = false
    override fun getSize(): Long = entry?.size ?: 0L
    override fun getPhysicalFile(): String = relPath
    override fun mkdir(): Boolean = false
    override fun delete(): Boolean = false
    override fun move(destination: FtpFile): Boolean = false

    override fun listFiles(): List<SafFtpFile>? {
        if (!isDirectory()) return null
        val children = source.list(relPath)
            .sortedBy { it.name }
        return children.map { e ->
            val childAbs = joinPath(absolutePath, e.name)
            val childRel = joinRel(relPath, e.name)
            SafFtpFile(childAbs, childRel, source, streamProvider)
        }
    }

    override fun createOutputStream(offset: Long): OutputStream =
        throw IOException("FTP server is read-only")

    override fun createInputStream(offset: Long): InputStream = streamProvider(relPath, offset)

    private fun joinPath(abs: String, name: String): String =
        if (abs == "/") "/$name" else "$abs/$name"

    private fun joinRel(rel: String, name: String): String =
        if (rel.isEmpty()) name else "$rel/$name"
}