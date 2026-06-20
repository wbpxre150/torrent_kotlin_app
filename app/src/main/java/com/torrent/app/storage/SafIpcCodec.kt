package com.torrent.app.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure codec for the SAF IPC wire protocol shared by [SafIpcServer] (Kotlin) and the
 * transmission fork's `file-saf.cc` (native). Little-endian throughout. No socket I/O.
 *
 * Request  frame: [u8 op][u16 payloadLen][payload]
 * Response frame: [i32 status][u16 payloadLen][payload]
 *
 * For OP_OPEN success the server additionally sends exactly one fd via SCM_RIGHTS
 * alongside the response frame; the codec never touches file descriptors.
 */
object SafIpcCodec {
    const val OP_OPEN: Byte = 1
    const val OP_TRUNCATE: Byte = 2
    const val OP_STAT: Byte = 3
    const val OP_RENAME: Byte = 4
    const val OP_REMOVE: Byte = 5
    const val OP_MKDIRS: Byte = 6
    const val OP_AVAIL: Byte = 7
    const val OP_EXISTS: Byte = 8

    const val MODE_R: Byte = 0
    const val MODE_RW: Byte = 1

    data class Request(val op: Byte, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (other !is Request) return false
            return op == other.op && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = op.toInt() * 31 + payload.contentHashCode()
    }
    data class Response(val status: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (other !is Response) return false
            return status == other.status && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = status * 31 + payload.contentHashCode()
    }

    private fun leU16(buf: ByteBuffer): Int =
        (buf.get().toInt() and 0xff) or ((buf.get().toInt() and 0xff) shl 8)
    private fun leU16(b0: Int, b1: Int): Int = (b0 and 0xff) or ((b1 and 0xff) shl 8)
    private fun putU16(buf: ByteBuffer, v: Int) {
        buf.put((v and 0xff).toByte()); buf.put(((v ushr 8) and 0xff).toByte())
    }
    private fun putI32(buf: ByteBuffer, v: Int) {
        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(v)
    }
    private fun putI64(buf: ByteBuffer, v: Long) {
        buf.order(ByteOrder.LITTLE_ENDIAN).putLong(v)
    }
    private fun leI32(buf: ByteBuffer): Int = buf.order(ByteOrder.LITTLE_ENDIAN).int
    private fun leI64(buf: ByteBuffer): Long = buf.order(ByteOrder.LITTLE_ENDIAN).long

    private fun putString(buf: ByteBuffer, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        putU16(buf, b.size)
        buf.put(b)
    }
    private fun readString(buf: ByteBuffer): String {
        val len = leU16(buf)
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    // ---- frame encode/decode ----

    fun encodeRequest(op: Byte, payload: ByteArray): ByteArray =
        ByteArray(3 + payload.size).also { buf ->
            buf[0] = op
            buf[1] = (payload.size and 0xff).toByte()
            buf[2] = ((payload.size ushr 8) and 0xff).toByte()
            System.arraycopy(payload, 0, buf, 3, payload.size)
        }

    fun decodeRequest(bytes: ByteArray): Request {
        require(bytes.size >= 3) { "short request frame" }
        val op = bytes[0]
        val len = leU16(bytes[1].toInt(), bytes[2].toInt())
        require(bytes.size >= 3 + len) { "truncated request payload" }
        val payload = bytes.copyOfRange(3, 3 + len)
        return Request(op, payload)
    }

    fun encodeResponse(status: Int, payload: ByteArray): ByteArray =
        ByteArray(6 + payload.size).also { buf ->
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(status)
            bb.put((payload.size and 0xff).toByte())
            bb.put(((payload.size ushr 8) and 0xff).toByte())
            bb.put(payload)
        }

    fun decodeResponse(bytes: ByteArray): Response {
        require(bytes.size >= 6) { "short response frame" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val status = bb.int
        val len = leU16(bb)
        require(bytes.size >= 6 + len) { "truncated response payload" }
        val payload = ByteArray(len)
        bb.get(payload)
        return Response(status, payload)
    }

    // ---- typed payload builders/parsers ----

    fun openRequest(mode: String, relPath: String): ByteArray {
        val m = if (mode == "rw") MODE_RW else MODE_R
        val sb = relPath.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 2 + sb.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(m); putU16(buf, sb.size); buf.put(sb)
        return encodeRequest(OP_OPEN, buf.array())
    }
    fun parseOpenRequest(payload: ByteArray): Pair<String, String> {
        val bb = ByteBuffer.wrap(payload)
        val mode = if (bb.get() == MODE_RW) "rw" else "r"
        return mode to readString(bb)
    }

    fun truncateRequest(relPath: String, size: Long): ByteArray {
        val sb = relPath.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(2 + sb.size + 8).order(ByteOrder.LITTLE_ENDIAN)
        putString(buf, relPath); putI64(buf, size)
        return encodeRequest(OP_TRUNCATE, buf.array())
    }
    fun parseTruncateRequest(payload: ByteArray): Pair<String, Long> {
        val bb = ByteBuffer.wrap(payload)
        return readString(bb) to leI64(bb)
    }

    fun statRequest(relPath: String): ByteArray {
        val sb = relPath.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(2 + sb.size).order(ByteOrder.LITTLE_ENDIAN)
        putString(buf, relPath)
        return encodeRequest(OP_STAT, buf.array())
    }
    fun parseStatRequest(payload: ByteArray): String =
        readString(ByteBuffer.wrap(payload))
    fun statResponse(isDir: Boolean, size: Long): ByteArray {
        val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(if (isDir) 1.toByte() else 0.toByte()); putI64(buf, size)
        return encodeResponse(0, buf.array())
    }
    fun parseStatResponse(resp: Response): UsbFdBridge.PathInfo? {
        if (resp.status != 0) return null
        val bb = ByteBuffer.wrap(resp.payload)
        val isDir = bb.get() == 1.toByte()
        return UsbFdBridge.PathInfo(isDir, leI64(bb))
    }

    fun renameRequest(relPath: String, newName: String): ByteArray {
        val buf = ByteBuffer.allocate(2 + relPath.toByteArray(Charsets.UTF_8).size +
            2 + newName.toByteArray(Charsets.UTF_8).size).order(ByteOrder.LITTLE_ENDIAN)
        putString(buf, relPath); putString(buf, newName)
        return encodeRequest(OP_RENAME, buf.array())
    }
    fun parseRenameRequest(payload: ByteArray): Pair<String, String> {
        val bb = ByteBuffer.wrap(payload)
        return readString(bb) to readString(bb)
    }

    fun stringRequest(op: Byte, relPath: String): ByteArray {
        val sb = relPath.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(2 + sb.size).order(ByteOrder.LITTLE_ENDIAN)
        putString(buf, relPath)
        return encodeRequest(op, buf.array())
    }
    fun parseStringRequest(payload: ByteArray): String = readString(ByteBuffer.wrap(payload))

    fun availResponse(bytes: Long): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        putI64(buf, bytes)
        return encodeResponse(0, buf.array())
    }
    fun parseAvailResponse(resp: Response): Long =
        if (resp.status != 0) -1L else leI64(ByteBuffer.wrap(resp.payload))

    /** Payload-only (no frame) for a STAT success response. */
    fun statPayload(isDir: Boolean, size: Long): ByteArray {
        val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(if (isDir) 1.toByte() else 0.toByte()); putI64(buf, size)
        return buf.array()
    }
    /** Payload-only (no frame) for an AVAIL success response. */
    fun availPayload(bytes: Long): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        putI64(buf, bytes); return buf.array()
    }
}