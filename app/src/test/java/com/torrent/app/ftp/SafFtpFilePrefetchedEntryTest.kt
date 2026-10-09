package com.torrent.app.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class SafFtpFilePrefetchedEntryTest {

    private class CountingSource(
        val dirs: Set<String>,
        val files: Map<String, Long>,
    ) : FtpFileSource {
        val statCalls = AtomicInteger(0)

        override fun list(relPath: String): List<FtpFileSource.Entry> {
            if (relPath !in dirs) return emptyList()
            val children = mutableListOf<FtpFileSource.Entry>()
            files.keys.filter { it.startsWith(if (relPath.isEmpty()) "" else "$relPath/") }
                .forEach { rp ->
                    val parent = rp.substringBeforeLast('/', "")
                    if (parent == relPath)
                        children.add(FtpFileSource.Entry(rp.substringAfterLast('/'), false, files.getValue(rp), 7L))
                }
            dirs.filter { it != relPath && it.startsWith(if (relPath.isEmpty()) "" else "$relPath/") }
                .forEach { dp ->
                    val parent = dp.substringBeforeLast('/', "")
                    if (parent == relPath && dp.isNotEmpty())
                        children.add(FtpFileSource.Entry(dp.substringAfterLast('/'), true, 0L, 7L))
                }
            return children
        }

        override fun stat(relPath: String): FtpFileSource.Entry? {
            statCalls.incrementAndGet()
            return when {
                relPath in dirs -> FtpFileSource.Entry(relPath.substringAfterLast('/'), true, 0L, 7L)
                files.containsKey(relPath) ->
                    FtpFileSource.Entry(relPath.substringAfterLast('/'), false, files.getValue(relPath), 7L)
                else -> null
            }
        }

        override fun delete(relPath: String): Boolean = false
    }

    private val noStream: (String, Long) -> InputStream =
        { _, _ -> InputStream.nullInputStream() }

    @Test fun listFilesUsesPrefetchedEntries_noPerChildStat() {
        val src = CountingSource(
            dirs = setOf("", "shows"),
            files = mapOf("a.mkv" to 1L, "b.mkv" to 2L, "shows/s1e1.mkv" to 3L),
        )
        val root = SafFtpFile("/", "", src, noStream)
        val kids = root.listFiles()!!

        // Exactly one stat: the root's isDirectory() check inside listFiles().
        assertEquals(1, src.statCalls.get())

        // Metadata access on every child must not trigger any additional stat:
        // the Entry prefetched from source.list() is carried into the children.
        kids.forEach { k ->
            k.getName()
            k.getSize()
            k.getLastModified()
            k.isDirectory()
            k.isFile()
            k.doesExist()
        }
        assertEquals(1, src.statCalls.get())

        // And the prefetched metadata is actually used (not just absent).
        val a = kids.first { it.getName() == "a.mkv" }
        assertEquals(1L, a.getSize())
        assertEquals(7L, a.getLastModified())
        val shows = kids.first { it.getName() == "shows" }
        assertTrue(shows.isDirectory())
    }

    @Test fun statStillLazilyCalledWhenNoPrefetchedEntry() {
        val src = CountingSource(dirs = setOf(""), files = mapOf("a.mkv" to 42L))
        val f = SafFtpFile("/a.mkv", "a.mkv", src, noStream)
        assertEquals(0, src.statCalls.get())
        assertEquals(42L, f.getSize())
        assertEquals(1, src.statCalls.get())
        assertEquals(42L, f.getSize())
        assertEquals(1, src.statCalls.get())   // lazy, computed once
    }
}
