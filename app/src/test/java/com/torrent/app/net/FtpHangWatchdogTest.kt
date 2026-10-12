package com.torrent.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plain-JVM tests for [FtpHangWatchdog] (no Robolectric): the class has no
 * Android imports, so real loopback sockets are enough. Tests use tiny
 * intervals (10 ms) and a no-op [FtpHangWatchdog.sleeper] — the loop paces
 * itself via the clock seam — keeping the whole suite well under 5 s.
 */
class FtpHangWatchdogTest {

    /** Control port that greets every connection with a 220 line (healthy). */
    private class GreetingServer : AutoCloseable {
        val server = ServerSocket(0)
        val port: Int get() = server.localPort
        val connections = java.util.concurrent.atomic.AtomicInteger()
        private val acceptor = Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                connections.incrementAndGet()
                Thread {
                    try {
                        sock.outputStream.write("220 test ftp ready\r\n".toByteArray())
                        sock.outputStream.flush()
                    } catch (_: Exception) { }
                    try { sock.close() } catch (_: Exception) { }
                }.start()
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            try { server.close() } catch (_: Exception) { }
            acceptor.join(2_000)
        }
    }

    /** Accepts connections but never sends anything (a hung daemon). */
    private class SilentServer : AutoCloseable {
        val server = ServerSocket(0)
        val port: Int get() = server.localPort
        val connections = java.util.concurrent.atomic.AtomicInteger()
        private val held = CopyOnWriteArrayList<Socket>()
        private val acceptor = Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                connections.incrementAndGet()
                held.add(sock)
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            try { server.close() } catch (_: Exception) { }
            acceptor.join(2_000)
            for (s in held) try { s.close() } catch (_: Exception) { }
        }
    }

    /**
     * Runs [block] with a watchdog built from the given providers on a
     * throwaway scope; always stops the watchdog and cancels the scope.
     */
    private fun <T> withWatchdog(
        host: () -> String?,
        port: () -> Int,
        running: () -> Boolean,
        restarts: MutableList<String>,
        intervalMs: Long = 10,
        connectTimeoutMs: Int = 50,
        failThreshold: Int = 3,
        block: (FtpHangWatchdog) -> T,
    ): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val watchdog = FtpHangWatchdog(
            scope = scope,
            hostProvider = host,
            portProvider = port,
            isRunningProvider = running,
            restartAction = { reason -> synchronized(restarts) { restarts.add(reason) } },
            checkIntervalMs = intervalMs,
            connectTimeoutMs = connectTimeoutMs,
            failThreshold = failThreshold,
            sleeper = { },
        )
        try {
            return block(watchdog)
        } finally {
            watchdog.stop()
            scope.cancel()
        }
    }

    @Test
    fun healthyGreeting_noRestarts() {
        val restarts = mutableListOf<String>()
        GreetingServer().use { server ->
            withWatchdog(
                host = { "127.0.0.1" },
                port = { server.port },
                running = { true },
                restarts = restarts,
            ) { wd ->
                wd.start()
                wd.start() // idempotent
                val deadline = System.currentTimeMillis() + 5_000
                while (server.connections.get() < 10 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
            }
            assertTrue("expected at least 10 probes, got ${server.connections.get()}",
                server.connections.get() >= 10)
        }
        assertEquals(0, restarts.size)
    }

    @Test
    fun hungDaemon_restartsOncePerThreshold_thenResets() {
        val latch = CountDownLatch(2)
        val restarts = object : ArrayList<String>() {
            override fun add(element: String): Boolean {
                val r = super.add(element)
                latch.countDown()
                return r
            }
        }
        SilentServer().use { server ->
            withWatchdog(
                host = { "127.0.0.1" },
                port = { server.port },
                running = { true },
                restarts = restarts,
                connectTimeoutMs = 30,
            ) { wd ->
                wd.start()
                assertTrue("watchdog never restarted the hung daemon",
                    latch.await(10, TimeUnit.SECONDS))
            }
            assertEquals(2, restarts.size)
            // A hung daemon accepts but never sends, so the probe times out
            // reading the greeting.
            assertTrue("reason should describe the failed probe: ${restarts[0]}",
                restarts[0].contains("probe failed"))
        }
    }

    @Test
    fun notRunning_skipsAndNoRestarts() {
        val restarts = mutableListOf<String>()
        withWatchdog(
            host = { "127.0.0.1" },
            port = { 0 }, // never used while not running
            running = { false },
            restarts = restarts,
        ) { wd ->
            wd.start()
            Thread.sleep(150) // several 10 ms intervals pass
        }
        assertEquals(0, restarts.size)
    }

    @Test
    fun hostNull_skipsAndNoRestarts() {
        val restarts = mutableListOf<String>()
        withWatchdog(
            host = { null },
            port = { 0 }, // never used while the host is null
            running = { true },
            restarts = restarts,
        ) { wd ->
            wd.start()
            Thread.sleep(150) // several 10 ms intervals pass
        }
        assertEquals(0, restarts.size)
    }

    @Test
    fun stop_haltsTheLoop() {
        val restarts = mutableListOf<String>()
        SilentServer().use { server ->
            withWatchdog(
                host = { "127.0.0.1" },
                port = { server.port },
                running = { true },
                restarts = restarts,
                connectTimeoutMs = 30,
            ) { wd ->
                wd.start()
                Thread.sleep(80) // let a few probes happen
                wd.stop()
                wd.stop() // idempotent
                Thread.sleep(100) // let any in-flight probe finish
                val atStop = server.connections.get()
                Thread.sleep(150) // more than several intervals
                assertEquals(atStop, server.connections.get())
            }
        }
    }
}
