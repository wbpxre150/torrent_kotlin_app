package com.torrent.app

import android.content.Context
import android.content.SharedPreferences
import com.torrent.app.config.AppConfig
import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.FtpSettings
import com.torrent.app.config.TransmissionSettings
import com.torrent.app.ftp.FtpStatus
import com.torrent.app.storage.SafStatus
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.Mockito

/**
 * Live routing tests for the stats websocket feed (webui stage 1): a real
 * ControlServer on a free port, a raw-socket upstream standing in for the
 * crawler's loopback /stats, and a hand-rolled RFC 6455 client (house style:
 * raw sockets + retries). Android unit tests compile against android.jar,
 * which has no java.net.http / com.sun.net.httpserver, so the WebSocket
 * client speaks the protocol by hand. Runs under Robolectric so org.json is
 * the real implementation (not the returnDefaultValues android.jar stub):
 * handleSearchFrame parses each frame with JSONObject.
 */
@RunWith(RobolectricTestRunner::class)
class ControlServerWebSocketTest {

    private companion object {
        const val TOKEN = "unit-test-token"

        /**
         * Realistic crawler /stats fixture: the documented top-level keys,
         * incl. the optional porn_filter / language_filter / supervisor /
         * bep51_cache / trees (with queues) / partitions / aggregate.
         */
        val STATS_FIXTURE = """
            {
              "version": "1.2.3",
              "torrents_indexed": 123456,
              "files_indexed": 654321,
              "torrents_last_hour": 42,
              "timestamp": 1700000000,
              "uptime": 999999,
              "porn_filter": {"enabled": true, "size": 900},
              "language_filter": {"non_latin_threshold": 30},
              "supervisor": {"active_trees": 16, "restarts": 0},
              "bep51_cache": {"bootstrap_complete": true, "cache_entries": 1000},
              "trees": [{"id": 1, "queues": {"metadata": 10, "peers": 5}}],
              "partitions": [{"id": 0, "nodes": 123}],
              "aggregate": {"total_queues": 15}
            }
        """.trimIndent()
    }

    // ── raw RFC 6455 client (android.jar has no java.net.http) ─────────────

    /**
     * Minimal websocket client: performs the upgrade handshake, then reads
     * text frames (masked client→server frames for ping replies/close).
     * [status] carries the HTTP status of the handshake response (101 = OK).
     */
    private class RawWebSocket(
        private val socket: Socket,
        private val input: BufferedInputStream,
        val upgraded: Boolean,
        val status: Int,
        val statusLine: String,
    ) {
        companion object {
            fun connect(port: Int, path: String): RawWebSocket {
                val socket = Socket().apply {
                    connect(InetSocketAddress("127.0.0.1", port), 5_000)
                    tcpNoDelay = true
                }
                val key = Base64.getEncoder()
                    .encodeToString(ByteArray(16).also { Random.nextBytes(it) })
                val request = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()
                val input = socket.getInputStream().buffered()
                val header = StringBuilder()
                while (!header.toString().endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b == -1) break
                    header.append(b.toChar())
                }
                val statusLine = header.lineSequence().firstOrNull() ?: ""
                val status = statusLine.substringAfter(" ", "").substringBefore(" ").toIntOrNull() ?: 0
                return RawWebSocket(socket, input, status == 101, status, statusLine)
            }
        }

