package com.torrent.app

/**
 * Decides whether/when the app process may be killed after a shutdown request
 * (plain Kotlin, no Android deps — see AGENTS.md house style).
 *
 * The Shutdown control stops every daemon **and then** closes the app. Killing
 * the process up front would SIGKILL dht_crawler before it can flush its SQLite
 * batch, save its bloom filter and run the final WAL TRUNCATE checkpoint, so the
 * exit may only happen once an ordered graceful teardown ([GracefulShutdownPlanner])
 * has actually completed. This gate holds that decision:
 *
 * Guarantees:
 *  - [requestExit] never exits by itself: it only records the intent, so the
 *    caller can go on to start (or join) the teardown cycle;
 *  - [onShutdownCycleCompleted] exits only when an exit was requested and a
 *    shutdown cycle has just completed — the ordering that makes a request
 *    arriving while a cycle is IN FLIGHT still exit at that cycle's completion
 *    (the intent is recorded first, the completion hook reads it last);
 *  - at most one exit is performed, however many completions are reported.
 *
 * [exitProcess] is injected so the decision is unit-testable; [DaemonService]
 * wires `Process.killProcess(Process.myPid())` into it. It is always invoked
 * OUTSIDE the internal lock.
 */
class ShutdownExitGate(private val exitProcess: () -> Unit) {

    private val lock = Any()
    private var requestedState = false
    private var exitStartedState = false

    /** True once an exit was requested (independently of cycle progress). */
    val isExitRequested: Boolean get() = synchronized(lock) { requestedState }

    /** Records the exit intent. Never exits on its own (see class KDoc). */
    fun requestExit() {
        synchronized(lock) { requestedState = true }
    }

    /**
     * Reports that a shutdown cycle completed and performs the deferred exit
     * when one was requested.
     *
     * @return true when this call exited the process, false otherwise.
     */
    fun onShutdownCycleCompleted(): Boolean {
        synchronized(lock) {
            // A request that arrives after this point belongs to the NEXT cycle:
            // executing here again would exit mid-teardown.
            if (!requestedState || exitStartedState) return false
            exitStartedState = true
        }
        exitProcess()
        return true
    }
}
