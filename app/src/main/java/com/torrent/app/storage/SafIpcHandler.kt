package com.torrent.app.storage

import android.os.ParcelFileDescriptor

/**
 * Maps one decoded IPC [SafIpcCodec.Request] to a [Handled] response. Pure (no
 * socket I/O): [SafIpcServer] does the framing, SCM_RIGHTS fd attach, and close.
 *
 * Implementations:
 * - [SafIpcRequestHandler] — full rw bridge for the transmission fork.
 * - [FtpSafIpcRequestHandler] — read-only ops for the native FTP daemon (stage 2).
 */
interface SafIpcHandler {
    fun handle(req: SafIpcCodec.Request): Handled
}

/**
 * Outcome of [SafIpcHandler.handle]. Status convention: 0 = ok (EXISTS uses
 * 1/0), negative = error (native maps to errno). For OP_OPEN/OP_RO_OPEN
 * success, [fd] is a non-detached pfd whose fd the server transmits via
 * SCM_RIGHTS and then closes.
 */
data class Handled(
    val status: Int,
    val payload: ByteArray = ByteArray(0),
    val fd: ParcelFileDescriptor? = null,
)
