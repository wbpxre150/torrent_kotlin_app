package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the optional readiness probe of [ProcessSupervisor]: a supervised
 * daemon must stay in [ProcessState.STARTING] after the fork until the injected
 * probe succeeds, and must publish [ProcessState.RUNNING] only then. With no
 * probe (the default) the old "RUNNING immediately after fork" behavior is kept.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessSupervisorReadinessTest {

    /** Long-lived child: stays alive until the supervisor kills it. */
    private val longLived = "#!/bin/sh\nwhile true; do sleep 0.05; done\n"

    /** Short-lived child: lives ~300 ms, then exits on its own. */
    private val shortLived = "#!/bin/sh\nsleep 0.3\n"

    private fun supervisor(
        tmp: File,
        script: String,
        probe: (suspend () -> Boolean)?,
        pollMs: Long = 50,
    ): ProcessSupervisor {
        val name = "fake-daemon.sh"
        val bin = File(tmp, name)
        bin.writeText(script)
        bin.setExecutable(true)
        return ProcessSupervisor(
            label = "ready",
            binaryName = name,
            nativeLibraryDir = tmp.absolutePath,
            workDir = File(tmp, "work").apply { mkdirs() },
            logDir = File(tmp, "logs").apply { mkdirs() },
            graceMs = 500,
            readinessProbe = probe,
            readinessPollMs = pollMs,
        )
    }

    /**
     * True once the supervisor has published the fork (pid / startTimeMs /
     * restartCount are all written at that single point).
     *
     * [ProcessStatus.pid] is always null in these unit tests: NativeProcess.pidOf()
     * reads `ProcessImpl.pid` reflectively and the JVM module system blocks that
     * access (java.base is not opened to the test classloader), so the launch
     * timestamp — published by the very same `copy(...)` — is the fork marker here.
     */
    private fun ProcessStatus.forked(): Boolean = startTimeMs != null

    /** Polls [ProcessSupervisor.status] until [predicate] holds; fails fast with the last status. */
    private fun awaitStatus(
        sup: ProcessSupervisor,
        timeoutMs: Long = 20_000,
        what: String,
        predicate: (ProcessStatus) -> Boolean,
    ): ProcessStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = sup.status.value
            if (predicate(s)) return s
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what; last=${sup.status.value}")
    }

    /**
     * Records every status it observes (5 ms sampling) on a daemon thread, so a
     * test can assert about *transient* states that a poll-until-X loop would
     * otherwise skip over.
     */
    private class StatusSampler(private val sup: ProcessSupervisor) {
        private val seen = Collections.synchronizedList(mutableListOf<ProcessStatus>())

        @Volatile
        private var running = true

        private val thread = Thread {
            while (running) {
                seen.add(sup.status.value)
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }

        fun start(): StatusSampler {
            thread.isDaemon = true
            thread.start()
            return this
        }

        /** Stops sampling and returns everything observed so far. Safe to call twice. */
        fun stop(): List<ProcessStatus> {
            running = false
            thread.interrupt()
            thread.join(2000)
            synchronized(seen) { return seen.toList() }
        }
    }

    // 1. Probe fails twice, then succeeds: STARTING (with pid) first, RUNNING after.
    @Test
    fun probeFailsThenSucceeds_staysStartingUntilReady() {
        val tmp = Files.createTempDirectory("pssup-ready-ok").toFile()
        val attempts = AtomicInteger(0)
        val sup = supervisor(tmp, longLived, probe = { attempts.incrementAndGet() > 2 })
        val sampler = StatusSampler(sup).start()
        try {
            sup.start()
            val running = awaitStatus(sup, what = "RUNNING") { it.state == ProcessState.RUNNING }
            assertNotNull("RUNNING must carry the launch timestamp of the fork", running.startTimeMs)
            assertEquals(
                "RUNNING must be published by the probe, not a timeout",
                ProcessState.RUNNING, sup.status.value.state)
            assertTrue(
                "probe should have run at least 3 times (2 failures + success), ran ${attempts.get()}",
                attempts.get() >= 3)

            val observed = sampler.stop()
            assertTrue(
                "must observe the forked launch in STARTING before RUNNING; saw=" +
                    observed.map { it.state to it.startTimeMs },
                observed.any { it.state == ProcessState.STARTING && it.forked() })
            // RUNNING itself is already asserted by awaitStatus above; the sampler
            // exists only to prove the forked launch was *visible* in STARTING first.
        } finally {
            sup.stop()
            sampler.stop()
        }
    }

    // 2. Probe never succeeds and the child exits: the relaunch is STARTING, never RUNNING.
    @Test
    fun probeNeverSucceeds_childExits_relaunchStaysStarting() {
        val tmp = Files.createTempDirectory("pssup-ready-exit").toFile()
        val sup = supervisor(tmp, shortLived, probe = { false })
        val sampler = StatusSampler(sup).start()
        try {
            sup.start()
            val relaunched = awaitStatus(sup, what = "relaunch forked in STARTING") {
                it.restartCount >= 1 && it.state == ProcessState.STARTING && it.forked()
            }
            assertEquals("a relaunch after an exit must stay STARTING", ProcessState.STARTING, relaunched.state)
            assertNotNull("the relaunched child must carry a launch timestamp", relaunched.startTimeMs)
            assertTrue(
                "a never-ready launch must never be reported RUNNING",
                sampler.stop().none { it.state == ProcessState.RUNNING })
        } finally {
            sup.stop()
            sampler.stop()
        }
    }

    // 3. stop() while the probe never answers: STOPPED, and RUNNING never appears.
    @Test
    fun stopWhileStarting_endsStoppedAndNeverRuns() {
        val tmp = Files.createTempDirectory("pssup-ready-stop").toFile()
        val sup = supervisor(tmp, longLived, probe = { false })
        val sampler = StatusSampler(sup).start()
        try {
            sup.start()
            val starting = awaitStatus(sup, what = "forked launch in STARTING") {
                it.state == ProcessState.STARTING && it.forked()
            }
            assertNotNull("the forked child must carry a launch timestamp", starting.startTimeMs)
            Thread.sleep(300) // several failed polls
            assertEquals("must still be STARTING while not ready", ProcessState.STARTING, sup.status.value.state)

            sup.stop()
            assertEquals(ProcessState.STOPPED, sup.status.value.state)
            Thread.sleep(500) // a stale poll/failure must not resurrect a state
            assertEquals("must stay stopped", ProcessState.STOPPED, sup.status.value.state)

            val observed = sampler.stop()
            assertTrue(
                "a late readiness poll must never publish RUNNING after stop(); saw=" +
                    observed.map { it.state }.distinct(),
                observed.none { it.state == ProcessState.RUNNING })
        } finally {
            sup.stop()
            sampler.stop()
        }
    }

    // 4. Regression guard: no probe configured → RUNNING as soon as the child is forked.
    @Test
    fun nullProbe_runsImmediatelyAfterFork() {
        val tmp = Files.createTempDirectory("pssup-ready-null").toFile()
        val sup = supervisor(tmp, longLived, probe = null)
        try {
            sup.start()
            val running = awaitStatus(sup, timeoutMs = 10_000, what = "RUNNING") {
                it.state == ProcessState.RUNNING
            }
            assertEquals(ProcessState.RUNNING, running.state)
            assertNotNull("RUNNING must carry the launch timestamp of the fork", running.startTimeMs)
        } finally {
            sup.stop()
        }
    }
}
