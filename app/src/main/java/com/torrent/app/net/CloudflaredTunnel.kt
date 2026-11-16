package com.torrent.app.net

/**
 * Stage 4: the tunnel's *connected* state, as published by the `cloudflared`
 * watcher in `DaemonService` and consumed by the foreground notification, the
 * QR main screen and `getCloudflaredStatus()`.
 *
 * [connected] is the tunnel state from the child's metrics server
 * (`GET http://127.0.0.1:20242/ready` answering 200) — the process can be alive
 * with no edge connection registered (bad token, blocked egress). [publicHostname]
 * is the hostname the tunnel serves, auto-detected while connected; while not
 * connected it carries the operator-stored value so the UI never has to special
 * case a blank field.
 *
 * The tunnel token is deliberately absent: this state reaches the activity and
 * the notification, and the token is only ever appended where it is needed.
 */
data class CloudflaredTunnelState(
    val connected: Boolean = false,
    val publicHostname: String = "",
)

/** One parsed `/ready` body: ready means HTTP 200 *and* `"status":200`. */
data class TunnelProbe(val ready: Boolean, val readyConnections: Int)

private val READY_STATUS_RE = Regex("\"status\"\\s*:\\s*(\\d+)")
private val READY_CONNECTIONS_RE = Regex("\"readyConnections\"\\s*:\\s*(\\d+)")

/**
 * Parses the metrics server's `/ready` body — `{"status":200,"readyConnections":N,…}`
 * — without pulling in a JSON dependency (this file must stay plain-JUnit
 * testable; `org.json` only exists under Robolectric/Android).
 *
 * Returns null when the body carries no `status` field at all, i.e. it is not a
 * `/ready` response (a 404 page, an empty body, the `/config` payload).
 */
fun parseReady(body: String): TunnelProbe? {
    val status = READY_STATUS_RE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val connections = READY_CONNECTIONS_RE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return TunnelProbe(ready = status == 200, readyConnections = connections)
}

/**
 * The connection URL — without the control token, which the caller appends — or
 * null when no usable one exists.
 *
 * The https form wins while the tunnel is connected *and* its hostname is known;
 * the LAN form is the fallback, itself conditional on a known wifi IP. When both
 * are missing — the cellular flagship case: tunnel connected, hostname never
 * detected, no wifi — the result is null, so the caller hides the link and the
 * QR instead of rendering a dead `http://:8090/?token=…` URL.
 *
 * Pure and Android-free on purpose (plain-JUnit testable, house style).
 */
fun connectionUrl(tunnelConnected: Boolean, publicHostname: String, wifiIp: String): String? = when {
    tunnelConnected && publicHostname.isNotEmpty() -> "https://$publicHostname"
    wifiIp.isNotEmpty() -> "http://$wifiIp:8090"
    else -> null
}

/**
 * Matches a `hostname` key/value pair in both the escaped and the unescaped form:
 * `\"hostname\":\"host\"` (inside the quoted `config="…"` log payload) and
 * `"hostname":"host"` (raw JSON from `GET /config`).
 */
private val HOSTNAME_RE = Regex("""\\?"hostname\\?"\s*:\s*\\?"([^"\\]+)\\?""")

/**
 * Extracts the first `hostname` value from [line].
 *
 * The primary source is cloudflared's own
 * `INF Updated to new configuration config="{\"ingress\":[{\"hostname\":\"…\"…}]}" version=N`
 * line, which only appears once an edge connection registered — that is why it is
 * also a de-facto "the tunnel is configured" signal. The hostname is never
 * hardcoded: if neither this nor `/config` yields one, the UI falls back to the
 * operator-entered value.
 */
fun extractHostname(line: String): String? =
    HOSTNAME_RE.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

/**
 * Scans captured log lines newest-first, so a re-configured tunnel's *latest*
 * ingress rule wins over the historical ones still in the ring buffer.
 */
fun hostnameFromLines(lines: List<String>): String? {
    for (i in lines.indices.reversed()) {
        extractHostname(lines[i])?.let { return it }
    }
    return null
}

/**
 * One-shot tunnel probe. Pure apart from the injected [fetch] (house style, see
 * `FtpHangWatchdog`): `DaemonService` passes its `fetchLocalHttp`, so the HTTP
 * GET stays injectable and the whole class is plain-JUnit testable.
 *
 * [probe] is the per-tick unit of work: readiness decides `connected`; the
 * hostname comes from the log lines (primary) and `GET /config` (secondary,
 * wrapper shape unverified upstream — hence secondary). [storedHostname] is
 * carried through unchanged when nothing is detected, so a not-connected tunnel
 * still reports the operator's value.
 */
class CloudflaredTunnel(
    private val fetch: (String) -> Pair<Int, String>?,
    private val readyUrl: String = DEFAULT_READY_URL,
    private val configUrl: String = DEFAULT_CONFIG_URL,
) {
    fun probe(lastLogLines: List<String>, storedHostname: String = ""): CloudflaredTunnelState {
        val ready = fetch(readyUrl)?.let { (code, body) ->
            code == 200 && parseReady(body)?.ready == true
        } ?: false
        if (!ready) return CloudflaredTunnelState(connected = false, publicHostname = storedHostname)
        val detected = hostnameFromLines(lastLogLines) ?: hostnameFromConfig()
        return CloudflaredTunnelState(
            connected = true,
            publicHostname = detected ?: storedHostname,
        )
    }

    /** `GET /config` on the metrics server: the same config JSON, raw (unescaped). */
    fun hostnameFromConfig(): String? = fetch(configUrl)?.let { (code, body) ->
        if (code == 200) extractHostname(body) else null
    }

    companion object {
        const val DEFAULT_READY_URL = "http://127.0.0.1:20242/ready"
        const val DEFAULT_CONFIG_URL = "http://127.0.0.1:20242/config"
    }
}
