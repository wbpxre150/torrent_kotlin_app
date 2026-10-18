package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression guard for the lost-update race on [ProcessSupervisor]'s shared status
 * flow. `_status.value = _status.value.copy(...)` is a read-modify-write, not an
 * atomic update: an unguarded writer (the log-tail push runs every ~300 ms while a
 * daemon spams its startup log) could read a stale snapshot and land its write
 * after a guarded publish, silently reverting it (a ready daemon stuck in
 * [ProcessState.STARTING] forever, or a live launch reported as STOPPED).
 *
 * The exact device interleaving (log push vs. readiness publish) is not
 * reproducible deterministically in a unit test — it needs a stalled writer — but
 * the property it broke is: concurrent read-modify-write writers must not lose
 * each other's updates. That is asserted here against the single guarded writer,
 * [ProcessSupervisor.updateStatus].
 */
class ProcessSupervisorStatusRaceTest {

    private fun supervisor(): ProcessSupervisor {
        val tmp = Files.createTempDirectory("pssup-race").toFile()
        return ProcessSupervisor(
            label = "race",
            binaryName = "fake-daemon.sh",
            nativeLibraryDir = tmp.absolutePath,
            workDir = File(tmp, "work"),
            logDir = File(tmp, "logs"),
        )
    }

    /**
     * Half the workers increment [ProcessStatus.restartCount] (the "state
     * publish" side), half append to [ProcessStatus.lastLogLines] (the "log tail"
     * side). With a guarded read-modify-write both totals must be exact; with an
     * unguarded one the writers overwrite each other's snapshots.
     */
    @Test
    fun concurrentUpdates_doNotLoseUpdates() {
        val sup = supervisor()
        val threads = 8
        val perThread = 250
        val counterWriters = threads / 2
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { idx ->
            Thread {
                gate.await()
                repeat(perThread) {
                    if (idx < counterWriters) {
                        sup.updateStatus { it.copy(restartCount = it.restartCount + 1) }
                    } else {
                        sup.updateStatus { it.copy(lastLogLines = it.lastLogLines + "x") }
                    }
                }
                done.countDown()
            }.apply { isDaemon = true }.start()
        }
        gate.countDown()
        assertTrue("writers did not finish in 30 s", done.await(30, TimeUnit.SECONDS))

        val finalStatus = sup.status.value
        assertEquals(
            "every restartCount increment must survive",
            counterWriters * perThread, finalStatus.restartCount)
        assertEquals(
            "every log-line append must survive",
            (threads - counterWriters) * perThread, finalStatus.lastLogLines.size)
    }

    /**
     * start(), stop() and awaitReadiness() already hold `lifecycleLock` when they
     * publish, so the helper must be re-entrant (a non-reentrant guard would
     * deadlock the supervisor).
     */
    @Test
    fun updateStatus_isReentrant_nestedPublishDoesNotDeadlock() {
        val sup = supervisor()
        var nestedSawStarting = false
        var outerState: ProcessState? = null
        val worker = Thread {
            val out = sup.updateStatus { outer ->
                val nested = sup.updateStatus { it.copy(state = ProcessState.STARTING) }
                nestedSawStarting = nested.state == ProcessState.STARTING
                outer.copy(state = ProcessState.RUNNING)
            }
            outerState = out.state
        }
        worker.isDaemon = true
        worker.start()
        worker.join(10_000)
        assertFalse("nested updateStatus() deadlocked — the guard is not reentrant", worker.isAlive)
        assertTrue("the nested publish must be visible", nestedSawStarting)
        assertEquals(ProcessState.RUNNING, outerState)
        assertEquals(ProcessState.RUNNING, sup.status.value.state)
    }

    /**
     * The helper must publish-merge, not overwrite: fields the transform ignores
     * (pid, logs, state) survive a later transition, and the returned value is
     * exactly what a reader of [ProcessSupervisor.status] observes.
     */
    @Test
    fun updateStatus_carriesOverFieldsTheTransformIgnores() {
        val sup = supervisor()
        sup.updateStatus {
            it.copy(state = ProcessState.RUNNING, pid = 4242, lastLogLines = listOf("a", "b"))
        }
        val out = sup.updateStatus { it.copy(restartCount = it.restartCount + 1) }
        assertEquals(ProcessState.RUNNING, out.state)
        assertEquals(4242, out.pid)
        assertEquals(listOf("a", "b"), out.lastLogLines)
        assertEquals(1, out.restartCount)
        assertEquals(out, sup.status.value)
    }
}
