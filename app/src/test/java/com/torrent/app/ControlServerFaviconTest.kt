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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * Live routing test for /favicon.ico: every browser requests it on every
 * page (the unauthenticated /login included), and before the route existed
 * each request 404'd in the console. Pins the route contract: no token
 * required (the icon is a static, non-sensitive asset like /health), the
 * image/png content type, and the exact embedded bytes.
 */
@RunWith(RobolectricTestRunner::class)
class ControlServerFaviconTest {

    private companion object {
        const val TOKEN = "unit-test-token"
    }

    @Test fun favicon_servedWithoutToken_asImagePng() {
        val port = freePort()
        val control = newServer(port)
        control.start()
        try {
            val conn = connectWithRetry(port)
            try {
                assertEquals(200, conn.responseCode)
                assertEquals("image/png", conn.contentType)
                val body = conn.inputStream.readBytes()
                assertTrue(
                    "expected PNG magic, got ${body.take(8).toList()}",
                    body.size >= 8 && body[0] == 0x89.toByte() && body[1] == 'P'.code.toByte(),
                )
                assertArrayEquals(ControlServer.FAVICON_PNG, body)
            } finally {
                conn.disconnect()
            }
        } finally {
            control.stop()
        }
    }

    // ── plumbing (same wiring as ControlServerWebSocketTest.startTestServer) ──

    private fun newServer(port: Int): ControlServer {
        val daemon = ProcessStatus(
            label = DaemonService.CRAWLER_LABEL,
            state = ProcessState.STOPPED,
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
        return ControlServer(
            tokenManager = tokenManagerWith(TOKEN),
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
            port = port,
        )
    }

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

    /** GET /favicon.ico with a short retry so the CIO listener has a beat to come up. */
    private fun connectWithRetry(port: Int): HttpURLConnection {
        var last: Exception? = null
        repeat(40) {
            try {
                val conn = URL("http://127.0.0.1:$port/favicon.ico")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                if (conn.responseCode in 200..299) return conn
                return conn
            } catch (e: ConnectException) {
                last = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("server never came up on port $port", last)
    }
}
