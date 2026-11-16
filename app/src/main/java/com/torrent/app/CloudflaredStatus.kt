package com.torrent.app

/**
 * Snapshot of the supervised `cloudflared` tunnel child (Stage 3) for the
 * control UI and `/api/status` (mirrors [com.torrent.app.ftp.FtpStatus]).
 *
 * [state] is the *process* state name (`STOPPED` | `STARTING` | `RUNNING` |
 * `STOPPING`) while [connected] is the *tunnel* state from the child's
 * `GET http://127.0.0.1:20242/ready` probe: the process can be alive with no
 * edge connection registered (bad token, blocked egress), and only /ready
 * distinguishes those cases. The JSON renderings lowercase [state] to match the
 * rest of the tunnel block.
 *
 * [publicHostname] is the bare hostname the tunnel serves — auto-detected while
 * connected (Stage 4) or operator-edited; blank until it is known. The tunnel
 * token is deliberately absent: this snapshot is rendered into `/api/status`
 * and dashboard frames.
 */
data class CloudflaredStatus(
    val enabled: Boolean = false,
    val state: String = "STOPPED",
    val connected: Boolean = false,
    val publicHostname: String = "",
    val readyConnections: Int = 0,
    /** Why the tunnel is not connected while the child runs; null when it is. */
    val lastError: String? = null,
)
