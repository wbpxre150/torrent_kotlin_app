package com.torrent.app.storage

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FtpSafIpcRequestHandlerTest {

    /** In-memory stand-in for the SafStorageGateway read-only path. */
    private class FakeGateway {
        val files = mutableMapOf<String, UsbFdBridge.PathInfo>()
        val dirs = mutableSetOf("")
        val children = mutableMapOf<String, List<SafStorageGateway.ChildEntry>>()
        var statSleepMs = 0L
        val openCalls = mutableListOf<String>()
        val removed = mutableListOf<String>()

        fun stat(p: String): UsbFdBridge.PathInfo? {
            if (statSleepMs > 0) Thread.sleep(statSleepMs)
            return when {
                dirs.contains(p) -> UsbFdBridge.PathInfo(true, 0)
                files.containsKey(p) -> files[p]
                else -> null
            }
        }

        fun list(p: String): List<SafStorageGateway.ChildEntry> = children[p] ?: emptyList()

        fun openRead(p: String): ParcelFileDescriptor? {
            openCalls.add(p)
            if (!files.containsKey(p)) return null
            // The mockable android.jar exposes no usable public PFD constructor;
            // with returnDefaultValues the stubbed ctor body is a no-op, so
            // invoke whatever constructor exists and pass nulls.
            val ctor = ParcelFileDescriptor::class.java.constructors.first()
            ctor.isAccessible = true
            val args = ctor.parameterTypes.map {
                when (it) {
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }.toTypedArray()
            return ctor.newInstance(*args) as ParcelFileDescriptor
        }

        fun remove(p: String): Boolean {
            removed.add(p)
            return files.remove(p) != null || dirs.remove(p)
        }
    }

    private fun handler(
        g: FakeGateway? = FakeGateway(),
        available: Boolean = true,
        timeoutSeconds: Long = 30L,
    ) = FtpSafIpcRequestHandler(
        stat = { g?.stat(it) },
        list = { g?.list(it) ?: emptyList() },
        openRead = { g?.openRead(it) },
        remove = { g?.remove(it) ?: false },
        gatewayAvailable = { g != null && available },
        throttleTimeoutSeconds = timeoutSeconds,
    )

    /** Decode a full request frame (stringRequest/roListRequest build the frame). */
    private fun frame(bytes: ByteArray) = SafIpcCodec.decodeRequest(bytes)

    @Test fun gatewayUnavailable_returnsEnodev() {
        val h = handler(g = FakeGateway(), available = false)
        val frames = listOf(
            SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_STAT, "x"),
            SafIpcCodec.roListRequest("x", 0),
            SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_OPEN, "x"),
            SafIpcCodec.stringRequest(SafIpcCodec.OP_REMOVE, "x"),
        )
        for (f in frames)
            assertEquals(-19, h.handle(frame(f)).status)
    }

    @Test fun stat_dispatchesAndReturnsPayload() {
        val g = FakeGateway().apply {
            files["a/b.bin"] = UsbFdBridge.PathInfo(false, 12345L, 1719300000L)
            dirs.add("sub")
        }
        val h = handler(g)
        val r = h.handle(frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_STAT, "a/b.bin")))
        assertEquals(0, r.status)
        assertEquals(UsbFdBridge.PathInfo(false, 12345L, 1719300000L),
            SafIpcCodec.parseStatResponse(SafIpcCodec.Response(0, r.payload)))
        val rd = h.handle(frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_STAT, "sub")))
        assertEquals(UsbFdBridge.PathInfo(true, 0L, 0L),
            SafIpcCodec.parseStatResponse(SafIpcCodec.Response(0, rd.payload)))
        val miss = h.handle(frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_STAT, "nope")))
        assertEquals(-2, miss.status)
    }

    @Test fun list_dispatchesSortsAndPacks() {
        // Unsorted input with metadata; the handler must sort by name and
        // pack a full batch (RO_LIST_END) for a small directory.
        val g = FakeGateway().apply {
            children[""] = listOf(
                SafStorageGateway.ChildEntry("z.bin", false, 3L, 30L),
                SafStorageGateway.ChildEntry("dir", true, 0L, 20L),
                SafStorageGateway.ChildEntry("a.bin", false, 1L, 10L),
            )
        }
        val h = handler(g)
        val r = h.handle(frame(SafIpcCodec.roListRequest("", 0)))
        assertEquals(0, r.status)
        val (next, out) = SafIpcCodec.parseRoListResponse(SafIpcCodec.Response(0, r.payload))
        assertEquals(SafIpcCodec.RO_LIST_END, next)
        assertEquals(listOf("a.bin", "dir", "z.bin"), out.map { it.name })
        assertEquals(
            listOf(
                SafStorageGateway.ChildEntry("a.bin", false, 1L, 10L),
                SafStorageGateway.ChildEntry("dir", true, 0L, 20L),
                SafStorageGateway.ChildEntry("z.bin", false, 3L, 30L),
            ), out)
    }

    @Test fun list_paginatesFromStartIndex() {
        val g = FakeGateway().apply {
            children[""] = (0 until 5).map {
                SafStorageGateway.ChildEntry("f$it", false, it.toLong(), 0L)
            }
        }
        val h = handler(g)
        val r = h.handle(frame(SafIpcCodec.roListRequest("", 3)))
        val (next, out) = SafIpcCodec.parseRoListResponse(SafIpcCodec.Response(0, r.payload))
        assertEquals(SafIpcCodec.RO_LIST_END, next)
        assertEquals(listOf("f3", "f4"), out.map { it.name })
    }

    @Test fun open_dispatches_nullReturnsEnoent() {
        val g = FakeGateway().apply { files["f.bin"] = UsbFdBridge.PathInfo(false, 1L) }
        val h = handler(g)
        val miss = h.handle(frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_OPEN, "nope")))
        assertEquals(-2, miss.status)
        assertNull(miss.fd)
        val hit = h.handle(frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_OPEN, "f.bin")))
        assertEquals(0, hit.status)
        // The pfd is passed through untouched (the server sends the fd and
        // closes it; the handler must NOT detach).
        assertTrue(hit.fd is ParcelFileDescriptor)
        assertEquals(listOf("nope", "f.bin"), g.openCalls)
    }

    @Test fun remove_dispatches_missingReturnsEioStyle() {
        val g = FakeGateway().apply { files["f"] = UsbFdBridge.PathInfo(false, 0) }
        val h = handler(g)
        val ok = h.handle(frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_REMOVE, "f")))
        assertEquals(0, ok.status)
        val miss = h.handle(frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_REMOVE, "f")))
        assertEquals(-5, miss.status)
        assertEquals(listOf("f", "f"), g.removed)
    }

    @Test fun unknownOp_returnsEinval() {
        val h = handler()
        // OP_OPEN (rw path) must not be served on the read-only socket.
        assertEquals(-22, h.handle(frame(SafIpcCodec.openRequest("rw", "f"))).status)
    }

    @Test fun thirdConcurrentCall_timesOutWithEagain() {
        // Two slow stat calls hold both semaphore permits; the third must
        // time out (1 s in the test) and report -11 (EAGAIN -> 450).
        val g = FakeGateway().apply {
            files["f"] = UsbFdBridge.PathInfo(false, 1L)
            statSleepMs = 2500
        }
        val h = handler(g, timeoutSeconds = 1L)
        val f = frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_STAT, "f"))
        val pool = Executors.newFixedThreadPool(3)
        try {
            val futures = (1..3).map { pool.submit<Handled> { h.handle(f) } }
            val statuses = futures.map { it.get(30, TimeUnit.SECONDS).status }
            assertEquals(2, statuses.count { it == 0 })
            assertEquals(1, statuses.count { it == -11 })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun permitsAreReleasedAfterCalls() {
        val g = FakeGateway().apply { files["f"] = UsbFdBridge.PathInfo(false, 1L) }
        val h = handler(g)
        val f = frame(SafIpcCodec.stringRequest(SafIpcCodec.OP_RO_STAT, "f"))
        repeat(4) { assertEquals(0, h.handle(f).status) }
    }
}
