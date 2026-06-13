package com.torrent.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.StateFlow

class ControlServer(
    private val tokenManager: TokenManager,
    private val serviceState: StateFlow<ServiceState>,
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
        return """
            <!DOCTYPE html>
            <html>
            <head><title>Torrent App — Control</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { font-family: system-ui, sans-serif; max-width: 600px; margin: 2em auto; padding: 0 1em; }
                .state { padding: 8px 16px; border-radius: 4px; display: inline-block; }
                .Running { background: #d4edda; color: #155724; }
                .Starting { background: #fff3cd; color: #856404; }
                .Stopping { background: #f8d7da; color: #721c24; }
                .Stopped { background: #e2e3e5; color: #383d41; }
            </style></head>
            <body>
                <h1>Torrent App Control</h1>
                <p>Service: <span class="state $state">$state</span></p>
                <h2>Daemons</h2>
                <p><em>No daemons configured yet. Stages 2+ of the original plan will add them here.</em></p>
            </body>
            </html>
        """.trimIndent()
    }
}
