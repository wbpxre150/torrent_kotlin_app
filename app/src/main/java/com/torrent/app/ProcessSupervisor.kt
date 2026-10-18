package com.torrent.app

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
/** Observable state of a single supervised daemon process. */
data class ProcessStatus(
    val label: String,
    val state: ProcessState,
    val pid: Int? = null,
    val startTimeMs: Long? = null,   // System.currentTimeMillis() when last launched
    val restartCount: Int = 0,
    val lastLogLines: List<String> = emptyList(),
)

enum class ProcessState { STOPPED, STARTING, RUNNING, STOPPING }

/**
 * Supervises one native child process: launch, drain stdio, restart with backoff,
 * clean shutdown.
 *
 * start/stop are serialized internally and may be called from any thread, but
 * stop()/restart() block for up to the SIGTERM grace period — never call them
 * from the main thread or an HTTP request handler. Status reads via [status]
 * are safe from any thread.
 *
 * Readiness: when [readinessProbe] is set, the supervisor stays in
 * [ProcessState.STARTING] after the child is forked and publishes
 * [ProcessState.RUNNING] only once the probe returns true (e.g. the daemon's
 * RPC port answers). While the child is alive the probe is retried every
 * [readinessPollMs]; a probe that throws counts as "not ready" and is retried.
 * There is deliberately no readiness timeout — a daemon that never answers is
 * reported as STARTING for as long as it stays alive, and an exit still takes
 * the normal exit/backoff/restart path. With no probe (the default) the
 * supervisor publishes RUNNING immediately after the fork.
 */
