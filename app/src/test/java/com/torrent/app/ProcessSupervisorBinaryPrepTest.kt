package com.torrent.app

import org.junit.Assert.assertEquals
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

    @Test fun resolveBinary_returnsSource_whenExecutable() {
        val sourceDir = tmp.newFolder("lib")
        val workDir = tmp.newFolder("daemons")
        val source = File(sourceDir, "libprobe.so").apply {
            writeBytes(ByteArray(256) { it.toByte() })
            setExecutable(true, false)
        }

        val sup = newSupervisor(sourceDir, workDir)
        val resolved = sup.resolveBinary()

        assertTrue("resolveBinary must return the nativeLibraryDir binary", resolved != null)
        assertEquals("must resolve in place from nativeLibraryDir, not staged",
            source.absolutePath, resolved!!.absolutePath)
    }

    @Test fun resolveBinary_makesBinaryExecutable_whenBitNotSet() {
        val sourceDir = tmp.newFolder("lib")
        val workDir = tmp.newFolder("daemons")
        File(sourceDir, "libprobe.so").apply {
            writeBytes(ByteArray(64))
            // intentionally not marked executable
        }

        val sup = newSupervisor(sourceDir, workDir)
        val resolved = sup.resolveBinary()

        assertTrue("resolveBinary must set the exec bit when missing", resolved != null)
        assertTrue("binary must be executable after resolveBinary", resolved!!.canExecute())
    }

    @Test fun resolveBinary_returnsNull_whenSourceMissing() {
        val sourceDir = tmp.newFolder("lib")
        val workDir = tmp.newFolder("daemons")

        val sup = newSupervisor(sourceDir, workDir)
        val resolved = sup.resolveBinary()

        assertNull("must return null when source binary does not exist", resolved)
    }

    @Test fun resolveBinary_doesNotStageIntoWorkDir() {
        val sourceDir = tmp.newFolder("lib")
        val workDir = tmp.newFolder("daemons")
        File(sourceDir, "libprobe.so").apply {
            writeBytes(ByteArray(128) { it.toByte() })
            setExecutable(true, false)
        }

        val sup = newSupervisor(sourceDir, workDir)
        sup.resolveBinary()

        val staged = File(workDir, "libprobe.so")
        assertTrue("no copy must be created in workDir", !staged.exists())
    }
}
