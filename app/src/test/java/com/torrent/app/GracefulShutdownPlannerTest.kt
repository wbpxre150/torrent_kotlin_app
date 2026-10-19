package com.torrent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GracefulShutdownPlannerTest {

    private fun planner(
        log: MutableList<String>,
        onError: (String, Throwable) -> Unit = { _, _ -> },
        names: List<String> = listOf("jobs", "crawler", "ftp", "transmission", "locks"),
    ) = GracefulShutdownPlanner(
        steps = names.map { name -> GracefulShutdownPlanner.Step(name) { log.add(name) } },
        onError = onError,
    )

    @Test fun runsEveryStepInDeclaredOrder() {
        val log = mutableListOf<String>()
        planner(log).run()
        assertEquals(listOf("jobs", "crawler", "ftp", "transmission", "locks"), log)
    }

    @Test fun secondRunWhileInFlightIsNoOp() {
        val log = mutableListOf<String>()
        lateinit var p: GracefulShutdownPlanner
        var nested: Boolean? = null
        p = GracefulShutdownPlanner(
            steps = listOf(
                GracefulShutdownPlanner.Step("outer-1") { log.add("outer-1") },
                GracefulShutdownPlanner.Step("re-enter") {
                    // Overlapping request (e.g. onDestroy during a graceful run).
                    nested = p.run()
                    log.add("re-enter")
                },
                GracefulShutdownPlanner.Step("outer-2") { log.add("outer-2") },
            ),
        )
        assertTrue(p.run())
        assertEquals(false, nested)
        // The nested call executed nothing: each step ran exactly once.
        assertEquals(listOf("outer-1", "re-enter", "outer-2"), log)
    }

    @Test fun completedCycleIsReArmedAndRunsEveryStepAgain() {
        val log = mutableListOf<String>()
        val p = planner(log)
        val expected = listOf("jobs", "crawler", "ftp", "transmission", "locks")
        assertTrue(p.run())
        assertTrue(p.isCompleted)
        assertEquals(expected, log.toList())
        // Re-armed after completion: the second cycle runs every step again.
        assertTrue(p.run())
        assertTrue(p.isCompleted)
        assertEquals(expected + expected, log)
    }

    @Test fun reArmedCycleReportsInFlightAgain() {
        val log = mutableListOf<String>()
        var inFlightSeen = false
        lateinit var p: GracefulShutdownPlanner
        p = GracefulShutdownPlanner(
            steps = listOf(
                GracefulShutdownPlanner.Step("probe") {
                    if (p.isRequested && !p.isCompleted) {
                        log.add("in-flight")
                        inFlightSeen = true
                    }
                },
            ),
        )
        p.run()
        assertTrue(p.isCompleted)
        inFlightSeen = false
        assertTrue(p.run())
        assertTrue(inFlightSeen)
        assertTrue(p.isCompleted)
        assertEquals(listOf("in-flight", "in-flight"), log)
    }

    @Test fun exposesRequestedAndCompletedState() {
        val p = planner(mutableListOf())
        assertFalse(p.isRequested)
        assertFalse(p.isCompleted)
        p.run()
        assertTrue(p.isRequested)
        assertTrue(p.isCompleted)
    }

    @Test fun requestedIsSetWhileStepsAreStillRunning() {
        var requestedDuringStep = false
        lateinit var p: GracefulShutdownPlanner
        p = GracefulShutdownPlanner(
            steps = listOf(GracefulShutdownPlanner.Step("first") {
                requestedDuringStep = p.isRequested && !p.isCompleted
            }),
        )
        p.run()
        assertTrue(requestedDuringStep)
        assertTrue(p.isCompleted)
    }

    @Test fun failingStepIsReportedAndRemainingStepsStillRun() {
        val log = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val p = GracefulShutdownPlanner(
            steps = listOf(
                GracefulShutdownPlanner.Step("ok-1") { log.add("ok-1") },
                GracefulShutdownPlanner.Step("boom") { throw IllegalStateException("nope") },
                GracefulShutdownPlanner.Step("ok-2") { log.add("ok-2") },
            ),
            onError = { name, _ -> errors.add(name) },
        )
        assertTrue(p.run())
        assertEquals(listOf("ok-1", "ok-2"), log)
        assertEquals(listOf("boom"), errors)
        assertTrue(p.isCompleted)
    }

    @Test fun stepNamesPreserveDeclaredOrder() {
        val p = planner(mutableListOf())
        assertEquals(listOf("jobs", "crawler", "ftp", "transmission", "locks"), p.stepNames())
    }
}
