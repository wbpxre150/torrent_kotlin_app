package com.torrent.app.config

object TransmissionApplyMode {
    /** Fields transmission's RPC cannot change live; changing any requires a restart. */
    fun needsRestart(before: TransmissionSettings, after: TransmissionSettings): Boolean =
        before.username != after.username ||
        before.password != after.password ||
        before.peerPort != after.peerPort ||
        before.downloadSubpath != after.downloadSubpath
}