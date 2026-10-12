package com.torrent.app.net

import android.net.NetworkCapabilities

/** Pure mapping from network transports to "is the default network wifi".
 *  Extracted from [WifiGateMonitor] for unit testing (ConnectivityManager
 *  itself is not unit-testable without Android). */
object WifiGateReducer {
    fun isWifi(transports: Set<Int>): Boolean =
        NetworkCapabilities.TRANSPORT_WIFI in transports
}
