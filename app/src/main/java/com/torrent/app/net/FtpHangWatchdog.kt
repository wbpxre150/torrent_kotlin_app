package com.torrent.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Stage 4: TCP hang watchdog for the supervised native FTP daemon.
 *
 * The C daemon already covers crashes (ProcessSupervisor restarts),
 * orphaning (getppid guard) and clean stops (fast SIGTERM exit). What remains
 * is the hung-but-alive case: the process is up and the port may even accept
 * connections, but the control channel no longer speaks FTP. This class
 * probes it on an interval with a plain TCP connection and expects the
 * daemon's "220 ..." greeting (session.c: `reply(s, "220", "ftpserver-host
 * ready.")`). After [failThreshold] consecutive failed probes it invokes
 * [restartAction]; the service wires that to the same serialized rebuild path
 * as every other FTP lifecycle op.
 *
 * Plain JVM (no Android imports) so it is unit-testable without Robolectric.
 * All work runs on [Dispatchers.IO] inside the supplied [scope]; [System.err]
 * lines reach logcat on Android.
 */
class FtpHangWatchdog(
    private val scope: CoroutineScope,
    private val hostProvider: () -> String?,
    private val portProvider: () -> Int,
    private val isRunningProvider: () -> Boolean,
    private val restartAction: (reason: String) -> Unit,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val connectTimeoutMs: Int = 5_000,
    private val failThreshold: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
) {
    @Volatile private var loopJob: Job? = null
    @Volatile private var consecutiveFailures = 0

    /** Starts the probe loop; a no-op while it is already running. */
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) {
            while (coroutineContext.isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    // A broken iteration must never kill the loop.
                    log("probe iteration failed: ${e.message}")
                }
                val deadline = clock() + checkIntervalMs
                // Sleep in slices so stop() takes effect promptly.
                while (coroutineContext.isActive) {
                    val remaining = deadline - clock()
                    if (remaining <= 0) break
                    sleeper(remaining.coerceAtMost(SLEEP_SLICE_MS))
                }
            }
        }
    }

    /** Stops the probe loop; idempotent. */
    fun stop() {
        val job = loopJob ?: return
        loopJob = null
        job.cancel()
    }

    /**
     * One probe. Skipped (with the failure counter reset) while the supervisor
     * is not RUNNING or the bind IP is unknown yet.
     */
    private fun tick() {
        val host = hostProvider()
        if (!isRunningProvider() || host == null) {
            consecutiveFailures = 0
            return
        }
        val reason = probe(host, portProvider())
        if (reason == null) {
            consecutiveFailures = 0
            return
        }
        consecutiveFailures++
        if (consecutiveFailures < failThreshold) return
        consecutiveFailures = 0
        log("$reason — restarting the ftp daemon")
        try {
            restartAction(reason)
        } catch (e: Exception) {
            // A failing restart must not crash the loop or start a restart
            // storm: log it and keep probing.
            log("restart action failed: ${e.message}")
        }
    }

    /**
     * Opens a plain TCP connection to [host]:[port] and reads the first line.
     * Returns null when the line starts with "220" (the daemon's greeting),
     * otherwise a human-readable failure reason.
     */
    private fun probe(host: String, port: Int): String? {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
                // Bound the read too: a hung daemon may accept but never send.
                sock.soTimeout = connectTimeoutMs
                val line = sock.getInputStream().bufferedReader().readLine()
                if (line != null && line.startsWith(GREETING_PREFIX)) {
                    null
                } else {
                    "no 220 greeting (got: ${line?.take(40) ?: "EOF"})"
                }
            }
        } catch (e: Exception) {
            "probe failed (${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun log(msg: String) {
        System.err.println("[FtpHangWatchdog] $msg")
    }

    companion object {
        private const val DEFAULT_CHECK_INTERVAL_MS = 30_000L
        private const val SLEEP_SLICE_MS = 250L
        private const val GREETING_PREFIX = "220"
    }
}
