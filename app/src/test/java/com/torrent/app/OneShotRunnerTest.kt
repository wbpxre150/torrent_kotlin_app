package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Exercises [OneShotRunner] against a real child process (a `/bin/sh` script,
 * the same technique as [ProcessSupervisorGraceMsTest]): verifies the exit
 * code is returned, output lines are streamed to the callback (stdout +
 * stderr merged), and a missing binary yields -1.
 */
@RunWith(RobolectricTestRunner::class)
class OneShotRunnerTest {

    @Test fun run_returnsExitCodeAndStreamsLines() {
        val tmp = Files.createTempDirectory("oneshot").toFile()
        val bin = File(tmp, "probe.sh")
        bin.writeText("#!/bin/sh\necho line-one\necho line-two\nexit 3\n")
        bin.setExecutable(true)
        val workDir = File(tmp, "work").apply { mkdirs() }

        val runner = OneShotRunner("probe.sh", tmp.absolutePath)
        val lines = mutableListOf<String>()
        val code = runner.run(emptyList(), workDir) { lines.add(it) }

        assertEquals(3, code)
        assertTrue("stdout lines must be captured", lines.contains("line-one"))
        assertTrue(lines.contains("line-two"))
    }

    @Test fun run_capturesStderrMergedWithStdout() {
        val tmp = Files.createTempDirectory("oneshot-err").toFile()
        val bin = File(tmp, "probe.sh")
        bin.writeText("#!/bin/sh\necho to-stdout\necho to-stderr 1>&2\nexit 0\n")
        bin.setExecutable(true)
        val workDir = File(tmp, "work").apply { mkdirs() }

        val runner = OneShotRunner("probe.sh", tmp.absolutePath)
        val lines = mutableListOf<String>()
        val code = runner.run(emptyList(), workDir) { lines.add(it) }

        assertEquals(0, code)
        assertTrue(lines.contains("to-stdout"))
        assertTrue("stderr is merged into the drained stream", lines.contains("to-stderr"))
    }

    @Test fun run_returnsMinusOne_whenBinaryMissing() {
        val tmp = Files.createTempDirectory("oneshot-missing").toFile()
        val workDir = File(tmp, "work").apply { mkdirs() }
        val runner = OneShotRunner("does-not-exist.so", tmp.absolutePath)
        assertEquals(-1, runner.run(emptyList(), workDir) { })
    }
}
