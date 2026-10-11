package com.torrent.app.config

object TransmissionApplyMode {
    /** Fields transmission's RPC cannot change live; changing any requires a restart. */
    fun needsRestart(before: AppConfig, after: AppConfig): Boolean =
        before.transmission.username != after.transmission.username ||
            before.transmission.password != after.transmission.password ||
            before.transmission.peerPort != after.transmission.peerPort ||
            before.dataSubpath != after.dataSubpath
}