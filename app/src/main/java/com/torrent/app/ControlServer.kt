package com.torrent.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import com.torrent.app.config.TransmissionApplyMode
import com.torrent.app.config.CrawlerKeys
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.BackupSettings

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
    // Stats websocket push period (webui stage 1); injectable for tests.
    private val wsPushPeriodMs: Long = 10_000L,
    private val crawlerDbSize: () -> Long? = { null },
    private val internalFreeSpace: () -> Long = { 0L },
    // Maintenance (plan 10 step 5): the POST route queues an action by name and
    // the GET route polls its status (incl. output lines since an offset).
    private val crawlerMaintenanceStart: (String) -> String = { "maintenance is not wired" },
    private val crawlerMaintenanceStatus: () -> MaintenanceStatus = { MaintenanceStatus() },
    // BEP51 cache backup (Stage 4): the POST route queues a manual upload of
    // the crawler's bootstrap cache to R2; the GET route polls the manager's
    // status (state, message, last success, bytes).
    private val crawlerBackupUpload: () -> String = { "backup upload is not wired" },
    private val crawlerBackupStatus: () -> com.torrent.app.backup.BackupStatus =
        { com.torrent.app.backup.BackupStatus() },
    // DB dump backup (plan 2, Stage 3): the POST route queues a manual
    // upload of the crawler's incremental DB dump to the R2 DB bucket;
    // the GET route polls the manager's status.
    private val crawlerBackupDbUpload: () -> String = { "DB backup is not wired" },
    private val crawlerBackupDbStatus: () -> com.torrent.app.backup.DbBackupStatus =
        { com.torrent.app.backup.DbBackupStatus() },
    // DB restore (plan 2, Stage 4): the POST route queues an explicit
    // restore of the crawler DB from the R2 dump chain; the GET route
    // polls the manager's status (no secrets).
    private val crawlerRestoreDb: () -> String = { "DB restore is not wired" },
    private val crawlerRestoreDbStatus: () -> com.torrent.app.backup.DbRestoreStatus =
        { com.torrent.app.backup.DbRestoreStatus() },
    private val port: Int = 8090,
    // Search-dashboard stage 2: same-origin POST /api/transmission/add (mirrors
    // CrawlerProxyServer's route); null = the route answers 503.
    private val addHandler: CrawlerAddHandler? = null,
) {
    @Volatile private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets)
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

                    // Crawler stats page (webui stage 2): a live view fed by
                    // the stats websocket below; the page never polls.
                    get("stats") {
                        call.respondText(buildStatsPage(), ContentType.Text.Html)
                    }

                    // Search-dashboard stage 3a: the blue phone search UI
                    // shell. Static markup/CSS only; the interaction logic
                    // lands in stage 3b over the /search/ws feed.
                    get("search") {
                        val proxyOrigin =
                            "http://${wifiIp() ?: "127.0.0.1"}:${loadConfig().crawler.proxyPort}"
                        call.respondText(buildSearchPage(proxyOrigin), ContentType.Text.Html)
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
                        call.respondText(crawlerStatsPayload(), ContentType.Application.Json)
                    }

                    // Same-origin magnet add (search-dashboard stage 2): mirrors
                    // CrawlerProxyServer's route so the :8090 dashboard can add a
                    // crawler-DB torrent without going through the proxy port.
                    // Auth is the route interceptor above; there is no second check.
                    post("api/transmission/add") {
                        val handler = addHandler
                        if (handler == null) {
                            call.respondText(
                                """{"ok":false,"error":"db_unavailable"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable,
                            )
                        } else {
                            val outcome = handler.add(call.request.queryParameters["hash"] ?: "")
                            call.respondText(
                                outcome.toJson(),
                                ContentType.Application.Json,
                                HttpStatusCode.fromValue(outcome.httpStatus),
                            )
                        }
                    }

                    // Live stats feed (webui stage 1): the crawler's CivetWeb has no
                    // websocket support, so the WS terminates here and we poll the
                    // loopback /stats ourselves. The interceptor above already
                    // rejected unauthenticated upgrades with 401; the handler
                    // re-checks the token (same precedence, constant-time via
                    // TokenManager) and never pushes stats on a bad socket.
                    webSocket("stats/ws") {
                        // `this` is the DefaultWebSocketServerSession.
                        val (token, _) = resolveAuthToken(
                            call.request.headers["Cookie"],
                            call.request.queryParameters["token"],
                            call.request.headers["Authorization"],
                            tokenManager::validate,
                        )
                        if (token == null) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                            return@webSocket
                        }
                        while (true) {
                            try {
                                send(Frame.Text(crawlerStatsPayload()))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e // coroutine cancelled (client gone); exit cleanly
                            } catch (e: Exception) {
                                break // the socket went away; stop pushing
                            }
                            delay(wsPushPeriodMs)
                        }
                    }

                    // Search feed (search-dashboard stage 2): request/response over
                    // one socket. The client sends {"id":N,"type":"search|browse|random",
                    // ...filters}; we validate, build a loopback path from enumerated
                    // values only, fetch the crawler JSON and reply with the raw body
                    // spliced verbatim under key "payload". It never throws on a
                    // malformed frame or a half-open socket.
                    webSocket("search/ws") {
                        val (token, _) = resolveAuthToken(
                            call.request.headers["Cookie"],
                            call.request.queryParameters["token"],
                            call.request.headers["Authorization"],
                            tokenManager::validate,
                        )
                        if (token == null) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                            return@webSocket
                        }
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val reply = try {
                                handleSearchFrame(frame.readText())
                            } catch (e: Exception) {
                                // Defensive: a reply is mandatory, never a crash.
                                """{"id":0,"ok":false,"error":"bad_request"}"""
                            }
                            try {
                                send(Frame.Text(reply))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e // coroutine cancelled (client gone)
                            } catch (e: Exception) {
                                break // the socket went away; stop the loop
                            }
                        }
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

                    // BEP51 cache backup (Stage 4): queue a manual upload of
                    // the crawler's bootstrap cache to R2 (gates apply; the
                    // daily gate is bypassed); the page polls
                    // /api/crawler/backup for the result.
                    post("crawler/backup/upload") {
                        val msg = crawlerBackupUpload()
                        val ok = !msg.startsWith("refused")
                        val token = parseCookieToken(call.request.headers["Cookie"])
                            ?: call.request.queryParameters["token"] ?: ""
                        call.respondRedirect("/settings/crawler?msg=${urlEnc(msg)}&ok=$ok&token=$token")
                    }

                    // Backup status JSON for the crawler panel poller.
                    get("api/crawler/backup") {
                        call.respondText(backupStatusJson(crawlerBackupStatus()), ContentType.Application.Json)
                    }

                    // DB dump backup (plan 2, Stage 3): queue a manual upload
                    // of the crawler's incremental DB dump to the R2 DB bucket
                    // (gates apply; the daily gate is bypassed); the page
                    // polls /api/crawler/backup/db for the result.
                    post("crawler/backup/db") {
                        val msg = crawlerBackupDbUpload()
                        val ok = !msg.startsWith("refused")
                        val token = parseCookieToken(call.request.headers["Cookie"])
                            ?: call.request.queryParameters["token"] ?: ""
                        call.respondRedirect("/settings/crawler?msg=${urlEnc(msg)}&ok=$ok&token=$token")
                    }

                    // DB backup status JSON for the crawler panel poller.
                    get("api/crawler/backup/db") {
                        call.respondText(dbBackupStatusJson(crawlerBackupDbStatus()), ContentType.Application.Json)
                    }

                    // DB restore (plan 2, Stage 4): queue an explicit restore
                    // of the crawler DB from the R2 dump chain (stops the
                    // crawler, downloads + gunzips the whole chain, runs
                    // `--restore-database`, resets the bookmark on success);
                    // the page polls /api/crawler/restore for the result.
                    post("crawler/restore/db") {
                        val msg = crawlerRestoreDb()
                        val ok = !msg.startsWith("refused")
                        val token = parseCookieToken(call.request.headers["Cookie"])
                            ?: call.request.queryParameters["token"] ?: ""
                        call.respondRedirect("/settings/crawler?msg=${urlEnc(msg)}&ok=$ok&token=$token")
                    }

                    // DB restore status JSON for the crawler panel poller.
                    get("api/crawler/restore") {
                        call.respondText(restoreStatusJson(crawlerRestoreDbStatus()), ContentType.Application.Json)
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
        <style>$WEBUI_CSS
        .box{background:#fff3cd;border:1px solid #ffeeba;border-radius:8px;padding:1em}</style></head>
        <body><div class="wrap"><h1>Setup required</h1>
        <div class="box"><p>Open the <strong>Torrent App</strong> on this Android device and complete
        the first-run wizard (grant the USB drive, set Transmission credentials).</p>
        <p>This control page becomes available once setup is finished.</p></div>
        <p><a href="/login">Log in</a></p></div></body></html>
    """.trimIndent()

    private fun buildLoginPage(error: Boolean): String = """
        <!DOCTYPE html><html><head><title>Torrent App — Login</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>$WEBUI_CSS
        .err{color:#721c24}</style></head>
        <body><div class="wrap"><h1>Login</h1>
        ${if (error) """<p class="err">Invalid token.</p>""" else ""}
        <form method="post" action="/login">
        <label>Control token:<input type="password" name="token" autofocus></label>
        <button type="submit">Log in</button>
        </form>
        <p class="meta">The token is shown in the Android app's main screen.</p></div></body></html>
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
                <form method="post" action="/daemon/$escapedLabel/start"><button class="btn">Start</button></form>
                <form method="post" action="/daemon/$escapedLabel/stop"><button class="btn">Stop</button></form>
                <form method="post" action="/daemon/$escapedLabel/restart"><button class="btn">Restart</button></form>
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
            <style>$WEBUI_CSS
              h2{margin:1.5em 0 .4em}
              .tx td+td{overflow-wrap:normal}
              .actions{white-space:nowrap}
              .actions form{display:inline;margin:0}
              .actions .btn{min-height:36px;min-width:0;padding:0 10px;font-size:.8rem;margin:0 2px;border-radius:18px}
              a.cfg{display:inline-block;margin:.5em .5em .5em 0;padding:.4em .8em;background:var(--card);border-radius:8px;text-decoration:none;border:1px solid #dadce0;color:var(--blue);font-weight:600}
              .warn{background:#f8d7da;color:#721c24;padding:.6em;border-radius:8px;margin:1em 0}
            </style></head><body>
            <div class="wrap wide">
            <header><h1>Torrent App Control</h1><nav><a href="/">Dashboard</a><a href="/stats">Crawler stats</a><a href="/search">Search</a></nav></header>
            <p class="meta">Service: <span class="state $state">$state</span>$rollbackBanner</p>
            <p>
              <a class="cfg" href="/settings/transmission">Transmission settings</a>
              <a class="cfg" href="/settings/ftp">FTP settings</a>
              <a class="cfg" href="/settings/storage">Storage</a>
              <a class="cfg" href="/settings/crawler">Crawler</a>
              <a class="cfg" href="/stats">Crawler Stats</a>
              <a class="cfg" href="/search">Search</a>
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
            </div>
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

    internal fun buildTransmissionPanel(msg: String?, ok: Boolean): String {
        val cfg = loadConfig()
        val s = cfg.transmission
        val banner = if (msg != null)
            """<div class="${if (ok) "ok" else "err"}">${esc(msg)}</div>""" else ""
        return """
            <!DOCTYPE html><html><head><title>Transmission settings</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$WEBUI_CSS</style></head><body>
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

    internal fun buildFtpPanel(msg: String?, ok: Boolean): String {
        val s = loadConfig().ftp
        val banner = if (msg != null)
            """<div class="${if (ok) "ok" else "err"}">${esc(msg)}</div>""" else ""
        val checked = if (s.wifiOnly) "checked" else ""
        return """
            <!DOCTYPE html><html><head><title>FTP settings</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$WEBUI_CSS</style></head><body>
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

    internal fun buildStoragePanel(): String {
        val s = safStatus()
        val free = usbFreeSpace()
        val f = ftpStatus()
        return """
            <!DOCTYPE html><html><head><title>Storage</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$WEBUI_CSS</style></head><body>
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

    internal fun buildCrawlerPanel(msg: String?, ok: Boolean, rebuildBloom: Boolean): String {
        val s = loadConfig().crawler
        val sBackup = loadConfig().backup
        val banner = if (msg != null)
            """<div class="${if (ok) "ok" else "err"}">${esc(msg)}</div>""" else ""
        val checked = { b: Boolean -> if (b) "checked" else "" }
        val levelOptions = CrawlerKeys.LOG_LEVELS.joinToString("") { l ->
            """<option value="$l"${if (l == s.logLevel) " selected" else ""}>$l</option>"""
        }
        val adultModeOptions = CrawlerKeys.ADULT_MODES.joinToString("") { m ->
            """<option value="$m"${if (m == s.adultMode.trim().lowercase()) " selected" else ""}>$m</option>"""
        }
        val rebuildBox = if (rebuildBloom) """
            <div class="warn">Bloom filter settings changed — the persisted filters are now stale.
            <form method="post" action="/crawler/maintenance/rebuild-bloom-filter"
                  onsubmit="return confirm('Rebuild the bloom filter? The crawler is stopped while it runs (a few minutes).')">
            <button type="submit">Run --rebuild-bloom-filter</button></form></div>""" else ""
        val maintenanceSection = buildMaintenanceSection()
        val backupSection = buildBackupSection()
        val dbBackupSection = buildDbBackupSection()
        return """
            <!DOCTYPE html><html><head><title>DHT crawler settings</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$WEBUI_CSS
            .warn-num{color:#721c24;font-weight:700}
            #apply-status{background:#e7f1ff;padding:.6em;border-radius:4px;margin:1em 0;font-size:.9em}
            #apply-status.busy{background:#fff3cd}
            pre.apply-log{margin:.4em 0 0;padding:8px;background:#fafafa;border-radius:4px;font-size:.8em;max-height:200px;overflow-y:auto;white-space:pre-wrap;word-break:break-all}
            #maint-btns form{display:inline-block;margin:0 .5em .5em 0}
            #maint-btns button{margin:0;padding:.4em .8em}
            #maint-status{font-size:.9em}
            #maint-status.busy{background:#fff3cd;padding:.4em;border-radius:4px}
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
                <p class="meta">Estimated worker threads: <span id="est-threads"></span><span id="est-warn" style="display:none;color:#721c24"> — above ${CrawlerKeys.THREAD_WARN_THRESHOLD}; the default config uses ~1216</span></p>
              </fieldset>
              <fieldset><legend>Filters</legend>
                <label class="row"><input type="checkbox" name="pornFilterEnabled" ${checked(s.pornFilterEnabled)}> Porn filter</label>
                <label>Non-Latin language threshold (0..100)<input name="languageFilterNonLatinThreshold" value="${s.languageFilterNonLatinThreshold}"></label>
              </fieldset>
              <fieldset><legend>Adult content handling</legend>
                <label class="row"><input type="checkbox" name="classifierEnabled" ${checked(s.classifierEnabled)}> Ingest-time classifier (on by default)</label>
                <label>Adult mode<select name="adultMode">$adultModeOptions</select></label>
                <label>Adult hide threshold (0..1; only used by tag mode)<input type="number" step="any" name="adultHideThreshold" value="${s.adultHideThreshold}"></label>
                <label>Adult drop threshold (0..1; only used by drop mode)<input type="number" step="any" name="adultDropThreshold" value="${s.adultDropThreshold}"></label>
                <p class="meta">The hard-block list (<code>hard_block.txt</code>) always drops matching names. <em>tag</em> stores
                adult rows (hidden above the hide threshold); <em>drop</em> discards rows at or above the drop threshold. The
                keyword porn filter above only runs while the classifier cannot decide (disabled, or its models are missing).</p>
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
                <label class="row"><input type="checkbox" name="dbBackupEnabled" ${checked(sBackup.dbBackupEnabled)}> Enable daily DB dump upload (uses the R2 credentials above)</label>
                <label>R2 DB bucket<input name="r2DbBucket" value="${esc(sBackup.r2DbBucket)}"></label>
                <label>R2 DB object prefix<input name="r2DbObjectPrefix" value="${esc(sBackup.r2DbObjectPrefix)}"></label>
                <label class="row"><input type="checkbox" name="bootstrapEnabled" ${checked(sBackup.bootstrapEnabled)}> Enable bootstrap download</label>
                <label>Bootstrap URL (https://…)<input name="bootstrapUrl" value="${esc(sBackup.bootstrapUrl)}"></label>
              </fieldset>
              <button type="submit">Apply</button>
            </form>
            $rebuildBox
            <div id="apply-status">Apply status: IDLE</div>
            <pre id="apply-log" class="apply-log" style="display:none"></pre>
            $maintenanceSection
            $backupSection
            $dbBackupSection
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
              function pollBackup() {
                fetch('/api/crawler/backup').then(function (r) { return r.json(); }).then(function (st) {
                  var box = document.getElementById('backup-status');
                  var text = 'Backup: ' + st.state + (st.message ? ' \u2014 ' + st.message : '');
                  if (st.bytes != null) text += ' (' + st.bytes + ' bytes uploaded)';
                  if (st.lastSuccess != null) text += ' (last success ' + new Date(st.lastSuccess).toLocaleString() + ')';
                  box.textContent = text;
                }).catch(function () {});
              }
              setInterval(pollBackup, 5000); pollBackup();
              function pollDbBackup() {
                fetch('/api/crawler/backup/db').then(function (r) { return r.json(); }).then(function (st) {
                  var box = document.getElementById('db-backup-status');
                  var text = 'DB backup: ' + st.state + (st.message ? ' \u2014 ' + st.message : '');
                  if (st.bytes != null) text += ' (' + st.bytes + ' bytes uploaded)';
                  if (st.lastSuccess != null) text += ' (last success ' + new Date(st.lastSuccess).toLocaleString() + ')';
                  box.textContent = text;
                }).catch(function () {});
              }
              setInterval(pollDbBackup, 5000); pollDbBackup();
              function pollDbRestore() {
                fetch('/api/crawler/restore').then(function (r) { return r.json(); }).then(function (st) {
                  var box = document.getElementById('db-restore-status');
                  var text = 'DB restore: ' + st.state + (st.message ? ' \u2014 ' + st.message : '');
                  if (st.objectCount != null) text += ' (' + st.objectCount + ' objects)';
                  if (st.lastSuccess != null) text += ' (last success ' + new Date(st.lastSuccess).toLocaleString() + ')';
                  box.textContent = text;
                }).catch(function () {});
              }
              setInterval(pollDbRestore, 5000); pollDbRestore();
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

    /** The BEP51 cache backup section on the crawler panel (Stage 4). */
    private fun buildBackupSection(): String {
        val st = crawlerBackupStatus()
        val enabledNote = if (st.uploadEnabled) "" else
            "<p class=\"warn\">R2 upload is disabled — tick <em>Enable R2 upload</em> above and apply first.</p>"
        return """
            <fieldset><legend>Cache backup (upload the crawler's BEP51 bootstrap cache to R2)</legend>
              <p class="meta">Uploads <code>bep51_cache.dat</code> (validated + gzip-compressed) to the R2
              object key above — at most once per day, on Wi-Fi, and only when no apply/maintenance
              job is running. <em>Upload now</em> bypasses the daily gate only.</p>
              $enabledNote
              <form method="post" action="/crawler/backup/upload"
                    onsubmit="return confirm('Upload the BEP51 cache to R2 now?')">
              <button type="submit">Upload now</button></form>
              <div id="backup-status" class="meta">Backup: ${st.state.name}</div>
            </fieldset>
        """.trimIndent()
    }

    /** The DB dump backup + restore section on the crawler panel (plan 2, Stage 3+4). */
    private fun buildDbBackupSection(): String {
        val st = crawlerBackupDbStatus()
        val rst = crawlerRestoreDbStatus()
        val enabledNote = if (st.uploadEnabled) "" else
            "<p class=\"warn\">DB dump upload is disabled — tick <em>Enable daily DB dump upload</em> above and apply first.</p>"
        return """
            <fieldset><legend>DB backup (upload the crawler's incremental SQLite dump to the R2 DB bucket)</legend>
              <p class="meta">Dumps new torrents (<code>torrents.db</code>) as gzip-compressed SQL to
              <code>&lt;db-prefix&gt;/incremental_&lt;date&gt;_&lt;start&gt;-&lt;end&gt;.sql.gz</code> — at most once per day, on
              Wi-Fi, and only when no apply/maintenance job is running. The upload bookmark advances only
              after a successful upload, so a failed run retries the same range.
              <em>Upload now</em> bypasses the daily gate only.</p>
              $enabledNote
              <form method="post" action="/crawler/backup/db"
                    onsubmit="return confirm('Upload the crawler DB dump to R2 now?')">
              <button type="submit">Upload now</button></form>
              <div id="db-backup-status" class="meta">DB backup: ${st.state.name}</div>
            </fieldset>
            <fieldset><legend>DB restore (replay the R2 dump chain into a fresh database)</legend>
              <p class="meta">Stops the crawler, downloads + gunzips every dump object in the DB bucket
              (in chain order), and runs <code>libdhtcrawler.so --restore-database</code> to replay them into a
              fresh <code>torrents.db</code>, swapped in atomically. On success the upload bookmark resets to
              first run. The crawler is restarted afterwards in all outcomes. Needs the R2 endpoint/keys
              and the DB bucket configured (the upload toggle is not required).</p>
              <form method="post" action="/crawler/restore/db"
                    onsubmit="return confirm('Restore the crawler DB from R2 now? This replaces torrents.db and the crawler is stopped while it runs.')">
              <button type="submit">Restore now</button></form>
              <div id="db-restore-status" class="meta">DB restore: ${rst.state.name}</div>
            </fieldset>
        """.trimIndent()
    }

    private fun crawlerSection(): String {
        // Search dashboard is same-origin on :8090 (/search); the proxied C UI was retired in stage 4.
        val d = daemonStatuses().firstOrNull { it.label == DaemonService.CRAWLER_LABEL }
        val state = d?.state ?: ProcessState.STOPPED
        val uptime = d?.startTimeMs?.let { formatUptime(System.currentTimeMillis() - it) } ?: "—"
        val logs = d?.lastLogLines?.takeLast(30)?.joinToString("<br>") { esc(it) } ?: "—"
        val dbSize = crawlerDbSize()
        val free = internalFreeSpace()
        val spaceWarn = if (free < CRAWLER_LOW_FREE_SPACE_BYTES) " <span class=\"warn\">low free space</span>" else ""
        val paused = if (crawlerPausedNoWifi()) " <span class=\"state STOPPED\">paused: no wifi</span>" else ""
        val searchUrl = "/search"
        return """
            <p class="meta">State: <span class="state $state">$state</span>$paused
               &nbsp; PID: <strong>${d?.pid ?: "—"}</strong>
               &nbsp; Uptime: <strong>$uptime</strong>
               &nbsp; Restarts: <strong>${d?.restartCount ?: 0}</strong></p>
            <p class="meta">DB: <strong>${dbSize?.let { formatBytes(it) } ?: "—"}</strong>
               &nbsp; Internal free: <strong>${formatBytes(free)}</strong>$spaceWarn</p>
            <p class="meta">Search dashboard: <a href="$searchUrl">$searchUrl</a></p>
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
     * The crawler stats payload served by /api/crawler/stats and pushed over
     * the stats websocket: the crawler's own /stats JSON while the daemon is
     * RUNNING, otherwise the clean "not running" fallback — so a connected
     * client recovers automatically when the crawler comes back up.
     */
    private fun crawlerStatsPayload(): String {
        val crawler = daemonStatuses()
            .firstOrNull { it.label == DaemonService.CRAWLER_LABEL }
        val body = if (crawler?.state == ProcessState.RUNNING) fetchCrawlerStats() else null
        return crawlerStatsJson(body, crawler?.state)
    }

    /**
     * Fetches the crawler's own /stats JSON over loopback. Returns null when
     * the crawler is unreachable — the caller responds with the clean
     * "not running" fallback.
     */
    private fun fetchCrawlerStats(): String? = fetchCrawlerJson("/stats")

    /**
     * Loopback GET of the crawler's JSON API ([pathAndQuery] must start with
     * `/`). Returns the raw body on 200, null on any error/non-200. The read
     * timeout is generous (15s) because search/browse queries can be slow;
     * the connect timeout stays short so a down crawler fails fast.
     */
    private fun fetchCrawlerJson(pathAndQuery: String): String? {
        return try {
            val url = "http://127.0.0.1:${crawlerHttpPort()}$pathAndQuery"
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 15_000
                instanceFollowRedirects = false
            }
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Answers one /search/ws text frame. Never throws: an unparseable frame
     * yields `bad_request` (id 0), a missing/invalid `id` or `type` yields
     * `bad_request: <field>`, an unreachable crawler yields `unavailable`, a
     * non-object body yields `db_error`, and success carries the crawler's raw
     * JSON body verbatim under `payload` (so escaping stays the crawler's).
     */
    private fun handleSearchFrame(text: String): String {
        val obj: JSONObject = try {
            JSONObject(text)
        } catch (e: Exception) {
            return """{"id":0,"ok":false,"error":"bad_request"}"""
        }
        val rawId = obj.opt("id")
        val id = when (rawId) {
            is Int -> rawId
            is Long -> rawId.toInt()
            else -> return """{"id":0,"ok":false,"error":"bad_request: id"}"""
        }
        val result = buildCrawlerQuery(
            type = searchField(obj, "type"),
            q = searchField(obj, "q"),
            category = searchField(obj, "category"),
            tvKind = searchField(obj, "tv_kind"),
            adult = searchField(obj, "adult"),
            sort = searchField(obj, "sort"),
            page = searchField(obj, "page"),
        )
        return when (result) {
            is CrawlerQueryResult.Bad ->
                "{\"id\":$id,\"ok\":false,\"error\":\"bad_request: ${result.field}\"}"
            is CrawlerQueryResult.Ok -> {
                val body = fetchCrawlerJson(result.path)
                    ?: return "{\"id\":$id,\"ok\":false,\"error\":\"unavailable\"}"
                val isObject = try {
                    JSONObject(body)
                    true
                } catch (e: Exception) {
                    false
                }
                if (!isObject) "{\"id\":$id,\"ok\":false,\"error\":\"db_error\"}"
                else "{\"id\":$id,\"ok\":true,\"payload\":$body}"
            }
        }
    }

    /** Reads a text field from a parsed frame; JSON null and absent keys are null. */
    private fun searchField(obj: JSONObject, key: String): String? {
        val v = obj.opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return v.toString()
    }

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
    private fun formatMtime(ms: Long): String =
        if (ms <= 0) "—" else java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    companion object {
        /** HTML-escapes a value for embedding in dashboard markup. */
        internal fun esc(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;")

        /** One /search/ws query-builder outcome (search-dashboard stage 2). */
        internal sealed interface CrawlerQueryResult {
            data class Ok(val path: String) : CrawlerQueryResult
            data class Bad(val field: String) : CrawlerQueryResult
        }

        private val SEARCH_CATEGORIES = setOf(
            "unknown", "movie", "tv", "anime", "music", "audiobook",
            "ebook", "comic", "software", "game", "image", "other")
        private val SEARCH_TV_KINDS = setOf(
            "none", "episode", "multi_episode", "season_pack",
            "multi_season", "complete_series", "daily")
        private val SEARCH_ADULT_MODES = setOf("hide", "show", "only")
        private val SEARCH_SORTS = setOf("peers", "added", "size")

        /**
         * Validates one /search/ws request and builds the crawler loopback path
         * (+query) for it, or reports the single offending field. The path is
         * built only from enumerated/parsed values with a deterministic param
         * order; absent optional params are omitted so the crawler defaults
         * apply. `q` is percent-encoded (URLEncoder, UTF-8). Pure: no Android,
         * no network, so it is unit-testable.
         */
        internal fun buildCrawlerQuery(
            type: String?,
            q: String?,
            category: String?,
            tvKind: String?,
            adult: String?,
            sort: String?,
            page: String?,
        ): CrawlerQueryResult {
            if (type != "search" && type != "browse" && type != "random") {
                return CrawlerQueryResult.Bad("type")
            }
            val cat = category?.takeIf { it.isNotEmpty() }
            if (cat != null && cat !in SEARCH_CATEGORIES) return CrawlerQueryResult.Bad("category")
            val tv = tvKind?.takeIf { it.isNotEmpty() }
            if (tv != null && tv !in SEARCH_TV_KINDS) return CrawlerQueryResult.Bad("tv_kind")
            val ad = adult?.takeIf { it.isNotEmpty() }
            if (ad != null && ad !in SEARCH_ADULT_MODES) return CrawlerQueryResult.Bad("adult")

            val parts = mutableListOf<String>()
            if (type == "search") {
                if (q.isNullOrBlank()) return CrawlerQueryResult.Bad("q")
                parts += "q=" + URLEncoder.encode(q, "UTF-8")
            }
            if (cat != null) parts += "category=$cat"
            if (tv != null) parts += "tv_kind=$tv"
            if (ad != null) parts += "adult=$ad"
            if (type == "browse") {
                val s = sort?.takeIf { it.isNotEmpty() }
                if (s != null && s !in SEARCH_SORTS) return CrawlerQueryResult.Bad("sort")
                if (s != null) parts += "sort=$s"
            }
            if (type == "random") parts += "min_peers=10"
            if (type != "random") {
                // page applies to search/browse only; absent -> crawler default 1.
                val raw = page?.takeIf { it.isNotEmpty() }
                if (raw != null) {
                    val n = raw.toIntOrNull()
                    if (n == null || n < 1) return CrawlerQueryResult.Bad("page")
                    parts += "page=$n"
                }
            }
            val base = "/$type"
            return CrawlerQueryResult.Ok(
                if (parts.isEmpty()) base else "$base?" + parts.joinToString("&"))
        }

        /**
         * The /search page (search-dashboard stage 3a): a static, self-contained
         * blue phone search shell. All markup, CSS and JS are inline — no
         * external assets, no CDN, no fonts. The search/browse/random/
         * infinite-scroll/add-button interaction logic lives in [SEARCH_JS],
         * wired to the authenticated /search/ws feed (token via
         * `location.search`).
         * [proxyOrigin] is the crawler proxy origin (3b uses it for per-torrent
         * detail links); it defaults to the loopback address so the page stays
         * unit-testable without a running app.
         */
        internal fun buildSearchPage(proxyOrigin: String = "http://127.0.0.1:8080"): String = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <title>Search</title>
            <style>$SEARCH_CSS</style>
            </head>
            <body>
            <!-- Search page: wired to the authenticated /search/ws feed (token via location.search). -->
            <div class="wrap">
            <header>
              <h1>Search</h1>
              <nav>
                <a href="/">Dashboard</a>
                <a href="/stats">Crawler stats</a>
                <span id="live" aria-label="live" title="live"></span>
              </nav>
            </header>
            <div class="control-bar">
              <div class="search-row">
                <input id="search-input" type="search" placeholder="Search torrents…" autocomplete="off" enterkeyhint="search">
                <button id="search-btn" class="btn" type="button">🔍 Search</button>
              </div>
              <div class="search-row">
                <button id="randomize-btn" class="btn secondary" type="button">🎲 Randomize</button>
                <select id="sort" aria-label="Sort">
                  <option value="peers">Peers</option>
                  <option value="added">Newest</option>
                  <option value="size">Largest</option>
                </select>
              </div>
            </div>
            <div id="category-grid">
              <button class="chip" type="button" data-category="" aria-pressed="true">All</button>
              <button class="chip" type="button" data-category="movie" aria-pressed="false">Movie</button>
              <button class="chip" type="button" data-category="tv" aria-pressed="false">TV</button>
              <button class="chip" type="button" data-category="anime" aria-pressed="false">Anime</button>
              <button class="chip" type="button" data-category="music" aria-pressed="false">Music</button>
              <button class="chip" type="button" data-category="audiobook" aria-pressed="false">Audiobook</button>
              <button class="chip" type="button" data-category="ebook" aria-pressed="false">Ebook</button>
              <button class="chip" type="button" data-category="comic" aria-pressed="false">Comic</button>
              <button class="chip" type="button" data-category="software" aria-pressed="false">Software</button>
              <button class="chip" type="button" data-category="game" aria-pressed="false">Game</button>
              <button class="chip" type="button" data-category="image" aria-pressed="false">Image</button>
              <button class="chip" type="button" data-category="other" aria-pressed="false">Other</button>
            </div>
            <div id="tv-kind-row" hidden>
              <button class="chip" type="button" data-tv-kind="none" aria-pressed="true">None</button>
              <button class="chip" type="button" data-tv-kind="episode" aria-pressed="false">Episodes</button>
              <button class="chip" type="button" data-tv-kind="multi_episode" aria-pressed="false">Multi-episode</button>
              <button class="chip" type="button" data-tv-kind="season_pack" aria-pressed="false">Season packs</button>
              <button class="chip" type="button" data-tv-kind="multi_season" aria-pressed="false">Multi-season</button>
              <button class="chip" type="button" data-tv-kind="complete_series" aria-pressed="false">Complete series</button>
              <button class="chip" type="button" data-tv-kind="daily" aria-pressed="false">Daily</button>
            </div>
            <div class="seg-row"><span class="seg-label">Adult</span><div id="adult-control" class="seg" role="group" aria-label="Adult results"><button class="chip" type="button" data-adult="hide" aria-pressed="true">Hide</button><button class="chip" type="button" data-adult="show" aria-pressed="false">Show</button><button class="chip" type="button" data-adult="only" aria-pressed="false">Only</button></div></div>
            <div id="results-status">Pick a category to browse</div>
            <div id="banner" class="banner" hidden></div>
            <div id="spinner" hidden aria-hidden="true"></div>
            <div id="results"></div>
            <div id="sentinel"></div>
            <button id="load-more" class="btn secondary" type="button" hidden>Load more</button>
            <div id="toast" class="toast" hidden></div>
            </div>
            <script>
            const proxyOrigin = '$proxyOrigin';
            $SEARCH_JS
            </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * Shared WebUI theme for every server-rendered page (search-dashboard
         * stage 5a): the blue palette, the 640px centred phone column, the
         * header/nav shell, the live status dot and the reusable controls
         * (buttons, chips, cards, banners, badges, toasts, meta rows).
         *
         * An `internal val` (not `const val`) because it is a `trimIndent()`
         * expression, and `internal` so the unit tests can pin the palette. Page
         * CSS is built as `WEBUI_CSS + "\n" + <page-specific rules>`; companion
         * vals initialise in declaration order, so it is declared before
         * `SEARCH_CSS`.
         */
        internal val WEBUI_CSS = """
            :root{--blue:#1a73e8;--blue-dark:#1557b0;--chip:#e8f0fe;--text:#1f2937;--muted:#5f6368;--card:#fff}
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
            html,body{margin:0;padding:0}
            body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;color:var(--text);font-size:16px;line-height:1.4;background:linear-gradient(180deg,#eef4fd,#f5f7fa);min-height:100vh}
            .wrap{max-width:640px;margin:0 auto;padding:0 12px calc(24px + env(safe-area-inset-bottom))}
            header{padding:16px 0 8px;display:flex;flex-wrap:wrap;align-items:center;gap:4px 16px}
            h1{margin:0;font-size:1.5rem;color:var(--blue);flex:1}
            nav{display:flex;align-items:center;flex-wrap:wrap;gap:16px}
            nav a{color:var(--blue);text-decoration:none;font-weight:600;font-size:.95rem}
            nav a:hover{color:var(--blue-dark)}
            #live{display:inline-block;width:10px;height:10px;border-radius:50%;background:#c5221f}
            #live.on{background:#188038}
            #live.wait{background:#f9ab00}
            .btn{display:inline-flex;align-items:center;justify-content:center;min-height:44px;min-width:44px;padding:0 18px;font-size:.95rem;font-weight:600;color:#fff;background:var(--blue);border:none;border-radius:22px;cursor:pointer;touch-action:manipulation}
            .btn:hover{background:var(--blue-dark)}
            .btn.secondary{background:var(--chip);color:var(--blue)}
            .btn.secondary:hover{background:#d2e3fc}
            .chip{display:inline-flex;align-items:center;min-height:44px;padding:0 16px;font-size:.9rem;color:var(--text);background:var(--card);border:1px solid #dadce0;border-radius:22px;cursor:pointer;touch-action:manipulation}
            .chip:hover{border-color:var(--blue)}
            .chip[aria-pressed="true"]{background:var(--chip);border-color:var(--blue);color:var(--blue);font-weight:600}
            .card{background:var(--card);border:1px solid #e3e7ee;border-radius:12px;padding:12px;box-shadow:0 1px 3px rgba(0,0,0,.06)}
            .banner{padding:.6em .8em;border-radius:8px;margin:8px 0;font-size:.9rem}
            .banner[hidden]{display:none}
            .banner.red{background:#fce8e6;color:#c5221f}
            .banner.amber{background:#fef7e0;color:#7a5900}
            .meta{display:flex;flex-wrap:wrap;align-items:center;gap:6px 10px;margin-top:8px;font-size:.85rem;color:var(--muted)}
            .badge{background:var(--chip);color:var(--blue);border-radius:10px;padding:1px 8px;font-size:.78rem;font-weight:600}
            .toast{position:fixed;left:50%;bottom:calc(24px + env(safe-area-inset-bottom));transform:translateX(-50%);max-width:90vw;padding:10px 16px;border-radius:8px;background:#202124;color:#fff;font-size:.9rem;z-index:60;box-shadow:0 2px 8px rgba(0,0,0,.3)}
            .toast[hidden]{display:none}
            .toast.err{background:#c5221f}
            .wrap.wide{max-width:860px}
            label{display:block;margin:.6em 0}
            label input,label select,label textarea{display:block;width:100%;margin-top:2px;padding:8px;font-size:1rem;color:var(--text);border:1px solid #dadce0;border-radius:8px;background:#fff;box-sizing:border-box;touch-action:manipulation}
            label input[type="checkbox"],label input[type="radio"]{display:inline;width:auto;margin:0 6px 0 0}
            fieldset{margin:.8em 0;border:1px solid #ddd;border-radius:8px}
            legend{padding:0 6px;font-weight:600;color:var(--blue)}
            fieldset label{display:inline-block;width:auto;vertical-align:middle}
            fieldset input,fieldset select{display:inline;width:auto}
            label.row{display:block}
            button:not(.btn):not(.chip){display:inline-flex;align-items:center;justify-content:center;min-height:44px;padding:.5em 1.2em;margin-top:1em;font-size:.95rem;font-weight:600;color:#fff;background:var(--blue);border:none;border-radius:22px;cursor:pointer;touch-action:manipulation}
            button:not(.btn):not(.chip):hover{background:var(--blue-dark)}
            .ok{background:#d4edda;color:#155724;padding:.6em;border-radius:8px;margin:1em 0}
            .err{background:#f8d7da;color:#721c24;padding:.6em;border-radius:8px;margin:1em 0}
            .warn{background:#fff3cd;color:#856404;padding:.6em;border-radius:8px;margin:1em 0}
            table{width:100%;border-collapse:collapse;margin:1em 0;background:var(--card);border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.1)}
            th{background:var(--chip);color:var(--blue);padding:8px 12px;text-align:left;font-size:.85em;text-transform:uppercase;letter-spacing:.05em}
            td{padding:8px 12px;border-top:1px solid #eee;vertical-align:top}
            th,td,code{overflow-wrap:anywhere}
            pre,code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
            pre{padding:8px;background:#fafafa;border-radius:8px;font-size:.8em;max-height:200px;overflow-y:auto;white-space:pre-wrap;word-break:break-all}
            pre.loglines{margin:4px 0 0}
            .state{display:inline-block;padding:2px 10px;border-radius:999px;font-size:.85em;font-weight:600;white-space:nowrap}
            .state.RUNNING{background:#d4edda;color:#155724}
            .state.STARTING{background:#fff3cd;color:#856404}
            .state.STOPPING{background:#f8d7da;color:#721c24}
            .state.STOPPED{background:#e2e3e5;color:#383d41}
            .banner.ok{background:#e6f4ea;color:#137333}
        """.trimIndent()

        /**
         * The /search page CSS (search-dashboard stage 3a): the shared WebUI
         * theme plus the search-only control bar, result-card details and paging
         * rules. A `private val` (not `const val`) because it is a
         * `trimIndent()` expression.
         */
        private val SEARCH_CSS = WEBUI_CSS + "\n" + """
            .control-bar{position:sticky;top:0;z-index:20;background:var(--card);margin:0 -12px;padding:calc(8px + env(safe-area-inset-top)) calc(12px + env(safe-area-inset-right)) 8px calc(12px + env(safe-area-inset-left));box-shadow:0 1px 4px rgba(0,0,0,.12)}
            .search-row{display:flex;align-items:center;gap:8px}
            .search-row+.search-row{margin-top:8px}
            #search-input{flex:1 1 auto;min-width:0;height:48px;padding:0 16px;font-size:1rem;color:var(--text);border:1px solid #dadce0;border-radius:24px;outline:none;background:#fff}
            #search-input:focus{border-color:var(--blue);box-shadow:0 0 0 2px var(--chip)}
            #sort{height:44px;min-width:44px;padding:0 12px;font-size:.95rem;color:var(--text);border:1px solid #dadce0;border-radius:22px;background:#fff;touch-action:manipulation}
            #category-grid{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}
            #tv-kind-row{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 12px}
            #tv-kind-row[hidden]{display:none}
            .seg{display:inline-flex;flex-wrap:wrap;gap:8px;margin:0 0 12px}
            .seg-row{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:0 0 12px}
            .seg-label{color:var(--muted);font-size:.9rem;font-weight:600}
            #results-status{color:var(--muted);font-size:.9rem;margin:8px 0 4px}
            #spinner{width:36px;height:36px;margin:24px auto;border:4px solid var(--chip);border-top-color:var(--blue);border-radius:50%;animation:spinseek .9s linear infinite}
            #spinner[hidden]{display:none}
            @keyframes spinseek{to{transform:rotate(360deg)}}
            #results{display:grid;grid-template-columns:1fr;gap:12px}
            #sentinel{height:1px}
            #load-more{display:inline-flex;margin:16px auto}
            #load-more[hidden]{display:none}
            .hidden{display:none}
            .card .name{font-weight:700;cursor:pointer;overflow-wrap:anywhere}
            .card .name:hover{color:var(--blue)}
            .filelist{margin:8px 0 0;padding:8px;background:#f5f7fa;border-radius:8px;font-size:.85rem}
            .filelist .frow{display:flex;justify-content:space-between;gap:8px}
            .filelist .frow span:first-child{overflow-wrap:anywhere}
            .filelist .more{color:var(--muted);margin-top:4px}
            .peers.hi{color:#188038}
            .peers.mid{color:#b06000}
            .peers.lo{color:#9aa0a6}
            .actions{display:flex;align-items:center;gap:14px;margin-top:10px}
            .add-btn{min-height:40px;padding:0 16px}
            .detail{color:var(--blue);text-decoration:none;font-size:1.1rem;font-weight:700;padding:8px}
        """.trimIndent()

        /**
         * The /search page interaction logic (search-dashboard stage 3b): the
         * authenticated /search/ws client, category/TV/adult/sort controls,
         * result cards, infinite scroll and the add-to-transmission button.
         */
        private val SEARCH_JS = """
            (function () {
              'use strict';
              var ws = null;
              var backoff = 1000;
              var MAX_BACKOFF = 30000;

              var sel = { category: null, tv_kind: null, adult: 'hide', sort: 'peers' };
              var current = null;
              var seq = 0;
              var activeId = 0;
              var loading = false;
              var autoLoadMore = false;
              var observer = null;
              var pendingAppend = false;
              var pendingReq = null;
              var toastTimer = null;

              function el(id) { return document.getElementById(id); }

              function setLive(state, label) {
                var s = el('live');
                s.className = state === 'ok' ? 'on' : (state === 'wait' ? 'wait' : '');
                if (label) s.title = label;
              }

              function statusText(t) { el('results-status').textContent = t; }

              function setLoading(on) {
                loading = !!on;
                var sp = el('spinner');
                if (on) sp.removeAttribute('hidden');
                else sp.setAttribute('hidden', '');
              }

              function showBanner(kind, msg) {
                var b = el('banner');
                b.textContent = msg;
                b.className = 'banner ' + kind;
                b.removeAttribute('hidden');
              }

              function hideBanner() { el('banner').setAttribute('hidden', ''); }

              function toast(msg, isErr) {
                var t = el('toast');
                t.textContent = msg;
                t.className = 'toast' + (isErr ? ' err' : '');
                t.removeAttribute('hidden');
                if (toastTimer) clearTimeout(toastTimer);
                toastTimer = setTimeout(function () { t.setAttribute('hidden', ''); }, 3000);
              }

              // Formatting helpers. All data reaches the DOM via textContent only.
              function fmtBytes(b) {
                if (typeof b !== 'number' || !isFinite(b)) return '—';
                var units = ['B', 'KB', 'MB', 'GB', 'TB'];
                var i = 0;
                while (b >= 1024 && i < units.length - 1) { b = b / 1024; i++; }
                var v = i === 0 ? b : Math.round(b * 10) / 10;
                return v + ' ' + units[i];
              }

              // `added` is an epoch-seconds or epoch-millis number; disambiguate
              // by magnitude (current seconds ~1.7e9, millis ~1.7e12).
              function relAge(added) {
                if (typeof added !== 'number' || !isFinite(added) || added <= 0) return '';
                var ms = added < 1e12 ? added * 1000 : added;
                var d = Date.now() - ms;
                if (d < 0) d = 0;
                var s = Math.floor(d / 1000);
                if (s < 60) return s + 's ago';
                var m = Math.floor(s / 60);
                if (m < 60) return m + 'm ago';
                var h = Math.floor(m / 60);
                if (h < 24) return h + 'h ago';
                var dd = Math.floor(h / 24);
                if (dd < 30) return dd + 'd ago';
                var mo = Math.floor(dd / 30);
                if (mo < 12) return mo + 'mo ago';
                return Math.floor(mo / 12) + 'y ago';
              }

              function sortLabel(s) {
                return s === 'added' ? 'newest' : (s === 'size' ? 'largest' : 'peers');
              }

              function badge(text) {
                var b = document.createElement('span');
                b.className = 'badge';
                b.textContent = text;
                return b;
              }

              function fillFilelist(box, r) {
                var files = r.files || [];
                var shown = Math.min(files.length, 10);
                for (var i = 0; i < shown; i++) {
                  var row = document.createElement('div');
                  row.className = 'frow';
                  var p = document.createElement('span');
                  p.textContent = files[i].path != null ? String(files[i].path) : '';
                  var sz = document.createElement('span');
                  sz.textContent = files[i].size != null
                    ? String(files[i].size) : fmtBytes(files[i].size_bytes);
                  row.appendChild(p);
                  row.appendChild(sz);
                  box.appendChild(row);
                }
                var total = typeof r.num_files === 'number' ? r.num_files : files.length;
                if (total > 10) {
                  var more = document.createElement('div');
                  more.className = 'more';
                  more.textContent = '\u2026 and ' + (total - 10) + ' more';
                  box.appendChild(more);
                }
              }

              function buildCard(r) {
                var card = document.createElement('div');
                card.className = 'card';

                var name = document.createElement('div');
                name.className = 'name';
                name.textContent = r.name != null ? String(r.name) : '(unnamed)';

                var filelist = document.createElement('div');
                filelist.className = 'filelist hidden';
                var built = false;
                name.onclick = function () {
                  if (!built) { fillFilelist(filelist, r); built = true; }
                  if (filelist.classList.contains('hidden')) filelist.classList.remove('hidden');
                  else filelist.classList.add('hidden');
                };
                card.appendChild(name);
                card.appendChild(filelist);

                var meta = document.createElement('div');
                meta.className = 'meta';
                if (r.category) meta.appendChild(badge(String(r.category)));
                if (r.tv_kind) meta.appendChild(badge(String(r.tv_kind)));

                var size = document.createElement('span');
                size.textContent = r.size != null ? String(r.size) : fmtBytes(r.size_bytes);
                meta.appendChild(size);

                var pc = typeof r.total_peers === 'number' ? r.total_peers : 0;
                var peers = document.createElement('span');
                peers.className = 'peers ' + (pc > 100 ? 'hi' : (pc > 10 ? 'mid' : 'lo'));
                peers.textContent = pc + ' peers';
                meta.appendChild(peers);

                if (typeof r.added === 'number' && r.added > 0) {
                  var age = document.createElement('span');
                  age.textContent = relAge(r.added);
                  meta.appendChild(age);
                }

                if (typeof r.confidence === 'number' && isFinite(r.confidence)) {
                  var conf = document.createElement('span');
                  var pct = r.confidence <= 1 ? r.confidence * 100 : r.confidence;
                  conf.textContent = 'confidence ' + Math.round(pct) + '%';
                  meta.appendChild(conf);
                }
                card.appendChild(meta);

                var actions = document.createElement('div');
                actions.className = 'actions';

                var add = document.createElement('button');
                add.type = 'button';
                add.className = 'btn add-btn';
                add.textContent = '\u2795 Add';
                add.onclick = function () { addTorrent(r.info_hash, add); };
                actions.appendChild(add);

                var detail = document.createElement('a');
                detail.className = 'detail';
                detail.textContent = '\u2026';
                detail.href = proxyOrigin + '/torrent?hash=' + encodeURIComponent(r.info_hash || '');
                detail.target = '_blank';
                detail.rel = 'noopener';
                actions.appendChild(detail);

                card.appendChild(actions);
                return card;
              }

              function render(payload, wasAppend) {
                hideBanner();
                if (!payload || typeof payload !== 'object') {
                  showBanner('red', 'Malformed response');
                  return;
                }
                var req = pendingReq || {};
                var box = el('results');
                var results = payload.results || [];
                if (!wasAppend) box.textContent = '';
                for (var i = 0; i < results.length; i++) box.appendChild(buildCard(results[i]));
                // Appended cards can leave the sentinel still intersecting, so no
                // new IntersectionObserver callback fires and infinite scroll stalls.
                // Re-observe to force a fresh intersection evaluation.
                if (observer) { observer.unobserve(el('sentinel')); observer.observe(el('sentinel')); }

                if (req.type === 'random') {
                  current = null;
                  statusText('Random pick');
                  return;
                }
                var page = typeof payload.page === 'number' ? payload.page : (req.page || 1);
                current = {
                  type: req.type, q: req.q, category: req.category, tv_kind: req.tv_kind,
                  adult: req.adult, sort: req.sort, page: page, has_more: !!payload.has_more
                };
                var n = typeof payload.total_count === 'number' ? payload.total_count : results.length;
                var txt = n + (n === 1 ? ' result' : ' results');
                if (req.type === 'browse') txt += ' \u00b7 sorted by ' + sortLabel(req.sort);
                statusText(txt);
              }

              // A NEW query bumps activeId; a load-more append reuses it so the
              // supersede marker stays stable across a multi-page result set.
              function send(obj, append) {
                if (!ws || ws.readyState !== 1) {
                  setLoading(false);
                  showBanner('amber', 'Not connected \u2014 reconnecting\u2026');
                  return;
                }
                var id;
                if (append && activeId) id = activeId;
                else { id = ++seq; activeId = id; }
                pendingAppend = !!append;
                pendingReq = {
                  type: obj.type, q: obj.q, category: obj.category,
                  tv_kind: obj.tv_kind, adult: obj.adult, sort: obj.sort, page: obj.page
                };
                setLoading(true);
                if (!append) statusText('Searching\u2026');
                obj.id = id;
                try { ws.send(JSON.stringify(obj)); }
                catch (e) { setLoading(false); showBanner('red', 'Send failed'); }
              }

              function sendBrowse() {
                send({
                  type: 'browse', category: sel.category, tv_kind: sel.tv_kind,
                  adult: sel.adult, sort: sel.sort, page: 1
                }, false);
              }

              function loadMore() {
                if (!current || !current.has_more || loading) return;
                var c = current;
                send({
                  type: c.type, q: c.q, category: c.category, tv_kind: c.tv_kind,
                  adult: c.adult, sort: c.sort, page: c.page + 1
                }, true);
              }

              function handleError(err) {
                err = err == null ? '' : String(err);
                statusText('');
                if (err === 'unavailable') {
                  setLive('down', 'unavailable');
                  showBanner('amber', 'Crawler is starting or stopped');
                } else if (err === 'db_error') {
                  showBanner('red', 'Crawler database error');
                } else if (err.indexOf('bad_request') === 0) {
                  var field = err.indexOf('bad_request:') === 0 ? err.slice(12).trim() : '';
                  showBanner('red', field ? 'Bad request: ' + field : 'Bad request');
                } else {
                  showBanner('red', 'Request failed');
                }
              }

              function onMessage(ev) {
                var data;
                try { data = JSON.parse(ev.data); } catch (e) { return; }
                // Latest-reply-wins: drop any frame that has been superseded.
                if (!data || typeof data.id !== 'number' || data.id !== activeId) return;
                setLoading(false);
                if (data.ok) {
                  setLive('ok', 'live');
                  render(data.payload, pendingAppend);
                } else {
                  if (pendingAppend) current = null;
                  handleError(data.error);
                }
              }

              function connect() {
                var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
                // Same-origin upgrade: the control_token cookie rides along and
                // a ?token= in the page URL does too. Never inline the token.
                ws = new WebSocket(proto + location.host + '/search/ws' + location.search);
                ws.onopen = function () {
                  backoff = 1000;
                  setLive('ok', 'live');
                  hideBanner();
                  setLoading(false);
                  if (!current) statusText('Live \u2014 pick a category to browse');
                };
                ws.onmessage = onMessage;
                ws.onclose = function () {
                  setLive('wait', 'reconnecting');
                  setLoading(false);
                  showBanner('amber', 'Connecting\u2026 (search feed unavailable)');
                  var wait = backoff;
                  backoff = Math.min(backoff * 2, MAX_BACKOFF);
                  setTimeout(connect, wait);
                };
                ws.onerror = function () { try { ws.close(); } catch (e) {} };
              }

              function setupObserver() {
                if (typeof window.IntersectionObserver !== 'function') {
                  // Fallback: expose the explicit Load more button.
                  var lm = el('load-more');
                  lm.removeAttribute('hidden');
                  lm.onclick = function () { loadMore(); };
                  return;
                }
                observer = new IntersectionObserver(function (entries) {
                  for (var i = 0; i < entries.length; i++) {
                    if (entries[i].isIntersecting && current && current.has_more && !loading) {
                      loadMore();
                    }
                  }
                }, { rootMargin: '150px' });
                observer.observe(el('sentinel'));
              }

              function addTorrent(hash, btn) {
                if (!hash) { toast('Bad info hash', true); return; }
                btn.disabled = true;
                fetch('/api/transmission/add?hash=' + encodeURIComponent(hash), { method: 'POST' })
                  .then(function (resp) { return resp.json(); })
                  .then(function (d) {
                    btn.disabled = false;
                    if (!d || !d.ok) {
                      var map = {
                        bad_hash: 'Bad info hash',
                        db_unavailable: 'Crawler DB unavailable',
                        not_found: 'Torrent not found in the crawler DB',
                        no_storage: 'No storage configured',
                        insufficient_space: 'Not enough free space',
                        rpc_failed: 'Transmission RPC error'
                      };
                      toast((d && map[d.error]) ? map[d.error] : 'Add failed', true);
                      return;
                    }
                    toast(d.duplicate ? 'Already added (duplicate)' : 'Added to Transmission', false);
                  })
                  .catch(function () { btn.disabled = false; toast('Add failed', true); });
              }

              function selectOnly(row, chip) {
                var chips = row.querySelectorAll('.chip');
                for (var i = 0; i < chips.length; i++) {
                  chips[i].setAttribute('aria-pressed', chips[i] === chip ? 'true' : 'false');
                }
              }

              function clearResults() {
                el('results').textContent = '';
                current = null;
              }

              el('category-grid').onclick = function (ev) {
                var t = ev.target;
                var cat = t && t.getAttribute ? t.getAttribute('data-category') : null;
                if (cat == null) return;
                selectOnly(el('category-grid'), t);
                sel.category = cat === '' ? null : cat;
                clearResults();
                var tvRow = el('tv-kind-row');
                if (cat === 'tv' || cat === 'anime') {
                  tvRow.removeAttribute('hidden');
                } else {
                  tvRow.setAttribute('hidden', '');
                  sel.tv_kind = null;
                  selectOnly(tvRow, tvRow.querySelector('.chip[data-tv-kind="none"]'));
                }
                sendBrowse();
              };

              el('tv-kind-row').onclick = function (ev) {
                var t = ev.target;
                var k = t && t.getAttribute ? t.getAttribute('data-tv-kind') : null;
                if (k == null) return;
                selectOnly(el('tv-kind-row'), t);
                sel.tv_kind = k === 'none' ? null : k;
                clearResults();
                sendBrowse();
              };

              el('adult-control').onclick = function (ev) {
                var t = ev.target;
                var a = t && t.getAttribute ? t.getAttribute('data-adult') : null;
                if (a == null) return;
                selectOnly(el('adult-control'), t);
                sel.adult = a;
                clearResults();
                sendBrowse();
              };

              function doSearch() {
                var v = el('search-input').value;
                v = v == null ? '' : v.trim();
                clearResults();
                if (v === '') { sendBrowse(); return; }
                send({
                  type: 'search', q: v, category: sel.category,
                  tv_kind: sel.tv_kind, adult: sel.adult, page: 1
                }, false);
              }

              el('search-btn').onclick = doSearch;
              el('search-input').onkeydown = function (ev) {
                if (ev.key === 'Enter') { ev.preventDefault(); doSearch(); }
              };

              el('randomize-btn').onclick = function () {
                clearResults();
                send({
                  type: 'random', category: sel.category,
                  tv_kind: sel.tv_kind, adult: sel.adult
                }, false);
              };

              el('sort').onchange = function () {
                sel.sort = el('sort').value;
                clearResults();
                sendBrowse();
              };

              setupObserver();
              connect();
            })();
        """.trimIndent()

        /**
         * The /stats page (webui stage 2): a live view of the crawler's
         * /stats JSON, pushed over the stats websocket (no polling). All
         * markup, CSS and JS are inline — no external assets. Every
         * object/array renders as a collapsible section keyed by a stable
         * path ("supervisor", "trees.2", "trees.2.queues"); the user's
         * expand/collapse choices live in the page's `openOverrides` map
         * and survive every re-render. The summary strip also carries a
         * Backfill cell (stage 8: `classifier.backfill`) that stays hidden
         * unless the crawler reports a usable backfill object. The
         * `running:false` fallback payload
         * swaps the sections for a status banner and recovers on its own
         * when real stats resume.
         */
        internal fun buildStatsPage(): String = """
            <!DOCTYPE html><html><head><title>Torrent App — Crawler Stats</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$WEBUI_CSS
              body{max-width:640px;margin:0 auto;padding:0 12px}
              a.cfg{display:inline-block;margin:.5em .5em .5em 0;padding:.4em .8em;background:var(--card);border-radius:8px;text-decoration:none;border:1px solid #dadce0;color:var(--blue);font-weight:600}
              .strip{display:flex;flex-wrap:wrap;gap:.5em;margin:1em 0}
              .strip div{background:var(--card);border:1px solid #e3e7ee;border-radius:12px;padding:.5em .8em;box-shadow:0 1px 3px rgba(0,0,0,.06);font-size:.95em}
              .strip b{display:block;font-size:.7em;text-transform:uppercase;letter-spacing:.05em;color:var(--muted);font-weight:600}
              #live{display:inline-block;width:auto;height:auto;padding:2px 8px;border-radius:10px;font-size:.85em;font-weight:600;white-space:nowrap}
              #live.ok{background:#e6f4ea;color:#137333}
              #live.bad{background:#fce8e6;color:#c5221f}
              .banner.stop{background:#fce8e6;color:#c5221f}
              .banner.start{background:#fef7e0;color:#7a5900}
              .banner:empty{display:none}
              section{margin:0 0 .75em;background:var(--card);border:1px solid #e3e7ee;border-radius:12px;box-shadow:0 1px 3px rgba(0,0,0,.06)}
              .head{cursor:pointer;user-select:none;padding:.6em .9em;font-weight:600}
              .head .tri{display:inline-block;width:1.2em;color:var(--blue)}
              .body{padding:.25em .9em .6em}
              .row{display:flex;gap:.5em;padding:.15em 0;font-size:.95em}
              .row .k{color:var(--muted);min-width:11em;flex-shrink:0}
              .row .v{overflow-wrap:anywhere;color:var(--text)}
            </style></head><body>
            <h1>Crawler Stats</h1>
            <p>
              <a class="cfg" href="/">Dashboard</a>
              <a class="cfg" href="/settings/crawler">Crawler settings</a>
            </p>
            <p class="meta">Feed: <span id="live" class="live bad">connecting…</span>
               &nbsp; last update: <span id="updated">—</span></p>
            <div class="strip">
              <div><b>Version</b><span id="s-version">—</span></div>
              <div><b>Uptime</b><span id="s-uptime">—</span></div>
              <div><b>Indexed torrents</b><span id="s-torrents">—</span></div>
              <div><b>Indexed files</b><span id="s-files">—</span></div>
              <div><b>New torrents (1h)</b><span id="s-last-hour">—</span></div>
              <div><b>Timestamp</b><span id="s-timestamp">—</span></div>
              <div id="bf-cell" style="display:none"><b>Backfill</b><span id="s-backfill">—</span></div>
            </div>
            <div id="banner" class="banner stop">Waiting for the first stats push…</div>
            <div id="tree"></div>
            <script>
            (function () {
              var ws = null;
              var backoff = 1000;
              var MAX_BACKOFF = 30000;
              var lastData = null;
              var lastFrameAt = null;
              // Explicit expand/collapse choices keyed by a stable section
              // path ("supervisor", "trees.2", "trees.2.queues"); they
              // survive every re-render because render() re-reads them.
              var openOverrides = {};

              function el(id) { return document.getElementById(id); }

              function setLive(ok, label) {
                var s = el('live');
                s.textContent = label;
                s.className = 'live ' + (ok ? 'ok' : 'bad');
              }

              function connect() {
                var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                // Same-origin upgrade: the control_token cookie is sent
                // automatically; a ?token= in the page URL rides along too.
                ws = new WebSocket(proto + '//' + location.host + '/stats/ws' + location.search);
                ws.onopen = function () {
                  backoff = 1000;
                  setLive(true, 'live');
                };
                ws.onmessage = function (ev) {
                  var data = null;
                  try { data = JSON.parse(ev.data); } catch (e) { return; }
                  lastData = data;
                  lastFrameAt = new Date();
                  render();
                };
                ws.onclose = function () {
                  setLive(false, 'reconnecting in ' + Math.round(backoff / 1000) + 's…');
                  var wait = backoff;
                  backoff = Math.min(backoff * 2, MAX_BACKOFF);
                  setTimeout(connect, wait);
                };
                ws.onerror = function () { try { ws.close(); } catch (e) {} };
              }

              function esc(s) {
                var d = document.createElement('div');
                d.textContent = s;
                return d.innerHTML;
              }

              function fmtScalar(v) {
                if (v === null || v === undefined) return '—';
                if (typeof v === 'number') return String(Math.round(v * 10000) / 10000);
                if (typeof v === 'boolean') return v ? 'yes' : 'no';
                return String(v);
              }

              function fmtInt(v) {
                if (typeof v !== 'number' || !isFinite(v)) return '—';
                return v.toLocaleString();
              }

              // Stage 8: the crawler's resumable backfill/re-classify job
              // reports itself under classifier.backfill. The cell stays
              // hidden unless that object is present and usable, so an older
              // crawler binary (no field) or an unavailable classifier
              // (available:false) never shows a misleading line.
              function renderBackfill(data) {
                var cell = el('bf-cell');
                var out = el('s-backfill');
                var bf = data && data.classifier ? data.classifier.backfill : null;
                if (!bf || typeof bf !== 'object' || bf.available === false ||
                    typeof bf.state !== 'string' || bf.state === '') {
                  out.textContent = '—';
                  cell.style.display = 'none';
                  return;
                }
                var text;
                if (bf.state === 'running') {
                  text = 'running \u2014 ' + fmtInt(bf.done) + '/' + fmtInt(bf.total) +
                      ' rows' + (typeof bf.rate_rows_s === 'number' && isFinite(bf.rate_rows_s)
                          ? ' (' + Math.round(bf.rate_rows_s).toLocaleString() + ' rows/s)' : '');
                } else if (bf.state === 'complete') {
                  text = 'complete \u2014 ' + fmtInt(bf.done) + '/' + fmtInt(bf.total) + ' rows';
                } else {
                  text = bf.state;
                }
                out.textContent = text;
                cell.style.display = '';
              }

              function defaultOpen(depth) {
                // Top-level sections and per-item objects start expanded;
                // deeper nesting (e.g. a tree's queues) starts collapsed.
                return depth <= 2;
              }

              function isOpen(path, depth) {
                var o = openOverrides[path];
                return o !== undefined ? o : defaultOpen(depth);
              }

              function rowHtml(key, value) {
                return '<div class="row"><span class="k">' + esc(String(key)) + '</span>' +
                    '<span class="v">' + esc(fmtScalar(value)) + '</span></div>';
              }

              function itemTitle(parentKey, item, index) {
                if (parentKey === 'trees') {
                  var t = 'tree ' + (item.tree_id != null ? item.tree_id : index);
                  return item.phase ? t + ' \u2014 ' + item.phase : t;
                }
                if (parentKey === 'partitions') {
                  return 'partition ' + (item.partition != null ? item.partition : index);
                }
                return 'item ' + index;
              }

              function sectionNode(title, value, path, depth) {
                var open = isOpen(path, depth);
                var html = '<section><div class="head" data-path="' + esc(path) + '">' +
                    '<span class="tri">' + (open ? '\u25be' : '\u25b8') + '</span>' + esc(title) + '</div>';
                if (open) {
                  html += '<div class="body">' + childrenHtml(
                      value, path, depth, Array.isArray(value) ? title : null) + '</div>';
                }
                return html + '</section>';
              }

              function childrenHtml(value, path, depth, parentKey) {
                var html = '';
                if (Array.isArray(value)) {
                  for (var i = 0; i < value.length; i++) {
                    var item = value[i];
                    var p = (path ? path + '.' : '') + i;
                    if (item !== null && typeof item === 'object') {
                      html += sectionNode(itemTitle(parentKey, item, i), item, p, depth + 1);
                    } else {
                      html += rowHtml(String(i), item);
                    }
                  }
                } else if (value !== null && typeof value === 'object') {
                  for (var k in value) {
                    if (!Object.prototype.hasOwnProperty.call(value, k)) continue;
                    var v = value[k];
                    var p2 = (path ? path + '.' : '') + k;
                    if (v !== null && typeof v === 'object') {
                      html += sectionNode(k, v, p2, depth + 1);
                    } else {
                      html += rowHtml(k, v);
                    }
                  }
                } else {
                  html += rowHtml(parentKey || 'value', value);
                }
                return html;
              }

              function render() {
                var data = lastData;
                el('s-version').textContent = fmtScalar(data ? data.version : null);
                el('s-uptime').textContent = fmtScalar(data ? data.uptime : null);
                el('s-torrents').textContent = fmtScalar(data ? data.torrents_indexed : null);
                el('s-files').textContent = fmtScalar(data ? data.files_indexed : null);
                el('s-last-hour').textContent = fmtScalar(data ? data.torrents_last_hour : null);
                el('s-timestamp').textContent = fmtScalar(data ? data.timestamp : null);
                el('updated').textContent = lastFrameAt ? lastFrameAt.toLocaleTimeString() : '—';
                renderBackfill(data);
                var banner = el('banner');
                var tree = el('tree');
                if (!data) {
                  banner.className = 'banner stop';
                  banner.textContent = 'Waiting for the first stats push…';
                  tree.innerHTML = '';
                  return;
                }
                if (data.running === false) {
                  var st = data.state || 'STOPPED';
                  if (st === 'STARTING') {
                    banner.className = 'banner start';
                    banner.textContent = 'Crawler is starting \u2014 stats appear once its HTTP API is up.';
                  } else {
                    banner.className = 'banner stop';
                    banner.textContent = 'Crawler not running (state: ' + st + ').';
                  }
                  tree.innerHTML = '';
                  return;
                }
                banner.className = 'banner';
                banner.textContent = '';
                tree.innerHTML = childrenHtml(data, '', 0, null);
              }

              el('tree').addEventListener('click', function (ev) {
                var head = ev.target.closest ? ev.target.closest('.head') : null;
                if (!head) return;
                var path = head.getAttribute('data-path');
                var depth = path ? path.split('.').length : 0;
                openOverrides[path] = !isOpen(path, depth);
                render();
              });

              connect();
              render();
            })();
            </script>
            </body></html>
        """.trimIndent()

        private fun jsonStr(s: String?): String =
            if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /**
         * The /api/crawler/backup response body (Stage 4): state, message, last
         * attempt/success epoch millis, uploaded (gzip) bytes and the config
         * flags. Secrets never appear here.
         */
        internal fun backupStatusJson(st: com.torrent.app.backup.BackupStatus): String = buildString {
            append("{\"state\":\"").append(st.state.name).append("\"")
            append(",\"message\":").append(jsonStr(st.message))
            append(",\"lastAttempt\":").append(st.lastAttempt?.toString() ?: "null")
            append(",\"lastSuccess\":").append(st.lastSuccess?.toString() ?: "null")
            append(",\"bytes\":").append(st.uploadedBytes?.toString() ?: "null")
            append(",\"uploadEnabled\":").append(st.uploadEnabled)
            append(",\"configured\":").append(st.configured).append("}")
        }

        /**
         * The /api/crawler/backup/db response body (plan 2, Stage 3): state,
         * message, last attempt/success epoch millis, uploaded (gzip) bytes,
         * the last successful upload's object key / SQL bytes / statement
         * count and the config flags. Secrets never appear here.
         */
        internal fun dbBackupStatusJson(st: com.torrent.app.backup.DbBackupStatus): String = buildString {
            append("{\"state\":\"").append(st.state.name).append("\"")
            append(",\"message\":").append(jsonStr(st.message))
            append(",\"lastAttempt\":").append(st.lastAttempt?.toString() ?: "null")
            append(",\"lastSuccess\":").append(st.lastSuccess?.toString() ?: "null")
            append(",\"bytes\":").append(st.uploadedBytes?.toString() ?: "null")
            append(",\"sqlBytes\":").append(st.sqlBytes?.toString() ?: "null")
            append(",\"statementCount\":").append(st.statementCount?.toString() ?: "null")
            append(",\"objectKey\":").append(jsonStr(st.objectKey))
            append(",\"uploadEnabled\":").append(st.uploadEnabled)
            append(",\"configured\":").append(st.configured).append("}")
        }

        /**
         * The /api/crawler/restore response body (plan 2, Stage 4): state,
         * running, message, last attempt/success epoch millis, the last
         * restore's object count + keys, the one-shot exit code and the
         * output tail. Secrets never appear here.
         */
        internal fun restoreStatusJson(st: com.torrent.app.backup.DbRestoreStatus): String = buildString {
            append("{\"state\":\"").append(st.state.name).append("\"")
            append(",\"running\":").append(st.running)
            append(",\"message\":").append(jsonStr(st.message))
            append(",\"lastAttempt\":").append(st.lastAttempt?.toString() ?: "null")
            append(",\"lastSuccess\":").append(st.lastSuccess?.toString() ?: "null")
            append(",\"objectCount\":").append(st.objectCount?.toString() ?: "null")
            append(",\"objectKeys\":[").append(
                st.objectKeys.joinToString(",") { jsonStr(it) }).append("]")
            append(",\"exitCode\":").append(st.exitCode?.toString() ?: "null")
            append(",\"lines\":[").append(
                st.lines.joinToString(",") { jsonStr(it) }).append("]")
            append(",\"startedAt\":").append(st.startedAt?.toString() ?: "null")
            append(",\"finishedAt\":").append(st.finishedAt?.toString() ?: "null")
            append(",\"configured\":").append(st.configured).append("}")
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
            classifierEnabled = form.containsKey("classifierEnabled"),
            adultMode = form["adultMode"]?.takeIf { it.isNotEmpty() }?.trim()?.lowercase() ?: current.adultMode,
            adultHideThreshold = form["adultHideThreshold"]?.toDoubleOrNull() ?: current.adultHideThreshold,
            adultDropThreshold = form["adultDropThreshold"]?.toDoubleOrNull() ?: current.adultDropThreshold,
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
            dbBackupEnabled = form.containsKey("dbBackupEnabled"),
            r2DbBucket = form["r2DbBucket"] ?: current.r2DbBucket,
            r2DbObjectPrefix = form["r2DbObjectPrefix"]?.takeIf { it.isNotEmpty() } ?: current.r2DbObjectPrefix,
            bootstrapEnabled = form.containsKey("bootstrapEnabled"),
            bootstrapUrl = form["bootstrapUrl"] ?: current.bootstrapUrl,
        )
    }
}
