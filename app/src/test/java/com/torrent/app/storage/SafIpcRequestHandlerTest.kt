package com.torrent.app.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class SafIpcRequestHandlerTest {

    private class FakeBridge : UsbFdBridge {
        val files = mutableMapOf<String, UsbFdBridge.PathInfo>()
        val dirs = mutableSetOf("")
        var avail = Long.MAX_VALUE
        var lastOpenMode: String? = null

        override fun openFd(relPath: String, mode: String): Int { lastOpenMode = mode; return -1 }
        override fun truncate(relPath: String, len: Long): Boolean = files.containsKey(relPath)
        override fun statSize(relPath: String): Long = files[relPath]?.size ?: -1L
        override fun renameDoc(relPath: String, dstRelPath: String): Boolean = files.containsKey(relPath)
        override fun removeDoc(relPath: String): Boolean =
            files.remove(relPath) != null || dirs.remove(relPath)
        override fun mkdirs(relPath: String): Boolean { dirs.add(relPath); return true }
        override fun availableBytes(): Long = avail
        override fun exists(relPath: String): Boolean =
            files.containsKey(relPath) || dirs.contains(relPath)
        override fun list(relPath: String): List<String> = emptyList()
        override fun stat(relPath: String): UsbFdBridge.PathInfo? = when {
            dirs.contains(relPath) -> UsbFdBridge.PathInfo(true, 0)
            files.containsKey(relPath) -> files[relPath]
            else -> null
        }
    }

    private fun req(op: Byte, payload: ByteArray = ByteArray(0)) = SafIpcCodec.Request(op, payload)

    @Test fun noBridge_returnsEnodev() {
        val h = SafIpcRequestHandler { null }
        assertEquals(-19, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(SafIpcCodec.OP_EXISTS, "x"))).status)
    }

    @Test fun openFailure_returnsEio() {
        val b = FakeBridge()
        val h = SafIpcRequestHandler { b }
        val frame = SafIpcCodec.openRequest("rw", "f.bin")
        val r = h.handle(SafIpcCodec.decodeRequest(frame))
        assertEquals(-5, r.status)
        assertEquals(null, r.fd)
        assertEquals("rw", b.lastOpenMode)
    }

    @Test fun stat_missingReturnsEnoent() {
        val h = SafIpcRequestHandler { FakeBridge() }
        val r = h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.statRequest("nope")))
        assertEquals(-2, r.status)
    }

    @Test fun stat_fileReturnsTypeAndSize() {
        val b = FakeBridge().apply { files["a/b.bin"] = UsbFdBridge.PathInfo(false, 12345L, 1719300000L) }
        val h = SafIpcRequestHandler { b }
        val r = h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.statRequest("a/b.bin")))
        assertEquals(0, r.status)
        val info = SafIpcCodec.parseStatResponse(SafIpcCodec.Response(0, r.payload))
        assertEquals(UsbFdBridge.PathInfo(false, 12345L, 1719300000L), info)
    }

    @Test fun stat_dirReturnsIsDir() {
        val b = FakeBridge().apply { dirs.add("sub") }
        val h = SafIpcRequestHandler { b }
        val r = h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.statRequest("sub")))
        assertEquals(0, r.status)
        assertEquals(UsbFdBridge.PathInfo(true, 0L, 0L),
            SafIpcCodec.parseStatResponse(SafIpcCodec.Response(0, r.payload)))
    }

    @Test fun exists_trueFalse() {
        val b = FakeBridge().apply { files["x"] = UsbFdBridge.PathInfo(false, 0) }
        val h = SafIpcRequestHandler { b }
        assertEquals(1, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(SafIpcCodec.OP_EXISTS, "x"))).status)
        assertEquals(0, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(SafIpcCodec.OP_EXISTS, "y"))).status)
    }

    @Test fun truncate_successAndFailure() {
        val b = FakeBridge().apply { files["f"] = UsbFdBridge.PathInfo(false, 10) }
        val h = SafIpcRequestHandler { b }
        assertEquals(0, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.truncateRequest("f", 5L))).status)
        assertEquals(-5, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.truncateRequest("missing", 5L))).status)
    }

    @Test fun rename_successAndFailure() {
        val b = FakeBridge().apply { files["a.part"] = UsbFdBridge.PathInfo(false, 0) }
        val h = SafIpcRequestHandler { b }
        assertEquals(0, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.renameRequest("a.part", "a"))).status)
        assertEquals(0, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.renameRequest("a.part", "sub/a"))).status)
        assertEquals(-5, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.renameRequest("nope", "a"))).status)
    }

    @Test fun remove_successAndFailure() {
        val b = FakeBridge().apply { files["f"] = UsbFdBridge.PathInfo(false, 0) }
        val h = SafIpcRequestHandler { b }
        assertEquals(0, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(SafIpcCodec.OP_REMOVE, "f"))).status)
        assertEquals(-5, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(SafIpcCodec.OP_REMOVE, "f"))).status)
    }

    @Test fun mkdirs_returnsOk() {
        val h = SafIpcRequestHandler { FakeBridge() }
        assertEquals(0, h.handle(SafIpcCodec.decodeRequest(SafIpcCodec.stringRequest(SafIpcCodec.OP_MKDIRS, "d/e"))).status)
    }

    @Test fun avail_returnsValue() {
        val b = FakeBridge().apply { avail = 999L }
        val h = SafIpcRequestHandler { b }
        val r = h.handle(req(SafIpcCodec.OP_AVAIL))
        assertEquals(0, r.status)
        assertEquals(999L, SafIpcCodec.parseAvailResponse(SafIpcCodec.Response(0, r.payload)))
    }

    @Test fun unknownOp_returnsEinval() {
        val h = SafIpcRequestHandler { FakeBridge() }
        assertEquals(-22, h.handle(req(99)).status)
    }
}