package com.torrent.app

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs the native binary as a one-shot process (plan 10 §4). Uses the same
 * launch path as [ProcessSupervisor] (via [NativeProcess]) so the app has a
 * single exec path.
 *
 * [run] blocks until the child exits; call it on a background executor, never
 * the main thread. It is cancellable: [cancel] terminates the child (SIGTERM,
 * then SIGKILL) and [run] returns -1.
 */
class OneShotRunner(
    private val binaryName: String,
    private val nativeLibraryDir: String,
    private val ldLibraryPath: String? = null,
    private val extraEnv: Map<String, String> = emptyMap(),
    private val cancelGraceMs: Long = 5_000L,
) {
    @Volatile private var child: Process? = null

    /** Child PID of the current run (for observability); null when idle. */
    @Volatile var pid: Int? = null
        private set

    /**
     * Runs the binary once with [args] in [workDir], invoking [onLine] for each
     * output line (stdout + stderr merged). Blocks until the child exits.
     *
     * @return the child exit code, or -1 when the launch failed or the run was
     *   cancelled.
     */
    fun run(args: List<String>, workDir: File, onLine: (String) -> Unit = {}): Int {
        val binary = NativeProcess.resolveBinary("oneshot", nativeLibraryDir, binaryName) ?: return -1
        val proc = NativeProcess.startProcess(
            NativeProcess.buildCommand(binary, args), workDir, ldLibraryPath, extraEnv) ?: return -1
        child = proc
        pid = NativeProcess.pidOf(proc)
        try {
            // Drain the merged stdout/stderr line-by-line (the C crawler
            // flushes stderr after every log line), then wait for exit.
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    onLine(line)
                    line = reader.readLine()
                }
            }
            return proc.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return -1
        } catch (_: Exception) {
            return -1
        } finally {
            child = null
            pid = null
        }
    }

    /** SIGTERM the running child, then SIGKILL after [cancelGraceMs]. Idempotent. */
    fun cancel() {
        val proc = child ?: return
        try {
            proc.destroy()
            if (!proc.waitFor(cancelGraceMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
        } catch (_: Exception) {
            // already dead
        }
    }
}
