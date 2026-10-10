package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Exercises [ProcessSupervisor.stop()] / killChild() against a real child
 * process that ignores SIGTERM, verifying the configurable [ProcessSupervisor]
 * grace period is honored (child survives TERM, is SIGKILLed after graceMs).
 */
@RunWith(RobolectricTestRunner::class)
class ProcessSupervisorGraceMsTest {

    @Test fun stop_termIgnoringChild_killedAfterConfiguredGrace() {
        val tmp = Files.createTempDirectory("pssup-grace").toFile()
        val bin = File(tmp, "fake-daemon.sh")
        bin.writeText("#!/bin/sh\ntrap '' TERM\nwhile true; do sleep 0.2; done\n")
        bin.setExecutable(true)
        val workDir = File(tmp, "work").apply { mkdirs() }
        val logDir = File(tmp, "logs").apply { mkdirs() }

        val graceMs = 500L
        val sup = ProcessSupervisor(
            label = "fake",
            binaryName = "fake-daemon.sh",
            nativeLibraryDir = tmp.absolutePath,
            workDir = workDir,
            logDir = logDir,
            graceMs = graceMs,
        )
        sup.start()

        val deadline = System.currentTimeMillis() + 10_000
        while (sup.status.value.state != ProcessState.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("child must reach RUNNING", ProcessState.RUNNING, sup.status.value.state)

        val t0 = System.currentTimeMillis()
        sup.stop()
        val elapsed = System.currentTimeMillis() - t0

        assertTrue(
            "stop() returned before the grace window elapsed (${elapsed}ms < ${graceMs}ms)",
            elapsed >= graceMs - 100
        )
        assertTrue(
            "stop() must SIGKILL promptly after the grace window (took ${elapsed}ms)",
            elapsed < 10_000
        )
        assertEquals(ProcessState.STOPPED, sup.status.value.state)
    }
}
