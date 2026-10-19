package com.torrent.app

/**
 * Ordered, one-at-a-time planner for a graceful app shutdown (plain Kotlin, no
 * Android deps — see AGENTS.md house style).
 *
 * Stage 1 of the graceful-shutdown work: the user force-stops the app today,
 * which SIGKILLs dht_crawler before it can flush its SQLite batch, save its
 * bloom filter and run the final WAL TRUNCATE checkpoint. [com.torrent.app.DaemonService]
 * therefore builds one planner with its teardown steps declared in shutdown
 * order and runs it from whichever path gets there first (the explicit
 * `requestGracefulShutdown()` or a later `onDestroy()`).
 *
 * The service instance can outlive a completed teardown (the activity keeps it
 * bound, so `stopSelf()` does not destroy it) and be asked to shut the daemons
 * down again later. The planner is therefore **one at a time; re-runnable after
 * a completed cycle**: a call made while a run is IN FLIGHT is a no-op, but once
 * a run has COMPLETED the next call re-arms the planner and runs every step again.
 *
 * Guarantees:
 *  - each step runs once per cycle, in declared order;
 *  - [run] never overlaps itself: an in-flight cycle rejects a second caller,
 *    while a completed cycle can be run again;
 *  - a throwing step is reported through [onError] and does not abort the
 *    remaining steps — a shutdown must keep releasing locks and stopping
 *    resources even when one collaborator misbehaves;
 *  - [isRequested] / [isCompleted] expose progress: requested = a cycle has been
 *    started, completed = its steps have all been attempted, so a run is in
 *    flight while `isRequested && !isCompleted`.
 */
class GracefulShutdownPlanner(
    private val steps: List<Step>,
    private val onError: (step: String, cause: Throwable) -> Unit = { _, _ -> },
) {

    /** One named teardown step; [action] must be idempotent-friendly. */
    class Step(val name: String, val action: () -> Unit)

    private val lock = Any()
    private var requestedState = false
    private var completedState = false

    /** True once [run] has been entered (re-armed by the next cycle after completion). */
    val isRequested: Boolean get() = synchronized(lock) { requestedState }

    /** True once every step of the current cycle has been attempted (or skipped after a failure). */
    val isCompleted: Boolean get() = synchronized(lock) { completedState }

    /** Step names in declared order (diagnostics/tests). */
    fun stepNames(): List<String> = steps.map { it.name }

    /**
     * Runs every step once, in order. Returns true when this call performed a
     * teardown cycle, false when one was already IN FLIGHT (no-op). A completed
     * cycle does not block the next: the planner re-arms itself.
     */
    fun run(): Boolean {
        synchronized(lock) {
            // In flight: exactly one cycle may execute at a time. A cycle that
            // already COMPLETED is re-armed instead — the service instance
            // survives a graceful stop, so a second shutdown cycle must really
            // tear the (restarted) daemons down again.
            if (requestedState && !completedState) return false
            requestedState = true
            completedState = false
        }
        try {
            for (step in steps) {
                try {
                    step.action()
                } catch (e: Exception) {
                    onError(step.name, e)
                }
            }
        } finally {
            synchronized(lock) { completedState = true }
        }
        return true
    }
}
