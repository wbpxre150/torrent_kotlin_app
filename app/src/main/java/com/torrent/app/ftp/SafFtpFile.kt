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
    // Plan 07 §2.5: prefetched metadata from source.list() so children created
    // during a listing do NOT trigger a per-child stat (N+1 SAF queries).
    private val entry: FtpFileSource.Entry? = null,
) : FtpFile {

    private val resolvedEntry: FtpFileSource.Entry? by lazy { entry ?: source.stat(relPath) }

    override fun getAbsolutePath(): String = absolutePath
    override fun getName(): String {
        if (absolutePath == "/") return "/"
        val idx = absolutePath.lastIndexOf('/')
        return if (idx < 0) absolutePath else absolutePath.substring(idx + 1)
    }
    override fun isHidden(): Boolean = false
    override fun isDirectory(): Boolean = resolvedEntry?.isDir ?: false
    override fun isFile(): Boolean = resolvedEntry?.let { !it.isDir } ?: false
    override fun doesExist(): Boolean = resolvedEntry != null
    override fun isReadable(): Boolean = true
    override fun isWritable(): Boolean = false
    override fun isRemovable(): Boolean = relPath.isNotEmpty()
    override fun getOwnerName(): String = "torrent"
    override fun getGroupName(): String = "torrent"
    override fun getLinkCount(): Int = 1
    override fun getLastModified(): Long = resolvedEntry?.lastModified ?: 0L
    override fun setLastModified(time: Long): Boolean = false
    override fun getSize(): Long = resolvedEntry?.size ?: 0L
    override fun getPhysicalFile(): String = relPath
    override fun mkdir(): Boolean = false
    override fun delete(): Boolean = source.delete(relPath)
    override fun move(destination: FtpFile): Boolean = false

    override fun listFiles(): List<SafFtpFile>? {
        if (!isDirectory()) return null
        val children = source.list(relPath)
            .sortedBy { it.name }
        return children.map { e ->
            val childAbs = joinPath(absolutePath, e.name)
            val childRel = joinRel(relPath, e.name)
            // Pass the prefetched Entry: no per-child stat on LIST (plan 07 §2.5).
            SafFtpFile(childAbs, childRel, source, streamProvider, e)
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
