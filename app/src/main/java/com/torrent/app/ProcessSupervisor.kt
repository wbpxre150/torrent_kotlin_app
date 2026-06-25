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
 * Instances are NOT thread-safe for start/stop from arbitrary threads — call from a
 * single dispatcher (e.g. the service's coroutine scope). Status reads via [status]
 * are safe from any thread.
 */
class ProcessSupervisor(
    private val label: String,
    private val binaryName: String,              // e.g. "libprobe.so"
    private val args: List<String> = emptyList(),
    private val nativeLibraryDir: String,         // applicationInfo.nativeLibraryDir
    private val workDir: File,                    // child process cwd
    private val logDir: File,                     // rotating log files go here
    private val ldLibraryPath: String? = null,    // optional LD_LIBRARY_PATH
    private val extraEnv: Map<String, String> = emptyMap(),
    private val selfLogging: Boolean = false,
    private val pipelineMaxLines: Int = 200,
) {
    companion object {
        private const val TAG = "ProcessSupervisor"

        // Backoff: 1s, 2s, 4s, 8s, 16s, 30s, 30s…
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)
        private const val BACKOFF_CAP_MS = 30000L

        // Grace period between destroy() and destroyForcibly()
        private const val GRACE_MS = 5000L

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

    private var child: Process? = null
    // Offset used by tailLogFileIntoRingBuffer and drained by drainLogTailOnce
    private var tailLastPos = 0L

    /** Launch the child process. Idempotent: if already running, does nothing. */
    fun start() {
        val current = _status.value
        if (current.state == ProcessState.RUNNING || current.state == ProcessState.STARTING) {
            Log.d(TAG, "[$label] already ${current.state}, ignoring start()")
            return
        }
        stopping = false
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("supervisor-$label"))
        scope!!.launch { runLoop() }
    }

    /** Graceful stop: SIGTERM → wait GRACE_MS → SIGKILL. Idempotent. */
    fun stop() {
        Log.d(TAG, "[$label] stop() requested")
        stopping = true
        _status.value = _status.value.copy(state = ProcessState.STOPPING)
        scope?.cancel()
        killChild()
        scope = null
        _status.value = _status.value.copy(state = ProcessState.STOPPED, pid = null, startTimeMs = null)
    }

    /** kill + delayed restart. Callable from any thread. */
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

        while (!stopping) {
            _status.value = _status.value.copy(state = ProcessState.STARTING, pid = null)

            val proc = launchChild()
            if (proc == null) {
                // launchChild logs the error; back off and retry
                val delayMs = backoffMs(backoffIndex)
                Log.w(TAG, "[$label] launch failed, retrying in ${delayMs}ms")
                delayFor(delayMs)
                backoffIndex = if (backoffIndex < BACKOFF_MS.size) backoffIndex + 1 else backoffIndex
                continue
            }

            child = proc
            val now = System.currentTimeMillis()
            restarts++
            _status.value = _status.value.copy(
                state = ProcessState.RUNNING,
                pid = pidOf(proc),
                startTimeMs = now,
                restartCount = restarts,
            )
            Log.i(TAG, "[$label] started (restart #$restarts)")

            // Drain stdout/stderr concurrently with waitFor
            val exited = drainAndWait(proc)

            child = null

            if (stopping) break

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
            delayFor(delayMs)
            backoffIndex = if (backoffIndex < BACKOFF_MS.size) backoffIndex + 1 else backoffIndex
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
    internal fun resolveBinary(): File? {
        val binary = File(nativeLibraryDir, binaryName)
        if (!binary.exists()) {
            Log.e(TAG, "[$label] binary not found: ${binary.absolutePath}")
            return null
        }
        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
            if (!binary.canExecute()) {
                Log.e(TAG, "[$label] binary not executable: ${binary.absolutePath}")
                return null
            }
        }
        return binary
    }

    private fun launchChild(): Process? {
        // workDir is the child's cwd (ProcessBuilder.directory). It must exist
        // before start(): ProcessBuilder forks and calls chdir(workDir) before
        // execve(); a missing cwd yields ENOENT that Java's IOException reports
        // against the program path, masking the real cause.
        workDir.mkdirs()

        val binary = resolveBinary() ?: return null

        val env = ProcessBuilder().environment().apply {
            if (ldLibraryPath != null) {
                put("LD_LIBRARY_PATH", ldLibraryPath)
            }
            putAll(extraEnv)
        }

        val cmd = mutableListOf(binary.absolutePath)
        cmd.addAll(args)

        return try {
            ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "[$label] exec failed: ${e.message}", e)
            null
        }
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
            if (proc.waitFor(GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return
            proc.destroyForcibly() // SIGKILL
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Process already dead — fine
        }
    }

    private fun backoffMs(index: Int): Long {
        return if (index < BACKOFF_MS.size) BACKOFF_MS[index] else BACKOFF_CAP_MS
    }

    private suspend fun delayFor(ms: Long) {
        try {
            delay(ms)
        } catch (_: CancellationException) {
            // Expected during stop()
        }
    }
    private fun pidOf(proc: Process): Int? = try {
        val f = proc.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        (f.get(proc) as? Int)
    } catch (_: Exception) {
        null
    }
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
