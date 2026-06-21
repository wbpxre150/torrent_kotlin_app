package com.torrent.app.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class SafFtpFileDeleteTest {

    private class FakeSource : FtpFileSource {
        var deletedPath: String? = null
        var deleteResult = true

        override fun list(relPath: String): List<FtpFileSource.Entry> = emptyList()
        override fun stat(relPath: String): FtpFileSource.Entry? = null
        override fun delete(relPath: String): Boolean {
            deletedPath = relPath
            return deleteResult
        }
    }

    private val noOpStream: (String, Long) -> InputStream =
        { _, _ -> InputStream.nullInputStream() }

    @Test fun isRemovable_falseForRoot() {
        val f = SafFtpFile("/", "", FakeSource(), noOpStream)
        assertFalse(f.isRemovable)
    }

    @Test fun isRemovable_trueForNonRoot() {
        val f = SafFtpFile("/movie.mkv", "movie.mkv", FakeSource(), noOpStream)
        assertTrue(f.isRemovable)
    }

    @Test fun delete_delegatesToSourceWithPath() {
        val src = FakeSource()
        val f = SafFtpFile("/dir/movie.mkv", "dir/movie.mkv", src, noOpStream)
        assertTrue(f.delete())
        assertEquals("dir/movie.mkv", src.deletedPath)
    }

    @Test fun delete_returnsFalseWhenSourceReturnsFalse() {
        val src = FakeSource().apply { deleteResult = false }
        val f = SafFtpFile("/x", "x", src, noOpStream)
        assertFalse(f.delete())
    }

    @Test fun delete_rootDelegatesToSourceWithEmptyPath() {
        val src = FakeSource()
        val f = SafFtpFile("/", "", src, noOpStream)
        f.delete()
        assertEquals("", src.deletedPath)
    }
}