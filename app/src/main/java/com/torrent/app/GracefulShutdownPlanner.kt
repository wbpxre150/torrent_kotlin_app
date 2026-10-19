package com.torrent.app

/**
 * Ordered, run-once planner for a graceful app shutdown (plain Kotlin, no
 * Android deps — see AGENTS.md house style).
 *
 * Stage 1 of the graceful-shutdown work: the user force-stops the app today,
 * which SIGKILLs dht_crawler before it can flush its SQLite batch, save its
 * bloom filter and run the final WAL TRUNCATE checkpoint. [com.torrent.app.DaemonService]
 * therefore builds one planner with its teardown steps declared in shutdown
 * order and runs it exactly once, from whichever path gets there first
 * (the explicit `requestGracefulShutdown()` or a later `onDestroy()`).
 *
 * Guarantees:
 *  - each step runs once, in declared order;
 *  - [run] is idempotent: the first caller wins, every later call is a no-op;
 *  - a throwing step is reported through [onError] and does not abort the
 *    remaining steps — a shutdown must keep releasing locks and stopping
 *    resources even when one collaborator misbehaves;
 *  - [isRequested] / [isCompleted] expose progress (requested = started,
 *    completed = all steps attempted).
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

    /** True once [run] has been entered by any caller. */
    val isRequested: Boolean get() = synchronized(lock) { requestedState }

    /** True once every step has been attempted (or skipped after a failure). */
    val isCompleted: Boolean get() = synchronized(lock) { completedState }

    /** Step names in declared order (diagnostics/tests). */
    fun stepNames(): List<String> = steps.map { it.name }

    /**
     * Runs every step once, in order. Returns true when this call performed the
     * shutdown, false when it had already been requested (idempotent no-op).
     */
    fun run(): Boolean {
        synchronized(lock) {
            if (requestedState) return false
            requestedState = true
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
