package com.torrent.app.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafIpcCodecTest {

    @Test fun requestFrame_roundTrips() {
        val frame = SafIpcCodec.encodeRequest(SafIpcCodec.OP_EXISTS, byteArrayOf(1, 2, 3))
        val req = SafIpcCodec.decodeRequest(frame)
        assertEquals(SafIpcCodec.OP_EXISTS, req.op)
        assertArrayEquals(byteArrayOf(1, 2, 3), req.payload)
    }

    @Test fun responseFrame_roundTrips() {
        val frame = SafIpcCodec.encodeResponse(-13, byteArrayOf(9))
        val resp = SafIpcCodec.decodeResponse(frame)
        assertEquals(-13, resp.status)
        assertArrayEquals(byteArrayOf(9), resp.payload)
    }

    @Test fun openRequest_roundTrips() {
        val frame = SafIpcCodec.openRequest("rw", "dir/file.bin")
        val req = SafIpcCodec.decodeRequest(frame)
        val (mode, path) = SafIpcCodec.parseOpenRequest(req.payload)
        assertEquals("rw", mode)
        assertEquals("dir/file.bin", path)
    }

    @Test fun openRequest_readMode() {
        val (mode, path) = SafIpcCodec.parseOpenRequest(
            SafIpcCodec.decodeRequest(SafIpcCodec.openRequest("r", "x")).payload)
        assertEquals("r", mode)
        assertEquals("x", path)
    }

    @Test fun truncateRequest_roundTrips() {
        val (path, size) = SafIpcCodec.parseTruncateRequest(
            SafIpcCodec.decodeRequest(SafIpcCodec.truncateRequest("a/b", 0x100000000L)).payload)
        assertEquals("a/b", path)
        assertEquals(0x100000000L, size)
    }

    @Test fun statRequestAndResponse_roundTrip() {
        val reqPath = SafIpcCodec.parseStatRequest(
            SafIpcCodec.decodeRequest(SafIpcCodec.statRequest("root")).payload)
        assertEquals("root", reqPath)
        val resp = SafIpcCodec.decodeResponse(SafIpcCodec.statResponse(isDir = true, size = 0L))
        val info = SafIpcCodec.parseStatResponse(resp)
        assertEquals(UsbFdBridge.PathInfo(true, 0L), info)
    }

    @Test fun statResponse_failure_returnsNull() {
        val resp = SafIpcCodec.decodeResponse(SafIpcCodec.encodeResponse(-2, ByteArray(0)))
        assertNull(SafIpcCodec.parseStatResponse(resp))
    }

    @Test fun renameRequest_roundTrips() {
        val (src, newName) = SafIpcCodec.parseRenameRequest(
            SafIpcCodec.decodeRequest(SafIpcCodec.renameRequest("a/b.part", "b")).payload)
        assertEquals("a/b.part", src)
        assertEquals("b", newName)
    }

    @Test fun stringRequest_roundTrips_forRemoveMkdirsExists() {
        for (op in listOf(SafIpcCodec.OP_REMOVE, SafIpcCodec.OP_MKDIRS, SafIpcCodec.OP_EXISTS)) {
            val path = SafIpcCodec.parseStringRequest(
                SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(op, "p/q")).payload)
            assertEquals("p/q", path)
        }
    }

    @Test fun stringRequest_handlesEmptyAndUtf8() {
        val empty = SafIpcCodec.parseStringRequest(
            SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(SafIpcCodec.OP_EXISTS, "")).payload)
        assertEquals("", empty)
        val utf8 = SafIpcCodec.parseStringRequest(
            SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(SafIpcCodec.OP_EXISTS, "\u0422\u043e\u0440\u0440\u0435\u043d\u0442/\u0444\u0430\u0439\u043b.bin")).payload)
        assertEquals("\u0422\u043e\u0440\u0440\u0435\u043d\u0442/\u0444\u0430\u0439\u043b.bin", utf8)
    }

    @Test fun availResponse_roundTrips() {
        val resp = SafIpcCodec.decodeResponse(SafIpcCodec.availResponse(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, SafIpcCodec.parseAvailResponse(resp))
    }

    @Test fun availResponse_errorReturnsNegativeOne() {
        val resp = SafIpcCodec.decodeResponse(SafIpcCodec.encodeResponse(-5, ByteArray(0)))
        assertEquals(-1L, SafIpcCodec.parseAvailResponse(resp))
    }
}