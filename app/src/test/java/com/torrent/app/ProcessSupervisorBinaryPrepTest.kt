package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProcessSupervisorBinaryPrepTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newSupervisor(sourceDir: File, workDir: File): ProcessSupervisor =
        ProcessSupervisor(
            label = "test",
            binaryName = "libprobe.so",
            args = emptyList(),
            nativeLibraryDir = sourceDir.absolutePath,
            workDir = workDir,
            logDir = workDir,
            ldLibraryPath = null,
            extraEnv = emptyMap(),
            selfLogging = false,
            pipelineMaxLines = 10,
        )

    @Test fun prepareBinary_copiesSourceToWorkDir_whenStagedMissing() {
        val sourceDir = tmp.newFolder("lib")
        val workDir = tmp.newFolder("daemons")
        val source = File(sourceDir, "libprobe.so").apply {
            writeBytes(ByteArray(256) { it.toByte() })
            setExecutable(true, false)
        }

        val sup = newSupervisor(sourceDir, workDir)
        val staged = sup.prepareBinary()

        assertNotNull("prepareBinary must return a staged file", staged)
        assertEquals(workDir, staged?.parentFile)
        assertTrue("staged copy must exist", staged!!.exists())
        assertEquals("staged copy length must match source", source.length(), staged.length())
        assertTrue("staged copy must be executable", staged.canExecute())
    }

    @Test fun prepareBinary_reusesStaged_whenLengthMatches() {
        val sourceDir = tmp.newFolder("lib")
        val workDir = tmp.newFolder("daemons")
        val source = File(sourceDir, "libprobe.so").apply {
            writeBytes(ByteArray(128) { it.toByte() })
            setExecutable(true, false)
        }

        val sup = newSupervisor(sourceDir, workDir)
        val first = sup.prepareBinary()!!
        val firstMtime = first.lastModified()
        first.setLastModified(firstMtime - 60_000)

        val second = sup.prepareBinary()!!
        assertEquals("same length must not trigger re-copy", 128, second.length().toInt())
        assertEquals("staged file must not be rewritten when length matches",
            firstMtime - 60_000, second.lastModified())
    }

    @Test fun prepareBinary_recopies_whenLengthDiffers() {
        val sourceDir = tmp.newFolder("lib")
        val workDir = tmp.newFolder("daemons")
        val source = File(sourceDir, "libprobe.so").apply {
            writeBytes(ByteArray(64) { it.toByte() })
            setExecutable(true, false)
        }

        val sup = newSupervisor(sourceDir, workDir)
        val stale = File(workDir, "libprobe.so").apply {
            writeBytes(ByteArray(32))
            setLastModified(0L)
        }

        val staged = sup.prepareBinary()!!
        assertEquals("stale copy must be replaced with source length",
            source.length(), staged.length())
        assertTrue("staged copy must be executable after re-copy", staged.canExecute())
    }

    @Test fun prepareBinary_returnsNull_whenSourceMissing() {
        val sourceDir = tmp.newFolder("lib")
        val workDir = tmp.newFolder("daemons")

        val sup = newSupervisor(sourceDir, workDir)
        val staged = sup.prepareBinary()
        assertNull("must return null when source binary does not exist", staged)
    }
}
