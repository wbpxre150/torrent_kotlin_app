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
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import com.torrent.app.config.TransmissionApplyMode
import com.torrent.app.config.CrawlerKeys
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.BackupSettings
import com.torrent.app.migration.MigrationPhase
import com.torrent.app.migration.MigrationStatus

class ControlServer(
    private val tokenManager: TokenManager,
    private val serviceState: StateFlow<ServiceState>,
    private val daemonStatuses: () -> List<ProcessStatus>,
    private val safStatus: () -> com.torrent.app.storage.SafStatus,
    private val wifiIp: () -> String?,
    private val ftpStatus: () -> com.torrent.app.ftp.FtpStatus,
    private val loadConfig: () -> com.torrent.app.config.AppConfig,
    private val wizardCompleted: () -> Boolean,
    private val rpcSummary: () -> com.torrent.app.transmission.TransmissionSummary?,
    private val usbFreeSpace: () -> Long?,
    private val lastApplyRolledBack: () -> Boolean,
    private val restartDaemon: (String) -> Boolean,
    private val stopDaemon: (String) -> Boolean,
    private val startDaemon: (String) -> Boolean,
    private val applyTransmission: (com.torrent.app.config.AppConfig, Boolean) -> com.torrent.app.config.ApplyResult,
    private val applyFtp: (com.torrent.app.config.FtpSettings) -> com.torrent.app.config.ApplyResult,
    // Crawler (plan 10 step 2): wired by DaemonService; the UI arrives in step 3.
    private val applyCrawler: (com.torrent.app.config.CrawlerSettings) -> com.torrent.app.config.ApplyResult =
        { com.torrent.app.config.ApplyResult(false, "crawler apply is not wired", false) },
    // App-side backup/bootstrap settings (Stage 2): carried by the crawler
    // panel form; the save is a plain store write (no daemon is touched).
    private val saveBackup: (com.torrent.app.config.BackupSettings) -> com.torrent.app.config.ApplyResult =
        { com.torrent.app.config.ApplyResult(false, "backup settings are not wired", false) },
    private val crawlerApplyStatus: () -> CrawlerApplyStatus = { CrawlerApplyStatus() },
    private val crawlerPausedNoWifi: () -> Boolean = { false },
    private val crawlerHttpPort: () -> Int = { 8081 },
    private val crawlerDbSize: () -> Long? = { null },
    private val internalFreeSpace: () -> Long = { 0L },
    // Maintenance (plan 10 step 5): the POST route queues an action by name and
    // the GET route polls its status (incl. output lines since an offset).
    private val crawlerMaintenanceStart: (String) -> String = { "maintenance is not wired" },
    private val crawlerMaintenanceStatus: () -> MaintenanceStatus = { MaintenanceStatus() },
    // Migration (plan 10 step 7): the POST route queues an action by name and
    // the GET route polls the coordinator's status (progress, verify log, diff).
    private val crawlerMigrationStart: (String) -> String = { "migration is not wired" },
    private val crawlerMigrationStatus: () -> MigrationStatus = { MigrationStatus() },
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

                // Everything below requires a valid token (cookie > query > header).
                // A valid query-string token also sets the cookie so the browser keeps
                // the session across link navigations (POST /login is the other entry).
                route("/") {
                    intercept(ApplicationCallPipeline.Plugins) {
                        val (token, setCookie) = resolveAuthToken(
                            call.request.headers["Cookie"],
                            call.request.queryParameters["token"],
                            call.request.headers["Authorization"],
                            tokenManager::validate,
                        )
                        if (token == null) {
                            call.respond(HttpStatusCode.Unauthorized,
                                "Missing or invalid token. Open /login or append ?token=...")
                            finish()
                            return@intercept
                        }
                        if (setCookie) {
                            call.response.headers.append(
                                "Set-Cookie",
                                "control_token=$token; Path=/; HttpOnly; SameSite=Strict")
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
                            append(",\"gatewayPresent\":").append(s.gatewayPresent)
                            append(",\"treeUri\":").append(jsonStr(s.treeUri))
                            append(",\"rootSummary\":").append(jsonStr(s.rootSummary)).append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }
                    get("ftp/status") {
                        val f = ftpStatus()
                        val json = buildString {
                            append("{\"running\":").append(f.running)
                            append(",\"state\":").append(jsonStr(f.state))
                            append(",\"pid\":").append(f.pid ?: "null")
                            append(",\"restartCount\":").append(f.restartCount)
                            append(",\"ip\":").append(jsonStr(f.ip))
                            append(",\"port\":").append(f.port)
                            append(",\"username\":").append(jsonStr(f.username))
                            append(",\"password\":").append(jsonStr(f.password))
                            append(",\"wifiUp\":").append(f.wifiUp).append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }
                    get("settings/transmission") {
                        val msg = call.request.queryParameters["msg"]
                        val ok = call.request.queryParameters["ok"]?.toBoolean() ?: false
                        call.respondText(buildTransmissionPanel(msg, ok), ContentType.Text.Html)
                    }
                    post("settings/transmission") {
                        val form = parseForm(call.receiveText())
                        val currentCfg = loadConfig()
                        val current = currentCfg.transmission
                        val password = form["password"]?.takeIf { it.isNotEmpty() } ?: current.password
                        val s = com.torrent.app.config.TransmissionSettings(
                            username = form["username"] ?: current.username,
                            password = password,
                            peerPort = form["peerPort"]?.toIntOrNull() ?: current.peerPort,
                            speedLimitDown = form["speedLimitDown"]?.toIntOrNull() ?: current.speedLimitDown,
                            speedLimitUp = form["speedLimitUp"]?.toIntOrNull() ?: current.speedLimitUp,
                            altSpeedDown = form["altSpeedDown"]?.toIntOrNull() ?: current.altSpeedDown,
                            altSpeedUp = form["altSpeedUp"]?.toIntOrNull() ?: current.altSpeedUp,
                        )
                        val newCfg = currentCfg.copy(
                            transmission = s,
                            dataSubpath = form["dataSubpath"] ?: currentCfg.dataSubpath,
                        )
                        val liveOnly = !TransmissionApplyMode.needsRestart(currentCfg, newCfg)
                        val r = applyTransmission(newCfg, liveOnly)
                        val token = parseCookieToken(call.request.headers["Cookie"]) ?: call.request.queryParameters["token"] ?: ""
                        val mode = if (liveOnly) "live+RPC" else "restart"
                        call.respondRedirect("/settings/transmission?msg=${urlEnc(r.message + " ($mode)")}&ok=${r.ok}&token=$token")
                    }

                    get("settings/ftp") {
                        val msg = call.request.queryParameters["msg"]
                        val ok = call.request.queryParameters["ok"]?.toBoolean() ?: false
                        call.respondText(buildFtpPanel(msg, ok), ContentType.Text.Html)
                    }
                    post("settings/ftp") {
                        val form = parseForm(call.receiveText())
                        val current = loadConfig().ftp
                        val password = form["password"]?.takeIf { it.isNotEmpty() } ?: current.password
                        val s = com.torrent.app.config.FtpSettings(
                            username = form["username"] ?: current.username,
                            password = password,
                            port = form["port"]?.toIntOrNull() ?: current.port,
                            wifiOnly = form.containsKey("wifiOnly"),
                            maxDownloadKiBps = form["maxDownloadKiBps"]?.toIntOrNull() ?: current.maxDownloadKiBps,
                        )
                        val r = applyFtp(s)
                        val token = parseCookieToken(call.request.headers["Cookie"]) ?: call.request.queryParameters["token"] ?: ""
                        call.respondRedirect("/settings/ftp?msg=${urlEnc(r.message)}&ok=${r.ok}&token=$token")
                    }

                    get("settings/storage") {
                        call.respondText(buildStoragePanel(), ContentType.Text.Html)
                    }
                    get("settings/crawler") {
                        val msg = call.request.queryParameters["msg"]
                        val ok = call.request.queryParameters["ok"]?.toBoolean() ?: false
                        val rebuildBloom = call.request.queryParameters["rebuildBloom"]?.toBoolean() ?: false
                        call.respondText(buildCrawlerPanel(msg, ok, rebuildBloom), ContentType.Text.Html)
                    }
                    post("settings/crawler") {
                        val form = parseForm(call.receiveText())
                        val currentCfg = loadConfig()
                        val current = currentCfg.crawler
                        val s = crawlerSettingsFromForm(form, current)
                        val backup = backupSettingsFromForm(form, currentCfg.backup)
                        val bloomChanged = current.bloomCapacity != s.bloomCapacity ||
                            current.failureBloomCapacity != s.failureBloomCapacity ||
                            current.bloomErrorRate != s.bloomErrorRate
                        val r = applyCrawler(s)
                        val rb = saveBackup(backup)
                        val token = parseCookieToken(call.request.headers["Cookie"]) ?: call.request.queryParameters["token"] ?: ""
                        val msg = if (r.ok && rb.ok) r.message else "${r.message} / backup: ${rb.message}"
                        val rebuild = if (bloomChanged) "&rebuildBloom=true" else ""
                        call.respondRedirect("/settings/crawler?msg=${urlEnc(msg)}&ok=${r.ok && rb.ok}&token=$token$rebuild")
                    }
                    get("api/crawler/apply-status") {
                        val st = crawlerApplyStatus()
                        val json = buildString {
                            append("{\"phase\":\"").append(st.phase.name).append("\"")
                            append(",\"message\":").append(jsonStr(st.message))
                            append(",\"logTail\":[").append(st.logTail.joinToString(",") { jsonStr(it) }).append("]")
                            .append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }
                    get("api/crawler/stats") {
                        val crawler = daemonStatuses()
                            .firstOrNull { it.label == DaemonService.CRAWLER_LABEL }
                        val body = if (crawler?.state == ProcessState.RUNNING) fetchCrawlerStats(crawlerHttpPort()) else null
                        call.respondText(crawlerStatsJson(body, crawler?.state), ContentType.Application.Json)
                    }

                    // Maintenance (plan 10 step 4): queue a one-shot action by name.
                    // The page confirms via JS confirm(); the action stops the
                    // crawler, runs, and restarts it — all off the request thread.
                    post("crawler/maintenance/{action}") {
                        val action = call.parameters["action"] ?: ""
                        val msg = crawlerMaintenanceStart(action)
                        val ok = !msg.startsWith("refused") && !msg.startsWith("unknown")
                        val token = parseCookieToken(call.request.headers["Cookie"])
                            ?: call.request.queryParameters["token"] ?: ""
                        call.respondRedirect("/settings/crawler?msg=${urlEnc(msg)}&ok=$ok&token=$token")
                    }

                    // Maintenance status JSON; `?offset=` returns only output
                    // lines appended since that index (for incremental polling).
                    get("api/crawler/maintenance") {
                        val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                        val st = crawlerMaintenanceStatus()
                        val lines = st.lines
                        val newLines = if (offset < lines.size) lines.subList(offset, lines.size) else emptyList()
                        val json = buildString {
                            append("{\"action\":").append(jsonStr(st.action?.route))
                            append(",\"running\":").append(st.running)
                            append(",\"exitCode\":").append(st.exitCode?.toString() ?: "null")
                            append(",\"startedAt\":").append(st.startedAt?.toString() ?: "null")
                            append(",\"finishedAt\":").append(st.finishedAt?.toString() ?: "null")
                            append(",\"message\":").append(jsonStr(st.message))
                            append(",\"totalLines\":").append(lines.size)
                            append(",\"lines\":[").append(newLines.joinToString(",") { jsonStr(it) }).append("]")
                            .append("}")
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }

                    // Migration (plan 10 step 7): queue a migration action by
                    // name; the long work runs on the crawler-control executor
                    // and the page polls /api/crawler/migration for progress.
                    post("crawler/migration/{action}") {
                        val action = call.parameters["action"] ?: ""
                        val msg = crawlerMigrationStart(action)
                        val ok = !msg.startsWith("refused") && !msg.startsWith("unknown")
                        val token = parseCookieToken(call.request.headers["Cookie"])
                            ?: call.request.queryParameters["token"] ?: ""
                        call.respondRedirect("/settings/crawler?msg=${urlEnc(msg)}&ok=$ok&token=$token")
                    }

                    get("api/crawler/migration") {
                        call.respondText(migrationStatusJson(crawlerMigrationStatus()), ContentType.Application.Json)
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
        the first-run wizard (grant the USB drive, set Transmission credentials).</p>
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
                append("<table class=\"tx\"><tr><th>Name</th><th>Done</th><th>Down</th><th>Up</th><th>Size</th></tr>")
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

        val txLink = "http://${wifiIp() ?: "127.0.0.1"}:9091/"

        return """
            <!DOCTYPE html><html><head><title>Torrent App — Control</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{font-family:system-ui,sans-serif;max-width:820px;margin:2em auto;padding:0 1em;background:#f5f5f5}
              h1{margin-bottom:.25em} h2{margin-top:1.5em}
              .state{padding:2px 8px;border-radius:4px;font-size:.85em;font-weight:600;white-space:nowrap}
              .RUNNING{background:#d4edda;color:#155724}.STARTING{background:#fff3cd;color:#856404}
              .STOPPING{background:#f8d7da;color:#721c24}.STOPPED{background:#e2e3e5;color:#383d41}
              table{width:100%;border-collapse:collapse;margin:1em 0;background:#fff;border-radius:6px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.1)}
              th{background:#eee;padding:8px 12px;text-align:left;font-size:.85em;text-transform:uppercase;letter-spacing:.05em}
              td{padding:8px 12px;border-top:1px solid #eee;vertical-align:top}
              th,td,code{overflow-wrap:anywhere}
              .tx td+td{overflow-wrap:normal}
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
              <a class="cfg" href="/settings/crawler">Crawler</a>
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
            <h2>DHT Crawler</h2>
            ${crawlerSection()}
            </body></html>
        """.trimIndent()
    }

    private fun buildStatusJson(): String {
        val daemons = daemonStatuses()
        val usb = usbFreeSpace()
        val f = ftpStatus()
        val s = safStatus()
        val crawler = daemons.firstOrNull { it.label == DaemonService.CRAWLER_LABEL }
        return buildString {
            append("{\"service\":\"").append(serviceState.value.toString()).append("\"")
            append(",\"wizardCompleted\":").append(wizardCompleted())
            append(",\"rolledBack\":").append(lastApplyRolledBack())
            append(",\"usb\":{\"granted\":").append(s.granted).append(",\"freeBytes\":").append(usb ?: "null").append("}")
            append(",\"wifiUp\":").append(f.wifiUp)
            append(",\"ftp\":{").append("\"running\":").append(f.running).append(",\"state\":").append(jsonStr(f.state)).append(",\"port\":").append(f.port).append("}")
            append(",\"crawler\":{\"state\":\"").append(crawler?.state?.name ?: "STOPPED")
                .append("\",\"pausedNoWifi\":").append(crawlerPausedNoWifi()).append("}")
            append(",\"daemons\":[")
            append(daemons.joinToString(",") { d ->
                "{\"label\":\"" + d.label + "\",\"state\":\"" + d.state + "\",\"pid\":" + (d.pid ?: "null") +
                ",\"restarts\":" + d.restartCount + "}"
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
               &nbsp; wifi: <strong>${if (f.wifiUp) "up" else "down"}</strong>
               &nbsp; restarts: <strong>${f.restartCount}</strong></p>
            <p>URL: <code>$url</code></p>
        """.trimIndent()
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

    // ── settings panel builders ───────────────────────────────────────────

    private fun buildTransmissionPanel(msg: String?, ok: Boolean): String {
        val cfg = loadConfig()
        val s = cfg.transmission
        val banner = if (msg != null)
            """<div class="${if (ok) "ok" else "err"}">${esc(msg)}</div>""" else ""
        return """
            <!DOCTYPE html><html><head><title>Transmission settings</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$panelCss</style></head><body>
            <h1>Transmission settings</h1>$banner
            <form method="post" action="/settings/transmission">
              <label>RPC username<input name="username" value="${esc(s.username)}"></label>
              <label>RPC password (leave blank to keep)<input type="password" name="password" placeholder="unchanged"></label>
              <label>Peer port<input name="peerPort" value="${s.peerPort}"></label>
              <fieldset><legend>Speed limits (KB/s, 0 = unlimited)</legend>
                <label>Down<input name="speedLimitDown" value="${s.speedLimitDown}"></label>
                <label>Up<input name="speedLimitUp" value="${s.speedLimitUp}"></label>
                <label>Alt down<input name="altSpeedDown" value="${s.altSpeedDown}"></label>
                <label>Alt up<input name="altSpeedUp" value="${s.altSpeedUp}"></label>
              </fieldset>
              <label>Data directory (under USB root; blank = root)<input name="dataSubpath" value="${esc(cfg.dataSubpath)}"></label>
              <button type="submit">Apply</button>
            </form>
            <p class="meta">Speed-only changes apply live via RPC. Username/password/peer-port/data-directory changes restart transmission.</p>
            <p><a href="/">Back to dashboard</a></p>
            </body></html>
        """.trimIndent()
    }

    private fun buildFtpPanel(msg: String?, ok: Boolean): String {
        val s = loadConfig().ftp
        val banner = if (msg != null)
            """<div class="${if (ok) "ok" else "err"}">${esc(msg)}</div>""" else ""
        val checked = if (s.wifiOnly) "checked" else ""
        return """
            <!DOCTYPE html><html><head><title>FTP settings</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$panelCss</style></head><body>
            <h1>FTP settings</h1>$banner
            <form method="post" action="/settings/ftp">
              <label>Username<input name="username" value="${esc(s.username)}"></label>
              <label>Password (leave blank to keep)<input type="password" name="password" placeholder="unchanged"></label>
              <label>Port<input name="port" value="${s.port}"></label>
              <label class="row"><input type="checkbox" name="wifiOnly" $checked> WiFi only</label>
              <label>Max download (KiB/s, 0 = unlimited)<input name="maxDownloadKiBps" value="${s.maxDownloadKiBps}"></label>
              <p class="meta">The FTP root follows the shared data directory (Transmission settings).</p>
              <button type="submit">Apply</button>
            </form>
            <p><a href="/">Back to dashboard</a></p>
            </body></html>
        """.trimIndent()
    }

    private fun buildStoragePanel(): String {
        val s = safStatus()
        val free = usbFreeSpace()
        val f = ftpStatus()
        return """
            <!DOCTYPE html><html><head><title>Storage</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$panelCss</style></head><body>
            <h1>USB storage</h1>
            <p class="meta">Granted: <strong>${s.granted}</strong></p>
            <p class="meta">Tree: <code>${esc(s.treeUri ?: "—")}</code></p>
            <p class="meta">Free: <strong>${free?.let { formatBytes(it) } ?: "unknown"}</strong></p>
            <p class="meta">WiFi: <strong>${if (f.wifiUp) "up" else "down"}</strong></p>
            <div class="warn">To re-grant or change the USB drive, open the Torrent App on the
            Android device and tap <strong>Grant USB</strong>. The data directory is set in
            <a href="/settings/transmission">Transmission settings</a>.</div>
            <p><a href="/">Back to dashboard</a></p>
            </body></html>
        """.trimIndent()
    }

    private fun buildCrawlerPanel(msg: String?, ok: Boolean, rebuildBloom: Boolean): String {
        val s = loadConfig().crawler
        val sBackup = loadConfig().backup
        val banner = if (msg != null)
            """<div class="${if (ok) "ok" else "err"}">${esc(msg)}</div>""" else ""
        val checked = { b: Boolean -> if (b) "checked" else "" }
        val levelOptions = CrawlerKeys.LOG_LEVELS.joinToString("") { l ->
            """<option value="$l"${if (l == s.logLevel) " selected" else ""}>$l</option>"""
        }
        val rebuildBox = if (rebuildBloom) """
            <div class="warn">Bloom filter settings changed — the persisted filters are now stale.
            <form method="post" action="/crawler/maintenance/rebuild-bloom-filter"
                  onsubmit="return confirm('Rebuild the bloom filter? The crawler is stopped while it runs (a few minutes).')">
            <button type="submit">Run --rebuild-bloom-filter</button></form></div>""" else ""
        val maintenanceSection = buildMaintenanceSection()
        return """
            <!DOCTYPE html><html><head><title>DHT crawler settings</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$panelCss
            .warn-num{color:#721c24;font-weight:700}
            #apply-status{background:#e7f1ff;padding:.6em;border-radius:4px;margin:1em 0;font-size:.9em}
            #apply-status.busy{background:#fff3cd}
            pre.apply-log{margin:.4em 0 0;padding:8px;background:#fafafa;border-radius:4px;font-size:.8em;max-height:200px;overflow-y:auto;white-space:pre-wrap;word-break:break-all}
            #maint-btns form{display:inline-block;margin:0 .5em .5em 0}
            #maint-btns button{margin:0;padding:.4em .8em}
            #maint-status{font-size:.9em}
            #maint-status.busy{background:#fff3cd;padding:.4em;border-radius:4px}
            #mig-btns form{display:inline-block;margin:0 .5em .5em 0}
            #mig-btns button{margin:0;padding:.4em .8em}
            </style></head><body>
            <h1>DHT crawler</h1>$banner
            <form method="post" action="/settings/crawler">
              <fieldset><legend>General</legend>
                <label class="row"><input type="checkbox" name="enabled" ${checked(s.enabled)}> Enabled (start with the service)</label>
                <label class="row"><input type="checkbox" name="wifiOnly" ${checked(s.wifiOnly)}> WiFi only (pause when wifi is down)</label>
                <label>Log level<select name="logLevel">$levelOptions</select></label>
                <label>DHT port<input name="dhtPort" value="${s.dhtPort}"></label>
                <label>Nice level (-20..19)<input name="niceLevel" value="${s.niceLevel}"></label>
              </fieldset>
              <fieldset><legend>Resources</legend>
                <label>Tree count (1..64)<input id="numTrees" name="numTrees" value="${s.numTrees}"></label>
                <label>Find-node workers<input id="treeFindNodeWorkers" name="treeFindNodeWorkers" value="${s.treeFindNodeWorkers}"></label>
                <label>BEP51 workers<input id="treeBep51Workers" name="treeBep51Workers" value="${s.treeBep51Workers}"></label>
                <label>Get-peers workers<input id="treeGetPeersWorkers" name="treeGetPeersWorkers" value="${s.treeGetPeersWorkers}"></label>
                <label>Metadata workers<input id="treeMetadataWorkers" name="treeMetadataWorkers" value="${s.treeMetadataWorkers}"></label>
                <label>Infohash queue capacity<input name="treeInfohashQueueCapacity" value="${s.treeInfohashQueueCapacity}"></label>
                <label>Peers queue capacity<input name="treePeersQueueCapacity" value="${s.treePeersQueueCapacity}"></label>
                <p class="meta">Estimated worker threads: <span id="est-threads"></span><span id="est-warn" style="display:none;color:#721c24"> — above ${CrawlerKeys.THREAD_WARN_THRESHOLD}; the Termux-proven config uses ~1,552</span></p>
              </fieldset>
              <fieldset><legend>Filters</legend>
                <label class="row"><input type="checkbox" name="pornFilterEnabled" ${checked(s.pornFilterEnabled)}> Porn filter</label>
                <label>Non-Latin language threshold (0..100)<input name="languageFilterNonLatinThreshold" value="${s.languageFilterNonLatinThreshold}"></label>
              </fieldset>
              <fieldset><legend>Bloom filters (changing these invalidates the persisted filters)</legend>
                <label>Bloom capacity<input name="bloomCapacity" value="${s.bloomCapacity}"></label>
                <label>Failure bloom capacity<input name="failureBloomCapacity" value="${s.failureBloomCapacity}"></label>
                <label>Bloom error rate (0..1)<input name="bloomErrorRate" value="${s.bloomErrorRate}"></label>
              </fieldset>
              <fieldset><legend>Advanced overrides (key=value, one per line)</legend>
                <textarea name="advancedOverrides" rows="6" placeholder="# key=value">${esc(s.advancedOverrides)}</textarea>
                <p class="meta">Path and backup keys are not allowed; form-managed keys must use the fields above.</p>
              </fieldset>
              <fieldset><legend>Access</legend>
                <label>Proxy port (LAN, authenticated)<input name="proxyPort" value="${s.proxyPort}"></label>
                <label>Crawler HTTP port (loopback)<input name="crawlerHttpPort" value="${s.crawlerHttpPort}"></label>
                <label class="row"><input type="checkbox" name="lanDirect" ${checked(s.lanDirect)}> LAN direct (bind 0.0.0.0, no proxy, no auth)</label>
              </fieldset>
              <fieldset><legend>Backup &amp; Bootstrap (app-side only; not written to the crawler config)</legend>
                <label class="row"><input type="checkbox" name="uploadEnabled" ${checked(sBackup.uploadEnabled)}> Enable R2 upload</label>
                <label>R2 endpoint (https://…)<input name="r2Endpoint" value="${esc(sBackup.r2Endpoint)}"></label>
                <label>R2 bucket<input name="r2Bucket" value="${esc(sBackup.r2Bucket)}"></label>
                <label>R2 object key<input name="r2ObjectKey" value="${esc(sBackup.r2ObjectKey)}"></label>
                <label>R2 access key ID<input name="r2AccessKeyId" value="${esc(sBackup.r2AccessKeyId)}"></label>
                <label>R2 secret access key (leave blank to keep)<input type="password" name="r2SecretAccessKey" placeholder="unchanged"></label>
                <label class="row"><input type="checkbox" name="bootstrapEnabled" ${checked(sBackup.bootstrapEnabled)}> Enable bootstrap download</label>
                <label>Bootstrap URL (https://…)<input name="bootstrapUrl" value="${esc(sBackup.bootstrapUrl)}"></label>
              </fieldset>
              <button type="submit">Apply</button>
            </form>
            $rebuildBox
            <div id="apply-status">Apply status: IDLE</div>
            <pre id="apply-log" class="apply-log" style="display:none"></pre>
            $maintenanceSection
            ${buildMigrationSection()}
            <p><a href="/">Back to dashboard</a></p>
            <script>
            (function () {
              var ids = ['numTrees','treeFindNodeWorkers','treeBep51Workers','treeGetPeersWorkers','treeMetadataWorkers'];
              function v(id) { var n = parseInt(document.getElementById(id).value, 10); return isNaN(n) ? 0 : n; }
              function updateEst() {
                var n = v('numTrees') * (v('treeFindNodeWorkers') + v('treeBep51Workers') +
                    v('treeGetPeersWorkers') + v('treeMetadataWorkers')) + ${CrawlerKeys.THREAD_OVERHEAD};
                var el = document.getElementById('est-threads');
                el.textContent = n;
                el.className = n > ${CrawlerKeys.THREAD_WARN_THRESHOLD} ? 'warn-num' : '';
                document.getElementById('est-warn').style.display = n > ${CrawlerKeys.THREAD_WARN_THRESHOLD} ? '' : 'none';
              }
              ids.forEach(function (id) { document.getElementById(id).addEventListener('input', updateEst); });
              updateEst();
              function pollApply() {
                fetch('/api/crawler/apply-status').then(function (r) { return r.json(); }).then(function (st) {
                  var box = document.getElementById('apply-status');
                  box.textContent = 'Apply status: ' + st.phase + (st.message ? ' — ' + st.message : '');
                  box.className = ['IDLE','OK','ROLLED_BACK','FAILED'].indexOf(st.phase) >= 0 ? '' : 'busy';
                  var tail = document.getElementById('apply-log');
                  tail.style.display = st.logTail.length ? '' : 'none';
                  tail.textContent = st.logTail.join('\\n');
                }).catch(function () {});
              }
              setInterval(pollApply, 2000); pollApply();
              var maintOffset = 0;
              var maintSeenAction = null;
              function pollMaint() {
                fetch('/api/crawler/maintenance?offset=' + maintOffset).then(function (r) { return r.json(); }).then(function (st) {
                  var box = document.getElementById('maint-status');
                  var log = document.getElementById('maint-log');
                  var skip = false;
                  if (st.action !== maintSeenAction) {
                    maintSeenAction = st.action;
                    maintOffset = 0;
                    log.textContent = '';
                    skip = true;  // st.lines was sliced with the old offset; refetch from 0 next round
                  } else if (st.lines && st.lines.length) {
                    log.textContent += (log.textContent ? '\n' : '') + st.lines.join('\n');
                    log.scrollTop = log.scrollHeight;
                  }
                  if (!skip) maintOffset = st.totalLines;
                  var state = st.running ? 'running\u2026' : (st.exitCode === null ? 'idle' : 'exit ' + st.exitCode);
                  box.textContent = st.action ? ('Maintenance: ' + st.action + ' \u2014 ' + state + (st.message ? ' (' + st.message + ')' : '')) : 'Maintenance: idle';
                  box.className = st.running ? 'busy' : 'meta';
                  log.style.display = st.totalLines ? '' : 'none';
                }).catch(function () {});
              }
              setInterval(pollMaint, 1500); pollMaint();
              var migPhase = null;
              var migLinesSeen = 0;
              var MIG_ACTIVE = ['SCANNING','COPYING','VERIFYING','SWAPPING'];
              function pollMig() {
                fetch('/api/crawler/migration').then(function (r) { return r.json(); }).then(function (st) {
                  var status = document.getElementById('mig-status');
                  if (status) status.textContent = 'Phase: ' + st.phase + (st.message ? ' \u2014 ' + st.message : '');
                  var active = MIG_ACTIVE.indexOf(st.phase) >= 0;
                  var prog = document.getElementById('mig-prog');
                  if (st.totalBytes > 0 && (active || st.copiedBytes > 0)) {
                    prog.style.display = '';
                    document.getElementById('mig-prog-bar').style.width =
                        Math.min(100, Math.round(st.copiedBytes * 100 / st.totalBytes)) + '%';
                    var rate = st.copyBytesPerSec > 0 ? ' (' + (st.copyBytesPerSec / 1048576).toFixed(1) + ' MB/s)' : '';
                    document.getElementById('mig-prog-text').textContent =
                        (st.currentFile ? st.currentFile + ' \u2014 ' : '') +
                        Math.round(st.copiedBytes / 1048576) + ' / ' + Math.round(st.totalBytes / 1048576) + ' MB' + rate;
                  }
                  var log = document.getElementById('mig-log');
                  var lines = st.verifyLines || [];
                  if (lines.length > migLinesSeen) {
                    log.textContent += (log.textContent ? '\n' : '') + lines.slice(migLinesSeen).join('\n');
                    log.scrollTop = log.scrollHeight;
                  } else if (lines.length < migLinesSeen) {
                    migLinesSeen = 0;
                    log.textContent = '';
                  }
                  migLinesSeen = lines.length;
                  log.style.display = log.textContent ? '' : 'none';
                  if (migPhase !== null && MIG_ACTIVE.indexOf(migPhase) >= 0 && !active) {
                    location.reload();  // re-render the buttons for the finished phase
                  }
                  migPhase = st.phase;
                }).catch(function () {});
              }
              setInterval(pollMig, 2000); pollMig();
            })();
            </script>
            </body></html>
        """.trimIndent()
    }

    /** The maintenance fieldset on the crawler panel (plan 10 step 4). */
    private fun buildMaintenanceSection(): String {
        val buttons = MaintenanceAction.entries.joinToString("") { a ->
            val warn = if (a.needsSpaceCheck) " (needs ~DB size of free internal space)" else ""
            """
              <form method="post" action="/crawler/maintenance/${a.route}"
                    onsubmit="return confirm('Run ${a.flag}? The crawler is stopped while it runs.$warn')">
              <button type="submit">${a.route}</button></form>
            """.trimIndent()
        }
        return """
            <fieldset><legend>Maintenance (one at a time; crawler stopped while running)</legend>
              <p class="meta">Runs <code>libdhtcrawler.so &lt;flag&gt;</code> as a one-shot in the crawler
              work dir. compact / recover-database need ~DB size of free internal space. The supervised
              crawler is stopped first and restarted afterwards if enabled (and wifi is up).</p>
              <div id="maint-btns">$buttons</div>
              <div id="maint-status" class="meta">Maintenance: idle</div>
              <pre id="maint-log" class="apply-log" style="display:none"></pre>
            </fieldset>
        """.trimIndent()
    }

    /** One migration action button (plan 10 step 7); [confirm] gates the submit. */
    private fun migAction(action: String, label: String, confirm: String): String =
        """
            <form method="post" action="/crawler/migration/$action"
                  onsubmit="return confirm('${confirm}')">
            <button type="submit">$label</button></form>
        """.trimIndent()

    /** The migration fieldset on the crawler panel (plan 10 step 7). */
    private fun buildMigrationSection(): String {
        val st = crawlerMigrationStatus()
        val phase = st.phase
        val treeLine = if (st.treeUri == null) {
            "<span class=\"meta\">No folder granted — open the <strong>Torrent App</strong> and tap " +
                "<strong>Choose Termux dht_crawler folder</strong> (the web page cannot open a picker).</span>"
        } else {
            buildString {
                append("<span class=\"meta\">Tree: <code>").append(esc(st.treeUri))
                append("</code> (authority: ").append(esc(st.treeAuthority ?: "?")).append(")</span>")
                if (st.nonTermuxWarning) {
                    append("<br><span class=\"warn\">Not the Termux provider — make sure the picked folder " +
                        "is the dht_crawler directory containing data/.</span>")
                }
            }
        }
        val fileTable = if (st.files.isEmpty()) "" else buildString {
            append("<table><tr><th>File</th><th>Size</th><th>Modified</th><th>Classification</th></tr>")
            for (f in st.files) {
                append("<tr><td>").append(esc(f.name)).append("</td><td>").append(formatBytes(f.size))
                    .append("</td><td>").append(formatMtime(f.mtime)).append("</td><td>")
                    .append(f.role.name).append("</td></tr>")
            }
            append("</table>")
        }
        val totals = if (st.files.isEmpty()) "" else buildString {
            append("<p class=\"meta\">Total to import: <strong>").append(formatBytes(st.totalBytes))
                .append("</strong> &nbsp; Free internal: <strong>").append(formatBytes(st.freeSpaceBytes))
                .append("</strong>")
            if (st.importBytes > 0) append(" &nbsp; data.import/: <strong>")
                .append(formatBytes(st.importBytes))
                .append("</strong> (reclaimable by a copy)")
            if (st.currentDataBytes > 0) append(" &nbsp; Current internal data: <strong>")
                .append(formatBytes(st.currentDataBytes))
                .append("</strong> (deletable by choice)")
            if (!st.spaceOk) append(" <span class=\"warn\">not enough free space (need total + 10%)</span>")
            append("</p>")
        }
        val verifyBox = if (st.verifyExitCode == null) "" else buildString {
            append("<p class=\"meta\">--check-database exit: <strong>").append(st.verifyExitCode).append("</strong>")
            st.torrentCount?.let { append(" &nbsp; Torrents: <strong>").append(it).append("</strong>") }
            append("</p>")
            if (st.torrentCount != null) {
                append("<p class=\"meta\">Compare in Termux: <code>sqlite3 data/torrents.db " +
                    "'SELECT COUNT(*) FROM torrents'</code></p>")
            }
        }
        val diffBox = if (st.configDiff.isEmpty()) "" else buildString {
            append("<p class=\"meta\">Tuning keys in Termux's config.ini that differ from the app's:</p>")
            append("<table><tr><th>Key</th><th>Termux</th><th>App</th></tr>")
            for (d in st.configDiff) {
                append("<tr><td><code>").append(esc(d.key)).append("</code></td><td>")
                    .append(esc(d.termuxValue)).append("</td><td>").append(esc(d.currentValue))
                    .append("</td></tr>")
            }
            append("</table>")
        }
        val buttons = buildList {
            if (st.treeUri != null && !phase.isActive && phase != MigrationPhase.CONFIRMED)
                add(migAction("scan", "Scan", "Scan the Termux dht_crawler folder?"))
            if (phase == MigrationPhase.SCANNED && st.spaceOk)
                add(migAction("copy", if (st.importComplete) "Copy again" else "Copy",
                    "Copy the files? The app crawler is stopped while it runs."))
            if (phase == MigrationPhase.SCANNED && st.importComplete)
                add(migAction("verify", "Verify existing copy",
                    "Run --check-database on the existing data.import/ copy?"))
            if (phase == MigrationPhase.COPYING)
                add(migAction("cancel", "Cancel copy", "Cancel the copy? data.import/ is deleted."))
            if (phase == MigrationPhase.COPIED)
                add(migAction("verify", "Verify", "Run --check-database on the imported data?"))
            if (phase == MigrationPhase.COPIED || phase == MigrationPhase.VERIFIED)
                add(migAction("swap", "Swap", "Swap in the imported data? The app crawler is stopped and the " +
                    "old data/ is kept as data.old/ until you confirm."))
            if (phase == MigrationPhase.SWAPPED && st.configDiff.isNotEmpty())
                add(migAction("adopt-config", "Adopt tuning keys",
                    "Adopt ${st.configDiff.size} tuning keys from Termux? The normal apply flow runs " +
                        "(the crawler is stopped and restarted)."))
            if (phase == MigrationPhase.SWAPPED)
                add(migAction("start", "Start crawler", "Start the crawler on the imported data?"))
            if (phase == MigrationPhase.SWAPPED || phase == MigrationPhase.STARTED)
                add(migAction("confirm", "Confirm migration",
                    "Confirm the migration? data.old/ is deleted and the folder grant is released. The " +
                        "Termux copy stays in place."))
            if (phase in setOf(MigrationPhase.COPIED, MigrationPhase.VERIFIED, MigrationPhase.SWAPPED,
                MigrationPhase.STARTED, MigrationPhase.FAILED, MigrationPhase.CANCELLED))
                add(migAction("revert", "Revert migration",
                    "Revert the migration? The app crawler is stopped, the imported data is moved to " +
                        "data.failed/ and the previous data and config.ini are restored."))
            if (phase == MigrationPhase.CONFIRMED && st.treeUri != null)
                add("""
                    <form method="post" action="/crawler/migration/delete-source"
                          onsubmit="return confirm('Delete the source files in the Termux tree via SAF? They are gone for good.') &&
                                     confirm('Second check: really delete the Termux source files now?')">
                    <button type="submit">Delete source files via SAF</button></form>
                """.trimIndent())
            if (phase == MigrationPhase.SCANNED && !st.spaceOk && st.currentDataBytes > 0)
                add("""
                    <form method="post" action="/crawler/migration/delete-current-data"
                          onsubmit="return confirm('Delete the current internal data (data/ and data.old/, ${formatBytes(st.currentDataBytes)}) to free space? The app crawler is stopped and a new scan runs.') &&
                                     confirm('Second check: really delete the current data? A revert will then have no previous data to restore.')">
                    <button type="submit">Delete current data</button></form>
                """.trimIndent())
        }.joinToString("")
        val cleanup = if (phase == MigrationPhase.CONFIRMED) {
            "<p class=\"meta\">Termux side: <code>rm -rf ~/dht_crawler/data</code> (or the whole " +
                "<code>~/dht_crawler</code>) — the app never deletes Termux files on its own.</p>"
        } else ""
        val remind = if (phase in setOf(MigrationPhase.SWAPPED, MigrationPhase.STARTED, MigrationPhase.CONFIRMED)) {
            "<p class=\"warn\">Do not start the Termux crawler again — ports 8080/6881 clash and the " +
                "two databases would diverge.</p>"
        } else ""
        return """
            <fieldset><legend>Termux migration (import the existing dht_crawler database)</legend>
              <p class="meta">1. In Termux: stop the crawler (Ctrl-C, then wait for the final flush). Optionally
              fold the WAL into the database: <code>sqlite3 data/torrents.db 'PRAGMA wal_checkpoint(TRUNCATE);'</code></p>
              <p class="meta">2. On the phone: open the Torrent App and tap <strong>Choose Termux dht_crawler
              folder</strong> — pick the <code>dht_crawler</code> folder (the parent of <code>data/</code>).</p>
              $treeLine
              <div id="mig-status" class="meta">Phase: ${phase.name} — ${esc(st.message)}</div>
              <div id="mig-prog" style="display:none;margin:.5em 0">
                <div style="background:#eee;border-radius:4px;overflow:hidden">
                  <div id="mig-prog-bar" style="background:#4a90d9;height:10px;width:0%"></div>
                </div>
                <span id="mig-prog-text" class="meta"></span>
              </div>
              $fileTable
              $totals
              $verifyBox
              <pre id="mig-log" class="apply-log" style="display:none"></pre>
              $diffBox
              <div id="mig-btns">$buttons</div>
              <div id="mig-live" class="meta">Torrents: ${st.torrentCount ?: "—"}
                 &nbsp; Crawler: ${if (st.crawlerRunning) "running" else "stopped"}
                 &nbsp; /stats: ${if (st.statsReachable) "reachable" else "unreachable"}</div>
              $cleanup
              $remind
            </fieldset>
        """.trimIndent()
    }

    private fun crawlerSection(): String {
        val c = loadConfig().crawler
        val d = daemonStatuses().firstOrNull { it.label == DaemonService.CRAWLER_LABEL }
        val state = d?.state ?: ProcessState.STOPPED
        val uptime = d?.startTimeMs?.let { formatUptime(System.currentTimeMillis() - it) } ?: "—"
        val logs = d?.lastLogLines?.takeLast(30)?.joinToString("<br>") { esc(it) } ?: "—"
        val dbSize = crawlerDbSize()
        val free = internalFreeSpace()
        val spaceWarn = if (free < CRAWLER_LOW_FREE_SPACE_BYTES) " <span class=\"warn\">low free space</span>" else ""
        val paused = if (crawlerPausedNoWifi()) " <span class=\"state STOPPED\">paused: no wifi</span>" else ""
        val host = wifiIp() ?: "127.0.0.1"
        val searchUrl = "http://$host:${c.proxyPort}/"
        return """
            <p class="meta">State: <span class="state $state">$state</span>$paused
               &nbsp; PID: <strong>${d?.pid ?: "—"}</strong>
               &nbsp; Uptime: <strong>$uptime</strong>
               &nbsp; Restarts: <strong>${d?.restartCount ?: 0}</strong></p>
            <p class="meta">DB: <strong>${dbSize?.let { formatBytes(it) } ?: "—"}</strong>
               &nbsp; Internal free: <strong>${formatBytes(free)}</strong>$spaceWarn</p>
            <p class="meta">Search UI: <a href="$searchUrl" target="_blank">$searchUrl</a></p>
            <p class="meta" id="crawler-stats">Stats: loading…</p>
            <pre class="loglines">$logs</pre>
            <script>
            (function () {
              function poll() {
                fetch('/api/crawler/stats').then(function (r) { return r.json(); }).then(function (s) {
                  var el = document.getElementById('crawler-stats');
                  if (!s || s.running === false) {
                    el.textContent = (s && (s.state === 'RUNNING' || s.state === 'STARTING'))
                      ? 'Stats: crawler starting (HTTP API not ready yet)'
                      : 'Stats: crawler not running';
                    return;
                  }
                  var rate = 0;
                  (s.trees || []).forEach(function (t) { rate += (t.metadata_rate || 0); });
                  el.textContent = 'Stats: ' + (s.torrents_indexed || 0) + ' torrents, ' +
                      ((s.supervisor && s.supervisor.active_trees) || 0) + ' active trees, ' +
                      rate.toFixed(1) + ' metadata/s';
                }).catch(function () {});
              }
              setInterval(poll, 5000); poll();
            })();
            </script>
        """.trimIndent()
    }

    /**
     * Fetches the crawler's own /stats JSON over loopback with a short
     * timeout. Returns null when the crawler is unreachable — the caller
     * responds with the clean "not running" fallback.
     */
    private fun fetchCrawlerStats(port: Int): String? {
        return try {
            val conn = (java.net.URL("http://127.0.0.1:$port/stats").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                instanceFollowRedirects = false
            }
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        }
    }

    private val panelCss = """
        body{font-family:system-ui,sans-serif;max-width:640px;margin:2em auto;padding:0 1em}
        label{display:block;margin:.6em 0} input{display:block;width:100%;padding:6px;box-sizing:border-box;margin-top:2px}
        fieldset{margin:.8em 0;border:1px solid #ddd;border-radius:4px}
        fieldset label{display:inline-block;width:auto;vertical-align:middle} fieldset input{display:inline;width:auto}
        label.row{display:block} .meta{color:#666;font-size:.9em}
        .ok{background:#d4edda;color:#155724;padding:.6em;border-radius:4px;margin:1em 0}
        .err{background:#f8d7da;color:#721c24;padding:.6em;border-radius:4px;margin:1em 0}
        .warn{background:#fff3cd;color:#856404;padding:.6em;border-radius:4px;margin:1em 0}
        button{padding:.5em 1em;margin-top:1em}
    """.trimIndent()

    private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")
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
    private fun formatMtime(ms: Long): String =
        if (ms <= 0) "—" else java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    companion object {
        private fun jsonStr(s: String?): String =
            if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /**
         * The /api/crawler/migration response body (plan 10 step 7): the polled
         * [MigrationStatus] as JSON for the panel's phase text, progress bar,
         * scan table, verify log, config diff and action buttons.
         */
        internal fun migrationStatusJson(st: MigrationStatus): String = buildString {
            append("{\"phase\":\"").append(st.phase.name).append("\"")
            append(",\"message\":").append(jsonStr(st.message))
            append(",\"treeUri\":").append(jsonStr(st.treeUri))
            append(",\"treeAuthority\":").append(jsonStr(st.treeAuthority))
            append(",\"nonTermuxWarning\":").append(st.nonTermuxWarning)
            append(",\"files\":[")
            append(st.files.joinToString(",") { f ->
                "{\"name\":" + jsonStr(f.name) + ",\"size\":" + f.size +
                    ",\"mtime\":" + f.mtime + ",\"role\":\"" + f.role.name + "\"}"
            })
            append("]")
            append(",\"totalBytes\":").append(st.totalBytes)
            append(",\"freeSpaceBytes\":").append(st.freeSpaceBytes)
            append(",\"spaceOk\":").append(st.spaceOk)
            append(",\"importComplete\":").append(st.importComplete)
            append(",\"importBytes\":").append(st.importBytes)
            append(",\"currentDataBytes\":").append(st.currentDataBytes)
            append(",\"currentFile\":").append(jsonStr(st.currentFile))
            append(",\"copiedBytes\":").append(st.copiedBytes)
            append(",\"copyBytesPerSec\":").append(st.copyBytesPerSec)
            append(",\"verifyExitCode\":").append(st.verifyExitCode?.toString() ?: "null")
            append(",\"verifyLines\":[").append(st.verifyLines.joinToString(",") { jsonStr(it) }).append("]")
            append(",\"torrentCount\":").append(st.torrentCount?.toString() ?: "null")
            append(",\"configDiff\":[")
            append(st.configDiff.joinToString(",") { d ->
                "{\"key\":" + jsonStr(d.key) + ",\"termuxValue\":" + jsonStr(d.termuxValue) +
                    ",\"currentValue\":" + jsonStr(d.currentValue) + "}"
            })
            append("]")
            append(",\"bloomRebuilt\":").append(st.bloomRebuilt)
            append(",\"crawlerRunning\":").append(st.crawlerRunning)
            append(",\"statsReachable\":").append(st.statsReachable)
            append(",\"startedAt\":").append(st.startedAt?.toString() ?: "null")
            append(",\"finishedAt\":").append(st.finishedAt?.toString() ?: "null")
            append("}")
        }

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

        /**
         * Decides which token authenticates a request and whether the response should
         * set the control_token cookie. Returns (token, setCookie):
         *   - token is the authenticated token, or null if none/invalid (caller rejects).
         *   - setCookie is true when the token came from the query string (no cookie
         *     was present), so the caller sets the cookie to keep the session alive.
         *
         * Pure and unit-testable; the interceptor applies the result.
         */
        fun resolveAuthToken(
            cookieHeader: String?,
            queryToken: String?,
            authHeader: String?,
            isValid: (String) -> Boolean,
        ): Pair<String?, Boolean> {
            val cookie = parseCookieToken(cookieHeader)
            if (cookie != null && isValid(cookie)) return cookie to false
            val query = queryToken
            if (query != null && isValid(query)) return query to true
            val bearer = authHeader?.removePrefix("Bearer ")
            if (bearer != null && bearer != authHeader && isValid(bearer)) return bearer to false
            val basic = authHeader?.removePrefix("Basic ")
            if (basic != null && basic != authHeader) {
                // `Basic base64(user:token)` — the control token is the password part.
                val password = try {
                    val decoded = String(Base64.getDecoder().decode(basic), Charsets.UTF_8)
                    val colon = decoded.indexOf(':')
                    if (colon < 0) null else decoded.substring(colon + 1)
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (password != null && isValid(password)) return password to false
            }
            return null to false
        }

        /** Free internal storage below which the dashboard card warns. */
        internal const val CRAWLER_LOW_FREE_SPACE_BYTES = 2L * 1024 * 1024 * 1024

        /**
         * The /api/crawler/stats response body: the crawler's own /stats JSON
         * passed through when [statsBody] was fetched; otherwise a clean
         * `{"running":false,"state":…}` fallback carrying the supervisor's
         * process [state], so the dashboard can tell "starting" (process up,
         * crawler's HTTP API — which comes up last, after the DB is opened —
         * not answering yet) apart from genuinely stopped.
         */
        internal fun crawlerStatsJson(statsBody: String?, state: ProcessState? = null): String =
            if (statsBody.isNullOrBlank()) "{\"running\":false,\"state\":\"${state ?: ProcessState.STOPPED}\"}" else statsBody

        /**
         * Builds CrawlerSettings from the settings form (checkbox presence =
         * true, blank/missing numerics fall back to [current]). Pure and
         * unit-testable; the POST route validates the result server-side.
         */
        internal fun crawlerSettingsFromForm(
            form: Map<String, String>,
            current: CrawlerSettings,
        ): CrawlerSettings = CrawlerSettings(
            enabled = form.containsKey("enabled"),
            wifiOnly = form.containsKey("wifiOnly"),
            logLevel = form["logLevel"]?.takeIf { it.isNotEmpty() } ?: current.logLevel,
            dhtPort = form["dhtPort"]?.toIntOrNull() ?: current.dhtPort,
            niceLevel = form["niceLevel"]?.toIntOrNull() ?: current.niceLevel,
            numTrees = form["numTrees"]?.toIntOrNull() ?: current.numTrees,
            treeFindNodeWorkers = form["treeFindNodeWorkers"]?.toIntOrNull() ?: current.treeFindNodeWorkers,
            treeBep51Workers = form["treeBep51Workers"]?.toIntOrNull() ?: current.treeBep51Workers,
            treeGetPeersWorkers = form["treeGetPeersWorkers"]?.toIntOrNull() ?: current.treeGetPeersWorkers,
            treeMetadataWorkers = form["treeMetadataWorkers"]?.toIntOrNull() ?: current.treeMetadataWorkers,
            treeInfohashQueueCapacity = form["treeInfohashQueueCapacity"]?.toIntOrNull() ?: current.treeInfohashQueueCapacity,
            treePeersQueueCapacity = form["treePeersQueueCapacity"]?.toIntOrNull() ?: current.treePeersQueueCapacity,
            pornFilterEnabled = form.containsKey("pornFilterEnabled"),
            languageFilterNonLatinThreshold = form["languageFilterNonLatinThreshold"]?.toIntOrNull() ?: current.languageFilterNonLatinThreshold,
            bloomCapacity = form["bloomCapacity"]?.toIntOrNull() ?: current.bloomCapacity,
            failureBloomCapacity = form["failureBloomCapacity"]?.toIntOrNull() ?: current.failureBloomCapacity,
            bloomErrorRate = form["bloomErrorRate"]?.toDoubleOrNull() ?: current.bloomErrorRate,
            advancedOverrides = form["advancedOverrides"] ?: current.advancedOverrides,
            proxyPort = form["proxyPort"]?.toIntOrNull() ?: current.proxyPort,
            crawlerHttpPort = form["crawlerHttpPort"]?.toIntOrNull() ?: current.crawlerHttpPort,
            lanDirect = form.containsKey("lanDirect"),
        )

        /**
         * Builds BackupSettings from the crawler panel form (checkbox
         * presence = true, blank secret keeps the current value, missing
         * values fall back to [current]). Pure and unit-testable.
         */
        internal fun backupSettingsFromForm(
            form: Map<String, String>,
            current: BackupSettings,
        ): BackupSettings = BackupSettings(
            r2Endpoint = form["r2Endpoint"] ?: current.r2Endpoint,
            r2Bucket = form["r2Bucket"] ?: current.r2Bucket,
            r2ObjectKey = form["r2ObjectKey"]?.takeIf { it.isNotEmpty() } ?: current.r2ObjectKey,
            r2AccessKeyId = form["r2AccessKeyId"] ?: current.r2AccessKeyId,
            r2SecretAccessKey = form["r2SecretAccessKey"]?.takeIf { it.isNotEmpty() } ?: current.r2SecretAccessKey,
            uploadEnabled = form.containsKey("uploadEnabled"),
            bootstrapEnabled = form.containsKey("bootstrapEnabled"),
            bootstrapUrl = form["bootstrapUrl"] ?: current.bootstrapUrl,
        )
    }
}