class ProcessSupervisor(
    private val label: String,
    private val binaryName: String,              // e.g. "libtransmissiond.so"
    private val args: List<String> = emptyList(),
    private val nativeLibraryDir: String,         // applicationInfo.nativeLibraryDir
    private val workDir: File,                    // child process cwd
    private val logDir: File,                     // rotating log files go here
    private val ldLibraryPath: String? = null,    // optional LD_LIBRARY_PATH
    private val extraEnv: Map<String, String> = emptyMap(),
    private val selfLogging: Boolean = false,
    private val pipelineMaxLines: Int = 200,
    private val graceMs: Long = DEFAULT_GRACE_MS,  // destroy() → destroyForcibly() window
    // Optional readiness probe: while set, the supervisor stays STARTING after
    // the fork until a probe returns true. See the class KDoc.
    private val readinessProbe: (suspend () -> Boolean)? = null,
    private val readinessPollMs: Long = 2000,      // delay between failed probes
) {
    companion object {
        private const val TAG = "ProcessSupervisor"

        // Backoff: 1s, 2s, 4s, 8s, 16s, 30s, 30s…
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)
        private const val BACKOFF_CAP_MS = 30000L

        // Grace period between destroy() and destroyForcibly()
        private const val DEFAULT_GRACE_MS = 5000L

        // Max log file size before rotation (1 MB)
        private const val MAX_LOG_BYTES = 1_000_000L
        private const val LOG_ROTATIONS = 1

        // Reset backoff to its fastest value only on a clean exit. A crash
        // (non-zero exit, e.g. SIGSEGV=139) keeps the current index so a crash
        // loop escalates toward BACKOFF_CAP_MS instead of resetting every cycle.
        internal fun shouldResetBackoff(exitCode: Int): Boolean = exitCode == 0
    }

    private val _status = MutableStateFlow(ProcessStatus(label = label, state = ProcessState.STOPPED))
    val status: StateFlow<ProcessStatus> = _status.asStateFlow()

    // Internal coroutine scope — cancelled on stop()
    private var scope: CoroutineScope? = null

    // True when the user requested a stop (suppresses restart)
    @Volatile
    private var stopping = false

    // Ring buffer for in-memory log tail
    private val ringBuffer = ArrayDeque<String>(pipelineMaxLines)

    // Guarded by [lifecycleLock]; serializes start()/stop() callers from
    // arbitrary threads (HTTP handlers, executors) against the run loop.
    private val lifecycleLock = Any()
    @Volatile
    private var child: Process? = null
    // Offset used by tailLogFileIntoRingBuffer and drained by drainLogTailOnce
    private var tailLastPos = 0L

    /** Launch the child process. Idempotent: if already running, does nothing. */
    fun start() = synchronized(lifecycleLock) {
        val current = _status.value
        if (current.state == ProcessState.RUNNING || current.state == ProcessState.STARTING) {
            Log.d(TAG, "[$label] already ${current.state}, ignoring start()")
            return@synchronized
        }
        stopping = false
        scope?.cancel()
        val newScope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + CoroutineName("supervisor-$label") +
                CoroutineExceptionHandler { _, e ->
                    // Never let a supervisor failure take down the app process.
                    Log.e(TAG, "[$label] supervisor loop failed", e)
                })
        scope = newScope
        _status.value = _status.value.copy(state = ProcessState.STARTING, pid = null)
        newScope.launch { runLoop() }
    }

    /**
     * Graceful stop: SIGTERM → wait [graceMs] → SIGKILL. Idempotent. Blocks for
     * up to [graceMs] + 2 s, so never call it from the main thread or an HTTP
     * request handler.
     */
    fun stop() = synchronized(lifecycleLock) {
        Log.d(TAG, "[$label] stop() requested")
        stopping = true
        _status.value = _status.value.copy(state = ProcessState.STOPPING)
        scope?.cancel()
        scope = null
        killChild()
        _status.value = _status.value.copy(state = ProcessState.STOPPED, pid = null, startTimeMs = null)
    }

    /** kill + delayed restart. Blocking (see [stop]); callable from any non-main thread. */
    fun restart() {
        Log.d(TAG, "[$label] restart() requested")
        stop()
        Thread.sleep(200) // brief pause to let OS reap
        start()
    }

    // ── internal ────────────────────────────────────────────────────────────

    private suspend fun runLoop() {
        var backoffIndex = 0
        var restarts = _status.value.restartCount

        // Loop on this coroutine's own cancellation, not only the shared
        // [stopping] flag: after stop() + start() the flag is false again, and
        // a stale loop must not resurrect and launch a second child.
        while (currentCoroutineContext().isActive && !stopping) {
            _status.value = _status.value.copy(state = ProcessState.STARTING, pid = null)

            val proc = launchChild()
            if (proc == null) {
                // launchChild logs the error; back off and retry
                val delayMs = backoffMs(backoffIndex)
                Log.w(TAG, "[$label] launch failed, retrying in ${delayMs}ms")
                delay(delayMs)
                backoffIndex = if (backoffIndex < BACKOFF_MS.size) backoffIndex + 1 else backoffIndex
                continue
            }

            // Re-check under the lock: a stop() that raced the launch must still
            // see (and kill) this child.
            val active = currentCoroutineContext().isActive
            val publish = synchronized(lifecycleLock) {
                if (!active || stopping) false
                else { child = proc; true }
            }
            if (!publish) {
                proc.destroyForcibly()
                break
            }
            val now = System.currentTimeMillis()
            restarts++
            // With a readiness probe the launch stays STARTING until the probe
            // answers; without one it is RUNNING as soon as it is forked (the
            // original behavior).
            _status.value = _status.value.copy(
                state = if (readinessProbe == null) ProcessState.RUNNING else ProcessState.STARTING,
                pid = pidOf(proc),
                startTimeMs = now,
                restartCount = restarts,
            )
            Log.i(TAG, "[$label] started (restart #$restarts)")

            // Drain stdout/stderr concurrently with waitFor. While a readiness
            // probe is configured it polls as a child coroutine; it is cancelled
            // as soon as this launch ends (child exit, or the loop itself being
            // cancelled by stop()), so a dead child's probe never lingers.
            val exited = try {
                coroutineScope {
                    val readyJob = if (readinessProbe != null) launch { awaitReadiness() } else null
                    try {
                        drainAndWait(proc)
                    } finally {
                        readyJob?.cancel()
                    }
                }
            } finally {
                synchronized(lifecycleLock) { if (child === proc) child = null }
            }

            if (stopping || !currentCoroutineContext().isActive) break

            // Process exited. Reset backoff only on a clean exit (exitCode == 0);
            // a crash keeps the index climbing so a crash loop escalates toward
            // BACKOFF_CAP_MS instead of resetting to 1s every cycle. Previously
            // backoff reset after 60s of uptime regardless of exit code, so a
            // daemon that ran ~2.5 min then crashed never escalated.
            val uptime = System.currentTimeMillis() - now
            val exitCode = if (exited) proc.exitValue() else -1
            if (shouldResetBackoff(exitCode)) {
                backoffIndex = 0
            }
            Log.w(TAG, "[$label] exited code=$exitCode uptime=${uptime}ms")

            val delayMs = backoffMs(backoffIndex)
            Log.i(TAG, "[$label] restarting in ${delayMs}ms")
            delay(delayMs)
            backoffIndex = if (backoffIndex < BACKOFF_MS.size) backoffIndex + 1 else backoffIndex
        }
    }

    /**
     * Polls [readinessProbe] until it reports ready, then publishes
     * [ProcessState.RUNNING]. Runs as a child coroutine of the launch's
     * [coroutineScope], so it is cancelled when the child exits or the
     * supervisor scope is cancelled by [stop].
     *
     * A probe that throws counts as not-ready (a daemon that is still binding
     * its port often refuses connections). There is deliberately no timeout: we
     * keep polling while the child is alive; if it dies, its exit drives the
     * normal backoff/restart path.
     */
    private suspend fun awaitReadiness() {
        val probe = readinessProbe ?: return
        while (currentCoroutineContext().isActive) {
            val ready = try {
                probe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "[$label] readiness probe failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (ready) {
                // A stop() that raced the probe must never be overwritten by a
                // late RUNNING: only publish from STARTING and only if we are
                // not stopping. Other fields (logs, pid, …) are carried over.
                val published = synchronized(lifecycleLock) {
                    val cur = _status.value
                    if (!stopping && cur.state == ProcessState.STARTING) {
                        _status.value = cur.copy(state = ProcessState.RUNNING)
                        true
                    } else {
                        false
                    }
                }
                if (published) {
                    Log.i(TAG, "[$label] ready (RPC probe succeeded)")
                } else {
                    Log.d(TAG, "[$label] ready but state is ${_status.value.state}, not publishing RUNNING")
                }
                return
            }
            delay(readinessPollMs)
        }
    }

    /**
     * Resolves the native binary under [nativeLibraryDir] and verifies it is
     * executable. The binary is exec'd in place from nativeLibraryDir: that
     * path carries the SELinux label `apk_data_file`, which `untrusted_app`
     * is permitted to execute. Staging the binary into [workDir]
     * (label `app_data_file`) is forbidden by Android W^X enforcement and
     * must not be done.
     */
    internal fun resolveBinary(): File? =
        NativeProcess.resolveBinary(label, nativeLibraryDir, binaryName)

    private fun launchChild(): Process? {
        val binary = resolveBinary() ?: return null
        return NativeProcess.startProcess(
            NativeProcess.buildCommand(binary, args), workDir, ldLibraryPath, extraEnv)
    }

    /**
     * Drains the process stdout (which also carries stderr due to redirectErrorStream=true)
     * into both the ring buffer and the rotating log file. Each line is pushed into
     * [status] so the control page always has fresh logs. Returns true if the process
     * exited normally (waitFor completed), false if we gave up waiting.
     */
    private suspend fun drainAndWait(proc: Process): Boolean = withContext(Dispatchers.IO) {
        val logFile = File(logDir, "$label.log")
        logDir.mkdirs()
        tailLastPos = 0L

        if (selfLogging) {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val tailJob = launch {
                tailLogFileIntoRingBuffer(logFile)
            }
            val stdoutJob = launch {
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        pushToRingBuffer(line)
                        line = reader.readLine()
                    }
                } catch (_: Exception) {}
            }
            try {
                proc.waitFor()
                true
            } catch (_: InterruptedException) {
                false
            } finally {
                tailJob.cancel()
                stdoutJob.cancel()
                try { reader.close() } catch (_: Exception) {}
                // One-shot final drain of any bytes the tail job had not yet polled.
                // Do NOT call the infinite tailLogFileIntoRingBuffer here — that hangs
                // the supervisor thread and blocks restart (Bug 2).
                drainLogTailOnce(logFile)
            }
        } else {
            val writer = logFile.bufferedWriter()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))

            try {
                var line = reader.readLine()
                while (line != null) {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                    pushToRingBuffer(line)
                    line = reader.readLine()
                }
            } catch (_: java.io.IOException) {
                // Expected on stop(): Process.destroy() closes the child's stdout
                // under us ("read interrupted by close() on another thread").
            } finally {
                try { writer.close() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
            }

            if (logFile.length() > MAX_LOG_BYTES) rotateLogs(logFile)

            try {
                proc.waitFor()
                true
            } catch (_: InterruptedException) {
                false
            }
        }
    }

    private fun pushToRingBuffer(line: String) {
        synchronized(ringBuffer) {
            if (ringBuffer.size >= pipelineMaxLines) ringBuffer.removeFirst()
            ringBuffer.addLast(line)
            _status.value = _status.value.copy(lastLogLines = ringBuffer.toList())
        }
    }

    private suspend fun tailLogFileIntoRingBuffer(logFile: File) {
        while (true) {
            try {
                val len = logFile.length()
                if (len > tailLastPos) {
                    logFile.bufferedReader().use { reader ->
                        reader.skip(tailLastPos)
                        var line = reader.readLine()
                        while (line != null) {
                            pushToRingBuffer(line)
                            tailLastPos += line.length.toLong() + 1L
                            line = reader.readLine()
                        }
                    }
                }
            } catch (_: Exception) {}
            delay(500)
        }
    }

    private fun rotateLogs(logFile: File) {
        for (i in LOG_ROTATIONS downTo 1) {
            val older = File(logDir, "$label.log.$i")
            val newer = if (i == 1) logFile else File(logDir, "$label.log.${i - 1}")
            older.delete()
            if (newer.exists()) newer.renameTo(older)
        }
    }

    private fun killChild() {
        val proc = child ?: return
        try {
            proc.destroy() // SIGTERM
            if (proc.waitFor(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return
            proc.destroyForcibly() // SIGKILL
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Process already dead — fine
        }
    }

    private fun backoffMs(index: Int): Long {
        return if (index < BACKOFF_MS.size) BACKOFF_MS[index] else BACKOFF_CAP_MS
    }
    private fun pidOf(proc: Process): Int? = NativeProcess.pidOf(proc)
    /**
     * One-shot read of any log file content appended since [tailLastPos], pushed
     * into the ring buffer. Returns immediately after a single pass (no looping).
     * Used as the final drain after process exit so the supervisor thread is not
     * blocked by the infinite [tailLogFileIntoRingBuffer] loop.
     */
    private fun drainLogTailOnce(logFile: File) {
        try {
            val len = logFile.length()
            if (len > tailLastPos) {
                logFile.bufferedReader().use { reader ->
                    reader.skip(tailLastPos)
                    var line = reader.readLine()
                    while (line != null) {
                        pushToRingBuffer(line)
                        tailLastPos += line.length.toLong() + 1L
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
