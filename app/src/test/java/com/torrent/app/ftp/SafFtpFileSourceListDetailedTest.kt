package com.torrent.app.ftp

import com.torrent.app.storage.SafStorageGateway
import com.torrent.app.storage.UsbFdBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class SafFtpFileSourceListDetailedTest {

    /**
     * list() must make exactly one stat() (parent dir check) + one listDetailed()
     * (single children query), not N+1 stat() calls per child.
     */
    @Test
    fun listUsesSingleChildrenQueryNotNPlus1Stats() {
        val gateway = Mockito.mock(SafStorageGateway::class.java)
        Mockito.`when`(gateway.stat("dir")).thenReturn(
            UsbFdBridge.PathInfo(isDir = true, size = 0L, lastModified = 0L))
        Mockito.`when`(gateway.listDetailed("dir")).thenReturn(listOf(
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

        // list() called stat() once for the parent, and listDetailed() once.
        Mockito.verify(gateway, Mockito.times(1)).stat("dir")
        Mockito.verify(gateway, Mockito.times(1)).listDetailed("dir")
        // It must NOT have called stat() per child.
        Mockito.verify(gateway, Mockito.never()).stat("dir/a.mkv")
        Mockito.verify(gateway, Mockito.never()).stat("dir/b.mkv")
        Mockito.verify(gateway, Mockito.never()).stat("dir/sub")
    }

    @Test
    fun listOnNonDirectoryReturnsEmpty() {
        val gateway = Mockito.mock(SafStorageGateway::class.java)
        Mockito.`when`(gateway.stat("file.mkv")).thenReturn(
            UsbFdBridge.PathInfo(isDir = false, size = 100L, lastModified = 1L))

        val src = SafFtpFileSource(gateway)
        val entries = src.list("file.mkv")

        assertEquals(0, entries.size)
        Mockito.verify(gateway, Mockito.never()).listDetailed(Mockito.anyString())
    }
}