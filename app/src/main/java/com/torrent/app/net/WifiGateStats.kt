package com.torrent.app.net

/**
 * Wifi-gate dropout counters (plan/pps-wifi-thermal-envelope.md, Stage 1).
 *
 * Pure and Android-free on purpose: plain-JUnit testable (house style). The clock is
 * always supplied by the caller, so the tests are deterministic.
 *
 * An **edge** is a `true -> false` transition of "the default network is wifi". Repeated
 * `false` samples do not re-count it; the edge is *closed* at the next `true` sample, and
 * only a closed edge whose down duration reached [OVER_2S_MS] counts as a disconnect
 * ([edgesOver2sTotal]). A sub-2 s capability flap lands in [edgesTotal] but never in
 * [edgesOver2sTotal] — that is the plan's definition of WIFI BREAK, and counting flaps
 * would fail a pps rung on noise.
 *
 * The app process keeps running across crawler restarts, so these counters are the one
 * instrument that survives a settings apply (every crawler counter is zeroed by the
 * restart). They reset on app restart, so a window baseline is taken per window.
 */
class WifiGateStats {

    /** One instant's reading. [downNowMs] is the still-open edge's age (0 when up). */
    data class Snapshot(
        val edgesTotal: Long,
        val edgesOver2sTotal: Long,
        val downMsTotal: Long,
        val maxDownMs: Long,
        val downNowMs: Long,
        val lastDropoutAtMs: Long?,
    ) {
        companion object {
            val EMPTY = Snapshot(0L, 0L, 0L, 0L, 0L, null)
        }
    }

    /** Every `true -> false` transition, including sub-2 s flaps. */
    var edgesTotal: Long = 0L
        private set

    /** Closed edges whose down duration reached [OVER_2S_MS] — the disconnect count. */
    var edgesOver2sTotal: Long = 0L
        private set

    /** Summed duration of the *closed* edges; an open edge is [Snapshot.downNowMs]. */
    var downMsTotal: Long = 0L
        private set

    var maxDownMs: Long = 0L
        private set

    var lastDropoutAtMs: Long? = null
        private set

    /** When the currently open edge began, or null while the gate is up. */
    var downSinceMs: Long? = null
        private set

    /** Set by [noteDeliberateStop]: the next `false` sample is shutdown, not a dropout. */
    private var suppressNextDown = false

    fun onSample(wifi: Boolean, nowMs: Long) {
        if (wifi) {
            close(nowMs)
            suppressNextDown = false
            return
        }
        if (downSinceMs != null) return            // repeated false: same edge
        if (suppressNextDown) {                    // the stop() that followed
            suppressNextDown = false
            return
        }
        edgesTotal++
        downSinceMs = nowMs
        lastDropoutAtMs = nowMs
    }

    /**
     * A deliberate shutdown is not a dropout: close any open edge as usual and ignore the
     * single `false` sample that [WifiGateMonitor.stop] publishes right after this call.
     *
     * Known limitation: if the gate really is down at that instant, that one edge is not
     * counted (observation ends with the process anyway). [onSample] with `true` clears the
     * suppression, so a restarted monitor counts normally.
     */
    fun noteDeliberateStop(nowMs: Long) {
        close(nowMs)
        suppressNextDown = true
    }

    fun snapshot(nowMs: Long): Snapshot = Snapshot(
        edgesTotal = edgesTotal,
        edgesOver2sTotal = edgesOver2sTotal,
        downMsTotal = downMsTotal,
        maxDownMs = maxDownMs,
        downNowMs = downSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L,
        lastDropoutAtMs = lastDropoutAtMs,
    )

    private fun close(nowMs: Long) {
        val since = downSinceMs ?: return
        val down = (nowMs - since).coerceAtLeast(0L)
        downSinceMs = null
        downMsTotal += down
        if (down > maxDownMs) maxDownMs = down
        if (down >= OVER_2S_MS) edgesOver2sTotal++
    }

    companion object {
        /** A gate-down edge this long is a disconnect, not a capability flap. */
        const val OVER_2S_MS = 2_000L
    }
}
