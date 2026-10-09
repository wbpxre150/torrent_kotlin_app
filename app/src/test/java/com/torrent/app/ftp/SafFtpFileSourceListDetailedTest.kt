package com.torrent.app.ftp

import com.torrent.app.storage.SafStorageGateway
import com.torrent.app.storage.UsbFdBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class SafFtpFileSourceListDetailedTest {

    /**
     * list() must make exactly one readOnlyStat() (parent dir check) + one
     * readOnlyListDetailed() (single children query), not N+1 stat() calls per
     * child. It must use ONLY the read-only FTP gateway API (plan 07 §2.1), so
     * it can never take transmission's path locks or write the shared cache.
     */
    @Test
    fun listUsesSingleChildrenQueryNotNPlus1Stats() {
        val gateway = Mockito.mock(SafStorageGateway::class.java)
        Mockito.`when`(gateway.readOnlyStat("dir")).thenReturn(
            UsbFdBridge.PathInfo(isDir = true, size = 0L, lastModified = 0L))
        Mockito.`when`(gateway.readOnlyListDetailed("dir")).thenReturn(listOf(
            SafStorageGateway.ChildEntry("a.mkv", false, 100L, 1L),
            SafStorageGateway.ChildEntry("b.mkv", false, 200L, 2L),
            SafStorageGateway.ChildEntry("sub", true, 0L, 3L),
        ))

        val src = SafFtpFileSource(gateway)
        val entries = src.list("dir")

        assertEquals(3, entries.size)
        assertEquals("a.mkv", entries[0].name)
        assertEquals(false, entries[0].isDir)
        assertEquals(100L, entries[0].size)
        assertEquals(1L, entries[0].lastModified)
        assertEquals("sub", entries[2].name)
        assertEquals(true, entries[2].isDir)

        // list() called readOnlyStat() once for the parent, and readOnlyListDetailed() once.
        Mockito.verify(gateway, Mockito.times(1)).readOnlyStat("dir")
        Mockito.verify(gateway, Mockito.times(1)).readOnlyListDetailed("dir")
        // It must NOT have called stat() per child.
        Mockito.verify(gateway, Mockito.never()).readOnlyStat("dir/a.mkv")
        Mockito.verify(gateway, Mockito.never()).readOnlyStat("dir/b.mkv")
        Mockito.verify(gateway, Mockito.never()).readOnlyStat("dir/sub")
        // And it must NOT have touched the transmission-path gateway methods.
        Mockito.verify(gateway, Mockito.never()).stat(Mockito.anyString())
        Mockito.verify(gateway, Mockito.never()).listDetailed(Mockito.anyString())
    }

    @Test
    fun listOnNonDirectoryReturnsEmpty() {
        val gateway = Mockito.mock(SafStorageGateway::class.java)
        Mockito.`when`(gateway.readOnlyStat("file.mkv")).thenReturn(
            UsbFdBridge.PathInfo(isDir = false, size = 100L, lastModified = 1L))

        val src = SafFtpFileSource(gateway)
        val entries = src.list("file.mkv")

        assertEquals(0, entries.size)
        Mockito.verify(gateway, Mockito.never()).readOnlyListDetailed(Mockito.anyString())
    }
}
