package com.torrent.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.StateFlow
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class ControlServer(
    private val tokenManager: TokenManager,
    private val serviceState: StateFlow<ServiceState>,
    private val daemonStatuses: () -> List<ProcessStatus>,
    private val safStatus: () -> com.torrent.app.storage.SafStatus,
    private val runSafValidation: suspend () -> com.torrent.app.storage.SafValidationResult,
    private val ftpStatus: () -> com.torrent.app.ftp.FtpStatus,
    private val loadConfig: () -> com.torrent.app.config.AppConfig,
    private val wizardCompleted: () -> Boolean,
    private val rpcSummary: () -> com.torrent.app.transmission.TransmissionSummary?,
    private val usbFreeSpace: () -> Long?,
    private val lastApplyRolledBack: () -> Boolean,
    private val restartDaemon: (String) -> Boolean,
    private val stopDaemon: (String) -> Boolean,
    private val startDaemon: (String) -> Boolean,
    private val port: Int = 8090,
) {
    @Volatile private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/health") { call.respondText("ok", ContentType.Text.Plain) }

                // Login (no auth)
                get("/login") { call.respondText(buildLoginPage(error = false), ContentType.Text.Html) }
                post("/login") {
                    val form = parseForm(call.receiveText())
                    val token = form["token"] ?: ""
                    if (token.isNotEmpty() && tokenManager.validate(token)) {
                        call.response.headers.append(
                            "Set-Cookie", "control_token=$token; Path=/; HttpOnly; SameSite=Strict")
                        call.respondRedirect("/")
                    } else {
                        call.respondText(buildLoginPage(error = true), ContentType.Text.Html)
                    }
                }

                // Everything below requires a valid token (cookie > query > header)
                route("/") {
                    intercept(ApplicationCallPipeline.Plugins) {
                        val token = parseCookieToken(call.request.headers["Cookie"])
                            ?: call.request.queryParameters["token"]
                            ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")
                        if (token == null || !tokenManager.validate(token)) {
                            call.respond(HttpStatusCode.Unauthorized,
                                "Missing or invalid token. Open /login or append ?token=...")
                            finish()
                        }
                    }

                    get {
                        val html = if (wizardCompleted()) buildDashboard()
                                   else buildSetupGate()
                        call.respondText(html, ContentType.Text.Html)
                    }

                    get("api/status") { call.respondText(buildStatusJson(), ContentType.Application.Json) }
                    get("downloads") { call.respondText(buildDownloadsJson(), ContentType.Application.Json) }

                    post("daemon/{label}/{action}") {
                        val label = call.parameters["label"] ?: ""
                        val action = call.parameters["action"] ?: ""
                        if (label.isNotEmpty()) {
                            when (action) {
                                "start" -> startDaemon(label)
                                "stop" -> stopDaemon(label)
                                "restart" -> restartDaemon(label)
                            }
                        }
                        val token = parseCookieToken(call.request.headers["Cookie"])
                            ?: call.request.queryParameters["token"] ?: ""
                        val qs = if (token.isNotEmpty()) "?token=$token" else ""
                        call.respondRedirect("/$qs")
                    }

                    get("saf/status") {
                        val s = safStatus()
                        val json = buildString {
                            append("{\"granted\":").append(s.granted)
                            append(",\"treeUri\":").append(jsonStr(s.treeUri))
                            append(",\"rootSummary\":").append(jsonStr(s.rootSummary)).append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }
                    post("saf/validate") {
                        val r = runSafValidation()
                        val json = buildString {
                            append("{\"ok\":").append(r.ok)
                            append(",\"message\":").append(jsonStr(r.message))
                            append(",\"throughputMBs\":").append(r.throughputMBs ?: "null").append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }
                    get("ftp/status") {
                        val f = ftpStatus()
                        val json = buildString {
                            append("{\"running\":").append(f.running)
                            append(",\"state\":").append(jsonStr(f.state))
                            append(",\"ip\":").append(jsonStr(f.ip))
                            append(",\"port\":").append(f.port)
                            append(",\"username\":").append(jsonStr(f.username))
                            append(",\"password\":").append(jsonStr(f.password))
                            append(",\"wifiUp\":").append(f.wifiUp).append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    fun stop() { server?.stop(1000, 2000); server = null }

    // ── pages ───────────────────────────────────────────────────────────────

    private fun buildSetupGate(): String = """
        <!DOCTYPE html><html><head><title>Torrent App — Setup required</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:system-ui,sans-serif;max-width:640px;margin:2em auto;padding:0 1em}
        .box{background:#fff3cd;border:1px solid #ffeeba;border-radius:6px;padding:1em}</style></head>
        <body><h1>Setup required</h1>
        <div class="box"><p>Open the <strong>Torrent App</strong> on this Android device and complete
        the first-run wizard (grant the USB drive, set Transmission and FTP credentials).</p>
        <p>This control page becomes available once setup is finished.</p></div>
        <p><a href="/login">Log in</a></p></body></html>
    """.trimIndent()

    private fun buildLoginPage(error: Boolean): String = """
        <!DOCTYPE html><html><head><title>Torrent App — Login</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:system-ui,sans-serif;max-width:420px;margin:2em auto;padding:0 1em}
        input{width:100%;padding:8px;margin:8px 0;box-sizing:border-box}
        .err{color:#721c24}</style></head>
        <body><h1>Login</h1>
        ${if (error) """<p class="err">Invalid token.</p>""" else ""}
        <form method="post" action="/login">
        <label>Control token:<input type="password" name="token" autofocus></label>
        <button type="submit">Log in</button>
        </form>
        <p class="meta">The token is shown in the Android app's main screen.</p></body></html>
    """.trimIndent()

    private fun buildDashboard(): String {
        val state = serviceState.value
        val daemons = daemonStatuses()
        val cfg = loadConfig()
        val usb = usbFreeSpace()
        val rpc = rpcSummary()

        val daemonRows = if (daemons.isEmpty()) {
            "<tr><td colspan=\"6\"><em>No daemons configured.</em></td></tr>"
        } else daemons.joinToString("\n") { d ->
            val now = System.currentTimeMillis()
            val uptime = if (d.startTimeMs != null) formatUptime(now - d.startTimeMs) else "—"
            val logs = d.lastLogLines.takeLast(30).joinToString("<br>") {
                it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            }
            val escapedLabel = d.label.replace("\"", "&quot;")
            """
            <tr>
              <td><strong>${d.label}</strong></td>
              <td><span class="state ${d.state}">${d.state}</span></td>
              <td>${d.pid ?: "—"}</td><td>$uptime</td><td>${d.restartCount}</td>
              <td class="actions">
                <form method="post" action="/daemon/$escapedLabel/start"><button>S</button></form>
                <form method="post" action="/daemon/$escapedLabel/stop"><button>X</button></form>
                <form method="post" action="/daemon/$escapedLabel/restart"><button>R</button></form>
              </td>
            </tr>
            <tr><td colspan="6"><pre class="loglines">$logs</pre></td></tr>
            """.trimIndent()
        }

        val rollbackBanner = if (lastApplyRolledBack())
            """<div class="warn">Transmission config was rolled back after a crash-loop. Review settings.</div>"""
        else ""

        val usbSection = buildString {
            append("<p class=\"meta\">Granted: <strong>").append(safStatus().granted).append("</strong>")
            append(" &nbsp; Free: <strong>").append(usb?.let { formatBytes(it) } ?: "unknown").append("</strong></p>")
        }

        val downloadsSection = if (rpc == null) {
            "<p class=\"meta\">Transmission RPC not reachable.</p>"
        } else {
            buildString {
                append("<table><tr><th>Name</th><th>Done</th><th>Down</th><th>Up</th><th>Size</th></tr>")
                if (rpc.torrents.isEmpty()) append("<tr><td colspan=\"5\"><em>No torrents.</em></td></tr>")
                for (t in rpc.torrents) {
                    append("<tr><td>").append(esc(t.name)).append("</td>")
                    append("<td>").append("%.0f%%".format(t.percentDone * 100)).append("</td>")
                    append("<td>").append(formatRate(t.rateDownload)).append("</td>")
                    append("<td>").append(formatRate(t.rateUpload)).append("</td>")
                    append("<td>").append(formatBytes(t.sizeWhenDone)).append("</td></tr>")
                }
                append("</table>")
                append("<p class=\"meta\">Download dir: <code>").append(esc(rpc.downloadDir)).append("</code>")
                append(" &nbsp; Limits: ↓").append(rpc.speedLimitDown).append(" ↑").append(rpc.speedLimitUp)
                append(" KB/s (0 = unlimited)</p>")
            }
        }

        val txLink = "http://${ftpStatus().ip ?: "127.0.0.1"}:9091/"

        return """
            <!DOCTYPE html><html><head><title>Torrent App — Control</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{font-family:system-ui,sans-serif;max-width:820px;margin:2em auto;padding:0 1em;background:#f5f5f5}
              h1{margin-bottom:.25em} h2{margin-top:1.5em}
              .state{padding:2px 8px;border-radius:4px;font-size:.85em;font-weight:600}
              .RUNNING{background:#d4edda;color:#155724}.STARTING{background:#fff3cd;color:#856404}
              .STOPPING{background:#f8d7da;color:#721c24}.STOPPED{background:#e2e3e5;color:#383d41}
              table{width:100%;border-collapse:collapse;margin:1em 0;background:#fff;border-radius:6px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.1)}
              th{background:#eee;padding:8px 12px;text-align:left;font-size:.85em;text-transform:uppercase;letter-spacing:.05em}
              td{padding:8px 12px;border-top:1px solid #eee;vertical-align:top}
              pre.loglines{margin:4px 0 0 0;padding:8px;background:#fafafa;border-radius:4px;font-size:.8em;max-height:200px;overflow-y:auto;white-space:pre-wrap;word-break:break-all}
              .meta{color:#666;font-size:.9em}.warn{background:#f8d7da;color:#721c24;padding:.6em;border-radius:4px;margin:1em 0}
              .actions form{display:inline;margin:0}.actions button{padding:0 6px;margin:0 2px}
              a.cfg{display:inline-block;margin:.5em .5em .5em 0;padding:.4em .8em;background:#fff;border-radius:4px;text-decoration:none;border:1px solid #ddd}
            </style></head><body>
            <h1>Torrent App Control</h1>
            <p class="meta">Service: <span class="state $state">$state</span>$rollbackBanner</p>
            <p>
              <a class="cfg" href="/settings/transmission">Transmission settings</a>
              <a class="cfg" href="/settings/ftp">FTP settings</a>
              <a class="cfg" href="/settings/storage">Storage</a>
              <a class="cfg" href="/settings/crawler" aria-disabled="true">Crawler (soon)</a>
              <a class="cfg" href="/settings/rclone" aria-disabled="true">Backups (soon)</a>
            </p>
            <h2>Daemons</h2>
            <table><tr><th>Name</th><th>State</th><th>PID</th><th>Uptime</th><th>Restarts</th><th>Control</th></tr>
            $daemonRows</table>
            <h2>Transmission</h2>
            <p class="meta">RPC: <a href="$txLink" target="_blank">$txLink</a> (user: ${esc(cfg.transmission.username)})</p>
            $downloadsSection
            <h2>USB Storage</h2>
            $usbSection
            <h2>FTP Server (wifi only)</h2>
            ${ftpStatusSection()}
            </body></html>
        """.trimIndent()
    }

    private fun buildStatusJson(): String {
        val daemons = daemonStatuses()
        val usb = usbFreeSpace()
        val f = ftpStatus()
        val s = safStatus()
        return buildString {
            append("{\"service\":\"").append(serviceState.value.toString()).append("\"")
            append(",\"wizardCompleted\":").append(wizardCompleted())
            append(",\"rolledBack\":").append(lastApplyRolledBack())
            append(",\"usb\":{\"granted\":").append(s.granted).append(",\"freeBytes\":").append(usb ?: "null").append("}")
            append(",\"wifiUp\":").append(f.wifiUp)
            append(",\"ftp\":{\"running\":").append(f.running).append(",\"port\":").append(f.port).append("}")
            append(",\"daemons\":[")
            append(daemons.joinToString(",") { d ->
                "{\"label\":\"" + d.label + "\",\"state\":\"" + d.state + "\",\"pid\":" + (d.pid ?: "null") +
                ",\"restarts\":" + d.restartCount + "}"
            })
            append("]}")
        }
    }

    private fun buildDownloadsJson(): String {
        val rpc = rpcSummary() ?: return "{\"reachable\":false}"
        return buildString {
            append("{\"reachable\":true,\"downloadDir\":").append(jsonStr(rpc.downloadDir))
            append(",\"speedLimitDown\":").append(rpc.speedLimitDown)
            append(",\"speedLimitUp\":").append(rpc.speedLimitUp)
            append(",\"torrents\":[")
            append(rpc.torrents.joinToString(",") { t ->
                "{\"name\":" + jsonStr(t.name) + ",\"percentDone\":" + t.percentDone +
                ",\"rateDownload\":" + t.rateDownload + ",\"rateUpload\":" + t.rateUpload +
                ",\"status\":" + t.status + ",\"sizeWhenDone\":" + t.sizeWhenDone + "}"
            })
            append("]}")
        }
    }

    private fun ftpStatusSection(): String {
        val f = ftpStatus()
        val url = if (f.running && f.ip != null && f.password != null)
            "ftp://${f.username}:${f.password}@${f.ip}:${f.port}/" else "—"
        return """
            <p class="meta">State: <span class="state ${f.state}">${f.state}</span>
               &nbsp; wifi: <strong>${if (f.wifiUp) "up" else "down"}</strong></p>
            <p>URL: <code>$url</code></p>
        """.trimIndent()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun formatUptime(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
    }
    private fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
        else -> "$b B"
    }
    private fun formatRate(bps: Long): String =
        if (bps <= 0) "—" else "%.1f KB/s".format(bps.toDouble() / 1024)
    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun jsonStr(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        internal fun parseForm(body: String): Map<String, String> {
            if (body.isEmpty()) return emptyMap()
            return body.split("&").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null
                else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                     URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.toMap()
        }
        internal fun parseCookieToken(cookieHeader: String?): String? {
            if (cookieHeader == null) return null
            for (part in cookieHeader.split(';')) {
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq).trim() == "control_token")
                    return part.substring(eq + 1).trim()
            }
            return null
        }
    }
}
