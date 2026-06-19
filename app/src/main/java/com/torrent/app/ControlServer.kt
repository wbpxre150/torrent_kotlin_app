package com.torrent.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

class ControlServer(
    private val tokenManager: TokenManager,
    private val serviceState: StateFlow<ServiceState>,
    private val daemonStatuses: () -> List<ProcessStatus>,
    private val safStatus: () -> com.torrent.app.storage.SafStatus,
    private val runSafValidation: suspend () -> com.torrent.app.storage.SafValidationResult,
    private val port: Int = 8090
) {
    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                // No-auth health check
                get("/health") {
                    call.respondText("ok", ContentType.Text.Plain)
                }

                // All other routes require token
                route("/") {
                    intercept(ApplicationCallPipeline.Plugins) {
                        val token = call.request.queryParameters["token"]
                            ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")
                        if (token == null || !tokenManager.validate(token)) {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                "Missing or invalid token. Append ?token=... or set Authorization: Bearer ..."
                            )
                            finish()
                        }
                    }

                    get {
                        call.respondText(buildStatusPage(), ContentType.Text.Html)
                    }

                    get("saf/status") {
                        val s = safStatus()
                        val json = buildString {
                            append("{")
                            append("\"granted\":").append(s.granted).append(",")
                            append("\"treeUri\":").append(jsonStr(s.treeUri)).append(",")
                            append("\"rootSummary\":").append(jsonStr(s.rootSummary))
                            append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }

                    post("saf/validate") {
                        val r = runSafValidation()
                        val json = buildString {
                            append("{")
                            append("\"ok\":").append(r.ok).append(",")
                            append("\"message\":").append(jsonStr(r.message)).append(",")
                            append("\"throughputMBs\":").append(r.throughputMBs ?: "null")
                            append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    private fun buildStatusPage(): String {
        val state = serviceState.value
        val daemons = daemonStatuses()

        val daemonRows = if (daemons.isEmpty()) {
            "<tr><td colspan=\"5\"><em>No daemons configured.</em></td></tr>"
        } else {
            daemons.joinToString("\n") { d ->
                val now = System.currentTimeMillis()
                val uptime = if (d.startTimeMs != null) formatUptime(now - d.startTimeMs) else "—"
                val logs = d.lastLogLines.takeLast(30).joinToString("<br>") { it
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                }
                """
                <tr>
                    <td><strong>${d.label}</strong></td>
                    <td><span class="state ${d.state}">${d.state}</span></td>
                    <td>${d.pid ?: "—"}</td>
                    <td>$uptime</td>
                    <td>${d.restartCount}</td>
                </tr>
                <tr><td colspan="5"><pre class="loglines">$logs</pre></td></tr>
                """.trimIndent()
            }
        }

        return """
            <!DOCTYPE html>
            <html>
            <head><title>Torrent App — Control</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { font-family: system-ui, sans-serif; max-width: 800px; margin: 2em auto;
                       padding: 0 1em; background: #f5f5f5; }
                h1 { margin-bottom: 0.25em; }
                .state { padding: 2px 8px; border-radius: 4px; font-size: 0.85em; font-weight: 600; }
                .RUNNING { background: #d4edda; color: #155724; }
                .STARTING { background: #fff3cd; color: #856404; }
                .STOPPING { background: #f8d7da; color: #721c24; }
                .STOPPED { background: #e2e3e5; color: #383d41; }
                table { width: 100%; border-collapse: collapse; margin: 1em 0; background: #fff;
                        border-radius: 6px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                th { background: #eee; padding: 8px 12px; text-align: left; font-size: 0.85em;
                     text-transform: uppercase; letter-spacing: 0.05em; }
                td { padding: 8px 12px; border-top: 1px solid #eee; vertical-align: top; }
                pre.loglines { margin: 4px 0 0 0; padding: 8px; background: #fafafa;
                               border-radius: 4px; font-size: 0.8em; max-height: 200px;
                               overflow-y: auto; white-space: pre-wrap; word-break: break-all; }
                .meta { color: #666; font-size: 0.9em; }
            </style></head>
            <body>
                <h1>Torrent App Control</h1>
                <p class="meta">Service: <span class="state $state">$state</span></p>
                <h2>Daemons</h2>
                <table>
                    <tr><th>Name</th><th>State</th><th>PID</th><th>Uptime</th><th>Restarts</th></tr>
                    $daemonRows
                </table>
            </body>
            </html>
        """.trimIndent()
    }
    private fun formatUptime(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (hours > 0) "%dh %02dm %02ds".format(hours, minutes, seconds)
        else "%dm %02ds".format(minutes, seconds)
    }
    private fun jsonStr(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
