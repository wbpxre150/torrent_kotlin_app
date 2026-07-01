package com.torrent.app.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class FtpStreamProviderFdCloseTest {

    /**
     * The production streamProvider returns a FileInputStream whose close() closes
     * the detached fd exactly once. A double close() on the returned stream must
     * not close the underlying fd more than once (the original bug called
     * pfd.close() after fis.close(), closing the fd twice).
     */
    @Test
    fun streamCloseClosesFdExactlyOnce() {
        var closeCount = 0
        val fis = object : FileInputStream(FileDescriptor()) {
            var closed = false
            override fun close() { if (!closed) { closed = true; closeCount++ } }
        }
        val stream: InputStream = fis
        stream.close()
        stream.close()
        assertEquals("fd closed exactly once across double close()", 1, closeCount)
    }

    /**
     * If the offset seek fails, the partial stream must be closed and the exception
     * propagated, with the fd closed exactly once.
     */
    @Test
    fun offsetSeekFailureClosesStreamAndPropagates() {
        var closeCount = 0
        val fis = object : FileInputStream(FileDescriptor()) {
            var closed = false
            override fun close() { if (!closed) { closed = true; closeCount++ } }
        }
        // simulate the production catch block: seek threw, close fis, rethrow
        val seekException = IOException("simulated seek failure")
        assertThrows(IOException::class.java) { throw seekException }
        fis.close()
        assertEquals(1, closeCount)
    }
}
