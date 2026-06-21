package com.torrent.app.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class LiteralPathListingCommandTest {

    private class FakeSource(
        val dirs: Set<String> = emptySet(),
        val files: Map<String, Long> = emptyMap(),
    ) : FtpFileSource {
        override fun list(relPath: String): List<FtpFileSource.Entry> = emptyList()
        override fun stat(relPath: String): FtpFileSource.Entry? = when {
            relPath in dirs -> FtpFileSource.Entry(relPath.substringAfterLast('/'), true, 0L)
            files.containsKey(relPath) -> FtpFileSource.Entry(relPath.substringAfterLast('/'), false, files.getValue(relPath))
            else -> null
        }
        override fun delete(relPath: String): Boolean = false
    }

    private fun view(dirs: Set<String>, files: Map<String, Long> = emptyMap()): SafFtpFileSystemView {
        val src = FakeSource(dirs, files)
        val sp: (String, Long) -> InputStream = { _, _ -> ByteArrayInputStream(ByteArray(0)) }
        return SafFtpFileSystemView(src, sp)
    }

    private fun decision(dirs: Set<String>, files: Map<String, Long> = emptyMap(), arg: String?): ListingDecision =
        decideListing(view(dirs, files), arg)

    @Test fun splitOptsPath_nullAndEmpty() {
        assertEquals("" to "", splitOptsPath(null))
        assertEquals("" to "", splitOptsPath(""))
        assertEquals("" to "", splitOptsPath("   "))
    }

    @Test fun splitOptsPath_noOptions() {
        assertEquals("" to "Movies", splitOptsPath("Movies"))
        assertEquals("" to "/Some Dir", splitOptsPath("/Some Dir"))
        assertEquals("" to "Matchstick Men (2003) [1080p]", splitOptsPath("Matchstick Men (2003) [1080p]"))
    }

    @Test fun splitOptsPath_leadingOptionsThenPath() {
        assertEquals("a" to "Movies", splitOptsPath("-a Movies"))
        assertEquals("la" to "/Some Dir", splitOptsPath("-la /Some Dir"))
        assertEquals("ab" to "/X Y", splitOptsPath("-a -b /X Y"))
    }

    @Test fun containsGlob_detectsMetachars() {
        assertTrue(containsGlob("a*b"))
        assertTrue(containsGlob("a?b"))
        assertTrue(containsGlob("a[b]"))
        assertTrue(containsGlob("Matchstick Men (2003) [1080p]"))
        assertFalse(containsGlob("Movies"))
        assertFalse(containsGlob("a (b) c"))
        assertFalse(containsGlob(""))
    }

    @Test fun decide_relativeBracketedDir_cwdAndDelegateNoOpts() {
        val d = decision(
            dirs = setOf("Matchstick Men (2003) [1080p]"),
            arg = "Matchstick Men (2003) [1080p]",
        )
        assertTrue(d is ListingDecision.CwdAndDelegate)
        d as ListingDecision.CwdAndDelegate
        assertEquals("Matchstick Men (2003) [1080p]", d.path)
        assertNull(d.optsArg)
    }

    @Test fun decide_absoluteBracketedDir_cwdAndDelegateNoOpts() {
        val d = decision(
            dirs = setOf("Matchstick Men (2003) [1080p]"),
            arg = "/Matchstick Men (2003) [1080p]",
        )
        d as ListingDecision.CwdAndDelegate
        assertEquals("/Matchstick Men (2003) [1080p]", d.path)
        assertNull(d.optsArg)
    }

    @Test fun decide_withOptions_preservesOptionsAsArg() {
        val d = decision(
            dirs = setOf("Some [x] Dir"),
            arg = "-a Some [x] Dir",
        )
        d as ListingDecision.CwdAndDelegate
        assertEquals("Some [x] Dir", d.path)
        assertEquals("-a", d.optsArg)
    }

    @Test fun decide_multipleOptions_concatenated() {
        val d = decision(
            dirs = setOf("[d]"),
            arg = "-la [d]",
        )
        d as ListingDecision.CwdAndDelegate
        assertEquals("-la", d.optsArg)
    }

    @Test fun decide_noGlobChars_delegatesUnchanged() {
        assertEquals(ListingDecision.DelegateUnchanged, decision(dirs = setOf("Movies"), arg = "Movies"))
    }

    @Test fun decide_nullArg_delegatesUnchanged() {
        assertEquals(ListingDecision.DelegateUnchanged, decision(dirs = setOf("Movies"), arg = null))
        assertEquals(ListingDecision.DelegateUnchanged, decision(dirs = setOf("Movies"), arg = ""))
    }

    @Test fun decide_dotAndDotDot_delegatesUnchanged_noGlob() {
        assertEquals(ListingDecision.DelegateUnchanged, decision(dirs = setOf("Movies"), arg = "."))
        assertEquals(ListingDecision.DelegateUnchanged, decision(dirs = setOf("Movies"), arg = ".."))
    }

    @Test fun decide_globPathResolvesToFile_delegatesUnchanged() {
        val d = decision(
            dirs = setOf(""),
            files = mapOf("Bracket [x] File.mkv" to 100L),
            arg = "Bracket [x] File.mkv",
        )
        assertEquals(ListingDecision.DelegateUnchanged, d)
    }

    @Test fun decide_globPathNonexistent_delegatesUnchanged() {
        assertEquals(
            ListingDecision.DelegateUnchanged,
            decision(dirs = setOf("Movies"), arg = "Nope [nope]"),
        )
    }

    @Test fun decide_actuallyCwdIntoBracketedDir_andRestore() {
        val v = view(dirs = setOf("", "Matchstick Men (2003) [1080p]"))
        assertTrue(v.changeWorkingDirectory("Matchstick Men (2003) [1080p]"))
        assertEquals("/Matchstick Men (2003) [1080p]", v.getWorkingDirectory().getAbsolutePath())
        assertTrue(v.changeWorkingDirectory("/"))
        assertEquals("/", v.getWorkingDirectory().getAbsolutePath())
    }

    private class FakeRequest(
        private val arg: String?,
        private val cmd: String = "LIST",
        private val line: String = "LIST $arg",
        private val t: Long = 0L,
    ) : org.apache.ftpserver.ftplet.FtpRequest {
        override fun getRequestLine() = line
        override fun getCommand() = cmd
        override fun getArgument() = arg
        override fun hasArgument() = arg != null
        override fun getReceivedTime() = t
    }

    @Test fun optsOnlyRequest_nullOpts_reportsNoArgument() {
        val inner = FakeRequest(arg = "Matchstick Men (2003) [1080p]")
        val w = OptsOnlyRequest(inner, null)
        assertNull(w.getArgument())
        assertFalse(w.hasArgument())
        assertEquals("LIST", w.getCommand())
        assertEquals("LIST Matchstick Men (2003) [1080p]", w.getRequestLine())
    }

    @Test fun optsOnlyRequest_optsArg_reportsArgument() {
        val inner = FakeRequest(arg = "-a Matchstick Men (2003) [1080p]")
        val w = OptsOnlyRequest(inner, "-a")
        assertEquals("-a", w.getArgument())
        assertTrue(w.hasArgument())
        assertEquals("LIST", w.getCommand())
    }
}