package com.torrent.app.storage

import android.os.ParcelFileDescriptor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * IPC handler for the native FTP daemon's read-only SAF socket (stage 2).
 *
 * Serves the USB tree to the ftpserver binary through the gateway's read-only
 * path only (plan 07 §2.1): [SafStorageGateway.readOnlyStat],
 * [SafStorageGateway.readOnlyListDetailed], [SafStorageGateway.readOnlyOpenRead],
 * and [SafStorageGateway.removeDoc] for DELE/RMD (SAF deleteDocument also
 * removes empty directories) — never the shared write cache, preserving the
 * FTP/transmission isolation of commit 75a40d5.
 *
 * Every gateway call runs under a fair [Semaphore] with a 30 s timeout — the
 * same throttle budget the in-process FTP server uses (FtpSafThrottle) — so
 * the daemon cannot wedge the SAF provider. Timeout -> -11 (EAGAIN; the daemon
 * answers 450), no gateway -> -19 (ENODEV).
 *
 * Plain-JVM testable: the gateway is injected as provider lambdas;
 * DaemonService passes lambdas over its (reassignable) gateway field.
 */
class FtpSafIpcRequestHandler(
    private val stat: (String) -> UsbFdBridge.PathInfo?,
    private val list: (String) -> List<SafStorageGateway.ChildEntry>,
    private val openRead: (String) -> ParcelFileDescriptor?,
    private val remove: (String) -> Boolean,
    private val gatewayAvailable: () -> Boolean,
    private val throttleTimeoutSeconds: Long = THROTTLE_TIMEOUT_S,
) : SafIpcHandler {

    private val throttle = Semaphore(THROTTLE_PERMITS, /* fair = */ true)

    override fun handle(req: SafIpcCodec.Request): Handled {
        if (!gatewayAvailable()) return Handled(-19) // ENODEV: USB not granted
        return withThrottle {
            when (req.op) {
                SafIpcCodec.OP_RO_STAT -> {
                    val relPath = SafIpcCodec.parseStringRequest(req.payload)
                    val info = stat(relPath)
                    if (info == null) Handled(-2) // ENOENT
                    else Handled(0, SafIpcCodec.statPayload(info.isDir, info.size, info.lastModified))
                }
                SafIpcCodec.OP_RO_LIST -> {
                    val (relPath, startIndex) = SafIpcCodec.parseRoListRequest(req.payload)
                    val sorted = list(relPath).sortedBy { it.name }
                    val batch = SafIpcCodec.packRoListBatch(sorted, startIndex)
                    Handled(0, SafIpcCodec.roListPayload(batch.nextIndex, batch.entries))
                }
                SafIpcCodec.OP_RO_OPEN -> {
                    val relPath = SafIpcCodec.parseStringRequest(req.payload)
                    val pfd = openRead(relPath)
                    if (pfd == null) Handled(-2) // ENOENT (provider miss/error)
                    else Handled(0, fd = pfd) // server sends the fd, then closes the pfd
                }
                SafIpcCodec.OP_REMOVE -> {
                    // DELE; SAF deleteDocument also removes empty dirs (RMD).
                    val relPath = SafIpcCodec.parseStringRequest(req.payload)
                    Handled(if (remove(relPath)) 0 else -5)
                }
                else -> Handled(-22) // EINVAL
            }
        }
    }

    /** Runs [block] under the 2-permit SAF throttle; -11 (EAGAIN) on timeout. */
    private inline fun withThrottle(block: () -> Handled): Handled {
        var acquired = false
        try {
            acquired = throttle.tryAcquire(throttleTimeoutSeconds, TimeUnit.SECONDS)
            if (!acquired) return Handled(-11) // EAGAIN: daemon answers 450
            return block()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Handled(-11)
        } finally {
            if (acquired) throttle.release()
        }
    }

    companion object {
        const val THROTTLE_PERMITS = 2
        const val THROTTLE_TIMEOUT_S = 30L
    }
}
