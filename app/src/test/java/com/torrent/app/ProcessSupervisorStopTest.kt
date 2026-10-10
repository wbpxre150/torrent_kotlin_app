package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.Collections

/**
 * Regression: stopping a non-self-logging daemon (the dht_crawler) crashed the
 * app — Process.destroy() closes the child's stdout while the drain loop is in
 * readLine(), and the resulting IOException escaped the supervisor coroutine.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessSupervisorStopTest {

    private fun chattySupervisor(): Pair<ProcessSupervisor, File> {
        val tmp = Files.createTempDirectory("pssup-stop").toFile()
        val bin = File(tmp, "chatty.sh")
        bin.writeText("#!/bin/sh\nwhile true; do echo line; sleep 0.01; done\n")
        bin.setExecutable(true)
        val sup = ProcessSupervisor(
            label = "chatty",
            binaryName = "chatty.sh",
            nativeLibraryDir = tmp.absolutePath,
            workDir = File(tmp, "work"),
            logDir = File(tmp, "logs"),
            selfLogging = false,
            graceMs = 1000,
        )
        return sup to tmp
    }

    private fun awaitState(sup: ProcessSupervisor, state: ProcessState) {
        val deadline = System.currentTimeMillis() + 10_000
        while (sup.status.value.state != state && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(state, sup.status.value.state)
    }

    @Test fun stop_whileDraining_doesNotThrowUncaught() {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            val (sup, _) = chattySupervisor()
            sup.start()
            awaitState(sup, ProcessState.RUNNING)
            Thread.sleep(200) // let the drain loop sit in readLine()
            sup.stop()
            assertEquals(ProcessState.STOPPED, sup.status.value.state)
            Thread.sleep(500)
            assertTrue("uncaught exceptions after stop(): $uncaught", uncaught.isEmpty())
            assertEquals("must stay stopped (no auto-restart)", ProcessState.STOPPED, sup.status.value.state)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun stopThenStart_runsExactlyOneLoop() {
        val (sup, _) = chattySupervisor()
        sup.start()
        awaitState(sup, ProcessState.RUNNING)
        val firstRestarts = sup.status.value.restartCount

        sup.stop()
        sup.start()
        awaitState(sup, ProcessState.RUNNING)
        Thread.sleep(1500) // a stale loop would relaunch within its 1 s backoff
        assertEquals(
            "a stale run loop must not launch an extra child",
            firstRestarts + 1, sup.status.value.restartCount)

        sup.stop()
        Thread.sleep(1500)
        assertEquals(ProcessState.STOPPED, sup.status.value.state)
    }
}
