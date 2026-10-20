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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import com.torrent.app.config.TransmissionApplyMode
import com.torrent.app.config.CrawlerKeys
import com.torrent.app.config.CrawlerSettings
import com.torrent.app.config.CrawlerTaxonomy
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
    // Read timeout for /search/ws queries while the crawler daemon is RUNNING
    // (search-dashboard): search/browse over a big torrents DB can take quite
    // a long time, so this is much longer than the 15 s default. A crawler that
    // is not RUNNING keeps the short default (fail fast). Injected for tests.
    private val searchReadTimeoutMs: Int = SEARCH_READ_TIMEOUT_MS,
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

    // ── shared push ticker (webui stage 1) ────────────────────────────────
    // One scope drives every periodic push loop. start() creates a fresh
    // scope (so a stop()/start() cycle never reuses a cancelled one) and
    // stop() cancels it, so no ticker outlives the server.
    @Volatile private var wsPushScope: CoroutineScope? = null
    // Slow values cached by the ticker and read by the render-cheap dashboard
    // frame: transmission RPC (two sequential calls, 3 s timeout each) and the
    // crawler's loopback /stats must never run on a frame build.
    @Volatile private var cachedRpcSummary: com.torrent.app.transmission.TransmissionSummary? = null
    @Volatile private var cachedCrawlerStatsJson: String? = null
    @Volatile private var cachedCrawlerStatsLine: String? = null
    // One frame flow per feed: replay keeps the newest frame for a client that
    // connects between ticks, DROP_OLDEST keeps a slow socket from stalling
    // the ticker.
    private val statsFrames = MutableSharedFlow<String>(
        replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val dashboardFrames = MutableSharedFlow<String>(
        replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                get("/health") { call.respondText("ok", ContentType.Text.Plain) }

                // Favicon: browsers request /favicon.ico on every page (the
                // unauthenticated /login included), so it sits outside the auth
                // block like /health. Without this route every page logs a 404
                // in the browser console; a tiny embedded PNG keeps the
                // dashboard's zero-external-assets rule.
                get("/favicon.ico") { call.respondBytes(FAVICON_PNG, ContentType.Image.PNG) }

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
                    // The category chips follow the app's crawler settings: a
                    // disabled category is neither rendered nor accepted by the
                    // /search/ws feed below.
                    get("search") {
                        val crawler = loadConfig().crawler
                        call.respondText(
                            buildSearchPage(
                                enabledCategories(crawler.saveCategories),
                                crawler.adultMode),
                            ContentType.Text.Html)
                    }

                    // Torrent-page stage 2: the /torrent?hash=<40-hex> detail
                    // shell. Static markup/CSS/JS only — the hash rides in the
                    // query string and is read client-side (the same pattern as
                    // the token on /search), so nothing here validates it; the
                    // JSON forwards below do.
                    get("torrent") {
                        call.respondText(buildTorrentPage(), ContentType.Text.Html)
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
                        val token = parseCookieToken(call.request.headers["Cookie"]) ?: call.request.queryParameters["token"] ?: ""
                        val unchanged = !TransmissionApplyMode.needsRestart(currentCfg, newCfg)
                        if (unchanged) {
                            // Config is identical: nothing to persist and nothing to restart.
                            call.respondRedirect("/settings/transmission?msg=${urlEnc("no changes")}&ok=true&token=$token")
                        } else {
                            val r = applyTransmission(newCfg, false)
                            call.respondRedirect("/settings/transmission?msg=${urlEnc(r.message + " (restart)")}&ok=${r.ok}&token=$token")
                        }
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
                        val multi = parseFormMulti(call.receiveText())
                        val form = collapseForm(multi)
                        val currentCfg = loadConfig()
                        val current = currentCfg.crawler
                        val token = parseCookieToken(call.request.headers["Cookie"]) ?: call.request.queryParameters["token"] ?: ""
                        when (val parsed = crawlerSettingsFromForm(multi, current)) {
                            // Rejected before anything is written or restarted (stage 4:
                            // an empty category selection would purge the whole database).
                            is CrawlerSettingsFormResult.Bad -> call.respondRedirect(
                                "/settings/crawler?msg=${urlEnc(parsed.message)}&ok=false&token=$token")
                            is CrawlerSettingsFormResult.Ok -> {
                                val s = parsed.settings
                                val backup = backupSettingsFromForm(form, currentCfg.backup)
                                val bloomChanged = current.bloomCapacity != s.bloomCapacity ||
                                    current.failureBloomCapacity != s.failureBloomCapacity ||
                                    current.bloomErrorRate != s.bloomErrorRate
                                val r = applyCrawler(s)
                                val rb = saveBackup(backup)
                                val msg = if (r.ok && rb.ok) r.message else "${r.message} / backup: ${rb.message}"
                                val rebuild = if (bloomChanged) "&rebuildBloom=true" else ""
                                call.respondRedirect(
                                    "/settings/crawler?msg=${urlEnc(msg)}&ok=${r.ok && rb.ok}&token=$token$rebuild")
                            }
                        }
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

                    // Torrent-page stage 2: JSON forwards for the detail page. The
                    // hash is the only field that can fail validation (exactly 40
                    // hex chars, lowercased); the files route clamps offset/limit
                    // and drops a filter shorter than 3 chars, and /refresh carries
                    // the 30 s read timeout because it re-queries the DHT.
                    get("api/crawler/torrent/info") {
                        call.respondText(
                            torrentForwardJson(buildTorrentInfoPath(call.request.queryParameters["hash"])),
                            ContentType.Application.Json,
                        )
                    }
                    get("api/crawler/torrent/files") {
                        call.respondText(
                            torrentForwardJson(
                                buildTorrentFilesPath(
                                    hash = call.request.queryParameters["hash"],
                                    offset = call.request.queryParameters["offset"]?.toIntOrNull(),
                                    limit = call.request.queryParameters["limit"]?.toIntOrNull(),
                                    q = call.request.queryParameters["q"],
                                ),
                            ),
                            ContentType.Application.Json,
                        )
                    }
                    get("api/crawler/refresh") {
                        call.respondText(
                            torrentForwardJson(buildTorrentRefreshPath(call.request.queryParameters["hash"])),
                            ContentType.Application.Json,
                        )
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
                    // websocket support, so the WS terminates here and the shared
                    // ticker polls the loopback /stats once per period. The
                    // interceptor above already rejected unauthenticated upgrades
                    // with 401; requireWsAuth() re-checks the token (same
                    // precedence, constant-time via TokenManager) and never pushes
                    // stats on a bad socket.
                    webSocket("stats/ws") {
                        if (!requireWsAuth()) return@webSocket
                        try {
                            statsFrames.collect { frame -> send(Frame.Text(frame)) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e // coroutine cancelled (client gone); exit cleanly
                        } catch (e: Exception) {
                            // the socket went away; stop pushing
                        }
                    }

                    // Live dashboard feed (webui stage 1): the same ticker
                    // mechanism, fed by cheap suppliers plus the cached slow
                    // snapshots, so a frame never blocks on transmission RPC or
                    // on the crawler's loopback API.
                    webSocket("dashboard/ws") {
                        if (!requireWsAuth()) return@webSocket
                        try {
                            dashboardFrames.collect { frame -> send(Frame.Text(frame)) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e // coroutine cancelled (client gone); exit cleanly
                        } catch (e: Exception) {
                            // the socket went away; stop pushing
                        }
                    }

                    // Search feed (search-dashboard stage 2): request/response over
                    // one socket. The client sends {"id":N,"type":"search|browse|random",
                    // ...filters}; we validate, build a loopback path from enumerated
                    // values only, fetch the crawler JSON and reply with the raw body
                    // spliced verbatim under key "payload". It never throws on a
                    // malformed frame or a half-open socket.
                    webSocket("search/ws") {
                        if (!requireWsAuth()) return@webSocket
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            // Snapshot the frame text here in the read loop: the
                            // Frame's buffer is recycled once the loop moves on,
                            // so readText() must not happen later, off-loop.
                            val text = frame.readText()
                            // A big-DB search can hold this fetch for the whole
                            // search read timeout (120 s), so the reply must not
                            // stall the read loop: fetch off-loop on
                            // Dispatchers.IO, one in-flight fetch per frame.
                            // Replies may race; the client drops stale ones by
                            // id (latest-reply-wins).
                            launch(Dispatchers.IO) {
                                val reply = try {
                                    handleSearchFrame(text)
                                } catch (e: Exception) {
                                    // Defensive: a reply is mandatory, never a crash.
                                    """{"id":0,"ok":false,"error":"bad_request"}"""
                                }
                                try {
                                    send(Frame.Text(reply))
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e // coroutine cancelled (client gone)
                                } catch (e: Exception) {
                                    // the socket went away; nothing more to do
                                }
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
        startPushTicker()
    }

    fun stop() {
        // Cancel the ticker first: a loop that is mid-fetch must not push into
        // a server that is going away, and nothing may outlive this call.
        wsPushScope?.cancel()
        wsPushScope = null
        server?.stop(1000, 2000)
        server = null
    }

    /**
     * Webui stage 1: the token check every websocket route shares. The auth
     * interceptor above already answered 401 for an unauthenticated upgrade;
     * this is the belt-and-braces re-check on the session itself (same
     * precedence, constant-time via [TokenManager]). An unauthenticated
     * session is closed with VIOLATED_POLICY and receives no frame.
     */
    private suspend fun DefaultWebSocketServerSession.requireWsAuth(): Boolean {
        val (token, _) = resolveAuthToken(
            call.request.headers["Cookie"],
            call.request.queryParameters["token"],
            call.request.headers["Authorization"],
            tokenManager::validate,
        )
        if (token == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return false
        }
        return true
    }

    // ── push ticker (webui stage 1) ──────────────────────────────────────────

    /**
     * Starts the three periodic push loops in a fresh scope. Each loop runs
     * through [tickerLoop], so a throwing frame build (a supplier failing, a
     * malformed crawler body) never kills the ticker.
     */
    private fun startPushTicker() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        wsPushScope = scope
        // Loop A — dashboard. Cheap suppliers plus the cached slow snapshots
        // only: transmission RPC and the crawler fetch stay off this path.
        tickerLoop(scope) { dashboardFrames.emit(dashboardPayload()) }
        // Loop B — crawler stats. One loopback /stats per period (blocking
        // HTTP, so off the ticker thread); the raw body and the derived
        // one-line summary are cached for the dashboard frame.
        tickerLoop(scope) { refreshCrawlerStats() }
        // Loop C — transmission RPC snapshot. Two sequential calls with a 3 s
        // timeout each, so it is cached off-thread and never on a render path.
        tickerLoop(scope) {
            cachedRpcSummary = withContext(Dispatchers.IO) {
                runCatching { rpcSummary() }.getOrNull()
            }
        }
    }

    /**
     * Runs [body] once per [wsPushPeriodMs] until the scope is cancelled.
     * A [kotlinx.coroutines.CancellationException] propagates (scope cancel /
     * shutdown); every other exception is swallowed so the loop keeps its
     * cadence instead of dying on one bad frame.
     */
    private fun tickerLoop(scope: CoroutineScope, body: suspend () -> Unit) {
        scope.launch {
            while (true) {
                try {
                    body()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad frame must not stop the feed.
                }
                delay(wsPushPeriodMs)
            }
        }
    }

    /**
     * One crawler-stats tick: fetch the crawler's loopback /stats while the
     * daemon is RUNNING, cache it, and emit the stats frame (the same body
     * /api/crawler/stats serves, including the clean not-running fallback).
     */
    private suspend fun refreshCrawlerStats() {
        val crawler = daemonStatuses().firstOrNull { it.label == DaemonService.CRAWLER_LABEL }
        val raw = if (crawler?.state == ProcessState.RUNNING) {
            withContext(Dispatchers.IO) { runCatching { fetchCrawlerStats() }.getOrNull() }
        } else {
            null
        }
        cachedCrawlerStatsJson = raw
        cachedCrawlerStatsLine = raw?.let { crawlerStatsLine(it) }
        statsFrames.emit(crawlerStatsJson(cachedCrawlerStatsJson, crawler?.state))
    }

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

    /**
     * The dashboard page (webui stage 2): one collapsible card per daemon in
     * [daemonStatuses] order, replacing the old six-column daemons table and the
     * standalone DHT-crawler / FTP sections (the crawler used to render twice).
     *
     * Every value the dashboard websocket client patches carries a stable hook —
     * `data-daemon` on the card, `data-field` on the value cell, `data-log-for`
     * on the log pane, `data-toggle` on the head — and is rendered here with a
     * server-side initial value. Every slow value on this page comes from the
     * ticker's cached snapshot ([cachedRpcSummary]), never from a blocking
     * loopback RPC, so a wedged transmission daemon cannot stall the render.
     *
     * `internal` so the unit tests can pin the markup without a live server.
     */
    internal fun buildDashboard(): String {
        val state = serviceState.value
        val daemons = daemonStatuses()
        val cfg = loadConfig()
        val usb = usbFreeSpace()
        val rpc = cachedRpcSummary
        val ftp = ftpStatus()
        val now = System.currentTimeMillis()

        val daemonCards = if (daemons.isEmpty()) {
            """<p class="meta"><em>No daemons configured.</em></p>"""
        } else {
            daemons.joinToString("\n") { daemonCard(it, now, rpc, cfg, ftp) }
        }

        val rollbackHidden = if (lastApplyRolledBack()) "" else " hidden"

        val usbSection = buildString {
            append("<p class=\"meta\">Granted: <strong>").append(safStatus().granted).append("</strong>")
            append(" &nbsp; Free: <strong>").append(usb?.let { formatBytes(it) } ?: "unknown").append("</strong></p>")
        }

        return """
            <!DOCTYPE html><html><head><title>Torrent App — Control</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$WEBUI_CSS
              h2{margin:1.5em 0 .4em}
              .tx td+td{overflow-wrap:normal}
              #live{width:auto;height:auto;padding:2px 8px;border-radius:10px;font-size:.85em;font-weight:600}
              #live.on{background:#e6f4ea;color:#137333}
              #live.wait{background:#fef7e0;color:#7a5900}
              a.cfg{display:inline-block;margin:.5em .5em .5em 0;padding:.4em .8em;background:var(--card);border-radius:8px;text-decoration:none;border:1px solid #dadce0;color:var(--blue);font-weight:600}
              .warn{background:#f8d7da;color:#721c24;padding:.6em;border-radius:8px;margin:1em 0}
              .daemon-card{margin:0 0 .75em}
              .daemon-head{display:flex;align-items:center;gap:.5em;cursor:pointer;user-select:none;font-weight:600}
              .daemon-head .tri{display:inline-block;width:1.2em;color:var(--blue)}
              .daemon-head .dname{flex:1 1 auto}
              .daemon-body{padding:.25em 0 0}
              .daemon-body[hidden]{display:none}
              .row{display:flex;gap:.5em;padding:.15em 0;font-size:.95em}
              .row .k{color:var(--muted);min-width:11em;flex-shrink:0}
              .row .v{overflow-wrap:anywhere;color:var(--text)}
              .daemon-actions{display:inline;margin:0}
              .daemon-actions .btn{margin:0 .35em .35em 0}
              .daemon-log{overflow:auto}
            </style></head><body>
            <div class="wrap wide">
            <header><h1>Torrent App Control</h1><nav><a href="/">Dashboard</a><a href="/stats">Crawler stats</a><a href="/search">Search</a></nav></header>
            <p class="meta">Service: <span id="service-state" class="state $state">$state</span> <span id="live" class="live wait">connecting…</span></p>
            <div id="rollback-banner" class="warn"$rollbackHidden>Transmission config was rolled back after a crash-loop. Review settings.</div>
            <p>
              <a class="cfg" href="/settings/transmission">Transmission settings</a>
              <a class="cfg" href="/settings/ftp">FTP settings</a>
              <a class="cfg" href="/settings/storage">Storage</a>
              <a class="cfg" href="/settings/crawler">Crawler</a>
              <a class="cfg" href="/stats">Crawler Stats</a>
              <a class="cfg" href="/search">Search</a>
            </p>
            <h2>Daemons</h2>
            $daemonCards
            <h2>USB Storage</h2>
            $usbSection
            </div>
            <script>
            $WEBSOCKET_CLIENT_JS
            $DASHBOARD_JS
            </script>
            </body></html>
        """.trimIndent()
    }

    /**
     * One daemon card. The shell is never rebuilt by a push, so a card's
     * open/closed state survives the 10 s frames by construction; the folded
     * extras are label/value rows (`data-field`) so the client patches a single
     * cell instead of a section.
     */
    private fun daemonCard(
        d: ProcessStatus,
        now: Long,
        rpc: com.torrent.app.transmission.TransmissionSummary?,
        cfg: com.torrent.app.config.AppConfig,
        ftp: com.torrent.app.ftp.FtpStatus,
    ): String {
        // Label goes into an attribute (data-daemon / form action) and the
        // title text, so it is escaped once and used verbatim.
        val label = esc(d.label)
        val uptime = d.startTimeMs?.let { formatUptime(now - it) } ?: "—"
        val logs = d.lastLogLines.takeLast(30).joinToString("<br>") { esc(it) }
        val pausedBadge = if (d.label == DaemonService.CRAWLER_LABEL && crawlerPausedNoWifi())
            "<span class=\"state STOPPED\" data-field=\"pausedNoWifi\">paused: no wifi</span>" else ""
        val extras = when (d.label) {
            DaemonService.TRANSMISSION_LABEL -> transmissionExtras(rpc, cfg)
            DaemonService.CRAWLER_LABEL -> crawlerExtras()
            DaemonService.FTP_LABEL -> ftpExtras(ftp)
            else -> emptyList()
        }
        return """
            <section class="card daemon-card" data-daemon="$label">
              <div class="daemon-head" data-toggle><span class="tri">▾</span><span class="dname">$label</span><span class="state ${d.state}" data-field="state">${d.state}</span>$pausedBadge</div>
              <div class="daemon-body">
                <div class="row"><span class="k">PID</span><span class="v" data-field="pid">${d.pid ?: "—"}</span></div>
                <div class="row"><span class="k">Uptime</span><span class="v" data-field="uptime">$uptime</span></div>
                <div class="row"><span class="k">Restarts</span><span class="v" data-field="restarts">${d.restartCount}</span></div>
                ${extras.joinToString("\n                ")}
                <form class="daemon-actions" method="post" action="/daemon/$label/start"><button class="btn">Start</button></form>
                <form class="daemon-actions" method="post" action="/daemon/$label/stop"><button class="btn">Stop</button></form>
                <form class="daemon-actions" method="post" action="/daemon/$label/restart"><button class="btn">Restart</button></form>
                <pre class="loglines daemon-log" data-log-for="$label">$logs</pre>
              </div>
            </section>
        """.trimIndent()
    }

    /** Transmission extras folded into its card: RPC link, unreachable banner,
     * download dir, limits, and the still server-rendered torrent table. */
    private fun transmissionExtras(
        rpc: com.torrent.app.transmission.TransmissionSummary?,
        cfg: com.torrent.app.config.AppConfig,
    ): List<String> {
        val txLink = esc("http://${wifiIp() ?: "127.0.0.1"}:9091/")
        val limits = if (rpc == null) "—"
            else "↓${rpc.speedLimitDown} ↑${rpc.speedLimitUp} KB/s (0 = unlimited)"
        return listOf(
            """<div class="row"><span class="k">RPC</span><span class="v"><a href="$txLink">$txLink</a> &nbsp; user: ${esc(cfg.transmission.username)}</span></div>""",
            """<div class="banner red" data-field="rpcUnreachable"${if (rpc == null) "" else " hidden"}>Transmission RPC not reachable.</div>""",
            """<div class="row"><span class="k">Download dir</span><span class="v"><code data-field="downloadDir">${esc(rpc?.downloadDir ?: "—")}</code></span></div>""",
            """<div class="row"><span class="k">Limits</span><span class="v" data-field="limits">$limits</span></div>""",
            downloadsTable(rpc),
        )
    }

    /** Crawler extras folded into its card (the old standalone section's data). */
    private fun crawlerExtras(): List<String> {
        val dbSize = crawlerDbSize()?.let { formatBytes(it) } ?: "—"
        val free = internalFreeSpace()
        val spaceWarn = if (free < CRAWLER_LOW_FREE_SPACE_BYTES)
            " <span class=\"warn\">low free space</span>" else ""
        return listOf(
            """<div class="row"><span class="k">DB size</span><span class="v" data-field="dbSize">$dbSize</span></div>""",
            """<div class="row"><span class="k">Internal free</span><span class="v"><span data-field="internalFree">${formatBytes(free)}</span>$spaceWarn</span></div>""",
            """<div class="row"><span class="k">Search dashboard</span><span class="v"><a href="/search">/search</a></span></div>""",
            """<div class="row"><span class="k">Stats</span><span class="v" data-field="statsLine">${esc(cachedCrawlerStatsLine ?: "—")}</span></div>""",
        )
    }

    /** FTP extras folded into its card: wifi-up badge, connection URL, port. */
    private fun ftpExtras(ftp: com.torrent.app.ftp.FtpStatus): List<String> {
        val url = if (ftp.running && ftp.ip != null && ftp.password != null)
            "ftp://${ftp.username}:${ftp.password}@${ftp.ip}:${ftp.port}/" else "—"
        return listOf(
            """<div class="row"><span class="k">Wifi</span><span class="v"><span class="badge" data-field="wifiUp">${if (ftp.wifiUp) "up" else "down"}</span></span></div>""",
            """<div class="row"><span class="k">URL</span><span class="v"><code data-field="ftpUrl">${esc(url)}</code></span></div>""",
            """<div class="row"><span class="k">Port</span><span class="v" data-field="ftpPort">${ftp.port}</span></div>""",
        )
    }

    /**
     * The transmission torrent table, still server-rendered inside the
     * transmission card (it refreshes on reload, exactly as before).
     */
    private fun downloadsTable(
        rpc: com.torrent.app.transmission.TransmissionSummary?,
    ): String {
        if (rpc == null) return ""
        return buildString {
            append("<table class=\"tx\"><tr><th>Torrent</th><th>Done</th><th>Down</th><th>Up</th><th>Size</th></tr>")
            if (rpc.torrents.isEmpty()) append("<tr><td colspan=\"5\"><em>No torrents.</em></td></tr>")
            for (t in rpc.torrents) {
                append("<tr><td>").append(esc(t.name)).append("</td>")
                append("<td>").append("%.0f%%".format(t.percentDone * 100)).append("</td>")
                append("<td>").append(formatRate(t.rateDownload)).append("</td>")
                append("<td>").append(formatRate(t.rateUpload)).append("</td>")
                append("<td>").append(formatBytes(t.sizeWhenDone)).append("</td></tr>")
            }
            append("</table>")
        }
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

    /**
     * The /dashboard/ws frame body (webui stage 1): everything the dashboard
     * patches, from cheap suppliers plus the ticker's cached slow snapshots.
     * Every string goes through [jsonStr] — labels, states and log lines are
     * daemon-controlled text, so raw concatenation would let one break the
     * frame. `transmission` is always a slim object (reachability plus the
     * scalars the client patches), and `statsLine` is null until the crawler's
     * /stats answers; a client leaves the server-rendered value alone on null.
     */
    private fun dashboardPayload(): String {
        val state = serviceState.value
        val usb = usbFreeSpace()
        val f = ftpStatus()
        val daemons = daemonStatuses()
        val rpc = cachedRpcSummary
        val ftpUrl = if (f.running && f.ip != null && f.password != null)
            "ftp://${f.username}:${f.password}@${f.ip}:${f.port}/" else "—"
        return buildString {
            append("{\"service\":{\"state\":").append(jsonStr(state.toString()))
            append(",\"rolledBack\":").append(lastApplyRolledBack()).append("}")
            append(",\"usb\":{\"granted\":").append(safStatus().granted)
            append(",\"freeBytes\":").append(usb ?: "null").append("}")
            append(",\"wifiUp\":").append(f.wifiUp)
            append(",\"ftp\":{\"running\":").append(f.running)
            append(",\"state\":").append(jsonStr(f.state))
            append(",\"pid\":").append(f.pid ?: "null")
            append(",\"restarts\":").append(f.restartCount)
            append(",\"port\":").append(f.port)
            append(",\"url\":").append(jsonStr(ftpUrl))
            append(",\"wifiOnly\":").append(loadConfig().ftp.wifiOnly).append("}")
            append(",\"transmission\":").append(transmissionScalarsJson(rpc))
            append(",\"daemons\":[")
            append(daemons.joinToString(",") { d ->
                buildString {
                    append("{\"label\":").append(jsonStr(d.label))
                    append(",\"state\":").append(jsonStr(d.state.name))
                    append(",\"pid\":").append(d.pid ?: "null")
                    append(",\"uptime\":").append(jsonStr(
                        d.startTimeMs?.let { formatUptime(System.currentTimeMillis() - it) } ?: "—"))
                    append(",\"restarts\":").append(d.restartCount)
                    append(",\"logs\":[")
                    append(d.lastLogLines.takeLast(30).joinToString(",") { jsonStr(it) })
                    append("]")
                    if (d.label == DaemonService.CRAWLER_LABEL) {
                        val free = internalFreeSpace()
                        val dbSize = crawlerDbSize()
                        append(",\"dbSize\":").append(jsonStr(dbSize?.let { formatBytes(it) } ?: "—"))
                        append(",\"internalFree\":").append(jsonStr(formatBytes(free)))
                        append(",\"internalFreeBytes\":").append(free)
                        append(",\"pausedNoWifi\":").append(crawlerPausedNoWifi())
                        append(",\"statsLine\":").append(jsonStr(cachedCrawlerStatsLine))
                    }
                    append("}")
                }
            })
            append("]}")
        }
    }

    /**
     * The one-line crawler summary the dashboard card shows, derived from the
     * raw loopback /stats body: the same figures the legacy 5 s dashboard poll
     * rendered (torrents indexed, active trees, summed metadata rate). Null
     * when the body does not parse, so the card keeps its previous line.
     */
    private fun crawlerStatsLine(raw: String): String? = try {
        val o = JSONObject(raw)
        val trees = o.optJSONObject("supervisor")?.optInt("active_trees", 0) ?: 0
        var rate = 0.0
        o.optJSONArray("trees")?.let { arr ->
            for (i in 0 until arr.length()) {
                rate += arr.optJSONObject(i)?.optDouble("metadata_rate", 0.0) ?: 0.0
            }
        }
        "%d torrents, %d active trees, %.1f metadata/s".format(
            o.optLong("torrents_indexed", 0L), trees, rate)
    } catch (e: Exception) {
        null
    }

    private fun buildDownloadsJson(): String {
        val rpc = rpcSummary() ?: return "{\"reachable\":false}"
        return transmissionSummaryJson(rpc)
    }

    /**
     * One [com.torrent.app.transmission.TransmissionSummary] as JSON: the field
     * shape /downloads has always served, shared with the /dashboard/ws
     * `transmission` object so the two serializers cannot drift.
     */
    private fun transmissionSummaryJson(rpc: com.torrent.app.transmission.TransmissionSummary): String =
        buildString {
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

    /**
     * The slim `transmission` object every /dashboard/ws frame carries: only the
     * scalars the client patches (`reachable`, download dir, speed limits). The
     * torrent array stays server-rendered, so a push never ships it (and a
     * wedged RPC cannot make a frame expensive); `reachable:false` is what lets
     * the client re-show its `rpcUnreachable` banner after a later outage.
     */
    private fun transmissionScalarsJson(
        rpc: com.torrent.app.transmission.TransmissionSummary?,
    ): String = buildString {
        append("{\"reachable\":").append(rpc != null)
        append(",\"downloadDir\":").append(jsonStr(rpc?.downloadDir))
        append(",\"speedLimitUp\":").append(rpc?.speedLimitUp?.toString() ?: "null")
        append(",\"speedLimitDown\":").append(rpc?.speedLimitDown?.toString() ?: "null")
        append("}")
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
              <fieldset class="card"><legend>Speed limits (KB/s, 0 = unlimited)</legend>
                <label>Down<input name="speedLimitDown" value="${s.speedLimitDown}"></label>
                <label>Up<input name="speedLimitUp" value="${s.speedLimitUp}"></label>
                <label>Alt down<input name="altSpeedDown" value="${s.altSpeedDown}"></label>
                <label>Alt up<input name="altSpeedUp" value="${s.altSpeedUp}"></label>
              </fieldset>
              <label>Data directory (under USB root; blank = root)<input name="dataSubpath" value="${esc(cfg.dataSubpath)}"></label>
              <button type="submit" class="btn">Apply</button>
            </form>
            <p class="meta">Any change here, including speed limits, restarts transmission so settings.json is rewritten and the change persists.</p>
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
              <fieldset class="card">
                <label>Username<input name="username" value="${esc(s.username)}"></label>
                <label>Password (leave blank to keep)<input type="password" name="password" placeholder="unchanged"></label>
                <label>Port<input name="port" value="${s.port}"></label>
                <label class="row"><input type="checkbox" name="wifiOnly" $checked> WiFi only</label>
                <label>Max download (KiB/s, 0 = unlimited)<input name="maxDownloadKiBps" value="${s.maxDownloadKiBps}"></label>
                <p class="meta">The FTP root follows the shared data directory (Transmission settings).</p>
                <button type="submit" class="btn">Apply</button>
              </fieldset>
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
            <div class="card">
              <p class="meta">Granted: <strong>${s.granted}</strong></p>
              <p class="meta">Tree: <code>${esc(s.treeUri ?: "—")}</code></p>
              <p class="meta">Free: <strong>${free?.let { formatBytes(it) } ?: "unknown"}</strong></p>
              <p class="meta">WiFi: <strong>${if (f.wifiUp) "up" else "down"}</strong></p>
            </div>
            <div class="warn">To re-grant or change the USB drive, open the Torrent App on the
            Android device and tap <strong>Grant USB</strong>. The data directory is set in
            <a href="/settings/transmission">Transmission settings</a>.</div>
            <p><a href="/">Back to dashboard</a></p>
            </body></html>
        """.trimIndent()
    }

    /**
     * The effective taxonomy selection for the UI: an empty stored list means
     * "save everything" (the [CrawlerSettings] convention), so it renders as
     * every name selected.
     */
    private fun savedSelection(selected: List<String>, all: List<String>): Set<String> =
        if (selected.isEmpty()) all.toSet() else selected.map { it.trim().lowercase() }.toSet()

    /**
     * One taxonomy multi-select, rendered as plain checkbox labels so the whole
     * settings form still posts as one plain `<form>` (stage 4). Every box takes
     * the same `name`, so the browser posts one value per checked box.
     */
    private fun taxonomyToggles(
        name: String,
        all: List<String>,
        labels: Map<String, String>,
        selected: List<String>,
    ): String {
        val saved = savedSelection(selected, all)
        return all.joinToString("") { n ->
            val on = if (n in saved) " checked" else ""
            """<label><input type="checkbox" name="$name" value="$n"$on> ${labels[n] ?: n}</label>"""
        }
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
            <button type="submit" class="btn">Run --rebuild-bloom-filter</button></form></div>""" else ""
        val maintenanceSection = buildMaintenanceSection()
        val backupSection = buildBackupSection()
        val dbBackupSection = buildDbBackupSection()
        // Stage 4: the category selection. An empty stored list means "save all"
        // (the CrawlerSettings convention), so the toggles all render checked.
        val savedCategories = savedSelection(s.saveCategories, CrawlerTaxonomy.CATEGORIES)
        val categoryToggles = taxonomyToggles(
            "saveCategories", CrawlerTaxonomy.CATEGORIES, CATEGORY_LABELS, s.saveCategories)
        val tvKindToggles = taxonomyToggles(
            "saveTvKinds", CrawlerTaxonomy.TV_KINDS, TV_KIND_LABELS, s.saveTvKinds)
        // TV kinds only matter for tv/anime rows; the row is revealed client-side
        // too, so a selection change takes effect without a save.
        val tvKindRowHidden =
            if ("tv" in savedCategories || "anime" in savedCategories) "" else " hidden"
        return """
            <!DOCTYPE html><html><head><title>DHT crawler settings</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$WEBUI_CSS
            .warn-num{color:#c5221f;font-weight:700}
            #apply-status{background:#e6f4ea;color:#137333;padding:.6em .8em;border-radius:8px;margin:8px 0;font-size:.9rem}
            #apply-status.busy{background:#fef7e0;color:#7a5900}
            #maint-status{font-size:.9rem;padding:.4em .8em;border-radius:999px;background:var(--chip);color:var(--blue);display:inline-block}
            #maint-status.busy{background:#fef7e0;color:#7a5900}
            #maint-btns form{display:inline-block;margin:0 .5em .5em 0}
            #maint-btns button{margin:0;padding:.4em .8em}
            .pick{display:flex;flex-wrap:wrap;margin:4px 0}
            .pick label{display:inline-flex;align-items:center;width:auto;margin:.2em 1em .2em 0}
            #save-tv-kind-row h3{margin:10px 0 2px;font-size:.85rem;color:var(--muted);text-transform:uppercase;letter-spacing:.05em}
            #save-tv-kind-row[hidden]{display:none}
            </style></head><body>
            <h1>DHT crawler</h1>$banner
            <form method="post" action="/settings/crawler">
              <fieldset class="card"><legend>General</legend>
                <label class="row"><input type="checkbox" name="enabled" ${checked(s.enabled)}> Enabled (start with the service)</label>
                <label class="row"><input type="checkbox" name="wifiOnly" ${checked(s.wifiOnly)}> WiFi only (pause when wifi is down)</label>
                <label>Log level<select name="logLevel">$levelOptions</select></label>
                <label>DHT port<input name="dhtPort" value="${s.dhtPort}"></label>
                <label>Nice level (-20..19)<input name="niceLevel" value="${s.niceLevel}"></label>
              </fieldset>
              <fieldset class="card"><legend>Resources</legend>
                <label>Tree count (1..64)<input id="numTrees" name="numTrees" value="${s.numTrees}"></label>
                <label>Find-node workers<input id="treeFindNodeWorkers" name="treeFindNodeWorkers" value="${s.treeFindNodeWorkers}"></label>
                <label>BEP51 workers<input id="treeBep51Workers" name="treeBep51Workers" value="${s.treeBep51Workers}"></label>
                <label>Get-peers workers<input id="treeGetPeersWorkers" name="treeGetPeersWorkers" value="${s.treeGetPeersWorkers}"></label>
                <label>Metadata workers<input id="treeMetadataWorkers" name="treeMetadataWorkers" value="${s.treeMetadataWorkers}"></label>
                <label>Infohash queue capacity<input name="treeInfohashQueueCapacity" value="${s.treeInfohashQueueCapacity}"></label>
                <label>Peers queue capacity<input name="treePeersQueueCapacity" value="${s.treePeersQueueCapacity}"></label>
                <p class="meta">Estimated worker threads: <span id="est-threads"></span><span id="est-warn" style="display:none;color:#c5221f"> — above ${CrawlerKeys.THREAD_WARN_THRESHOLD}; the default config uses ~1216</span></p>
              </fieldset>
              <fieldset class="card"><legend>Filters</legend>
                <label>Non-Latin language threshold (0..100)<input name="languageFilterNonLatinThreshold" value="${s.languageFilterNonLatinThreshold}"></label>
              </fieldset>
              <fieldset class="card"><legend>Categories to save</legend>
                <p class="meta"><strong>Checked categories are saved</strong> to the database;
                <strong>unchecked categories are dropped</strong> when the crawler finds them. The
                <em>Category filter update</em> button in the maintenance section below then deletes the
                existing database rows of every unchecked category, plus every adult-tagged
                torrent (as tagged by the classifier). Tick at least one category.</p>
                <div class="pick">$categoryToggles</div>
                <div id="save-tv-kind-row"$tvKindRowHidden>
                  <h3>TV kinds — applies to TV and anime torrents only</h3>
                  <p class="meta">Checked kinds are saved; unchecked kinds are dropped (only within TV and
                  anime rows). Leaving every kind unchecked saves all of them.</p>
                  <div class="pick">$tvKindToggles</div>
                </div>
              </fieldset>
              <fieldset class="card"><legend>Adult content handling</legend>
                <label class="row"><input type="checkbox" name="classifierEnabled" ${checked(s.classifierEnabled)}> Ingest-time classifier (on by default)</label>
                <label>Adult mode<select name="adultMode">$adultModeOptions</select></label>
                <label>Adult hide threshold (0..1)<input type="number" step="any" name="adultHideThreshold" value="${s.adultHideThreshold}"></label>
                <p class="meta">The hard-block list (<code>hard_block.txt</code>) always drops matching names. <em>tag</em> stores
                adult rows (hidden at or above the hide threshold); <em>drop</em> discards every adult-tagged row, i.e.
                everything at or above the hide threshold.</p>
              </fieldset>
              <fieldset class="card"><legend>Bloom filters (changing these invalidates the persisted filters)</legend>
                <label>Bloom capacity<input name="bloomCapacity" value="${s.bloomCapacity}"></label>
                <label>Failure bloom capacity<input name="failureBloomCapacity" value="${s.failureBloomCapacity}"></label>
                <label>Bloom error rate (0..1)<input name="bloomErrorRate" value="${s.bloomErrorRate}"></label>
              </fieldset>
              <fieldset class="card"><legend>Advanced overrides (key=value, one per line)</legend>
                <textarea name="advancedOverrides" rows="6" placeholder="# key=value">${esc(s.advancedOverrides)}</textarea>
                <p class="meta">Path and backup keys are not allowed; form-managed keys must use the fields above.</p>
              </fieldset>
              <fieldset class="card"><legend>Access</legend>
                <label>Proxy port (LAN, authenticated)<input name="proxyPort" value="${s.proxyPort}"></label>
                <label>Crawler HTTP port (loopback)<input name="crawlerHttpPort" value="${s.crawlerHttpPort}"></label>
                <label class="row"><input type="checkbox" name="lanDirect" ${checked(s.lanDirect)}> LAN direct (bind 0.0.0.0, no proxy, no auth)</label>
              </fieldset>
              <fieldset class="card"><legend>Backup &amp; Bootstrap (app-side only; not written to the crawler config)</legend>
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
              <button type="submit" class="btn">Apply</button>
            </form>
            $rebuildBox
            <div id="apply-status">Apply status: IDLE</div>
            <pre id="apply-log" class="apply-log loglines" style="display:none"></pre>
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
              // TV kinds only apply to tv/anime rows, so the row is revealed whenever
              // one of those categories is ticked. Its boxes stay checked while
              // hidden, so switching tv/anime off and back on keeps the selection.
              var catBoxes = document.querySelectorAll('input[name="saveCategories"]');
              function syncTvKindRow() {
                var on = false;
                for (var ci = 0; ci < catBoxes.length; ci++) {
                  if (catBoxes[ci].checked && (catBoxes[ci].value === 'tv' || catBoxes[ci].value === 'anime')) on = true;
                }
                var row = document.getElementById('save-tv-kind-row');
                if (on) row.removeAttribute('hidden'); else row.setAttribute('hidden', '');
              }
              for (var ci = 0; ci < catBoxes.length; ci++) catBoxes[ci].addEventListener('change', syncTvKindRow);
              syncTvKindRow();
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

    /**
     * The maintenance fieldset on the crawler panel (plan 10 step 4). Each action
     * renders one button; stage 4 replaced the raw route names with a readable
     * label + a description of what the action does, so the purge button cannot
     * be mistaken for a filtered export.
     */
    private fun buildMaintenanceSection(): String {
        val buttons = MaintenanceAction.entries.joinToString("") { a ->
            val warn = if (a.needsSpaceCheck) " (needs ~DB size of free internal space)" else ""
            """
              <form method="post" action="/crawler/maintenance/${a.route}"
                    onsubmit="return confirm('${maintenanceWhat(a)}? The crawler is stopped while it runs.$warn')">
              <button type="submit" class="btn secondary" title="${a.flag}">${maintenanceLabel(a)}</button></form>
            """.trimIndent()
        }
        return """
            <fieldset class="card"><legend>Maintenance (one at a time; crawler stopped while running)</legend>
              <p class="meta">Runs <code>libdhtcrawler.so &lt;flag&gt;</code> as a one-shot in the crawler
              work dir. compact / recover-database need ~DB size of free internal space. The supervised
              crawler is stopped first and restarted afterwards if enabled (and wifi is up).</p>
              <p class="meta"><strong>Category filter update</strong> deletes the database rows of every
              category you left unchecked in <em>Categories to save</em> above (and of the unchecked TV
              kinds inside TV/anime rows), plus every adult-tagged torrent, updates the crawler's stats
              and stops there — it does not compact the database (use <em>Compact database</em> for that).</p>
              <div id="maint-btns">$buttons</div>
              <div id="maint-status" class="meta">Maintenance: idle</div>
              <pre id="maint-log" class="apply-log loglines" style="display:none"></pre>
            </fieldset>
        """.trimIndent()
    }

    /** Human label for a maintenance button (the flag is kept as its tooltip). */
    private fun maintenanceLabel(a: MaintenanceAction): String = when (a) {
        MaintenanceAction.CHECK_DATABASE -> "Check database"
        MaintenanceAction.COMPACT -> "Compact database"
        MaintenanceAction.RECOVER_DATABASE -> "Recover database"
        MaintenanceAction.REBUILD_BLOOM_FILTER -> "Rebuild bloom filter"
        MaintenanceAction.CLEAR_FAILURE_FILTER -> "Clear failure filter"
        MaintenanceAction.CATEGORY_FILTER_UPDATE -> "Category filter update"
    }

    /** What a maintenance action does, phrased for its confirmation prompt. */
    private fun maintenanceWhat(a: MaintenanceAction): String = when (a) {
        MaintenanceAction.CHECK_DATABASE -> "Check the database for corruption"
        MaintenanceAction.COMPACT -> "Compact the database"
        MaintenanceAction.RECOVER_DATABASE -> "Recover a damaged database"
        MaintenanceAction.REBUILD_BLOOM_FILTER -> "Rebuild the bloom filters from the database"
        MaintenanceAction.CLEAR_FAILURE_FILTER -> "Clear the failure filter"
        MaintenanceAction.CATEGORY_FILTER_UPDATE ->
            "Delete all torrents in unchecked categories and adult-tagged torrents " +
                "from the database"
    }

    /** The BEP51 cache backup section on the crawler panel (Stage 4). */
    private fun buildBackupSection(): String {
        val st = crawlerBackupStatus()
        val enabledNote = if (st.uploadEnabled) "" else
            "<p class=\"warn\">R2 upload is disabled — tick <em>Enable R2 upload</em> above and apply first.</p>"
        return """
            <fieldset class="card"><legend>Cache backup (upload the crawler's BEP51 bootstrap cache to R2)</legend>
              <p class="meta">Uploads <code>bep51_cache.dat</code> (validated + gzip-compressed) to the R2
              object key above — at most once per day, on Wi-Fi, and only when no apply/maintenance
              job is running. <em>Upload now</em> bypasses the daily gate only.</p>
              $enabledNote
              <form method="post" action="/crawler/backup/upload"
                    onsubmit="return confirm('Upload the BEP51 cache to R2 now?')">
              <button type="submit" class="btn">Upload now</button></form>
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
            <fieldset class="card"><legend>DB backup (upload the crawler's incremental SQLite dump to the R2 DB bucket)</legend>
              <p class="meta">Dumps new torrents (<code>torrents.db</code>) as gzip-compressed SQL to
              <code>&lt;db-prefix&gt;/incremental_&lt;date&gt;_&lt;start&gt;-&lt;end&gt;.sql.gz</code> — at most once per day, on
              Wi-Fi, and only when no apply/maintenance job is running. The upload bookmark advances only
              after a successful upload, so a failed run retries the same range.
              <em>Upload now</em> bypasses the daily gate only.</p>
              $enabledNote
              <form method="post" action="/crawler/backup/db"
                    onsubmit="return confirm('Upload the crawler DB dump to R2 now?')">
              <button type="submit" class="btn">Upload now</button></form>
              <div id="db-backup-status" class="meta">DB backup: ${st.state.name}</div>
            </fieldset>
            <fieldset class="card"><legend>DB restore (replay the R2 dump chain into a fresh database)</legend>
              <p class="meta">Stops the crawler, downloads + gunzips every dump object in the DB bucket
              (in chain order), and runs <code>libdhtcrawler.so --restore-database</code> to replay them into a
              fresh <code>torrents.db</code>, swapped in atomically. On success the upload bookmark resets to
              first run. The crawler is restarted afterwards in all outcomes. Needs the R2 endpoint/keys
              and the DB bucket configured (the upload toggle is not required).</p>
              <form method="post" action="/crawler/restore/db"
                    onsubmit="return confirm('Restore the crawler DB from R2 now? This replaces torrents.db and the crawler is stopped while it runs.')">
              <button type="submit" class="btn">Restore now</button></form>
              <div id="db-restore-status" class="meta">DB restore: ${rst.state.name}</div>
            </fieldset>
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
     * `/`). Returns the raw body on 200, null on any error/non-200 — the legacy
     * null-on-anything seam for /stats, which cannot tell a down crawler from
     * a 404 and must keep behaving exactly as before. The read timeout default
     * [CRAWLER_READ_TIMEOUT_MS] serves the fast indexed routes; the search feed
     * picks its own state-aware timeout — see [handleSearchFrame].
     * The connect timeout stays short so a down crawler fails fast.
     */
    private fun fetchCrawlerJson(
        pathAndQuery: String,
        readTimeoutMs: Int = CRAWLER_READ_TIMEOUT_MS,
    ): String? = (fetchCrawlerJsonStatus(pathAndQuery, readTimeoutMs) as? CrawlerFetch.Ok)?.body

    /**
     * One loopback crawler GET outcome. The torrent page (torrent-page stage 2)
     * needs the HTTP status: "the crawler has no such torrent" (404) and "the
     * crawler is down" are different answers for the user, and a sealed result
     * keeps the three cases explicit instead of overloading a status int.
     */
    private sealed interface CrawlerFetch {
        data class Ok(val body: String) : CrawlerFetch
        data object NotFound : CrawlerFetch
        data object Unavailable : CrawlerFetch
    }

    /**
     * Loopback GET of the crawler's JSON API, status-aware: 200 carries the raw
     * body, 404 is [CrawlerFetch.NotFound], and everything else (5xx, a timeout,
     * a refused connection) is [CrawlerFetch.Unavailable]. A non-200 error body
     * is drained and dropped so the JDK's keep-alive cache can reuse the socket.
     */
    private fun fetchCrawlerJsonStatus(pathAndQuery: String, readTimeoutMs: Int): CrawlerFetch {
        return try {
            val url = "http://127.0.0.1:${crawlerHttpPort()}$pathAndQuery"
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
            }
            val status = conn.responseCode
            if (status == 200) {
                CrawlerFetch.Ok(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                // An unread error body pins the socket; drain and discard it.
                try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    // Nothing to read — the status code alone is the answer.
                }
                if (status == 404) CrawlerFetch.NotFound else CrawlerFetch.Unavailable
            }
        } catch (e: Exception) {
            CrawlerFetch.Unavailable
        }
    }

    /**
     * Answers one torrent-detail forward route (torrent-page stage 2): validates
     * the built path, fetches it with that path's own read timeout, and wraps the
     * answer in the JSON convention shared with /search/ws — the crawler's raw
     * 200 body spliced verbatim under `payload`, and a fixed error code
     * otherwise: `bad_request:hash` for a route-side validation failure,
     * `not_found` for a crawler 404, `unavailable` for a down crawler, a
     * timeout, or any other status.
     */
    private fun torrentForwardJson(result: TorrentPathResult): String = when (result) {
        is TorrentPathResult.Bad -> """{"ok":false,"error":"bad_request:${result.field}"}"""
        is TorrentPathResult.Ok ->
            when (val r = fetchCrawlerJsonStatus(result.path, result.readTimeoutMs)) {
                is CrawlerFetch.Ok -> """{"ok":true,"payload":${r.body}}"""
                is CrawlerFetch.NotFound -> """{"ok":false,"error":"not_found"}"""
                is CrawlerFetch.Unavailable -> """{"ok":false,"error":"unavailable"}"""
            }
    }

    /**
     * True when the crawler supervisor reports the crawler daemon process
     * RUNNING (label [DaemonService.CRAWLER_LABEL]). A RUNNING process whose
     * loopback HTTP API lets a read timeout expire is busy — a big-DB search
     * can take quite a while — not starting or stopped.
     */
    private fun crawlerDaemonRunning(): Boolean = daemonStatuses().any {
        it.label == DaemonService.CRAWLER_LABEL && it.state == ProcessState.RUNNING
    }

    /**
     * Answers one /search/ws text frame. Never throws: an unparseable frame
     * yields `bad_request` (id 0), a missing/invalid `id` or `type` yields
     * `bad_request: <field>`, a non-object body yields `db_error`, and success
     * carries the crawler's raw JSON body verbatim under `payload` (so
     * escaping stays the crawler's). An unreachable crawler yields
     * `unavailable` — unless the daemon state says the crawler process is
     * RUNNING: then a timed-out loopback fetch means the crawler is busy, not
     * down, so the answer is `busy` and the query rides the long
     * [searchReadTimeoutMs] instead of the short [CRAWLER_READ_TIMEOUT_MS].
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
            // Only categories the crawler settings actually ingest are
            // searchable (the page renders those chips only, so a request for a
            // disabled one is a client bug and answers bad_request: category).
            allowedCategories = enabledCategories(loadConfig().crawler.saveCategories),
            tvKind = searchField(obj, "tv_kind"),
            adult = searchField(obj, "adult"),
            page = searchField(obj, "page"),
        )
        return when (result) {
            is CrawlerQueryResult.Bad ->
                "{\"id\":$id,\"ok\":false,\"error\":\"bad_request: ${result.field}\"}"
            is CrawlerQueryResult.Ok -> {
                // The actual daemon state (not the fetch outcome) picks the read
                // timeout and the "down" wording: a RUNNING crawler gets the
                // long search timeout (big-DB queries are slow) and a
                // timed-out/5xx fetch answers `busy`; anything else keeps the
                // short default and the "starting or stopped" answer.
                val crawlerRunning = crawlerDaemonRunning()
                val fetch = fetchCrawlerJsonStatus(
                    result.path,
                    if (crawlerRunning) searchReadTimeoutMs else CRAWLER_READ_TIMEOUT_MS,
                )
                when (fetch) {
                    is CrawlerFetch.Ok -> {
                        val body = fetch.body
                        val isObject = try {
                            JSONObject(body)
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (!isObject) "{\"id\":$id,\"ok\":false,\"error\":\"db_error\"}"
                        else "{\"id\":$id,\"ok\":true,\"payload\":$body}"
                    }
                    // The crawler answered 404: it is up, but that is not the
                    // answer a search query can use (kept as `unavailable`).
                    is CrawlerFetch.NotFound ->
                        "{\"id\":$id,\"ok\":false,\"error\":\"unavailable\"}"
                    is CrawlerFetch.Unavailable ->
                        if (crawlerRunning) "{\"id\":$id,\"ok\":false,\"error\":\"busy\"}"
                        else "{\"id\":$id,\"ok\":false,\"error\":\"unavailable\"}"
                }
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

        /** 32×32 PNG favicon (blue rounded square, white down-arrow) served
         * at /favicon.ico. Embedded as Base64 so the dashboard keeps its
         * zero-external-assets rule (no file assets, no external hosts). */
        internal val FAVICON_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABpElEQVRYw+2XTUsCQRjHfzt06RMkdgzq1MfoK4h4iM52CfIbSLf2KqIIIggeg+jUTercIRASPaoVxK4ZW760dtgptLednbb14u+yMPPM8//vzDPLPgafiB/ebgK7QBLYIBzaQA2odM1Yc3bCmBFeBXLAXkiiP1EG0l0z9vxhQIpfh/jGKjuy3TVjz0IO5CIUR2rlAAx55jcRis+yJfAKblHsCrxqXxTJFTTOvnO89uPceuYuSKoNEST6P1gaWBpYGjDih7dTv6Df7r0fft8FpR3YMR+0xFXWKRlo9CZkTweBxLOnAxq9STgGAAp1h9a9f0KA1v2EQt1RilU24E4hVbSVYlNFG9e3sgIaAOhYr+xX+7/G7Ff7dKxX5ZyBr+HJ1QvnjeG3c+eNISdXL4HyaX0HDmqPgcZDN2A7Lom8NTeWyFvYjhuNAYDL1ojShVfppQuHy9ZIK4/A+0XW4ujsae6pQVvgdSxaDMdT1jN3DMeKd+4rNQFUdFeHQEXIXq28APFy14w134swzR9qQYO21PRugWwUtyPaiTKyL4SZ7vidqNvzN4l8kfwbMAKSAAAAAElFTkSuQmCC")

        /** One /search/ws query-builder outcome (search-dashboard stage 2). */
        internal sealed interface CrawlerQueryResult {
            data class Ok(val path: String) : CrawlerQueryResult
            data class Bad(val field: String) : CrawlerQueryResult
        }

        // The search allow-lists are exactly the crawler taxonomy (stage 4): the
        // chips the page renders are hand-written below, but validation and the
        // settings form share one source of truth.
        private val SEARCH_CATEGORIES: Set<String> = CrawlerTaxonomy.CATEGORIES.toSet()
        private val SEARCH_TV_KINDS: Set<String> = CrawlerTaxonomy.TV_KINDS.toSet()
        private val SEARCH_ADULT_MODES = setOf("hide", "show", "only")

        /**
         * Resolves the crawler's `save_categories` setting into the categories
         * this server renders and accepts (search categories follow the crawler
         * ingest filter): names outside the taxonomy are dropped, and an empty
         * selection means "save everything" (the crawler's own convention, see
         * [CrawlerTaxonomy]), so it resolves to the full taxonomy. Pure: no
         * Android, no network, no clock, so it is unit-testable.
         */
        internal fun enabledCategories(saveCategories: List<String>): Set<String> {
            val known = saveCategories.filter { it in SEARCH_CATEGORIES }.toSet()
            return known.ifEmpty { SEARCH_CATEGORIES }
        }

        /** Display labels for the crawler settings page's category toggles. */
        private val CATEGORY_LABELS: Map<String, String> = mapOf(
            "unknown" to "Unknown", "movie" to "Movie", "tv" to "TV", "anime" to "Anime",
            "music" to "Music", "audiobook" to "Audiobook", "ebook" to "Ebook",
            "comic" to "Comic", "software" to "Software", "game" to "Game",
            "image" to "Image", "other" to "Other")

        /** Display labels for the crawler settings page's tv-kind toggles. */
        private val TV_KIND_LABELS: Map<String, String> = mapOf(
            "none" to "None", "episode" to "Episodes", "multi_episode" to "Multi-episode",
            "season_pack" to "Season packs", "multi_season" to "Multi-season",
            "complete_series" to "Complete series", "daily" to "Daily")

        /**
         * Validates one /search/ws request and builds the crawler loopback path
         * (+query) for it, or reports the single offending field. The path is
         * built only from enumerated/parsed values with a deterministic param
         * order; absent optional params are omitted so the crawler defaults
         * apply. `q` is percent-encoded (URLEncoder, UTF-8). `category` must be
         * a taxonomy name *and* one the app's crawler settings enable
         * ([allowedCategories], resolved by [enabledCategories]), so a disabled
         * category never reaches the daemon even if a client asks for it.
         * Pure: no Android, no network, so it is unit-testable.
         */
        internal fun buildCrawlerQuery(
            type: String?,
            q: String?,
            category: String?,
            allowedCategories: Set<String>,
            tvKind: String?,
            adult: String?,
            page: String?,
        ): CrawlerQueryResult {
            if (type != "search" && type != "browse" && type != "random") {
                return CrawlerQueryResult.Bad("type")
            }
            val cat = category?.takeIf { it.isNotEmpty() }
            if (cat != null && (cat !in SEARCH_CATEGORIES || cat !in allowedCategories)) {
                return CrawlerQueryResult.Bad("category")
            }
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
         * One torrent-detail path-builder outcome (torrent-page stage 2).
         * The read timeout rides along with the path so the slow `/refresh`
         * variant is observable from a pure unit test (no server, no clock).
         */
        internal sealed interface TorrentPathResult {
            data class Ok(val path: String, val readTimeoutMs: Int) : TorrentPathResult
            data class Bad(val field: String) : TorrentPathResult
        }

        /** Loopback read timeouts for the torrent-detail forward routes. */
        internal const val TORRENT_READ_TIMEOUT_MS = 15_000
        internal const val TORRENT_REFRESH_TIMEOUT_MS = 30_000

        /** Default loopback read timeout for one crawler JSON GET — the fast,
         * indexed routes (/stats, torrent detail). Short so a down crawler
         * fails fast. */
        internal const val CRAWLER_READ_TIMEOUT_MS = 15_000

        /** Read timeout for /search/ws queries (search/browse/random) while
         * the crawler daemon is RUNNING. FTS search over a big torrents DB can
         * take quite a long time (a `tv_kind=complete_series` search
         * especially), so this is 8x [CRAWLER_READ_TIMEOUT_MS]; the client
         * spinner shows "Searching…" for the whole wait. Injected per server
         * as `searchReadTimeoutMs`. */
        internal const val SEARCH_READ_TIMEOUT_MS = 120_000

        /** `hash` must be a full BEP3 info hash: exactly 40 hex chars. */
        internal const val INFO_HASH_HEX_LEN = 40

        /** `/torrent/files` paging defaults/clamps (mirrors the crawler's C handler). */
        internal const val FILES_DEFAULT_LIMIT = 50
        internal const val FILES_MAX_LIMIT = 200

        /** A `q` shorter than this cannot match the crawler's trigram FTS index. */
        internal const val FILES_MIN_FILTER_LEN = 3

        /**
         * Validates one `hash` query value: exactly 40 hex chars, case-insensitive,
         * LOWERCASED before it is forwarded — mirrors the crawler's C
         * `tp_get_hash_param` (http_torrent_page.c). Returns null when invalid.
         */
        internal fun torrentHash(hash: String?): String? {
            if (hash == null || hash.length != INFO_HASH_HEX_LEN) return null
            for (c in hash) {
                val hex = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
                if (!hex) return null
            }
            return hash.lowercase()
        }

        /**
         * Builds the crawler loopback path for `/torrent/info?hash=<hex>`.
         * Only `hash` can fail validation; everything else has a safe default.
         */
        internal fun buildTorrentInfoPath(hash: String?): TorrentPathResult {
            val h = torrentHash(hash) ?: return TorrentPathResult.Bad("hash")
            return TorrentPathResult.Ok("/torrent/info?hash=$h", TORRENT_READ_TIMEOUT_MS)
        }

        /**
         * Builds the crawler loopback path for `/torrent/files?hash=…&offset=…&limit=…[&q=…]`
         * with the C handler's clamping: a negative (or unparseable) `offset`
         * becomes 0, and a `limit` outside 1..200 becomes the default 50. A `q`
         * shorter than [FILES_MIN_FILTER_LEN] is DROPPED (not an error); longer
         * filters are percent-encoded (URLEncoder, UTF-8). Pure: no network.
         */
        internal fun buildTorrentFilesPath(
            hash: String?,
            offset: Int? = null,
            limit: Int? = null,
            q: String? = null,
        ): TorrentPathResult {
            val h = torrentHash(hash) ?: return TorrentPathResult.Bad("hash")
            val off = (offset ?: 0).coerceAtLeast(0)
            val lim = limit?.takeIf { it in 1..FILES_MAX_LIMIT } ?: FILES_DEFAULT_LIMIT
            val filter = q?.takeIf { it.length >= FILES_MIN_FILTER_LEN }
            val path = buildString {
                append("/torrent/files?hash=").append(h)
                append("&offset=").append(off)
                append("&limit=").append(lim)
                if (filter != null) append("&q=").append(URLEncoder.encode(filter, "UTF-8"))
            }
            return TorrentPathResult.Ok(path, TORRENT_READ_TIMEOUT_MS)
        }

        /**
         * Builds the crawler loopback path for `/refresh?hash=<hex>` (forced peer
         * re-query). This is the one slow route — a fresh DHT lookup can take
         * tens of seconds — so it carries [TORRENT_REFRESH_TIMEOUT_MS].
         */
        internal fun buildTorrentRefreshPath(hash: String?): TorrentPathResult {
            val h = torrentHash(hash) ?: return TorrentPathResult.Bad("hash")
            return TorrentPathResult.Ok("/refresh?hash=$h", TORRENT_REFRESH_TIMEOUT_MS)
        }

        /**
         * The server-rendered `#category-grid` chips for the /search page: the
         * "All" chip first (the default selection, `data-category=""`), then one
         * chip per enabled category in [CrawlerTaxonomy.CATEGORIES] order with
         * its [CATEGORY_LABELS] caption. [enabled] is a resolved set (see
         * [enabledCategories]); an empty one renders the full taxonomy, so the
         * page stays total. The page's JS reads these chips generically through
         * `data-category`, so nothing else has to change.
         */
        internal fun categoryChips(enabled: Set<String>): String {
            val active = enabled.ifEmpty { SEARCH_CATEGORIES }
            val chips = mutableListOf(
                """<button class="chip" type="button" data-category="" """ +
                    """aria-pressed="true">All</button>""")
            for (name in CrawlerTaxonomy.CATEGORIES) {
                if (name !in active) continue
                val label = CATEGORY_LABELS[name] ?: name
                chips += """<button class="chip" type="button" data-category="$name" """ +
                    """aria-pressed="false">$label</button>"""
            }
            // 14 spaces = the chip column of the (trimIndent()ed) page string.
            return chips.joinToString("\n").prependIndent("              ")
        }

        /**
         * The `Adult` selection row (Hide/Show/Only) of the /search page, or `""`
         * when it must not be offered. The row is only meaningful in
         * `adult_mode=tag`: in `adult_mode=drop` the crawler discards adult-tagged
         * rows at ingest, so a per-query adult filter could only ever be a no-op
         * (see [buildSearchPage], which resolves that from the crawler settings).
         * The page's JS reads the rendered chips generically through `data-adult`
         * and gates its wiring on the `ADULT_SELECTION` flag — the same decision.
         */
        private fun adultSelectionRow(show: Boolean): String = if (!show) "" else
            """<div class="seg-row"><span class="seg-label">Adult</span><div id="adult-control" class="seg" role="group" aria-label="Adult results"><button class="chip" type="button" data-adult="hide" aria-pressed="true">Hide</button><button class="chip" type="button" data-adult="show" aria-pressed="false">Show</button><button class="chip" type="button" data-adult="only" aria-pressed="false">Only</button></div></div>"""

        /**
         * The /search page (search-dashboard stage 3a): a static, self-contained
         * blue phone search shell. All markup, CSS and JS are inline — no
         * external assets, no CDN, no fonts. The search/browse/random/
         * infinite-scroll/add-button interaction logic lives in [SEARCH_JS],
         * wired to the authenticated /search/ws feed (token via
         * `location.search`). Per-torrent detail links point at this server's own
         * /torrent page, so the page needs no crawler origin.
         *
         * The category chips are server-rendered from [enabled] (resolved from
         * the crawler settings by [enabledCategories]), so a category the
         * crawler does not ingest is not offered at all; an empty set still
         * renders the full taxonomy ([categoryChips]).
         *
         * The adult selection row is rendered from [adultMode] the same way
         * ([adultSelectionRow]): in `adult_mode=drop` the crawler discards
         * adult-tagged rows at ingest, so the Hide/Show/Only filter would have
         * nothing left to filter and is neither rendered nor wired.
         */
        internal fun buildSearchPage(
            enabled: Set<String> = CrawlerTaxonomy.CATEGORIES.toSet(),
            adultMode: String = "tag",
        ): String {
            val adultSelection = !adultMode.trim().equals("drop", ignoreCase = true)
            return """
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
              </div>
            </div>
            <div id="category-grid">
${categoryChips(enabled)}
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
${adultSelectionRow(adultSelection)}
            <div id="results-status">Pick your filters, then Search or Randomize</div>
            <div id="banner" class="banner" hidden></div>
            <div id="spinner" hidden aria-hidden="true"></div>
            <div id="results"></div>
            <div id="sentinel"></div>
            <button id="load-more" class="btn secondary" type="button" hidden>Load more</button>
            <div id="toast" class="toast" hidden></div>
            </div>
            <script>
            var ADULT_SELECTION = ${if (adultSelection) "true" else "false"};
            $WEBSOCKET_CLIENT_JS
            $SEARCH_JS
            </script>
            </body>
            </html>
        """.trimIndent()
        }

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
            fieldset{margin:.8em 0;padding:12px;border:1px solid #e3e7ee;border-radius:12px;background:var(--card);box-shadow:0 1px 3px rgba(0,0,0,.06)}
            legend{padding:0 8px;margin-bottom:8px;color:var(--muted);font-weight:600;font-size:.85rem}
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
         * The shared WebUI websocket client (webui stage 3): `wsUrl` builds a
         * same-origin ws/wss URL from `location.host` plus the page's
         * `location.search` (so a `?token=` in the URL rides along — the
         * `control_token` cookie is sent automatically on the upgrade),
         * `setLive` drives the `#live` chip, `esc` escapes text for reuse, and
         * `connectSocket(path, handlers)` is the shared backoff/reconnect
         * loop: 1 s doubling to 30 s, `onerror` closes so `onclose` always runs.
         *
         * All three live pages share it: the dashboard (`/dashboard/ws`), the
         * crawler stats page (`/stats/ws`) and the search feed (`/search/ws`).
         */
        internal val WEBSOCKET_CLIENT_JS = """
            // ── shared websocket client ────────────────────────────────────
            var WS = (function () {
              var MAX_BACKOFF = 30000;

              function wsUrl(path) {
                var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                return proto + '//' + location.host + path + location.search;
              }

              function setLive(state, label) {
                var s = document.getElementById('live');
                if (!s) return;
                s.textContent = label;
                s.className = 'live ' + state;
              }

              function esc(s) {
                return String(s === null || s === undefined ? '' : s)
                  .replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;')
                  .replace(/"/g, '&quot;')
                  .replace(/'/g, '&#39;');
              }

              // handlers: {onopen(ws), onmessage(ev), onclose(backoff)} — all
              // optional. `onopen` receives the freshly opened socket so a page
              // that sends requests (the search feed) can hold the live handle
              // across reconnects.
              function connectSocket(path, handlers) {
                handlers = handlers || {};
                var backoff = 1000;
                var ws = null;

                function connect() {
                  ws = new WebSocket(wsUrl(path));
                  ws.onopen = function () {
                    backoff = 1000;
                    if (handlers.onopen) handlers.onopen(ws);
                  };
                  ws.onmessage = function (ev) {
                    if (handlers.onmessage) handlers.onmessage(ev);
                  };
                  ws.onclose = function () {
                    if (handlers.onclose) handlers.onclose(backoff);
                    var wait = backoff;
                    backoff = Math.min(backoff * 2, MAX_BACKOFF);
                    setTimeout(connect, wait);
                  };
                  ws.onerror = function () { try { ws.close(); } catch (e) {} };
                  return ws;
                }

                return connect();
              }

              return {wsUrl: wsUrl, setLive: setLive, esc: esc, connectSocket: connectSocket};
            })();
        """.trimIndent()

        /**
         * The dashboard live client (webui stage 3). It patches the
         * server-rendered markup in place — `.textContent`, classes and
         * attributes only — so scroll position and each card's open/closed
         * state survive every push by construction: the card shell is never
         * rebuilt and the page never navigates.
         *
         * A `null` value in a frame means "no fresh snapshot yet", so the
         * server-rendered value is left untouched (transmission, statsLine on
         * the first frames). Logs go through [patchLogs], the single writer
         * stage 4 refines into the tail-follow variant.
         */
        internal val DASHBOARD_JS = """
            (function () {
              function el(id) { return document.getElementById(id); }

              // Write only when the value actually changed, and never treat
              // null/undefined as a value: the first frames carry null for the
              // slow snapshots and must not blank the rendered cell.
              function patchText(node, v) {
                if (!node || v === null || v === undefined) return;
                var s = String(v);
                if (node.textContent !== s) node.textContent = s;
              }

              // State chips are styled by class, labelled by text.
              function patchState(node, state) {
                if (!node || !state) return;
                var cls = 'state ' + state;
                if (node.className !== cls) node.className = cls;
              }

              // The non-per-daemon `data-field` hooks are unique on the page
              // (downloadDir, limits, ftpUrl, statsLine, …).
              function field(name) {
                return document.querySelector('[data-field="' + name + '"]');
              }

              // The per-daemon ones (state/pid/uptime/restarts/extras) are
              // scoped to their card through data-daemon.
              function card(label) {
                var all = document.querySelectorAll('[data-daemon]');
                for (var i = 0; i < all.length; i++) {
                  if (all[i].getAttribute('data-daemon') === label) return all[i];
                }
                return null;
              }

              function fieldIn(scope, name) {
                if (!scope) return null;
                return scope.querySelector('[data-field="' + name + '"]');
              }

              // The only log writer: tail-follow with a position hold.
              // A cheap signature of the tail (line count, length, last 80
              // chars) gates every write, so a push repeating an unchanged
              // tail costs one getAttribute and no DOM write, no reflow and
              // no scroll touch. When the tail did change, the pane follows
              // it only if the user was already at the bottom; otherwise the
              // position is held. Only pre.scrollTop is ever written, never
              // the page scroll.
              function patchLogs(pre, lines) {
                if (!pre || !lines) return;
                var text = lines.join('\n');
                var sig = lines.length + ':' + text.length + ':' + text.slice(-80);
                if (pre.getAttribute('data-logsig') === sig) return;
                var wasAtBottom =
                  pre.scrollHeight - pre.scrollTop - pre.clientHeight < 8;
                // textContent, never innerHTML: log lines are plain text.
                pre.textContent = text;
                pre.setAttribute('data-logsig', sig);
                if (wasAtBottom) pre.scrollTop = pre.scrollHeight;
              }

              function applyFrame(ev) {
                var f = null;
                try { f = JSON.parse(ev.data); } catch (e) { return; }
                if (!f) return;

                if (f.service) {
                  patchState(el('service-state'), f.service.state);
                  patchText(el('service-state'), f.service.state);
                  var banner = el('rollback-banner');
                  if (banner) {
                    var hide = !f.service.rolledBack;
                    if (banner.hidden !== hide) banner.hidden = hide;
                  }
                }

                var daemons = f.daemons || [];
                for (var i = 0; i < daemons.length; i++) {
                  var d = daemons[i];
                  var section = card(d.label);
                  if (!section) continue;
                  patchState(fieldIn(section, 'state'), d.state);
                  patchText(fieldIn(section, 'state'), d.state);
                  patchText(fieldIn(section, 'pid'),
                    d.pid === null || d.pid === undefined ? '—' : d.pid);
                  patchText(fieldIn(section, 'uptime'), d.uptime);
                  patchText(fieldIn(section, 'restarts'), d.restarts);
                  // Crawler-only extras: absent for the other daemons, and
                  // statsLine is null until the crawler's /stats answers.
                  patchText(fieldIn(section, 'dbSize'), d.dbSize);
                  patchText(fieldIn(section, 'internalFree'), d.internalFree);
                  patchText(fieldIn(section, 'statsLine'), d.statsLine);
                  // The paused badge only exists while paused: show/hide it
                  // instead of building any markup.
                  var paused = fieldIn(section, 'pausedNoWifi');
                  if (paused && typeof d.pausedNoWifi === 'boolean') {
                    paused.style.display = d.pausedNoWifi ? '' : 'none';
                  }
                  patchLogs(section.querySelector('[data-log-for]'), d.logs);
                }

                // Transmission scalars come from the ticker's cached RPC
                // snapshot and are always present: `reachable` false means the
                // last RPC refresh failed, so the banner comes back on and the
                // values blank out (a later recovery clears them again).
                var tx = f.transmission;
                if (tx) {
                  var red = field('rpcUnreachable');
                  if (tx.reachable) {
                    if (red) red.hidden = true;
                    patchText(field('downloadDir'), tx.downloadDir);
                    patchText(field('limits'),
                      '↓' + tx.speedLimitDown + ' ↑' + tx.speedLimitUp +
                      ' KB/s (0 = unlimited)');
                  } else {
                    if (red) red.hidden = false;
                    patchText(field('downloadDir'), '—');
                    patchText(field('limits'), '—');
                  }
                }

                if (f.ftp) {
                  patchText(field('ftpUrl'), f.ftp.url);
                  patchText(field('ftpPort'), f.ftp.port);
                }
                if (typeof f.wifiUp === 'boolean') {
                  patchText(field('wifiUp'), f.wifiUp ? 'up' : 'down');
                }
              }

              // One delegated click on the head toggles the sibling body. The
              // shell is never rebuilt, so a card's open/closed state survives
              // every push by construction.
              document.addEventListener('click', function (ev) {
                var head = ev.target && ev.target.closest
                  ? ev.target.closest('.daemon-head[data-toggle]') : null;
                if (!head) return;
                var body = head.parentNode
                  ? head.parentNode.querySelector('.daemon-body') : null;
                if (!body) return;
                body.hidden = !body.hidden;
                var tri = head.querySelector('.tri');
                if (tri) tri.textContent = body.hidden ? '▸' : '▾';
              });

              // The control forms stay real POSTs (they work with JS disabled).
              // With JS we POST via fetch so the page does not navigate; the
              // next push reflects the new state.
              document.addEventListener('submit', function (ev) {
                var form = ev.target;
                if (!form || !form.classList ||
                    !form.classList.contains('daemon-actions')) return;
                ev.preventDefault();
                fetch(form.action, {method:'POST',credentials:'same-origin',redirect:'manual'})
                  .catch(function () {});
              });

              WS.connectSocket('/dashboard/ws', {
                onopen: function () { WS.setLive('on', 'live'); },
                onmessage: applyFrame,
                onclose: function (backoff) {
                  WS.setLive('wait',
                    'reconnecting in ' + Math.round(backoff / 1000) + 's…');
                }
              });
            })();
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
            .actions{display:flex;flex-wrap:wrap;align-items:center;gap:10px 14px;margin-top:10px}
            .add-btn{min-height:40px;padding:0 16px}
            .refresh-btn{min-height:40px;min-width:40px;padding:0}
            .imdb-btn{min-height:40px;padding:0 12px}
            .detail{color:var(--blue);text-decoration:none;font-size:1.1rem;font-weight:700;padding:8px}
        """.trimIndent()

        /**
         * The /search page interaction logic (search-dashboard stage 3b): the
         * authenticated /search/ws client, category/TV/adult controls,
         * result cards, infinite scroll and the add-to-transmission button.
         *
         * The adult wiring is gated on the `ADULT_SELECTION` flag that
         * [buildSearchPage] emits just before this script body: the row is only
         * rendered in `adult_mode=tag`, so `el('adult-control')` is null in
         * `drop` mode and the handler must not be installed at all. `sel.adult`
         * keeps its `'hide'` default either way, so the /search/ws payload is
         * unchanged.
         */
        private val SEARCH_JS = """
            (function () {
              'use strict';
              // The live socket handle, refreshed by the shared client on every
              // (re)connect; send()/loadMore() write through it.
              var ws = null;

              var sel = { category: null, tv_kind: null, adult: 'hide' };
              var current = null;
              var seq = 0;
              var activeId = 0;
              var loading = false;
              var autoLoadMore = false;
              var observer = null;
              var pendingAppend = false;
              var pendingReq = null;
              var toastTimer = null;
              // ?q= keyword prefill handed over by the /torrent page (torrent-page
              // stage 2); it is applied on the first socket open, not before.
              var prefill = null;
              // Back-navigation restore (Search -> torrent detail -> Back): the
              // detail link is a full page navigation, so /search is re-fetched
              // from scratch on Back. The last result set is mirrored into
              // sessionStorage (per-tab: survives Back/Forward, dies with the
              // tab) and repainted on load instead of coming back empty.
              var SAVE_KEY = 'torrent.search.v1';
              var resultsAccum = [];
              var lastQuery = null;

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
              var IMDB_CATEGORIES = ['movie', 'tv'];
              function imdbQuery(r) {
                // filtered_name is the crawler's extracted media title; never rebuild a
                // query from the raw release name. A missing/blank value means no usable
                // title was extractable.
                if (r == null || r.filtered_name == null) return null;
                var s = String(r.filtered_name).replace(/\s+/g, ' ').trim();
                return s.length > 0 ? s : null;
              }
              function imdbCategory(r) {
                return r != null && r.category != null &&
                  IMDB_CATEGORIES.indexOf(String(r.category)) !== -1;
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

                if (imdbCategory(r)) {
                  var imdbBtn = document.createElement('button');
                  imdbBtn.type = 'button';
                  imdbBtn.className = 'btn imdb-btn';
                  imdbBtn.textContent = 'Search IMDb';
                  if (imdbQuery(r) == null) {
                    imdbBtn.disabled = true;
                    imdbBtn.title = 'No IMDb-searchable title';
                    imdbBtn.setAttribute('aria-label', 'Search IMDb (unavailable: no searchable title)');
                  } else {
                    imdbBtn.onclick = function () {
                      var a = document.createElement('a');
                      a.href = 'https://www.imdb.com/find/?q=' + encodeURIComponent(imdbQuery(r));
                      a.target = '_blank';
                      a.rel = 'noopener noreferrer';
                      document.body.appendChild(a);
                      a.click();
                      document.body.removeChild(a);
                    };
                  }
                  actions.appendChild(imdbBtn);
                }
                var add = document.createElement('button');
                add.type = 'button';
                add.className = 'btn add-btn';
                add.textContent = '\u2795 Add';
                add.onclick = function () { addTorrent(r.info_hash, add); };
                actions.appendChild(add);
                var refreshBtn = document.createElement('button');
                refreshBtn.type = 'button';
                refreshBtn.className = 'btn refresh-btn';
                refreshBtn.setAttribute('title', 'Refresh peers');
                refreshBtn.setAttribute('aria-label', 'Refresh peers');
                refreshBtn.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/></svg>';
                refreshBtn.onclick = function () { refreshPeers(r.info_hash, refreshBtn, peers); };
                actions.appendChild(refreshBtn);

                var detail = document.createElement('a');
                detail.className = 'detail';
                detail.textContent = '\u2026';
                // Same-origin detail page: this server serves /torrent itself
                // (torrent-page stage 2), so no crawler proxy origin is needed.
                detail.href = '/torrent?hash=' + encodeURIComponent(r.info_hash || '');
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
                if (!wasAppend) resultsAccum = [];
                for (var i = 0; i < results.length; i++) {
                  resultsAccum.push(results[i]);
                  box.appendChild(buildCard(results[i]));
                }
                // Appended cards can leave the sentinel still intersecting, so no
                // new IntersectionObserver callback fires and infinite scroll stalls.
                // Re-observe to force a fresh intersection evaluation.
                if (observer) { observer.unobserve(el('sentinel')); observer.observe(el('sentinel')); }

                if (req.type === 'random') {
                  current = null;
                  statusText('Random pick');
                  lastQuery = {
                    mode: 'random', q: null, page: 1, has_more: false, statusText: 'Random pick'
                  };
                  save();
                  return;
                }
                var page = typeof payload.page === 'number' ? payload.page : (req.page || 1);
                current = {
                  type: req.type, q: req.q, category: req.category, tv_kind: req.tv_kind,
                  adult: req.adult, page: page, has_more: !!payload.has_more
                };
                var n = typeof payload.total_count === 'number' ? payload.total_count : results.length;
                var txt = n + (n === 1 ? ' result' : ' results');
                if (req.type === 'search') txt += ' \u00b7 best matches first';
                statusText(txt);
                lastQuery = {
                  mode: req.type === 'search' ? 'search' : 'browse',
                  q: req.q == null ? null : String(req.q),
                  page: page, has_more: !!payload.has_more, statusText: txt
                };
                save();
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
                  tv_kind: obj.tv_kind, adult: obj.adult, page: obj.page
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
                  adult: sel.adult, page: 1
                }, false);
              }

              function loadMore() {
                if (!current || !current.has_more || loading) return;
                // A restored list can leave the sentinel visible before the feed
                // is open; sending then would only flash a "not connected" banner.
                if (!ws || ws.readyState !== 1) return;
                var c = current;
                send({
                  type: c.type, q: c.q, category: c.category, tv_kind: c.tv_kind,
                  adult: c.adult, page: c.page + 1
                }, true);
              }

              function handleError(err) {
                err = err == null ? '' : String(err);
                statusText('');
                if (err === 'unavailable') {
                  setLive('down', 'unavailable');
                  showBanner('amber', 'Crawler is starting or stopped');
                } else if (err === 'busy') {
                  // The server checked the daemon state: the crawler process is
                  // RUNNING and the query simply outlasted the search read
                  // timeout (big DB). Not a crash — retry or narrow the filters.
                  showBanner('amber', 'Crawler is busy \u2014 the search timed out. Try again or narrow the filters.');
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

              // The socket (URL, capped backoff, reconnect) is the shared client.
              // This page keeps its own setLive: its #live is a coloured dot
              // (`#live.on` / `#live.wait`) with a title, not the stats page's
              // text chip the shared helper writes.
              function connect() {
                WS.connectSocket('/search/ws', {
                  onopen: function (sock) {
                    ws = sock;
                    setLive('ok', 'live');
                    hideBanner();
                    setLoading(false);
                    // ?q= prefill (torrent-page stage 2): /torrent links here with
                    // the selected keywords. Run the same search the Search button
                    // runs, but only now that the feed can accept it.
                    if (prefill) {
                      var q = prefill;
                      prefill = null;
                      el('search-input').value = q;
                      doSearch();
                      return;
                    }
                    // Never overwrite a restored list: a random restore has no
                    // `current` but must keep its 'Random pick' status line.
                    if (!current && !restored) statusText('Live \u2014 pick your filters, then Search or Randomize');
                  },
                  onmessage: onMessage,
                  onclose: function () {
                    setLive('wait', 'reconnecting');
                    setLoading(false);
                    showBanner('amber', 'Connecting\u2026 (search feed unavailable)');
                  }
                });
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

              // Per-card peer refresh: the same /api/crawler/refresh route the
              // torrent page uses, but the outcome lands in this card's peers span
              // instead of replacing a peer-info line.
              function refreshPeers(hash, btn, peers) {
                if (!hash) { toast('Bad info hash', true); return; }
                if (btn.disabled) return;
                btn.disabled = true;
                peers.textContent = 'Refreshing\u2026';
                // The route holds the connection up to 30 s; give up before it.
                var ctrl = new AbortController();
                var timer = setTimeout(function () { ctrl.abort(); }, 25000);
                fetch('/api/crawler/refresh?hash=' + encodeURIComponent(hash), { signal: ctrl.signal })
                  .then(function (r) { return r.json(); })
                  .then(function (j) {
                    clearTimeout(timer);
                    btn.disabled = false;
                    if (!j || !j.ok || !j.payload || typeof j.payload.total_peers !== 'number') {
                      peers.textContent = 'Refresh failed';
                      toast('Refresh failed', true);
                      return;
                    }
                    var n = j.payload.total_peers;
                    peers.textContent = n + ' peers';
                    peers.className = 'peers ' + (n > 100 ? 'hi' : (n > 10 ? 'mid' : 'lo'));
                  })
                  .catch(function () { clearTimeout(timer); btn.disabled = false; peers.textContent = 'Refresh failed'; toast('Refresh failed', true); });
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
                resultsAccum = [];
                lastQuery = null;
                try { sessionStorage.removeItem(SAVE_KEY); } catch (e) {}
              }

              // ── back-navigation restore ──────────────────────────────────
              // save()/restore() only repaint: neither one touches the feed, so
              // returning from /torrent never re-runs the query.
              // True when a row is in focus mode: a chip was pressed and its
              // siblings are hidden (see chipFocus below). Mirrored into the
              // saved payload so Back restores the same control layout.
              function rowFocused(row) {
                if (!row) return false;
                var chips = row.querySelectorAll('.chip');
                for (var i = 0; i < chips.length; i++) {
                  if (chips[i].classList.contains('hidden')) return true;
                }
                return false;
              }

              // Hide every chip in a row except the pressed one (focus mode).
              function focusRow(row) {
                var chips = row.querySelectorAll('.chip');
                for (var i = 0; i < chips.length; i++) {
                  if (chips[i].getAttribute('aria-pressed') === 'true') chips[i].classList.remove('hidden');
                  else chips[i].classList.add('hidden');
                }
              }

              function save() {
                try {
                  sessionStorage.setItem(SAVE_KEY, JSON.stringify({
                    sel: { category: sel.category, tv_kind: sel.tv_kind, adult: sel.adult },
                    focus: { category: rowFocused(el('category-grid')), tv_kind: rowFocused(el('tv-kind-row')) },
                    mode: lastQuery ? lastQuery.mode : null,
                    q: lastQuery ? lastQuery.q : null,
                    page: lastQuery ? lastQuery.page : 1,
                    has_more: lastQuery ? !!lastQuery.has_more : false,
                    statusText: lastQuery ? lastQuery.statusText : '',
                    results: resultsAccum
                  }));
                } catch (e) {}
              }

              function restore() {
                var saved = null;
                try {
                  var raw = sessionStorage.getItem(SAVE_KEY);
                  if (!raw) return false;
                  saved = JSON.parse(raw);
                } catch (e) { return false; }
                if (!saved || typeof saved !== 'object') return false;
                var list = saved.results || [];
                if (!list.length) return false;
                try {
                  if (saved.sel && typeof saved.sel === 'object') {
                    sel.category = saved.sel.category == null ? null : String(saved.sel.category);
                    sel.tv_kind = saved.sel.tv_kind == null ? null : String(saved.sel.tv_kind);
                    if (saved.sel.adult != null) sel.adult = String(saved.sel.adult);
                  }
                  applySel(saved.focus && typeof saved.focus === 'object' ? saved.focus : null);
                  var box = el('results');
                  box.textContent = '';
                  for (var i = 0; i < list.length; i++) box.appendChild(buildCard(list[i]));
                  resultsAccum = list;
                  if (saved.q != null) el('search-input').value = String(saved.q);
                  lastQuery = {
                    mode: saved.mode == null ? null : String(saved.mode),
                    q: saved.q == null ? null : String(saved.q),
                    page: typeof saved.page === 'number' ? saved.page : 1,
                    has_more: !!saved.has_more,
                    statusText: saved.statusText == null ? '' : String(saved.statusText)
                  };
                  if (lastQuery.mode === 'search' || lastQuery.mode === 'browse') {
                    current = {
                      type: lastQuery.mode, q: lastQuery.q, category: sel.category,
                      tv_kind: sel.tv_kind, adult: sel.adult, page: lastQuery.page,
                      has_more: lastQuery.has_more
                    };
                  } else {
                    current = null;
                  }
                  if (lastQuery.statusText) statusText(lastQuery.statusText);
                  return true;
                } catch (e) { return false; }
              }

              // Repaint the restored selection onto the visible controls: the
              // category chip, the TV-kind row (shown for tv/anime only) and,
              // only when the server rendered it, the adult control.
              function applySel(focus) {
                var grid = el('category-grid');
                if (grid) {
                  var catChip = grid.querySelector('.chip[data-category="' + (sel.category || '') + '"]');
                  var allChip = grid.querySelector('.chip[data-category=""]');
                  if (catChip || allChip) selectOnly(grid, catChip || allChip);
                  // Re-apply focus mode (pressed chip visible, siblings hidden)
                  // so Back returns to the exact control layout it left behind.
                  if (focus && focus.category) focusRow(grid); else revealRow(grid);
                }
                var tvRow = el('tv-kind-row');
                if (tvRow) {
                  var showTv = sel.category === 'tv' || sel.category === 'anime';
                  if (showTv) tvRow.removeAttribute('hidden');
                  else tvRow.setAttribute('hidden', '');
                  var kind = showTv && sel.tv_kind ? sel.tv_kind : 'none';
                  var kindChip = tvRow.querySelector('.chip[data-tv-kind="' + kind + '"]');
                  if (kindChip) selectOnly(tvRow, kindChip);
                  if (showTv && focus && focus.tv_kind) focusRow(tvRow); else revealRow(tvRow);
                }
                if (ADULT_SELECTION) {
                  var adultRow = el('adult-control');
                  if (adultRow) {
                    var chips = adultRow.querySelectorAll('.chip');
                    for (var i = 0; i < chips.length; i++) {
                      if (chips[i].getAttribute('data-adult') === sel.adult) {
                        selectOnly(adultRow, chips[i]);
                        break;
                      }
                    }
                  }
                }
              }

              // Focus mode: pressing a chip selects it and hides its siblings; pressing
              // the pressed chip again reverts the row to its default and unhides it.
              // Returns true when this call was a revert (chip was already pressed).
              function chipFocus(row, chip, defaultChip) {
                if (chip.getAttribute('aria-pressed') === 'true') {
                  selectOnly(row, defaultChip);
                  revealRow(row);
                  return true;
                }
                selectOnly(row, chip);
                var chips = row.querySelectorAll('.chip');
                for (var i = 0; i < chips.length; i++) {
                  if (chips[i] === chip) {
                    chips[i].classList.remove('hidden');
                  } else {
                    chips[i].classList.add('hidden');
                  }
                }
                return false;
              }
              function revealRow(row) {
                var chips = row.querySelectorAll('.chip');
                for (var i = 0; i < chips.length; i++) {
                  chips[i].classList.remove('hidden');
                }
              }
              el('category-grid').onclick = function (ev) {
                var t = ev.target;
                var cat = t && t.getAttribute ? t.getAttribute('data-category') : null;
                if (cat == null) return;
                var grid = el('category-grid');
                var allChip = grid.querySelector('.chip[data-category=""]');
                var tvRow = el('tv-kind-row');
                if (chipFocus(grid, t, allChip)) {
                  sel.category = null;
                  tvRow.setAttribute('hidden', '');
                  sel.tv_kind = null;
                  selectOnly(tvRow, tvRow.querySelector('.chip[data-tv-kind="none"]'));
                  revealRow(tvRow);
                  clearResults();
                  return;
                }
                sel.category = cat === '' ? null : cat;
                clearResults();
                if (cat === 'tv' || cat === 'anime') {
                  tvRow.removeAttribute('hidden');
                } else {
                  tvRow.setAttribute('hidden', '');
                  sel.tv_kind = null;
                  selectOnly(tvRow, tvRow.querySelector('.chip[data-tv-kind="none"]'));
                  revealRow(tvRow);
                }
              };

              el('tv-kind-row').onclick = function (ev) {
                var t = ev.target;
                var k = t && t.getAttribute ? t.getAttribute('data-tv-kind') : null;
                if (k == null) return;
                var row = el('tv-kind-row');
                var noneChip = row.querySelector('.chip[data-tv-kind="none"]');
                if (chipFocus(row, t, noneChip)) {
                  sel.tv_kind = null;
                  clearResults();
                  return;
                }
                sel.tv_kind = k === 'none' ? null : k;
                clearResults();
              };

              // Only wired when the server rendered the row (`adult_mode=tag`); in
              // `drop` mode there is no filter to drive and el() would be null.
              if (ADULT_SELECTION) {
                el('adult-control').onclick = function (ev) {
                  var t = ev.target;
                  var a = t && t.getAttribute ? t.getAttribute('data-adult') : null;
                  if (a == null) return;
                  selectOnly(el('adult-control'), t);
                  sel.adult = a;
                  clearResults();
                };
              }

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

              // Read the keyword prefill from this page's own query string; the
              // search itself waits for the first socket open (send() would bail
              // on a not-yet-open feed).
              var prefillQ = new URLSearchParams(location.search).get('q');
              // An explicit ?q= hand-off wins over a restored list: it is a new
              // query from /torrent, not a back-navigation.
              var restored = false;
              if (prefillQ && prefillQ.trim() !== '') {
                prefill = prefillQ.trim();
                el('search-input').value = prefill;
              } else {
                restored = restore();
              }

              setupObserver();
              connect();
            })();
        """.trimIndent()

        /**
         * The /torrent detail page (torrent-page stage 2): the Kotlin twin of the
         * crawler's C page, on the control server's :8090 origin. It is a PURE
         * string builder — no fetcher, no network, no config lookups — so it is
         * unit-testable and can never leak a token into the markup: the hash (and
         * an optional token) are read client-side from `location.search`, exactly
         * like the token on /search.
         *
         * The page talks only to same-origin routes: /api/crawler/torrent/info,
         * /api/crawler/torrent/files, /api/crawler/refresh, /api/transmission/add
         * and /search?q= (IMDb is the one external link). Markup/CSS/JS are all
         * inline; every crawler value reaches the DOM through textContent only.
         */
        internal fun buildTorrentPage(): String = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <title>Torrent</title>
            <style>$TORRENT_CSS</style>
            </head>
            <body>
            <!-- Torrent detail shell: the info hash rides in location.search, so the
                 server renders nothing torrent-specific here. -->
            <div class="wrap">
            <header><h1 id="name">Torrent</h1><nav><a id="back" href="/">&larr; Back</a></nav></header>
            <div id="banner" class="banner red" hidden></div>
            <div id="detail" hidden>
              <div class="meta" id="meta"></div>
              <p class="hint">Select keywords for search:</p>
              <div class="pills" id="pills"></div>
              <div class="query-preview" id="query-preview"></div>
              <div class="btnrow">
                <button id="imdb-btn" class="btn" type="button">Search IMDb</button>
                <button id="dht-btn" class="btn secondary" type="button">Search DHT</button>
                <button id="refresh-btn" class="btn secondary refresh-btn" type="button" title="Refresh peers" aria-label="Refresh peers"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/></svg></button>
                <button id="add-btn" class="btn secondary" type="button">&#10133; Add to Transmission</button>
                <span id="peer-info" class="hint"></span>
              </div>
              <h3>Files</h3>
              <div class="file-tools">
                <input id="file-filter" type="search" placeholder="Filter files&hellip;" autocomplete="off">
              </div>
              <ul class="file-list" id="file-list"></ul>
              <div class="sentinel" id="sentinel">Loading&hellip;</div>
            </div>
            <div id="toast" class="toast" hidden></div>
            </div>
            <script>
            $TORRENT_JS
            </script>
            </body>
            </html>
        """.trimIndent()

        /**
         * The /torrent page CSS (torrent-page stage 2): the shared search theme
         * plus the keyword pills, the query preview, the button row and the
         * paginated file list. A `private val` (not `const val`) because it is a
         * `trimIndent()` expression, and declared after [SEARCH_CSS] because it
         * extends it.
         */
        private val TORRENT_CSS = SEARCH_CSS + "\n" + """
            .hint{color:var(--muted);font-size:.9rem;margin:8px 0 4px}
            .pills{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}
            .pill{display:inline-flex;align-items:center;min-height:38px;padding:0 14px;font-size:.9rem;color:var(--text);background:var(--card);border:1px solid #dadce0;border-radius:19px;cursor:pointer;user-select:none;touch-action:manipulation}
            .pill.on{background:var(--blue);border-color:var(--blue);color:#fff;font-weight:600}
            .query-preview{background:var(--chip);color:var(--blue);padding:6px 10px;border-radius:8px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.85rem;margin:8px 0;overflow-wrap:anywhere}
            .btnrow{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:12px 0}
            .file-tools{display:flex;gap:8px;margin:8px 0}
            #file-filter{flex:1 1 auto;min-width:0;height:44px;padding:0 14px;font-size:.95rem;color:var(--text);border:1px solid #dadce0;border-radius:22px;outline:none;background:#fff}
            #file-filter:focus{border-color:var(--blue);box-shadow:0 0 0 2px var(--chip)}
            .file-list{list-style:none;margin:0;padding:0;background:var(--card);border:1px solid #e3e7ee;border-radius:12px;overflow:hidden}
            .file-list li{display:flex;justify-content:space-between;gap:8px;padding:8px 12px;border-top:1px solid #eee;font-size:.9rem}
            .file-list li:first-child{border-top:none}
            .file-path{overflow-wrap:anywhere}
            .file-size{color:var(--muted);flex-shrink:0;white-space:nowrap}
            .sentinel{padding:10px;text-align:center;color:var(--muted);font-size:.85rem}
        """.trimIndent()

        /**
         * The /torrent page interaction logic (torrent-page stage 2), ported
         * from the crawler's C page: keyword pills, IMDb/DHT search, refresh
         * peers, add-to-Transmission and the paginated/filterable file list.
         * Only same-origin :8090 routes are contacted; nothing here inlines the
         * control token.
         */
        private val TORRENT_JS = """
            (function () {
              'use strict';
              var hash = new URLSearchParams(location.search).get('hash') || '';
              var FILE_BATCH = 50;
              var fileOffset = 0;
              var fileLoading = false;
              var fileHasMore = true;
              var fileQuery = '';
              var filterTimer = null;
              var toastTimer = null;
              var fileGen = 0;

              function el(id) { return document.getElementById(id); }

              // %.2f with 1024 steps: byte-for-byte parity with the crawler's C
              // tp_format_size (http_torrent_page.c).
              function fmtSize(bytes) {
                var units = ['B', 'KB', 'MB', 'GB', 'TB'];
                var unit = 0;
                var size = typeof bytes === 'number' && isFinite(bytes) ? bytes : 0;
                while (size >= 1024 && unit < 4) { size = size / 1024; unit++; }
                return size.toFixed(2) + ' ' + units[unit];
              }

              function fmtGiB(b) {
                var n = typeof b === 'number' && isFinite(b) ? b : 0;
                return (n / 1073741824).toFixed(2) + ' GiB';
              }

              function toast(msg, isErr) {
                var t = el('toast');
                t.textContent = msg;
                t.className = 'toast' + (isErr ? ' err' : '');
                t.removeAttribute('hidden');
                if (toastTimer) clearTimeout(toastTimer);
                toastTimer = setTimeout(function () { t.setAttribute('hidden', ''); }, 4000);
              }

              function setState(msg) {
                el('detail').setAttribute('hidden', '');
                el('banner').textContent = msg;
                el('banner').removeAttribute('hidden');
                el('name').textContent = 'Torrent';
              }

              function showDetail() {
                el('banner').setAttribute('hidden', '');
                el('detail').removeAttribute('hidden');
              }

              // The control token is never server-rendered into this page: prefer
              // the ?token= of this nav (the auth interceptor preserves it), else
              // the control_token cookie set by that same interceptor.
              function tokenSuffix() {
                var t = new URLSearchParams(location.search).get('token');
                if (!t) {
                  var m = /(?:^|;\s*)control_token=([^;]+)/.exec(document.cookie || '');
                  if (m) t = decodeURIComponent(m[1]);
                }
                return t ? '&token=' + encodeURIComponent(t) : '';
              }

              // Name -> search words: dots/underscores become spaces, the token
              // split is on whitespace, and ( ) [ ] { } are stripped (C parity).
              function tokenizeName(name) {
                var s = (name == null ? '' : String(name)).replace(/[._]/g, ' ');
                var parts = s.split(/\s+/);
                var out = [];
                for (var i = 0; i < parts.length; i++) {
                  var w = parts[i].replace(/[()\[\]{}]/g, '');
                  if (w) out.push(w);
                }
                return out;
              }

              // Case-insensitive whole-token match against the crawler's
              // filtered_name (its extracted media title).
              function wordInFiltered(word, filtered) {
                if (!word || !filtered) return false;
                var tokens = String(filtered).split(/\s+/);
                var lw = String(word).toLowerCase();
                for (var i = 0; i < tokens.length; i++) {
                  if (tokens[i].toLowerCase() === lw) return true;
                }
                return false;
              }

              function selectedWords() {
                var out = [];
                var pills = el('pills').querySelectorAll('.pill');
                for (var i = 0; i < pills.length; i++) {
                  if (pills[i].classList.contains('on')) out.push(pills[i].getAttribute('data-word'));
                }
                return out;
              }

              function updatePreview() {
                var q = selectedWords().join(' ');
                el('query-preview').textContent = q || '(no words selected)';
              }

              function renderPills(name, filteredName) {
                var box = el('pills');
                box.textContent = '';
                var words = tokenizeName(name);
                for (var i = 0; i < words.length; i++) {
                  if (!wordInFiltered(words[i], filteredName)) continue;
                  var pill = document.createElement('span');
                  pill.className = 'pill on';
                  pill.setAttribute('data-word', words[i]);
                  pill.textContent = words[i];
                  pill.onclick = function () {
                    if (this.classList.contains('on')) this.classList.remove('on');
                    else this.classList.add('on');
                    updatePreview();
                  };
                  box.appendChild(pill);
                }
                updatePreview();
              }

              function infoError(err) {
                err = err == null ? '' : String(err);
                if (err.indexOf('bad_request') === 0) {
                  setState('Bad request: the info hash in this URL is not a 40-character hex string.');
                } else if (err === 'not_found') {
                  setState('Torrent not found');
                } else {
                  setState('Crawler unavailable \u2014 it may be starting or stopped.');
                }
              }

              function renderInfo(payload) {
                var info = payload && typeof payload === 'object' ? payload : {};
                var name = info.name != null ? String(info.name) : '(unnamed)';
                document.title = name;
                el('name').textContent = name;
                var peers = typeof info.total_peers === 'number' ? info.total_peers : 0;
                var files = typeof info.file_count === 'number' ? info.file_count : 0;
                var hex = info.info_hash != null ? String(info.info_hash) : hash;
                el('meta').textContent = fmtSize(info.size_bytes) + ' \u00b7 ' +
                  peers + (peers === 1 ? ' peer' : ' peers') + ' \u00b7 ' +
                  files + (files === 1 ? ' file' : ' files') + ' \u00b7 ' + hex;
                el('peer-info').textContent = peers + (peers === 1 ? ' peer' : ' peers');
                renderPills(info.name, info.filtered_name);
                showDetail();
                loadFiles();
              }

              function loadInfo() {
                if (!hash) { setState('Bad request: no info hash in this URL.'); return; }
                fetch('/api/crawler/torrent/info?hash=' + encodeURIComponent(hash))
                  .then(function (r) { return r.json(); })
                  .then(function (d) {
                    if (!d || !d.ok) { infoError(d && d.error); return; }
                    renderInfo(d.payload);
                  })
                  .catch(function () { infoError('unavailable'); });
              }

              function setSentinel(text) { el('sentinel').textContent = text; }

              function loadFiles() {
                if (fileLoading || !fileHasMore) return;
                fileLoading = true;
                var gen = fileGen;
                var url = '/api/crawler/torrent/files?hash=' + encodeURIComponent(hash) +
                  '&offset=' + fileOffset + '&limit=' + FILE_BATCH;
                if (fileQuery) url += '&q=' + encodeURIComponent(fileQuery);
                fetch(url)
                  .then(function (r) { return r.json(); })
                  .then(function (j) {
                    if (gen !== fileGen) return;  // a reset replaced this request; drop the stale response
                    fileLoading = false;
                    if (!j || !j.ok) { fileHasMore = false; setSentinel('Failed to load files'); return; }
                    var payload = j.payload || {};
                    var files = payload.files || [];
                    var list = el('file-list');
                    for (var i = 0; i < files.length; i++) {
                      var row = document.createElement('li');
                      var path = document.createElement('span');
                      path.className = 'file-path';
                      path.textContent = files[i].path != null ? String(files[i].path) : '';
                      var size = document.createElement('span');
                      size.className = 'file-size';
                      size.textContent = fmtSize(files[i].size_bytes);
                      row.appendChild(path);
                      row.appendChild(size);
                      list.appendChild(row);
                    }
                    fileOffset += files.length;
                    fileHasMore = !!payload.has_more;
                    var total = typeof payload.total === 'number' ? payload.total : fileOffset;
                    setSentinel(fileHasMore ? 'Scroll for more\u2026' : 'No more files (' + total + ' total)');
                  })
                  .catch(function () {
                    if (gen !== fileGen) return;
                    fileLoading = false;
                    fileHasMore = false;
                    setSentinel('Failed to load files');
                  });
              }

              function resetFiles() {
                fileGen++;
                fileLoading = false;
                fileOffset = 0;
                fileHasMore = true;
                el('file-list').textContent = '';
                setSentinel('Loading\u2026');
                loadFiles();
              }

              // The add outcome map is the /search page's, except that a
              // space refusal carries the two byte counts (C-page parity).
              function addMessage(d) {
                if (!d) return 'Add failed';
                if (d.ok) return d.duplicate ? 'Already added (duplicate)' : 'Added to Transmission';
                if (d.error === 'insufficient_space') {
                  return 'Not enough free space (torrent ' + fmtGiB(d.sizeBytes) +
                    ', free ' + fmtGiB(d.freeBytes) + ')';
                }
                var map = {
                  bad_hash: 'Bad info hash',
                  db_unavailable: 'Crawler DB unavailable',
                  not_found: 'Torrent not found in the crawler DB',
                  no_storage: 'No storage configured',
                  rpc_failed: 'Transmission RPC error'
                };
                return map[d.error] || 'Add failed';
              }

              el('back').onclick = function (ev) {
                if (history.length > 1) { ev.preventDefault(); history.back(); }
              };

              el('imdb-btn').onclick = function () {
                var a = document.createElement('a');
                a.href = 'https://www.imdb.com/find/?q=' + encodeURIComponent(selectedWords().join(' '));
                a.target = '_blank';
                a.rel = 'noopener noreferrer';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
              };

              el('dht-btn').onclick = function () {
                var q = selectedWords().join(' ');
                if (!q) { toast('Select at least one keyword', true); return; }
                location.href = '/search?q=' + encodeURIComponent(q) + tokenSuffix();
              };

              el('refresh-btn').onclick = function () {
                var btn = el('refresh-btn');
                if (btn.disabled) return;
                btn.disabled = true;
                var peersBox = el('peer-info');
                peersBox.textContent = 'Refreshing\u2026';
                // The route holds the connection up to 30 s; give up before it.
                var ctrl = new AbortController();
                var timer = setTimeout(function () { ctrl.abort(); }, 25000);
                fetch('/api/crawler/refresh?hash=' + encodeURIComponent(hash), { signal: ctrl.signal })
                  .then(function (r) { return r.json(); })
                  .then(function (j) {
                    clearTimeout(timer);
                    var payload = j && j.ok ? j.payload : null;
                    if (payload && typeof payload.total_peers === 'number') {
                      var n = payload.total_peers;
                      peersBox.textContent = n + (n === 1 ? ' peer' : ' peers');
                    } else {
                      peersBox.textContent = 'Refresh failed';
                    }
                    btn.disabled = false;
                  })
                  .catch(function () {
                    clearTimeout(timer);
                    peersBox.textContent = 'Refresh failed';
                    btn.disabled = false;
                  });
              };

              el('add-btn').onclick = function () {
                var btn = el('add-btn');
                if (btn.disabled) return;
                btn.disabled = true;
                // The control_token cookie authenticates this POST (as on /search).
                fetch('/api/transmission/add?hash=' + encodeURIComponent(hash), { method: 'POST' })
                  .then(function (r) { return r.json().catch(function () { return null; }); })
                  .then(function (d) {
                    btn.disabled = false;
                    toast(addMessage(d), !(d && d.ok));
                  })
                  .catch(function () {
                    btn.disabled = false;
                    toast('Add failed', true);
                  });
              };

              el('file-filter').oninput = function () {
                if (filterTimer) clearTimeout(filterTimer);
                filterTimer = setTimeout(function () {
                  fileQuery = el('file-filter').value.trim();
                  resetFiles();
                }, 250);
              };

              if (typeof window.IntersectionObserver === 'function') {
                var observer = new IntersectionObserver(function (entries) {
                  for (var i = 0; i < entries.length; i++) {
                    if (entries[i].isIntersecting) loadFiles();
                  }
                }, { rootMargin: '150px' });
                observer.observe(el('sentinel'));
              }

              loadInfo();
            })();
        """.trimIndent()

        /**
         * The /stats page (webui stage 2): a live view of the crawler's
         * /stats JSON, pushed over the stats websocket (no polling). All
         * markup, CSS and JS are inline — no external assets. Every
         * object/array renders as a collapsible section keyed by a stable
         * path ("supervisor", "trees.2", "trees.2.queues"); the user's
         * expand/collapse choices live in the page's `openOverrides` map
         * and survive every re-render. The `running:false` fallback payload
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
            </div>
            <div id="banner" class="banner stop">Waiting for the first stats push…</div>
            <div id="tree"></div>
            <script>
            $WEBSOCKET_CLIENT_JS
            (function () {
              var lastData = null;
              var lastFrameAt = null;
              // Explicit expand/collapse choices keyed by a stable section
              // path ("supervisor", "trees.2", "trees.2.queues"); they
              // survive every re-render because render() re-reads them.
              var openOverrides = {};

              function el(id) { return document.getElementById(id); }

              // The socket (URL, capped backoff, reconnect, live chip) is the
              // shared client; this page owns only its frame handling and its
              // render() below. openOverrides survives every re-render.
              function connect() {
                WS.connectSocket('/stats/ws', {
                  onopen: function () {
                    WS.setLive('ok', 'live');
                  },
                  onmessage: function (ev) {
                    var data = null;
                    try { data = JSON.parse(ev.data); } catch (e) { return; }
                    lastData = data;
                    lastFrameAt = new Date();
                    render();
                  },
                  onclose: function (backoff) {
                    WS.setLive('bad',
                        'reconnecting in ' + Math.round(backoff / 1000) + 's…');
                  }
                });
              }

              function fmtScalar(v) {
                if (v === null || v === undefined) return '—';
                if (typeof v === 'number') return String(Math.round(v * 10000) / 10000);
                if (typeof v === 'boolean') return v ? 'yes' : 'no';
                return String(v);
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
                return '<div class="row"><span class="k">' + WS.esc(String(key)) + '</span>' +
                    '<span class="v">' + WS.esc(fmtScalar(value)) + '</span></div>';
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
                var html = '<section><div class="head" data-path="' + WS.esc(path) + '">' +
                    '<span class="tri">' + (open ? '\u25be' : '\u25b8') + '</span>' + WS.esc(title) + '</div>';
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

        /**
         * Decodes a form body keeping every value of a repeated key (checkbox
         * groups post one value per checked box). Otherwise identical to
         * [parseForm]: `+` stays literal, undecodable pairs are dropped.
         */
        internal fun parseFormMulti(body: String): Map<String, List<String>> {
            if (body.isEmpty()) return emptyMap()
            return body.split("&").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null
                else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                     URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.groupBy({ it.first }, { it.second })
        }

        /**
         * Collapses a multi-value form map to one value per key (last wins, like
         * the old `toMap()`), for the single-value controls.
         */
        internal fun collapseForm(multi: Map<String, List<String>>): Map<String, String> =
            multi.mapValues { it.value.last() }

        internal fun parseForm(body: String): Map<String, String> =
            collapseForm(parseFormMulti(body))
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

        /** One crawler-settings form parse outcome (stage 4). */
        internal sealed interface CrawlerSettingsFormResult {
            data class Ok(val settings: CrawlerSettings) : CrawlerSettingsFormResult

            /** The form was rejected; [message] is shown in the panel's error banner. */
            data class Bad(val message: String) : CrawlerSettingsFormResult
        }

        /** One validated taxonomy multi-select: the picked names in taxonomy order. */
        private sealed interface TaxonomySelection {
            data class Ok(val names: List<String>) : TaxonomySelection
            data class Bad(val message: String) : TaxonomySelection
        }

        /**
         * Validates one taxonomy multi-select parameter ([values] is every posted
         * value of the field, or null when the field was not posted at all) and
         * returns the selection in [allowed]'s order. Values are trimmed +
         * lowercased first; a value outside [allowed] rejects the whole form.
         */
        private fun taxonomySelection(
            label: String,
            allowed: List<String>,
            values: List<String>?,
        ): TaxonomySelection {
            val picked = values.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val unknown = picked.firstOrNull { it !in allowed }
            if (unknown != null)
                return TaxonomySelection.Bad(
                    "unknown $label '$unknown' — not one of ${allowed.joinToString(", ")}")
            return TaxonomySelection.Ok(allowed.filter { it in picked })
        }

        /**
         * Builds CrawlerSettings from the settings form (checkbox presence =
         * true, blank/missing numerics fall back to [current]). Pure and
         * unit-testable; the POST route redirects with the message on [Bad].
         *
         * The multi-select taxonomies are validated here rather than in
         * ConfigValidator because "nothing selected" is a *form* mistake, not an
         * invalid config: an empty list legitimately means "save everything".
         * `saveCategories` is required — the fieldset is always rendered on the
         * only page that posts this form, so an absent key can only mean the
         * user unticked every box, which would purge the whole database.
         * `saveTvKinds` stays optional (empty = keep every kind; CrawlerConfig
         * then omits the key).
         */
        internal fun crawlerSettingsFromForm(
            form: Map<String, List<String>>,
            current: CrawlerSettings,
        ): CrawlerSettingsFormResult {
            val categories = when (
                val r = taxonomySelection("category", CrawlerTaxonomy.CATEGORIES, form["saveCategories"])
            ) {
                is TaxonomySelection.Bad -> return CrawlerSettingsFormResult.Bad(r.message)
                is TaxonomySelection.Ok -> r.names
            }
            if (categories.isEmpty()) return CrawlerSettingsFormResult.Bad(
                "tick at least one category to save — an empty selection would drop every torrent and " +
                    "delete the whole database when purged")
            val tvKinds = when (
                val r = taxonomySelection("TV kind", CrawlerTaxonomy.TV_KINDS, form["saveTvKinds"])
            ) {
                is TaxonomySelection.Bad -> return CrawlerSettingsFormResult.Bad(r.message)
                is TaxonomySelection.Ok -> r.names
            }
            val f = collapseForm(form)
            return CrawlerSettingsFormResult.Ok(CrawlerSettings(
                enabled = f.containsKey("enabled"),
                wifiOnly = f.containsKey("wifiOnly"),
                logLevel = f["logLevel"]?.takeIf { it.isNotEmpty() } ?: current.logLevel,
                dhtPort = f["dhtPort"]?.toIntOrNull() ?: current.dhtPort,
                niceLevel = f["niceLevel"]?.toIntOrNull() ?: current.niceLevel,
                numTrees = f["numTrees"]?.toIntOrNull() ?: current.numTrees,
                treeFindNodeWorkers = f["treeFindNodeWorkers"]?.toIntOrNull() ?: current.treeFindNodeWorkers,
                treeBep51Workers = f["treeBep51Workers"]?.toIntOrNull() ?: current.treeBep51Workers,
                treeGetPeersWorkers = f["treeGetPeersWorkers"]?.toIntOrNull() ?: current.treeGetPeersWorkers,
                treeMetadataWorkers = f["treeMetadataWorkers"]?.toIntOrNull() ?: current.treeMetadataWorkers,
                treeInfohashQueueCapacity = f["treeInfohashQueueCapacity"]?.toIntOrNull() ?: current.treeInfohashQueueCapacity,
                treePeersQueueCapacity = f["treePeersQueueCapacity"]?.toIntOrNull() ?: current.treePeersQueueCapacity,
                saveCategories = categories,
                saveTvKinds = tvKinds,
                languageFilterNonLatinThreshold = f["languageFilterNonLatinThreshold"]?.toIntOrNull() ?: current.languageFilterNonLatinThreshold,
                classifierEnabled = f.containsKey("classifierEnabled"),
                adultMode = f["adultMode"]?.takeIf { it.isNotEmpty() }?.trim()?.lowercase() ?: current.adultMode,
                adultHideThreshold = f["adultHideThreshold"]?.toDoubleOrNull() ?: current.adultHideThreshold,
                bloomCapacity = f["bloomCapacity"]?.toIntOrNull() ?: current.bloomCapacity,
                failureBloomCapacity = f["failureBloomCapacity"]?.toIntOrNull() ?: current.failureBloomCapacity,
                bloomErrorRate = f["bloomErrorRate"]?.toDoubleOrNull() ?: current.bloomErrorRate,
                advancedOverrides = f["advancedOverrides"] ?: current.advancedOverrides,
                proxyPort = f["proxyPort"]?.toIntOrNull() ?: current.proxyPort,
                crawlerHttpPort = f["crawlerHttpPort"]?.toIntOrNull() ?: current.crawlerHttpPort,
                lanDirect = f.containsKey("lanDirect"),
            ))
        }

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
