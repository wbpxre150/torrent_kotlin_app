package com.torrent.app.ftp

import com.torrent.app.storage.SafPaths
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import java.io.InputStream

class SafFtpFileSystemView(
    private val source: FtpFileSource,
    private val streamProvider: (relPath: String, offset: Long) -> InputStream,
) : FileSystemView {

    private var cwd: String = "/"

    override fun getHomeDirectory(): FtpFile = file("/")
    override fun getWorkingDirectory(): FtpFile = file(cwd)
    override fun isRandomAccessible(): Boolean = true
    override fun dispose() {}

    override fun changeWorkingDirectory(dir: String): Boolean {
        val target = resolve(dir)
        val f = file(target)
        if (!f.doesExist() || !f.isDirectory()) return false
        cwd = target
        return true
    }

    override fun getFile(path: String): FtpFile = file(resolve(path))

    private fun file(ftpAbs: String): SafFtpFile {
        val rel = if (ftpAbs == "/") "" else ftpAbs.trimStart('/')
        val normalized = SafPaths.normalize(rel)
        return SafFtpFile(ftpAbs, normalized, source, streamProvider)
    }

    private fun resolve(dir: String): String {
        if (dir.isEmpty() || dir == ".") return cwd
        val segs = if (dir.startsWith("/")) mutableListOf() else cwdSegments()
        for (seg in dir.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
                else -> segs.add(seg)
            }
        }
        return if (segs.isEmpty()) "/" else "/" + segs.joinToString("/")
    }

    private fun cwdSegments(): MutableList<String> {
        if (cwd == "/") return mutableListOf()
        return cwd.trimStart('/').split('/').toMutableList()
    }
}