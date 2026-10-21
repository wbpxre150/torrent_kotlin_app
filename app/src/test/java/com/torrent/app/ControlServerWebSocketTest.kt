package com.torrent.app

import android.content.Context
import android.content.SharedPreferences
import com.torrent.app.config.AppConfig
import com.torrent.app.config.BackupSettings
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.FtpSettings
import com.torrent.app.config.TransmissionSettings
import com.torrent.app.ftp.FtpStatus
import com.torrent.app.transmission.TransmissionSummary
import com.torrent.app.storage.SafStatus
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import org.json.JSONObject
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
        /** The full handshake response header block (rejections included). */
        val headers: String,
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
                return RawWebSocket(socket, input, status == 101, status, statusLine, header.toString())
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
        // Default: the one crawler daemon whose state follows [crawlerRunning].
        // Tests that need pid/uptime/restart/log fixtures pass their own list.
        daemons: List<ProcessStatus> = listOf(
            ProcessStatus(
                label = DaemonService.CRAWLER_LABEL,
                state = if (crawlerRunning) ProcessState.RUNNING else ProcessState.STOPPED,
            ),
        ),
        // Default: no RPC snapshot, so `transmission.reachable` stays false.
        // Tests that exercise the reachable branch pass a fake summary.
        rpc: TransmissionSummary? = null,
    ): LiveServer {
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
                daemonStatuses = { daemons },
                safStatus = { SafStatus(false, false, null, null) },
                wifiIp = { null },
                ftpStatus = { FtpStatus(false, "STOPPED", null, 0, null, 2121, "kodi", null, false) },
                loadConfig = { config },
                wizardCompleted = { true },
                rpcSummary = { rpc },
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

    @Test fun dashboardWebSocket_withToken_framesApproximatelyEachPeriod() {
        // Webui stage 1: /dashboard/ws pushes the shared ticker frames the
        // dashboard patches. Crawler down + no RPC snapshot is the honest
        // empty state: `transmission` is always an object, unreachable until
        // the RPC loop lands, and the crawler entry still carries its label and
        // a logs array.
        val live = startTestServer(TOKEN, crawlerRunning = false, crawlerPort = 1, pushPeriodMs = 150)
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/dashboard/ws?token=$TOKEN")
            try {
                assertTrue("expected a 101 upgrade, got: ${ws.statusLine}", ws.upgraded)
                // (a) first frame arrives promptly and carries the daemon list.
                val first = ws.nextText(10_000)
                assertTrue("first frame did not arrive", first != null)
                val t1 = System.currentTimeMillis()
                val obj = JSONObject(first!!)
                val daemons = obj.getJSONArray("daemons")
                assertEquals(
                    DaemonService.CRAWLER_LABEL,
                    daemons.getJSONObject(0).getString("label"),
                )
                assertTrue(
                    "expected a logs array, got: ${daemons.getJSONObject(0)}",
                    daemons.getJSONObject(0).getJSONArray("logs") != null,
                )
                val tx = obj.getJSONObject("transmission")
                assertFalse(
                    "expected reachable:false until the RPC ticker lands, got: $tx",
                    tx.getBoolean("reachable"),
                )
                // (b) a second frame arrives after ~one short period.
                val second = ws.nextText(10_000)
                val t2 = System.currentTimeMillis()
                assertTrue("expected a daemons array, got: $second", second!!.contains("\"daemons\""))
                // (c) the interval really is the push period: with
                // wsPushPeriodMs = 150 a regression to seconds (e.g. 5s) would
                // still satisfy "a second frame within 10s". 1000ms is a
                // generous, CI-safe bound on a loaded machine.
                assertTrue(
                    "second frame came ${t2 - t1}ms after the first, expected ~150ms (<=1000ms)",
                    t2 - t1 <= 1_000,
                )
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
        }
    }

    /**
     * Corrective stage 10: the reachable-RPC branch of the slim `transmission`
     * object. With a non-null RPC snapshot the frame must report
     * `reachable:true` and carry the download dir plus both speed limits the
     * client patches into the transmission card. The other tests only ever
     * exercise the `cachedRpcSummary == null` (unreachable) branch.
     */
    @Test fun dashboardWebSocket_reachableTransmissionFrame() {
        val summary = TransmissionSummary(
            downloadDir = "/saf/usb/data/torrents",
            speedLimitDown = 500,
            speedLimitUp = 125,
            torrents = emptyList(),
        )
        val live = startTestServer(
            TOKEN, crawlerRunning = false, crawlerPort = 1, pushPeriodMs = 150,
            rpc = summary,
        )
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/dashboard/ws?token=$TOKEN")
            try {
                assertTrue("expected a 101 upgrade, got: ${ws.statusLine}", ws.upgraded)
                // The snapshot lands on its own ticker loop, so the first frame
                // may still be the honest empty state; wait for the frame that
                // carries it, bounded by the same 10s budget the other
                // websocket tests use.
                val deadline = System.currentTimeMillis() + 10_000
                var reached: JSONObject? = null
                while (reached == null && System.currentTimeMillis() < deadline) {
                    val frame = ws.nextText(10_000)
                    assertTrue("frame did not arrive", frame != null)
                    val candidate = JSONObject(frame!!).getJSONObject("transmission")
                    if (candidate.getBoolean("reachable")) reached = candidate
                }
                val tx = reached ?: throw AssertionError(
                    "no dashboard frame reported a reachable transmission within 10s",
                )
                assertFalse(
                    "downloadDir must be present once RPC is reachable: $tx",
                    tx.isNull("downloadDir"),
                )
                assertEquals("/saf/usb/data/torrents", tx.getString("downloadDir"))
                assertFalse("speedLimitUp must be present: $tx", tx.isNull("speedLimitUp"))
                assertFalse("speedLimitDown must be present: $tx", tx.isNull("speedLimitDown"))
                assertEquals(125, tx.getInt("speedLimitUp"))
                assertEquals(500, tx.getInt("speedLimitDown"))
                assertFalse("slim frame must not ship a full torrents array", tx.has("torrents"))
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
        }
    }

    /**
     * Webui stage 6: the unauthenticated `/dashboard/ws` upgrade must never
     * become a live socket. The route interceptor answers 401 before any
     * handshake, so there is no 101, no `Sec-WebSocket-Accept` and therefore
     * no way for a frame to reach the client; `requireWsAuth()`
     * (VIOLATED_POLICY close) is the in-session second line of defence for a
     * session that ever got past the interceptor. A missing token and a wrong
     * token must fail identically.
     */
    @Test fun dashboardWebSocket_withoutToken_rejected() {
        val live = startTestServer(TOKEN, crawlerRunning = false, crawlerPort = 1, pushPeriodMs = 150)
        live.start()
        try {
            for (path in listOf("/dashboard/ws", "/dashboard/ws?token=wrong-token")) {
                val ws = connectWithRetry(live.port, path)
                try {
                    assertFalse("$path must not upgrade: ${ws.statusLine}", ws.upgraded)
                    assertFalse(
                        "$path must never answer 101: ${ws.statusLine}",
                        ws.statusLine.contains(" 101"),
                    )
                    assertEquals("$path must be rejected: ${ws.statusLine}", 401, ws.status)
                    // No handshake acceptance header ⇒ no websocket session ⇒ no frame.
                    assertFalse(
                        "$path must not accept the handshake: ${ws.headers}",
                        ws.headers.contains("Sec-WebSocket-Accept", ignoreCase = true),
                    )
                } finally {
                    ws.close()
                }
            }
        } finally {
            live.stop()
        }
    }

    /**
     * Webui stage 6: daemon-controlled text survives the pushed frame intact.
     * A log line carrying `"`, `<`, `&` and a backslash parses back
     * byte-for-byte, which only `jsonStr` (backslash + quote escaping) can
     * produce: raw concatenation would end the string at the first quote and
     * [JSONObject] would throw on the truncated frame.
     */
    @Test fun dashboardWebSocket_escapesDaemonTextInFrame() {
        val nasty = """tx <script>alert(1)</script> & "quoted" \ back"""
        val daemon = ProcessStatus(
            label = DaemonService.CRAWLER_LABEL,
            state = ProcessState.RUNNING,
            pid = 4242,
            startTimeMs = System.currentTimeMillis() - 65_000,
            restartCount = 3,
            lastLogLines = listOf(nasty),
        )
        val live = startTestServer(
            TOKEN, crawlerRunning = true, crawlerPort = 1, pushPeriodMs = 150,
            daemons = listOf(daemon),
        )
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/dashboard/ws?token=$TOKEN")
            try {
                assertTrue("expected a 101 upgrade, got: ${ws.statusLine}", ws.upgraded)
                val frame = ws.nextText(10_000)
                assertTrue("frame did not arrive", frame != null)
                val entry = JSONObject(frame!!).getJSONArray("daemons").getJSONObject(0)
                val logs = entry.getJSONArray("logs")
                assertEquals(1, logs.length())
                assertEquals("the log line must round-trip unchanged", nasty, logs.getString(0))
                assertEquals(4242, entry.getInt("pid"))
                assertEquals(3, entry.getInt("restarts"))
                // The same two escapes jsonStr applies: backslash first, then quote.
                val escaped = nasty.replace("\\", "\\\\").replace("\"", "\\\"")
                assertTrue(
                    "expected the escaped log line in the frame: $frame",
                    frame.contains(escaped),
                )
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
        }
    }

    /**
     * Corrective stage 8: RFC 8259 forbids raw U+0000–U+001F inside a JSON
     * string. A log line carrying an ANSI ESC (0x1B) or any other control byte
     * used to make the dashboard client's `JSON.parse` throw, so the frame was
     * dropped and the live feed froze under a green `#live` chip. The frame
     * must stay parseable, carry no raw control byte, and the line must come
     * back byte-for-byte.
     */
    @Test fun dashboardWebSocket_controlBytesInLogLinesStillParse() {
        val ansi = "\u001b[31merror\u001b[0m\r\n\tprogress 50%\u0000\u0007"
        val daemon = ProcessStatus(
            label = DaemonService.CRAWLER_LABEL,
            state = ProcessState.RUNNING,
            pid = 4242,
            startTimeMs = System.currentTimeMillis() - 65_000,
            restartCount = 3,
            lastLogLines = listOf(ansi),
        )
        val live = startTestServer(
            TOKEN, crawlerRunning = true, crawlerPort = 1, pushPeriodMs = 150,
            daemons = listOf(daemon),
        )
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/dashboard/ws?token=$TOKEN")
            try {
                assertTrue("expected a 101 upgrade, got: ${ws.statusLine}", ws.upgraded)
                val frame = ws.nextText(10_000)
                assertTrue("frame did not arrive", frame != null)
                val logs = JSONObject(frame!!)
                    .getJSONArray("daemons").getJSONObject(0).getJSONArray("logs")
                assertEquals(1, logs.length())
                assertEquals("control bytes must round-trip unchanged", ansi, logs.getString(0))
                assertTrue(
                    "no raw control byte may reach the wire: $frame",
                    Regex("[\\x00-\\x1f]").find(frame) == null,
                )
            } finally {
                ws.close()
            }
        } finally {
            live.stop()
        }
    }

    /**
     * Webui stage 6: the exact frame shape the dashboard client patches
     * against — `service`, `usb`, `wifiUp`, `ftp`, one `daemons[]` entry per
     * daemon with `label/state/pid/uptime/restarts/logs`, the crawler extras,
     * and the slim `transmission` object with `reachable:false` plus null
     * scalars while no RPC snapshot has landed (`cachedRpcSummary == null`), so
     * the client keeps the server-rendered downloads block untouched.
     */
    @Test fun dashboardWebSocket_frameCarriesTheDashboardShape() {
        val daemon = ProcessStatus(
            label = DaemonService.CRAWLER_LABEL,
            state = ProcessState.RUNNING,
            pid = 77,
            startTimeMs = System.currentTimeMillis() - 65_000,
            restartCount = 4,
            lastLogLines = listOf("line one", "line two"),
        )
        val live = startTestServer(
            TOKEN, crawlerRunning = true, crawlerPort = 1, pushPeriodMs = 150,
            daemons = listOf(daemon),
        )
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/dashboard/ws?token=$TOKEN")
            try {
                assertTrue("expected a 101 upgrade, got: ${ws.statusLine}", ws.upgraded)
                val frame = ws.nextText(10_000)
                assertTrue("frame did not arrive", frame != null)
                val obj = JSONObject(frame!!)
                // service
                val service = obj.getJSONObject("service")
                assertEquals("Running", service.getString("state"))
                assertFalse(service.getBoolean("rolledBack"))
                // usb / wifi / ftp
                val usb = obj.getJSONObject("usb")
                assertFalse(usb.getBoolean("granted"))
                assertTrue("no free-space snapshot yet", usb.isNull("freeBytes"))
                assertFalse(obj.getBoolean("wifiUp"))
                val ftp = obj.getJSONObject("ftp")
                for (key in listOf(
                    "running", "state", "pid", "restarts", "port", "url", "wifiOnly",
                )) {
                    assertTrue("ftp is missing $key: $ftp", ftp.has(key))
                }
                // daemons[] — every field the client patches
                val daemons = obj.getJSONArray("daemons")
                assertEquals(1, daemons.length())
                val d = daemons.getJSONObject(0)
                for (key in listOf("label", "state", "pid", "uptime", "restarts", "logs")) {
                    assertTrue("daemon entry is missing $key: $d", d.has(key))
                }
                assertEquals(DaemonService.CRAWLER_LABEL, d.getString("label"))
                assertEquals("RUNNING", d.getString("state"))
                assertEquals(77, d.getInt("pid"))
                assertEquals(4, d.getInt("restarts"))
                assertTrue(
                    "uptime must be a server-formatted string, got: ${d.get("uptime")}",
                    Regex("\\d+m \\d{2}s").containsMatchIn(d.getString("uptime")),
                )
                assertEquals(2, d.getJSONArray("logs").length())
                for (key in listOf(
                    "dbSize", "internalFree", "internalFreeBytes", "pausedNoWifi", "statsLine",
                )) {
                    assertTrue("crawler entry is missing $key: $d", d.has(key))
                }
                // No RPC tick has landed: the slim transmission object is
                // always present but reports unreachable with null scalars.
                val tx = obj.getJSONObject("transmission")
                assertFalse(
                    "transmission must report unreachable until the RPC ticker lands, got: $frame",
                    tx.getBoolean("reachable"),
                )
                for (key in listOf("downloadDir", "speedLimitUp", "speedLimitDown")) {
                    assertTrue(
                        "$key must be null without an RPC snapshot: $tx",
                        tx.isNull(key),
                    )
                }
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
                ws.sendText("""{"id":9,"type":"browse","category":"tv","page":1}""")
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

    @Test fun searchWebSocket_legacySortKey_toleratedAsUnknown() {
        // webui-ux stage 6: `sort` is gone from the protocol, but a cached older
        // page may still send it; the unknown key is ignored and the reply stays ok.
        val upstream = startStatsUpstream("""{"results":[],"has_more":false}""")
        val live = startTestServer(
            TOKEN, crawlerRunning = true, crawlerPort = upstream.localPort,
            pushPeriodMs = 10_000, searchReadTimeoutMs = 15_000,
        )
        live.start()
        try {
            val ws = connectWithRetry(live.port, "/search/ws?token=$TOKEN")
            try {
                ws.sendText("""{"id":10,"type":"browse","category":"tv","sort":"peers","page":1}""")
                val reply = ws.nextText(10_000)
                assertTrue("expected a reply, got none", reply != null)
                assertTrue("expected ok despite the stray sort key, got: $reply",
                    reply!!.contains("\"ok\":true"))
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
