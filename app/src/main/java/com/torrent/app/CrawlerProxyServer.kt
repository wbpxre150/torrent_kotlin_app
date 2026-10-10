package com.torrent.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.writeFully
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Authenticated reverse proxy in front of the dht_crawler search UI (plan 10 §3).
 *
 * The crawler itself listens on `127.0.0.1:<crawlerHttpPort>` with no auth; this
 * server binds `0.0.0.0:<port>` (the configured proxyPort), requires a valid
 * control token and streams every request through to the crawler using
 * [HttpURLConnection] (no new dependencies). Auth reuses
 * [ControlServer.resolveAuthToken]: the `control_token` cookie from the :8090
 * control server works here because browsers send cookies regardless of port;
 * `?token=` (which sets the cookie) and Basic/Bearer are fallbacks.
 *
 * The crawler's own `http_bind` is 127.0.0.1, so port 8081 is not reachable
 * from the LAN. When `lanDirect` is set the crawler binds the LAN itself and
 * [DaemonService] does not start this proxy at all.
 */
class CrawlerProxyServer(
    private val tokenManager: TokenManager,
    /** Target loopback port, read live so crawlerHttpPort changes need no restart. */
    private val crawlerHttpPort: () -> Int,
    val port: Int,
    private val controlPort: Int = DEFAULT_CONTROL_PORT,
) {
    @Volatile private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        try {
            server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                routing {
                    handle {
                        val (token, setCookie) = ControlServer.resolveAuthToken(
                            call.request.headers["Cookie"],
                            call.request.queryParameters["token"],
                            call.request.headers["Authorization"],
                            tokenManager::validate,
                        )
                        if (token == null) {
                            val host = call.request.headers[HttpHeaders.Host]
                                ?.substringBefore(':') ?: "127.0.0.1"
                            call.response.headers.append(
                                HttpHeaders.WWWAuthenticate, "Basic realm=\"dht_crawler\"")
                            call.respondText(
                                unauthorizedPage("http://$host:$controlPort/login"),
                                ContentType.Text.Html,
                                HttpStatusCode.Unauthorized,
                            )
                            return@handle
                        }
                        if (setCookie) {
                            call.response.headers.append(
                                "Set-Cookie",
                                "control_token=$token; Path=/; HttpOnly; SameSite=Strict")
                        }
                        forwardToCrawler(call)
                    }
                }
            }
            server?.start(wait = false)
        } catch (e: Exception) {
            // A busy port must not crash the service; the next sync/apply retries.
            server = null
            android.util.Log.e(TAG, "crawler proxy failed to bind port $port", e)
        }
    }

    fun stop() { server?.stop(1000, 2000); server = null }

    /**
     * Forwards the request (any method/path/query/body) to the crawler on
     * loopback and streams the response back in 8 KiB chunks — search pages
     * can be large and /refresh can take minutes, so nothing is buffered
     * whole and the read timeout is [READ_TIMEOUT_MS].
     */
    private suspend fun forwardToCrawler(call: ApplicationCall) {
        // `local.uri` is the encoded request target (`/path?query`); the
        // `token` query param is stripped before forwarding.
        val uri = call.request.local.uri
        val path = uri.substringBefore('?')
        val query = stripTokenFromQuery(uri.substringAfter('?', ""))
        val target = "http://127.0.0.1:${crawlerHttpPort()}$path" +
            (if (query.isNotEmpty()) "?$query" else "")

        val conn = try {
            (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = call.request.local.method.value.uppercase()
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doInput = true
            }
        } catch (e: Exception) {
            return respondBadGateway(call, e)
        }
        try {
            for ((name, values) in filteredRequestHeaders(call.request.headers).entries()) {
                for (v in values) conn.setRequestProperty(name, v)
            }
            // Stream the request body through (GET/HEAD simply read EOF).
            val body = call.receiveStream()
            call.request.contentLength()?.takeIf { it > 0 }
                ?.let { conn.setRequestProperty("Content-Length", it.toString()) }
            conn.doOutput = true
            conn.outputStream.use { out ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }

            val code = conn.responseCode // sends the request
            val status = HttpStatusCode.fromValue(code)
                ?: HttpStatusCode.InternalServerError
            val bodyStream = (if (code >= 400) conn.errorStream else conn.inputStream)
            val contentType = runCatching { ContentType.parse(conn.contentType ?: "") }
                .getOrNull() ?: ContentType.Application.OctetStream
            for ((name, values) in conn.headerFields) {
                if (name == null) continue // status line
                if (isHopByHop(name) || name.equals("Content-Length", true)) continue
                for (v in values) call.response.headers.append(name, v)
            }
            // The channel is the lambda receiver; stream the upstream body in
            // 8 KiB chunks without buffering the whole response.
            call.respondBytesWriter(contentType, status) {
                bodyStream?.use { input ->
                    val buf = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        writeFully(buf, 0, n)
                    }
                }
                flushAndClose()
            }
        } catch (e: IOException) {
            respondBadGateway(call, e)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun respondBadGateway(call: ApplicationCall, cause: Exception) {
        android.util.Log.w(TAG, "crawler proxy: upstream failure: ${cause.message}")
        val host = call.request.headers[HttpHeaders.Host]?.substringBefore(':') ?: "127.0.0.1"
        call.respondText(
            badGatewayPage(cause.message ?: "no response", "http://$host:$controlPort/"),
            ContentType.Text.Html,
            HttpStatusCode.BadGateway,
        )
    }

    companion object {
        private const val TAG = "CrawlerProxyServer"
        internal const val DEFAULT_CONTROL_PORT = 8090

        /** Loopback connect timeout: the crawler is local, fail fast when down. */
        internal const val CONNECT_TIMEOUT_MS = 2_000
        /** Read timeout: covers /refresh (multi-second DHT waits with retries). */
        internal const val READ_TIMEOUT_MS = 300_000
        private const val COPY_BUFFER_BYTES = 8192

        /** RFC 7230 §6.1 hop-by-hop headers; never forwarded in either direction. */
        internal val HOP_BY_HOP_HEADERS = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
        )

        internal fun isHopByHop(name: String): Boolean =
            HOP_BY_HOP_HEADERS.contains(name.lowercase())

        /**
         * Request headers to forward to the crawler: drops Host, hop-by-hop
         * headers and Authorization (the control token must not reach the
         * crawler) and strips control_token from the Cookie header.
         */
        internal fun filteredRequestHeaders(headers: Headers): Headers {
            val out = HeadersBuilder()
            for ((name, values) in headers.entries()) {
                if (name.equals("Host", true)) continue
                if (isHopByHop(name)) continue
                if (name.equals("Authorization", true)) continue
                if (name.equals("Cookie", true)) {
                    val cleaned = values.joinToString("; ")
                        .split(';')
                        .map { it.trim() }
                        .filter {
                            it.isNotEmpty() &&
                                !it.substringBefore('=').trim().equals("control_token", true)
                        }
                        .joinToString("; ")
                    if (cleaned.isNotEmpty()) out.appendAll(name, listOf(cleaned))
                } else {
                    out.appendAll(name, values)
                }
            }
            return out.build()
        }

        /** Removes the `token` param from a raw query string, preserving the rest in order. */
        internal fun stripTokenFromQuery(query: String): String {
            if (query.isEmpty()) return ""
            return query.split('&')
                .filterNot { part ->
                    part.substringBefore('=', part).equals("token", true)
                }
                .joinToString("&")
        }

        internal fun unauthorizedPage(loginUrl: String): String = """
            <!DOCTYPE html><html><head><title>dht_crawler — authentication required</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:system-ui,sans-serif;max-width:480px;margin:2em auto;padding:0 1em}
            .box{background:#fff3cd;border:1px solid #ffeeba;border-radius:6px;padding:1em}</style></head>
            <body><h1>Authentication required</h1>
            <div class="box"><p>This port serves the <strong>dht_crawler</strong> search UI and
            needs a valid control token. The <code>control_token</code> cookie from the
            control server is accepted here too.</p>
            <p><a href="$loginUrl">Log in on the control server</a></p></div>
            </body></html>
        """.trimIndent()

        internal fun badGatewayPage(detail: String, dashboardUrl: String): String = """
            <!DOCTYPE html><html><head><title>dht_crawler — unavailable</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:system-ui,sans-serif;max-width:480px;margin:2em auto;padding:0 1em}
            .box{background:#f8d7da;border:1px solid #f5c6cb;border-radius:6px;padding:1em}</style></head>
            <body><h1>dht_crawler unavailable</h1>
            <div class="box"><p>The crawler's HTTP interface did not answer ($detail).</p>
            <p>Check the crawler's state on the <a href="$dashboardUrl">control dashboard</a>,
            then retry.</p></div>
            </body></html>
        """.trimIndent()
    }
}
