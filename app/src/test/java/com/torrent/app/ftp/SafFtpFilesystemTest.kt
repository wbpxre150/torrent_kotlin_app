package com.torrent.app.ftp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class SafFtpFilesystemTest {

    private class FakeSource(
        val dirs: Set<String> = emptySet(),
        val files: Map<String, Long> = emptyMap(),
        val mtimes: Map<String, Long> = emptyMap(),
    ) : FtpFileSource {
        override fun list(relPath: String): List<FtpFileSource.Entry> {
            if (relPath !in dirs) return emptyList()
            val children = mutableListOf<FtpFileSource.Entry>()
            files.keys.filter { it.startsWith(if (relPath.isEmpty()) "" else "$relPath/") }
                .forEach { rp ->
                    val name = rp.substringAfterLast('/')
                    val parent = rp.substringBeforeLast('/', "")
                    if (parent == relPath) children.add(FtpFileSource.Entry(name, false, files.getValue(rp), mtimes[rp] ?: 0L))
                }
            dirs.filter { it != relPath && it.startsWith(if (relPath.isEmpty()) "" else "$relPath/") }
                .forEach { dp ->
                    val name = dp.substringAfterLast('/')
                    val parent = dp.substringBeforeLast('/', "")
                    if (parent == relPath && name.isNotEmpty()) children.add(FtpFileSource.Entry(name, true, 0L, mtimes[dp] ?: 0L))
                }
            return children
        }
        override fun stat(relPath: String): FtpFileSource.Entry? = when {
            relPath in dirs -> FtpFileSource.Entry(relPath.substringAfterLast('/'), true, 0L, mtimes[relPath] ?: 0L)
            files.containsKey(relPath) -> FtpFileSource.Entry(relPath.substringAfterLast('/'), false, files.getValue(relPath), mtimes[relPath] ?: 0L)
            else -> null
        }
        override fun delete(relPath: String): Boolean = false
    }

    private fun streamProvider(bytes: ByteArray): (String, Long) -> InputStream = { _, offset ->
        ByteArrayInputStream(if (offset <= 0) bytes else bytes.copyOfRange(offset.toInt(), bytes.size))
    }

    @Test fun root_isDirectoryWithZeroSize() {
        val f = SafFtpFile("/", "", FakeSource(dirs = setOf("")), streamProvider(byteArrayOf(0)))
        assertTrue(f.isDirectory())
        assertFalse(f.isFile())
        assertTrue(f.doesExist())
        assertEquals(0L, f.getSize())
        assertEquals("/", f.getName())
        assertEquals("/", f.getAbsolutePath())
    }

    @Test fun file_hasSizeAndIsReadableNotWritable() {
        val src = FakeSource(dirs = setOf(""), files = mapOf("movie.mkv" to 5_000_000_000L))
        val f = SafFtpFile("/movie.mkv", "movie.mkv", src, streamProvider(byteArrayOf(1, 2, 3)))
        assertTrue(f.isFile())
        assertFalse(f.isDirectory())
        assertTrue(f.isReadable())
        assertFalse(f.isWritable())
        assertTrue(f.isRemovable())
        assertEquals(5_000_000_000L, f.getSize())
        assertEquals("movie.mkv", f.getName())
    }

    @Test fun createOutputStream_throws() {
        val f = SafFtpFile("/x", "x", FakeSource(), streamProvider(byteArrayOf()))
        assertThrows(java.io.IOException::class.java) { f.createOutputStream(0L) }
    }

    @Test fun createInputStream_invokesProviderWithOffset() {
        var capturedOffset = -1L
        val prov: (String, Long) -> InputStream = { _, off -> capturedOffset = off; ByteArrayInputStream(byteArrayOf()) }
        val f = SafFtpFile("/x", "x", FakeSource(files = mapOf("x" to 1L)), prov)
        f.createInputStream(123L).close()
        assertEquals(123L, capturedOffset)
    }

    @Test fun listFiles_sortedAlphabeticallyAndNullForFile() {
        val src = FakeSource(
            dirs = setOf("", "shows"),
            files = mapOf("b.mkv" to 2L, "a.mkv" to 1L, "shows/s1e1.mkv" to 3L),
        )
        val root = SafFtpFile("/", "", src, streamProvider(byteArrayOf()))
        val kids = root.listFiles()!!
        assertEquals(listOf("a.mkv", "b.mkv", "shows"), kids.map { it.getName() })
        assertTrue(kids[2].isDirectory())
        assertNull(SafFtpFile("/a.mkv", "a.mkv", src, streamProvider(byteArrayOf())).listFiles())
    }

    @Test fun listFiles_missingDir_returnsNull() {
        val f = SafFtpFile("/nope", "nope", FakeSource(), streamProvider(byteArrayOf()))
        assertNull(f.listFiles())
    }

    @Test fun homeAndWorkingAreRoot() {
        val v = SafFtpFileSystemView(FakeSource(dirs = setOf("")), streamProvider(byteArrayOf()))
        assertEquals("/", v.homeDirectory.absolutePath)
        assertEquals("/", v.workingDirectory.absolutePath)
        assertTrue(v.isRandomAccessible)
    }

    @Test fun getFile_absoluteAndRelative() {
        val src = FakeSource(dirs = setOf("", "d"), files = mapOf("d/f.mkv" to 9L))
        val v = SafFtpFileSystemView(src, streamProvider(byteArrayOf()))
        assertEquals("f.mkv", v.getFile("/d/f.mkv").name)
        assertTrue(v.changeWorkingDirectory("/d"))
        assertEquals("f.mkv", v.getFile("f.mkv").name)
    }

    @Test fun changeWorkingDirectory_relativeDotDotAndAbsolute() {
        val src = FakeSource(dirs = setOf("", "a", "a/b"))
        val v = SafFtpFileSystemView(src, streamProvider(byteArrayOf()))
        assertTrue(v.changeWorkingDirectory("/a"))
        assertEquals("/a", v.workingDirectory.absolutePath)
        assertTrue(v.changeWorkingDirectory("b"))
        assertEquals("/a/b", v.workingDirectory.absolutePath)
        assertTrue(v.changeWorkingDirectory(".."))
        assertEquals("/a", v.workingDirectory.absolutePath)
        assertTrue(v.changeWorkingDirectory("/"))
        assertEquals("/", v.workingDirectory.absolutePath)
    }

    @Test fun changeWorkingDirectory_missingReturnsFalse() {
        val v = SafFtpFileSystemView(FakeSource(dirs = setOf("")), streamProvider(byteArrayOf()))
        assertFalse(v.changeWorkingDirectory("/nope"))
        assertEquals("/", v.workingDirectory.absolutePath)
    }

    @Test fun changeWorkingDirectory_toFileReturnsFalse() {
        val src = FakeSource(dirs = setOf(""), files = mapOf("f" to 1L))
        val v = SafFtpFileSystemView(src, streamProvider(byteArrayOf()))
        assertFalse(v.changeWorkingDirectory("/f"))
    }

    @Test fun dotDotAtRootStaysRoot() {
        val v = SafFtpFileSystemView(FakeSource(dirs = setOf("")), streamProvider(byteArrayOf()))
        assertTrue(v.changeWorkingDirectory(".."))
        assertEquals("/", v.workingDirectory.absolutePath)
    }

    @Test fun getLastModified_reportsEntryMtime() {
        val src = FakeSource(
            dirs = setOf(""),
            files = mapOf("movie.mkv" to 5_000_000_000L),
            mtimes = mapOf("movie.mkv" to 1_700_000_000_000L),
        )
        val f = SafFtpFile("/movie.mkv", "movie.mkv", src, streamProvider(byteArrayOf()))
        assertEquals(1_700_000_000_000L, f.getLastModified())
    }

    @Test fun getLastModified_zeroWhenMtimeMissing() {
        val src = FakeSource(dirs = setOf(""), files = mapOf("x" to 1L))
        val f = SafFtpFile("/x", "x", src, streamProvider(byteArrayOf()))
        assertEquals(0L, f.getLastModified())
    }
}