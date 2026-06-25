package com.torrent.app.storage

import android.os.ParcelFileDescriptor

/**
 * Maps one decoded IPC [SafIpcCodec.Request] to a [Handled] response using the SAF
 * gateway obtained from [bridgeProvider]. Pure (no socket I/O); the server frames and
 * sends [Handled]. For OP_OPEN success, [Handled.fd] is a non-detached pfd whose fd the
 * server transmits via SCM_RIGHTS and then closes.
 *
 * Status convention: 0 = ok (EXISTS uses 1/0), negative = error (native maps to errno).
 */
class SafIpcRequestHandler(private val bridgeProvider: () -> UsbFdBridge?) {

    data class Handled(
        val status: Int,
        val payload: ByteArray = ByteArray(0),
        val fd: ParcelFileDescriptor? = null,
    )

    fun handle(req: SafIpcCodec.Request): Handled {
        val bridge = bridgeProvider() ?: return Handled(-19) // ENODEV: USB not granted
        return when (req.op) {
            SafIpcCodec.OP_OPEN -> {
                val (mode, relPath) = SafIpcCodec.parseOpenRequest(req.payload)
                val fd = bridge.openFd(relPath, mode)
                if (fd < 0) Handled(-5) // EIO
                else Handled(0, fd = ParcelFileDescriptor.adoptFd(fd))
            }
            SafIpcCodec.OP_TRUNCATE -> {
                val (relPath, size) = SafIpcCodec.parseTruncateRequest(req.payload)
                Handled(if (bridge.truncate(relPath, size)) 0 else -5)
            }
            SafIpcCodec.OP_STAT -> {
                val relPath = SafIpcCodec.parseStatRequest(req.payload)
                val info = bridge.stat(relPath)
                if (info == null) Handled(-2) // ENOENT
                else Handled(0, SafIpcCodec.statPayload(info.isDir, info.size, info.lastModified))
            }
            SafIpcCodec.OP_RENAME -> {
                val (relPath, newName) = SafIpcCodec.parseRenameRequest(req.payload)
                Handled(if (bridge.renameDoc(relPath, newName)) 0 else -5)
            }
            SafIpcCodec.OP_REMOVE -> {
                val relPath = SafIpcCodec.parseStringRequest(req.payload)
                Handled(if (bridge.removeDoc(relPath)) 0 else -5)
            }
            SafIpcCodec.OP_MKDIRS -> {
                val relPath = SafIpcCodec.parseStringRequest(req.payload)
                Handled(if (bridge.mkdirs(relPath)) 0 else -5)
            }
            SafIpcCodec.OP_AVAIL -> Handled(0, SafIpcCodec.availPayload(bridge.availableBytes()))
            SafIpcCodec.OP_EXISTS -> {
                val relPath = SafIpcCodec.parseStringRequest(req.payload)
                Handled(if (bridge.exists(relPath)) 1 else 0)
            }
            else -> Handled(-22) // EINVAL
        }
    }
}