        /**
         * Waits for the next text message (reassembling continuations,
         * answering pings). Returns null when the server closes the socket.
         * Throws [java.net.SocketTimeoutException] after [timeoutMs].
         */
        fun nextText(timeoutMs: Long): String? {
            socket.soTimeout = timeoutMs.toInt()
            var message: StringBuilder? = null
            while (true) {
                val b0 = input.read()
                if (b0 == -1) return null
                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F
                val b1 = input.read()
                if (b1 == -1) return null
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = (((input.read() or 0) shl 8) or (input.read() or 0)).toLong()
                } else if (len == 127L) {
                    len = 0L
                    repeat(8) { len = (len shl 8) or (input.read().toLong() and 0xFFL) }
                }
                val mask = if ((b1 and 0x80) != 0) ByteArray(4).also { readFully(it) } else null
                val payload = ByteArray(len.toInt())
                readFully(payload)
                if (mask != null) for (i in payload.indices) payload[i] = payload[i].toInt().xor(mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x1 -> {
                        message = StringBuilder().append(payload.decodeToString())
                        if (fin) return message.toString()
                    }
                    0x0 -> {
                        val m = message ?: return null
                        m.append(payload.decodeToString())
                        if (fin) return m.toString()
                    }
                    0x8 -> {
                        runCatching { sendFrame(0x8, payload) }
                        return null
                    }
                    0x9 -> sendFrame(0xA, payload) // ping → pong
                    else -> Unit
                }
            }
        }

        /** [BufferedInputStream] has no readFully on android.jar; read until filled. */
        private fun readFully(dst: ByteArray) {
            var off = 0
            while (off < dst.size) {
                val n = input.read(dst, off, dst.size - off)
                if (n == -1) throw java.io.EOFException("socket closed mid-frame")
                off += n
            }
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            val mask = ByteArray(4).also { Random.nextBytes(it) }
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            when {
                payload.size < 126 -> out.write(0x80 or payload.size)
                payload.size < 65536 -> {
                    out.write(0x80 or 126)
                    out.write((payload.size ushr 8) and 0xFF)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(0x80 or 127)
                    repeat(8) { i ->
                        out.write(((payload.size.toLong() ushr (8 * (7 - i))) and 0xFF).toInt())
                    }
                }
            }
            out.write(mask)
            for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
            val bytes = out.toByteArray()
            synchronized(socket) {
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
            }
        }

        fun close() = runCatching { socket.close() }

        /** Sends one masked client→server text frame (RFC 6455: client frames mask). */
        fun sendText(text: String) = sendFrame(0x1, text.toByteArray())
    }

    // ── server / upstream plumbing ─────────────────────────────────────────

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun tokenManagerWith(token: String): TokenManager {
        val prefs = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(prefs.getString("auth_token", null)).thenReturn(token)
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(
            context.getSharedPreferences("control_auth", Context.MODE_PRIVATE)
        ).thenReturn(prefs)
        return TokenManager(context)
    }

    /** Minimal raw-socket /stats upstream (stands in for the crawler's CivetWeb). */
    private fun startStatsUpstream(body: String): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                Thread {
                    try {
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        // Drain with available() polling: the client (JDK
                        // HttpURLConnection) writes in small paced chunks.
                        val deadline = System.currentTimeMillis() + 5_000
                        while (!header.toString().endsWith("\r\n\r\n") &&
                            System.currentTimeMillis() < deadline
                        ) {
                            val available = input.available()
                            if (available > 0) {
                                header.append(String(input.readNBytes(available)))
                            } else {
                                Thread.sleep(20)
                            }
                        }
                        val bytes = body.toByteArray()
                        val responseHeaders =
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        val out = socket.getOutputStream()
                        out.write(responseHeaders.toByteArray())
                        out.write(bytes)
                        out.flush()
                    } catch (_: Exception) {
                        // A per-connection failure must not kill the upstream.
                    } finally {
                        runCatching { socket.close() }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    /** Accepts connections but never answers: the loopback fetch must hit its
     * read timeout while the crawler daemon state still reports RUNNING. */
    private fun startSilentCrawlerUpstream(): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                Thread {
                    try {
                        // Drain the request so the socket stays open; the
                        // client blocks on read until its read timeout fires.
                        val input = socket.getInputStream()
                        val buf = ByteArray(512)
                        while (input.read(buf) != -1) Unit
                    } catch (_: Exception) {
                    } finally {
                        runCatching { socket.close() }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    private class LiveServer(val control: ControlServer, val port: Int) {
        fun start() = control.start()
        fun stop() = control.stop()
    }

    private fun startTestServer(
        token: String,
        crawlerRunning: Boolean,
        crawlerPort: Int,
        pushPeriodMs: Long,
        searchReadTimeoutMs: Int = ControlServer.SEARCH_READ_TIMEOUT_MS,
    ): LiveServer {
        val daemon = ProcessStatus(
            label = DaemonService.CRAWLER_LABEL,
            state = if (crawlerRunning) ProcessState.RUNNING else ProcessState.STOPPED,
        )
        val config = AppConfig(
            wizardCompleted = true,
            transmission = TransmissionSettings("u", "p", 51413, 0, 0, 0, 0),
            ftp = FtpSettings("kodi", "pw", 2121, false),
            crawler = CrawlerSettings(),
            backup = BackupSettings(),
            dataSubpath = "data",
        )
        val notWired = com.torrent.app.config.ApplyResult(false, "test", false)
        val port = freePort()
        return LiveServer(
            ControlServer(
                tokenManager = tokenManagerWith(token),
                serviceState = MutableStateFlow(ServiceState.Running),
                daemonStatuses = { listOf(daemon) },
                safStatus = { SafStatus(false, false, null, null) },
                wifiIp = { null },
                ftpStatus = { FtpStatus(false, "STOPPED", null, 0, null, 2121, "kodi", null, false) },
                loadConfig = { config },
                wizardCompleted = { true },
                rpcSummary = { null },
                usbFreeSpace = { null },
                lastApplyRolledBack = { false },
                restartDaemon = { false },
                stopDaemon = { false },
                startDaemon = { false },
                applyTransmission = { _, _ -> notWired },
                applyFtp = { notWired },
                crawlerHttpPort = { crawlerPort },
                port = port,
                wsPushPeriodMs = pushPeriodMs,
                searchReadTimeoutMs = searchReadTimeoutMs,
            ),
            port,
        )
    }

    /** Connects with a short retry so the CIO listener has a beat to come up. */
    private fun connectWithRetry(port: Int, path: String): RawWebSocket {
        var last: Exception? = null
        repeat(40) {
            try {
                return RawWebSocket.connect(port, path)
            } catch (e: ConnectException) {
                last = e
                Thread.sleep(50)
            } catch (e: SocketException) {
                last = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("websocket never connected on port $port", last)
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test fun statsWebSocket_withToken_firstFramePromptly_thenSecondAfterPeriod() {
        val upstream = startStatsUpstream(STATS_FIXTURE)
        val live = startTestServer(TOKEN, crawlerRunning = true, crawlerPort = upstream.localPort, pushPeriodMs = 150)
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/stats/ws?token=$TOKEN")
            try {
                assertTrue("expected a 101 upgrade, got: ${ws.statusLine}", ws.upgraded)
                // (a) first frame arrives promptly and carries the fixture.
                val first = ws.nextText(10_000)
                assertTrue("first frame did not arrive", first != null)
                assertFixture(first!!)
                // (b) a second frame arrives after ~one short period.
                val second = ws.nextText(10_000)
                assertTrue("second frame did not arrive within 10s", second != null)
                assertFixture(second!!)
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
            upstream.close()
        }
    }

    @Test fun statsWebSocket_withoutToken_isRejectedAndDeliversNoStats() {
        val upstream = startStatsUpstream(STATS_FIXTURE)
        val live = startTestServer(TOKEN, crawlerRunning = true, crawlerPort = upstream.localPort, pushPeriodMs = 150)
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/stats/ws")
            try {
                // (c) no token → the upgrade is rejected (401) before a single
                // frame could be delivered, so no stats ever reach the client.
                assertTrue(
                    "expected the unauthenticated upgrade to be rejected, got: ${ws.statusLine}",
                    !ws.upgraded,
                )
                assertEquals(401, ws.status)
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
            upstream.close()
        }
    }

    @Test fun statsWebSocket_crawlerDown_pushesNotRunningFallback() {
        // No upstream at all; the daemon reports STOPPED, so the feed must
        // keep pushing the clean fallback (and recover when it restarts).
        val live = startTestServer(TOKEN, crawlerRunning = false, crawlerPort = 1, pushPeriodMs = 150)
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/stats/ws?token=$TOKEN")
            try {
                assertTrue("expected a 101 upgrade, got: ${ws.statusLine}", ws.upgraded)
                val first = ws.nextText(10_000)
                assertTrue("expected the not-running fallback, got: $first", first?.contains("\"running\":false") == true)
                assertTrue("expected state STOPPED, got: $first", first?.contains("\"state\":\"STOPPED\"") == true)
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
        }
    }

    @Test fun searchWebSocket_runningDaemon_silentCrawler_answersBusyNotUnavailable() {
        // The exact field report: a complete_series search while the crawler
        // daemon is RUNNING but its HTTP API never answers. The old reply was
        // the misleading `unavailable` ("crawler is starting or stopped");
        // with the daemon state consulted it must be `busy` instead.
        val upstream = startSilentCrawlerUpstream()
        val live = startTestServer(
            TOKEN, crawlerRunning = true, crawlerPort = upstream.localPort,
            pushPeriodMs = 10_000, searchReadTimeoutMs = 500,
        )
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/search/ws?token=$TOKEN")
            try {
                assertTrue("expected a 101 upgrade, got: ${ws.statusLine}", ws.upgraded)
                ws.sendText("""{"id":7,"type":"search","q":"Manifest","tv_kind":"complete_series"}""")
                val reply = ws.nextText(10_000)
                assertTrue("expected a reply, got none", reply != null)
                assertTrue("expected busy, got: $reply", reply!!.contains("\"error\":\"busy\""))
                assertFalse("busy must not claim the crawler is down: $reply",
                    reply.contains("unavailable"))
                assertTrue("expected the request id echoed, got: $reply", reply.contains("\"id\":7"))
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
            upstream.close()
        }
    }

    @Test fun searchWebSocket_stoppedDaemon_answersUnavailable() {
        // Nothing listens on the crawler port and the daemon reports STOPPED:
        // the reply stays `unavailable` (the "starting or stopped" banner).
        val live = startTestServer(
            TOKEN, crawlerRunning = false, crawlerPort = 1,
            pushPeriodMs = 10_000, searchReadTimeoutMs = 500,
        )
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/search/ws?token=$TOKEN")
            try {
                ws.sendText("""{"id":8,"type":"search","q":"Manifest"}""")
                val reply = ws.nextText(10_000)
                assertTrue("expected a reply, got none", reply != null)
                assertTrue("expected unavailable, got: $reply",
                    reply!!.contains("\"error\":\"unavailable\""))
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
        }
    }

    @Test fun searchWebSocket_crawlerAnswers_splicesPayloadVerbatim() {
        // Happy path: the crawler answers 200 JSON, the reply splices it
        // verbatim under `payload` (escaping stays the crawler's).
        val upstream = startStatsUpstream("""{"results":[],"has_more":false}""")
        val live = startTestServer(
            TOKEN, crawlerRunning = true, crawlerPort = upstream.localPort,
            pushPeriodMs = 10_000, searchReadTimeoutMs = 15_000,
        )
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/search/ws?token=$TOKEN")
            try {
                ws.sendText("""{"id":9,"type":"browse","category":"tv","sort":"peers","page":1}""")
                val reply = ws.nextText(10_000)
                assertTrue("expected a reply, got none", reply != null)
                assertTrue("expected ok with the payload spliced verbatim, got: $reply",
                    reply!!.contains("\"ok\":true") &&
                        reply.contains("\"payload\":{\"results\":[],\"has_more\":false}"))
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
            upstream.close()
        }
    }

    private fun assertFixture(frame: String) {
        for (key in listOf(
            "\"version\"", "\"torrents_indexed\": 123456", "\"files_indexed\": 654321",
            "\"torrents_last_hour\": 42", "\"timestamp\"", "\"uptime\"",
            "\"porn_filter\"", "\"language_filter\"", "\"supervisor\"", "\"bep51_cache\"",
            "\"trees\"", "\"queues\"", "\"partitions\"", "\"aggregate\"",
        )) {
            assertTrue("expected $key in frame, got: $frame", frame.contains(key))
        }
    }
}
