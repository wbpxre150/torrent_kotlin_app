package com.torrent.app.net

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plain-JVM tests for the greeting probe extracted from [FtpHangWatchdog]
 * ([FtpHangWatchdog.probeGreeting]) — shared with the ftp_server supervisor
 * readiness probe, so a not-yet-ready daemon and a hung one are judged the
 * same way. No Android imports, real loopback sockets only.
 */
class FtpHangWatchdogProbeTest {

    /** Accepts one connection, writes [greeting] (if any), then closes. */
    private fun respondWith(greeting: String?): Pair<ServerSocket, Int> {
        val server = ServerSocket(0)
        Thread {
            val sock = try { server.accept() } catch (_: Exception) { null } ?: return@Thread
            try {
                if (greeting != null) {
                    sock.outputStream.write(greeting.toByteArray())
                    sock.outputStream.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { sock.close() } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        return server to server.localPort
    }

    @Test
    fun `220 greeting is ready`() {
        val (server, port) = respondWith("220 ftpserver-host ready.\r\n")
        try {
            assertNull(FtpHangWatchdog.probeGreeting("127.0.0.1", port, connectTimeoutMs = 2_000))
        } finally {
            server.close()
        }
    }

    @Test
    fun `non-220 greeting is not ready`() {
        val (server, port) = respondWith("530 no way\r\n")
        try {
            val reason = FtpHangWatchdog.probeGreeting("127.0.0.1", port, connectTimeoutMs = 2_000)
            assertTrue("expected a not-ready reason, got: $reason",
                reason != null && reason.contains("no 220 greeting"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `connection without greeting is not ready`() {
        val (server, port) = respondWith(null)  // accepts, sends nothing, closes
        try {
            val reason = FtpHangWatchdog.probeGreeting("127.0.0.1", port, connectTimeoutMs = 2_000)
            assertTrue("expected a not-ready reason, got: $reason",
                reason != null && reason.contains("EOF"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `silent daemon times out as not ready`() {
        val server = ServerSocket(0)
        val held = CopyOnWriteArrayList<Socket>()
        Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                held.add(sock)  // accept but never speak
            }
        }.apply { isDaemon = true; start() }
        try {
            val reason = FtpHangWatchdog.probeGreeting(
                "127.0.0.1", server.localPort, connectTimeoutMs = 300)
            assertTrue("expected a timeout reason, got: $reason",
                reason != null && reason.contains("probe failed"))
        } finally {
            try { server.close() } catch (_: Exception) { }
            held.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }

    @Test
    fun `closed port is not ready`() {
        val port = ServerSocket(0).use { it.localPort }  // bound then closed
        val reason = FtpHangWatchdog.probeGreeting("127.0.0.1", port, connectTimeoutMs = 500)
        assertTrue("expected a connect failure reason, got: $reason",
            reason != null && reason.contains("probe failed"))
    }
}
