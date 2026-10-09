package com.torrent.app.ftp

import android.os.ParcelFileDescriptor
import com.torrent.app.storage.SafStorageGateway

/**
 * SAF-backed FTP file source. Uses ONLY the gateway's read-only FTP API
 * (plan 07 §2.1), so it never takes transmission's path locks and never
 * writes the shared URI cache. Every metadata/open call is guarded by
 * [FtpSafThrottle] (§2.2) and runs at background priority (§2.3).
 *
 * [delete] keeps using the existing transmission-safe [SafStorageGateway.removeDoc],
 * which also evicts the gateway's private FTP read cache.
 */
class SafFtpFileSource(private val gateway: SafStorageGateway) : FtpFileSource {

    override fun list(relPath: String): List<FtpFileSource.Entry> {
        FtpThreads.lowerPriority()
        if (FtpSafThrottle.withLimit { gateway.readOnlyStat(relPath) }?.isDir != true)
            return emptyList()
        return FtpSafThrottle.withLimit { gateway.readOnlyListDetailed(relPath) }
            ?.map { child ->
                FtpFileSource.Entry(child.name, child.isDir, child.size, child.lastModified)
            }
            ?: emptyList()
    }

    override fun stat(relPath: String): FtpFileSource.Entry? {
        FtpThreads.lowerPriority()
        val st = FtpSafThrottle.withLimit { gateway.readOnlyStat(relPath) } ?: return null
        val leaf = if (relPath.isEmpty()) "/" else relPath.substringAfterLast('/')
        return FtpFileSource.Entry(leaf, st.isDir, st.size, st.lastModified)
    }

    fun openReadPfd(relPath: String): ParcelFileDescriptor? {
        FtpThreads.lowerPriority()
        return FtpSafThrottle.withLimit { gateway.readOnlyOpenRead(relPath) }
    }

    override fun delete(relPath: String): Boolean {
        FtpThreads.lowerPriority()
        return gateway.removeDoc(relPath)
    }
}
