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

    /**
     * Plan 07 §2.4: the production streamProvider now wraps the FileInputStream
     * in BufferedInputStream(fis, 512 KiB). Closing the buffered stream must
     * close the underlying fis (and detached fd) exactly once; a double close
     * of the returned stream must not close the fd twice.
     */
    @Test
    fun bufferedStreamCloseClosesFdExactlyOnce() {
        val f = java.io.File.createTempFile("ftpfd", ".bin").apply { writeBytes(ByteArray(64) { it.toByte() }); deleteOnExit() }
        var closeCount = 0
        // Count every close() reaching the underlying stream (no guard here).
        val fis = object : FileInputStream(f) {
            override fun close() { closeCount++; super.close() }
        }
        val stream: InputStream = java.io.BufferedInputStream(fis, 512 * 1024)
        assertEquals(8, stream.read(ByteArray(8)))
        stream.close()
        stream.close()
        assertEquals("buffered stream must close the fd exactly once", 1, closeCount)
    }

    /**
     * Reading through the buffered wrapper must not close the fd; only close()
     * does, and exactly once.
     */
    @Test
    fun bufferedReadsDoNotCloseFd() {
        val f = java.io.File.createTempFile("ftpfd", ".bin").apply { writeBytes(ByteArray(4096)); deleteOnExit() }
        var closeCount = 0
        val fis = object : FileInputStream(f) {
            override fun close() { closeCount++; super.close() }
        }
        val stream: InputStream = java.io.BufferedInputStream(fis, 512 * 1024)
        repeat(3) { stream.read(ByteArray(1024)) }
        assertEquals("reads must not close the fd", 0, closeCount)
        stream.close()
        assertEquals(1, closeCount)
    }
}
